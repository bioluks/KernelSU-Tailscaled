package com.askingmeric.tailmate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class RootCommand {
    static final String CONTROL = "/data/adb/modules/TailMate/control.sh";

    // Managers expose su at different paths; the app PATH is not guaranteed to
    // contain any of them. Try the known absolute locations before "su".
    private static final String[] SU_CANDIDATES = {
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
            "/su/bin/su", "/system/xbin/su", "/system/bin/su", "su",
    };

    private static String suPath() {
        for (String candidate : SU_CANDIDATES) {
            if (candidate.contains("/")) {
                if (!new java.io.File(candidate).canExecute()) continue;
            }
            return candidate;
        }
        return "su";
    }

    private static ProcessBuilder rootProcess(String... command) {
        String[] su = new String[command.length + 2];
        su[0] = suPath();
        su[1] = "-c";
        System.arraycopy(command, 0, su, 2, command.length);
        return new ProcessBuilder(su);
    }

    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList(
            "status-json", "enable", "disable", "restart", "logs", "diagnostics", "web-restart",
            "taildrop-targets", "taildrop-save-dir", "login-server",
            "enable-dns", "disable-dns", "enable-system-dns", "disable-system-dns", "private-dns-off",
            "migration-status", "migrate-legacy", "apply-staged",
            "engine-status", "engine-check", "engine-prepare", "engine-apply"));

    private static long timeoutSeconds(String action) {
        switch (action) {
            case "engine-prepare": return 180;
            case "login-server": case "engine-apply": case "apply-staged": case "migrate-legacy": return 90;
            default: return 60;
        }
    }

    static final class Capture extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private IOException error;
        Capture(InputStream input) { this.input = input; start(); }
        @Override public void run() {
            byte[] buffer = new byte[4096];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int keep = Math.min(count, 524288 - bytes.size());
                    if (keep > 0) bytes.write(buffer, 0, keep);
                }
            } catch (IOException e) { error = e; }
        }
        String result() throws IOException, InterruptedException {
            join(3000);
            if (isAlive()) throw new IOException("Root command output did not close");
            if (error != null) throw error;
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    static String run(String action) throws Exception { return run(action, null); }

    /** Runs an allowlisted control action; {@code input} is written as one stdin line, never as argv. */
    static String run(String action, String input) throws Exception {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported action");
        Process process = rootProcess("exec sh " + CONTROL + " " + action)
                .redirectErrorStream(true).start();
        try {
            Capture capture = new Capture(process.getInputStream());
            try (java.io.OutputStream stdin = process.getOutputStream()) {
                if (input != null) stdin.write((input + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(timeoutSeconds(action), TimeUnit.SECONDS)) throw new IOException("Root command timed out");
            String output = capture.result();
            if (process.exitValue() != 0) throw new IOException(output.isEmpty()
                    ? "Root denied or module unavailable. Install the module and grant root access." : output);
            return output;
        } finally { process.destroy(); }
    }

    static Process send() throws IOException {
        // Include /proc start time: a recycled PID must never authorize a kill.
        String handshake = "IFS= read -r s < /proc/$$/stat || exit; s=${s##*) }; "
                + "i=1; while [ $i -lt 20 ]; do s=${s#* }; i=$((i+1)); done; "
                + "echo TM_PID=$$:${s%% *}; exec sh " + CONTROL + " taildrop-send";
        return rootProcess(handshake).redirectErrorStream(true).start();
    }

    static void cancel(String identity) {
        if (identity == null || !identity.matches("[1-9][0-9]{0,9}:[0-9]{1,20}")) return;
        String[] fields = identity.split(":", 2);
        // Validate start time and exact NUL-delimited argv, not flattened substrings.
        String script = "p=" + fields[0] + "; expected=" + fields[1] + "; "
                + "IFS= read -r s < /proc/$p/stat || exit; s=${s##*) }; "
                + "i=1; while [ $i -lt 20 ]; do s=${s#* }; i=$((i+1)); done; "
                + "[ \"${s%% *}\" = \"$expected\" ] || exit; "
                + "exec 7< /proc/$p/cmdline || exit; "
                + "IFS= read -r -d '' a <&7; IFS= read -r -d '' b <&7; "
                + "IFS= read -r -d '' c <&7; IFS= read -r -d '' d <&7; "
                + "e=$(readlink /proc/$p/exe) || exit; "
                + "case \"$b\" in "
                + "/data/adb/modules/TailMate/control.sh|"
                + "/data/adb/TailMate/releases/*/scripts/runtime-control.sh|"
                + "/data/adb/TailMate/releases/*/tailscale/scripts/tailscale-service) "
                + "[ \"$e\" = \"$(readlink -f /system/bin/sh)\" ] && "
                + "[ \"$c\" = taildrop-send ] && [ -z \"$d\" ] || exit ;; "
                + "--socket=/data/adb/TailMate/run/tailscaled.sock) "
                + "[ \"$c\" = file ] && [ \"$d\" = cp ] || exit; "
                + "case \"$a\" in /data/adb/TailMate/releases/*/bin/tailscale|"
                + "/data/adb/TailMate/engines/*/bin/tailscale) ;; *) exit ;; esac; "
                + "[ \"$e\" = \"$(readlink -f \"$a\")\" ] || exit; "
                + "IFS= read -r -d '' n <&7; IFS= read -r -d '' dash <&7; "
                + "IFS= read -r -d '' target <&7; IFS= read -r -d '' extra <&7; "
                + "case \"$n\" in --name=*) ;; *) exit ;; esac; "
                + "[ \"$dash\" = - ] && [ -z \"$extra\" ] || exit ;; *) exit ;; esac; "
                + "kill -TERM $p";
        try {
            Process killer = rootProcess(script).start();
            killer.waitFor(5, TimeUnit.SECONDS);
            killer.destroy();
        } catch (Exception ignored) { /* Stream closure also aborts an in-progress upload. */ }
    }
}
