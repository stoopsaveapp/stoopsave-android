package com.stoopsave.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives FCM pushes (expiry reminders, weekly digest) and shows them as
 * system notifications, even when the app is in the background or killed.
 *
 * The data payload carries the offer id / type; tapping the notification
 * opens the app (deep link into /app is handled by the page itself).
 */
public class StoopSaveMessagingService extends FirebaseMessagingService {

    public static final String CHANNEL_ID = "stoopsave-alerts";
    private static final int NOTIF_ID = 1001;

    @Override
    public void onNewToken(String token) {
        // The page picks the token up via the StoopSaveApp.getFcmToken()
        // bridge and registers it with /api/devices/register. Nothing to do
        // here besides letting the activity know a fresh token exists.
        MainActivity.onFcmTokenRefresh(token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String title = "StoopSave";
        String body = "";
        if (message.getNotification() != null) {
            if (message.getNotification().getTitle() != null) {
                title = message.getNotification().getTitle();
            }
            if (message.getNotification().getBody() != null) {
                body = message.getNotification().getBody();
            }
        }
        // Fall back to data payload if no notification block.
        if (body.isEmpty() && message.getData().containsKey("body")) {
            body = message.getData().get("body");
        }
        if (title.isEmpty() && body.isEmpty()) return;
        String deepLink = message.getData().get("url");
        showNotification(title, body, deepLink);
    }

    private void showNotification(String title, String body, String deepLinkUrl) {
        Context ctx = getApplicationContext();
        ensureChannel(ctx);

        Intent open;
        if (deepLinkUrl != null && !deepLinkUrl.isEmpty()
                && deepLinkUrl.startsWith("https://stoopsave.com/app")) {
            // Deep link (e.g. ?digest=1): fire as ACTION_VIEW so
            // MainActivity.handleAppLink routes it into the WebView.
            open = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deepLinkUrl));
            open.setClass(ctx, MainActivity.class);
        } else {
            open = new Intent(ctx, MainActivity.class);
        }
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, b.build());
    }

    /** Idempotent: creates the alerts channel on first use (API 26+). */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "StoopSave alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Offer reminders and the weekly digest.");
        nm.createNotificationChannel(ch);
    }
}
