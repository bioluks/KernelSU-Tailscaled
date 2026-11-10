package io.github.bioluks.tailscaled;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class ReceiveReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context base, Intent intent) {
        // Manifest DUMP permission restricts this entry to privileged senders.
        if (!"io.github.bioluks.tailscaled.RECEIVED".equals(intent.getAction())) return;
        String name = intent.getStringExtra("filename");
        if (name == null || name.isEmpty() || name.length() > 255 || name.contains("/")
                || name.contains("\\") || name.matches("(?s).*[\\p{Cntrl}].*")) return;
        Context context = AppLocale.wrap(base);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("received",
                context.getString(R.string.receive_channel), NotificationManager.IMPORTANCE_DEFAULT));
        if (!manager.areNotificationsEnabled()) return;
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify("received", name.hashCode(), new Notification.Builder(context, "received")
                .setSmallIcon(R.drawable.ic_module).setContentTitle(context.getString(R.string.received))
                .setContentText(name).setStyle(new Notification.BigTextStyle()
                        .bigText(name + "\n" + context.getString(R.string.received_note)))
                .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
    }
}
