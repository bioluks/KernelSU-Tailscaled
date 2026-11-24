package io.github.bioluks.tailscaled;

import android.app.*;
import android.content.res.ColorStateList;

import android.content.*;
import android.graphics.Typeface;
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
    private TextView status;
    private TextView result;
    private boolean busy;
    private String displayedLanguage;
    private final SharedPreferences.OnSharedPreferenceChangeListener transferListener = (preferences, key) -> {
        if ("transferRevision".equals(key)) showLastTransfer();
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

        result = Ui.text(this, "", 14, R.color.muted);
        result.setTextIsSelectable(true);
        result.setPadding(dp(4), dp(8), dp(4), 0);
        content.addView(result, new LinearLayout.LayoutParams(-1, -2));
        showLastTransfer();
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
        TextView label = Ui.text(this, getString(R.string.status), 16, R.color.on_surface);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
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
        card.addView(header);
        status = Ui.text(this, getString(R.string.working), 16, R.color.on_surface);
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(4), 0, dp(4));
        card.addView(status, new LinearLayout.LayoutParams(-1, -2));
        TextView idle = Ui.text(this, getString(R.string.idle_note), 14, R.color.muted);
        idle.setPadding(0, dp(4), 0, 0);
        card.addView(idle, new LinearLayout.LayoutParams(-1, -2));
        controls.add(refresh);
        return card;
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

    private void showLastTransfer() {
        String last = getSharedPreferences("companion", 0).getString("lastTransfer", "");
        result.setText(last);
        result.setVisibility(last.isEmpty() ? View.GONE : View.VISIBLE);
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
        status.setText(R.string.working);
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
                setBusy(false); status.setText(getString(R.string.error) + "\n" + e.getMessage());
            } }); }
        });
    }
    private void renderStatus(String value) {
        try {
            JSONObject state = new JSONObject(value);
            JSONObject tailscale = state.optJSONObject("tailscale");
            JSONObject self = tailscale == null ? null : tailscale.optJSONObject("Self");
            JSONObject drop = state.optJSONObject("taildrop");
            String description = state.getString("lifecycle") + " · " + state.getString("mode")
                    + "\nModule " + state.getString("moduleVersion") + " · Tailscale " + state.getString("tailscaleVersion");
            if (self != null) {
                String name = self.optString("DNSName", self.optString("Hostname"));
                if (!name.isEmpty() && !"null".equals(name)) description += "\n" + name;
            }
            if (tailscale != null) {
                JSONArray ips = tailscale.optJSONArray("TailscaleIPs");
                if (ips != null && ips.length() > 0) {
                    StringBuilder list = new StringBuilder(ips.optString(0));
                    for (int i = 1; i < ips.length(); i++) list.append(", ").append(ips.optString(i));
                    description += "\n" + tailscale.optString("BackendState") + " · " + list;
                } else {
                    description += "\n" + tailscale.optString("BackendState");
                }
            }
            if (drop != null) {
                String receiver = drop.optString("receiver");
                String directory = drop.optString("directory");
                if (!receiver.isEmpty() && !"null".equals(receiver)) description += "\nTaildrop: " + receiver;
                if (!directory.isEmpty() && !"null".equals(directory)) description += "\n" + directory;
            }
            JSONArray diagnostics = state.optJSONArray("diagnostics");
            if (diagnostics != null) for (int i = 0; i < diagnostics.length(); i++)
                description += "\n" + diagnostics.getJSONObject(i).optString("message");
            status.setText(description);
            showLastTransfer();
        } catch (JSONException e) { status.setText(getString(R.string.error) + "\n" + e.getMessage()); }
    }

    /** Starts the loopback panel when needed, then opens it in-app or in the browser per Settings. */
    private void openPanel() {
        if (busy) return;
        setBusy(true);
        status.setText(R.string.working);
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
                setBusy(false); status.setText(getString(R.string.error) + "\n" + e.getMessage());
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
        showLastTransfer();
    }
    @Override protected void onStop() {
        getSharedPreferences("companion", 0).unregisterOnSharedPreferenceChangeListener(transferListener);
        super.onStop();
    }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
