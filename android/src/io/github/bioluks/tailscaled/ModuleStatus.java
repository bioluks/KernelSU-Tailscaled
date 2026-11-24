package io.github.bioluks.tailscaled;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The subset of the public {@code status-json} contract the companion acts on. */
final class ModuleStatus {
    final boolean enabled;
    final String lifecycle;
    final String backendState;
    final boolean daemonRunning;
    final boolean webRunning;
    final Boolean acceptDns;
    final Boolean acceptRoutes;
    final String exitNode;
    final String controlUrl;
    final boolean hasSystemDns;
    final boolean systemDnsEnabled;
    final boolean systemDnsApplied;
    final String taildropDirectory;
    final boolean taildropReceiverRunning;
    final String activeVersion;
    final String stagedVersion;
    final String previousVersion;
    final boolean migrationRequired;
    final boolean awaitingEnable;
    final String panelUrl;

    private ModuleStatus(JSONObject state) throws JSONException {
        enabled = state.getBoolean("enabled");
        lifecycle = state.getString("lifecycle");
        JSONObject components = state.getJSONObject("components");
        daemonRunning = "running".equals(components.optString("daemon"));
        webRunning = "running".equals(components.optString("web"));
        JSONObject tailscale = state.optJSONObject("tailscale");
        String backend = text(tailscale, "BackendState");
        backendState = backend != null ? backend : enabled ? "Unknown" : "Stopped";
        JSONObject prefs = state.optJSONObject("prefs");
        acceptDns = prefs != null && prefs.opt("CorpDNS") instanceof Boolean ? prefs.getBoolean("CorpDNS") : null;
        acceptRoutes = prefs != null && prefs.opt("RouteAll") instanceof Boolean ? prefs.getBoolean("RouteAll") : null;
        exitNode = text(prefs, "ExitNodeID");
        controlUrl = text(prefs, "ControlURL");
        JSONObject systemDns = state.optJSONObject("systemDns");
        hasSystemDns = systemDns != null;
        systemDnsEnabled = systemDns != null && systemDns.optBoolean("enabled");
        systemDnsApplied = systemDns != null && systemDns.optBoolean("applied");
        JSONObject taildrop = state.getJSONObject("taildrop");
        taildropDirectory = taildrop.getString("directory");
        taildropReceiverRunning = "running".equals(taildrop.optString("receiver"));
        JSONObject runtime = state.getJSONObject("runtime");
        activeVersion = text(runtime, "activeVersion");
        stagedVersion = text(runtime, "stagedVersion");
        previousVersion = text(runtime, "previousVersion");
        boolean required = "migration-required".equals(lifecycle);
        boolean awaiting = false;
        JSONArray diagnostics = state.optJSONArray("diagnostics");
        if (diagnostics != null) for (int i = 0; i < diagnostics.length(); i++) {
            String code = diagnostics.getJSONObject(i).optString("code");
            required |= "migration-required".equals(code);
            awaiting |= "migration-awaiting-enable".equals(code);
        }
        migrationRequired = required;
        awaitingEnable = awaiting;
        String listen = text(state, "webListen");
        if (listen == null) listen = "127.0.0.1:8088";
        if (!listen.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
                || Integer.parseInt(listen.substring(listen.indexOf(':') + 1)) > 65535) {
            throw new JSONException("Invalid panel address");
        }
        panelUrl = "http://" + listen + "/";
    }

    static ModuleStatus parse(String json) throws JSONException {
        return new ModuleStatus(new JSONObject(json));
    }

    /** Sign-in, DNS and folder changes need the owned daemon and a settled identity. */
    boolean daemonControlsBlocked() {
        return !enabled || !daemonRunning || migrationRequired || awaitingEnable;
    }

    /** Non-empty string value, or null for absent, JSON null, non-string or empty values. */
    private static String text(JSONObject object, String key) {
        if (object == null) return null;
        Object value = object.opt(key);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }
}
