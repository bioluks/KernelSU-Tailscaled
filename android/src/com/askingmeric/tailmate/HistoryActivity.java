package com.askingmeric.tailmate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Full sent/received history with a Clear action. */
public final class HistoryActivity extends Activity {
    private LinearLayout list;
    private Button clear;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) -> {
        if ("transferRevision".equals(key)) render();
    };

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.history_title);
        LinearLayout root = Ui.screen(this);
        LinearLayout bar = Ui.backBar(this, getString(R.string.history_title));
        clear = new Button(this, null, android.R.attr.borderlessButtonStyle);
        clear.setText(R.string.history_clear);
        clear.setAllCaps(false);
        clear.setTextColor(getColor(R.color.accent));
        clear.setMinWidth(0);
        clear.setMinimumWidth(0);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(R.string.history_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_clear, (d, w) -> {
                    TransferHistory.clear(this);
                    render();
                }).show());
        bar.addView(clear, new LinearLayout.LayoutParams(-2, Ui.dp(this, 48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.card(this, 16, true));
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        content.addView(list, new LinearLayout.LayoutParams(-1, -2));
        TextView note = Ui.text(this, getString(R.string.history_note, TransferHistory.LIMIT), 13, R.color.muted);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), 0);
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));
        render();
    }

    private void render() {
        list.removeAllViews();
        List<TransferHistory.Entry> entries = TransferHistory.load(this);
        clear.setEnabled(!entries.isEmpty());
        if (entries.isEmpty()) {
            TextView empty = Ui.text(this, getString(R.string.history_empty), 14, R.color.muted);
            empty.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (TransferHistory.Entry entry : entries) list.addView(row(this, entry), new LinearLayout.LayoutParams(-1, -2));
    }

    /** One history row: direction badge, file name, peer/detail and relative time. */
    static View row(Context context, TransferHistory.Entry entry) {
        int pad = Ui.dp(context, 16);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, Ui.dp(context, 10), pad, Ui.dp(context, 10));
        int arrow, color, verb;
        switch (entry.kind) {
            case SENT: arrow = R.string.history_arrow_sent; color = R.color.accent; verb = R.string.history_sent_to; break;
            case RECEIVED: arrow = R.string.history_arrow_received; color = R.color.accent; verb = R.string.history_received; break;
            case CANCELLED: arrow = R.string.history_arrow_failed; color = R.color.muted; verb = R.string.history_cancelled_to; break;
            default: arrow = R.string.history_arrow_failed; color = R.color.status_error; verb = R.string.history_failed_to; break;
        }
        TextView badge = Ui.text(context, context.getString(arrow), 16, color);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(context.getColor(R.color.accent_surface));
        badge.setBackground(circle);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36));
        badgeLayout.setMarginEnd(Ui.dp(context, 14));
        row.addView(badge, badgeLayout);
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.text(context, entry.file, 15, R.color.on_surface);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
        String summary = entry.peer == null ? context.getString(verb, "").trim() : context.getString(verb, entry.peer);
        if (entry.detail != null) summary += " · " + entry.detail;
        TextView sub = Ui.text(context, summary, 13, R.color.muted);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        CharSequence when = DateUtils.getRelativeTimeSpanString(entry.time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
        TextView time = Ui.text(context, when.toString(), 12, R.color.muted);
        time.setPadding(Ui.dp(context, 8), 0, 0, 0);
        row.addView(time, new LinearLayout.LayoutParams(-2, -2));
        row.setContentDescription(entry.file + ", " + summary + ", " + when);
        row.setFocusable(true);
        return row;
    }

    @Override protected void onStart() {
        super.onStart();
        getSharedPreferences("companion", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener);
        render();
    }

    @Override protected void onStop() {
        getSharedPreferences("companion", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener);
        super.onStop();
    }
}
