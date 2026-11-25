package com.askingmeric.tailmate;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

/** Shows the module's loopback panel in-app; only that origin may load here. */
public final class PanelActivity extends Activity {
    static final String EXTRA_URL = "panelUrl";
    private WebView web;
    private LinearLayout failure;
    private String origin;

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppLocale.wrap(base)); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String url = getIntent().getStringExtra(EXTRA_URL);
        Uri parsed = url == null ? null : Uri.parse(url);
        if (parsed == null || !"http".equals(parsed.getScheme()) || !"127.0.0.1".equals(parsed.getHost())) {
            finish();
            return;
        }
        origin = "http://127.0.0.1:" + parsed.getPort();
        setTitle(R.string.panel);
        LinearLayout root = Ui.screen(this);
        root.addView(Ui.backBar(this, getString(R.string.panel)), new LinearLayout.LayoutParams(-1, -2));
        failure = failureView(url);
        failure.setVisibility(View.GONE);
        root.addView(failure, new LinearLayout.LayoutParams(-1, -2));
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (isPanel(target)) return false;
                // Sign-in links and any other site open outside the app.
                openExternal(target);
                return true;
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                // Usually a ROM per-app "Network access" switch: loopback is refused for this UID only.
                web.setVisibility(View.GONE);
                failure.setVisibility(View.VISIBLE);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        // API 33+ dispatches back here (predictive back); older releases use onKeyDown.
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        if (saved == null || web.restoreState(saved) == null) web.loadUrl(url);
    }

    /** Back walks the panel's history first, then returns to the companion. */
    private void back() {
        if (web.canGoBack()) web.goBack();
        else finish();
    }

    private boolean isPanel(Uri target) {
        return "http".equals(target.getScheme()) && origin.equals("http://" + target.getHost() + ":" + target.getPort());
    }

    private void openExternal(Uri target) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, target)); }
        catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    /** Explains a refused loopback load and offers app network settings, retry and the browser. */
    private LinearLayout failureView(String url) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        TextView message = Ui.text(this, getString(R.string.panel_blocked), 16, R.color.on_surface);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(message, new LinearLayout.LayoutParams(-1, -2));
        failureButton(box, R.string.panel_network_settings, () -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))));
        failureButton(box, R.string.panel_retry, () -> {
            box.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            web.loadUrl(url);
        });
        failureButton(box, R.string.panel_open_browser, () -> openExternal(Uri.parse(url)));
        return box;
    }

    private void failureButton(LinearLayout box, int label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(Ui.dp(this, 48));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = Ui.dp(this, 8);
        box.addView(button, layout);
    }

    /** Pre-33 back path; API 33+ uses the OnBackInvoked callback, so the key must not double-handle. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Build.VERSION.SDK_INT < 33 && keyCode == KeyEvent.KEYCODE_BACK && web != null) {
            back();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (web != null) web.saveState(state);
    }

    @Override public void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
