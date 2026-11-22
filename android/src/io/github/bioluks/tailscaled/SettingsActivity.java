package io.github.bioluks.tailscaled;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    private TextView notificationSummary;
    private LinearLayout notificationRow;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.settings_title);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.background));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
        addAppBar(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(Ui.heading(this, R.string.settings_preferences));
        TextView languageSummary = Ui.text(this, "", 14, R.color.muted);
        LinearLayout languageRow = preferenceRow(content, R.string.settings_language, languageSummary);
        languageSummary.setText("zh".equals(AppLocale.language(this))
                ? R.string.settings_language_zh : R.string.settings_language_en);
        languageRow.setContentDescription(getString(R.string.settings_language) + ", " + languageSummary.getText());
        languageRow.setOnClickListener(view -> chooseLanguage());
        addConfirmation(content);

        content.addView(Ui.heading(this, R.string.settings_notifications));
        notificationSummary = Ui.text(this, "", 14, R.color.muted);
        notificationRow = preferenceRow(content, R.string.settings_receive_notifications, notificationSummary);
        notificationRow.setOnClickListener(view -> openNotificationSettings());
        TextView note = Ui.text(this, getString(R.string.settings_notifications_note), 14, R.color.muted);
        note.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        content.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onResume() {
        super.onResume();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel("received");
        boolean enabled = manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
        notificationSummary.setText(enabled ? R.string.settings_notifications_on : R.string.settings_notifications_off);
        notificationRow.setContentDescription(getString(R.string.settings_receive_notifications)
                + ", " + notificationSummary.getText());
    }

    private void addAppBar(LinearLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        ImageButton back = new ImageButton(this);
        Path path = new Path();
        path.moveTo(20, 12); path.lineTo(4, 12);
        path.moveTo(11, 5); path.lineTo(4, 12); path.lineTo(11, 19);
        ShapeDrawable arrow = new ShapeDrawable(new PathShape(path, 24, 24));
        arrow.getPaint().setColor(getColor(R.color.on_surface));
        arrow.getPaint().setStyle(Paint.Style.STROKE);
        arrow.getPaint().setStrokeWidth(2);
        arrow.getPaint().setStrokeCap(Paint.Cap.ROUND);
        arrow.getPaint().setStrokeJoin(Paint.Join.ROUND);
        arrow.setIntrinsicWidth(Ui.dp(this, 24));
        arrow.setIntrinsicHeight(Ui.dp(this, 24));
        back.setImageDrawable(arrow);
        back.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        back.setBackground(Ui.card(this, 24, false));
        back.setContentDescription(getString(R.string.settings_back));
        back.setOnClickListener(view -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView title = Ui.text(this, getString(R.string.settings_title), 24, R.color.on_surface);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(Ui.dp(this, 16), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout preferenceRow(LinearLayout content, int title, TextView summary) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        row.setMinimumHeight(Ui.dp(this, 80));
        row.setBackground(Ui.card(this, 16, true));
        row.setFocusable(true);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        labels.addView(Ui.text(this, getString(title), 16, R.color.on_surface), new LinearLayout.LayoutParams(-1, -2));
        summary.setPadding(0, Ui.dp(this, 4), 0, 0);
        labels.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = Ui.text(this, "›", 24, R.color.muted);
        chevron.setPadding(Ui.dp(this, 16), 0, 0, 0);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = Ui.dp(this, 12);
        content.addView(row, layout);
        return row;
    }

    private void addConfirmation(LinearLayout content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.card(this, 16, true));
        Switch confirmation = new Switch(this);
        confirmation.setText(R.string.settings_confirm_send);
        confirmation.setTextSize(16);
        confirmation.setTextColor(getColor(R.color.on_surface));
        confirmation.setSingleLine(false);
        confirmation.setSwitchPadding(Ui.dp(this, 16));
        confirmation.setMinimumHeight(Ui.dp(this, 48));
        confirmation.setChecked(getSharedPreferences("companion", MODE_PRIVATE).getBoolean("confirmSend", false));
        confirmation.setContentDescription(getString(R.string.settings_confirm_send)
                + ". " + getString(R.string.settings_confirm_summary));
        confirmation.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("companion", MODE_PRIVATE).edit().putBoolean("confirmSend", checked).apply());
        card.addView(confirmation, new LinearLayout.LayoutParams(-1, -2));
        TextView summary = Ui.text(this, getString(R.string.settings_confirm_summary), 14, R.color.muted);
        summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        card.setOnClickListener(view -> confirmation.toggle());
        card.setFocusable(false);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = Ui.dp(this, 12);
        content.addView(card, layout);
    }

    private void chooseLanguage() {
        String[] languages = {"en", "zh"};
        new AlertDialog.Builder(this).setTitle(R.string.settings_language)
                .setSingleChoiceItems(new String[]{getString(R.string.settings_language_en),
                        getString(R.string.settings_language_zh)}, "zh".equals(AppLocale.language(this)) ? 1 : 0,
                        (dialog, index) -> {
                            dialog.dismiss();
                            if (!languages[index].equals(AppLocale.language(this))) {
                                AppLocale.setLanguage(this, languages[index]);
                                recreate();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openNotificationSettings() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.areNotificationsEnabled() && manager.getNotificationChannel("received") != null) {
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, "received"));
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }
}
