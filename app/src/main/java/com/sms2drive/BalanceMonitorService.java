package com.sms2drive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class BalanceMonitorService extends Service {

    private static final String TAG = "BalanceMonitorService";
    private static final String CHANNEL_FOREGROUND = "sms2drive_service_v2";
    private static final int FOREGROUND_NOTIF_ID = 9001;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification());
        Log.d(TAG, "서비스 시작");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 시스템이 종료해도 자동 재시작
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "서비스 종료");
    }

    private Notification buildForegroundNotification() {
        Intent launchIntent = new Intent(this, PinActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("경로당")
                .setContentText("")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)  // 최저 우선순위
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // 잠금화면 숨김
                .setSilent(true)  // 소리/진동 없음
                .build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            // 포그라운드 서비스 채널 (상태바 상주)
            NotificationChannel foregroundChannel = new NotificationChannel(
                    CHANNEL_FOREGROUND, "백그라운드 서비스",
                    NotificationManager.IMPORTANCE_MIN);  // 상태바 아이콘도 숨김
            foregroundChannel.setDescription("백그라운드에서 SMS를 수신합니다");
            foregroundChannel.setShowBadge(false);  // 앱 배지 숨김
            nm.createNotificationChannel(foregroundChannel);
        }
    }
}
