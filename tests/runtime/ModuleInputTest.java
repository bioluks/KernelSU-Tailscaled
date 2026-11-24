package io.github.bioluks.tailscaled;

/** Cases mirror webroot-src/src/server.test.ts and taildrop.ts so both UIs accept the same input. */
public final class ModuleInputTest {
    private static void same(String expected, String actual, String input) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("For " + input + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        String[] unsafeServers = {
            "https://example.com'; id #", "https://example.com/$(id)",
            "https://user:password@example.com", "https://example.com?token=x",
            "https://example.com#fragment", "https://example.com\nextra",
            "https://example.com:65536", "file:///tmp/test", "javascript:alert(1)",
        };
        for (String value : unsafeServers) same(null, ModuleInput.controlUrl(value), value);
        same("http://192.168.1.2:8080", ModuleInput.controlUrl("http://192.168.1.2:8080/"), "trailing slash");
        same("https://[fd00::1]:8443", ModuleInput.controlUrl("https://[fd00::1]:8443"), "IPv6");
        same(ModuleInput.DEFAULT_CONTROL_URL, ModuleInput.controlUrl("  "), "blank");

        String[] goodFolders = {"/sdcard/Download", "/storage/emulated/0/Download", "/storage/ABCD-1234/Taildrop"};
        for (String value : goodFolders) same(value, ModuleInput.taildropDirectory(value), value);
        String[] badFolders = {
            "/sdcard/", "/sdcard", "/sdcard/../data", "/sdcard/a//b", "/sdcard/a/", "/storage/emulated/x",
            "/storage/emulated/Download", "/data/local/tmp", "/sdcard/a\nb",
        };
        for (String value : badFolders) same(null, ModuleInput.taildropDirectory(value), value);

        if (ModuleInput.safeAuthUrl("javascript:alert(1)")) throw new AssertionError("javascript auth URL");
        if (ModuleInput.safeAuthUrl("https://user:password@example.com/login")) throw new AssertionError("credential auth URL");
        if (!ModuleInput.safeAuthUrl("https://tscal.example.com/register/abc")) throw new AssertionError("valid auth URL");
    }
}
