package com.sms2drive;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;

public class BalanceWidget extends AppWidgetProvider {

    private static final String TAG = "BalanceWidget";
    private static final String PREF_NAME = "sms2drive_prefs";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId);
        }
    }

    static void updateWidget(Context context, AppWidgetManager appWidgetManager, int widgetId) {
        SharedPreferences prefs;
        try {
            prefs = context.createPackageContext(
                            context.getPackageName(), Context.CONTEXT_IGNORE_SECURITY)
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (Exception e) {
            prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }

        // ── 버전 불일치 여부 확인 ──────────────────────────────
        String driveVersion = prefs.getString("drive_version", "");
        String myVersion = getMyVersion(context);
        boolean needUpdate = !driveVersion.isEmpty() && !driveVersion.equals(myVersion);

        Log.d(TAG, "updateWidget myVer=" + myVersion + " driveVer=" + driveVersion + " needUpdate=" + needUpdate);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_balance);

        if (needUpdate) {
            // ── 업데이트 필요: 잔액 숨기고 경고 표시 ──────────
            views.setViewVisibility(R.id.widget_bal_0, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_bal_1, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_bal_2, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_bal_3, android.view.View.GONE);

            // 경고 텍스트
            views.setViewVisibility(R.id.widget_update_msg, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widget_update_msg,
                    "⚠  앱 업데이트가 필요합니다\n현재 v" + myVersion + "  →  최신 v" + driveVersion);

            // 업데이트 버튼 표시
            views.setViewVisibility(R.id.widget_update_btn, android.view.View.VISIBLE);
            views.setTextViewText(R.id.widget_update_btn, "Play Store 업데이트");

            // 업데이트 버튼 클릭 → Play Store
            PendingIntent openStore;
            try {
                openStore = PendingIntent.getActivity(
                        context, 1,
                        new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=" + context.getPackageName())),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } catch (Exception e) {
                openStore = PendingIntent.getActivity(
                        context, 1,
                        new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=" + context.getPackageName())),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            views.setOnClickPendingIntent(R.id.widget_update_btn, openStore);

            // 위젯 루트 클릭 → Play Store (전체 클릭 시에도 동일)
            views.setOnClickPendingIntent(R.id.widget_root, openStore);

        } else {
            // ── 정상: 잔액 표시 ───────────────────────────────
            String bal0 = prefs.getString("bal_5510-13", "-");
            String bal1 = prefs.getString("bal_5510-83", "-");
            String bal2 = prefs.getString("bal_5510-53", "-");
            String bal3 = prefs.getString("bal_5510-23", "-");

            Log.d(TAG, "updateWidget bal0=" + bal0 + " bal1=" + bal1 + " bal2=" + bal2 + " bal3=" + bal3);

            views.setViewVisibility(R.id.widget_bal_0, android.view.View.VISIBLE);
            views.setViewVisibility(R.id.widget_bal_1, android.view.View.VISIBLE);
            views.setViewVisibility(R.id.widget_bal_2, android.view.View.VISIBLE);
            views.setViewVisibility(R.id.widget_bal_3, android.view.View.VISIBLE);

            views.setTextViewText(R.id.widget_bal_0, bal0);
            views.setTextViewText(R.id.widget_bal_1, bal1);
            views.setTextViewText(R.id.widget_bal_2, bal2);
            views.setTextViewText(R.id.widget_bal_3, bal3);

            // 경고 뷰 숨김
            views.setViewVisibility(R.id.widget_update_msg, android.view.View.GONE);
            views.setViewVisibility(R.id.widget_update_btn, android.view.View.GONE);

            // 위젯 클릭 → 앱 열기
            PendingIntent openApp = PendingIntent.getActivity(
                    context, 0,
                    new Intent(context, PinActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_root, openApp);
        }

        appWidgetManager.updateAppWidget(widgetId, views);
    }

    /** 현재 앱 버전 이름 반환 */
    private static String getMyVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
