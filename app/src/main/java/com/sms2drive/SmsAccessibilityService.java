package com.sms2drive;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import java.util.List;

public class SmsAccessibilityService extends AccessibilityService {

    private static final String TAG = "SmsAccessibility";
    private String lastProcessedText = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // 삼성 메시지앱 알림만 처리
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!pkg.equals("com.samsung.android.messaging")) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;

        // 텍스트 추출
        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null) sb.append(t).append("\n");
            }
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return;

        // 계좌 키워드 포함 여부 확인
        boolean matched = false;
        for (String kw : SmsReceiver.FILTER_KEYWORDS) {
            if (text.contains(kw)) { matched = true; break; }
        }
        if (!matched) return;

        // 중복 처리 방지
        if (text.equals(lastProcessedText)) return;
        lastProcessedText = text;

        Log.d(TAG, "농협 문자 감지: " + text);

        // SmsReceiver 직접 호출
        SmsReceiver receiver = new SmsReceiver();
        receiver.processMessage(this, text);
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.packageNames = null;
        setServiceInfo(info);
        Log.d(TAG, "접근성 서비스 연결됨");
    }
}
