package io.github.ekotov.davmail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground service that keeps DavMail's CalDAV listener running. */
public class GatewayService extends Service {
    private static final String CHANNEL = "gateway";
    private static final int NOTIFICATION_ID = 1;

    static volatile String status = "Stopped";

    static void start(Context context) {
        Config.setEnabled(context, true);
        context.startForegroundService(new Intent(context, GatewayService.class));
    }

    static void stop(Context context) {
        Config.setEnabled(context, false);
        context.stopService(new Intent(context, GatewayService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        status = "Starting";
        Notification notification = notification(status);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        // Binding and config reading stay off the main thread.
        new Thread(this::startGateway, "GatewayStart").start();
        return START_STICKY;
    }

    private void startGateway() {
        try {
            Config.read(this);
            int port = Gateway.start(Config.file(this), Config.effectiveFile(this), Config.logFile(this));
            status = "Listening on 127.0.0.1:" + port;
        } catch (Throwable e) {
            status = "Error: " + e.getMessage();
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
    }

    @Override
    public void onDestroy() {
        new Thread(Gateway::stop, "GatewayStop").start();
        status = "Stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
