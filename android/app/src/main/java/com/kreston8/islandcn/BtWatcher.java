package com.kreston8.islandcn;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Connected Bluetooth accessories and the battery level they report.
 *
 * <p>Android has no public battery API for accessories, so a level is hunted down in four steps,
 * cheapest first, and the first one that answers wins:
 *
 * <ol>
 *   <li>The hidden {@code BluetoothDevice.getBatteryLevel()} method, which is the same number the
 *       system's own Bluetooth screen prints. It is greylisted, so plenty of ROMs refuse it.</li>
 *   <li>The system's {@code BATTERY_LEVEL_CHANGED} broadcast, sent when a device pushes a level
 *       right after connecting. This is cached and persisted.</li>
 *   <li>A GATT read of the standard Battery Service ({@code 0x180F} / {@code 0x2A19}), plus a
 *       notification subscription on the same characteristic, for LE and dual-mode accessories.</li>
 *   <li>A BLE twin address for classic-only accessories, looked up by name with an eight second LE
 *       scan. TWS earbuds register with the system as A2DP, and their battery service sits on the
 *       BLE side, often under an address the system never reports. The address found is stored as
 *       {@code ble:<classic address>}, so later reads go straight to it and no scan is repeated.</li>
 * </ol>
 *
 * <p>A read costs the accessory a connection, so each device is asked at most once every ten
 * minutes, only one accessory is connected and only one scan runs at a time, and the connection is
 * closed the moment the value arrives (or after nine seconds). Levels are persisted, so a restart
 * does not throw away what the system already told us.
 */
final class BtWatcher extends BroadcastReceiver {

    /** Hidden platform broadcast; there is no public constant for it. */
    private static final String ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    private static final String EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL";

    private static final String PREFS = "island_bt";
    private static final String PREFIX_LEVEL = "lv:";
    private static final String PREFIX_NAME = "nm:";
    /** Classic address to BLE twin address, learned by one scan and never guessed again. */
    private static final String PREFIX_BLE = "ble:";

    /** Battery service and its level characteristic, the only standard way to ask a device. */
    private static final UUID SERVICE_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    /** Client Characteristic Configuration, written to subscribe to the level notification. */
    private static final UUID DESCRIPTOR_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** A read opens a connection to the accessory, so it is not repeated more often than this. */
    private static final long ASK_GAP_MS = 10L * 60L * 1000L;
    private static final long ASK_TIMEOUT_MS = 9000L;
    /** A scan for the BLE twin of a classic headset; it is stopped on the first name match. */
    private static final long SCAN_TIMEOUT_MS = 8000L;

    private final Map<String, Integer> levels = new LinkedHashMap<>();
    private final Map<String, String> names = new LinkedHashMap<>();
    /** When each address was last asked over GATT, so a silent device is not asked every redraw. */
    private final Map<String, Long> askedAt = new LinkedHashMap<>();
    /** Classic address to the BLE address the battery service was found on. */
    private final Map<String, String> bleOf = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable onLevel;
    private boolean loaded;
    /** True while a name scan runs, so two classic accessories never scan at the same time. */
    private volatile boolean scanning;

    /** Called after a level arrives, so the chips on screen can be redrawn without a new card. */
    void levelListener(Runnable listener) {
        this.onLevel = listener;
    }

    void start(Context context) {
        load(context);
        IntentFilter filter = new IntentFilter(ACTION_BATTERY);
        try {
            Context app = context.getApplicationContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // A protected system broadcast: exported is correct, and unlike the alarm receiver
                // this one does not require the not-exported variant.
                app.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(this, filter);
            }
        } catch (Throwable ignored) {
            // Registration can fail in a restricted profile; the chips then show icons only.
        }
        // Warm the device list through the public profile proxies; the answer is cached.
        refreshLinked(context);
    }

    void stop(Context context) {
        handler.removeCallbacksAndMessages(null);
        try {
            context.getApplicationContext().unregisterReceiver(this);
        } catch (Throwable ignored) {
            // Not registered, or the process is going away.
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        BluetoothDevice device = null;
        try {
            device = (BluetoothDevice) intent.getParcelableExtra(EXTRA_DEVICE);
        } catch (Throwable ignored) {
            // Malformed extra from a vendor stack.
        }
        int level = intent.getIntExtra(EXTRA_LEVEL, -1);
        if (device == null || level < 0) {
            return;
        }
        load(context);
        String address = addressOf(device);
        if (address.isEmpty()) {
            return;
        }
        remember(context, device, address, level);
    }

    /**
     * One entry per connected accessory that is switched on, or null when there is nothing to show.
     */
    List<IslandEvent.Bt> chips(Context context) {
        return build(context, true);
    }

    /**
     * The chips as they stand right now, for the standby pill.
     *
     * <p>No connection is opened here: the pill redraws every four seconds and a GATT read costs the
     * accessory a link. The platform's own number and the last cached one are used instead, so a
     * device that never answered simply shows its icon.
     */
    static List<IslandEvent.Bt> peek(Context context) {
        BtWatcher watcher = new BtWatcher();
        watcher.load(context);
        return watcher.build(context, false);
    }

    /** Last list the profile proxies reported: BLE/classic address -> display name. */
    private static final Map<String, String> LINKED = new LinkedHashMap<String, String>();
    private static long linkedAt;
    /** When the sweep below started, so a proxy that never answers cannot wedge it forever. */
    private static long linkingSince;
    private static boolean linking;

    /**
     * Refreshes {@link #LINKED} through the public profile proxies.
     *
     * <p>The hidden {@code BluetoothManager.getConnectedDevices(int)} is what this class used to
     * call, and Android 12+ is free to refuse it — which is why the device list came back empty on
     * a phone that was plainly playing music over A2DP. The proxies below are the supported way in,
     * and they answer on the main thread a moment later, so the answer is cached.
     */
    static void refreshLinked(Context context) {
        if (linking) {
            if (linkingSince != 0L
                    && android.os.SystemClock.elapsedRealtime() - linkingSince > 15000L) {
                // A callback never came back: start over instead of staying empty for good.
                linking = false;
            } else {
                return;
            }
        }
        if (linkedAt != 0L && android.os.SystemClock.elapsedRealtime() - linkedAt < 8000L) {
            return;
        }
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        BluetoothAdapter adapter;
        try {
            BluetoothManager manager =
                    (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = manager == null ? null : manager.getAdapter();
        } catch (Throwable failure) {
            adapter = null;
        }
        if (adapter == null) {
            return;
        }
        final BluetoothAdapter open = adapter;
        boolean started;
        try {
            started = open.isEnabled();
        } catch (Throwable failure) {
            started = false;
        }
        if (!started) {
            return;
        }
        linking = true;
        linkingSince = android.os.SystemClock.elapsedRealtime();
        LINKED.clear();
        askProfile(app, open, new int[]{BluetoothProfile.A2DP, BluetoothProfile.HEADSET,
                BluetoothProfile.GATT}, 0);
    }

    /** Walks the profile list one proxy at a time; each callback asks for the next. */
    private static void askProfile(final Context app, final BluetoothAdapter adapter,
            final int[] profiles, final int index) {
        if (index >= profiles.length) {
            linking = false;
            linkedAt = android.os.SystemClock.elapsedRealtime();
            return;
        }
        final int profile = profiles[index];
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int connected, BluetoothProfile proxy) {
                try {
                    for (BluetoothDevice device : proxy.getConnectedDevices()) {
                        String address = addressOf(device);
                        if (address.isEmpty()) {
                            continue;
                        }
                        LINKED.put(address, deviceName(device));
                    }
                } catch (Throwable ignored) {
                    // Permission refused or the stack is gone; the next profile may still answer.
                }
                try {
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Throwable ignored) {
                    // Already closed.
                }
                askProfile(app, adapter, profiles, index + 1);
            }

            @Override
            public void onServiceDisconnected(int disconnected) {
                askProfile(app, adapter, profiles, index + 1);
            }
        };
        try {
            if (!adapter.getProfileProxy(app, listener, profile)) {
                askProfile(app, adapter, profiles, index + 1);
            }
        } catch (Throwable failure) {
            askProfile(app, adapter, profiles, index + 1);
        }
    }

    /**
     * {@code [address, name]} for every connected accessory, so the settings screen can offer one
     * switch per device. An empty list means no adapter, no permission, or nothing connected.
     *
     * <p>Three sources are merged and each address is reported once: the hidden
     * {@code getConnectedDevices(int)} call, the answer the public profile proxies left in
     * {@link #LINKED}, and a bonded device this class already holds a level for — a level only comes
     * from a live link, so such a device is online even when no profile call answered.
     */
    static List<String[]> connected(Context context) {
        List<String[]> out = new ArrayList<>();
        Map<String, String> found = new LinkedHashMap<>();
        BtWatcher watcher = new BtWatcher();
        BluetoothAdapter adapter = null;
        try {
            watcher.load(context);
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager != null) {
                adapter = manager.getAdapter();
            }
            if (adapter != null && !adapter.isEnabled()) {
                // Bluetooth off: the cached answer is stale, so it must not be shown.
                LINKED.clear();
            } else if (adapter != null) {
                Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
                collect(manager, BluetoothProfile.GATT, devices);
                collect(manager, BluetoothProfile.HEADSET, devices);
                // TWS earbuds often only register A2DP, so skipping this profile hid them entirely.
                collect(manager, BluetoothProfile.A2DP, devices);
                for (BluetoothDevice device : devices.values()) {
                    if (device == null) {
                        continue;
                    }
                    String address = addressOf(device);
                    if (!address.isEmpty() && !found.containsKey(address)) {
                        found.put(address, watcher.nameOf(context, device, address));
                    }
                }
            }
        } catch (Throwable ignored) {
            // No permission or no Bluetooth stack: the settings screen shows its hint instead.
        }
        // Second path: what the public profile proxies last reported, names included.
        try {
            for (Map.Entry<String, String> entry : LINKED.entrySet()) {
                String address = entry.getKey();
                if (address == null || address.isEmpty() || found.containsKey(address)) {
                    continue;
                }
                String name = entry.getValue();
                if (name == null || name.trim().isEmpty()) {
                    BluetoothDevice device = null;
                    try {
                        device = adapter == null ? null : adapter.getRemoteDevice(address);
                    } catch (Throwable failure) {
                        device = null;
                    }
                    name = device == null
                            ? watcher.names.get(address)
                            : watcher.nameOf(context, device, address);
                }
                if (name == null || name.trim().isEmpty()) {
                    name = context.getString(R.string.bt_unknown);
                }
                found.put(address, name);
            }
        } catch (Throwable ignored) {
            // A dying stack only costs the names.
        }
        // Third path: a bonded device with a cached level was online when that level arrived.
        if (adapter != null) {
            try {
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    if (device == null) {
                        continue;
                    }
                    String address = addressOf(device);
                    if (address.isEmpty() || found.containsKey(address)) {
                        continue;
                    }
                    Integer known = watcher.levels.get(address);
                    if (known == null || known < 0) {
                        continue;
                    }
                    found.put(address, watcher.nameOf(context, device, address));
                }
            } catch (Throwable ignored) {
                // The bonded list needs BLUETOOTH_CONNECT on Android 12+ as well.
            }
        }
        for (Map.Entry<String, String> entry : found.entrySet()) {
            out.add(new String[]{entry.getKey(), entry.getValue()});
        }
        // Warms the cache for the next call; it throttles itself to one sweep every eight seconds.
        refreshLinked(context);
        return out;
    }

    /** Per-accessory switch; on unless that one device was turned off. */
    static boolean deviceOn(Context context, String address) {
        return Prefs.get(context, Prefs.KEY_BT_DEVICE + address);
    }

    /**
     * One line per connected accessory saying where its battery figure could come from.
     *
     * <p>The settings screen shows this so an empty chip can be told apart from a missing permission
     * or an accessory that publishes no level at all. Nothing is opened here: the reflection read is
     * free, and a GATT ask would cost the accessory a link.
     */
    public static String probe(Context context) {
        String permissions = "权限 CONNECT="
                + (granted(context, android.Manifest.permission.BLUETOOTH_CONNECT) ? "有" : "无")
                + " SCAN="
                + (granted(context, android.Manifest.permission.BLUETOOTH_SCAN) ? "有" : "无");
        // A diagnostic is also a good moment to warm the proxy cache for the next redraw.
        refreshLinked(context);

        BluetoothManager manager;
        try {
            manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        } catch (Throwable failure) {
            manager = null;
        }
        if (manager == null) {
            return permissions + "\n无蓝牙服务";
        }
        BluetoothAdapter adapter;
        try {
            adapter = manager.getAdapter();
        } catch (Throwable failure) {
            adapter = null;
        }
        if (adapter == null) {
            return permissions + "\n无蓝牙适配器";
        }
        try {
            if (!adapter.isEnabled()) {
                return permissions + "\n蓝牙已关闭";
            }
        } catch (Throwable failure) {
            return permissions + "\n蓝牙状态不可读：缺 BLUETOOTH_CONNECT";
        }
        BtWatcher watcher = new BtWatcher();
        watcher.load(context);
        Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
        Map<String, String> via = new LinkedHashMap<>();
        int[] profiles = {BluetoothProfile.GATT, BluetoothProfile.HEADSET, BluetoothProfile.A2DP};
        String[] labels = {"GATT", "HEADSET", "A2DP"};
        for (int i = 0; i < profiles.length; i++) {
            List<BluetoothDevice> found;
            try {
                found = manager.getConnectedDevices(profiles[i]);
            } catch (Throwable failure) {
                found = null;
            }
            if (found == null) {
                continue;
            }
            for (BluetoothDevice device : found) {
                if (device == null) {
                    continue;
                }
                String address = addressOf(device);
                if (address.isEmpty()) {
                    continue;
                }
                devices.put(address, device);
                String previous = via.get(address);
                via.put(address, previous == null ? labels[i] : previous + "+" + labels[i]);
            }
        }
        if (devices.isEmpty()) {
            return permissions + "\n蓝牙已开启，无已连接设备（代理缓存 " + LINKED.size() + "）"
                    + "\n列表来源 " + (LINKED.isEmpty() ? "隐藏接口/已配对" : "profile 代理");
        }
        StringBuilder out = new StringBuilder(permissions);
        out.append('\n').append("已连接 ").append(devices.size())
                .append(" 台（代理缓存 ").append(LINKED.size()).append("）");
        for (BluetoothDevice device : devices.values()) {
            String address = addressOf(device);
            String name = watcher.nameOf(context, device, address);
            Integer known = watcher.levels.get(address);
            int system = reflectLevel(device);
            String twin = watcher.bleOf.get(address);
            out.append('\n').append(name).append(" | ").append(via.get(address))
                    .append(" | ").append(probeType(device))
                    .append(" | 电量 ").append(known == null || known < 0 ? "无" : known + "%")
                    .append(" | 系统 ").append(system < 0 ? "无" : system + "%")
                    .append(" | BLE 地址 ").append(twin == null || twin.isEmpty() ? "未扫描" : twin);
        }
        out.append("\n列表来源 ")
                .append(LINKED.isEmpty() ? "隐藏接口/已配对" : "profile 代理");
        return out.toString();
    }

    /** True when a runtime Bluetooth permission is held; before Android 12 there were none. */
    private static boolean granted(Context context, String permission) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return true;
            }
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** The connection type as the diagnostics line spells it. */
    private static String probeType(BluetoothDevice device) {
        try {
            switch (device.getType()) {
                case BluetoothDevice.DEVICE_TYPE_LE:
                    return "LE";
                case BluetoothDevice.DEVICE_TYPE_DUAL:
                    return "LE+经典";
                case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                    return "经典";
                default:
                    return "未知";
            }
        } catch (Throwable failure) {
            return "不可读";
        }
    }

    /**
     * One chip per connected accessory that is switched on.
     *
     * <p>{@code GATT} covers watches and bands, {@code HEADSET} covers earbuds; other profiles never
     * carry a battery. Every platform call needs {@code BLUETOOTH_CONNECT} on Android 12+, so each
     * one is guarded instead of crashing the overlay. {@code ask} decides whether a device with no
     * cached level may be opened over GATT.
     */
    private List<IslandEvent.Bt> build(Context context, boolean ask) {
        load(context);
        BluetoothManager manager;
        try {
            manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        } catch (Throwable failure) {
            return null;
        }
        if (manager == null) {
            return null;
        }
        BluetoothAdapter adapter;
        try {
            adapter = manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                return null;
            }
        } catch (Throwable failure) {
            return null;
        }
        Map<String, BluetoothDevice> connected = new LinkedHashMap<>();
        collect(manager, BluetoothProfile.GATT, connected);
        collect(manager, BluetoothProfile.HEADSET, connected);
        collect(manager, BluetoothProfile.A2DP, connected);
        if (connected.isEmpty()) {
            return null;
        }

        // A device that dropped off can be asked again the moment it reconnects.
        pruneAsked(connected);
        List<IslandEvent.Bt> chips = new ArrayList<>();
        for (BluetoothDevice device : connected.values()) {
            if (device == null) {
                continue;
            }
            String address = addressOf(device);
            if (!address.isEmpty() && !deviceOn(context, address)) {
                // Turned off for this accessory: no chip, and no connection opened for it either.
                continue;
            }
            String name = nameOf(context, device, address);
            Integer known = address.isEmpty() ? null : levels.get(address);
            if ((known == null || known < 0) && !address.isEmpty()) {
                int read = reflectLevel(device);
                if (read >= 0) {
                    levels.put(address, read);
                    persist(context, PREFIX_LEVEL + address, String.valueOf(read));
                    known = read;
                }
            }
            if (ask && (known == null || known < 0) && !address.isEmpty()) {
                // Nothing cached, so ask the device itself; the answer arrives later and redraws.
                requestGatt(context, device, address);
            }
            chips.add(new IslandEvent.Bt(name, known == null ? -1 : known, iconOf(name)));
        }
        return chips.isEmpty() ? null : chips;
    }

    private static void collect(BluetoothManager manager, int profile,
                                Map<String, BluetoothDevice> into) {
        try {
            List<BluetoothDevice> devices = manager.getConnectedDevices(profile);
            if (devices == null) {
                return;
            }
            for (BluetoothDevice device : devices) {
                String address = addressOf(device);
                if (device != null && !address.isEmpty()) {
                    into.put(address, device);
                }
            }
        } catch (Throwable ignored) {
            // Missing BLUETOOTH_CONNECT, or a profile the ROM does not expose.
        }
    }

    /**
     * The level the platform already knows, read through a hidden method.
     *
     * <p>This is the number Settings shows, so it is tried before any connection is opened. The
     * method is greylisted: on most ROMs it either throws or answers -1, which is why the GATT
     * fallback exists at all.
     */
    private static int reflectLevel(BluetoothDevice device) {
        try {
            Object value = BluetoothDevice.class.getMethod("getBatteryLevel").invoke(device);
            if (value instanceof Integer) {
                return clamp((Integer) value);
            }
        } catch (Throwable ignored) {
            // Not exposed here, or blocked as a hidden API.
        }
        return -1;
    }

    /**
     * Asks the accessory for its battery service, if it is due.
     *
     * <p>An LE or dual-mode accessory is read on its own address. A classic-only accessory has no
     * GATT server there, so its BLE twin is looked up by name first; a twin found earlier is used
     * straight away and the scan is skipped. The result arrives through {@link #onLevel}.
     */
    private void requestGatt(Context context, BluetoothDevice device, String address) {
        if (address.isEmpty() || !shouldAsk(System.currentTimeMillis(), address)) {
            return;
        }
        int type;
        try {
            type = device.getType();
        } catch (Throwable ignored) {
            return;
        }
        String twin = bleOf.get(address);
        if (twin != null && !twin.isEmpty()) {
            // The LE side of this accessory is already known: connect to it, no scan needed.
            askedAt.put(address, System.currentTimeMillis());
            readBattery(context, address, twin);
            return;
        }
        if (type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL) {
            askedAt.put(address, System.currentTimeMillis());
            readBattery(context, address, address);
            return;
        }
        if (type != BluetoothDevice.DEVICE_TYPE_CLASSIC || scanning) {
            // No usable type, or a lookup is already running: only one scan is ever in flight.
            return;
        }
        askedAt.put(address, System.currentTimeMillis());
        scanTwin(context, device, address);
    }

    /**
     * Reads the standard battery service over GATT, over the LE transport.
     *
     * <p>{@code key} is the address the chip is filed under, which is what the system reports;
     * {@code bleAddress} is the address actually connected to, which differs for a classic headset
     * and its BLE twin. A read and a notification subscription are both started, because some
     * earbuds only push the value; whichever answers first closes the connection.
     */
    private void readBattery(Context context, final String key, String bleAddress) {
        final Context app = context.getApplicationContext();
        BluetoothAdapter adapter = adapterOf(app);
        if (adapter == null) {
            return;
        }
        BluetoothDevice target;
        try {
            target = adapter.getRemoteDevice(bleAddress);
        } catch (Throwable ignored) {
            return;
        }
        if (target == null) {
            return;
        }
        final BluetoothGatt[] open = new BluetoothGatt[1];
        final boolean[] done = new boolean[1];
        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        gatt.discoverServices();
                        return;
                    } catch (Throwable ignored) {
                        // Fall through to the closed connection below.
                    }
                }
                if (newState != BluetoothProfile.STATE_CONNECTED) {
                    finish(app, gatt, open, done, key, -1);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                BluetoothGattCharacteristic characteristic = levelOf(gatt);
                if (characteristic == null) {
                    finish(app, gatt, open, done, key, -1);
                    return;
                }
                try {
                    gatt.readCharacteristic(characteristic);
                } catch (Throwable ignored) {
                    // The notification subscription below may still answer.
                }
                try {
                    gatt.setCharacteristicNotification(characteristic, true);
                    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(DESCRIPTOR_CCCD);
                    if (descriptor != null) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(descriptor);
                    }
                } catch (Throwable ignored) {
                    // No subscription: the read above, or the nine second timeout, decides.
                }
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                             int status) {
                finish(app, gatt, open, done, key, levelFrom(characteristic));
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                             byte[] value, int status) {
                finish(app, gatt, open, done, key, levelOfBytes(value));
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic) {
                finish(app, gatt, open, done, key, levelFrom(characteristic));
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic, byte[] value) {
                finish(app, gatt, open, done, key, levelOfBytes(value));
            }
        };
        try {
            BluetoothGatt gatt = target.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                return;
            }
            open[0] = gatt;
            handler.postDelayed(() -> finish(app, open[0], open, done, key, -1), ASK_TIMEOUT_MS);
        } catch (Throwable ignored) {
            // A refused connection leaves the previous value, or the icon-only chip, in place.
        }
    }

    /**
     * Looks up the BLE twin of a classic-only accessory by name.
     *
     * <p>TWS earbuds register with the system as A2DP devices, but the battery service sits on the
     * BLE side, under an address the system never reports; a plain {@code connectGatt} on the
     * classic address therefore fails. An eight second LE scan compares every advertised name with
     * the classic one (case-insensitive, either containing the other, ignoring a {@code -L}/{@code
     * -R}/" (L)"/"(R)" suffix), and the first match is remembered so the scan happens once ever.
     *
     * <p>Scanning needs {@code BLUETOOTH_SCAN}: every call is guarded, so a refused scan silently
     * leaves the icon-only chip this file would produce anyway.
     */
    private void scanTwin(Context context, BluetoothDevice device, final String address) {
        final Context app = context.getApplicationContext();
        BluetoothAdapter adapter = adapterOf(app);
        if (adapter == null) {
            return;
        }
        String raw = deviceName(device);
        if (raw.isEmpty()) {
            raw = names.get(address);
        }
        final String target = bareName(raw);
        if (target.length() < 3) {
            // Without a name there is nothing to compare, and a guess would take a wrong device.
            return;
        }
        BluetoothLeScanner found;
        try {
            found = adapter.getBluetoothLeScanner();
        } catch (Throwable ignored) {
            return;
        }
        if (found == null) {
            return;
        }
        final BluetoothLeScanner scanner = found;
        final boolean[] stopped = new boolean[1];
        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                try {
                    if (result == null) {
                        return;
                    }
                    BluetoothDevice hit = result.getDevice();
                    if (hit == null) {
                        return;
                    }
                    if (!namesMatch(target, bareName(recordName(result, hit)))) {
                        return;
                    }
                    String twin = addressOf(hit);
                    if (twin.isEmpty() || twin.equals(address)) {
                        return;
                    }
                    if (stopScan(scanner, this, stopped)) {
                        bleOf.put(address, twin);
                        persist(app, PREFIX_BLE + address, twin);
                        readBattery(app, address, twin);
                    }
                } catch (Throwable ignored) {
                    // A vendor ScanResult that will not be read only costs this one hit.
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                stopScan(scanner, this, stopped);
            }
        };
        try {
            scanner.startScan(callback);
        } catch (Throwable ignored) {
            // BLUETOOTH_SCAN not granted, or scanning switched off: keep the old three steps.
            return;
        }
        scanning = true;
        handler.postDelayed(() -> stopScan(scanner, callback, stopped), SCAN_TIMEOUT_MS);
    }

    /** Stops a scan once; a second caller finds it already stopped and does nothing. */
    private boolean stopScan(BluetoothLeScanner scanner, ScanCallback callback, boolean[] stopped) {
        synchronized (stopped) {
            if (stopped[0]) {
                return false;
            }
            stopped[0] = true;
        }
        scanning = false;
        try {
            scanner.stopScan(callback);
        } catch (Throwable ignored) {
            // Already stopped, or BLUETOOTH_SCAN was revoked while the scan ran.
        }
        return true;
    }

    /** The adapter, or null when the ROM exposes no Bluetooth service at all. */
    private static BluetoothAdapter adapterOf(Context context) {
        try {
            BluetoothManager manager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            return manager == null ? null : manager.getAdapter();
        } catch (Throwable failure) {
            return null;
        }
    }

    /** The name a scan result carries, falling back to the device's own name. */
    private static String recordName(ScanResult result, BluetoothDevice device) {
        String name = null;
        try {
            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
        } catch (Throwable ignored) {
            // Some vendor records throw when the advertised name is malformed.
        }
        if (name == null || name.trim().isEmpty()) {
            name = deviceName(device);
        }
        return name;
    }

    private static String deviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "" : name.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Lower case, with a -L/-R/(L)/(R) tail removed, so two spellings of one accessory match. */
    private static String bareName(String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        String[] tails = {" (l)", " (r)", "(l)", "(r)", "-l", "-r", "_l", "_r", " l", " r"};
        boolean changed = true;
        while (changed && !name.isEmpty()) {
            changed = false;
            for (String tail : tails) {
                if (name.length() > tail.length() && name.endsWith(tail)) {
                    name = name.substring(0, name.length() - tail.length()).trim();
                    changed = true;
                }
            }
        }
        return name;
    }

    /** Case-insensitive, either name containing the other; both must be specific enough. */
    private static boolean namesMatch(String target, String found) {
        if (target.length() < 3 || found.length() < 3) {
            return false;
        }
        return found.contains(target) || target.contains(found);
    }

    /** Delivers one result and closes the connection; the first caller wins. */
    private void finish(Context context, BluetoothGatt gatt, BluetoothGatt[] open, boolean[] done,
                        String address, int level) {
        synchronized (done) {
            if (done[0]) {
                return;
            }
            done[0] = true;
        }
        if (level >= 0) {
            levels.put(address, level);
            persist(context, PREFIX_LEVEL + address, String.valueOf(level));
            notifyLevel();
        }
        BluetoothGatt target = gatt != null ? gatt : open[0];
        open[0] = null;
        if (target != null) {
            try {
                target.disconnect();
            } catch (Throwable ignored) {
                // Already gone.
            }
            try {
                target.close();
            } catch (Throwable ignored) {
                // Already closed.
            }
        }
    }

    private boolean shouldAsk(long now, String address) {
        Long last = askedAt.get(address);
        return last == null || now - last >= ASK_GAP_MS;
    }

    /**
     * Forgets when a device was last asked, once it is no longer connected.
     *
     * <p>A reconnect then asks again at once instead of waiting out the ten minute gap. Called only
     * with a non-empty list of connected devices: a missing permission also reports nothing
     * connected, and that must not wipe the table.
     */
    private void pruneAsked(Map<String, BluetoothDevice> connected) {
        try {
            List<String> gone = new ArrayList<>();
            for (String address : askedAt.keySet()) {
                if (!connected.containsKey(address)) {
                    gone.add(address);
                }
            }
            for (String address : gone) {
                askedAt.remove(address);
            }
        } catch (Throwable ignored) {
            // An odd map implementation only costs a throttled retry.
        }
    }

    private static BluetoothGattCharacteristic levelOf(BluetoothGatt gatt) {
        try {
            BluetoothGattService service = gatt.getService(SERVICE_BATTERY);
            return service == null ? null : service.getCharacteristic(CHAR_LEVEL);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int levelFrom(BluetoothGattCharacteristic characteristic) {
        try {
            Integer value = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
            return value == null ? -1 : clamp(value);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int levelOfBytes(byte[] value) {
        if (value == null || value.length == 0) {
            return -1;
        }
        return clamp(value[0] & 0xFF);
    }

    private static int clamp(int value) {
        return value < 0 || value > 100 ? -1 : value;
    }

    private String nameOf(Context context, BluetoothDevice device, String address) {
        String cached = address.isEmpty() ? null : names.get(address);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        String name = null;
        try {
            name = device.getName();
        } catch (Throwable ignored) {
            // Requires BLUETOOTH_CONNECT on Android 12+.
        }
        if (name == null || name.trim().isEmpty()) {
            name = context.getString(R.string.bt_unknown);
        } else {
            name = name.trim();
            if (!address.isEmpty()) {
                names.put(address, name);
                persist(context, PREFIX_NAME + address, name);
            }
        }
        return name;
    }

    private void remember(Context context, BluetoothDevice device, String address, int level) {
        Integer previous = levels.put(address, level);
        // The name usually arrives with the same broadcast, so keep it while we are here.
        nameOf(context, device, address);
        if (previous != null && previous == level) {
            return;
        }
        persist(context, PREFIX_LEVEL + address, String.valueOf(level));
        notifyLevel();
    }

    private void notifyLevel() {
        Runnable listener = onLevel;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Throwable ignored) {
            // A dead service must not take the receiver down with it.
        }
    }

    private static int iconOf(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.contains("watch") || lower.contains("band") || lower.contains("手表")
                || lower.contains("手环") || lower.contains("fit")) {
            return R.drawable.ic_bt_watch;
        }
        if (lower.contains("mouse") || lower.contains("鼠标")) {
            return R.drawable.ic_bt_mouse;
        }
        if (lower.contains("buds") || lower.contains("pods") || lower.contains("headset")
                || lower.contains("headphone") || lower.contains("earbud") || lower.contains("耳机")
                // Baseus Bowie earbuds, the ones this watch was written for, carry no keyword.
                || lower.contains("baseus") || lower.contains("倍思") || lower.contains("bowie")
                || lower.contains("sound") || lower.contains("speaker")) {
            return R.drawable.ic_bt_headset;
        }
        return R.drawable.ic_bt_generic;
    }

    private static String addressOf(BluetoothDevice device) {
        try {
            String address = device.getAddress();
            return address == null ? "" : address;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void load(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        SharedPreferences store;
        try {
            store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable failure) {
            return;
        }
        try {
            for (Map.Entry<String, ?> entry : store.getAll().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null || !(value instanceof String)) {
                    continue;
                }
                String text = (String) value;
                if (key.startsWith(PREFIX_LEVEL)) {
                    try {
                        levels.put(key.substring(PREFIX_LEVEL.length()), Integer.parseInt(text));
                    } catch (NumberFormatException ignored) {
                        // A stale value; the next reading replaces it.
                    }
                } else if (key.startsWith(PREFIX_NAME)) {
                    names.put(key.substring(PREFIX_NAME.length()), text);
                } else if (key.startsWith(PREFIX_BLE)) {
                    // An address learned by an earlier scan: connect straight to it, no scan needed.
                    bleOf.put(key.substring(PREFIX_BLE.length()), text);
                }
            }
        } catch (Throwable ignored) {
            // A corrupt file only costs the numbers, not the overlay.
        }
    }

    private static void persist(Context context, String key, String value) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(key, value).apply();
        } catch (Throwable ignored) {
            // Read-only profile: the in-memory copy still drives this run.
        }
    }
}
