package io.github.bioluks.tailscaled;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class SendService extends Service {
    static final String CANCEL = "io.github.bioluks.tailscaled.CANCEL";
    static volatile boolean active;
    private volatile boolean cancelled;
    private volatile Process process;
    private volatile String rootPid;
    private volatile InputStream source;
    private PowerManager.WakeLock wake;
    private Context localized;
    private NotificationManager notifications;
    private String target;
    private long lastProgress;
    private CancellationSignal sourceCancellation;
    private int latestStartId;

    @Override public void onCreate() {
        super.onCreate();
        localized = AppLocale.wrap(this);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("sending",
                localized.getString(R.string.send_channel), NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel("results",
                localized.getString(R.string.result_channel), NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        latestStartId = id;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (CANCEL.equals(intent.getAction())) { cancel(); return START_NOT_STICKY; }
        if (active) return START_NOT_STICKY;
        target = intent.getStringExtra("target");
        ArrayList<Uri> files = intent.getParcelableArrayListExtra("files");
        if (target == null || !target.matches("[0-9.]{7,15}") || files == null || files.isEmpty()) {
            stopSelf(); return START_NOT_STICKY;
        }
        String label = intent.getStringExtra("label");
        active = true;
        cancelled = false;
        sourceCancellation = new CancellationSignal();
        Notification notification = progress(label == null ? target : label, "", 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, notification);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "tailscaled:send");
        wake.acquire(6 * 60 * 60 * 1000L);
        new Thread(() -> transfer(files, label == null ? target : label), "taildrop-send").start();
        return START_NOT_STICKY;
    }

    private Notification progress(String label, String detail, int percent, boolean indeterminate) {
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, SendService.class)
                .setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(localized, "sending").setSmallIcon(R.drawable.ic_module)
                .setContentTitle(localized.getString(R.string.sending, label)).setContentText(detail)
                .setProgress(100, percent, indeterminate).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, localized.getString(R.string.cancel), cancel).build())
                .build();
    }

    private void transfer(ArrayList<Uri> files, String label) {
        String title;
        String detail = label;
        try {
            for (int index = 0; index < files.size(); index++) {
                if (cancelled) throw new IOException("Cancelled");
                Uri uri = files.get(index);
                if (!"content".equals(uri.getScheme())) throw new IOException("Only granted content URIs are supported");
                String name = "shared-file";
                long size = -1;
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null,
                        sourceCancellation)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        if (!cursor.isNull(0)) name = cursor.getString(0);
                        if (!cursor.isNull(1)) size = cursor.getLong(1);
                    }
                }
                name = safeName(name);
                sourceCancellation.throwIfCanceled();
                AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r", sourceCancellation);
                if (descriptor == null) throw new IOException("Shared file is unavailable");
                try {
                    sourceCancellation.throwIfCanceled();
                    source = descriptor.createInputStream();
                } catch (Exception error) { descriptor.close(); throw error; }
                if (cancelled) { closeSource(); throw new IOException("Cancelled"); }
                process = RootCommand.send();
                if (cancelled) { process.destroy(); throw new IOException("Cancelled"); }
                InputStream reply = process.getInputStream();
                String first = readHandshake(reply);
                if (first == null || !first.matches("KST_PID=[1-9][0-9]{0,9}:[0-9]{1,20}"))
                    throw new IOException("Root denied or module unavailable");
                rootPid = first.substring(8);
                if (cancelled) { RootCommand.cancel(rootPid); throw new IOException("Cancelled"); }
                // Drain CLI output during upload so a full pipe cannot deadlock sending.
                RootCommand.Capture output = new RootCommand.Capture(reply);
                try (InputStream input = source; OutputStream upload = process.getOutputStream()) {
                    upload.write((target + "\n" + name + "\n").getBytes(StandardCharsets.UTF_8));
                    byte[] buffer = new byte[65536];
                    long sent = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled) throw new IOException("Cancelled");
                        upload.write(buffer, 0, count);
                        sent += count;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastProgress >= 500) {
                            lastProgress = now;
                            notifications.notify(1, progress(label, (index + 1) + "/" + files.size() + " · " + name,
                                    size > 0 ? (int) Math.min(100, sent * 100 / size) : 0, size <= 0));
                        }
                    }
                }
                source = null;
                int exit = process.waitFor();
                String message = output.result();
                if (exit != 0) throw new IOException(message.isEmpty() ? "Taildrop send failed (" + exit + ")" : message);
                rootPid = null;
                process = null;
            }
            if (cancelled) throw new IOException("Cancelled");
            title = localized.getString(R.string.sent, label);
        } catch (Exception e) {
            title = localized.getString(cancelled ? R.string.cancelled : R.string.failed);
            detail = cancelled ? label : String.valueOf(e.getMessage());
        } finally {
            closeSource();
            if (process != null) { RootCommand.cancel(rootPid); process.destroy(); }
        }
        String completedTitle = title;
        String completedDetail = detail;
        new Handler(Looper.getMainLooper()).post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            if (wake != null && wake.isHeld()) wake.release();
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notifications.notify(2, new Notification.Builder(localized, "results").setSmallIcon(R.drawable.ic_module)
                    .setContentTitle(completedTitle).setContentText(completedDetail)
                    .setStyle(new Notification.BigTextStyle().bigText(completedDetail))
                    .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
            active = false;
            SharedPreferences preferences = getSharedPreferences("companion", 0);
            preferences.edit().putString("lastTransfer", completedTitle + "\n" + completedDetail)
                    .putLong("transferRevision", preferences.getLong("transferRevision", 0) + 1).apply();
            stopSelfResult(latestStartId);
        });
    }

    static String safeName(String name) {
        name = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "shared-file";
        while (name.getBytes(StandardCharsets.UTF_8).length > 255)
            name = name.substring(0, name.offsetByCodePoints(name.length(), -1));
        return name;
    }

    private void closeSource() {
        InputStream input = source;
        source = null;
        if (input != null) try { input.close(); } catch (IOException ignored) { }
    }
    private void cancel() {
        cancelled = true;
        CancellationSignal signal = sourceCancellation;
        String identity = rootPid;
        InputStream input = source;
        Process current = process;
        new Thread(() -> {
            if (signal != null) signal.cancel();
            RootCommand.cancel(identity);
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (current != null) current.destroy();
        }, "taildrop-cancel").start();
    }
    @Override public void onTimeout(int startId, int type) { cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onDestroy() {
        if (active) cancel();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private static String readHandshake(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder(32);
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (line.length() >= 64) throw new IOException("Invalid root handshake");
            line.append((char) value);
        }
        return line.toString();
    }
}
