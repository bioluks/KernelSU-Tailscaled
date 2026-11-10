package io.github.bioluks.tailscaled;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> files = new ArrayList<>();
    private LinearLayout content;
    private TextView status;
    private TextView result;
    private final ArrayList<Button> controls = new ArrayList<>();
    private String panelUrl = "http://127.0.0.1:8088/";
    private String directTarget;
    private boolean busy;
    private boolean selectionPending;
    private final SharedPreferences.OnSharedPreferenceChangeListener transferListener = (preferences, key) -> {
        if ("transferRevision".equals(key)) {
            result.setText(preferences.getString("lastTransfer", ""));
            if (!busy) status.setText(R.string.status);
        }
    };

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(content);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = text(getString(R.string.app_name), 24);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        button(R.string.language, () -> { AppLocale.toggle(this); recreate(); });
        text(getString(R.string.idle_note), 14);
        status = text(getString(R.string.working), 16);
        button(R.string.refresh, () -> load("status-json"));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(actions);
        actionButton(actions, R.string.enable, () -> load("enable"));
        actionButton(actions, R.string.disable, () -> confirmControl("disable", R.string.disable_warning));
        actionButton(actions, R.string.restart, () -> confirmControl("restart", R.string.restart_warning));
        button(R.string.logs, () -> load("logs"));
        button(R.string.panel, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(panelUrl))); }
            catch (ActivityNotFoundException e) { showError(e); }
        });
        button(R.string.send_files, this::pickFiles);
        button(R.string.notifications, this::allowNotifications);
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) text(getString(R.string.notification_off), 14);
        result = text(getSharedPreferences("companion", 0).getString("lastTransfer", ""), 14);
        if (saved != null) {
            ArrayList<Uri> restored = saved.getParcelableArrayList("files");
            if (restored != null) files.addAll(restored);
            directTarget = saved.getString("directTarget");
        } else readShare(getIntent());
        if (!files.isEmpty()) chooseTargets();
        else load("status-json");
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(8), 0, dp(8));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }
    private Button button(int label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setOnClickListener(v -> action.run());
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        controls.add(view);
        return view;
    }
    private void actionButton(LinearLayout row, int label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setOnClickListener(v -> action.run());
        row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        controls.add(view);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void setBusy(boolean value) {
        busy = value;
        for (Button control : controls) control.setEnabled(!value);
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
                        status.setText(R.string.status);
                        TextView log = new TextView(this);
                        log.setText(answer);
                        log.setTextIsSelectable(true);
                        log.setPadding(dp(16), dp(16), dp(16), dp(16));
                        ScrollView scroll = new ScrollView(this);
                        scroll.addView(log);
                        new AlertDialog.Builder(this).setTitle(R.string.logs).setView(scroll)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                    if (selectionPending) chooseTargets();
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) {
                setBusy(false); status.setText(getString(R.string.error) + "\n" + e.getMessage());
                if (selectionPending) chooseTargets();
            } }); }
        });
    }
    private void renderStatus(String value) {
        try {
            JSONObject state = new JSONObject(value);
            JSONObject tailscale = state.optJSONObject("tailscale");
            JSONObject self = tailscale == null ? null : tailscale.optJSONObject("Self");
            JSONObject drop = state.optJSONObject("taildrop");
            String listen = state.optString("webListen");
            if (listen.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) panelUrl = "http://" + listen + "/";
            String description = state.getString("lifecycle") + " · " + state.getString("mode")
                    + "\nModule " + state.getString("moduleVersion") + " · Tailscale " + state.getString("tailscaleVersion");
            if (self != null) description += "\n" + self.optString("DNSName", self.optString("HostName"));
            if (tailscale != null) description += "\n" + tailscale.optString("BackendState") + " " + tailscale.optJSONArray("TailscaleIPs");
            if (drop != null) description += "\nTaildrop: " + drop.optString("receiver") + "\n" + drop.optString("directory");
            JSONArray diagnostics = state.optJSONArray("diagnostics");
            if (diagnostics != null) for (int i = 0; i < diagnostics.length(); i++)
                description += "\n" + diagnostics.getJSONObject(i).optString("message");
            status.setText(description);
            result.setText(getSharedPreferences("companion", 0).getString("lastTransfer", ""));
        } catch (JSONException e) { status.setText(getString(R.string.error) + "\n" + e.getMessage()); }
    }

    private void readShare(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        directTarget = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        if (Intent.ACTION_SEND.equals(action)) addUri(intent.getParcelableExtra(Intent.EXTRA_STREAM));
        else {
            ArrayList<Uri> incoming = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (incoming != null) for (Uri uri : incoming) addUri(uri);
        }
        if (files.isEmpty() && intent.getClipData() != null)
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) addUri(intent.getClipData().getItemAt(i).getUri());
        if (files.isEmpty()) Toast.makeText(this, R.string.share_files_only, Toast.LENGTH_LONG).show();
    }
    private void addUri(Uri uri) {
        if (uri != null && "content".equals(uri.getScheme()) && !files.contains(uri)) files.add(uri);
    }
    private void pickFiles() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(picker, 1);
    }
    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != 1 || code != RESULT_OK || data == null) return;
        files.clear(); directTarget = null;
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++)
            addUri(data.getClipData().getItemAt(i).getUri());
        else addUri(data.getData());
        if (!files.isEmpty()) chooseTargets();
    }
    private void chooseTargets() {
        if (busy) { selectionPending = true; return; }
        selectionPending = false;
        if (SendService.active) { Toast.makeText(this, R.string.transfer_busy, Toast.LENGTH_LONG).show(); return; }
        setBusy(true);
        worker.execute(() -> {
            try {
                String output = RootCommand.run("taildrop-targets");
                ArrayList<String> ips = new ArrayList<>();
                ArrayList<String> labels = new ArrayList<>();
                for (String line : output.split("\n")) {
                    String[] fields = line.trim().split("\\s+", 3);
                    if (fields.length >= 2 && fields[0].matches("[0-9.]{7,15}")) {
                        ips.add(fields[0]); labels.add(fields[1] + (fields.length == 3 ? " · " + fields[2] : ""));
                    }
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    publishShortcuts(ips, labels);
                    if (ips.isEmpty()) { status.setText(R.string.no_targets); return; }
                    if (directTarget != null) {
                        int index = ips.indexOf(directTarget);
                        directTarget = null;
                        if (index < 0) { status.setText(R.string.no_targets); return; }
                        confirmSend(ips.get(index), labels.get(index));
                    } else new AlertDialog.Builder(this).setTitle(R.string.choose_device)
                            .setItems(labels.toArray(new String[0]), (d, index) -> confirmSend(ips.get(index), labels.get(index)))
                            .setNegativeButton(R.string.cancel, (d, w) -> files.clear()).show();
                });
            } catch (Exception e) { runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); showError(e); } }); }
        });
    }
    private void confirmSend(String ip, String label) {
        new AlertDialog.Builder(this).setTitle(R.string.send)
                .setMessage(getString(R.string.confirm_send, files.size(), label))
                .setNegativeButton(R.string.cancel, (d, w) -> files.clear())
                .setPositiveButton(R.string.send, (d, w) -> {
                    if (SendService.active) { Toast.makeText(this, R.string.transfer_busy, Toast.LENGTH_LONG).show(); return; }
                    Intent send = new Intent(this, SendService.class).putExtra("target", ip).putExtra("label", label)
                            .putParcelableArrayListExtra("files", new ArrayList<>(files));
                    ClipData clip = ClipData.newRawUri("Taildrop", files.get(0));
                    for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
                    send.setClipData(clip); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startForegroundService(send); files.clear(); status.setText(getString(R.string.sending, label)); }
                    catch (RuntimeException e) { showError(e); }
                }).show();
    }
    private void publishShortcuts(ArrayList<String> ips, ArrayList<String> labels) {
        if (Build.VERSION.SDK_INT < 29) return;
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        for (int i = 0; i < Math.min(ips.size(), manager.getMaxShortcutCountPerActivity()); i++) {
            Intent launch = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN);
            shortcuts.add(new ShortcutInfo.Builder(this, ips.get(i)).setShortLabel(labels.get(i))
                    .setLongLabel(labels.get(i)).setIcon(Icon.createWithResource(this, R.drawable.ic_module))
                    .setCategories(Collections.singleton("io.github.bioluks.tailscaled.FILE_TARGET"))
                    .setLongLived(true).setIntent(launch).build());
        }
        try { manager.setDynamicShortcuts(shortcuts); } catch (IllegalArgumentException ignored) { }
    }
    private void allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }
    private void showError(Exception error) {
        new AlertDialog.Builder(this).setTitle(R.string.error).setMessage(error.getMessage())
                .setPositiveButton(android.R.string.ok, null).show();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelableArrayList("files", files);
        state.putString("directTarget", directTarget);
    }
    @Override protected void onStart() {
        super.onStart();
        SharedPreferences preferences = getSharedPreferences("companion", 0);
        preferences.registerOnSharedPreferenceChangeListener(transferListener);
        result.setText(preferences.getString("lastTransfer", ""));
    }
    @Override protected void onStop() {
        getSharedPreferences("companion", 0).unregisterOnSharedPreferenceChangeListener(transferListener);
        super.onStop();
    }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
