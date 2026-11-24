package io.github.bioluks.tailscaled;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/** Native equivalents of every module setting in the KernelSU WebUI. */
public final class ModuleSettingsActivity extends Activity {
    private interface Work { String run() throws Exception; }
    private interface Done { void accept(String output) throws Exception; }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<View> controls = new ArrayList<>();
    private boolean busy;
    private boolean fieldsFilled;
    private ModuleStatus current;
    private JSONObject engine;

    private TextView notice;
    private LinearLayout sections;
    private EditText server;
    private Button connect;
    private LinearLayout signInResult;
    private TextView receiver;
    private EditText folder;
    private Button saveFolder;
    private LinearLayout taildropCard;
    private Switch acceptDns;
    private Switch systemDns;
    private TextView systemDnsNotApplied;
    private TextView dnsBlocked;
    private LinearLayout systemDnsCard;
    private TextView runtimeFacts;
    private TextView stagedNote;
    private Button applyStaged;
    private TextView engineFacts;
    private TextView enginePolicy;
    private Button engineCheck;
    private Button enginePrepare;
    private Button engineApply;
    private Button migrate;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.module_settings_title);
        LinearLayout root = Ui.screen(this);
        LinearLayout bar = Ui.backBar(this, getString(R.string.module_settings_title));
        Button reload = new Button(this, null, android.R.attr.borderlessButtonStyle);
        reload.setText(R.string.refresh);
        reload.setAllCaps(false);
        reload.setTextColor(getColor(R.color.accent));
        reload.setMinWidth(0);
        reload.setMinimumWidth(0);
        reload.setOnClickListener(view -> refresh());
        bar.addView(reload, new LinearLayout.LayoutParams(-2, dp(48)));
        controls.add(reload);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        notice = Ui.text(this, getString(R.string.working), 14, R.color.muted);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.setTextIsSelectable(true);
        notice.setPadding(dp(4), dp(4), dp(4), dp(8));
        content.addView(notice, new LinearLayout.LayoutParams(-1, -2));
        sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setVisibility(View.GONE);
        content.addView(sections, new LinearLayout.LayoutParams(-1, -2));

        addSignIn();
        addTaildrop();
        addDns();
        addUpdates();
        addMaintenance();
        refresh();
    }

    // --- Layout ---

    private int dp(int value) { return Ui.dp(this, value); }

    /** Heading plus outlined card, wrapped so the card's parent hides both together. */
    private LinearLayout card(int heading) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(Ui.heading(this, heading));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        card.setBackground(Ui.card(this, 16, true));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(12);
        group.addView(card, layout);
        sections.addView(group, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private TextView body(LinearLayout parent, String value) {
        TextView text = Ui.text(this, value, 14, R.color.muted);
        text.setPadding(0, dp(4), 0, dp(4));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private TextView label(LinearLayout parent, int value) {
        TextView text = Ui.text(this, getString(value), 16, R.color.on_surface);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private EditText field(LinearLayout parent, int labelText, String hint, int inputType) {
        TextView name = label(parent, labelText);
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(inputType | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setHint(hint);
        edit.setTextColor(getColor(R.color.on_surface));
        edit.setHintTextColor(getColor(R.color.muted));
        edit.setMinHeight(dp(48));
        name.setLabelFor(View.generateViewId());
        edit.setId(name.getLabelFor());
        parent.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        controls.add(edit);
        return edit;
    }

    private Button button(LinearLayout parent, int text, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(action);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(8);
        parent.addView(button, layout);
        controls.add(button);
        return button;
    }

    private Switch toggle(LinearLayout parent, int text, int summary) {
        Switch view = new Switch(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(getColor(R.color.on_surface));
        view.setSingleLine(false);
        view.setSwitchPadding(dp(16));
        view.setMinimumHeight(dp(48));
        view.setContentDescription(getString(text) + ". " + getString(summary));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        body(parent, getString(summary)).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        controls.add(view);
        return view;
    }

    private void addSignIn() {
        LinearLayout card = card(R.string.module_signin);
        server = field(card, R.string.module_control_url, "https://headscale.example.com", InputType.TYPE_TEXT_VARIATION_URI);
        body(card, getString(R.string.module_control_help));
        connect = button(card, R.string.module_connect, view -> connect());
        signInResult = new LinearLayout(this);
        signInResult.setOrientation(LinearLayout.VERTICAL);
        card.addView(signInResult, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addTaildrop() {
        taildropCard = card(R.string.module_taildrop);
        receiver = Ui.text(this, "", 16, R.color.on_surface);
        receiver.setPadding(0, 0, 0, dp(8));
        taildropCard.addView(receiver, new LinearLayout.LayoutParams(-1, -2));
        folder = field(taildropCard, R.string.module_taildrop_folder, "/sdcard/Download", InputType.TYPE_CLASS_TEXT);
        body(taildropCard, getString(R.string.module_taildrop_help));
        saveFolder = button(taildropCard, R.string.module_save_folder, view -> saveFolder());
    }

    private void addDns() {
        LinearLayout card = card(R.string.module_dns);
        dnsBlocked = body(card, getString(R.string.module_daemon_required));
        acceptDns = toggle(card, R.string.module_accept_dns, R.string.module_accept_dns_summary);
        acceptDns.setOnClickListener(view -> {
            boolean wanted = acceptDns.isChecked();
            acceptDns.setChecked(!wanted);
            mutate(wanted ? "enable-dns" : "disable-dns",
                    wanted ? R.string.module_dns_enabled : R.string.module_dns_disabled);
        });
        systemDnsCard = new LinearLayout(this);
        systemDnsCard.setOrientation(LinearLayout.VERTICAL);
        systemDnsCard.setPadding(0, dp(8), 0, 0);
        card.addView(systemDnsCard, new LinearLayout.LayoutParams(-1, -2));
        systemDns = toggle(systemDnsCard, R.string.module_system_dns, R.string.module_system_dns_summary);
        systemDns.setOnClickListener(view -> {
            boolean wanted = systemDns.isChecked();
            systemDns.setChecked(!wanted);
            mutate(wanted ? "enable-system-dns" : "disable-system-dns",
                    wanted ? R.string.module_system_dns_enabled : R.string.module_system_dns_disabled);
        });
        systemDnsNotApplied = body(systemDnsCard, getString(R.string.module_system_dns_not_applied));
    }

    private void addUpdates() {
        LinearLayout card = card(R.string.module_updates);
        runtimeFacts = Ui.text(this, "", 16, R.color.on_surface);
        runtimeFacts.setTextIsSelectable(true);
        card.addView(runtimeFacts, new LinearLayout.LayoutParams(-1, -2));
        stagedNote = body(card, getString(R.string.module_staged_note));
        applyStaged = button(card, R.string.module_apply_staged, view -> confirm(R.string.module_apply_staged_confirm,
                () -> mutate("apply-staged", 0)));

        LinearLayout engineCard = card(R.string.module_engine);
        engineFacts = Ui.text(this, "", 16, R.color.on_surface);
        engineFacts.setTextIsSelectable(true);
        engineCard.addView(engineFacts, new LinearLayout.LayoutParams(-1, -2));
        enginePolicy = body(engineCard, "");
        engineCheck = button(engineCard, R.string.module_engine_check, view -> engineAction("engine-check", R.string.working));
        enginePrepare = button(engineCard, R.string.module_engine_prepare,
                view -> engineAction("engine-prepare", R.string.module_engine_preparing));
        engineApply = button(engineCard, R.string.module_engine_apply, view -> confirm(R.string.module_engine_apply_confirm,
                () -> engineAction("engine-apply", R.string.working)));
    }

    private void addMaintenance() {
        LinearLayout card = card(R.string.module_maintenance);
        button(card, R.string.module_diagnostics, view -> run(() -> RootCommand.run("diagnostics"), output -> {
            notice.setText("");
            showText(R.string.module_diagnostics, output.isEmpty() ? getString(R.string.module_no_records) : output);
        }));
        button(card, R.string.module_migration_status, view -> run(() -> RootCommand.run("migration-status"),
                output -> report(migrationLabel(output))));
        migrate = button(card, R.string.module_migrate, view -> confirm(R.string.module_migrate_confirm,
                () -> mutate("migrate-legacy", 0)));
    }

    // --- Rendering ---

    /** {@code engineJson}: null keeps the last engine state, empty means unreadable. */
    private void render(String statusJson, String engineJson) throws JSONException {
        current = ModuleStatus.parse(statusJson);
        if (engineJson != null) engine = engineJson.isEmpty() ? null : new JSONObject(engineJson);
        ModuleStatus s = current;
        if (!fieldsFilled) {
            server.setText(s.controlUrl == null ? "" : s.controlUrl);
            folder.setText(s.taildropDirectory);
            fieldsFilled = true;
        }
        receiver.setText(getString(R.string.module_taildrop_receiver,
                getString(s.taildropReceiverRunning ? R.string.module_running : R.string.module_stopped)));
        ((View) taildropCard.getParent()).setVisibility(s.migrationRequired || s.awaitingEnable ? View.GONE : View.VISIBLE);
        dnsBlocked.setVisibility(s.daemonControlsBlocked() ? View.VISIBLE : View.GONE);
        acceptDns.setVisibility(s.acceptDns == null ? View.GONE : View.VISIBLE);
        if (s.acceptDns != null) acceptDns.setChecked(s.acceptDns);
        systemDnsCard.setVisibility(s.hasSystemDns ? View.VISIBLE : View.GONE);
        systemDns.setChecked(s.systemDnsEnabled);
        systemDnsNotApplied.setVisibility(s.systemDnsEnabled && !s.systemDnsApplied ? View.VISIBLE : View.GONE);
        runtimeFacts.setText(getString(R.string.module_runtime_facts, orUnknown(s.activeVersion),
                orNone(s.stagedVersion), orNone(s.previousVersion)));
        stagedNote.setVisibility(s.stagedVersion == null ? View.GONE : View.VISIBLE);
        applyStaged.setVisibility(s.stagedVersion == null ? View.GONE : View.VISIBLE);
        migrate.setVisibility(s.migrationRequired ? View.VISIBLE : View.GONE);
        ((View) engineFacts.getParent().getParent()).setVisibility(engine == null ? View.GONE : View.VISIBLE);
        if (engine != null) renderEngine();
        sections.setVisibility(View.VISIBLE);
        applyEnabled();
    }

    private void renderEngine() {
        boolean standalone = "engine".equals(engine.optString("updatePolicy"));
        String latest = engine.optString("latest");
        String staged = engine.optString("staged");
        String facts = getString(R.string.module_engine_current, orUnknown(emptyToNull(engine.optString("current"))))
                + "\n" + getString(R.string.module_engine_latest,
                latest.isEmpty() ? getString(R.string.module_not_checked) : latest);
        if (standalone) facts += "\n" + getString(R.string.module_engine_ready, orNone(emptyToNull(staged)));
        engineFacts.setText(facts);
        enginePolicy.setText(standalone ? R.string.module_engine_policy_engine : R.string.module_engine_policy_module);
        enginePrepare.setVisibility(standalone && !latest.isEmpty() ? View.VISIBLE : View.GONE);
        enginePrepare.setText(latest.equals(engine.optString("current"))
                ? R.string.module_engine_prepare_again : R.string.module_engine_prepare);
        engineApply.setVisibility(standalone && !staged.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void applyEnabled() {
        for (View control : controls) control.setEnabled(!busy);
        if (busy || current == null) return;
        boolean blocked = current.daemonControlsBlocked();
        server.setEnabled(!blocked);
        connect.setEnabled(!blocked);
        acceptDns.setEnabled(!blocked);
        systemDns.setEnabled(!blocked);
    }

    private String orUnknown(String value) { return value == null ? getString(R.string.module_unknown) : value; }
    private String orNone(String value) { return value == null ? getString(R.string.module_none) : value; }
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    private String migrationLabel(String output) throws JSONException {
        switch (new JSONObject(output).getString("state")) {
            case "migration-incomplete": return getString(R.string.module_migration_incomplete);
            case "awaiting-enable": return getString(R.string.module_migration_awaiting);
            case "destination-exists": return getString(R.string.module_migration_destination);
            case "migration-required": return getString(R.string.module_migration_required);
            case "no-legacy-identity": return getString(R.string.module_migration_none);
            default: throw new JSONException("Invalid migration status");
        }
    }

    // --- Actions ---

    private void run(Work work, Done done) {
        if (busy) return;
        busy = true;
        applyEnabled();
        notice.setText(R.string.working);
        worker.execute(() -> {
            try {
                String output = work.run();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    try { done.accept(output); }
                    catch (Exception e) { fail(e); }
                    applyEnabled();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    busy = false;
                    fail(e);
                    applyEnabled();
                });
            }
        });
    }

    /** Runs one action (null: none), then re-reads status and, when readable, engine state. */
    private void runThenRefresh(String action, int message) {
        run(() -> {
            if (action != null) RootCommand.run(action);
            String status = RootCommand.run("status-json");
            String engineStatus;
            try { engineStatus = RootCommand.run("engine-status"); }
            catch (Exception e) { engineStatus = ""; }
            return status + "\n" + engineStatus;
        }, output -> {
            int split = output.lastIndexOf('\n');
            render(output.substring(0, split), output.substring(split + 1));
            if (message == 0) notice.setText("");
            else report(getString(message));
        });
    }

    private void refresh() { runThenRefresh(null, 0); }

    private void mutate(String action, int message) { runThenRefresh(action, message); }

    private void engineAction(String action, int progress) {
        runThenRefresh(action, 0);
        if (busy) notice.setText(progress);
    }

    private void connect() {
        String value = ModuleInput.controlUrl(server.getText().toString());
        if (value == null) { report(getString(R.string.module_invalid_server)); return; }
        new AlertDialog.Builder(this).setMessage(getString(R.string.module_connect_confirm, value))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    signInResult.removeAllViews();
                    run(() -> RootCommand.run("login-server", value) + "\n" + RootCommand.run("status-json"), output -> {
                        int split = output.indexOf('\n');
                        JSONObject login = new JSONObject(output.substring(0, split));
                        render(output.substring(split + 1), null);
                        server.setText(login.getString("controlURL"));
                        String auth = login.optString("authURL");
                        if (!auth.isEmpty() && !ModuleInput.safeAuthUrl(auth)) throw new JSONException("Invalid sign-in URL");
                        notice.setText("");
                        body(signInResult, getString(auth.isEmpty() ? R.string.module_signin_saved : R.string.module_signin_pending));
                        if (!auth.isEmpty()) {
                            button(signInResult, R.string.module_open_signin, view -> openExternal(auth));
                        }
                    });
                }).show();
    }

    private void saveFolder() {
        String value = ModuleInput.taildropDirectory(folder.getText().toString());
        if (value == null) { report(getString(R.string.module_invalid_folder)); return; }
        run(() -> {
            String output = RootCommand.run("taildrop-save-dir", value);
            return output + "\n" + RootCommand.run("status-json");
        }, output -> {
            int split = output.lastIndexOf('\n');
            render(output.substring(split + 1), null);
            folder.setText(current.taildropDirectory);
            String detail = output.substring(0, split).trim();
            report(detail.isEmpty() ? getString(R.string.module_folder_saved) : detail);
        });
    }

    private void confirm(int message, Runnable action) {
        new AlertDialog.Builder(this).setMessage(message).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> action.run()).show();
    }

    private void showText(int title, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { fail(e); }
    }

    /** Result text stays at the top and is announced as a toast, since the trigger may be far below. */
    private void report(String message) {
        notice.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void fail(Exception error) {
        notice.setText(getString(R.string.error) + "\n" + error.getMessage());
        new AlertDialog.Builder(this).setTitle(R.string.error).setMessage(error.getMessage())
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
