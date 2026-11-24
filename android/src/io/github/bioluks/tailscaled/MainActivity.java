package io.github.bioluks.tailscaled;

import android.app.*;
import android.content.res.ColorStateList;

import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<View> controls = new ArrayList<>();
    private View stateDot;
    private TextView headline;
    private TextView subtitle;
    private LinearLayout facts;
    private LinearLayout alerts;
    private LinearLayout historySection;
    private LinearLayout historyList;
    private Button historyAll;
    private static final int HISTORY_PREVIEW = 5;
    private boolean busy;
    private String displayedLanguage;
    private final SharedPreferences.OnSharedPreferenceChangeListener transferListener = (preferences, key) -> {
        if ("transferRevision".equals(key)) showHistory();
    };

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        displayedLanguage = AppLocale.language(this);
        LinearLayout root = Ui.screen(this);
        addAppBar(root);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(Ui.heading(this, R.string.main_service));
        LinearLayout.LayoutParams cardLayout = new LinearLayout.LayoutParams(-1, -2);
        cardLayout.bottomMargin = dp(12);
        content.addView(statusCard(), cardLayout);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLayout = new LinearLayout.LayoutParams(-1, -2);
        actionsLayout.bottomMargin = dp(4);
        content.addView(actions, actionsLayout);
        actionButton(actions, R.string.enable, () -> load("enable"));
        actionButton(actions, R.string.disable, () -> confirmControl("disable", R.string.disable_warning));
        actionButton(actions, R.string.restart, () -> confirmControl("restart", R.string.restart_warning));

        content.addView(Ui.heading(this, R.string.main_tools));
        toolRow(content, R.string.module_settings_title, R.string.main_module_settings_summary,
                () -> startActivity(new Intent(this, ModuleSettingsActivity.class)));
        toolRow(content, R.string.panel, R.string.main_panel_summary, this::openPanel);
        toolRow(content, R.string.logs, R.string.main_logs_summary, () -> load("logs"));
        toolRow(content, R.string.send_files, R.string.main_send_summary, this::pickFiles);
        toolRow(content, R.string.settings_title, R.string.main_settings_summary,
                () -> startActivity(new Intent(this, SettingsActivity.class)));

        historySection = new LinearLayout(this);
        historySection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout historyHeader = new LinearLayout(this);
        historyHeader.setGravity(Gravity.CENTER_VERTICAL);
        historyHeader.addView(Ui.heading(this, R.string.history_title), new LinearLayout.LayoutParams(0, -2, 1));
        historyAll = new Button(this, null, android.R.attr.borderlessButtonStyle);
        historyAll.setText(R.string.history_show_all);
        historyAll.setAllCaps(false);
        historyAll.setTextColor(getColor(R.color.accent));
        historyAll.setMinWidth(0);
        historyAll.setMinimumWidth(0);
        historyAll.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        historyHeader.addView(historyAll, new LinearLayout.LayoutParams(-2, dp(48)));
        historySection.addView(historyHeader, new LinearLayout.LayoutParams(-1, -2));
        historyList = new LinearLayout(this);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyList.setBackground(Ui.card(this, 16, true));
        historyList.setPadding(0, dp(4), 0, dp(4));
        historySection.addView(historyList, new LinearLayout.LayoutParams(-1, -2));
        content.addView(historySection, new LinearLayout.LayoutParams(-1, -2));
        showHistory();
        load("status-json");
    }

    private void addAppBar(LinearLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(8), dp(20), dp(8));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_module);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        icon.setBackground(Ui.card(this, 24, false));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, getString(R.string.app_name), 24, R.color.on_surface);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(16), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout statusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(8), dp(16), dp(16));
        card.setBackground(Ui.card(this, 16, true));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        stateDot = new View(this);
        stateDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotLayout = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotLayout.setMarginEnd(dp(12));
        header.addView(stateDot, dotLayout);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        headline = Ui.text(this, getString(R.string.working), 18, R.color.on_surface);
        headline.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        titles.addView(headline, new LinearLayout.LayoutParams(-1, -2));
        subtitle = Ui.text(this, "", 14, R.color.muted);
        subtitle.setTextIsSelectable(true);
        titles.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = new Button(this, null, android.R.attr.borderlessButtonStyle);
        refresh.setText(R.string.refresh);
        refresh.setAllCaps(false);
        refresh.setTextColor(getColor(R.color.accent));
        refresh.setMinHeight(dp(40));
        refresh.setMinWidth(0);
        refresh.setMinimumWidth(0);
        refresh.setPadding(dp(8), 0, dp(8), 0);
        refresh.setOnClickListener(v -> load("status-json"));
        header.addView(refresh, new LinearLayout.LayoutParams(-2, dp(48)));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));
        facts = new LinearLayout(this);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setPadding(0, dp(8), 0, 0);
        card.addView(facts, new LinearLayout.LayoutParams(-1, -2));
        alerts = new LinearLayout(this);
        alerts.setOrientation(LinearLayout.VERTICAL);
        card.addView(alerts, new LinearLayout.LayoutParams(-1, -2));
        TextView idle = Ui.text(this, getString(R.string.idle_note), 13, R.color.muted);
        idle.setPadding(0, dp(12), 0, 0);
        card.addView(idle, new LinearLayout.LayoutParams(-1, -2));
        controls.add(refresh);
        return card;
    }

    /** Label/value row; the value is selectable so IPs can be copied. */
    private void fact(int label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView name = Ui.text(this, getString(label), 14, R.color.muted);
        row.addView(name, new LinearLayout.LayoutParams(dp(104), -2));
        TextView text = Ui.text(this, value, 14, R.color.on_surface);
        text.setTextIsSelectable(true);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setContentDescription(getString(label) + ": " + value);
        facts.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Warning/error callout; diagnostics with detail or an action open an explanation dialog. */
    private void alert(ModuleStatus.Diagnostic item) {
        boolean error = "error".equals(item.severity);
        boolean privateDns = "private-dns-strict".equals(item.code);
        boolean expandable = item.detail != null || privateDns;
        TextView text = Ui.text(this, expandable ? item.message + "  ›" : item.message, 14, R.color.on_surface);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(12));
        shape.setColor(getColor(error ? R.color.error_surface : R.color.warning_surface));
        text.setBackground(shape);
        text.setPadding(dp(12), dp(8), dp(12), dp(8));
        text.setContentDescription(getString(error ? R.string.status_error : R.string.status_warning) + ": " + item.message);
        if (expandable) {
            text.setFocusable(true);
            text.setOnClickListener(v -> explain(item, privateDns));
        }
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(8);
        alerts.addView(text, layout);
    }

    private void explain(ModuleStatus.Diagnostic item, boolean privateDns) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        if ("system-dns-other-module".equals(item.code)) dialog.setTitle(R.string.dns_conflict_title);
        else if (privateDns) dialog.setTitle(R.string.private_dns_title);
        else dialog.setTitle(item.message);
        if ("system-dns-other-module".equals(item.code)) {
            TextView body = Ui.text(this, getString(R.string.dns_conflict_explain), 14, R.color.on_surface);
            TextView detail = Ui.text(this, item.detail == null ? "" : item.detail, 12, R.color.muted);
            detail.setTypeface(Typeface.MONOSPACE);
            detail.setTextIsSelectable(true);
            detail.setPadding(0, dp(12), 0, 0);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(24), dp(8), dp(24), 0);
            box.addView(body);
            box.addView(detail);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(box);
            dialog.setView(scroll).setPositiveButton(android.R.string.ok, null);
        } else if (privateDns) {
            dialog.setMessage(R.string.private_dns_explain)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.private_dns_turn_off, (d, w) -> load("private-dns-off"));
        } else {
            dialog.setMessage(item.detail).setPositiveButton(android.R.string.ok, null);
        }
        dialog.show();
    }

    private void actionButton(LinearLayout row, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
        layout.setMarginStart(dp(4));
        layout.setMarginEnd(dp(4));
        row.addView(button, layout);
        controls.add(button);
    }

    private void toolRow(LinearLayout content, int title, int summary, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setMinimumHeight(dp(72));
        row.setBackground(Ui.card(this, 16, true));
        row.setFocusable(true);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setOnClickListener(v -> action.run());
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        TextView name = Ui.text(this, getString(title), 16, R.color.on_surface);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView description = Ui.text(this, getString(summary), 14, R.color.muted);
        description.setPadding(0, dp(4), 0, 0);
        labels.addView(description, new LinearLayout.LayoutParams(-1, -2));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = Ui.text(this, "›", 24, R.color.muted);
        chevron.setPadding(dp(16), 0, 0, 0);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(12);
        content.addView(row, layout);
        controls.add(row);
    }

    /** Newest transfers on the landing page; the full list and Clear live in HistoryActivity. */
    private void showHistory() {
        historyList.removeAllViews();
        boolean enabled = TransferHistory.enabled(this);
        java.util.List<TransferHistory.Entry> entries = enabled ? TransferHistory.load(this) : java.util.Collections.emptyList();
        historySection.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;
        if (entries.isEmpty()) {
            TextView empty = Ui.text(this, getString(R.string.history_empty), 14, R.color.muted);
            empty.setPadding(dp(16), dp(14), dp(16), dp(14));
            historyList.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (int i = 0; i < Math.min(HISTORY_PREVIEW, entries.size()); i++) {
            historyList.addView(HistoryActivity.row(this, entries.get(i)), new LinearLayout.LayoutParams(-1, -2));
        }
        historyAll.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private int dp(int value) { return Ui.dp(this, value); }
    private void setBusy(boolean value) {
        busy = value;
        for (View control : controls) control.setEnabled(!value);
    }
    private void confirmControl(String action, int message) {
        new AlertDialog.Builder(this).setMessage(message).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> load(action)).show();
    }
    private void load(String action) {
        if (busy) return;
        setBusy(true);
        showWorking();
        worker.execute(() -> {
            try {
                String answer = RootCommand.run(action);
                String current = action.equals("logs") ? null
                        : action.equals("status-json") ? answer : RootCommand.run("status-json");
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    if (current != null) renderStatus(current);
                    if (action.equals("logs")) {
                        TextView log = new TextView(this);
                        log.setText(answer);
                        log.setTextIsSelectable(true);
                        log.setPadding(dp(16), dp(16), dp(16), dp(16));
                        ScrollView scroll = new ScrollView(this);
                        scroll.addView(log);
                        new AlertDialog.Builder(this).setTitle(R.string.logs).setView(scroll)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) {
                setBusy(false); showStatusError(e.getMessage());
            } }); }
        });
    }
    private void showWorking() {
        headline.setText(R.string.working);
        setDot(R.color.muted);
    }

    private void showStatusError(String message) {
        headline.setText(R.string.error);
        subtitle.setText(message);
        subtitle.setVisibility(View.VISIBLE);
        setDot(R.color.status_error);
        facts.removeAllViews();
        alerts.removeAllViews();
    }

    private void setDot(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(getColor(color));
        stateDot.setBackground(dot);
    }

    /** Headline from lifecycle + backend; facts in rows; only warnings/errors as callouts. */
    private void renderStatus(String value) {
        ModuleStatus s;
        try { s = ModuleStatus.parse(value); }
        catch (JSONException e) { showStatusError(e.getMessage()); return; }
        int title, color;
        if (!s.enabled) { title = R.string.status_disabled; color = R.color.muted; }
        else if ("failed".equals(s.lifecycle)) { title = R.string.status_failed; color = R.color.status_error; }
        else if ("migration-required".equals(s.lifecycle)) { title = R.string.status_migration; color = R.color.status_warning; }
        else if ("NeedsLogin".equals(s.backendState)) { title = R.string.status_needs_login; color = R.color.status_warning; }
        else if ("NeedsMachineAuth".equals(s.backendState)) { title = R.string.status_needs_approval; color = R.color.status_warning; }
        else if ("Running".equals(s.backendState)) { title = R.string.status_connected; color = R.color.accent; }
        else if ("starting".equals(s.lifecycle) || "Starting".equals(s.backendState)) { title = R.string.status_starting; color = R.color.status_warning; }
        else { title = R.string.status_stopped; color = R.color.muted; }
        headline.setText(title);
        setDot(color);
        subtitle.setText(s.dnsName == null ? "" : s.dnsName);
        subtitle.setVisibility(s.dnsName == null ? View.GONE : View.VISIBLE);

        facts.removeAllViews();
        if (s.ipv4 != null) fact(R.string.status_ipv4, s.ipv4);
        if (s.ipv6 != null) fact(R.string.status_ipv6, s.ipv6);
        fact(R.string.status_taildrop, getString(s.taildropReceiverRunning ? R.string.module_running : R.string.module_stopped)
                + " · " + s.taildropDirectory);
        if (s.hasSystemDns && s.systemDnsEnabled) fact(R.string.status_dns, getString(s.systemDnsApplied
                ? R.string.status_dns_all_apps : R.string.status_dns_not_applied));
        fact(R.string.status_mode, "native".equals(s.mode) ? "Native TUN" : "Userspace");
        fact(R.string.status_version, s.moduleVersion);

        alerts.removeAllViews();
        for (ModuleStatus.Diagnostic item : s.diagnostics) if (!"info".equals(item.severity)) alert(item);
    }

    /** Starts the loopback panel when needed, then opens it in-app or in the browser per Settings. */
    private void openPanel() {
        if (busy) return;
        setBusy(true);
        showWorking();
        worker.execute(() -> {
            try {
                String state = RootCommand.run("status-json");
                ModuleStatus module = ModuleStatus.parse(state);
                if (!module.webRunning) {
                    RootCommand.run("web-restart");
                    long deadline = System.currentTimeMillis() + 20_000;
                    do {
                        Thread.sleep(500);
                        state = RootCommand.run("status-json");
                        module = ModuleStatus.parse(state);
                    } while (!module.webRunning && System.currentTimeMillis() < deadline);
                    if (!module.webRunning) throw new java.io.IOException(getString(R.string.panel_start_timeout));
                }
                String current = state;
                String url = module.panelUrl;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    renderStatus(current);
                    boolean external = getSharedPreferences("companion", 0).getBoolean(SettingsActivity.PANEL_EXTERNAL, false);
                    try {
                        startActivity(external ? new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                : new Intent(this, PanelActivity.class).putExtra(PanelActivity.EXTRA_URL, url));
                    } catch (ActivityNotFoundException e) { showError(e); }
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) {
                setBusy(false); showStatusError(e.getMessage());
            } }); }
        });
    }
    private void pickFiles() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(picker, 1);
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != 1 || code != RESULT_OK || data == null) return;
        Intent share = new Intent(this, ShareActivity.class).setAction(Intent.ACTION_SEND_MULTIPLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ArrayList<Uri> selected = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) selected.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            selected.add(data.getData());
            clip = ClipData.newRawUri("Taildrop", data.getData());
        }
        if (!selected.isEmpty()) {
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, selected).setClipData(clip);
            startActivity(share);
        }
    }
    private void showError(Exception error) {
        new AlertDialog.Builder(this).setTitle(R.string.error).setMessage(error.getMessage())
                .setPositiveButton(android.R.string.ok, null).show();
    }
    @Override protected void onResume() {
        super.onResume();
        if (!AppLocale.language(this).equals(displayedLanguage)) recreate();
    }
    @Override protected void onStart() {
        super.onStart();
        SharedPreferences preferences = getSharedPreferences("companion", 0);
        preferences.registerOnSharedPreferenceChangeListener(transferListener);
        showHistory();
    }
    @Override protected void onStop() {
        getSharedPreferences("companion", 0).unregisterOnSharedPreferenceChangeListener(transferListener);
        super.onStop();
    }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
