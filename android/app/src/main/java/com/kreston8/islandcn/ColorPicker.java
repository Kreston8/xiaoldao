package com.kreston8.islandcn;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.function.IntConsumer;

/**
 * 「自定义颜色」对话框：色相 / 饱和度 / 明度 三条滑杆，实时混出一个颜色。
 *
 * <p>明度下限固定在 30 而不是 0：浮岛上的文字是白色的，明度到 0 时颜色必然是纯黑，
 * 白字虽然还看得见，但整块浮岛几乎和暗色桌面糊成一片，用户也很容易把自己的主题
 * 误调成"什么都看不见"。所以这里直接砍掉 0–29 这一段，任何一次滑动都不会
 * 意外得到纯黑，只能得到一个仍然能托起白字的深色。
 *
 * <p>本类只负责挑颜色，不保存、不应用：点"确定"时通过 {@link IntConsumer}
 * 把最终的 ARGB 值交出去，调用方负责持久化这个值并刷新浮岛。
 */
final class ColorPicker {

    /** 明度滑杆的下限，见类注释。 */
    private static final int VAL_MIN = 30;

    /** 标签与十六进制数字用的次要灰色。 */
    private static final int HINT_COLOR = 0xFF8A8A94;

    private ColorPicker() {
    }

    static void show(android.app.Activity activity, int current, IntConsumer onPick) {
        if (activity.isFinishing()) {
            return;
        }

        float[] hsv = new float[3];
        Color.colorToHSV(current, hsv);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8));

        final GradientDrawable previewBackground = new GradientDrawable();
        previewBackground.setCornerRadius(dp(activity, 10));
        final View preview = new View(activity);
        preview.setBackground(previewBackground);
        box.addView(preview, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 44)));

        final TextView hex = new TextView(activity);
        hex.setTextSize(13);
        hex.setTextColor(HINT_COLOR);
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hexParams.topMargin = dp(activity, 6);
        box.addView(hex, hexParams);

        final TextView hueLabel = addLabel(activity, box);
        final SeekBar hueBar = addBar(activity, 0, 360, Math.min(360, Math.round(hsv[0])), box);
        final TextView satLabel = addLabel(activity, box);
        final SeekBar satBar = addBar(activity, 0, 100, Math.round(hsv[1] * 100f), box);
        final TextView valLabel = addLabel(activity, box);
        final SeekBar valBar = addBar(activity, VAL_MIN, 100,
                Math.max(VAL_MIN, Math.round(hsv[2] * 100f)), box);

        final int[] picked = new int[]{current};
        final Runnable refresh = () -> {
            picked[0] = Color.HSVToColor(new float[]{
                    hueBar.getProgress(),
                    satBar.getProgress() / 100f,
                    valBar.getProgress() / 100f}) | 0xFF000000;
            previewBackground.setColor(picked[0]);
            hueLabel.setText("色相 " + hueBar.getProgress());
            satLabel.setText("饱和度 " + satBar.getProgress());
            valLabel.setText("明度 " + valBar.getProgress());
            hex.setText(String.format("#%06X", picked[0] & 0xFFFFFF));
        };

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                refresh.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        hueBar.setOnSeekBarChangeListener(listener);
        satBar.setOnSeekBarChangeListener(listener);
        valBar.setOnSeekBarChangeListener(listener);
        refresh.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("自定义颜色");
        builder.setView(box);
        builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());
        builder.setPositiveButton("确定", (dialog, which) -> {
            if (onPick != null) {
                onPick.accept(picked[0]);
            }
            dialog.dismiss();
        });
        builder.show();
    }

    private static TextView addLabel(Context c, LinearLayout parent) {
        TextView label = new TextView(c);
        label.setTextSize(13);
        label.setTextColor(HINT_COLOR);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(c, 10);
        parent.addView(label, params);
        return label;
    }

    private static SeekBar addBar(Context c, int min, int max, int progress, LinearLayout parent) {
        SeekBar bar = new SeekBar(c);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(progress);
        parent.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
