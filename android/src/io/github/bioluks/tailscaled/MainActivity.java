package io.github.bioluks.tailscaled;

import android.app.*;
import android.content.*;
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
    private LinearLayout content;
    private TextView status;
    private TextView result;
    private final ArrayList<Button> controls = new ArrayList<>();
    private String panelUrl = "http://127.0.0.1:8088/";
    private boolean busy;
    private String displayedLanguage;
    private final SharedPreferences.OnSharedPreferenceChangeListener transferListener = (preferences, key) -> {
        if ("transferRevision".equals(key)) {
            result.setText(preferences.getString("lastTransfer", ""));
            if (!busy) status.setText(R.string.status);
        }
    };

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        displayedLanguage = AppLocale.language(this);
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
        button(R.string.settings_title, () -> startActivity(new Intent(this, SettingsActivity.class)));
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
        result = text(getSharedPreferences("companion", 0).getString("lastTransfer", ""), 14);
        load("status-json");
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
        result.setText(preferences.getString("lastTransfer", ""));
    }
    @Override protected void onStop() {
        getSharedPreferences("companion", 0).unregisterOnSharedPreferenceChangeListener(transferListener);
        super.onStop();
    }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
