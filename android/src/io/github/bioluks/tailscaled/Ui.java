package io.github.bioluks.tailscaled;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual helpers so the companion screens stay consistent. */
final class Ui {
    private Ui() { }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(context.getColor(color));
        return view;
    }

    static RippleDrawable card(Context context, int radius, boolean outlined) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(context.getColor(R.color.surface));
        shape.setCornerRadius(dp(context, radius));
        if (outlined) shape.setStroke(dp(context, 1), context.getColor(R.color.outline));
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.accent_surface)), shape, null);
    }

    static TextView heading(Context context, int title) {
        TextView heading = text(context, context.getString(title), 13, R.color.accent);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 12));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        return heading;
    }

    /** Edge-to-edge vertical root padded by system bars, cutouts and the keyboard. */
    static LinearLayout screen(Activity activity) {
        if (Build.VERSION.SDK_INT >= 30) activity.getWindow().setDecorFitsSystemWindows(false);
        else activity.getWindow().getDecorView().setSystemUiVisibility(
                activity.getWindow().getDecorView().getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(activity.getColor(R.color.background));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        activity.setContentView(root);
        root.requestApplyInsets();
        return root;
    }

    /** App-bar row with a back arrow that finishes the activity, and a bold title. */
    static LinearLayout backBar(Activity activity, String titleText) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 20), dp(activity, 8));
        ImageButton back = new ImageButton(activity);
        Path path = new Path();
        path.moveTo(20, 12); path.lineTo(4, 12);
        path.moveTo(11, 5); path.lineTo(4, 12); path.lineTo(11, 19);
        ShapeDrawable arrow = new ShapeDrawable(new PathShape(path, 24, 24));
        arrow.getPaint().setColor(activity.getColor(R.color.on_surface));
        arrow.getPaint().setStyle(Paint.Style.STROKE);
        arrow.getPaint().setStrokeWidth(2);
        arrow.getPaint().setStrokeCap(Paint.Cap.ROUND);
        arrow.getPaint().setStrokeJoin(Paint.Join.ROUND);
        arrow.setIntrinsicWidth(dp(activity, 24));
        arrow.setIntrinsicHeight(dp(activity, 24));
        back.setImageDrawable(arrow);
        back.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        back.setBackground(card(activity, 24, false));
        back.setContentDescription(activity.getString(R.string.settings_back));
        back.setOnClickListener(view -> activity.finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        TextView title = text(activity, titleText, 24, R.color.on_surface);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(activity, 16), 0, 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }
}
