package com.sms2drive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class BusAlarmService extends Service {

    private static final String TAG = "BusAlarmService";

    // 인텐트 액션
    public static final String ACTION_START  = "com.sms2drive.BUS_ALARM_START";
    public static final String ACTION_STOP   = "com.sms2drive.BUS_ALARM_STOP";

    // 인텐트 extras
    public static final String EXTRA_ROUTE_ID     = "route_id";
    public static final String EXTRA_ROUTE_NO     = "route_no";
    public static final String EXTRA_BOARD_NM     = "board_nm";
    public static final String EXTRA_ALIGHT_ID    = "alight_id";
    public static final String EXTRA_ALIGHT_NM    = "alight_nm";
    public static final String EXTRA_ALIGHT_ORD   = "alight_ord";  // 하차 정류장 순번
    public static final String EXTRA_COLOR        = "color";

    // 알림 채널
    private static final String CH_FOREGROUND  = "bus_alarm_fg";
    private static final String CH_ALERT       = "bus_alarm_alert";
    private static final int    NOTIF_FG_ID    = 9002;
    private static final int    NOTIF_ALERT_ID = 9003;

    // API
    private static final String BUS_KEY   = "4f9182aa6a8d775a6013c074fc5620578371c0031a6f97e9c0434e3973bcf1d5";
    private static final String BUS_BASE2 = "https://apis.data.go.kr/1613000/";
    private static final String BUS_CITY  = "25";
    private static final String PREF_NAME = "sms2drive_prefs";

    // 폴링 간격 (30초)
    private static final long POLL_INTERVAL = 30_000L;
    // 하차 N정거장 전 알림
    private static final int ALERT_BEFORE_STOPS = 2;

    private Handler handler;
    private Runnable pollRunnable;

    private String routeId, routeNo, boardNm, alightId, alightNm, colorHex;
    private int alightOrd = -1;

    private boolean alarmFired = false; // 중복 알림 방지

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopAlarm();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            routeId   = intent.getStringExtra(EXTRA_ROUTE_ID);
            routeNo   = intent.getStringExtra(EXTRA_ROUTE_NO);
            boardNm   = intent.getStringExtra(EXTRA_BOARD_NM);
            alightId  = intent.getStringExtra(EXTRA_ALIGHT_ID);
            alightNm  = intent.getStringExtra(EXTRA_ALIGHT_NM);
            alightOrd = intent.getIntExtra(EXTRA_ALIGHT_ORD, -1);
            colorHex  = intent.getStringExtra(EXTRA_COLOR);
            if (colorHex == null) colorHex = "#0984E3";
            alarmFired = false;

            // 포그라운드 시작
            startForeground(NOTIF_FG_ID, buildForegroundNotif());
            Log.d(TAG, "하차알림 서비스 시작: " + routeNo + " → " + alightNm);

            // 폴링 시작
            startPolling();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (handler != null && pollRunnable != null)
            handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    // ── 폴링 ─────────────────────────────────────────
    private void startPolling() {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    try {
                        checkBusLocation();
                    } catch (Exception e) {
                        Log.e(TAG, "폴링 오류", e);
                    }
                    // 알람 안 울렸으면 재예약
                    if (!alarmFired) {
                        handler.postDelayed(this, POLL_INTERVAL);
                    }
                }).start();
            }
        };
        handler.post(pollRunnable);
    }

    private void checkBusLocation() throws Exception {
        // 버스 실시간 위치 조회
        String url = BUS_BASE2 + "BusLcInfoInqireService/getRouteAcctoBusLcList"
                + "?serviceKey=" + BUS_KEY
                + "&cityCode=" + BUS_CITY
                + "&routeId=" + routeId
                + "&numOfRows=50&pageNo=1&_type=xml";

        String xml = httpGet(url);
        if (xml == null || xml.isEmpty()) return;

        // 각 버스의 현재 정류장 순번 파싱
        // 하차 정류장 순번에서 ALERT_BEFORE_STOPS 빼면 알림 발동 순번
        int alertOrd = alightOrd - ALERT_BEFORE_STOPS;
        if (alertOrd < 1) alertOrd = 1;

        for (String item : xml.split("<item>")) {
            String nodeordStr = tag(item, "nodeord");
            if (nodeordStr.isEmpty()) continue;
            int nodeord;
            try { nodeord = Integer.parseInt(nodeordStr); } catch (Exception e) { continue; }

            // 버스가 alertOrd ~ alightOrd-1 구간에 있으면 알림
            if (nodeord >= alertOrd && nodeord < alightOrd) {
                int remaining = alightOrd - nodeord;
                fireAlarm(remaining);
                return;
            }
        }

        // 포그라운드 알림 텍스트 업데이트 (상태 표시)
        updateForegroundNotif("모니터링 중... " + routeNo + "번 → " + alightNm);
    }

    private void fireAlarm(int remainingStops) {
        if (alarmFired) return;
        alarmFired = true;

        Log.d(TAG, "하차 알림 발동! 남은 정거장: " + remainingStops);

        // 클릭 시 앱으로 복귀
        Intent launchIntent = new Intent(this, PinActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = remainingStops <= 0
                ? "🔔 곧 하차합니다!"
                : "🔔 " + remainingStops + "정거장 후 " + alightNm;
        String body = routeNo + "번 버스 | " + boardNm + " → " + alightNm;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL) // 소리 + 진동
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ALERT_ID, builder.build());

        // SharedPreferences 알림 해제 (자동 종료)
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit()
                .remove("alarm_alight_" + routeId + "_" + alightId)
                .remove("alarm_alight_last_" + routeId)
                .apply();

        // 서비스 종료
        stopSelf();
    }

    private void stopAlarm() {
        Log.d(TAG, "하차알림 서비스 수동 종료");
        if (handler != null && pollRunnable != null)
            handler.removeCallbacks(pollRunnable);
        stopForeground(true);
        stopSelf();
    }

    // ── 알림 빌더 ─────────────────────────────────────
    private Notification buildForegroundNotif() {
        return buildForegroundNotif("모니터링 시작 중...");
    }

    private Notification buildForegroundNotif(String text) {
        Intent launchIntent = new Intent(this, PinActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 알림 종료 버튼
        Intent stopIntent = new Intent(this, BusAlarmService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CH_FOREGROUND)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🚌 " + routeNo + "번 → " + alightNm + " 하차 알림")
                .setContentText(text)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_delete, "알림 종료", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .build();
    }

    private void updateForegroundNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_FG_ID, buildForegroundNotif(text));
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            // 포그라운드 상주 채널 (조용히)
            NotificationChannel fg = new NotificationChannel(
                    CH_FOREGROUND, "하차알림 모니터링", NotificationManager.IMPORTANCE_LOW);
            fg.setDescription("버스 하차 알림 모니터링 중");
            fg.setShowBadge(false);
            nm.createNotificationChannel(fg);

            // 알림 발동 채널 (소리+진동)
            NotificationChannel alert = new NotificationChannel(
                    CH_ALERT, "하차 알림", NotificationManager.IMPORTANCE_HIGH);
            alert.setDescription("버스 하차 정거장 도착 알림");
            alert.enableVibration(true);
            alert.setShowBadge(true);
            nm.createNotificationChannel(alert);
        }
    }

    // ── 유틸 ─────────────────────────────────────────
    private String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "httpGet 오류: " + urlStr, e);
            return "";
        }
    }

    private String tag(String xml, String tagName) {
        try {
            int s = xml.indexOf("<" + tagName + ">");
            int e = xml.indexOf("</" + tagName + ">");
            if (s < 0 || e < 0) return "";
            return xml.substring(s + tagName.length() + 2, e).trim();
        } catch (Exception ex) { return ""; }
    }
}
