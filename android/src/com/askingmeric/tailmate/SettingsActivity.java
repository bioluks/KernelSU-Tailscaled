package com.askingmeric.tailmate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    /** Preference key: true opens the full panel in the default browser instead of in-app. */
    static final String PANEL_EXTERNAL = "panelExternal";
    private TextView notificationSummary;
    private LinearLayout notificationRow;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.settings_title);
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.backBar(this, getString(R.string.settings_title)), new LinearLayout.LayoutParams(-1, -2));

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
        addSwitch(content, "confirmSend", R.string.settings_confirm_send, R.string.settings_confirm_summary);
        addSwitch(content, PANEL_EXTERNAL, R.string.settings_panel_external, R.string.settings_panel_external_summary);
        Switch history = addSwitch(content, TransferHistory.ENABLED, true,
                R.string.settings_history, R.string.settings_history_summary);
        history.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("companion", MODE_PRIVATE).edit().putBoolean(TransferHistory.ENABLED, checked).apply();
            if (!checked && !TransferHistory.load(this).isEmpty()) {
                new AlertDialog.Builder(this).setMessage(R.string.settings_history_clear_prompt)
                        .setNegativeButton(R.string.settings_history_keep, null)
                        .setPositiveButton(R.string.history_clear, (d, w) -> TransferHistory.clear(this)).show();
            }
        });

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

    private Switch addSwitch(LinearLayout content, String key, int title, int summaryText) {
        return addSwitch(content, key, false, title, summaryText);
    }

    private Switch addSwitch(LinearLayout content, String key, boolean fallback, int title, int summaryText) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));
        card.setBackground(Ui.card(this, 16, true));
        Switch confirmation = new Switch(this);
        confirmation.setText(title);
        confirmation.setTextSize(16);
        confirmation.setTextColor(getColor(R.color.on_surface));
        confirmation.setSingleLine(false);
        confirmation.setSwitchPadding(Ui.dp(this, 16));
        confirmation.setMinimumHeight(Ui.dp(this, 48));
        confirmation.setChecked(getSharedPreferences("companion", MODE_PRIVATE).getBoolean(key, fallback));
        confirmation.setContentDescription(getString(title) + ". " + getString(summaryText));
        confirmation.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences("companion", MODE_PRIVATE).edit().putBoolean(key, checked).apply());
        card.addView(confirmation, new LinearLayout.LayoutParams(-1, -2));
        TextView summary = Ui.text(this, getString(summaryText), 14, R.color.muted);
        summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        card.setOnClickListener(view -> confirmation.toggle());
        card.setFocusable(false);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = Ui.dp(this, 12);
        content.addView(card, layout);
        return confirmation;
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
