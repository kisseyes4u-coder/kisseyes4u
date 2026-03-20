package com.sms2drive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
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
import java.util.Base64;

import java.text.SimpleDateFormat;
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

    // Drive에 저장된 FCM 토큰 목록 파일명
    private static final String FCM_TOKENS_FILE = "fcm_tokens.txt";

    // ── FCM V1 API - 서비스 계정 정보 ────────────────────────
    private static final String FCM_PROJECT_ID  = "sms2drive-74205";
    private static final String FCM_CLIENT_EMAIL =
            "firebase-adminsdk-fbsvc@sms2drive-74205.iam.gserviceaccount.com";
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
            "15882100",    // 숫자만
            "1588-2100"    // 하이픈 형태
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
        String senderRaw    = "";  // 원본 (010-4792-2345 형태)
        String senderNumber = "";  // 숫자만 (01047922345 형태)
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
                    senderRaw    = sms.getOriginatingAddress().trim();
                    senderNumber = senderRaw.replaceAll("[^0-9]", "");
                }
            }
        }

        String body = fullMsg.toString();

        // 로그: 발신번호와 본문 확인
        Log.d(TAG, "=== SMS 수신 ===");
        Log.d(TAG, "발신번호 원본: [" + senderRaw + "]");
        Log.d(TAG, "발신번호 숫자: [" + senderNumber + "]");
        Log.d(TAG, "본문: [" + body + "]");

        processMessage(context, senderNumber, body);
    }

    // 실제 메시지 처리 (SMS 직접 수신 / 접근성 경로 공용)
    public void processMessage(Context context, String body) {
        // 접근성에서 오는 "NH농협 : " 헤더 제거
        String cleaned = body;
        int colonIdx = body.indexOf(": [Web발신]");
        if (colonIdx >= 0) {
            cleaned = body.substring(colonIdx + 2).trim(); // ": " 이후
        } else {
            int webIdx = body.indexOf("[Web발신]");
            if (webIdx >= 0) cleaned = body.substring(webIdx).trim();
        }
        processMessage(context, null, cleaned);
    }

    public void processMessage(Context context, String senderNumber, String body) {
        // 발신번호 필터 (senderNumber가 있을 때만 체크)
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
        // 계좌 키워드 포함된 문자만 처리
        boolean matched = false;
        for (String kw : FILTER_KEYWORDS) {
            if (body.contains(kw)) { matched = true; break; }
        }
        Log.d(TAG, "계좌키워드 매칭: " + matched);
        if (!matched) return;

        // 원문 → 신형 5줄로 변환 (실패시 원문 그대로)
        String convertedTemp;
        try {
            convertedTemp = convertToNewFormat(body.trim());
            if (convertedTemp == null || convertedTemp.isEmpty()) convertedTemp = body.trim();
        } catch (Exception e) {
            Log.e(TAG, "convert error: " + e.getMessage());
            convertedTemp = body.trim();
        }
        final String converted = convertedTemp;

        // 타임스탬프 + 변환된 내용 저장
        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(new Date());
        String entry = timestamp + "\n" + converted + "\n"
                + "-----------------------------------\n";

        // Drive에 이어쓰기 (백그라운드)
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(context);
                String rawFile = getSmsRawFile(); // 현재 연도 파일
                reader.readFile(rawFile, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        saveToDrive(context, existing + entry, converted, rawFile);
                    }
                    @Override public void onFailure(String error) {
                        // 파일이 없으면 새로 생성
                        saveToDrive(context, entry, converted, rawFile);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Drive read error: " + e.getMessage());
                saveToDrive(context, entry, converted, getSmsRawFile());
            }
        }).start();
    }

    // ── 구형 원문 → 신형 5줄 변환 ─────────────────────────────
    // [입력 예시1] 2줄:
    //   농협 출금65,000원
    //   03/09 15:47 351-****-5510-83 해동상회 잔액70,060원
    //
    // [입력 예시2] 5줄:
    //   농협 출금251,710원
    //   03/03 18:30
    //   351-****-5510-53
    //   아파트관리비
    //   잔액802,220원
    //
    // [출력 신형 5줄]:
    //   농협 출금 65,000원
    //   3월 9일 오후 3시 47분
    //   351-****-5510-83 (부식비)
    //   해동상회
    //   잔액 70,060원
    private String convertToNewFormat(String body) {
        try {
            String[] lines = body.split("\\r?\\n");

            String out1 = ""; // 출금/입금 줄
            String out2 = ""; // 날짜시간
            String out3 = ""; // 계좌번호
            String out4 = ""; // 가게명 (여러 줄 가능)
            String out5 = ""; // 잔액

            Log.d(TAG, "=== convertToNewFormat 시작 ===");
            Log.d(TAG, "전체줄수: " + lines.length);
            for (int li = 0; li < lines.length; li++) {
                Log.d(TAG, "줄[" + li + "]: [" + lines[li] + "] len=" + lines[li].length());
                // 각 문자 코드 출력
                StringBuilder hexLog = new StringBuilder();
                for (char c : lines[li].toCharArray()) hexLog.append(String.format("%04x ", (int)c));
                Log.d(TAG, "  hex: " + hexLog.toString().trim());
            }

            for (String rl : lines) {
                String t = rl.trim();
                if (t.isEmpty()) continue;
                // [Web발신] 태그 제거
                t = t.replace("[Web발신]", "").trim();
                if (t.isEmpty()) continue;

                // 한 줄에 출금/입금+날짜+계좌+잔액 모두 포함된 경우 (접근성 수신 형태)
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

                // ★ 농협+날짜+계좌 한 줄 형식: "농협03/20 20:23 351-****-5510-13"
                if (t.startsWith("농협") && t.matches("농협\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}.*")) {
                    java.util.regex.Matcher dm3 = java.util.regex.Pattern
                            .compile("(\\d{2}/\\d{2}\\s+\\d{2}:\\d{2})").matcher(t);
                    if (dm3.find()) out2 = convertDateTimeToKorean(dm3.group(1));
                    java.util.regex.Matcher am3 = java.util.regex.Pattern
                            .compile("(351-[\\S]+)").matcher(t);
                    if (am3.find()) out3 = addAccountName(am3.group(1));
                    continue;
                }

                // ★ 자동출금+가게명 한 줄: "자동출금8,170원((주)씨엠비)"
                if (t.contains("자동출금") || t.contains("자동입금")) {
                    // 금액 추출
                    java.util.regex.Matcher om2 = java.util.regex.Pattern
                            .compile("(자동(출금|입금)\\s*[\\d,]+원)").matcher(t);
                    if (om2.find()) {
                        out1 = om2.group(1).replaceAll("(출금|입금)(\\d)", "$1 $2");
                        // 금액 뒤 나머지를 가게명으로
                        String afterAmt = t.substring(om2.end()).trim();
                        if (!afterAmt.isEmpty() && out4.isEmpty()) {
                            // 괄호 처리: ((주)씨엠비) → (주)씨엠비
                            afterAmt = afterAmt.replaceAll("^\\(\\(", "(").replaceAll("\\)\\)$", ")");
                            out4 = afterAmt;
                        }
                    } else {
                        out1 = t;
                    }
                    continue;
                }

                // 출금/입금 줄: "농협 출금30,000원" / "농협 입금50,000원"
                if ((t.contains("출금") || t.contains("입금")) && !t.contains("잔액")) {
                    out1 = t.replaceAll("(출금|입금)(\\d)", "$1 $2");

                    // 날짜시간 줄: "03/10 07:01" 또는 "03/10 07:01 351-..." 혼합
                } else if (t.matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}.*")) {
                    // 날짜시간 추출
                    java.util.regex.Matcher dm = java.util.regex.Pattern
                            .compile("(\\d{2}/\\d{2}\\s+\\d{2}:\\d{2})")
                            .matcher(t);
                    if (dm.find()) out2 = convertDateTimeToKorean(dm.group(1));
                    // 같은 줄에 계좌번호가 있으면 추출
                    java.util.regex.Matcher am = java.util.regex.Pattern
                            .compile("(351-[\\S]+|676-[\\S]+)")
                            .matcher(t);
                    if (am.find()) {
                        String acctPart = am.group(1);
                        out3 = addAccountName(acctPart);
                        // 계좌번호 뒤 나머지: "노은상회 잔액40,060원" 또는 "노은상회" 또는 "잔액40,060원"
                        String afterAcct = t.substring(t.indexOf(acctPart) + acctPart.length()).trim();
                        if (!afterAcct.isEmpty()) {
                            if (afterAcct.contains("잔액")) {
                                int idx = afterAcct.indexOf("잔액");
                                String storePart = afterAcct.substring(0, idx).trim();
                                String balPart   = afterAcct.substring(idx).replaceAll("잔액\\s*(\\d)", "잔액 $1");
                                if (!storePart.isEmpty() && out4.isEmpty()) out4 = storePart;
                                if (out5.isEmpty()) out5 = balPart;
                            } else if (out4.isEmpty()) {
                                out4 = afterAcct;
                            }
                        }
                    }
                    // 같은 줄에 잔액이 있으면 추출
                    java.util.regex.Matcher bm = java.util.regex.Pattern
                            .compile("잔액\\s*([\\d,]+원)")
                            .matcher(t);
                    if (bm.find()) out5 = "잔액 " + bm.group(1);

                    // 계좌번호 줄: "351-****-5510-83" 또는 "676-02-****84"
                } else if (t.contains("351-") && t.contains("5510-")) {
                    // 공백/탭 기준으로 첫 토큰=계좌번호, 나머지=가게명
                    String[] parts = t.trim().split("[\\s\\t]+", 2);
                    out3 = addAccountName(parts[0]);
                    if (parts.length >= 2) {
                        String rest = parts[1].trim();
                        // 잔액이 포함된 경우 분리
                        if (rest.contains("잔액")) {
                            int idx = rest.indexOf("잔액");
                            String storePart = rest.substring(0, idx).trim();
                            String balPart   = rest.substring(idx).replaceAll("잔액(\\d)", "잔액 $1");
                            if (!storePart.isEmpty() && out4.isEmpty()) out4 = storePart;
                            if (out5.isEmpty()) out5 = balPart;
                        } else if (out4.isEmpty()) {
                            out4 = rest;
                        }
                    }

                    // 잔액 줄: "잔액40,060원" 또는 "노은상회 잔액40,060원"
                } else if (t.contains("잔액")) {
                    int idx = t.indexOf("잔액");
                    String before = t.substring(0, idx).trim();
                    String after  = t.substring(idx).replaceAll("잔액\\s*(\\d)", "잔액 $1");
                    if (!before.isEmpty() && out4.isEmpty()) out4 = before;
                    out5 = after;

                    // 그 외: 가게명으로 처리 (out4가 비어있을 때만)
                } else {
                    if (out4.isEmpty()) out4 = t;
                        // 이미 가게명 있으면 이어 붙임 (가게명이 2줄인 경우)
                    else if (!out5.isEmpty()) { /* 잔액 이후는 무시 */ }
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

    // "351-****-5510-83" → "351-****-5510-83 (부식비)"
    private String addAccountName(String accountNo) {
        for (String[] info : ACCOUNT_MAP) {
            if (accountNo.contains(info[0])) {
                return accountNo + " (" + info[1] + ")";
            }
        }
        return accountNo;
    }

    // "03/09 15:47" → "3월 9일 오후 3시 47분"
    private String convertDateTimeToKorean(String dateTime) {
        try {
            String[] dt = dateTime.trim().split("\\s+");
            if (dt.length < 2) return dateTime;
            String[] dateParts = dt[0].split("/");
            String[] timeParts = dt[1].split(":");
            if (dateParts.length < 2 || timeParts.length < 2) return dateTime;

            int month  = Integer.parseInt(dateParts[0]);
            int day    = Integer.parseInt(dateParts[1]);
            int hour24 = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);

            String ampm = hour24 < 12 ? "오전" : "오후";
            int hour12  = hour24 % 12;
            if (hour12 == 0) hour12 = 12;

            return month + "월 " + day + "일 " + ampm + " "
                    + hour12 + "시 " + minute + "분";
        } catch (Exception e) {
            return dateTime;
        }
    }

    private void sendBalanceNotification(Context context, String converted) {
        try {
            String CHANNEL_ID = "sms2drive_balance";
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);

            // 채널 생성 (Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "잔액 변경 알림",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("통장 잔액이 변경되면 알림을 보냅니다");
                nm.createNotificationChannel(channel);
            }

            // 알림 클릭 시 앱 실행
            Intent launchIntent = new Intent(context, PinActivity.class);
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // 알림 내용 파싱 (출금/입금 줄 + 잔액 줄 추출)
            String title = "잔액 변경";
            String body = "";
            String accountName = "";
            for (String line : converted.split("\n")) {
                String t = line.trim();
                if ((t.contains("출금") || t.contains("입금")) && !title.contains("출금") && !title.contains("입금")) {
                    title = t;
                }
                if (t.startsWith("잔액")) {
                    body = t;
                }
                // 계좌명 추출 (운영비/부식비/냉난방비/회비)
                if (t.contains("운영비")) accountName = "[운영비]";
                else if (t.contains("부식비")) accountName = "[부식비]";
                else if (t.contains("냉난방비")) accountName = "[냉난방비]";
                else if (t.contains("회비")) accountName = "[회비]";
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
            Log.d(TAG, "알림 전송: " + title + " / " + body);
        } catch (Exception e) {
            Log.e(TAG, "알림 전송 실패: " + e.getMessage());
        }
    }

    /**
     * 삭제 신호 FCM 전송 - 일반사용자 기기에 Drive 재로드 요청
     */
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
                                if (!token.isEmpty()
                                        && !email.equalsIgnoreCase("kisseyes4u@gmail.com")) {
                                    tokens.add(token);
                                }
                            }
                        }
                        if (tokens.isEmpty()) {
                            Log.d(TAG, "FCM 삭제 신호: 토큰 없음");
                            return;
                        }
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

                                    String urlStr = "https://fcm.googleapis.com/v1/projects/"
                                            + FCM_PROJECT_ID + "/messages:send";
                                    URL url = new URL(urlStr);
                                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                    conn.setRequestMethod("POST");
                                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                                    conn.setRequestProperty("Content-Type", "application/json; UTF-8");
                                    conn.setDoOutput(true);
                                    conn.setConnectTimeout(10000);
                                    conn.setReadTimeout(10000);
                                    byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                                    try (OutputStream os = conn.getOutputStream()) {
                                        os.write(payloadBytes);
                                    }
                                    int code = conn.getResponseCode();
                                    Log.d(TAG, "FCM 삭제신호 응답: " + code + " token=" + token.substring(0, 10) + "...");
                                    conn.disconnect();
                                } catch (Exception e) {
                                    Log.e(TAG, "FCM 삭제신호 전송 오류: " + e.getMessage());
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "sendFcmDeleteSignal 오류: " + e.getMessage());
                        }
                    }
                    @Override public void onFailure(String error) {
                        Log.d(TAG, "FCM 토큰 파일 없음: " + error);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "sendFcmDeleteSignal 외부 오류: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Drive의 fcm_tokens.txt 에서 토큰 목록을 읽어 FCM 푸시 전송
     * 포맷: 한 줄에 하나의 토큰 (이메일|토큰 형태)
     */
    public static void sendFcmToAllUsers(Context context, String converted) {
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(context);
                reader.readFile(FCM_TOKENS_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String tokensContent) {
                        // 제목/본문 파싱
                        String title = "잔액 변경";
                        String body  = "통장 잔액이 변경되었습니다.";
                        for (String line : converted.split("\n")) {
                            String t = line.trim();
                            if ((t.contains("출금") || t.contains("입금"))
                                    && !title.contains("출금") && !title.contains("입금")) {
                                title = t;
                            }
                            if (t.startsWith("잔액")) body = t;
                        }

                        // 토큰 목록 파싱 (이메일|토큰 형태)
                        java.util.List<String> tokens = new java.util.ArrayList<>();
                        for (String line : tokensContent.split("\r?\n")) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            String[] parts = line.split("\\|");
                            // 관리자 토큰은 제외 (자기 자신에게는 보내지 않음)
                            if (parts.length >= 2) {
                                String email = parts[0].trim();
                                String token = parts[1].trim();
                                if (!token.isEmpty()
                                        && !email.equalsIgnoreCase("kisseyes4u@gmail.com")) {
                                    tokens.add(token);
                                }
                            }
                        }

                        if (tokens.isEmpty()) {
                            Log.d(TAG, "FCM 토큰 없음 - 전송 생략");
                            return;
                        }

                        Log.d(TAG, "FCM 전송 대상: " + tokens.size() + "명");
                        final String fTitle = title;
                        final String fBody  = body;

                        // V1 API: 토큰별 개별 전송
                        sendFcmBatch(tokens, fTitle, fBody, converted);
                    }
                    @Override public void onFailure(String error) {
                        Log.d(TAG, "FCM 토큰 파일 없음: " + error);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "sendFcmToAllUsers 오류: " + e.getMessage());
            }
        }).start();
    }

    /**
     * FCM V1 API: 토큰 1개씩 전송 (V1은 multicast 미지원 → 루프)
     * 액세스 토큰은 JWT로 직접 생성 (외부 라이브러리 없이)
     */
    private static void sendFcmBatch(java.util.List<String> tokens, String title, String body, String converted) {
        try {
            String accessToken = getFcmAccessToken();
            if (accessToken == null) {
                Log.e(TAG, "액세스 토큰 획득 실패");
                return;
            }
            for (String token : tokens) {
                sendFcmV1Single(accessToken, token, title, body, converted);
            }
        } catch (Exception e) {
            Log.e(TAG, "FCM 배치 전송 오류: " + e.getMessage());
        }
    }

    /** JWT로 OAuth2 액세스 토큰 획득 */
    private static String getFcmAccessToken() {
        try {
            long now = System.currentTimeMillis() / 1000L;
            // JWT Header
            JSONObject header = new JSONObject();
            header.put("alg", "RS256");
            header.put("typ", "JWT");
            header.put("kid", FCM_PRIVATE_KEY_ID);
            String headerB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));

            // JWT Claim
            JSONObject claim = new JSONObject();
            claim.put("iss", FCM_CLIENT_EMAIL);
            claim.put("sub", FCM_CLIENT_EMAIL);
            claim.put("aud", "https://oauth2.googleapis.com/token");
            claim.put("scope", "https://www.googleapis.com/auth/firebase.messaging");
            claim.put("iat", now);
            claim.put("exp", now + 3600);
            String claimB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(claim.toString().getBytes(StandardCharsets.UTF_8));

            // 서명
            String signingInput = headerB64 + "." + claimB64;
            byte[] keyBytes = Base64.getDecoder().decode(FCM_PRIVATE_KEY);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String sigB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sig.sign());

            String jwt = signingInput + "." + sigB64;

            // 토큰 교환
            URL url = new URL("https://oauth2.googleapis.com/token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            String reqBody = "grant_type=" +
                    java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
                    "&assertion=" + java.net.URLEncoder.encode(jwt, "UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(reqBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();

            if (code == 200) {
                JSONObject resp = new JSONObject(sb.toString());
                String token = resp.getString("access_token");
                Log.d(TAG, "FCM 액세스 토큰 획득 성공");
                return token;
            } else {
                Log.e(TAG, "토큰 교환 실패 " + code + ": " + sb);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "getFcmAccessToken 오류: " + e.getMessage());
            return null;
        }
    }

    /** FCM V1 API로 단일 토큰에 전송 */
    private static void sendFcmV1Single(String accessToken, String token, String title, String body, String converted) {
        try {
            // data-only 메시지 (notification 필드 없음)
            // → 앱이 백그라운드/종료 상태에서도 onMessageReceived() 호출됨
            JSONObject data = new JSONObject();
            data.put("type", "sms_updated");
            data.put("title", title);
            data.put("body", body);
            data.put("new_block", converted);

            JSONObject androidConfig = new JSONObject();
            androidConfig.put("priority", "high");

            JSONObject message = new JSONObject();
            message.put("token", token);
            message.put("data", data);
            message.put("android", androidConfig);

            JSONObject payload = new JSONObject();
            payload.put("message", message);

            String urlStr = "https://fcm.googleapis.com/v1/projects/"
                    + FCM_PROJECT_ID + "/messages:send";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json; UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payloadBytes);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "FCM V1 응답: " + code + " token=" + token.substring(0, 10) + "...");
            conn.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "sendFcmV1Single 오류: " + e.getMessage());
        }
    }

    private static final String BALANCE_FILE = "balance.txt";
    private static final String[] ACCOUNT_KEYS = {"5510-13", "5510-83", "5510-53", "5510-23"};

    /**
     * converted(변환된 SMS 1건)에서 계좌번호+잔액 파싱 후 balance.txt 갱신
     * 포맷: 계좌번호|계좌명|잔액금액
     * 예: 5510-13|운영비|999,000원
     */
    private void updateBalanceFile(Context context, String converted) {
        // 계좌번호 추출
        String acctKey = null;
        String acctName = "";
        for (String[] info : ACCOUNT_MAP) {
            if (converted.contains(info[0])) {
                acctKey  = info[0];
                acctName = info[1];
                break;
            }
        }
        if (acctKey == null) return;

        // 잔액 추출: "잔액 999,000원" 형태
        java.util.regex.Matcher bm = java.util.regex.Pattern
                .compile("잔액\\s*([\\d,]+원)").matcher(converted);
        if (!bm.find()) return;
        String amount = bm.group(1);

        String nowTime = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date());
        final String finalAcctKey  = acctKey;
        final String finalAcctName = acctName;
        final String finalAmount   = amount;
        final String finalTime     = nowTime;

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
                                sb.append(finalAcctKey).append("|")
                                        .append(finalAcctName).append("|")
                                        .append(finalAmount).append("|")
                                        .append(finalTime).append("\n");
                                found = true;
                            } else {
                                sb.append(line.trim()).append("\n");
                            }
                        }
                        if (!found) {
                            sb.append(finalAcctKey).append("|")
                                    .append(finalAcctName).append("|")
                                    .append(finalAmount).append("|")
                                    .append(finalTime).append("\n");
                        }
                        saveBalanceFile(context, sb.toString().trim());
                    }
                    @Override public void onFailure(String error) {
                        // balance.txt 없으면 새로 생성
                        String newLine = finalAcctKey + "|" + finalAcctName + "|" + finalAmount + "|" + finalTime;
                        saveBalanceFile(context, newLine);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "updateBalanceFile 오류: " + e.getMessage());
            }
        }).start();
    }

    private void saveBalanceFile(Context context, String content) {
        new Thread(() -> {
            try {
                DriveUploadHelper up = new DriveUploadHelper(context);
                up.uploadFileSync(content, BALANCE_FILE);
                Log.d(TAG, "balance.txt 업데이트 완료");
            } catch (Exception e) {
                Log.e(TAG, "balance.txt 저장 실패: " + e.getMessage());
            }
        }).start();
    }

    private void saveToDrive(Context context, String content, String converted, String fileName) {
        // 타임스탬프 포함한 전체 블록 (PinActivity에서 잔액 파싱에 필요)
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
                    // balance.txt 업데이트 (최신 잔액만 빠르게 로드하기 위한 캐시)
                    updateBalanceFile(context, converted);
                    android.content.Intent broadcastIntent = new android.content.Intent("com.sms2drive.SMS_UPDATED");
                    broadcastIntent.putExtra("new_block", newBlock);
                    androidx.localbroadcastmanager.content.LocalBroadcastManager
                            .getInstance(context).sendBroadcast(broadcastIntent);
                    Log.d(TAG, "SMS_UPDATED 브로드캐스트 전송 완료 / new_block 길이=" + newBlock.length());
                    sendBalanceNotification(context, converted);
                    sendFcmToAllUsers(context, newBlock); // 타임스탬프 포함하여 FCM 전송
                    return; // 성공 시 종료
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
}
