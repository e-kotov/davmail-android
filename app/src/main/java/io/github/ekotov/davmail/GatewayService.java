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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Foreground service that keeps DavMail's CalDAV listener running. */
public class GatewayService extends Service {
    private static final String CHANNEL = "gateway";
    private static final int NOTIFICATION_ID = 1;

    static volatile String status = "Stopped";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ConfigReloader reloader;
    private ScheduledFuture<?> polling;
    private volatile boolean destroyed;

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
        reloader = new ConfigReloader(Config.store(this),
                text -> Gateway.start(text, Config.effectiveFile(this), Config.logFile(this), this));
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
        worker.execute(() -> {
            if (destroyed) { return; }
            reloader.start();
            publishStatus();
            if (polling == null) {
                polling = worker.scheduleWithFixedDelay(() -> {
                    if (!destroyed) {
                        reloader.poll();
                        publishStatus();
                    }
                }, 5, 5, TimeUnit.SECONDS);
            }
        });
        return START_STICKY;
    }

    private void publishStatus() {
        String next = reloader.status();
        if (next.equals(status)) { return; }
        status = next;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(status));
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        worker.execute(() -> {
            if (polling != null) { polling.cancel(false); }
            Gateway.stop(this);
        });
        worker.shutdown();
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
