package io.github.bioluks.tailscaled;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
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
}
