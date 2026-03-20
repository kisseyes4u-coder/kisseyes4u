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

    public static final String ACTION_START = "com.sms2drive.BUS_ALARM_START";
    public static final String ACTION_STOP  = "com.sms2drive.BUS_ALARM_STOP";

    public static final String EXTRA_ROUTE_ID   = "route_id";
    public static final String EXTRA_ROUTE_NO   = "route_no";
    public static final String EXTRA_BOARD_NM   = "board_nm";
    public static final String EXTRA_BOARD_NO   = "board_no";
    public static final String EXTRA_ALIGHT_ID  = "alight_id";
    public static final String EXTRA_ALIGHT_NM  = "alight_nm";
    public static final String EXTRA_ALIGHT_ORD = "alight_ord";
    public static final String EXTRA_COLOR      = "color";

    private static final String CH_FG    = "bus_alarm_fg";
    private static final String CH_ALERT = "bus_alarm_alert";
    private static final int NOTIF_FG_ID    = 9002;
    private static final int NOTIF_ALERT_ID = 9003;

    private static final String BUS_KEY   = "4f9182aa6a8d775a6013c074fc5620578371c0031a6f97e9c0434e3973bcf1d5";
    private static final String BUS_BASE2 = "https://apis.data.go.kr/1613000/";
    private static final String BUS_CITY  = "25";
    private static final String PREF_NAME = "sms2drive_prefs";
    private static final String PREF_SVC  = "bus_alarm_svc";

    private static final long POLL_INTERVAL = 30_000L;
    private static final int  ALERT_STOPS   = 2;

    private Handler  handler;
    private Runnable pollRunnable;

    private String routeId, routeNo, boardNm, boardNo, alightId, alightNm, colorHex;
    private int    alightOrd   = -1;
    private boolean alarmFired = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { restoreFromPrefs(); return START_STICKY; }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopAlarm(); return START_NOT_STICKY; }

        if (ACTION_START.equals(action)) {
            routeId   = intent.getStringExtra(EXTRA_ROUTE_ID);
            routeNo   = intent.getStringExtra(EXTRA_ROUTE_NO);
            boardNm   = intent.getStringExtra(EXTRA_BOARD_NM);
            boardNo   = intent.getStringExtra(EXTRA_BOARD_NO);
            alightId  = intent.getStringExtra(EXTRA_ALIGHT_ID);
            alightNm  = intent.getStringExtra(EXTRA_ALIGHT_NM);
            alightOrd = intent.getIntExtra(EXTRA_ALIGHT_ORD, -1);
            colorHex  = intent.getStringExtra(EXTRA_COLOR);
            if (colorHex == null) colorHex = "#0984E3";
            alarmFired = false;
            saveToPrefs();
            startForeground(NOTIF_FG_ID, buildFgNotif("버스 위치 확인 중..."));
            Log.d(TAG, "시작: " + routeNo + " → " + alightNm + " ord=" + alightOrd);
            startPolling();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (handler != null && pollRunnable != null) handler.removeCallbacks(pollRunnable);
        clearPrefs();
        super.onDestroy();
    }

    private void startPolling() {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        pollRunnable = new Runnable() {
            @Override public void run() {
                new Thread(() -> {
                    try { poll(); } catch (Exception e) { Log.w(TAG, "폴링 오류", e); }
                    if (!alarmFired) handler.postDelayed(this, POLL_INTERVAL);
                }).start();
            }
        };
        handler.post(pollRunnable);
    }

    private void poll() throws Exception {
        if (alightOrd > 0) pollByLocation();
        else                pollByArrival();
    }

    private void pollByLocation() throws Exception {
        String xml = httpGet(BUS_BASE2 + "BusLcInfoInqireService/getRouteAcctoBusLcList"
                + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                + "&routeId=" + routeId + "&numOfRows=50&pageNo=1&_type=xml");
        if (xml.isEmpty()) return;

        int alertOrd = Math.max(1, alightOrd - ALERT_STOPS);
        for (String item : xml.split("<item>")) {
            String ordStr = tag(item, "nodeord");
            if (ordStr.isEmpty()) continue;
            int ord;
            try { ord = Integer.parseInt(ordStr); } catch (Exception e) { continue; }
            if (ord >= alertOrd && ord < alightOrd) {
                fireAlarm(alightOrd - ord);
                return;
            }
        }
        updateFgNotif(routeNo + "번 모니터링 중 → " + alightNm);
    }

    private void pollByArrival() throws Exception {
        String xml = httpGet(BUS_BASE2 + "ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"
                + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                + "&nodeId=" + alightId + "&numOfRows=50&pageNo=1&_type=xml");
        if (xml.isEmpty()) return;

        for (String item : xml.split("<item>")) {
            if (!tag(item, "routeno").equals(routeNo)) continue;
            int arrSec  = -1; try { arrSec  = Integer.parseInt(tag(item, "arrtime")); }            catch(Exception ig){}
            int prevCnt = -1; try { prevCnt = Integer.parseInt(tag(item, "arrprevstationcnt")); }  catch(Exception ig){}

            if ((prevCnt >= 0 && prevCnt <= ALERT_STOPS) || (arrSec > 0 && arrSec <= 180)) {
                fireAlarm(prevCnt >= 0 ? prevCnt : 0);
                return;
            }
            String st = prevCnt > 0 ? prevCnt + "정거장 전" : (arrSec > 0 ? (arrSec/60) + "분 후" : "확인 중");
            updateFgNotif(routeNo + "번 " + alightNm + " · " + st);
            break;
        }
    }

    private void fireAlarm(int remaining) {
        if (alarmFired) return;
        alarmFired = true;

        Intent launch = new Intent(this, PinActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = remaining <= 0 ? "🔔 곧 하차합니다!" : "🔔 " + remaining + "정거장 후 " + alightNm;
        String body  = routeNo + "번 · " + boardNm + " → " + alightNm;

        Notification n = new NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ALERT_ID, n);

        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .remove("alarm_alight_" + routeId + "_" + alightId)
                .remove("alarm_alight_last_" + routeId)
                .apply();

        stopForeground(true);
        stopSelf();
    }

    private void stopAlarm() {
        if (handler != null && pollRunnable != null) handler.removeCallbacks(pollRunnable);
        stopForeground(true);
        stopSelf();
    }

    private Notification buildFgNotif(String text) {
        Intent launch = new Intent(this, PinActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent lPi = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopI = new Intent(this, BusAlarmService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent sPi = PendingIntent.getService(this, 1, stopI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = routeNo != null
                ? "🚌 " + routeNo + "번 → " + alightNm + " 하차 알림"
                : "🚌 하차 알림 대기 중";

        return new NotificationCompat.Builder(this, CH_FG)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(lPi)
                .addAction(android.R.drawable.ic_delete, "알림 종료", sPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .build();
    }

    private void updateFgNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_FG_ID, buildFgNotif(text));
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel fg = new NotificationChannel(CH_FG, "하차알림 모니터링", NotificationManager.IMPORTANCE_LOW);
        fg.setShowBadge(false);
        nm.createNotificationChannel(fg);
        NotificationChannel al = new NotificationChannel(CH_ALERT, "하차 알림", NotificationManager.IMPORTANCE_HIGH);
        al.enableVibration(true);
        nm.createNotificationChannel(al);
    }

    private void saveToPrefs() {
        getSharedPreferences(PREF_SVC, MODE_PRIVATE).edit()
                .putString("route_id",   routeId)
                .putString("route_no",   routeNo)
                .putString("board_nm",   boardNm != null ? boardNm : "")
                .putString("board_no",   boardNo  != null ? boardNo  : "")
                .putString("alight_id",  alightId)
                .putString("alight_nm",  alightNm)
                .putInt   ("alight_ord", alightOrd)
                .putString("color",      colorHex)
                .apply();
    }

    private void restoreFromPrefs() {
        SharedPreferences p = getSharedPreferences(PREF_SVC, MODE_PRIVATE);
        routeId   = p.getString("route_id",   null);
        routeNo   = p.getString("route_no",   null);
        boardNm   = p.getString("board_nm",   "");
        boardNo   = p.getString("board_no",   "");
        alightId  = p.getString("alight_id",  null);
        alightNm  = p.getString("alight_nm",  null);
        alightOrd = p.getInt   ("alight_ord", -1);
        colorHex  = p.getString("color",      "#0984E3");
        alarmFired = false;
        if (routeId != null && alightId != null) {
            Log.d(TAG, "재시작 복원: " + routeNo + " → " + alightNm);
            startForeground(NOTIF_FG_ID, buildFgNotif("재시작 후 모니터링 중..."));
            startPolling();
        } else { stopSelf(); }
    }

    private void clearPrefs() {
        getSharedPreferences(PREF_SVC, MODE_PRIVATE).edit().clear().apply();
    }

    private String httpGet(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close(); conn.disconnect();
            return sb.toString();
        } catch (Exception e) { Log.w(TAG, "httpGet: " + e.getMessage()); return ""; }
    }

    private String tag(String xml, String t) {
        try {
            int s = xml.indexOf("<" + t + ">"), e = xml.indexOf("</" + t + ">");
            return (s < 0 || e < 0) ? "" : xml.substring(s + t.length() + 2, e).trim();
        } catch (Exception ex) { return ""; }
    }
}
