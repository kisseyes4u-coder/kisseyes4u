package com.sms2drive;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

public class SmsReceiver extends BroadcastReceiver {

    /** 현재 연도에 맞는 sms_raw 파일명 반환 (예: sms_raw_2026.txt) */
    public static String getSmsRawFile() {
        String year = new SimpleDateFormat("yyyy", Locale.KOREA).format(new Date());
        return "sms_raw_" + year + ".txt";
    }

    /** 특정 연도의 sms_raw 파일명 반환 */
    public static String getSmsRawFile(int year) {
        return "sms_raw_" + year + ".txt";
    }

    private static final String TAG = "SmsReceiver";
    private static final String PREF_NAME = "sms2drive_prefs";

    // Drive에 저장된 FCM 토큰 목록 파일명
    private static final String FCM_TOKENS_FILE = "fcm_tokens.txt";

    // ── FCM V1 API - 서비스 계정 정보 ────────────────────────
    private static final String FCM_PROJECT_ID  = "sms2drive-74205";
    private static final String FCM_CLIENT_EMAIL = "firebase-adminsdk-fbsvc@sms2drive-74205.iam.gserviceaccount.com";
    private static final String FCM_PRIVATE_KEY_ID = "320da65ad6c6f4330231e5bb439be1f07ff4f8b4";
    private static final String FCM_PRIVATE_KEY =
            "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQD4AFM1d1MW4/NH" +
                    "8zK3P5vtDs1U3RAb5wHUoq4tm03XYWUr3m61VrXRkGGZ3/5W0IJGeJy4sDPoDqmn" +
                    "otQ9cjBXO5PMKcjaJ4WIE9rZ/xEeNB+slb4aJglh+qgNaun+fPSqRwKpip7mk9Sj" +
                    "L9BwqEwooEN8zNIWv3EAIVuNq2tRFBan9OI8ny6u77xfwQoJd1PzPd1NIyEsluNU" +
                    "eoDUuW3hSUvk+OVdcHeEFnDHN2YOzBuYT3+wrLuh9bFLWGcW7qA/iEjWREqcTWpN" +
                    "FluCJKgd6LTm3IPI96cOkdV6KQeUxCu+kLhvvobjQtO0yW1jUmBG1yGnnGn66ntn" +
                    "l/Axo9qtAgMBAAECggEAI5HqXVQKwRD7QwHU75cKPnc9yJL3XUUmDFEz8bshcEea" +
                    "Gu9pig1QgSj38rm/kUSRNtnUQqIVI868kxxl3BVNbSmQonsIwY9jFdghLtyoYCBi" +
                    "PZ7xl+8GL/0jTt9bheJ2thh+v4HwFgq0eB5iw1HEMJyB6Xu4E0ashrQCHWDtgMB8" +
                    "UQwCD3kxScyQscg7uwhpBXfRGBOG5g2Y8HPShjvPWB8V6wzbWtiMcYbUD2JtsAuf" +
                    "KI9WUprpFAXBNCtowayuem9vj3TxeOwrnAIXwMBcfBa22dnKoqtWC6glA4rttbop" +
                    "v7HHIUPBv9JFSDT2mc4oxa6Ww2NBnT+dZi7yKEQ/oQKBgQD/lMVNNuROFLl4wDkL" +
                    "GE/pBvfssbVBfj9KuR/RM8zSxnZD1m8/drcRIQcJGNIypt2qJ2eFJGf/A1bcupRM" +
                    "BG1IB5uT2Ab78OL+La5vExBILemGLf9TdLMWV+OTYQSwQO9UgI/dOp0jiaczS36m" +
                    "aJvvTkjj2RK1gsnPp+mExzxkIQKBgQD4aF/KonBSZXLXXMDozLlAZi/ut50nbAoL" +
                    "mrNGSjFdX0thfffSc6qxLapzPk+aBvzWhJcYOoRaKvH/yEYzkVNcgTc6bss6xztt" +
                    "inQY4qIzPyD5xqXSJUZnk19Y/aEtrKk7VXcihUdTY2lxmGiLFTD+4cqIrQErktT2" +
                    "BgF2ddolDQKBgFRfRX8HBvlryAq/0lUCUqcH4OKni8GyLqy5TnKemhhe4f3lFVar" +
                    "FyY1dAAhzIpiIb0hQwBmE1rRPGSjx38M2xKzSD3XS/7x982XQQV0EqTxWy0rlCV1" +
                    "2gUfQIaPuZ+B4EBSLKwIxIVN1P+PBaFj2U531oI5T/7RzVObB/EIYLxhAoGBALCM" +
                    "5kBL8U7uoY+lccpD7wpxVnHw+HYTWJRk0DQN+UXmu9m/wQpHgTLKRRIBYGwVuU/y" +
                    "Dr1+oaDAUx07R4HRMRFXGVyjcDgHcBprxBYHxcZsgNBlumdAbOiimqrSIOMoi2ML" +
                    "XFAhr875ofDFpM/tMNSGv/8iDuncQxXUsOdz3aZlAoGBAK1wUam4kFXZ0oRVWade" +
                    "wyGKi9fr6ppm55sadmJ5cK2zpQqmzNVnxKOr6YBTbserXJv5Vjw2KAhWweiSu4JQ" +
                    "UsyqKtFpu5FuZCsUo1jwkTh1CtZ7g1l3+oB0bzbjbDBI2rit5Sc7kdkkrJhCPhoS" +
                    "n98RteRmFwzfFsnMK2Hbazn+";

    // 수신할 발신번호
    private static final String[] SENDER_NUMBERS = {
            "15882100",   // 숫자만
            "1588-2100"   // 하이픈 형태
    };

    // 저장할 계좌 키워드
    public static final String[] FILTER_KEYWORDS = {
            "5510-13", "5510-83", "5510-53", "5510-23"
    };

    private static final String[][] ACCOUNT_MAP = {
            {"5510-13", "운영비"},
            {"5510-83", "부식비"},
            {"5510-53", "냉난방비"},
            {"5510-23", "회비"}
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        // 접근성 서비스에서 넘어온 브로드캐스트 처리
        if ("com.sms2drive.SMS_FROM_ACCESSIBILITY".equals(action)) {
            String body = intent.getStringExtra("body");
            if (body != null && !body.isEmpty()) {
                Log.d(TAG, "접근성 경로 SMS 처리: " + body);
                processMessage(context, body);
            }
            return;
        }

        if (!"android.provider.Telephony.SMS_RECEIVED".equals(action)) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        String format = bundle.getString("format");

        // SMS 본문 합치기 + 발신번호 추출
        StringBuilder fullMsg = new StringBuilder();
        String senderRaw = "";
        String senderNumber = "";

        for (Object pdu : pdus) {
            SmsMessage sms;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            } else {
                sms = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (sms != null) {
                fullMsg.append(sms.getMessageBody());
                if (senderRaw.isEmpty() && sms.getOriginatingAddress() != null) {
                    senderRaw   = sms.getOriginatingAddress().trim();
                    senderNumber = senderRaw.replaceAll("[^0-9]", "");
                }
            }
        }

        String body = fullMsg.toString();
        Log.d(TAG, "=== SMS 수신 ===");
        Log.d(TAG, "발신번호 원본: [" + senderRaw + "]");
        Log.d(TAG, "발신번호 숫자: [" + senderNumber + "]");
        Log.d(TAG, "본문: [" + body + "]");

        processMessage(context, senderNumber, body);
    }

    // 접근성 경로 (발신번호 없이)
    public void processMessage(Context context, String body) {
        String cleaned = body;
        int colonIdx = body.indexOf(": [Web발신]");
        if (colonIdx >= 0) {
            cleaned = body.substring(colonIdx + 2).trim();
        } else {
            int webIdx = body.indexOf("[Web발신]");
            if (webIdx >= 0) cleaned = body.substring(webIdx).trim();
        }
        processMessage(context, null, cleaned);
    }

    public void processMessage(Context context, String senderNumber, String body) {
        // 발신번호 필터
        if (senderNumber != null && !senderNumber.isEmpty()) {
            boolean senderOk = false;
            for (String s : SENDER_NUMBERS) {
                if (senderNumber.equals(s) || senderNumber.contains(s) || s.contains(senderNumber)) {
                    senderOk = true; break;
                }
            }
            if (!senderOk) {
                Log.d(TAG, "발신번호 불일치, 무시: " + senderNumber);
                return;
            }
        }

        // 계좌 키워드 포함 여부
        boolean matched = false;
        for (String kw : FILTER_KEYWORDS) {
            if (body.contains(kw)) { matched = true; break; }
        }
        if (!matched) return;

        // 원문 → 신형 5줄 변환
        String convertedTemp;
        try {
            convertedTemp = convertToNewFormat(body.trim());
            if (convertedTemp == null || convertedTemp.isEmpty()) convertedTemp = body.trim();
        } catch (Exception e) {
            Log.e(TAG, "convert error: " + e.getMessage());
            convertedTemp = body.trim();
        }
        final String converted = convertedTemp;

        // 타임스탬프 + 변환된 내용
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
        String entry = timestamp + "\n" + converted + "\n" + "-----------------------------------\n";

        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(context);
                String rawFile = getSmsRawFile();
                reader.readFile(rawFile, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        saveToDrive(context, existing + entry, converted, rawFile);
                    }
                    @Override public void onFailure(String error) {
                        saveToDrive(context, entry, converted, getSmsRawFile());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Drive read error: " + e.getMessage());
                saveToDrive(context, entry, converted, getSmsRawFile());
            }
        }).start();
    }

    // ── 구형 원문 → 신형 5줄 변환 ─────────────────────────────
    private String convertToNewFormat(String body) {
        try {
            String[] lines = body.split("\\r?\\n");
            String out1 = "", out2 = "", out3 = "", out4 = "", out5 = "";

            for (String rl : lines) {
                String t = rl.trim();
                if (t.isEmpty()) continue;
                t = t.replace("[Web발신]", "").trim();
                if (t.isEmpty()) continue;

                // 한 줄에 출금/입금+날짜+계좌+잔액 모두 포함 (접근성 수신 형태)
                if ((t.contains("출금") || t.contains("입금")) && t.contains("잔액")
                        && t.matches(".*\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}.*")) {
                    java.util.regex.Matcher om = java.util.regex.Pattern
                            .compile("(농협\\s*(출금|입금)\\s*[\\d,]+원)").matcher(t);
                    if (om.find()) out1 = om.group(1).replaceAll("(출금|입금)(\\d)", "$1 $2");
                    java.util.regex.Matcher dm2 = java.util.regex.Pattern
                            .compile("(\\d{2}/\\d{2}\\s+\\d{2}:\\d{2})").matcher(t);
                    if (dm2.find()) out2 = convertDateTimeToKorean(dm2.group(1));
                    java.util.regex.Matcher am2 = java.util.regex.Pattern
                            .compile("(351-[\\S]+)").matcher(t);
                    if (am2.find()) {
                        String acct = am2.group(1);
                        out3 = addAccountName(acct);
                        String after = t.substring(t.indexOf(acct) + acct.length()).trim();
                        if (!after.isEmpty()) {
                            if (after.contains("잔액")) {
                                int idx = after.indexOf("잔액");
                                String store = after.substring(0, idx).trim();
                                String bal = after.substring(idx).replaceAll("잔액(\\d)", "잔액 $1");
                                if (!store.isEmpty()) out4 = store;
                                out5 = bal;
                            }
                        }
                    }
                    continue;
                }

                if ((t.contains("출금") || t.contains("입금")) && !t.contains("잔액")) {
                    out1 = t.replaceAll("(출금|입금)(\\d)", "$1 $2");
                } else if (t.matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}.*")) {
                    java.util.regex.Matcher dm = java.util.regex.Pattern
                            .compile("(\\d{2}/\\d{2}\\s+\\d{2}:\\d{2})").matcher(t);
                    if (dm.find()) out2 = convertDateTimeToKorean(dm.group(1));
                    java.util.regex.Matcher am = java.util.regex.Pattern
                            .compile("(351-[\\S]+|676-[\\S]+)").matcher(t);
                    if (am.find()) {
                        String acctPart = am.group(1);
                        out3 = addAccountName(acctPart);
                        String afterAcct = t.substring(t.indexOf(acctPart) + acctPart.length()).trim();
                        if (!afterAcct.isEmpty()) {
                            if (afterAcct.contains("잔액")) {
                                int idx = afterAcct.indexOf("잔액");
                                String storePart = afterAcct.substring(0, idx).trim();
                                String balPart = afterAcct.substring(idx).replaceAll("잔액\\s*(\\d)", "잔액 $1");
                                if (!storePart.isEmpty() && out4.isEmpty()) out4 = storePart;
                                if (out5.isEmpty()) out5 = balPart;
                            } else if (out4.isEmpty()) {
                                out4 = afterAcct;
                            }
                        }
                    }
                    java.util.regex.Matcher bm = java.util.regex.Pattern
                            .compile("잔액\\s*([\\d,]+원)").matcher(t);
                    if (bm.find()) out5 = "잔액 " + bm.group(1);
                } else if (t.contains("351-") && t.contains("5510-")) {
                    String[] parts = t.trim().split("[\\s\\t]+", 2);
                    out3 = addAccountName(parts[0]);
                    if (parts.length >= 2) {
                        String rest = parts[1].trim();
                        if (rest.contains("잔액")) {
                            int idx = rest.indexOf("잔액");
                            String storePart = rest.substring(0, idx).trim();
                            String balPart = rest.substring(idx).replaceAll("잔액(\\d)", "잔액 $1");
                            if (!storePart.isEmpty() && out4.isEmpty()) out4 = storePart;
                            if (out5.isEmpty()) out5 = balPart;
                        } else if (out4.isEmpty()) {
                            out4 = rest;
                        }
                    }
                } else if (t.contains("잔액")) {
                    int idx = t.indexOf("잔액");
                    String before = t.substring(0, idx).trim();
                    String after = t.substring(idx).replaceAll("잔액\\s*(\\d)", "잔액 $1");
                    if (!before.isEmpty() && out4.isEmpty()) out4 = before;
                    out5 = after;
                } else {
                    if (out4.isEmpty()) out4 = t;
                    else if (!out5.isEmpty()) { /* 잔액 이후 무시 */ }
                    else out4 = out4 + " " + t;
                }
            }

            StringBuilder sb = new StringBuilder();
            if (!out1.isEmpty()) sb.append(out1).append("\n");
            if (!out2.isEmpty()) sb.append(out2).append("\n");
            if (!out3.isEmpty()) sb.append(out3).append("\n");
            if (!out4.isEmpty()) sb.append(out4).append("\n");
            if (!out5.isEmpty()) sb.append(out5);
            String result = sb.toString().trim();
            return result.isEmpty() ? body : result;
        } catch (Exception e) {
            Log.e(TAG, "convertToNewFormat error: " + e.getMessage());
            return body;
        }
    }

    private String addAccountName(String accountNo) {
        for (String[] info : ACCOUNT_MAP) {
            if (accountNo.contains(info[0])) return accountNo + " (" + info[1] + ")";
        }
        return accountNo;
    }

    private String convertDateTimeToKorean(String dateTime) {
        try {
            String[] dt = dateTime.trim().split("\\s+");
            if (dt.length < 2) return dateTime;
            String[] dateParts = dt[0].split("/");
            String[] timeParts = dt[1].split(":");
            if (dateParts.length < 2 || timeParts.length < 2) return dateTime;
            int month = Integer.parseInt(dateParts[0]);
            int day   = Integer.parseInt(dateParts[1]);
            int hour24 = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            String ampm = hour24 < 12 ? "오전" : "오후";
            int hour12 = hour24 % 12;
            if (hour12 == 0) hour12 = 12;
            return month + "월 " + day + "일 " + ampm + " " + hour12 + "시 " + minute + "분";
        } catch (Exception e) {
            return dateTime;
        }
    }

    private void sendBalanceNotification(Context context, String converted) {
        try {
            String CHANNEL_ID = "sms2drive_balance";
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "잔액 변경 알림", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
            Intent launchIntent = new Intent(context, PinActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String title = "잔액 변경", body = "", accountName = "";
            for (String line : converted.split("\n")) {
                String t = line.trim();
                if ((t.contains("출금") || t.contains("입금")) && !title.contains("출금") && !title.contains("입금")) title = t;
                if (t.startsWith("잔액")) body = t;
                if      (t.contains("운영비"))  accountName = "[운영비]";
                else if (t.contains("부식비"))  accountName = "[부식비]";
                else if (t.contains("냉난방비")) accountName = "[냉난방비]";
                else if (t.contains("회비"))    accountName = "[회비]";
            }
            if (!accountName.isEmpty()) title = accountName + " " + title;
            if (body.isEmpty()) body = "잔액이 변경되었습니다. 확인하세요.";

            androidx.core.app.NotificationCompat.Builder builder =
                    new androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH);
            nm.notify((int) System.currentTimeMillis(), builder.build());
        } catch (Exception e) {
            Log.e(TAG, "알림 전송 실패: " + e.getMessage());
        }
    }

    public static void sendFcmDeleteSignal(Context context) {
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(context);
                reader.readFile(FCM_TOKENS_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String tokensContent) {
                        java.util.List<String> tokens = new java.util.ArrayList<>();
                        for (String line : tokensContent.split("\r?\n")) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            String[] parts = line.split("\\|");
                            if (parts.length >= 2) {
                                String email = parts[0].trim();
                                String token = parts[1].trim();
                                if (!token.isEmpty() && !email.equalsIgnoreCase("kisseyes4u@gmail.com"))
                                    tokens.add(token);
                            }
                        }
                        if (tokens.isEmpty()) return;
                        try {
                            String accessToken = getFcmAccessToken();
                            if (accessToken == null) return;
                            for (String token : tokens) {
                                try {
                                    JSONObject data = new JSONObject();
                                    data.put("type", "sms_deleted");
                                    JSONObject androidConfig = new JSONObject();
                                    androidConfig.put("priority", "high");
                                    JSONObject message = new JSONObject();
                                    message.put("token", token);
                                    message.put("data", data);
                                    message.put("android", androidConfig);
                                    JSONObject payload = new JSONObject();
                                    payload.put("message", message);
                                    String urlStr = "https://fcm.googleapis.com/v1/projects/" + FCM_PROJECT_ID + "/messages:send";
                                    URL url = new URL(urlStr);
                                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                    conn.setRequestMethod("POST");
                                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                                    conn.setRequestProperty("Content-Type", "application/json; UTF-8");
                                    conn.setDoOutput(true);
                                    conn.setConnectTimeout(10000);
                                    conn.setReadTimeout(10000);
                                    try (OutputStream os = conn.getOutputStream()) {
                                        os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                                    }
                                    int code = conn.getResponseCode();
                                    Log.d(TAG, "FCM 삭제신호 응답: " + code);
                                    conn.disconnect();
                                } catch (Exception e) {
                                    Log.e(TAG, "FCM 삭제신호 전송 오류: " + e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "sendFcmDeleteSignal 오류: " + e.getMessage());
                        }
                    }
                    @Override public void onFailure(String error) {}
                });
            } catch (Exception e) {
                Log.e(TAG, "sendFcmDeleteSignal 외부 오류: " + e.getMessage());
            }
        }).start();
    }

    public static void sendFcmToAllUsers(Context context, String converted) {
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(context);
                reader.readFile(FCM_TOKENS_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String tokensContent) {
                        String title = "잔액 변경", body = "통장 잔액이 변경되었습니다.";
                        for (String line : converted.split("\n")) {
                            String t = line.trim();
                            if ((t.contains("출금") || t.contains("입금")) && !title.contains("출금") && !title.contains("입금")) title = t;
                            if (t.startsWith("잔액")) body = t;
                        }
                        java.util.List<String> tokens = new java.util.ArrayList<>();
                        for (String line : tokensContent.split("\r?\n")) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            String[] parts = line.split("\\|");
                            if (parts.length >= 2) {
                                String email = parts[0].trim();
                                String token = parts[1].trim();
                                if (!token.isEmpty() && !email.equalsIgnoreCase("kisseyes4u@gmail.com"))
                                    tokens.add(token);
                            }
                        }
                        if (tokens.isEmpty()) return;
                        final String fTitle = title, fBody = body;
                        sendFcmBatch(tokens, fTitle, fBody, converted);
                    }
                    @Override public void onFailure(String error) {}
                });
            } catch (Exception e) {
                Log.e(TAG, "sendFcmToAllUsers 오류: " + e.getMessage());
            }
        }).start();
    }

    private static void sendFcmBatch(java.util.List<String> tokens, String title, String body, String converted) {
        try {
            String accessToken = getFcmAccessToken();
            if (accessToken == null) return;
            for (String token : tokens) sendFcmV1Single(accessToken, token, title, body, converted);
        } catch (Exception e) {
            Log.e(TAG, "FCM 배치 전송 오류: " + e.getMessage());
        }
    }

    private static String getFcmAccessToken() {
        try {
            long now = System.currentTimeMillis() / 1000L;
            JSONObject header = new JSONObject();
            header.put("alg", "RS256"); header.put("typ", "JWT"); header.put("kid", FCM_PRIVATE_KEY_ID);
            String headerB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));
            JSONObject claim = new JSONObject();
            claim.put("iss", FCM_CLIENT_EMAIL); claim.put("sub", FCM_CLIENT_EMAIL);
            claim.put("aud", "https://oauth2.googleapis.com/token");
            claim.put("scope", "https://www.googleapis.com/auth/firebase.messaging");
            claim.put("iat", now); claim.put("exp", now + 3600);
            String claimB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(claim.toString().getBytes(StandardCharsets.UTF_8));
            String signingInput = headerB64 + "." + claimB64;
            byte[] keyBytes = Base64.getDecoder().decode(FCM_PRIVATE_KEY);
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String jwt = signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
            URL url = new URL("https://oauth2.googleapis.com/token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true); conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
            String reqBody = "grant_type=" + java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8")
                    + "&assertion=" + java.net.URLEncoder.encode(jwt, "UTF-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(reqBody.getBytes(StandardCharsets.UTF_8)); }
            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            if (code == 200) return new JSONObject(sb.toString()).getString("access_token");
            Log.e(TAG, "토큰 교환 실패 " + code);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "getFcmAccessToken 오류: " + e.getMessage());
            return null;
        }
    }

    private static void sendFcmV1Single(String accessToken, String token, String title, String body, String converted) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "sms_updated");
            data.put("title", title);
            data.put("body", body);
            data.put("new_block", converted);
            JSONObject androidConfig = new JSONObject();
            androidConfig.put("priority", "high");
            JSONObject message = new JSONObject();
            message.put("token", token); message.put("data", data); message.put("android", androidConfig);
            JSONObject payload = new JSONObject();
            payload.put("message", message);
            String urlStr = "https://fcm.googleapis.com/v1/projects/" + FCM_PROJECT_ID + "/messages:send";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; UTF-8");
            conn.setDoOutput(true); conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "FCM V1 응답: " + conn.getResponseCode());
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "sendFcmV1Single 오류: " + e.getMessage());
        }
    }

    private static final String BALANCE_FILE = "balance.txt";
    private static final String[] ACCOUNT_KEYS = {"5510-13", "5510-83", "5510-53", "5510-23"};

    private void updateBalanceFile(Context context, String converted) {
        String acctKey = null, acctName = "";
        for (String[] info : ACCOUNT_MAP) {
            if (converted.contains(info[0])) { acctKey = info[0]; acctName = info[1]; break; }
        }
        if (acctKey == null) return;
        java.util.regex.Matcher bm = java.util.regex.Pattern
                .compile("잔액\\s*([\\d,]+원)").matcher(converted);
        if (!bm.find()) return;
        String amount = bm.group(1);
        String nowTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date());
        final String finalAcctKey = acctKey, finalAcctName = acctName,
                finalAmount = amount, finalTime = nowTime;
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(context);
                reader.readFile(BALANCE_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        StringBuilder sb = new StringBuilder();
                        boolean found = false;
                        for (String line : existing.split("\r?\n")) {
                            if (line.trim().isEmpty()) continue;
                            if (line.contains(finalAcctKey)) {
                                sb.append(finalAcctKey).append("|").append(finalAcctName).append("|")
                                        .append(finalAmount).append("|").append(finalTime).append("\n");
                                found = true;
                            } else { sb.append(line.trim()).append("\n"); }
                        }
                        if (!found) sb.append(finalAcctKey).append("|").append(finalAcctName).append("|")
                                .append(finalAmount).append("|").append(finalTime).append("\n");
                        saveBalanceFile(context, sb.toString().trim());
                    }
                    @Override public void onFailure(String error) {
                        saveBalanceFile(context, finalAcctKey + "|" + finalAcctName + "|" + finalAmount + "|" + finalTime);
                    }
                });
            } catch (Exception e) { Log.e(TAG, "updateBalanceFile 오류: " + e.getMessage()); }
        }).start();
    }

    private void saveBalanceFile(Context context, String content) {
        new Thread(() -> {
            try {
                DriveUploadHelper up = new DriveUploadHelper(context);
                up.uploadFileSync(content, BALANCE_FILE);
                Log.d(TAG, "balance.txt 업데이트 완료");
            } catch (Exception e) { Log.e(TAG, "balance.txt 저장 실패: " + e.getMessage()); }
        }).start();
    }

    // ★ 핵심 수정: SMS 수신 즉시 SharedPreferences 저장 + 위젯 갱신
    // 앱이 완전 종료 상태여도 위젯이 즉시 갱신됨
    private void saveBalanceToPrefsAndWidget(Context context, String converted) {
        String acctKey = null;
        for (String[] info : ACCOUNT_MAP) {
            if (converted.contains(info[0])) { acctKey = info[0]; break; }
        }
        if (acctKey == null) return;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("잔액\\s*([\\d,]+)원").matcher(converted);
        if (!m.find()) return;

        // "999,000원" 형태로 저장 (BalanceWidget 표시 형식과 통일)
        String amount  = m.group(1) + "원";
        String nowTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date());

        SharedPreferences.Editor editor = context
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.putString("bal_" + acctKey, amount);
        editor.putString("bal_time_" + acctKey, nowTime);
        editor.apply();

        // 위젯 즉시 갱신
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(context);
            int[] ids = awm.getAppWidgetIds(new ComponentName(context, BalanceWidget.class));
            for (int wid : ids) BalanceWidget.updateWidget(context, awm, wid);
            Log.d(TAG, "★ 관리자 위젯 즉시 갱신: " + acctKey + " = " + amount);
        } catch (Exception e) {
            Log.e(TAG, "위젯 갱신 오류: " + e.getMessage());
        }
    }

    private void saveToDrive(Context context, String content, String converted, String fileName) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
        String newBlock = timestamp + "\n" + converted;
        final int MAX_RETRY = 5;
        final int RETRY_DELAY_MS = 3000;
        new Thread(() -> {
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                try {
                    DriveUploadHelper uploader = new DriveUploadHelper(context);
                    uploader.uploadFileSync(content, fileName);
                    Log.d(TAG, "SMS saved to Drive (attempt " + attempt + ") file=" + fileName);
                    DriveReadHelper.invalidateCache(fileName);

                    // balance.txt 갱신 (Drive)
                    updateBalanceFile(context, converted);

                    // ★ SharedPreferences 즉시 저장 + 위젯 즉시 갱신 (앱 종료 상태에서도 동작)
                    saveBalanceToPrefsAndWidget(context, converted);

                    // ★ 관리자 앱 오픈 시 즉시 처리할 수 있도록 pending_new_block 저장
                    // (앱이 종료 상태에서 열릴 때 onResume에서 처리 - 관리자/일반사용자 공통)
                    context.getSharedPreferences("sms2drive_prefs", Context.MODE_PRIVATE)
                            .edit().putString("pending_new_block", newBlock).apply();
                    Log.d(TAG, "pending_new_block 저장 완료 (관리자)");

                    // 포그라운드 앱에 브로드캐스트
                    android.content.Intent broadcastIntent = new android.content.Intent("com.sms2drive.SMS_UPDATED");
                    broadcastIntent.putExtra("new_block", newBlock);
                    androidx.localbroadcastmanager.content.LocalBroadcastManager
                            .getInstance(context).sendBroadcast(broadcastIntent);
                    Log.d(TAG, "SMS_UPDATED 브로드캐스트 전송 완료");

                    // 관리자 로컬 알림
                    sendBalanceNotification(context, converted);

                    // 일반사용자 FCM 전송
                    sendFcmToAllUsers(context, newBlock);

                    return;
                } catch (Exception e) {
                    Log.e(TAG, "Drive upload error (attempt " + attempt + "): " + e.getMessage());
                    if (attempt < MAX_RETRY) {
                        try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ignored) {}
                    }
                }
            }
            Log.e(TAG, "Drive upload failed after " + MAX_RETRY + " attempts");
        }).start();
    }

    public static void saveFcmReceivedLogPublic(android.content.Context context) {
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs =
                        context.getSharedPreferences("sms2drive_prefs", android.content.Context.MODE_PRIVATE);
                String email = prefs.getString("user_email", "");
                if (email.isEmpty()) return;
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                String now = sdf.format(new java.util.Date());
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
                                new DriveUploadHelper(context).uploadFileSync(sb.toString().trim(), "fcm_received.txt");
                            } catch (Exception e) { Log.e("FCM", "수신기록 업로드 실패: " + e.getMessage()); }
                        }).start();
                    }
                    @Override public void onFailure(String error) {
                        new Thread(() -> {
                            try {
                                new DriveUploadHelper(context).uploadFileSync(newLine, "fcm_received.txt");
                            } catch (Exception e) { Log.e("FCM", "수신기록 업로드 실패: " + e.getMessage()); }
                        }).start();
                    }
                });
            } catch (Exception e) { Log.e("FCM", "saveFcmReceivedLogPublic 오류: " + e.getMessage()); }
        }).start();
    }

    /** 특정 토큰 1개에만 FCM 전송 (테스트용 - sms_updated) */
    public static void sendFcmToSpecificToken(Context context, String token,
                                              String title, String body, String newBlock) {
        new Thread(() -> {
            try {
                String accessToken = getFcmAccessToken();
                if (accessToken == null) return;
                sendFcmV1Single(accessToken, token, title, body, newBlock);
                Log.d(TAG, "특정 토큰 FCM 전송 완료");
            } catch (Exception e) {
                Log.e(TAG, "sendFcmToSpecificToken 오류: " + e.getMessage());
            }
        }).start();
    }

    /** 특정 토큰에 삭제 신호 전송 (sms_deleted 타입) */
    public static void sendDeleteSignalToToken(Context context, String token) {
        new Thread(() -> {
            try {
                String accessToken = getFcmAccessToken();
                if (accessToken == null) return;
                org.json.JSONObject data = new org.json.JSONObject();
                data.put("type", "sms_deleted");
                org.json.JSONObject androidConfig = new org.json.JSONObject();
                androidConfig.put("priority", "high");
                org.json.JSONObject message = new org.json.JSONObject();
                message.put("token", token);
                message.put("data", data);
                message.put("android", androidConfig);
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("message", message);
                String urlStr = "https://fcm.googleapis.com/v1/projects/" + FCM_PROJECT_ID + "/messages:send";
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Log.d(TAG, "삭제신호 전송 응답: " + conn.getResponseCode());
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "sendDeleteSignalToToken 오류: " + e.getMessage());
            }
        }).start();
    }

    /** convertToNewFormat의 public 래퍼 (PinActivity에서 호출 가능) */
    public String convertToNewFormatPublic(String body) {
        return convertToNewFormat(body);
    }


}