package com.askingmeric.tailmate;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The subset of the public {@code status-json} contract the companion acts on. */
final class ModuleStatus {
    /** One status-json diagnostic; severity is info, warning or error. */
    static final class Diagnostic {
        final String code;
        final String severity;
        final String message;
        /** Optional multi-line explanation (for example which chain/script). */
        final String detail;
        Diagnostic(String code, String severity, String message, String detail) {
            this.code = code; this.severity = severity; this.message = message; this.detail = detail;
        }
    }

    final boolean enabled;
    final String lifecycle;
    final String mode;
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
    /** Android Private DNS mode: off, opportunistic, hostname, or null when unreported. */
    final String privateDns;
    final String taildropDirectory;
    final boolean taildropReceiverRunning;
    final String moduleVersion;
    final String tailscaleVersion;
    final String dnsName;
    final String ipv4;
    final String ipv6;
    final java.util.List<Diagnostic> diagnostics;
    final String activeVersion;
    final String stagedVersion;
    final String previousVersion;
    final boolean migrationRequired;
    final boolean awaitingEnable;
    final String panelUrl;

    private ModuleStatus(JSONObject state) throws JSONException {
        enabled = state.getBoolean("enabled");
        lifecycle = state.getString("lifecycle");
        mode = state.optString("mode", "native");
        moduleVersion = state.optString("moduleVersion");
        tailscaleVersion = state.optString("tailscaleVersion");
        JSONObject components = state.getJSONObject("components");
        daemonRunning = "running".equals(components.optString("daemon"));
        webRunning = "running".equals(components.optString("web"));
        JSONObject tailscale = state.optJSONObject("tailscale");
        String backend = text(tailscale, "BackendState");
        backendState = backend != null ? backend : enabled ? "Unknown" : "Stopped";
        JSONObject self = tailscale == null ? null : tailscale.optJSONObject("Self");
        String name = text(self, "DNSName");
        if (name != null && name.endsWith(".")) name = name.substring(0, name.length() - 1);
        dnsName = name != null ? name : text(self, "HostName");
        String v4 = null, v6 = null;
        JSONArray ips = tailscale == null ? null : tailscale.optJSONArray("TailscaleIPs");
        if (ips != null) for (int i = 0; i < ips.length(); i++) {
            String ip = ips.optString(i);
            if (v4 == null && ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) v4 = ip;
            else if (v6 == null && ip.contains(":")) v6 = ip;
        }
        ipv4 = v4;
        ipv6 = v6;
        JSONObject prefs = state.optJSONObject("prefs");
        acceptDns = prefs != null && prefs.opt("CorpDNS") instanceof Boolean ? prefs.getBoolean("CorpDNS") : null;
        acceptRoutes = prefs != null && prefs.opt("RouteAll") instanceof Boolean ? prefs.getBoolean("RouteAll") : null;
        exitNode = text(prefs, "ExitNodeID");
        controlUrl = text(prefs, "ControlURL");
        JSONObject systemDns = state.optJSONObject("systemDns");
        hasSystemDns = systemDns != null;
        systemDnsEnabled = systemDns != null && systemDns.optBoolean("enabled");
        systemDnsApplied = systemDns != null && systemDns.optBoolean("applied");
        privateDns = text(systemDns, "privateDns");
        JSONObject taildrop = state.getJSONObject("taildrop");
        taildropDirectory = taildrop.getString("directory");
        taildropReceiverRunning = "running".equals(taildrop.optString("receiver"));
        JSONObject runtime = state.getJSONObject("runtime");
        activeVersion = text(runtime, "activeVersion");
        stagedVersion = text(runtime, "stagedVersion");
        previousVersion = text(runtime, "previousVersion");
        boolean required = "migration-required".equals(lifecycle);
        boolean awaiting = false;
        java.util.ArrayList<Diagnostic> list = new java.util.ArrayList<>();
        JSONArray raw = state.optJSONArray("diagnostics");
        if (raw != null) for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.getJSONObject(i);
            String code = item.optString("code");
            required |= "migration-required".equals(code);
            awaiting |= "migration-awaiting-enable".equals(code);
            list.add(new Diagnostic(code, item.optString("severity", "info"), item.optString("message"),
                    text(item, "detail")));
        }
        diagnostics = java.util.Collections.unmodifiableList(list);
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
