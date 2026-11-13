package com.dsacms.sheikhrequests.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.dsacms.sheikhrequests.MainActivity;
import com.dsacms.sheikhrequests.R;
import com.dsacms.sheikhrequests.SecureSessionStore;
import com.dsacms.sheikhrequests.SiteApiClient;

public final class SheikhMessagingService extends FirebaseMessagingService {
    public static final String EXTRA_REQUEST_ID = "question_id";
    private static final String CHANNEL_ID = "contact_requests";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        new Thread(() -> {
            try {
                String session = new SecureSessionStore(this).read();
                if (session != null && !session.isBlank()) {
                    new SiteApiClient().registerPushToken(session, token);
                    getSharedPreferences("push_status", MODE_PRIVATE).edit().putBoolean("registered", true).apply();
                }
            } catch (Exception ignored) {
                getSharedPreferences("push_status", MODE_PRIVATE).edit().putBoolean("registered", false).apply();
            }
        }, "fcm-token-register").start();
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);
        String requestId = message.getData().get(EXTRA_REQUEST_ID);
        if (requestId == null) requestId = message.getData().get("request_id");
        String title = message.getNotification() == null || message.getNotification().getTitle() == null
                ? "سؤال عام جديد" : message.getNotification().getTitle();
        String body = message.getNotification() == null || message.getNotification().getBody() == null
                ? "وصل سؤال جديد للشيخ." : message.getNotification().getBody();
        showNotification(title, body, requestId);
    }

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.notification_channel_description));
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void showNotification(String title, String body, String requestId) {
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (requestId != null && !requestId.isBlank()) openApp.putExtra(EXTRA_REQUEST_ID, requestId);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                requestId == null ? (int) System.currentTimeMillis() : requestId.hashCode(),
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        NotificationManagerCompat.from(this).notify(
                requestId == null ? (int) System.currentTimeMillis() : requestId.hashCode(), notification.build());
    }
}
