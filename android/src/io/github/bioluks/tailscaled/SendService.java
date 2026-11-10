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
import android.text.format.Formatter;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshProgress = this::updateProgress;
    private volatile TransferProgress transferProgress;
    private volatile String preparingFile;
    private String notificationLabel;
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
        notificationLabel = label == null ? target : label;
        preparingFile = localized.getString(R.string.transfer_preparing_file, 1, files.size());
        transferProgress = null;
        Notification notification = progress(notificationLabel, preparingFile, "", 0, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, notification);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "tailscaled:send");
        wake.acquire(6 * 60 * 60 * 1000L);
        mainHandler.postDelayed(refreshProgress, 500);
        new Thread(() -> transfer(files, label == null ? target : label), "taildrop-send").start();
        return START_NOT_STICKY;
    }

    private Notification progress(String label, String file, String detail, int percent, boolean indeterminate) {
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, SendService.class)
                .setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String expanded = detail.isEmpty() ? file : file + "\n" + detail;
        return new Notification.Builder(localized, "sending").setSmallIcon(R.drawable.ic_module)
                .setContentTitle(localized.getString(R.string.sending, label))
                .setSubText(file).setContentText(detail.isEmpty() ? file : detail)
                .setStyle(new Notification.BigTextStyle().bigText(expanded))
                .setContentIntent(open)
                .setProgress(100, percent, indeterminate).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, localized.getString(R.string.cancel), cancel).build())
                .build();
    }

    private void updateProgress() {
        if (!active || cancelled) return;
        TransferProgress current = transferProgress;
        if (current == null) {
            notifications.notify(1, progress(notificationLabel, preparingFile, "", 0, true));
        } else {
            current.sample(SystemClock.elapsedRealtime());
            notifications.notify(1, progress(notificationLabel, current.fileLabel, transferDetail(current),
                    current.percent(), current.totalBytes() <= 0 || current.isFinalizing()));
        }
        // Sampling continues even while a provider, pipe write, or CLI exit is blocked.
        mainHandler.postDelayed(refreshProgress, 500);
    }

    private String transferDetail(TransferProgress current) {
        double rate = current.bytesPerSecond();
        String speed = localized.getString(rate >= 1000000 ? R.string.transfer_rate_mb : R.string.transfer_rate_kb,
                rate / (rate >= 1000000 ? 1000000 : 1000));
        String sent = Formatter.formatShortFileSize(localized, current.sentBytes());
        long total = current.totalBytes();
        String detail = total < 0
                ? localized.getString(R.string.transfer_unknown_size, sent, speed)
                : localized.getString(R.string.transfer_known_size, sent,
                        Formatter.formatShortFileSize(localized, total), speed);
        return current.isFinalizing() ? localized.getString(R.string.transfer_finalizing, detail) : detail;
    }

    private void transfer(ArrayList<Uri> files, String label) {
        String title;
        String detail = label;
        byte[] buffer = new byte[65536];
        try {
            for (int index = 0; index < files.size(); index++) {
                if (cancelled) throw new IOException("Cancelled");
                preparingFile = localized.getString(R.string.transfer_preparing_file, index + 1, files.size());
                transferProgress = null;
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
                preparingFile = localized.getString(R.string.transfer_file, index + 1, files.size(), name);
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
                    TransferProgress current = new TransferProgress(preparingFile, size, SystemClock.elapsedRealtime());
                    transferProgress = current;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled) throw new IOException("Cancelled");
                        upload.write(buffer, 0, count);
                        current.addBytes(count);
                    }
                    current.finishInput();
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
        mainHandler.post(() -> {
            mainHandler.removeCallbacks(refreshProgress);
            transferProgress = null;
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
        mainHandler.removeCallbacks(refreshProgress);
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
        mainHandler.removeCallbacks(refreshProgress);
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
