package com.askingmeric.tailmate;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Client-side mirrors of the WebUI input rules ({@code server.ts}, {@code taildrop.ts}).
 * The module re-validates every value; these only give early, localized feedback.
 */
final class ModuleInput {
    static final String DEFAULT_CONTROL_URL = "https://controlplane.tailscale.com";
    private static final Pattern SERVER = Pattern.compile(
            "https?://(?:[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?|\\[[0-9a-fA-F:]+\\])"
                    + "(?::([0-9]{1,5}))?(?:/[a-zA-Z0-9._~%/-]*)?");
    private static final Pattern SHARED_STORAGE = Pattern.compile(
            "/sdcard/.+|/storage/(?:emulated/[0-9]+|self/primary|(?!(?:emulated|self)(?:/|$))[A-Za-z0-9_-]+)/.+",
            Pattern.DOTALL);
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f-\\u009f]");

    private ModuleInput() { }

    /** Normalized control-server base URL (blank selects Tailscale), or null when unsafe. */
    static String controlUrl(String value) {
        String input = value.trim();
        if (input.isEmpty()) input = DEFAULT_CONTROL_URL;
        if (input.length() > 2048) return null;
        java.util.regex.Matcher matcher = SERVER.matcher(input);
        if (!matcher.matches()) return null;
        if (matcher.group(1) != null && Integer.parseInt(matcher.group(1)) > 65535) return null;
        int end = input.length();
        while (end > 0 && input.charAt(end - 1) == '/') end--;
        return input.substring(0, end);
    }

    /** The folder unchanged when it is a safe shared-storage destination, otherwise null. */
    static String taildropDirectory(String value) {
        if (!SHARED_STORAGE.matcher(value).matches() || CONTROL.matcher(value).find()) return null;
        String[] parts = value.split("/", -1);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty() || ".".equals(parts[i]) || "..".equals(parts[i])) return null;
        }
        return value;
    }

    /** True for an http(s) sign-in link without embedded credentials. */
    static boolean safeAuthUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }
}
