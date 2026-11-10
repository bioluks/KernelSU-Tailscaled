package io.github.bioluks.tailscaled;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShareActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Uri> files = new ArrayList<>();
    private LinearLayout body;
    private TextView subtitle;
    private String directTarget;
    private String selectedIp;
    private String selectedLabel;
    private String displayedLanguage;
    private boolean loading;
    private boolean sending;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        displayedLanguage = AppLocale.language(this);
        if (saved != null) {
            ArrayList<Uri> restored = saved.getParcelableArrayList("files");
            if (restored != null) files.addAll(restored);
            directTarget = saved.getString("directTarget");
            selectedIp = saved.getString("selectedIp");
            selectedLabel = saved.getString("selectedLabel");
        } else readShare(getIntent());
        buildSheet();
        if (files.isEmpty()) showMessage(R.string.share_files_only, false);
        else if (selectedIp != null && confirmBeforeSending()) showConfirmation();
        else loadTargets();
    }

    private void buildSheet() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.32f);
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(12), dp(24), dp(20));
        sheet.setBackground(shape(R.color.surface, 28));
        sheet.setElevation(dp(12));
        sheet.setOnClickListener(v -> { });
        FrameLayout.LayoutParams frame = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        frame.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(sheet, frame);
        View handle = new View(this);
        handle.setBackground(shape(R.color.outline, 4));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(16);
        sheet.addView(handle, handleParams);
        View.OnTouchListener dismissSwipe = new View.OnTouchListener() {
            private float startY;
            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { startY = event.getRawY(); return true; }
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                    sheet.setTranslationY(Math.max(0, event.getRawY() - startY)); return true;
                }
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
                        || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    if (sheet.getTranslationY() > dp(80)) finish();
                    else sheet.animate().translationY(0).setDuration(160).start();
                    return true;
                }
                return false;
            }
        };
        handle.setOnTouchListener(dismissSwipe);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(getString(R.string.share_title), 23, R.color.on_surface);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this, null, android.R.attr.borderlessButtonStyle);
        close.setText("×"); close.setTextSize(26); close.setMinWidth(0); close.setMinimumWidth(0);
        close.setContentDescription(getString(R.string.share_close));
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        sheet.addView(header);
        subtitle = label("", 14, R.color.muted);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        sheet.addView(subtitle);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button settings = new Button(this, null, android.R.attr.borderlessButtonStyle);
        settings.setText(R.string.settings_title); settings.setAllCaps(false);
        settings.setTextColor(getColor(R.color.accent)); settings.setMinHeight(dp(48));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(settings, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button cancel = new Button(this);
        cancel.setText(R.string.cancel); cancel.setAllCaps(false); cancel.setMinHeight(dp(48));
        cancel.setOnClickListener(v -> finish());
        footer.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        sheet.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int availableHeight = b - t - root.getPaddingTop() - root.getPaddingBottom();
            int availableWidth = r - l - root.getPaddingLeft() - root.getPaddingRight();
            int height = Math.max(1, Math.round(availableHeight * (availableHeight >= dp(600) ? 0.56f : 0.86f)));
            int width = Math.max(1, Math.min(dp(560), availableWidth - dp(24)));
            if (frame.height != height || frame.width != width) {
                frame.height = height; frame.width = width; sheet.setLayoutParams(frame);
            }
        });
    }

    private void readShare(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        directTarget = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        if (Intent.ACTION_SEND.equals(action)) addUri(intent.getParcelableExtra(Intent.EXTRA_STREAM));
        else {
            ArrayList<Uri> shared = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) for (Uri uri : shared) addUri(uri);
        }
        if (files.isEmpty() && intent.getClipData() != null)
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) addUri(intent.getClipData().getItemAt(i).getUri());
    }

    private void addUri(Uri uri) {
        if (uri != null && "content".equals(uri.getScheme()) && !files.contains(uri)) files.add(uri);
    }

    private void loadTargets() {
        if (loading || sending || files.isEmpty()) return;
        if (SendService.active) { showMessage(R.string.transfer_busy, false); return; }
        loading = true;
        body.removeAllViews();
        subtitle.setText(getString(R.string.share_loading));
        body.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(32), dp(32)));
        worker.execute(() -> {
            try {
                String output = RootCommand.run("taildrop-targets");
                ArrayList<String[]> targets = new ArrayList<>();
                for (String line : output.split("\n")) {
                    String[] fields = line.trim().split("\\s+", 3);
                    if (fields.length >= 2 && fields[0].matches("[0-9.]{7,15}")) targets.add(fields);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    publishShortcuts(targets);
                    if (targets.isEmpty()) { showMessage(R.string.no_targets, true); return; }
                    body.removeAllViews();
                    subtitle.setText(getResources().getQuantityString(R.plurals.share_file_count, files.size(), files.size())
                            + " · " + getString(confirmBeforeSending() ? R.string.share_hint_confirm : R.string.share_hint));
                    if (directTarget != null) {
                        String requested = directTarget;
                        directTarget = null;
                        for (String[] target : targets) if (requested.equals(target[0])) {
                            selectTarget(target[0], target[1]); return;
                        }
                    }
                    for (String[] target : targets) addDevice(target);
                });
            } catch (Exception error) { runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                showMessage(R.string.error, true);
                body.addView(label(String.valueOf(error.getMessage()), 14, R.color.muted));
            }); }
        });
    }

    private void addDevice(String[] target) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.outline)),
                shape(R.color.background, 18), null));
        row.setFocusable(true);
        row.setOnClickListener(v -> selectTarget(target[0], target[1]));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_device);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        icon.setBackground(shape(R.color.accent_surface, 24));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(target[1], 17, R.color.on_surface);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        words.addView(name);
        words.addView(label(target[0] + (target.length == 3 ? " · " + target[2] : ""), 13, R.color.muted));
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1);
        wordsParams.setMarginStart(dp(16)); row.addView(words, wordsParams);
        TextView arrow = label("›", 26, R.color.muted);
        arrow.setPadding(dp(8), 0, 0, 0); row.addView(arrow);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10); body.addView(row, params);
    }

    private boolean confirmBeforeSending() { return getSharedPreferences("companion", 0).getBoolean("confirmSend", false); }

    private void selectTarget(String ip, String name) {
        if (sending || isFinishing()) return;
        selectedIp = ip; selectedLabel = name;
        if (confirmBeforeSending()) showConfirmation(); else send();
    }

    private void showConfirmation() {
        body.removeAllViews(); subtitle.setText(R.string.share_confirm_title);
        TextView message = label(getString(R.string.confirm_send, files.size(), selectedLabel), 18, R.color.on_surface);
        message.setPadding(0, dp(12), 0, dp(24)); body.addView(message);
        action(R.string.send, this::send);
        action(R.string.share_other_device, () -> { selectedIp = null; selectedLabel = null; loadTargets(); });
    }

    private void send() {
        if (sending || files.isEmpty()) return;
        if (SendService.active) { showMessage(R.string.transfer_busy, false); return; }
        Intent send = new Intent(this, SendService.class).putExtra("target", selectedIp).putExtra("label", selectedLabel)
                .putParcelableArrayListExtra("files", new ArrayList<>(files));
        ClipData clip = ClipData.newRawUri("Taildrop", files.get(0));
        for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
        send.setClipData(clip); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            sending = true; startForegroundService(send);
            files.clear();
            Toast.makeText(this, getString(R.string.sending, selectedLabel), Toast.LENGTH_SHORT).show();
            finish();
        } catch (RuntimeException error) {
            sending = false;
            showMessage(R.string.error, false);
            body.addView(label(String.valueOf(error.getMessage()), 14, R.color.muted));
        }
    }

    private void publishShortcuts(ArrayList<String[]> targets) {
        if (Build.VERSION.SDK_INT < 29) return;
        ShortcutManager manager = getSystemService(ShortcutManager.class);
        ArrayList<ShortcutInfo> shortcuts = new ArrayList<>();
        for (int i = 0; i < Math.min(targets.size(), manager.getMaxShortcutCountPerActivity()); i++) {
            String[] target = targets.get(i);
            shortcuts.add(new ShortcutInfo.Builder(this, target[0]).setShortLabel(target[1]).setLongLabel(target[1])
                    .setActivity(new ComponentName(this, MainActivity.class))
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_module))
                    .setCategories(Collections.singleton("io.github.bioluks.tailscaled.FILE_TARGET"))
                    .setLongLived(true).setIntent(new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)).build());
        }
        try { manager.setDynamicShortcuts(shortcuts); } catch (IllegalArgumentException ignored) { }
    }

    private void showMessage(int message, boolean retry) {
        body.removeAllViews(); subtitle.setText(R.string.share_subtitle);
        TextView explanation = label(getString(message), 16, R.color.muted);
        explanation.setPadding(0, dp(16), 0, dp(20)); body.addView(explanation);
        if (retry) action(R.string.share_retry, this::loadTargets);
    }
    private void action(int title, Runnable task) {
        Button button = new Button(this); button.setAllCaps(false); button.setText(title);
        button.setMinHeight(dp(48)); button.setOnClickListener(v -> task.run());
        body.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(getColor(color));
        return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(getColor(color));
        background.setCornerRadius(dp(radius)); return background;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() {
        super.onResume();
        if (!displayedLanguage.equals(AppLocale.language(this))) recreate();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putParcelableArrayList("files", files);
        state.putString("directTarget", directTarget); state.putString("selectedIp", selectedIp);
        state.putString("selectedLabel", selectedLabel);
    }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
