package com.sms2drive;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";
    private static final String PREF_NAME = "sms2drive_prefs";
    private static final String CHANNEL_ID = "sms2drive_channel";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Map<String, String> data = remoteMessage.getData();
        String type = data.get("type");
        Log.d(TAG, "FCM 수신 type=" + type);

        if ("sms_updated".equals(type)) {
            String newBlock = data.get("new_block");
            String title = data.get("title");
            String body  = data.get("body");
            Log.d(TAG, "sms_updated / new_block 길이=" + (newBlock != null ? newBlock.length() : 0));

            // 1) 위젯 갱신 (백그라운드에서도 동작)
            if (newBlock != null && !newBlock.isEmpty()) {
                updateWidgetFromBlock(newBlock);

                // ★ 앱 오픈 시 즉시 처리할 수 있도록 pending_new_block 저장
                // 앱이 백그라운드/종료 상태에서 열릴 때 onResume에서 처리
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .putString("pending_new_block", newBlock)
                        .apply();
                Log.d(TAG, "pending_new_block 저장 완료");
            }

            // 2) 알림 직접 표시 (data-only라 자동 알림 없음)
            showNotification(title != null ? title : "경로당",
                    body  != null ? body  : "새 문자가 도착했습니다");

            // 3) FCM 수신 기록 Drive에 저장
            saveFcmReceivedLog();

            // 4) 앱이 포그라운드면 화면도 갱신 (LocalBroadcast)
            Intent intent = new Intent("com.sms2drive.SMS_UPDATED");
            intent.putExtra("new_block", newBlock != null ? newBlock : "");
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(this).sendBroadcast(intent);

        } else if ("sms_deleted".equals(type)) {
            Log.d(TAG, "sms_deleted 수신");

            // 1) 앱이 포그라운드면 화면도 갱신
            Intent intent = new Intent("com.sms2drive.SMS_DELETED");
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                    .getInstance(this).sendBroadcast(intent);

            // 2) 앱 종료/백그라운드 상태에서 열릴 때 처리할 수 있도록 플래그 저장
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putBoolean("pending_delete", true)
                    .apply();
            Log.d(TAG, "pending_delete 저장 완료");

            // 3) 앱 종료 상태에서도 위젯 갱신
            updateWidgetAfterDelete();
        }
    }

    /** 삭제 후 Drive에서 최신 잔액 재읽기 → 위젯 갱신 */
    private void updateWidgetAfterDelete() {
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(this);
                String rawFile = SmsReceiver.getSmsRawFile();
                reader.readFile(rawFile, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String content) {
                        String[] accounts = {"5510-13", "5510-83", "5510-53", "5510-23"};
                        String[] blocks = content.split("-----------------------------------\r?\n");
                        String[] latestBal = new String[4];
                        for (int i = blocks.length - 1; i >= 0; i--) {
                            String block = blocks[i].trim();
                            if (block.isEmpty()) continue;
                            for (int j = 0; j < accounts.length; j++) {
                                if (latestBal[j] != null) continue;
                                if (!block.contains(accounts[j])) continue;
                                Matcher m = Pattern.compile("잔액\\s*([\\d,]+)원").matcher(block);
                                if (m.find()) latestBal[j] = m.group(1) + "원";
                            }
                            boolean allFound = true;
                            for (String b : latestBal) if (b == null) { allFound = false; break; }
                            if (allFound) break;
                        }
                        SharedPreferences.Editor editor =
                                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
                        for (int i = 0; i < accounts.length; i++) {
                            if (latestBal[i] != null)
                                editor.putString("bal_" + accounts[i], latestBal[i]);
                        }
                        editor.apply();
                        AppWidgetManager awm = AppWidgetManager.getInstance(
                                MyFirebaseMessagingService.this);
                        int[] ids = awm.getAppWidgetIds(new ComponentName(
                                MyFirebaseMessagingService.this, BalanceWidget.class));
                        for (int wid : ids)
                            BalanceWidget.updateWidget(MyFirebaseMessagingService.this, awm, wid);
                        Log.d(TAG, "삭제 후 위젯 갱신 완료");
                    }
                    @Override public void onFailure(String error) {
                        Log.e(TAG, "삭제 후 Drive 읽기 실패: " + error);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateWidgetAfterDelete 오류: " + e.getMessage());
            }
        }).start();
    }

    /** new_block에서 잔액 파싱 → prefs 저장 → 위젯 직접 갱신 */
    private void updateWidgetFromBlock(String block) {
        String[] accts = {"5510-13", "5510-83", "5510-53", "5510-23"};
        boolean updated = false;
        SharedPreferences.Editor editor =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        for (String acct : accts) {
            if (block.contains(acct)) {
                Matcher m = Pattern.compile("잔액\\s*([\\d,]+)원").matcher(block);
                if (m.find()) {
                    String amount = m.group(1) + "원";
                    editor.putString("bal_" + acct, amount);
                    Log.d(TAG, "위젯 저장: " + acct + " = " + amount);
                    updated = true;
                }
            }
        }
        if (updated) {
            editor.apply();
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            int[] ids = awm.getAppWidgetIds(new ComponentName(this, BalanceWidget.class));
            if (ids != null && ids.length > 0) {
                for (int wid : ids) BalanceWidget.updateWidget(this, awm, wid);
                Log.d(TAG, "위젯 갱신 완료 " + ids.length + "개");
            }
        }
    }

    /** data-only 메시지이므로 직접 알림 표시 */
    private void showNotification(String title, String body) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "경로당 알림", NotificationManager.IMPORTANCE_HIGH);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(
                this, 0,
                new Intent(this, PinActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // SharedPreferences에서 최신 잔액 읽기
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String bal0 = prefs.getString("bal_5510-13", "-");
        String bal1 = prefs.getString("bal_5510-83", "-");
        String bal2 = prefs.getString("bal_5510-53", "-");
        String bal3 = prefs.getString("bal_5510-23", "-");
        String balSummary = "▣ 운영비 "   + bal0 + "\n"
                + "▣ 부식비 "   + bal1 + "\n"
                + "▣ 냉난방비 " + bal2 + "\n"
                + "▣ 회비 "     + bal3;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(body + "\n\n" + balSummary))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(pi);
        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    public static void saveFcmReceivedLogPublic(android.content.Context context) {
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs =
                        context.getSharedPreferences("sms2drive_prefs",
                                android.content.Context.MODE_PRIVATE);
                String email = prefs.getString("user_email", "");
                if (email.isEmpty()) return;
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                java.util.Locale.KOREA);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                String now     = sdf.format(new java.util.Date());
                String newLine = email + "|" + now;
                DriveReadHelper reader = new DriveReadHelper(context);
                reader.readFile("fcm_received.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        StringBuilder sb = new StringBuilder();
                        boolean found = false;
                        for (String line : existing.split("\r?\n")) {
                            if (line.trim().isEmpty()) continue;
                            if (line.toLowerCase().startsWith(email.toLowerCase())) {
                                sb.append(newLine).append("\n"); found = true;
                            } else { sb.append(line.trim()).append("\n"); }
                        }
                        if (!found) sb.append(newLine).append("\n");
                        new Thread(() -> {
                            try {
                                new DriveUploadHelper(context)
                                        .uploadFileSync(sb.toString().trim(), "fcm_received.txt");
                            } catch (Exception e) {
                                Log.e("FCM", "수신기록 업로드 실패: " + e.getMessage());
                            }
                        }).start();
                    }
                    @Override public void onFailure(String error) {
                        new Thread(() -> {
                            try {
                                new DriveUploadHelper(context)
                                        .uploadFileSync(newLine, "fcm_received.txt");
                            } catch (Exception e) {
                                Log.e("FCM", "수신기록 업로드 실패: " + e.getMessage());
                            }
                        }).start();
                    }
                });
            } catch (Exception e) {
                Log.e("FCM", "saveFcmReceivedLogPublic 오류: " + e.getMessage());
            }
        }).start();
    }

    private void saveFcmReceivedLog() {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                String email = prefs.getString("user_email", "");
                if (email.isEmpty()) return;
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                java.util.Locale.KOREA);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                String now     = sdf.format(new java.util.Date());
                String newLine = email + "|" + now;
                DriveReadHelper reader = new DriveReadHelper(this);
                reader.readFile("fcm_received.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        StringBuilder sb = new StringBuilder();
                        boolean found = false;
                        for (String line : existing.split("\r?\n")) {
                            if (line.trim().isEmpty()) continue;
                            if (line.toLowerCase().startsWith(email.toLowerCase())) {
                                sb.append(newLine).append("\n"); found = true;
                            } else { sb.append(line.trim()).append("\n"); }
                        }
                        if (!found) sb.append(newLine).append("\n");
                        uploadFcmLog(sb.toString().trim());
                    }
                    @Override public void onFailure(String error) { uploadFcmLog(newLine); }
                });
            } catch (Exception e) { Log.e(TAG, "FCM 수신 기록 오류: " + e.getMessage()); }
        }).start();
    }

    private void uploadFcmLog(String content) {
        new Thread(() -> {
            try {
                new DriveUploadHelper(this).uploadFileSync(content, "fcm_received.txt");
                Log.d(TAG, "FCM 수신 기록 저장 완료");
            } catch (Exception e) { Log.e(TAG, "FCM 수신 기록 업로드 실패: " + e.getMessage()); }
        }).start();
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "새 FCM 토큰: " + token);
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putString("fcm_token", token)
                .putBoolean("fcm_token_dirty", true)
                .apply();
    }
}
