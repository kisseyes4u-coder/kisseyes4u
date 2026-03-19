package com.sms2drive;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import android.app.DownloadManager;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.widget.ProgressBar;
import java.io.File;

public class PinActivity extends AppCompatActivity {

    private TextView[] menuBalTv = null;  // 일반사용자 메뉴 잔액 TextView
    // ── 업데이트 다운로드 관련 ──
    private long        downloadId = -1;
    private String      driveApkFilename = "";  // version.txt 둘째 줄 (예: v1.0.6_release.apk)
    private Button      btnInstall = null;
    private TextView    tvDownloadStatus = null;
    private ProgressBar downloadProgressBar = null;
    private BroadcastReceiver downloadReceiver = null;
    private TextView   tvRecentNotice = null; // 최근 거래 내역 안내 텍스트
    private int        recentChangedCount = 0; // 앱 실행 후 변동된 계좌 수
    private String[] lastMenuBalValues = {"", "", "", ""};  // 이전 잔액 (변경 감지용)
    // ── 최근 거래 ticker ──────────────────────────────────
    private android.widget.FrameLayout tickerFrame = null;
    private android.os.Handler tickerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable tickerRunnable = null;
    private java.util.List<String> tickerItems = new java.util.ArrayList<>();
    // ── 인라인 날씨 뷰 (히어로 안) ────────────────────────
    private LinearLayout savedInlineWeatherView = null;
    private LinearLayout savedForecastBackPanel = null; // 재사용용
    private android.content.BroadcastReceiver smsUpdateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context ctx, android.content.Intent i) {
            if ("com.sms2drive.SMS_UPDATED".equals(i.getAction())) {
                String newBlock = i.getStringExtra("new_block");
                android.util.Log.d("SMS_RECV", "브로드캐스트 수신 / isOwner=" + isOwner
                        + " / isOnBalanceScreen=" + isOnBalanceScreen
                        + " / isOnMenuScreen=" + isOnMenuScreen
                        + " / new_block 길이=" + (newBlock != null ? newBlock.length() : 0));

                if (newBlock != null && !newBlock.isEmpty()) {
                    // ★ new_block이 있으면 Drive 읽기 없이 캐시에 즉시 추가 → 화면 즉시 갱신
                    final String fNewBlock = newBlock;
                    runOnUiThread(() -> {
                        // cachedBlocks에 새 블록 추가
                        if (cachedBlocks == null) cachedBlocks = new java.util.ArrayList<>();
                        cachedBlocks.add(fNewBlock);
                        lastKnownBlockCount = cachedBlocks.size();
                        android.util.Log.d("SMS_RECV", "new_block 즉시 추가 → 총 " + cachedBlocks.size() + "개");

                        // 잔액 갱신 (UI + 위젯 + SharedPreferences)
                        if (tvBalValues != null) updateBalanceValues(cachedBlocks);
                        else updateWidgetFromBlocks(cachedBlocks);

                        // 통장 잔액 현황 화면 즉시 갱신
                        if (isOnBalanceScreen && msgContainer != null) {
                            displayedCount = Math.min(Math.max(displayedCount, PAGE_SIZE), cachedBlocks.size());
                            renderMessages(cachedBlocks, currentTabFilter);
                        }
                        // 메뉴 잔액 카드 갱신
                        if (menuBalTv != null && isOnMenuScreen) updateMenuBalCards(cachedBlocks);

                        // cachedBalValues 무효화 (balance.txt 재로드 유도)
                        cachedBalValues = null;
                    });
                } else {
                    // new_block 없으면 기존 방식 (Drive 전체 읽기)
                    runOnUiThread(() -> forceReloadMessages());
                }
            }
        }
    };

    private android.content.BroadcastReceiver smsDeleteReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context ctx, android.content.Intent i) {
            if ("com.sms2drive.SMS_DELETED".equals(i.getAction())) {
                android.util.Log.d("SMS_RECV", "삭제 브로드캐스트 수신 → 강제 재로드");
                // 캐시 무효화 후 Drive에서 최신 데이터 로드
                cachedBlocks    = null;
                cachedBalValues = null;
                runOnUiThread(() -> forceReloadAfterDelete());
            }
        }
    };

    private static final String OWNER_EMAIL  = "kisseyes4u@gmail.com";
    private static final String USERS_FILE     = "users.txt";
    private static final String FCM_TOKENS_FILE = "fcm_tokens.txt";
    private static final String VERSION_FILE = "version.txt";
    private static final String BALANCE_FILE = "balance.txt";
    private static final int    PAGE_SIZE    = 20;
    private static final int    RC_SIGN_IN   = 9001;
    private static final String PREF_NAME    = "sms2drive_prefs";

    private GoogleSignInClient googleSignInClient;
    private boolean isOwner          = false;
    // ── 화면 빌더 (관리자/일반사용자 분리 편집용) ────────────────
    private OwnerMenuBuilder ownerMenuBuilder;
    private UserMenuBuilder  userMenuBuilder;
    private String  currentUserEmail = "";
    private boolean isOnMenuScreen   = false;

    // ── 잔액/메시지 화면 상태 ──────────────────────────────
    private boolean isShowingFiltered   = false;
    private boolean isOnBalanceScreen   = false;
    private boolean isOnSubScreen       = false;  // 팩스/식단/회원명부 화면
    private boolean weatherSlideShown   = false;  // 앱 실행 후 날씨 슬라이드 1회 완료 여부
    private boolean weatherLoadedThisSession = false; // 이번 실행에서 API 로딩 완료 여부
    private String lastValidTmfc = null; // 마지막으로 성공한 tmfc (탐색 출발점)
    private boolean isWeatherLoading = false; // 날씨 로딩 중 여부
    private android.widget.FrameLayout savedHeaderWeatherFrame = null;  // 날씨카드 프레임 재사용
    private boolean isSelectMode        = false;
    private boolean isDeleting           = false;  // 삭제 중 forceReload 차단
    private String  currentTabFilter    = null;   // null=전체

    // ── 앱 프로세스 전체 공유 캐시 (화면 이동해도 유지) ────
    private static List<String> cachedBlocks   = null;  // 전체 메시지 블록
    // 메모 캐시: 타임스탬프 → [item0,item1,item2,item3,item4] (Drive memo.txt 공유)
    private static java.util.Map<String,String[]> memoCache = new java.util.HashMap<>();
    private static boolean memoCacheLoaded = false;
    private static String[]     cachedBalValues = null; // balance.txt 파싱 결과 [4줄]

    private List<Integer> selectedIdx   = new ArrayList<>();
    private List<Integer> pendingSelectIdx = new ArrayList<>();

    // ── 버스 검색 화면 ─────────────────────────────────────
    private LinearLayout busSearchArea = null;
    private LinearLayout busFixedHeader = null;
    // 인메모리 버스 DB (앱 시작 시 1회 로드, 이후 즉시 검색)
    // 인메모리 버스 DB (앱 시작 시 1회 로드, 이후 즉시 검색)
    private java.util.List<String[]> routeDbList = null;
    private java.util.List<String[]> stopDbList  = null; // 미사용 (세션 캐시로 대체)
    // 버스 화면 백스택: ["type", params...] type=timeline/arrival/search
    private final java.util.Deque<String[]> busBackStack = new java.util.ArrayDeque<>();
    private boolean busFavDirty = false; // 즐겨찾기 변경 시 true → 검색화면 복귀 시 갱신
    private ScrollView busTimelineSv = null;  // 타임라인 ScrollView
    private int busTurnRowY = -1;             // 회차 정류소 Y 좌표
    private String busPendingScrollDir = null;  // 방향전환 후 자동 스크롤 ("forward"/"reverse")
    // nodeno(표시번호) → 노선번호 목록 (예: "46820" → "211,212,601,708")
    private java.util.Map<String, String> nodeNoToRoutes = new java.util.HashMap<>();
    // 도착정보 세션 캐시: nodeId → [렌더링용 데이터 스냅샷, 캐시시각]
    private final java.util.Map<String, Object[]> arrivalSessionCache = new java.util.HashMap<>();
    // 배차시간표: 노선번호 → {src, dst, s:[출발시간들], d:[종점출발시간들]}
    private java.util.Map<String, String[]> busTimesMap = new java.util.HashMap<>();
    // busTimesMap value: [src, dst, "0540,0605,...", "0540,0606,..."]
    // 정류장 검색 세션 캐시 (keyword → 결과 리스트, 앱 실행 중 유지)
    @SuppressWarnings("serial")
    private final java.util.Map<String, java.util.List<String[]>> stopSearchCache =
            new java.util.LinkedHashMap<String, java.util.List<String[]>>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, java.util.List<String[]>> e) {
                    return size() > 30;
                }
            };
    private LinearLayout busResultContainer = null;
    private LinearLayout busFavSection2 = null;
    private android.widget.EditText busEtSearch = null;
    private TextView busTabBus = null;  // 버스번호 탭 버튼
    private TextView busTabStop = null; // 정류장 탭 버튼
    private Runnable busUpdateTabStyle = null; // 탭 스타일 업데이트
    private boolean[] busIsBusTab = {true}; // 현재 탭 상태
    private TextView splashLoadingTv = null;
    private android.widget.ProgressBar splashProgressBar = null;
    private TextView splashProgressTv = null;
    private LinearLayout splashProgressArea = null;

    // ── UI 참조 (잔액화면 갱신용) ──────────────────────────
    private LinearLayout msgContainer       = null;
    private ScrollView   msgScrollView      = null;
    private LinearLayout selectActionBar    = null;
    private TextView     tvSelectCount      = null;
    private TextView[]      tvBalValues = null;   // 잔액 카드 TextView 참조
    private LinearLayout[]  balCards    = null;   // 잔액 카드 레이아웃 참조
    private String[][]      balInfo     = null;   // 잔액 카드 정보 참조

    // ── 자동 새로고침 ──────────────────────────────────────
    private android.os.Handler refreshHandler = new android.os.Handler();
    private android.graphics.Bitmap busIconBitmap = null;      // 진한 보라 착색 (타임라인용)
    private android.graphics.Bitmap busIconWhiteBitmap = null; // 흰색 (헤더용)
    private android.graphics.Bitmap busIconPurpleBitmap = null; // 진한 보라 + 흰 배경 (즐겨찾기 카드용)

    /** assets/bus.png - 흰 배경 + 진한 보라 아이콘 (즐겨찾기 카드용) */
    private android.graphics.Bitmap getBusIconPurple() {
        return getBusIconColor(0xFF6C3FA0);
    }
    /** assets/bus.png를 지정된 색상으로 렌더링 */
    private android.graphics.Bitmap getBusIconColor(int argbColor) {
        try {
            android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeStream(
                    getAssets().open("bus.png"));
            if (raw != null) {
                int w = raw.getWidth(), h = raw.getHeight();
                android.graphics.Bitmap result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                int[] pixels = new int[w * h];
                raw.getPixels(pixels, 0, w, 0, 0, w, h);
                for (int i = 0; i < pixels.length; i++) {
                    int r2 = (pixels[i] >> 16) & 0xFF;
                    int g2 = (pixels[i] >> 8)  & 0xFF;
                    int b2 =  pixels[i]         & 0xFF;
                    int brightness = (r2 + g2 + b2) / 3;
                    if (brightness > 128) {
                        pixels[i] = argbColor;
                    } else {
                        pixels[i] = 0xFFFFFFFF;
                    }
                }
                result.setPixels(pixels, 0, w, 0, 0, w, h);
                raw.recycle();
                return result;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** assets/stop.png(정류소 전용) 또는 pngwing_com.png(노선+정류소) 색상 렌더링 */
    /** assets/stop.png - getBusIconColor와 완전 동일, 파일명만 다름 */
    private android.graphics.Bitmap getStopIconColor(int argbColor) {
        try {
            android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeStream(
                    getAssets().open("stop.png"));
            if (raw != null) {
                int w = raw.getWidth(), h = raw.getHeight();
                android.graphics.Bitmap result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                int[] pixels = new int[w * h];
                raw.getPixels(pixels, 0, w, 0, 0, w, h);
                for (int i = 0; i < pixels.length; i++) {
                    int r2 = (pixels[i] >> 16) & 0xFF;
                    int g2 = (pixels[i] >> 8)  & 0xFF;
                    int b2 =  pixels[i]         & 0xFF;
                    int brightness = (r2 + g2 + b2) / 3;
                    if (brightness > 128) { pixels[i] = argbColor; }
                    else                  { pixels[i] = 0xFFFFFFFF; }
                }
                result.setPixels(pixels, 0, w, 0, 0, w, h);
                raw.recycle();
                return result;
            }
        } catch (Exception ignored) {}
        return null;
    }
    /** assets/pngwing_com.png - getBusIconColor와 완전 동일, 파일명만 다름 */
    private android.graphics.Bitmap getStopRouteIconColor(int argbColor) {
        try {
            android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeStream(
                    getAssets().open("pngwing_com.png"));
            if (raw != null) {
                int w = raw.getWidth(), h = raw.getHeight();
                android.graphics.Bitmap result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                int[] pixels = new int[w * h];
                raw.getPixels(pixels, 0, w, 0, 0, w, h);
                for (int i = 0; i < pixels.length; i++) {
                    int r2 = (pixels[i] >> 16) & 0xFF;
                    int g2 = (pixels[i] >> 8)  & 0xFF;
                    int b2 =  pixels[i]         & 0xFF;
                    int brightness = (r2 + g2 + b2) / 3;
                    if (brightness > 128) { pixels[i] = argbColor; }
                    else                  { pixels[i] = 0xFFFFFFFF; }
                }
                result.setPixels(pixels, 0, w, 0, 0, w, h);
                raw.recycle();
                return result;
            }
        } catch (Exception ignored) {}
        return null;
    }
    /** assets/bus.png 로드 - 검정 배경 투명화 + 흰색 픽셀 → 빨간색 (타임라인 버스 위치용) */
    private android.graphics.Bitmap getBusIcon() {
        if (busIconBitmap == null) {
            try {
                android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeStream(
                        getAssets().open("bus.png"));
                if (raw != null) {
                    android.graphics.Bitmap result = raw.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                    int w = result.getWidth(), h = result.getHeight();
                    int[] pixels = new int[w * h];
                    result.getPixels(pixels, 0, w, 0, 0, w, h);
                    for (int i = 0; i < pixels.length; i++) {
                        int r2 = (pixels[i] >> 16) & 0xFF;
                        int g2 = (pixels[i] >> 8)  & 0xFF;
                        int b2 =  pixels[i]         & 0xFF;
                        int brightness = (r2 + g2 + b2) / 3;
                        if (brightness > 128) {
                            pixels[i] = (brightness << 24) | 0x00E74C3C; // 빨간색
                        } else {
                            pixels[i] = 0x00000000; // 투명
                        }
                    }
                    result.setPixels(pixels, 0, w, 0, 0, w, h);
                    busIconBitmap = result;
                    raw.recycle();
                }
            } catch (Exception ignored) {}
        }
        return busIconBitmap;
    }

    /** assets/bus.png 로드 - 흰색 버전 (파란 헤더 배경용) */
    private android.graphics.Bitmap getBusIconWhite() {
        if (busIconWhiteBitmap == null) {
            try {
                android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeStream(
                        getAssets().open("bus.png"));
                if (raw != null) {
                    android.graphics.Bitmap result = raw.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                    int w = result.getWidth(), h = result.getHeight();
                    int[] pixels = new int[w * h];
                    result.getPixels(pixels, 0, w, 0, 0, w, h);
                    for (int i = 0; i < pixels.length; i++) {
                        int r2 = (pixels[i] >> 16) & 0xFF;
                        int g2 = (pixels[i] >> 8)  & 0xFF;
                        int b2 =  pixels[i]         & 0xFF;
                        int brightness = (r2 + g2 + b2) / 3;
                        if (brightness > 128) {
                            pixels[i] = (brightness << 24) | 0x00FFFFFF; // 흰색
                        } else {
                            pixels[i] = 0x00000000; // 투명
                        }
                    }
                    result.setPixels(pixels, 0, w, 0, 0, w, h);
                    busIconWhiteBitmap = result;
                    raw.recycle();
                }
            } catch (Exception ignored) {}
        }
        return busIconWhiteBitmap;
    }

    private android.os.Handler busRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable busRefreshRunnable = null;
    private Runnable refreshRunnable;
    private Runnable blockedCheckRunnable;
    private int lastKnownBlockCount = 0;
    private int displayedCount      = PAGE_SIZE;
    private int statusBarHeight = 0;
    private int navBarHeight = 0;

    // ──────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 가로모드 비활성화 — 세로 모드 고정
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        showSplashScreen();
        requestSmsPermissions();
        // 백그라운드 잔액 모니터링 서비스 시작
        startBalanceMonitorService();

        // SMS 실시간 업데이트 수신 등록 (LocalBroadcastManager 사용)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .registerReceiver(smsUpdateReceiver,
                        new android.content.IntentFilter("com.sms2drive.SMS_UPDATED"));
        // 삭제 신호 수신 등록
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .registerReceiver(smsDeleteReceiver,
                        new android.content.IntentFilter("com.sms2drive.SMS_DELETED"));
        // 상태바 아이콘 검정색 설정 (모든 API 안전 방식)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            getWindow().setStatusBarColor(Color.WHITE);
        }
        // 상태바 높이 측정
        int resId2 = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId2 > 0) statusBarHeight = getResources().getDimensionPixelSize(resId2);
        // 네비게이션 바 높이 측정
        int navResId2 = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (navResId2 > 0) navBarHeight = getResources().getDimensionPixelSize(navResId2);
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken("939865897218-c3f0fhv359eqthrcub4lm414uhi1ro6d.apps.googleusercontent.com")
                .requestEmail()
                .requestScopes(new com.google.android.gms.common.api.Scope(
                        "https://www.googleapis.com/auth/drive"))
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        getOnBackPressedDispatcher().addCallback(this,
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (isSelectMode) {
                            exitSelectMode();
                        } else if (isOnMeatScreen && meatSelectMode) {
                            // 선결제 선택 모드 → 선택 취소만
                            meatSelectMode = false;
                            meatSelectedIdx = new ArrayList<>();
                            if (meatSelectBar != null) meatSelectBar.setVisibility(View.GONE);
                            if (meatBlocks != null) renderMeatMessages(meatBlocks, meatDisplayedCount);
                        } else if (isOnMeatScreen) {
                            if (meatTabFilter != null) {
                                meatTabFilter = null;
                                updateMeatCardColors();
                                meatDisplayedCount = PAGE_SIZE;
                                if (meatBlocks != null)
                                    renderMeatMessages(meatBlocks, meatDisplayedCount);
                            } else {
                                isOnMeatScreen = false;
                                isOnSubScreen  = false;
                                if (isOwner) ownerMenuBuilder.build();
                                else userMenuBuilder.build(false);
                            }
                        } else if (isOnSubScreen) {
                            // 버스 화면이면 백스택 기반 뒤로가기
                            if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.GONE) {
                                busNavigateBack();
                            } else {
                                isOnSubScreen = false;
                                if (isOwner) ownerMenuBuilder.build();
                                else userMenuBuilder.build(false);
                            }
                        } else if (isOnBalanceScreen) {
                            if (currentTabFilter != null) {
                                // 필터 해제 → 전체 보기
                                currentTabFilter = null;
                                if (balCards != null && balInfo != null)
                                    updateBalCardColors(balCards, balInfo, -1);
                                renderLatest(displayedCount);
                            } else {
                                // 전체 보기 → 메뉴로
                                stopAutoRefresh();
                                goBackFromBalance();
                            }
                        } else {
                            finish();
                        }
                    }
                });

        // silentSignIn: 토큰 자동 갱신 + 저장된 계정 자동 복원
        GoogleSignInAccount cached = GoogleSignIn.getLastSignedInAccount(this);
        if (cached != null) {
            // 저장된 계정 있음 → silentSignIn으로 토큰 갱신 시도
            googleSignInClient.silentSignIn()
                    .addOnSuccessListener(account -> handleSignedIn(account))
                    .addOnFailureListener(e -> {
                        // silentSignIn 실패 시 (토큰 완전 만료 등) → 수동 로그인
                        startActivityForResult(googleSignInClient.getSignInIntent(), RC_SIGN_IN);
                    });
        } else {
            // 저장된 계정 없음 → 수동 로그인
            startActivityForResult(googleSignInClient.getSignInIntent(), RC_SIGN_IN);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task =
                    GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                handleSignedIn(task.getResult(ApiException.class));
            } catch (ApiException e) {
                Toast.makeText(this, "로그인 실패", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void handleSignedIn(GoogleSignInAccount account) {
        currentUserEmail = account.getEmail() != null
                ? account.getEmail().toLowerCase() : "";
        isOwner = currentUserEmail.equals(OWNER_EMAIL.toLowerCase());
        // MyFirebaseMessagingService에서 읽을 수 있도록 SharedPreferences에 저장
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putString("user_email", currentUserEmail).apply();
        ownerMenuBuilder = new OwnerMenuBuilder(this);
        userMenuBuilder  = new UserMenuBuilder(this);

        if (isOwner) {
            uploadVersionToDrive();
            uploadFcmTokenIfNeeded();
            if (busDbNeedsUpdate()) {
                if (splashLoadingTv != null) splashLoadingTv.setText("버스 노선 데이터 다운로드 중...");
                showSplashProgress();
                downloadBusRouteDb(() -> {
                    hideSplashProgress();
                    loadBusDbToMemory();
                    loadStopDbFromDriveIfNeeded(() -> {
                        ownerMenuBuilder.build();
                        checkAccessibilityService();
                    });
                }, pct -> updateSplashProgress(pct));
            } else {
                loadBusDbToMemory();
                loadStopDbFromDriveIfNeeded(() -> {
                    ownerMenuBuilder.build();
                    checkAccessibilityService();
                });
            }
        } else {
            uploadFcmTokenIfNeeded();
            updateLastAccessTime();
            checkBlockedThenStart();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  버전 관리
    // ═══════════════════════════════════════════════════════
    private void uploadVersionToDrive() {
        String myVersion = getMyVersion();
        new Thread(() -> {
            try {
                // Drive의 현재 버전 확인
                DriveReadHelper reader = new DriveReadHelper(PinActivity.this);
                final String[] driveVer = {null};
                final Object lock = new Object();
                reader.readFile(VERSION_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String content) {
                        synchronized (lock) { driveVer[0] = content.trim().split("\r?\n")[0].trim(); lock.notifyAll(); }
                    }
                    @Override public void onFailure(String error) {
                        synchronized (lock) { driveVer[0] = ""; lock.notifyAll(); }
                    }
                });
                synchronized (lock) { if (driveVer[0] == null) lock.wait(10000); }

                DriveUploadHelper uploader = new DriveUploadHelper(PinActivity.this);
                uploader.uploadFileSync(myVersion, VERSION_FILE);

                // Drive 버전과 다를 때만 FCM 전송 (새 버전 배포 시에만)
                if (!myVersion.equals(driveVer[0])) {
                    android.util.Log.d("FCM_UPDATE", "새 버전 감지: " + driveVer[0] + " → " + myVersion);
                    sendFcmUpdateNotification(myVersion);
                }
            } catch (Exception e) { /* ignore */ }
        }).start();
    }

    private void sendFcmUpdateNotification(String newVersion) {
        try {
            // Apps Script 웹앱 URL 호출 → Apps Script가 FCM V1 API로 전송
            String url = "https://script.google.com/macros/s/AKfycbwLYFmf9IFkhKV0PA5Q_4nE9aYH8ZiVzMl0S9_HuAqQVk04PYTphNE-MatNkj-bGI9ejw/exec"
                    + "?version=" + java.net.URLEncoder.encode(newVersion, "UTF-8");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close(); conn.disconnect();
            android.util.Log.d("FCM_UPDATE", "응답=" + code + " " + sb);
            try {
                org.json.JSONObject res = new org.json.JSONObject(sb.toString());
                int success = res.optInt("success", 0);
                int total   = res.optInt("total", 0);
                runOnUiThread(() -> android.widget.Toast.makeText(PinActivity.this,
                        "업데이트 알림 " + total + "개 기기 전송 완료 (" + success + "개 성공)",
                        android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.e("FCM_UPDATE", "알림 전송 오류: " + e.getMessage());
        }
    }

    private boolean isBatteryOptimizationExempt() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabledServices = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null) return false;
            android.util.Log.d("Accessibility", "enabledServices: " + enabledServices);
            // 패키지명만으로 체크 (클래스명 불일치 대비)
            return enabledServices.contains(getPackageName());
        } catch (Exception e) {
            android.util.Log.e("Accessibility", "오류: " + e.getMessage());
            return false;
        }
    }

    private void checkAccessibilityService() {
        try {
            String enabledServices = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String pkg = getPackageName() + "/" + getPackageName() + ".SmsAccessibilityService";
            boolean isEnabled = enabledServices != null && enabledServices.contains(pkg);
            if (!isEnabled) {
                new android.app.AlertDialog.Builder(this,
                        android.R.style.Theme_Material_Light_Dialog_Alert)
                        .setTitle("접근성 서비스 필요")
                        .setMessage("농협 문자 자동 수신을 위해\n접근성 서비스를 켜주세요.\n\n설정 > 접근성 > 설치된 서비스\n> 경로당 잔액알림 > 켜기")
                        .setPositiveButton("설정으로 이동", (d, w) -> {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        })
                        .setNegativeButton("나중에", null)
                        .show();
            }
        } catch (Exception e) {
            android.util.Log.e("PinActivity", "접근성 체크 오류: " + e.getMessage());
        }
    }

    private void startBalanceMonitorService() {
        try {
            Intent serviceIntent = new Intent(this, BalanceMonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            android.util.Log.e("PinActivity", "서비스 시작 실패: " + e.getMessage());
        }
    }

    private void requestSmsPermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();

        // SMS 권한
        String[] smsPerms = {
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS
        };
        for (String p : smsPerms) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        // 알림 권한 (Android 13 이상)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), 1001);
        }
    }

    // ── 날씨 데이터 로드 (기상청 API) ────────────────────
    private static final String WEATHER_API_KEY = "JA-sLWgDQ7iPrC1oA7O4Wg";
    // 대전 유성구 격자 좌표 (기상청 격자 변환)
    private static final int WEATHER_NX = 67;
    private static final int WEATHER_NY = 100;

    private void loadWeatherData(LinearLayout card, TextView tvLoading) { loadWeatherData(card, tvLoading, null); }
    private void loadWeatherData(LinearLayout card, TextView tvLoading, Runnable onReady) {
        // ── 캐시: 이번 앱 실행에서 이미 API 로딩 완료한 경우에만 사용 ──
        // 앱 재시작/재설치 시에는 weatherLoadedThisSession=false → 항상 API 호출
        android.content.SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String cTemp = p.getString("wx_temp", null);
        if (weatherLoadedThisSession && cTemp != null) {
            // 같은 앱 실행 중 화면 복귀: 캐시에서 바로 표시 (현재 시간으로 갱신)
            java.util.Calendar cNow = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("Asia/Seoul"));
            String nowTime = String.format("%04d/%02d/%02d %02d:%02d 기준",
                    cNow.get(java.util.Calendar.YEAR),
                    cNow.get(java.util.Calendar.MONTH) + 1,
                    cNow.get(java.util.Calendar.DAY_OF_MONTH),
                    cNow.get(java.util.Calendar.HOUR_OF_DAY),
                    cNow.get(java.util.Calendar.MINUTE));
            renderWeatherCard(card, tvLoading,
                    cTemp,
                    p.getString("wx_hum",  "-"),
                    p.getString("wx_wsd",  "-"),
                    p.getString("wx_wdir", "-"),
                    p.getString("wx_rain", "0"),
                    p.getString("wx_pty",  "없음"),
                    nowTime,
                    p.getString("wx_sunrise", "-"),
                    p.getString("wx_sunset",  "-"),
                    p.getString("wx_pm25val", "-"),
                    p.getString("wx_pm10val", "-"),
                    p.getString("wx_o3val",   "-"),
                    p.getString("wx_pm25g",   "0"),
                    p.getString("wx_pm10g",   "0"),
                    p.getString("wx_o3g",     "0"));
            if (onReady != null) onReady.run();
            return;
        }
        // API 호출 (앱 재시작/재설치 시 항상 새로 불러옴)
        new Thread(() -> {
            try {
                java.util.Calendar cal = java.util.Calendar.getInstance(
                        java.util.TimeZone.getTimeZone("Asia/Seoul"));

                // ── 초단기실황 API (nph-dfs_odam_grd) ──────────────────
                // tmfc: 현재 10분 내림, 데이터 없으면(-99) 10분씩 최대 3회 후퇴
                java.util.Calendar calNow = java.util.Calendar.getInstance(
                        java.util.TimeZone.getTimeZone("Asia/Seoul"));
                int curHour = calNow.get(java.util.Calendar.HOUR_OF_DAY);
                int curMin  = calNow.get(java.util.Calendar.MINUTE);
                int odamMin  = (curMin / 10) * 10;
                int odamHour = curHour;

                int gridIndex = (WEATHER_NY - 1) * 149 + (WEATHER_NX - 1);
                java.util.Map<String, String> wx = new java.util.HashMap<>();

                // 유효한 tmfc 탐색 (T1H로 최대 7회 10분 후퇴)
                // lastValidTmfc가 있으면 그 시각부터 탐색 시작
                if (lastValidTmfc != null && lastValidTmfc.length() == 12) {
                    try {
                        int lh = Integer.parseInt(lastValidTmfc.substring(8, 10));
                        int lm = Integer.parseInt(lastValidTmfc.substring(10, 12));
                        int diffMin = (odamHour * 60 + odamMin) - (lh * 60 + lm);
                        if (diffMin >= 0 && diffMin <= 60) {
                            odamHour = lh;
                            odamMin = lm;
                        }
                    } catch (Exception ignored) {}
                }
                String odamTmfc = null;
                for (int retry = 0; retry < 7; retry++) {
                    int tryMin  = odamMin - retry * 10;
                    int tryHour = odamHour;
                    while (tryMin < 0) { tryMin += 60; tryHour--; }
                    java.util.Calendar calTry = (java.util.Calendar) calNow.clone();
                    if (tryHour < 0) {
                        tryHour = 23;
                        calTry.add(java.util.Calendar.DAY_OF_MONTH, -1);
                    }
                    String cand = String.format("%04d%02d%02d%02d%02d",
                            calTry.get(java.util.Calendar.YEAR),
                            calTry.get(java.util.Calendar.MONTH) + 1,
                            calTry.get(java.util.Calendar.DAY_OF_MONTH),
                            tryHour, tryMin);
                    try {
                        java.net.HttpURLConnection tc = (java.net.HttpURLConnection)
                                new java.net.URL("https://apihub.kma.go.kr/api/typ01/cgi-bin/url/nph-dfs_odam_grd"
                                        + "?tmfc=" + cand + "&vars=T1H&authKey=" + WEATHER_API_KEY).openConnection();
                        tc.setConnectTimeout(8000); tc.setReadTimeout(8000);
                        if (tc.getResponseCode() == 200) {
                            java.io.BufferedReader tbr = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(tc.getInputStream(), "UTF-8"));
                            StringBuilder tsb = new StringBuilder();
                            String tl;
                            while ((tl = tbr.readLine()) != null) tsb.append(tl).append(" ");
                            tbr.close();
                            String[] toks = tsb.toString().trim().split("[,\\s]+");
                            java.util.List<String> tnums = new java.util.ArrayList<>();
                            for (String t : toks) { try { Double.parseDouble(t.trim()); tnums.add(t.trim()); } catch (Exception ig) {} }
                            if (gridIndex < tnums.size() && !tnums.get(gridIndex).startsWith("-99")) {
                                odamTmfc = cand;
                                wx.put("T1H", tnums.get(gridIndex));
                                lastValidTmfc = cand;
                                android.util.Log.d("WEATHER", "유효 tmfc=" + odamTmfc + " T1H=" + tnums.get(gridIndex));
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // 유효 tmfc 확인 후 나머지 변수 병렬 요청
                if (odamTmfc != null) {
                    final String finalTmfc = odamTmfc;
                    String[] restVars = {"REH", "WSD", "VEC", "RN1", "PTY", "SKY"};
                    java.util.List<Thread> threads = new java.util.ArrayList<>();
                    for (String var : restVars) {
                        Thread t = new Thread(() -> {
                            try {
                                java.net.HttpURLConnection c2 = (java.net.HttpURLConnection)
                                        new java.net.URL("https://apihub.kma.go.kr/api/typ01/cgi-bin/url/nph-dfs_odam_grd"
                                                + "?tmfc=" + finalTmfc + "&vars=" + var + "&authKey=" + WEATHER_API_KEY).openConnection();
                                c2.setConnectTimeout(8000); c2.setReadTimeout(8000);
                                if (c2.getResponseCode() == 200) {
                                    java.io.BufferedReader br2 = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(c2.getInputStream(), "UTF-8"));
                                    StringBuilder sb2 = new StringBuilder();
                                    String ln2;
                                    while ((ln2 = br2.readLine()) != null) sb2.append(ln2).append(" ");
                                    br2.close();
                                    String[] tokens = sb2.toString().trim().split("[,\\s]+");
                                    java.util.List<String> nums2 = new java.util.ArrayList<>();
                                    for (String t2 : tokens) { try { Double.parseDouble(t2.trim()); nums2.add(t2.trim()); } catch (Exception ig) {} }
                                    if (gridIndex < nums2.size()) {
                                        String val = nums2.get(gridIndex);
                                        if (!val.startsWith("-99")) synchronized (wx) { wx.put(var, val); }
                                    }
                                }
                            } catch (Exception ignored) {}
                        });
                        threads.add(t);
                        t.start();
                    }
                    // 모든 병렬 요청 완료 대기 (최대 6초)
                    for (Thread t : threads) {
                        try { t.join(8000); } catch (Exception ignored) {}
                    }
                }
                android.util.Log.d("WEATHER", "wx=" + wx.toString());

                if (!wx.containsKey("T1H")) { // T1H 없으면 완전 실패
                    // 캐시에 이전 데이터가 있으면 폴백
                    android.content.SharedPreferences cp =
                            getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                    String cachedTemp = cp.getString("wx_temp", null);
                    if (cachedTemp != null) {
                        android.util.Log.d("WEATHER", "wx 비어있음 → 캐시 폴백");
                        java.util.Calendar cNow2 = java.util.Calendar.getInstance(
                                java.util.TimeZone.getTimeZone("Asia/Seoul"));
                        String nowTime2 = String.format("%04d/%02d/%02d %02d:%02d 기준(캐시)",
                                cNow2.get(java.util.Calendar.YEAR),
                                cNow2.get(java.util.Calendar.MONTH) + 1,
                                cNow2.get(java.util.Calendar.DAY_OF_MONTH),
                                cNow2.get(java.util.Calendar.HOUR_OF_DAY),
                                cNow2.get(java.util.Calendar.MINUTE));
                        runOnUiThread(() -> {
                            renderWeatherCard(card, tvLoading,
                                    cachedTemp,
                                    cp.getString("wx_hum",  "-"),
                                    cp.getString("wx_wsd",  "-"),
                                    cp.getString("wx_wdir", "-"),
                                    cp.getString("wx_rain", "0"),
                                    cp.getString("wx_pty",  "없음"),
                                    nowTime2,
                                    cp.getString("wx_sunrise", "-"),
                                    cp.getString("wx_sunset",  "-"),
                                    cp.getString("wx_pm25val", "-"),
                                    cp.getString("wx_pm10val", "-"),
                                    cp.getString("wx_o3val",   "-"),
                                    cp.getString("wx_pm25g",   "0"),
                                    cp.getString("wx_pm10g",   "0"),
                                    cp.getString("wx_o3g",     "0"));
                            if (onReady != null) onReady.run();
                        });
                    } else {
                        runOnUiThread(() -> tvLoading.setText("날씨 데이터를 가져올 수 없습니다."));
                    }
                    return;
                }

                // wx 맵에서 값 추출 (vars 키 그대로 사용)
                String temp = getWxVal(wx, "T1H", "TMP", "TA");
                String hum  = getWxVal(wx, "REH", "HM", "RH");
                String wsd  = getWxVal(wx, "WSD", "WS");
                String vec  = getWxVal(wx, "VEC", "WD");
                String rain = getWxVal(wx, "RN1", "RN");
                String pty  = getWxVal(wx, "PTY");
                String sky  = getWxVal(wx, "SKY");

                // 풍향 숫자 → 한글
                String windDir = "-";
                try {
                    double deg = Double.parseDouble(vec);
                    String[] dirs = {"북","북북동","북동","동북동","동","동남동","남동","남남동",
                            "남","남남서","남서","서남서","서","서북서","북서","북북서"};
                    windDir = dirs[(int)((deg + 11.25) / 22.5) % 16];
                } catch (Exception ignored) {}

                // 강수형태
                // PTY를 정수로 변환 후 한글 매핑
                int ptyInt = 0;
                try { ptyInt = (int)Double.parseDouble(pty.trim()); } catch(Exception ig){}
                String ptyStr;
                switch (ptyInt) {
                    case 1: ptyStr = "비"; break;
                    case 2: ptyStr = "비/눈"; break;
                    case 3: ptyStr = "눈"; break;
                    case 5: ptyStr = "빗방울"; break;
                    case 6: ptyStr = "빗방울/눈날림"; break;
                    case 7: ptyStr = "눈날림"; break;
                    default: ptyStr = "없음"; break;
                }

                // 소수점 1자리로 포맷
                try { temp = String.valueOf(Math.round(Double.parseDouble(temp) * 10) / 10.0); } catch (Exception ignored) {}
                try { hum  = String.valueOf((int)Double.parseDouble(hum)); } catch (Exception ignored) {}
                try { wsd  = String.format("%.1f", Double.parseDouble(wsd)); } catch (Exception ignored) {}
                if (rain.equals("-") || rain.equals("0.0") || rain.equals("0")) rain = "0";

                final String fTemp = temp;
                final String fHum  = hum;
                final String fWsd  = wsd;
                final String fWDir = windDir;
                final String fRain = rain;
                final String fPty  = ptyStr;
                // SKY 정수 정규화 (비정상값 "-99" 등 제외)
                String _skyInt = "1";
                try {
                    double _sd = Double.parseDouble(sky.trim());
                    if (_sd > 0) _skyInt = String.valueOf((int)_sd);
                } catch(Exception ig){}
                final String fSky = _skyInt;
                // PTY 숫자값 정수로 저장
                String _ptyNum = "0";
                try { _ptyNum = String.valueOf((int)Double.parseDouble(pty.trim())); } catch(Exception ig2){}
                final String fPtyNum = _ptyNum;
                // 기준시각: 실제 사용된 tmfc에서 추출
                int dispHour = odamHour, dispMin = odamMin;
                if (odamTmfc != null && odamTmfc.length() == 12) {
                    dispHour = Integer.parseInt(odamTmfc.substring(8, 10));
                    dispMin  = Integer.parseInt(odamTmfc.substring(10, 12));
                }
                final String fTime = String.format("%04d/%02d/%02d %02d:%02d 기준",
                        calNow.get(java.util.Calendar.YEAR),
                        calNow.get(java.util.Calendar.MONTH) + 1,
                        calNow.get(java.util.Calendar.DAY_OF_MONTH),
                        dispHour, dispMin);

                // ── 에어코리아 미세먼지 API ─────────────────────
                String pm25Val = "-", pm10Val = "-", o3Val = "-";
                String pm25Grade = "0", pm10Grade = "0", o3Grade = "0";
                try {
                    // numOfRows=5: 통신장애 시 이전 유효 데이터 사용
                    String airUrl = "https://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getMsrstnAcctoRltmMesureDnsty"
                            + "?stationName=%EC%A7%80%EC%A1%B1%EB%8F%99"
                            + "&dataTerm=DAILY&pageNo=1&numOfRows=5&returnType=json&ver=1.0"
                            + "&serviceKey=4f9182aa6a8d775a6013c074fc5620578371c0031a6f97e9c0434e3973bcf1d5";
                    java.net.HttpURLConnection ac =
                            (java.net.HttpURLConnection) new java.net.URL(airUrl).openConnection();
                    ac.setConnectTimeout(8000);
                    ac.setReadTimeout(8000);
                    if (ac.getResponseCode() == 200) {
                        java.io.BufferedReader abr = new java.io.BufferedReader(
                                new java.io.InputStreamReader(ac.getInputStream(), "UTF-8"));
                        StringBuilder asb = new StringBuilder();
                        String al;
                        while ((al = abr.readLine()) != null) asb.append(al);
                        abr.close();
                        org.json.JSONObject aJson = new org.json.JSONObject(asb.toString());
                        org.json.JSONArray aItems = aJson.getJSONObject("response")
                                .getJSONObject("body").getJSONArray("items");
                        // 통신장애(-) 건너뛰고 유효한 첫 번째 데이터 사용
                        for (int ai2 = 0; ai2 < aItems.length(); ai2++) {
                            org.json.JSONObject ai = aItems.getJSONObject(ai2);
                            String v25 = ai.optString("pm25Value", "-");
                            String v10 = ai.optString("pm10Value", "-");
                            String vo3 = ai.optString("o3Value", "-");
                            if (!v25.equals("-") || !v10.equals("-") || !vo3.equals("-")) {
                                pm25Val   = v25;
                                pm10Val   = v10;
                                o3Val     = vo3;
                                pm25Grade = ai.optString("pm25Grade", "0");
                                pm10Grade = ai.optString("pm10Grade", "0");
                                o3Grade   = ai.optString("o3Grade", "0");
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // ── 일출/일몰 계산 (대전: 위도36.35, 경도127.38) ──
                String[] sunTimes = calcSunriseSunset(
                        cal.get(java.util.Calendar.YEAR),
                        cal.get(java.util.Calendar.MONTH) + 1,
                        cal.get(java.util.Calendar.DAY_OF_MONTH),
                        36.35, 127.38);

                final String fPm25Val = pm25Val, fPm10Val = pm10Val, fO3Val = o3Val;
                final String fPm25G = pm25Grade, fPm10G = pm10Grade, fO3G = o3Grade;
                final String fSunrise = sunTimes[0], fSunset = sunTimes[1];

                // ── 날씨 데이터 캐시 저장 + 이번 세션 로딩 완료 표시 ──
                weatherLoadedThisSession = true;
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .putString("wx_temp", fTemp)
                        .putString("wx_hum",  fHum)
                        .putString("wx_wsd",  fWsd)
                        .putString("wx_wdir", fWDir)
                        .putString("wx_rain", fRain)
                        .putString("wx_sky",  fSky)
                        .putString("wx_pty_num", fPtyNum)
                        .putString("wx_pty",  fPty)
                        .putString("wx_time", fTime)
                        .putString("wx_sunrise", fSunrise)
                        .putString("wx_sunset",  fSunset)
                        .putString("wx_pm25val",  fPm25Val)
                        .putString("wx_pm10val",  fPm10Val)
                        .putString("wx_o3val",    fO3Val)
                        .putString("wx_pm25g",    fPm25G)
                        .putString("wx_pm10g",    fPm10G)
                        .putString("wx_o3g",      fO3G)
                        .apply();

                android.util.Log.d("ICON_DEBUG","PTY="+fPtyNum+" SKY="+fSky+" ptyStr="+fPty);
                runOnUiThread(() -> {
                    renderWeatherCard(card, tvLoading,
                            fTemp, fHum, fWsd, fWDir, fRain, fPty, fTime,
                            fSunrise, fSunset,
                            fPm25Val, fPm10Val, fO3Val,
                            fPm25G, fPm10G, fO3G);
                    if (onReady != null) onReady.run();
                    // 로딩이 2초 이상 걸린 경우: tag에 슬라이드 Runnable이 있으면 실행
                    Object tag = card.getTag();
                    if (tag instanceof Runnable) {
                        card.setTag(null);
                        ((Runnable) tag).run();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> tvLoading.setText("날씨 정보를 불러올 수 없습니다.\n" + e.getMessage()));
                // 실패 시에도 onReady 호출 → 새로고침 버튼 복원
                if (onReady != null) runOnUiThread(onReady);
            }
        }).start();
    }

    /** 일출/일몰 계산 (단순 천문 공식) */
    private String[] calcSunriseSunset(int year, int month, int day, double lat, double lon) {
        try {
            // 율리우스 날짜 계산
            double A = Math.floor((14 - month) / 12.0);
            double Y = year + 4800 - A;
            double M = month + 12 * A - 3;
            double JD = day + Math.floor((153 * M + 2) / 5.0) + 365 * Y
                    + Math.floor(Y / 4.0) - Math.floor(Y / 100.0) + Math.floor(Y / 400.0) - 32045;
            double n = JD - 2451545.0 + 0.0008;
            double Jstar = n - lon / 360.0;
            double M2 = (357.5291 + 0.98560028 * Jstar) % 360;
            double Mr = Math.toRadians(M2);
            double C = 1.9148 * Math.sin(Mr) + 0.0200 * Math.sin(2 * Mr) + 0.0003 * Math.sin(3 * Mr);
            double lambda = (M2 + C + 180 + 102.9372) % 360;
            double Jtransit = 2451545.0 + Jstar + 0.0053 * Math.sin(Mr) - 0.0069 * Math.sin(2 * Math.toRadians(lambda));
            double sinDec = Math.sin(Math.toRadians(lambda)) * Math.sin(Math.toRadians(23.4397));
            double cosDec = Math.cos(Math.asin(sinDec));
            double cosH = (Math.sin(Math.toRadians(-0.833)) - Math.sin(Math.toRadians(lat)) * sinDec)
                    / (Math.cos(Math.toRadians(lat)) * cosDec);
            if (cosH > 1 || cosH < -1) return new String[]{"--:--", "--:--"};
            double H = Math.toDegrees(Math.acos(cosH));
            double Jrise = Jtransit - H / 360.0;
            double Jset  = Jtransit + H / 360.0;
            // JD → UTC → KST (+9)
            double riseUTC = (Jrise - Math.floor(Jrise) - 0.5) * 24 + 9;
            double setUTC  = (Jset  - Math.floor(Jset)  - 0.5) * 24 + 9;
            if (riseUTC < 0) riseUTC += 24; if (riseUTC >= 24) riseUTC -= 24;
            if (setUTC  < 0) setUTC  += 24; if (setUTC  >= 24) setUTC  -= 24;
            String sr = String.format("%02d:%02d", (int) riseUTC, (int) ((riseUTC % 1) * 60));
            String ss = String.format("%02d:%02d", (int) setUTC,  (int) ((setUTC  % 1) * 60));
            return new String[]{sr, ss};
        } catch (Exception e) {
            return new String[]{"--:--", "--:--"};
        }
    }

    /** wx 맵에서 여러 키 중 값이 있는 첫 번째 반환 */
    private String getWxVal(java.util.Map<String, String> wx, String... keys) {
        for (String k : keys) {
            String v = wx.get(k);
            if (v != null && !v.isEmpty() && !v.equals("-9") && !v.equals("-99")
                    && !v.equals("-999") && !v.equals("-9999")) return v;
        }
        return "-";
    }

    /** PTY 한글 문자열 → 날씨 아이콘 이모지 */
    private String resolveWeatherIcon(String pty) {
        return resolveWeatherIcon(pty, null);
    }
    private String resolveWeatherIcon(String pty, String skyRaw) {
        android.content.SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String pNum = p.getString("wx_pty_num", "0");
        int pN = 0;
        try { pN = (int) Double.parseDouble(pNum.trim()); } catch (Exception ig) {}
        if (pN == 0 && pty != null) {
            if (pty.contains("비") || pty.contains("빗방울")) pN = 1;
            else if (pty.contains("눈")) pN = 3;
        }
        android.util.Log.d("ICON_DEBUG", "resolveIcon pNum="+pNum+" pN="+pN+" pty="+pty);
        if (pN == 0) {
            String sky = (skyRaw != null && !skyRaw.isEmpty()) ? skyRaw : p.getString("wx_sky", "1");
            int sN = 1;
            try {
                double sd = Double.parseDouble(sky.trim());
                if (sd > 0) sN = (int) sd;
            } catch (Exception ig) {}
            return sN == 1 ? "☀" : sN == 3 ? "⛅" : "☁";
        } else if (pN == 1 || pN == 4 || pN == 5 || pN == 6) return "🌧";
        else if (pN == 2) return "🌨";
        else if (pN == 3 || pN == 7) return "❄";
        else return "🌧";
    }

    private void renderWeatherCard(LinearLayout card, TextView tvLoading,
                                   String temp, String hum, String wsd,
                                   String windDir, String rain, String pty, String timeStr,
                                   String sunrise, String sunset,
                                   String pm25Val, String pm10Val, String o3Val,
                                   String pm25Grade, String pm10Grade, String o3Grade) {
        card.removeView(tvLoading);
        card.setClipChildren(true);
        card.setClipToPadding(true);
        card.setPadding(0, 0, 0, 0);  // 카드 패딩 제거 (내부 패널에서 처리)

        // ── FrameLayout: 앞면(기본) + 뒷면(상세) 겹침 ──────────────
        android.widget.FrameLayout flipFrame = new android.widget.FrameLayout(this);
        flipFrame.setClipChildren(false);
        flipFrame.setClipToPadding(false);
        flipFrame.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 앞면: 위치+기온+미세먼지 ───────────────────────────────
        LinearLayout frontPanel = new LinearLayout(this);
        frontPanel.setOrientation(LinearLayout.VERTICAL);
        frontPanel.setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(2));
        frontPanel.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        // 위치 + 기온 + 🔄
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout locBox = new LinearLayout(this);
        locBox.setOrientation(LinearLayout.VERTICAL);
        locBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvLoc = new TextView(this);
        tvLoc.setText("📍 대전 유성구 지족동");
        tvLoc.setTextColor(Color.parseColor("#4A90D9"));
        tvLoc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvLoc.setTypeface(null, android.graphics.Typeface.BOLD);
        locBox.addView(tvLoc);
        TextView tvTime = new TextView(this);
        tvTime.setText(timeStr);
        tvTime.setTextColor(Color.parseColor("#AAAAAA"));
        tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        tvTime.setTypeface(null, android.graphics.Typeface.BOLD);
        locBox.addView(tvTime);
        topRow.addView(locBox);
        TextView btnRefresh = new TextView(this);
        btnRefresh.setText("🔄");
        btnRefresh.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(18));
        btnRefresh.setGravity(Gravity.CENTER);
        btnRefresh.setTextColor(Color.parseColor("#FF6B6B"));
        LinearLayout.LayoutParams rfLp = new LinearLayout.LayoutParams(dpToPx(38), dpToPx(38));
        rfLp.setMargins(0, 0, dpToPx(8), 0);
        rfLp.gravity = Gravity.CENTER_VERTICAL;
        btnRefresh.setLayoutParams(rfLp);
        btnRefresh.setOnClickListener(v -> {
            // 인스턴스 변수로 중복 방지 (버튼이 재생성돼도 유효)
            if (isWeatherLoading) return;
            isWeatherLoading = true;
            btnRefresh.setEnabled(false);
            // 새로고침: 캐시 삭제 + 인스턴스 변수 초기화 → API 재호출
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .remove("wx_temp").remove("wx_hum").remove("wx_wsd").remove("wx_wdir")
                    .remove("wx_rain").remove("wx_pty").remove("wx_pty_num")
                    .remove("wx_sky").remove("wx_time")
                    .remove("wx_sunrise").remove("wx_sunset")
                    .remove("wx_pm25val").remove("wx_pm10val").remove("wx_o3val")
                    .remove("wx_pm25g").remove("wx_pm10g").remove("wx_o3g")
                    .apply();
            weatherSlideShown = false;
            weatherLoadedThisSession = false;
            card.removeAllViews();
            TextView tvL = new TextView(this);
            tvL.setText("날씨 정보를 불러오는 중...");
            tvL.setTextColor(Color.parseColor("#AAAAAA"));
            tvL.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            tvL.setGravity(Gravity.CENTER);
            tvL.setPadding(0, dpToPx(8), 0, dpToPx(8));
            card.addView(tvL);
            loadWeatherData(card, tvL, () -> isWeatherLoading = false);
        });
        // 온도: WRAP_CONTENT + START → 숫자가 왼쪽으로 늘어남
        TextView tvTemp = new TextView(this);
        tvTemp.setTag("wx_temp");
        tvTemp.setText(temp + "°C");
        tvTemp.setTextColor(Color.parseColor("#1A1A2E"));
        tvTemp.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(28));
        tvTemp.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTemp.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tvTemp.setSingleLine(true);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tempLp.gravity = Gravity.CENTER_VERTICAL;
        tempLp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        tvTemp.setLayoutParams(tempLp);
        topRow.addView(tvTemp);
        topRow.addView(btnRefresh);
        frontPanel.addView(topRow);

        if (!"없음".equals(pty)) {
            TextView tvPty = new TextView(this);
            tvPty.setText("🌧  현재 날씨: " + pty);
            tvPty.setTextColor(Color.parseColor("#4A90D9"));
            tvPty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            LinearLayout.LayoutParams ptyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ptyLp.setMargins(0, dpToPx(2), 0, 0);
            tvPty.setLayoutParams(ptyLp);
            frontPanel.addView(tvPty);
        }

        // 미세먼지 행
        String[] gradeColors = {"#CCCCCC", "#4FC3F7", "#66BB6A", "#FFA726", "#EF5350"};
        String[] gradeNames  = {"-", "좋음", "보통", "나쁨", "매우나쁨"};
        Object[][] dusts = {
                {"초미세먼지", pm25Val + "㎍", pm25Grade},
                {"미세먼지",   pm10Val + "㎍", pm10Grade},
                {"오존",       o3Val + "ppm",  o3Grade}
        };
        LinearLayout dustRow = new LinearLayout(this);
        dustRow.setOrientation(LinearLayout.HORIZONTAL);
        dustRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        drLp.setMargins(0, 0, 0, dpToPx(2));
        dustRow.setLayoutParams(drLp);
        for (int i = 0; i < dusts.length; i++) {
            LinearLayout db = new LinearLayout(this);
            db.setOrientation(LinearLayout.VERTICAL);
            db.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            dbLp.setMargins(0, 0, 0, 0);
            db.setLayoutParams(dbLp);
            int grade = 0;
            try { grade = Integer.parseInt((String) dusts[i][2]); } catch (Exception ignored) {}
            if (grade < 0 || grade >= gradeColors.length) grade = 0;
            int r = dpToPx(32);
            TextView tvCircle = new TextView(this);
            tvCircle.setText(gradeNames[grade]);
            tvCircle.setTextColor(Color.WHITE);
            tvCircle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvCircle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvCircle.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable circle =
                    new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(Color.parseColor(gradeColors[grade]));
            tvCircle.setBackground(circle);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(r, r);
            cLp.gravity = Gravity.CENTER_HORIZONTAL;
            tvCircle.setLayoutParams(cLp);
            db.addView(tvCircle);
            TextView tvDustVal = new TextView(this);
            tvDustVal.setText((String) dusts[i][1]);
            tvDustVal.setTextColor(Color.parseColor("#333333"));
            tvDustVal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvDustVal.setTypeface(null, android.graphics.Typeface.BOLD);
            tvDustVal.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dvLp.setMargins(0, 0, 0, 0);
            tvDustVal.setLayoutParams(dvLp);
            db.addView(tvDustVal);
            TextView tvDustLbl = new TextView(this);
            tvDustLbl.setText((String) dusts[i][0]);
            tvDustLbl.setTextColor(Color.parseColor("#555555"));
            tvDustLbl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvDustLbl.setTypeface(null, android.graphics.Typeface.BOLD);
            tvDustLbl.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlLp.setMargins(0, 0, 0, 0);
            tvDustLbl.setLayoutParams(dlLp);
            db.addView(tvDustLbl);
            dustRow.addView(db);
            if (i < dusts.length - 1) {
                android.view.View dv = new android.view.View(this);
                dv.setBackgroundColor(Color.parseColor("#F0EEF8"));
                LinearLayout.LayoutParams dvpLp = new LinearLayout.LayoutParams(1, dpToPx(24));
                dvpLp.gravity = Gravity.CENTER_VERTICAL;
                dv.setLayoutParams(dvpLp);
                dustRow.addView(dv);
            }
        }

        frontPanel.addView(dustRow);
        flipFrame.addView(frontPanel);

        // frontPanel 렌더링 후 flipFrame + weatherCard 높이를 frontPanel 기준으로 완전 고정
        frontPanel.post(() -> {
            int fh = frontPanel.getHeight();
            if (fh <= 0) return;
            // flipFrame + weatherCard 높이만 고정 (headerWeatherFrame은 건드리지 않음)
            LinearLayout.LayoutParams ffLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, fh);
            flipFrame.setLayoutParams(ffLp);
            android.view.ViewParent vp = flipFrame.getParent();
            if (vp instanceof android.view.View) {
                android.view.View wc = (android.view.View) vp;
                android.view.ViewGroup.LayoutParams wlp = wc.getLayoutParams();
                wlp.height = fh;
                wc.setLayoutParams(wlp);
            }
        });

        // ── 뒷면: 습도/바람/강수 + 일출/일몰 + 닫기 ─────────────────
        LinearLayout backPanel = new LinearLayout(this);
        backPanel.setOrientation(LinearLayout.VERTICAL);
        backPanel.setGravity(Gravity.CENTER_VERTICAL);
        backPanel.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        android.widget.FrameLayout.LayoutParams bpLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        backPanel.setLayoutParams(bpLp);
        backPanel.setTranslationX(card.getWidth() > 0 ? card.getWidth() : dpToPx(400)); // 처음엔 오른쪽 밖

        // 상단: 닫기(←) 버튼
        // 습도/바람/강수
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        String[][] infos = {
                {"💧", "습도", hum + "%"},
                {"💨", "바람", windDir + " " + wsd + "m/s"},
                {"🌂", "강수", rain.equals("0") ? "없음" : rain + "mm"}
        };
        for (int i = 0; i < infos.length; i++) {
            LinearLayout infoBox = new LinearLayout(this);
            infoBox.setOrientation(LinearLayout.VERTICAL);
            infoBox.setGravity(Gravity.CENTER);
            infoBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView tvIco = new TextView(this);
            tvIco.setText(infos[i][0] + "  " + infos[i][1]);
            tvIco.setTextColor(Color.parseColor("#888888"));
            tvIco.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvIco.setGravity(Gravity.CENTER);
            infoBox.addView(tvIco);
            TextView tvVal = new TextView(this);
            tvVal.setText(infos[i][2]);
            tvVal.setTextColor(Color.parseColor("#1A1A2E"));
            tvVal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
            tvVal.setTypeface(null, android.graphics.Typeface.BOLD);
            tvVal.setGravity(Gravity.CENTER);
            tvVal.setSingleLine(true);
            tvVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            valLp.setMargins(0, dpToPx(3), 0, 0);
            tvVal.setLayoutParams(valLp);
            infoBox.addView(tvVal);
            infoRow.addView(infoBox);
            if (i < infos.length - 1) {
                android.view.View vDiv = new android.view.View(this);
                vDiv.setBackgroundColor(Color.parseColor("#F0EEF8"));
                vDiv.setLayoutParams(new LinearLayout.LayoutParams(1, dpToPx(32)));
                infoRow.addView(vDiv);
            }
        }
        backPanel.addView(infoRow);
        backPanel.addView(makeDivider(dpToPx(8)));

        // 일출/일몰
        LinearLayout sunRow = new LinearLayout(this);
        sunRow.setOrientation(LinearLayout.HORIZONTAL);
        sunRow.setGravity(Gravity.CENTER_VERTICAL);
        sunRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        String[][] sunInfos = {{"🌅", "일출", sunrise}, {"🌇", "일몰", sunset}};
        for (int i = 0; i < sunInfos.length; i++) {
            LinearLayout sb = new LinearLayout(this);
            sb.setOrientation(LinearLayout.HORIZONTAL);
            sb.setGravity(Gravity.CENTER);
            sb.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView stv = new TextView(this);
            stv.setText(sunInfos[i][0] + "  " + sunInfos[i][1] + "  ");
            stv.setTextColor(Color.parseColor("#888888"));
            stv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            sb.addView(stv);
            TextView svv = new TextView(this);
            svv.setText(sunInfos[i][2]);
            svv.setTextColor(Color.parseColor("#1A1A2E"));
            svv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            svv.setTypeface(null, android.graphics.Typeface.BOLD);
            sb.addView(svv);
            sunRow.addView(sb);
            if (i == 0) {
                android.view.View sv = new android.view.View(this);
                sv.setBackgroundColor(Color.parseColor("#F0EEF8"));
                sv.setLayoutParams(new LinearLayout.LayoutParams(1, dpToPx(24)));
                sunRow.addView(sv);
            }
        }
        backPanel.addView(sunRow);
        flipFrame.addView(backPanel);
        card.addView(flipFrame);

        // 뒤늦게 backPanel 초기 위치를 카드 너비로 설정
        flipFrame.post(() -> {
            int w = flipFrame.getWidth();
            if (w > 0) backPanel.setTranslationX(w);
        });

        // ── 슬라이드 전환: 0=날씨, 1=날씨상세 순환, 항상 오른쪽에서 들어옴 ──
        // 초기: backPanel 오른쪽 밖에 대기
        flipFrame.post(() -> {
            int w = flipFrame.getWidth();
            if (w > 0) backPanel.setTranslationX(w);
        });

        final int[] page = {0}; // 0=날씨, 1=날씨상세

        android.view.View.OnClickListener pageFlip = v -> {
            int w = flipFrame.getWidth();
            int cur = page[0];
            int next = (cur + 1) % 2;
            android.view.View curView  = cur == 0 ? frontPanel : backPanel;
            android.view.View nextView = next == 0 ? frontPanel : backPanel;
            float outTo, inFrom;
            if (cur == 0) {
                // 날씨 → 날씨상세: 오른쪽에서 들어옴
                outTo = -w; inFrom = w;
            } else {
                // 날씨상세 → 날씨: 왼쪽에서 들어옴
                outTo = w; inFrom = -w;
            }
            nextView.setTranslationX(inFrom);
            android.animation.ObjectAnimator outAnim = android.animation.ObjectAnimator
                    .ofFloat(curView, "translationX", 0f, outTo);
            android.animation.ObjectAnimator inAnim = android.animation.ObjectAnimator
                    .ofFloat(nextView, "translationX", inFrom, 0f);
            outAnim.setDuration(300);
            inAnim.setDuration(300);
            outAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            inAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            set.playTogether(outAnim, inAnim);
            set.start();
            page[0] = next;
        };

        frontPanel.setClickable(true);
        frontPanel.setOnClickListener(pageFlip);
        backPanel.setClickable(true);
        backPanel.setOnClickListener(pageFlip);
    }

    // ── 인라인 날씨 뷰 빌더 (히어로 배경 안) ──────────────
    // 날씨 행: 12°  ⛅ 맑음 · 대전 유성구 지족동  [↻]
    // 뱃지 행: [초미세먼지 (좋음)]  [미세먼지 (보통)]
    private LinearLayout buildInlineWeatherView() {
        LinearLayout wx = new LinearLayout(this);
        wx.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wxLp.setMargins(0, dpToPx(2), 0, dpToPx(4));
        wx.setLayoutParams(wxLp);

        android.widget.FrameLayout flipFrame = new android.widget.FrameLayout(this);
        flipFrame.setClipChildren(true);
        flipFrame.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ═══ 앞면 ═══
        LinearLayout frontPanel = new LinearLayout(this);
        frontPanel.setOrientation(LinearLayout.VERTICAL);
        frontPanel.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        // 행1: 온도 | (아이콘 + "맑음 · 지족동") | 새로고침 버튼
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 온도: WRAP_CONTENT + Gravity.END → 숫자 늘어날수록 왼쪽으로
        TextView tvTemp = new TextView(this);
        tvTemp.setTag("wx_temp");
        tvTemp.setText("--°");
        tvTemp.setTextColor(Color.WHITE);
        tvTemp.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(38));
        tvTemp.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTemp.setShadowLayer(6f, 0f, 2f, 0x50000000);
        tvTemp.setSingleLine(true);
        tvTemp.setIncludeFontPadding(false);
        tvTemp.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLp.setMargins(dpToPx(4), 0, 0, 0);
        tvTemp.setLayoutParams(tLp);
        // centerBox: VERTICAL — (아이콘+맑음 한줄) / (시간 한줄)
        LinearLayout centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams centerBoxLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        centerBoxLp.setMargins(dpToPx(16), 0, 0, 0);
        centerBox.setLayoutParams(centerBoxLp);

        // 상단: 아이콘 + "맑음 · 대전 유성구 지족동" 가로행
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 온도: WRAP_CONTENT — 음수/소수점 온도 어떤 값도 잘리지 않음
        LinearLayout.LayoutParams tLpS = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tLpS.setMargins(0, 0, dpToPx(4), 0);
        tLpS.gravity = Gravity.CENTER_VERTICAL;
        tvTemp.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tvTemp.setLayoutParams(tLpS);
        stateRow.addView(tvTemp);

        // 날씨 아이콘
        TextView tvWxIcon = new TextView(this);
        tvWxIcon.setTag("wx_icon");
        android.content.SharedPreferences _p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        tvWxIcon.setText(resolveWeatherIcon(_p.getString("wx_pty", "없음"), _p.getString("wx_sky", "1")));
        tvWxIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(20));
        tvWxIcon.setShadowLayer(0f, 0f, 0f, 0x00000000);
        LinearLayout.LayoutParams icoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        icoLp.setMargins(0, 0, dpToPx(5), 0);
        tvWxIcon.setLayoutParams(icoLp);
        stateRow.addView(tvWxIcon);

        // locTimeBox: "대전 유성구 지족동" + 날짜 세로 묶음 → 날짜가 지족동 바로 아래 정렬
        LinearLayout locTimeBox = new LinearLayout(this);
        locTimeBox.setOrientation(LinearLayout.VERTICAL);
        locTimeBox.setGravity(Gravity.START);
        locTimeBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // "맑음 · 대전 유성구 지족동" — 1줄 고정 + ... 말줄임
        TextView tvStateLoc = new TextView(this);
        tvStateLoc.setTag("wx_state");
        tvStateLoc.setText("불러오는 중...");
        tvStateLoc.setTextColor(Color.WHITE);
        tvStateLoc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvStateLoc.setTypeface(null, android.graphics.Typeface.BOLD);
        tvStateLoc.setShadowLayer(4f, 0f, 1f, 0x40000000);
        tvStateLoc.setSingleLine(true);
        tvStateLoc.setMaxLines(1);
        tvStateLoc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvStateLoc.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        locTimeBox.addView(tvStateLoc);

        // 날짜: 지족동 바로 아래 정렬
        TextView tvWxTime = new TextView(this);
        tvWxTime.setTag("wx_time_disp");
        tvWxTime.setText("");
        tvWxTime.setTextColor(Color.parseColor("#CCFFFFFF"));
        tvWxTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(10));
        tvWxTime.setShadowLayer(2f, 0f, 1f, 0x30000000);
        tvWxTime.setGravity(Gravity.START);
        tvWxTime.setSingleLine(true);
        tvWxTime.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        locTimeBox.addView(tvWxTime);

        stateRow.addView(locTimeBox);
        centerBox.addView(stateRow);

        row1.addView(centerBox);

        // 새로고침 버튼 — 원 작게(28dp), 글자 크게+굵게
        // 날씨 새로고침 — 단순 원형 버튼: #8C6CE7 alpha140 + stroke #AAFFFFFF 3dp + 흰 글자
        TextView btnWxRefresh = new TextView(this);
        btnWxRefresh.setText("↻");
        btnWxRefresh.setTextColor(Color.WHITE);
        btnWxRefresh.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(18));
        btnWxRefresh.setTypeface(null, android.graphics.Typeface.BOLD);
        btnWxRefresh.setGravity(Gravity.CENTER);
        btnWxRefresh.setIncludeFontPadding(false);
        btnWxRefresh.setPadding(0, dpToPx(2), 0, 0);
        btnWxRefresh.setShadowLayer(4f, 0f, 1f, 0x40000000);
        android.graphics.drawable.GradientDrawable rBgNew =
                new android.graphics.drawable.GradientDrawable();
        rBgNew.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        rBgNew.setColor(Color.parseColor("#8C6CE7"));
        rBgNew.setAlpha(140);
        rBgNew.setStroke(dpToPx(3), Color.parseColor("#AAFFFFFF"));
        btnWxRefresh.setBackground(rBgNew);
        LinearLayout.LayoutParams wxFLp = new LinearLayout.LayoutParams(dpToPx(34), dpToPx(34));
        wxFLp.setMargins(dpToPx(8), 0, 0, 0);
        wxFLp.gravity = Gravity.CENTER_VERTICAL;
        btnWxRefresh.setLayoutParams(wxFLp);

        btnWxRefresh.setOnClickListener(v -> {
            if (isWeatherLoading) return;
            isWeatherLoading = true;
            // 버튼 비활성화 + 반투명으로 로딩 중 표시
            btnWxRefresh.setEnabled(false);
            btnWxRefresh.setAlpha(0.4f);
            // 기존 날씨 화면은 그대로 유지하면서 백그라운드 API 호출
            weatherLoadedThisSession = false;
            lastValidTmfc = null; // 현재 시각 기준으로 재탐색
            // SharedPreferences 캐시만 삭제 (UI는 그대로)
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .remove("wx_temp").remove("wx_hum").remove("wx_wsd").remove("wx_wdir")
                    .remove("wx_rain").remove("wx_pty").remove("wx_pty_num")
                    .remove("wx_sky").remove("wx_time")
                    .remove("wx_sunrise").remove("wx_sunset")
                    .remove("wx_pm25val").remove("wx_pm10val").remove("wx_o3val")
                    .remove("wx_pm25g").remove("wx_pm10g").remove("wx_o3g")
                    .apply();
            // 더미 카드로 API 호출 → 완료되면 인라인 뷰만 자연스럽게 갱신
            LinearLayout dummyCard2 = new LinearLayout(PinActivity.this);
            TextView dummyLoading2 = new TextView(PinActivity.this);
            loadWeatherData(dummyCard2, dummyLoading2, () -> {
                android.content.SharedPreferences p2 =
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                updateInlineWeatherView(
                        p2.getString("wx_temp", "--"),
                        p2.getString("wx_pty",  "없음"),
                        p2.getString("wx_sky",  ""),
                        p2.getString("wx_pm25val", "-"),
                        p2.getString("wx_pm10val", "-"),
                        p2.getString("wx_pm25g",   "0"),
                        p2.getString("wx_pm10g",   "0"));
                isWeatherLoading = false;
                // 버튼 복원
                runOnUiThread(() -> {
                    btnWxRefresh.setEnabled(true);
                    btnWxRefresh.setAlpha(1f);
                });
            });
            // 예보도 함께 새로고침
            if (savedForecastBackPanel != null) {
                loadDailyForecast(savedForecastBackPanel);
            }
        });
        row1.addView(btnWxRefresh);
        frontPanel.addView(row1);

        // 행2: 미세먼지 뱃지 (아이콘/텍스트 줄 바로 아래)
        LinearLayout row2 = new LinearLayout(this);
        row2.setTag("wx_badges");
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2Lp.setMargins(0, dpToPx(10), 0, 0);
        row2.setLayoutParams(r2Lp);
        row2.addView(makeDustBadge("초미세먼지", "#AAAAAA"));
        row2.addView(makeDustBadge("미세먼지", "#AAAAAA"));
        frontPanel.addView(row2);

        flipFrame.addView(frontPanel);

        // ═══ 뒷면: 일별 예보 ═══
        LinearLayout backPanel = new LinearLayout(this);
        backPanel.setOrientation(LinearLayout.VERTICAL);
        backPanel.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        backPanel.setTranslationX(3000f);
        backPanel.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
        savedForecastBackPanel = backPanel;

        // 로딩 텍스트
        TextView tvFcstLoad2 = new TextView(this);
        tvFcstLoad2.setText("예보 불러오는 중...");
        tvFcstLoad2.setTextColor(Color.parseColor("#CCFFFFFF"));
        tvFcstLoad2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        tvFcstLoad2.setGravity(Gravity.CENTER);
        tvFcstLoad2.setTag("fcst_loading2");
        tvFcstLoad2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(112)));
        backPanel.addView(tvFcstLoad2);

        flipFrame.addView(backPanel);
        wx.addView(flipFrame);

        // 예보 데이터 비동기 로드
        loadDailyForecast(backPanel);

        // 슬라이드 클릭 (앞면 탭→뒷면)
        final int[] page = {0};
        final int[] frontH = {0};
        final android.view.View.OnClickListener[] goFrontHolder = {null};

        android.view.View.OnClickListener pageFlip = v -> {
            int w = flipFrame.getWidth();
            if (w <= 0) w = dpToPx(360);
            int cur = page[0], next = (cur + 1) % 2;
            android.view.View curV  = cur  == 0 ? frontPanel : backPanel;
            android.view.View nextV = next == 0 ? frontPanel : backPanel;
            float outTo = cur == 0 ? -w : w;
            float inFrom = cur == 0 ? w : -w;
            nextV.setTranslationX(inFrom);
            android.animation.ObjectAnimator outAnim = android.animation.ObjectAnimator
                    .ofFloat(curV, "translationX", 0f, outTo);
            android.animation.ObjectAnimator inAnim  = android.animation.ObjectAnimator
                    .ofFloat(nextV, "translationX", inFrom, 0f);
            outAnim.setDuration(300); inAnim.setDuration(300);
            outAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            inAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            if (next == 1) {
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                flipFrame.setLayoutParams(fp);
                android.widget.FrameLayout.LayoutParams bp =
                        new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
                backPanel.setLayoutParams(bp);
            } else {
                int fh2 = frontH[0] > 0 ? frontH[0] : frontPanel.getHeight();
                flipFrame.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, fh2));
                backPanel.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT, fh2));
            }
            android.animation.AnimatorSet set = new android.animation.AnimatorSet();
            set.playTogether(outAnim, inAnim);
            set.start();
            page[0] = next;
        };

        goFrontHolder[0] = v -> {
            if (page[0] != 1) return;
            int w = flipFrame.getWidth(); if (w <= 0) w = dpToPx(360);
            frontPanel.setTranslationX(w);
            android.animation.ObjectAnimator outAnim = android.animation.ObjectAnimator
                    .ofFloat(backPanel, "translationX", 0f, w);
            android.animation.ObjectAnimator inAnim  = android.animation.ObjectAnimator
                    .ofFloat(frontPanel, "translationX", w, 0f);
            outAnim.setDuration(300); inAnim.setDuration(300);
            outAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            inAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            int fh2 = frontH[0] > 0 ? frontH[0] : frontPanel.getHeight();
            flipFrame.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, fh2));
            backPanel.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, fh2));
            android.animation.AnimatorSet gfSet = new android.animation.AnimatorSet();
            gfSet.playTogether(outAnim, inAnim);
            gfSet.start();
            page[0] = 0;
        };

        frontPanel.setClickable(true);
        frontPanel.setOnClickListener(pageFlip);
        backPanel.setClickable(true);
        backPanel.setOnTouchListener(new android.view.View.OnTouchListener() {
            float startX, startY; boolean isDragging = false;
            android.widget.HorizontalScrollView activeScroll = null;
            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startX = e.getX(); startY = e.getY(); isDragging = false;
                        activeScroll = null;
                        for (int ci = 0; ci < backPanel.getChildCount(); ci++) {
                            android.view.View ch = backPanel.getChildAt(ci);
                            if (ch instanceof LinearLayout) {
                                LinearLayout ll = (LinearLayout) ch;
                                for (int cj = 0; cj < ll.getChildCount(); cj++) {
                                    if (ll.getChildAt(cj) instanceof android.widget.HorizontalScrollView) {
                                        activeScroll = (android.widget.HorizontalScrollView) ll.getChildAt(cj);
                                        break;
                                    }
                                }
                            }
                            if (activeScroll != null) break;
                        }
                        if (activeScroll != null) activeScroll.onTouchEvent(e);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(e.getX() - startX), dy = Math.abs(e.getY() - startY);
                        if (dx > dpToPx(6) || dy > dpToPx(6)) isDragging = true;
                        if (isDragging && dx > dy && activeScroll != null) activeScroll.onTouchEvent(e);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (activeScroll != null) activeScroll.onTouchEvent(e);
                        if (!isDragging) goFrontHolder[0].onClick(v);
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (activeScroll != null) activeScroll.onTouchEvent(e);
                        return true;
                }
                return false;
            }
        });

        frontPanel.post(() -> {
            int fh = frontPanel.getHeight();
            if (fh <= 0) return;
            frontH[0] = fh;
            flipFrame.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, fh));
            android.widget.FrameLayout.LayoutParams bpFix =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, fh);
            backPanel.setLayoutParams(bpFix);
            int realW = flipFrame.getWidth();
            if (realW > 0) backPanel.setTranslationX(realW);
        });

        return wx;
    }

    private void updateFcstError(LinearLayout backPanel, String msg) {
        android.view.View vl = backPanel.findViewWithTag("fcst_loading2");
        if (vl instanceof TextView) runOnUiThread(() -> ((TextView) vl).setText(msg));
    }

    private void loadDailyForecast(LinearLayout backPanel) {
        android.content.SharedPreferences cp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        // 캐시 버전 체크 - 버전 다르면 삭제 (날씨 로직 변경 시 강제 갱신)
        final String FCST_VER = "v6"; // PTY 최댓값 로직 적용 // 버전 바꾸면 캐시 자동 삭제
        String savedVer = cp.getString("daily_fcst_ver", "");
        if (!FCST_VER.equals(savedVer)) {
            cp.edit().remove("daily_fcst_cache").putString("daily_fcst_ver", FCST_VER).apply();
            android.util.Log.d("FCST","캐시버전 변경→삭제");
        }
        String rawCache = cp.getString("daily_fcst_cache", null);
        if (rawCache != null) {
            try {
                String cd = new org.json.JSONArray(rawCache).getJSONArray(0).getString(0);
                java.util.Calendar tc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                String td = String.format("%04d%02d%02d", tc.get(java.util.Calendar.YEAR), tc.get(java.util.Calendar.MONTH)+1, tc.get(java.util.Calendar.DAY_OF_MONTH));
                if (!cd.equals(td)) { cp.edit().remove("daily_fcst_cache").apply(); rawCache = null; android.util.Log.d("FCST","날짜변경→캐시삭제"); }
            } catch (Exception ig) {}
        }
        final String cachedFcst = rawCache;
        if (cachedFcst != null) {
            try {
                org.json.JSONArray j = new org.json.JSONArray(cachedFcst);
                java.util.List<String[]> cr = new java.util.ArrayList<>();
                for (int i = 0; i < j.length(); i++) {
                    org.json.JSONArray row = j.getJSONArray(i); String[] r = new String[8];
                    for (int k = 0; k < 8; k++) r[k] = row.getString(k); cr.add(r);
                }
                runOnUiThread(() -> renderDailyForecast(backPanel, cr));
            } catch (Exception ig) {}
        }
        new Thread(() -> {
            try {
                final String KEY = "4f9182aa6a8d775a6013c074fc5620578371c0031a6f97e9c0434e3973bcf1d5";
                java.util.Calendar now = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                int yr=now.get(java.util.Calendar.YEAR), mo=now.get(java.util.Calendar.MONTH)+1;
                int da=now.get(java.util.Calendar.DAY_OF_MONTH), hr=now.get(java.util.Calendar.HOUR_OF_DAY);
                int mn=now.get(java.util.Calendar.MINUTE);
                String today = String.format("%04d%02d%02d", yr, mo, da);
                String[] DAY_KR = {"일","월","화","수","목","금","토"};

                // ── 단기예보 발표시각 계산 ──
                int[] bTimes = {2,5,8,11,14,17,20,23};
                int bHour = 2;
                for (int bt : bTimes) { if (hr > bt || (hr == bt && mn >= 10)) bHour = bt; }
                String bDate = today;
                if (hr < 2 || (hr == 2 && mn < 10)) {
                    java.util.Calendar yc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                    yc.add(java.util.Calendar.DAY_OF_MONTH, -1);
                    bDate = String.format("%04d%02d%02d", yc.get(java.util.Calendar.YEAR), yc.get(java.util.Calendar.MONTH)+1, yc.get(java.util.Calendar.DAY_OF_MONTH));
                    bHour = 23;
                }
                String bTimeStr = String.format("%04d", bHour * 100);
                android.util.Log.d("FCST", "단기발표: "+bDate+" "+bTimeStr);

                // ── 단기예보 파싱 헬퍼 ──
                java.util.Map<String,java.util.Map<String,String>> sMap = new java.util.HashMap<>();
                android.util.Log.d("FCST","단기발표1: "+bDate+" "+bTimeStr);
                // 단기예보 파싱 (두 번 호출: 현재발표 + 보완용 이전발표)
                String[] bDates = {bDate}; String[] bTimes2 = {bTimeStr};
                // tMin 보완: 전날 2300 발표도 추가 요청
                java.util.Calendar yc2 = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                yc2.add(java.util.Calendar.DAY_OF_MONTH, -1);
                String yd2 = String.format("%04d%02d%02d", yc2.get(java.util.Calendar.YEAR), yc2.get(java.util.Calendar.MONTH)+1, yc2.get(java.util.Calendar.DAY_OF_MONTH));
                if (bHour <= 5) {
                    // 이미 전날 발표 사용 중이므로 추가 불필요
                    bDates = new String[]{bDate}; bTimes2 = new String[]{bTimeStr};
                } else {
                    // 현재 발표 + 전날 2300 발표 (tMin 보완)
                    bDates = new String[]{yd2, bDate}; bTimes2 = new String[]{"2300", bTimeStr};
                }
                for (int bi = 0; bi < bDates.length; bi++) {
                    try {
                        String url = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"
                                + "?serviceKey="+KEY+"&pageNo=1&numOfRows=3000&dataType=JSON"
                                + "&base_date="+bDates[bi]+"&base_time="+bTimes2[bi]+"&nx=67&ny=100";
                        java.net.HttpURLConnection sc = (java.net.HttpURLConnection)new java.net.URL(url).openConnection();
                        sc.setConnectTimeout(12000); sc.setReadTimeout(15000);
                        if (sc.getResponseCode() == 200) {
                            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(sc.getInputStream(),"UTF-8"));
                            StringBuilder sb = new StringBuilder(); String ln;
                            while ((ln = br.readLine()) != null) sb.append(ln); br.close();
                            org.json.JSONArray items = new org.json.JSONObject(sb.toString())
                                    .getJSONObject("response").getJSONObject("body")
                                    .getJSONObject("items").getJSONArray("item");
                            for (int i = 0; i < items.length(); i++) {
                                org.json.JSONObject it = items.getJSONObject(i);
                                String dt = it.getString("fcstDate");
                                String tm = it.getString("fcstTime");
                                String cat = it.getString("category");
                                String val = it.getString("fcstValue");
                                sMap.computeIfAbsent(dt, k2 -> new java.util.HashMap<>());
                                java.util.Map<String,String> dm = sMap.get(dt);
                                int t = Integer.parseInt(tm);
                                switch (cat) {
                                    case "SKY": {
                                        // SKY: 최솟값(가장 흐린) 사용
                                        String key = t<1200 ? "amSky" : "pmSky";
                                        if (!dm.containsKey(key)) dm.put(key, val);
                                        else { try { if(Integer.parseInt(val)<Integer.parseInt(dm.get(key))) dm.put(key,val); } catch(Exception ig){} }
                                        break; }
                                    case "PTY": {
                                        // PTY: 최댓값(가장 나쁜 날씨) 사용
                                        String key = t<1200 ? "amPty" : "pmPty";
                                        if (!dm.containsKey(key)) dm.put(key, val);
                                        else { try { if(Integer.parseInt(val)>Integer.parseInt(dm.get(key))) dm.put(key,val); } catch(Exception ig){} }
                                        break; }
                                    case "TMN": if(!dm.containsKey("tMin")) dm.put("tMin",val); break;
                                    case "TMX": if(!dm.containsKey("tMax")) dm.put("tMax",val); break;
                                    case "POP": {
                                        // POP: 최댓값 사용
                                        String key = t<1200 ? "amPop" : "pmPop";
                                        if (!dm.containsKey(key)) dm.put(key, val);
                                        else { try { if(Integer.parseInt(val)>Integer.parseInt(dm.get(key))) dm.put(key,val); } catch(Exception ig){} }
                                        break; }
                                }
                            }
                        }
                    } catch (Exception e) { android.util.Log.e("FCST","단기오류["+bi+"]:"+e.getMessage()); }
                }
                android.util.Log.d("FCST","단기 날짜 수: "+sMap.size()+" 키: "+sMap.keySet());

                // ── 중기예보 tmfc 계산 ──
                // 18시이후→당일1800, 나머지→전날1800 (항상 완성된 발표 사용)
                // 당일 0600은 wf4Am 등이 비어있는 경우가 많아 전날 1800이 더 안정적
                java.util.Calendar tmfcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                String tmfc;
                if (hr >= 18) {
                    tmfc = today + "1800";
                } else {
                    // 전날 1800 발표 사용 (wf3Am~wf10Am 모두 채워진 상태)
                    tmfcCal.add(java.util.Calendar.DAY_OF_MONTH, -1);
                    tmfc = String.format("%04d%02d%02d",
                            tmfcCal.get(java.util.Calendar.YEAR),
                            tmfcCal.get(java.util.Calendar.MONTH)+1,
                            tmfcCal.get(java.util.Calendar.DAY_OF_MONTH)) + "1800";
                }
                // tmfcCal → tmfc 날짜 자정으로 리셋 (파싱)
                int tyr=Integer.parseInt(tmfc.substring(0,4));
                int tmo=Integer.parseInt(tmfc.substring(4,6));
                int tda=Integer.parseInt(tmfc.substring(6,8));
                tmfcCal=java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                tmfcCal.set(tyr,tmo-1,tda,0,0,0); tmfcCal.set(java.util.Calendar.MILLISECOND,0);
                android.util.Log.d("FCST","중기tmfc: "+tmfc);

                // ── 중기 육상/기온 API ──
                org.json.JSONObject land = null, temp = null;
                try {
                    java.net.HttpURLConnection lc = (java.net.HttpURLConnection)new java.net.URL(
                            "https://apis.data.go.kr/1360000/MidFcstInfoService/getMidLandFcst"
                                    +"?serviceKey="+KEY+"&pageNo=1&numOfRows=10&dataType=JSON&regId=11C20401&tmFc="+tmfc).openConnection();
                    lc.setConnectTimeout(12000); lc.setReadTimeout(12000);
                    if (lc.getResponseCode()==200) {
                        java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(lc.getInputStream(),"UTF-8"));
                        StringBuilder sb=new StringBuilder(); String ln;
                        while((ln=br.readLine())!=null)sb.append(ln); br.close();
                        org.json.JSONObject lRes=new org.json.JSONObject(sb.toString()).getJSONObject("response");
                        if(lRes.has("body")&&!lRes.isNull("body")){
                            org.json.JSONArray la=lRes.getJSONObject("body").getJSONObject("items").getJSONArray("item");
                            if(la.length()>0){
                                land=la.getJSONObject(0);
                                android.util.Log.d("FCST","육상keys: "+land.toString().substring(0,Math.min(200,land.toString().length())));
                            }
                        }
                    }
                } catch(Exception e){android.util.Log.e("FCST","육상오류:"+e.getMessage());}
                // 기온 API: 복수 tmfc 시도 (0600이 없으면 전날1800, 당일0600 순)
                String[] tryTmfcs = {tmfc};
                if (!tmfc.endsWith("1800")) {
                    // 0600이면 전날 1800도 시도
                    java.util.Calendar yt2 = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                    yt2.add(java.util.Calendar.DAY_OF_MONTH, -1);
                    String yt2s = String.format("%04d%02d%02d", yt2.get(java.util.Calendar.YEAR), yt2.get(java.util.Calendar.MONTH)+1, yt2.get(java.util.Calendar.DAY_OF_MONTH))+"1800";
                    tryTmfcs = new String[]{tmfc, yt2s};
                }
                for (String tt : tryTmfcs) {
                    if (temp != null) break;
                    try {
                        java.net.HttpURLConnection tc2=(java.net.HttpURLConnection)new java.net.URL(
                                "https://apis.data.go.kr/1360000/MidFcstInfoService/getMidTa"
                                        +"?serviceKey="+KEY+"&pageNo=1&numOfRows=10&dataType=JSON&regId=133&tmFc="+tt).openConnection();
                        tc2.setConnectTimeout(12000); tc2.setReadTimeout(12000);
                        if(tc2.getResponseCode()==200){
                            java.io.BufferedReader br=new java.io.BufferedReader(new java.io.InputStreamReader(tc2.getInputStream(),"UTF-8"));
                            StringBuilder sb=new StringBuilder(); String ln;
                            while((ln=br.readLine())!=null)sb.append(ln); br.close();
                            org.json.JSONObject tRes=new org.json.JSONObject(sb.toString()).getJSONObject("response");
                            if(tRes.has("body")&&!tRes.isNull("body")){
                                org.json.JSONArray ta=tRes.getJSONObject("body").getJSONObject("items").getJSONArray("item");
                                if(ta.length()>0){ temp=ta.getJSONObject(0); android.util.Log.d("FCST","기온OK tmfc="+tt); }
                            }
                        }
                    } catch(Exception e){android.util.Log.e("FCST","기온오류("+tt+"):"+e.getMessage());}
                }

                // ── 5일 행 생성 ──
                java.util.List<String[]> rows = new java.util.ArrayList<>();
                java.util.Calendar dayCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
                dayCal.set(java.util.Calendar.HOUR_OF_DAY,0); dayCal.set(java.util.Calendar.MINUTE,0);
                dayCal.set(java.util.Calendar.SECOND,0); dayCal.set(java.util.Calendar.MILLISECOND,0);

                for (int d = 0; d < 5; d++) {
                    java.util.Calendar dc = (java.util.Calendar) dayCal.clone();
                    dc.add(java.util.Calendar.DAY_OF_MONTH, d);
                    String dDate = String.format("%04d%02d%02d", dc.get(java.util.Calendar.YEAR), dc.get(java.util.Calendar.MONTH)+1, dc.get(java.util.Calendar.DAY_OF_MONTH));
                    int dow = dc.get(java.util.Calendar.DAY_OF_WEEK)-1;
                    String dayLabel = (dc.get(java.util.Calendar.MONTH)+1)+"/"+dc.get(java.util.Calendar.DAY_OF_MONTH)+"("+DAY_KR[dow]+")";
                    String amIco="?",pmIco="?",tMin="-",tMax="-",amPop="-",pmPop="-";

                    // 1순위: 단기예보
                    java.util.Map<String,String> dm = sMap.get(dDate);
                    boolean shortOK = dm!=null && (dm.containsKey("amSky")||dm.containsKey("pmSky"));
                    if (shortOK) {
                        // 아이콘 계산 (인라인)
                        { String sk=dm.getOrDefault("amSky","1"),pt=dm.getOrDefault("amPty","0"); int p=0; try{p=Integer.parseInt(pt);}catch(Exception ig){} if(p==1||p==4)amIco="\uD83C\uDF27"; else if(p==2)amIco="\uD83C\uDF28"; else if(p==3)amIco="\u2744"; else{int s=1;try{s=Integer.parseInt(sk);}catch(Exception ig){} amIco=s==1?"\u2600":s==3?"\u26C5":"\u2601";} }
                        { String sk=dm.getOrDefault("pmSky","1"),pt=dm.getOrDefault("pmPty","0"); int p=0; try{p=Integer.parseInt(pt);}catch(Exception ig){} if(p==1||p==4)pmIco="\uD83C\uDF27"; else if(p==2)pmIco="\uD83C\uDF28"; else if(p==3)pmIco="\u2744"; else{int s=1;try{s=Integer.parseInt(sk);}catch(Exception ig){} pmIco=s==1?"\u2600":s==3?"\u26C5":"\u2601";} }
                        String rawMin=dm.getOrDefault("tMin","-");
                        String rawMax=dm.getOrDefault("tMax","-");
                        tMin = "-".equals(rawMin) ? "-" : rawMin.replace(".0","")+"°";
                        tMax = "-".equals(rawMax) ? "-" : rawMax.replace(".0","")+"°";
                        amPop = dm.getOrDefault("amPop","-")+"%";
                        pmPop = dm.getOrDefault("pmPop","-")+"%";
                        android.util.Log.d("FCST","d="+d+" 단기OK dDate="+dDate+" tMin="+tMin+" tMax="+tMax);
                    } else if (land!=null) {  // temp 없어도 육상만으로 날씨 표시
                        // 2순위: 중기예보 - tmfc 기준 일수 차이로 N 계산
                        long diffMs = dc.getTimeInMillis() - tmfcCal.getTimeInMillis();
                        int n = (int)(diffMs / (24L*60*60*1000));
                        android.util.Log.d("FCST","d="+d+" dDate="+dDate+" tmfcCal="+String.format("%04d%02d%02d",tmfcCal.get(java.util.Calendar.YEAR),tmfcCal.get(java.util.Calendar.MONTH)+1,tmfcCal.get(java.util.Calendar.DAY_OF_MONTH))+" n="+n);
                        // n이 3~10 사이면 유효, 아니면 인접 탐색
                        if (n >= 1 && n <= 12) {
                            // n=3~10 범위에서 유효한 값 찾기 (넓은 범위 탐색)
                            int bestN = -1;
                            // 1단계: n 정확히 시도
                            for (int tryN = Math.max(3,n-1); tryN <= Math.min(10,n+2); tryN++) {
                                if (!land.optString("wf"+tryN+"Am","").isEmpty()) { bestN=tryN; break; }
                            }
                            // 2단계: 그래도 없으면 3~10 전체 순회
                            if (bestN < 0) {
                                for (int tryN = 3; tryN <= 10; tryN++) {
                                    if (!land.optString("wf"+tryN+"Am","").isEmpty()) { bestN=tryN; break; }
                                }
                            }
                            android.util.Log.d("FCST","d="+d+" bestN="+bestN+" land="+( land!=null?"OK":"null")+" temp="+(temp!=null?"OK":"null"));
                            if (bestN >= 0) {
                                String wfAm = land.optString("wf"+bestN+"Am","");
                                String wfPm = land.optString("wf"+bestN+"Pm","");
                                // 아이콘 (인라인)
                                amIco=wfAm.contains("비")||wfAm.contains("소나기")?"\uD83C\uDF27":wfAm.contains("눈")&&wfAm.contains("비")?"\uD83C\uDF28":wfAm.contains("눈")?"\u2744":wfAm.contains("구름많")?"\u26C5":wfAm.contains("흐림")?"\u2601":"\u2600";
                                pmIco=wfPm.isEmpty()?amIco:wfPm.contains("비")||wfPm.contains("소나기")?"\uD83C\uDF27":wfPm.contains("눈")&&wfPm.contains("비")?"\uD83C\uDF28":wfPm.contains("눈")?"\u2744":wfPm.contains("구름많")?"\u26C5":wfPm.contains("흐림")?"\u2601":"\u2600";
                                amPop = land.optInt("rnSt"+bestN+"Am",0)+"%";
                                pmPop = land.optInt("rnSt"+bestN+"Pm",0)+"%";
                                tMin  = (temp!=null) ? temp.optInt("taMin"+bestN,0)+"°" : "-";
                                tMax  = (temp!=null) ? temp.optInt("taMax"+bestN,0)+"°" : "-";
                            }
                        }
                    }
                    // d=4 fallback: 중기예보도 없으면 d=3 데이터 근사 사용
                    if (d == 4 && "?".equals(amIco) && rows.size() >= 4) {
                        String[] prev = rows.get(3); // d=3 데이터
                        amIco = prev[2]; pmIco = prev[3];
                        tMin = prev[4]; tMax = prev[5];
                        amPop = prev[6]; pmPop = prev[7];
                        android.util.Log.d("FCST","d=4 fallback → d=3 데이터 사용");
                    }
                    rows.add(new String[]{dDate,dayLabel,amIco,pmIco,tMin,tMax,amPop,pmPop});
                }

                // ── 캐시 저장 (? 없을 때만) ──
                boolean canSave = true;
                for (String[] r : rows) for (String s : r) if ("?".equals(s)){canSave=false;break;}
                if (!canSave) {
                    // 마지막 행만 ? → 캐시에서 복원 시도
                    String[] last = rows.get(rows.size()-1);
                    boolean lastQ = false; for(String s:last) if("?".equals(s)){lastQ=true;break;}
                    if (lastQ && cachedFcst!=null) {
                        try {
                            org.json.JSONArray ca=new org.json.JSONArray(cachedFcst);
                            if (ca.length()>=rows.size()) {
                                org.json.JSONArray cl=ca.getJSONArray(rows.size()-1);
                                if (cl.getString(0).equals(last[0])) {
                                    String[] res=new String[8]; for(int i=0;i<8;i++)res[i]=cl.getString(i);
                                    rows.set(rows.size()-1,res);
                                }
                            }
                        } catch(Exception ig){}
                    }
                }
                boolean doSave=true; for(String[] r:rows) for(String s:r) if("?".equals(s)){doSave=false;break;}
                if (doSave) {
                    org.json.JSONArray ja=new org.json.JSONArray();
                    for(String[] r:rows){org.json.JSONArray rr=new org.json.JSONArray();for(String s:r)rr.put(s);ja.put(rr);}
                    cp.edit().putString("daily_fcst_cache",ja.toString()).apply();
                }
                final java.util.List<String[]> fr = rows;
                runOnUiThread(()->renderDailyForecast(backPanel,fr));
            } catch(Exception e){
                android.util.Log.e("FCST","오류:"+e.getMessage());
                if(cachedFcst==null) runOnUiThread(()->updateFcstError(backPanel,"예보 로드 실패"));
            }
        }).start();
    }

    private void renderDailyForecast(LinearLayout backPanel, java.util.List<String[]> rows) {
        int colW=dpToPx(48), labelW=dpToPx(28);
        int rowH0=dpToPx(22), rowH1=dpToPx(28), rowH2=dpToPx(20), rowH3=dpToPx(20);

        // 스크롤 없이 전체 가로 배치 (짤림 방지)
        android.widget.HorizontalScrollView newScroll = new android.widget.HorizontalScrollView(this);
        newScroll.setHorizontalScrollBarEnabled(false);
        newScroll.setFillViewport(true);
        newScroll.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        LinearLayout newTable = new LinearLayout(this);
        newTable.setOrientation(LinearLayout.HORIZONTAL);

        java.util.Calendar todayCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Seoul"));
        String todayStr = String.format("%04d%02d%02d", todayCal.get(java.util.Calendar.YEAR),
                todayCal.get(java.util.Calendar.MONTH)+1, todayCal.get(java.util.Calendar.DAY_OF_MONTH));

        for (String[] row : rows) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackgroundColor(Color.parseColor("#7C6FE0"));
            col.setLayoutParams(new LinearLayout.LayoutParams(colW,LinearLayout.LayoutParams.WRAP_CONTENT));

            java.util.Calendar dc = java.util.Calendar.getInstance();
            try { dc.set(Integer.parseInt(row[0].substring(0,4)),Integer.parseInt(row[0].substring(4,6))-1,Integer.parseInt(row[0].substring(6,8))); } catch(Exception ig){}
            int dow = dc.get(java.util.Calendar.DAY_OF_WEEK);
            boolean isToday = row[0].equals(todayStr);
            int hdColor = isToday ? 0xFF5B21B6 : dow==java.util.Calendar.SUNDAY ? 0xFFB91C1C : dow==java.util.Calendar.SATURDAY ? 0xFF1D4ED8 : 0xFF6C5CE7;
            android.graphics.drawable.GradientDrawable hdBg = new android.graphics.drawable.GradientDrawable();
            hdBg.setColor(hdColor); hdBg.setCornerRadius(dpToPx(5));
            TextView tvDate = new TextView(this);
            tvDate.setText(row[1]); tvDate.setTextColor(Color.WHITE);
            tvDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(9));
            tvDate.setTypeface(null, android.graphics.Typeface.BOLD);
            tvDate.setGravity(Gravity.CENTER); tvDate.setBackground(hdBg);
            LinearLayout.LayoutParams hdLp = new LinearLayout.LayoutParams(colW,rowH0);
            hdLp.setMargins(0,0,0,dpToPx(2)); tvDate.setLayoutParams(hdLp);
            col.addView(tvDate);

            LinearLayout icoRow = new LinearLayout(this);
            icoRow.setOrientation(LinearLayout.HORIZONTAL);
            icoRow.setBackgroundColor(Color.parseColor("#EEE8FF"));
            icoRow.setLayoutParams(new LinearLayout.LayoutParams(colW,rowH1));
            for (int p=0;p<2;p++) {
                TextView tvI = new TextView(this); tvI.setText(p==0?row[2]:row[3]);
                tvI.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,fs(17));
                tvI.setGravity(Gravity.CENTER);
                tvI.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
                icoRow.addView(tvI);
            }
            col.addView(icoRow);

            LinearLayout tmpRow = new LinearLayout(this);
            tmpRow.setOrientation(LinearLayout.HORIZONTAL);
            tmpRow.setBackgroundColor(Color.parseColor("#EEE8FF"));
            tmpRow.setLayoutParams(new LinearLayout.LayoutParams(colW,rowH2));

            TextView tvMin=new TextView(this); tvMin.setText(row[4]);
            tvMin.setTextColor(Color.parseColor("#0066CC")); tvMin.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,fs(10));
            tvMin.setTypeface(null,android.graphics.Typeface.BOLD); tvMin.setGravity(Gravity.CENTER);
            tvMin.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
            TextView tvMax=new TextView(this); tvMax.setText(row[5]);
            tvMax.setTextColor(Color.parseColor("#CC2200")); tvMax.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,fs(10));
            tvMax.setTypeface(null,android.graphics.Typeface.BOLD); tvMax.setGravity(Gravity.CENTER);
            tvMax.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
            tmpRow.addView(tvMin); tmpRow.addView(tvMax);
            col.addView(tmpRow);

            LinearLayout popRow = new LinearLayout(this);
            popRow.setOrientation(LinearLayout.HORIZONTAL);
            popRow.setBackgroundColor(Color.parseColor("#EEE8FF"));
            popRow.setLayoutParams(new LinearLayout.LayoutParams(colW,rowH3));
            for (int p=0;p<2;p++) {
                TextView tvP=new TextView(this); tvP.setText(p==0?row[6]:row[7]);
                tvP.setTextColor(Color.parseColor("#2E7D32")); tvP.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,fs(9));
                tvP.setTypeface(null,android.graphics.Typeface.BOLD); tvP.setGravity(Gravity.CENTER);
                tvP.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f));
                popRow.addView(tvP);
            }
            col.addView(popRow);
            newTable.addView(col);
            // 구분선: 날짜 제외, 날씨~강수 행만
            android.view.View sep=new android.view.View(this);
            sep.setBackgroundColor(Color.parseColor("#CCCCCC"));
            int sepH = rowH1 + rowH2 + rowH3;
            LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(dpToPx(1), sepH);
            sepLp.setMargins(0, rowH0 + dpToPx(2), 0, 0);
            sep.setLayoutParams(sepLp);
            newTable.addView(sep);
        }
        newScroll.addView(newTable);

        // 레이블 열: ◀ / 날씨 / 기온 / 강수
        LinearLayout labelCol = new LinearLayout(this);
        labelCol.setOrientation(LinearLayout.VERTICAL);
        labelCol.setLayoutParams(new LinearLayout.LayoutParams(labelW,LinearLayout.LayoutParams.WRAP_CONTENT));
        String[] lbls={"◀","날씨","기온","강수"};
        int[] lblH={rowH0+dpToPx(2),rowH1,rowH2,rowH3};
        for (int i=0;i<lbls.length;i++) {
            TextView tvL=new TextView(this); tvL.setText(lbls[i]);
            tvL.setTextColor(Color.WHITE);
            tvL.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,i==0?fs(13):fs(10));
            tvL.setTypeface(null,android.graphics.Typeface.BOLD);
            tvL.setGravity(i==0?Gravity.CENTER:(Gravity.CENTER_VERTICAL|Gravity.END));
            tvL.setPadding(0,0,i==0?0:dpToPx(4),0);
            // 레이블 배경 없음
            tvL.setLayoutParams(new LinearLayout.LayoutParams(labelW,lblH[i]));
            labelCol.addView(tvL);
        }

        // contentRow를 가운데 정렬 wrapper로 감쌈
        LinearLayout contentRow = new LinearLayout(this);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER);
        // 전체 필요 너비: labelW + colW*5 + 구분선*5
        int totalW = labelW + (colW + dpToPx(1)) * 5;
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                totalW, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentRow.setLayoutParams(crLp);
        contentRow.addView(labelCol);
        // newScroll은 이미 newTable을 포함 → 그냥 scroll 추가
        newScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        contentRow.addView(newScroll);

        // 가운데 정렬 컨테이너
        backPanel.removeAllViews();
        backPanel.setPadding(0, dpToPx(4), 0, dpToPx(4));
        backPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        backPanel.addView(contentRow);
    }

    private TextView makeDustBadge(String label, String colorHex) { return makeDustBadge(label, colorHex, fs(12)); }
    private TextView makeDustBadge(String label, String colorHex, float textSize) {
        // 뱃지: "초미세먼지 (좋음)" 형태
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, textSize);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setShadowLayer(3f, 0f, 1f, 0x40000000);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dpToPx(12));
        try { bg.setColor(Color.parseColor(colorHex)); } catch (Exception e) { bg.setColor(0xFFAAAAAA); }
        tv.setBackground(bg);
        tv.setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dpToPx(14), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updateInlineWeatherView(String temp, String pty, String sky,
                                         String pm25Val, String pm10Val,
                                         String pm25Grade, String pm10Grade) {
        if (savedInlineWeatherView == null) return;
        // 기존 온도값과 다를 때만 페이드 전환 (캐시→실시간 교체 시 자연스럽게)
        View vTempCheck = savedInlineWeatherView.findViewWithTag("wx_temp");
        boolean hasExisting = vTempCheck instanceof TextView
                && !((TextView) vTempCheck).getText().toString().equals("--°")
                && !((TextView) vTempCheck).getText().toString().isEmpty();
        String newTempStr = temp + "°";
        boolean isUpdate = hasExisting
                && !((TextView) vTempCheck).getText().toString().equals(newTempStr);
        if (isUpdate) {
            // 페이드아웃 → 값 교체 → 페이드인
            savedInlineWeatherView.animate().alpha(0.4f).setDuration(200).withEndAction(() -> {
                applyInlineWeatherValues(temp, pty, sky, pm25Grade, pm10Grade);
                savedInlineWeatherView.animate().alpha(1f).setDuration(300).start();
            }).start();
            return;
        }
        applyInlineWeatherValues(temp, pty, sky, pm25Grade, pm10Grade);
    }

    private void applyInlineWeatherValues(String temp, String pty, String skyRaw,
                                          String pm25Grade, String pm10Grade) {
        if (savedInlineWeatherView == null) return;
        android.content.SharedPreferences p2 = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String hum     = p2.getString("wx_hum",  "--");
        String wsd     = p2.getString("wx_wsd",  "--");
        String rain    = p2.getString("wx_rain", "0");
        String sunrise = p2.getString("wx_sunrise", "--:--");
        String sunset  = p2.getString("wx_sunset",  "--:--");
        runOnUiThread(() -> {
            // 온도
            View vTemp = savedInlineWeatherView.findViewWithTag("wx_temp");
            if (vTemp instanceof TextView) ((TextView) vTemp).setText(temp + "°");

            // 날씨 아이콘 - skyRaw 직접 전달
            String icon = resolveWeatherIcon(pty, skyRaw);
            String stateText = "대전 유성구 지족동";

            View vIcon = savedInlineWeatherView.findViewWithTag("wx_icon");
            if (vIcon instanceof TextView) ((TextView) vIcon).setText(icon);

            // "맑음 · 대전 유성구 지족동" 한 줄
            View vState = savedInlineWeatherView.findViewWithTag("wx_state");
            if (vState instanceof TextView)
                ((TextView) vState).setText("대전 유성구 지족동");

            // 기준 시각 표시 (날씨 API 시간 → "03.17.(화) 01:30 현재" 형식)
            View vTimeDisp = savedInlineWeatherView.findViewWithTag("wx_time_disp");
            if (vTimeDisp instanceof TextView) {
                String rawTime = p2.getString("wx_time", "");
                String fmtTime = "";
                if (!rawTime.isEmpty()) {
                    try {
                        // rawTime 형식: "2026/03/17 01:30 기준"
                        java.text.SimpleDateFormat inFmt =
                                new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.KOREA);
                        java.util.Date dt = inFmt.parse(rawTime.replace(" 기준","").replace("(캐시)","").trim());
                        java.text.SimpleDateFormat outFmt =
                                new java.text.SimpleDateFormat("MM.dd.(E) HH:mm", java.util.Locale.KOREA);
                        fmtTime = outFmt.format(dt) + " 현재";
                    } catch (Exception ignored) {
                        fmtTime = rawTime;
                    }
                }
                ((TextView) vTimeDisp).setText(fmtTime);
            }

            // 미세먼지 뱃지 갱신
            View vBadges = savedInlineWeatherView.findViewWithTag("wx_badges");
            if (vBadges instanceof LinearLayout) {
                LinearLayout badgeRow = (LinearLayout) vBadges;
                badgeRow.removeAllViews();
                String[] gradeColors = {"#AAAAAA","#4FC3F7","#66BB6A","#FFA726","#EF5350"};
                String[] gradeNames  = {"-","좋음","보통","나쁨","매우나쁨"};
                int g25 = 0;
                try { g25 = Integer.parseInt(pm25Grade); } catch (Exception ignored) {}
                if (g25 < 0 || g25 >= gradeColors.length) g25 = 0;
                badgeRow.addView(makeDustBadge("초미세먼지 (" + gradeNames[g25] + ")", gradeColors[g25]));
                int g10 = 0;
                try { g10 = Integer.parseInt(pm10Grade); } catch (Exception ignored) {}
                if (g10 < 0 || g10 >= gradeColors.length) g10 = 0;
                badgeRow.addView(makeDustBadge("미세먼지 (" + gradeNames[g10] + ")", gradeColors[g10]));
                // 오존
                String o3g2 = p2.getString("wx_o3g", "0");
            }

            // 뒷면 상세값 업데이트
            View vHum = savedInlineWeatherView.findViewWithTag("wx_b_hum");
            if (vHum instanceof TextView) ((TextView) vHum).setText(hum + "%");
            View vWsd = savedInlineWeatherView.findViewWithTag("wx_b_wsd");
            if (vWsd instanceof TextView) ((TextView) vWsd).setText(wsd + "㎧");
            View vRain = savedInlineWeatherView.findViewWithTag("wx_b_rain");
            if (vRain instanceof TextView) ((TextView) vRain).setText(
                    "0".equals(rain) ? "없음" : rain + "mm");
            View vSun = savedInlineWeatherView.findViewWithTag("wx_b_sunrise");
            if (vSun instanceof TextView) ((TextView) vSun).setText(sunrise);
            View vSet = savedInlineWeatherView.findViewWithTag("wx_b_sunset");
            if (vSet instanceof TextView) ((TextView) vSet).setText(sunset);
        });
    }

    private void loadWeatherInline() {
        android.content.SharedPreferences p = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String cTemp = p.getString("wx_temp", null);

        // ── 케이스 1: 같은 세션 중 화면 복귀 → 캐시 즉시 표시, API 재호출 없음
        if (weatherLoadedThisSession && cTemp != null) {
            updateInlineWeatherView(
                    cTemp,
                    p.getString("wx_pty",  "없음"),
                    p.getString("wx_sky",  ""),
                    p.getString("wx_pm25val", "-"),
                    p.getString("wx_pm10val", "-"),
                    p.getString("wx_pm25g",   "0"),
                    p.getString("wx_pm10g",   "0"));
            isWeatherLoading = false;
            return;
        }

        // ── 케이스 2: 재시작/재설치 후 저장된 캐시가 있으면 즉시 표시 + 백그라운드 API 갱신
        if (cTemp != null) {
            // 저장된 캐시를 먼저 즉시 표시 (사용자가 바로 날씨 확인 가능)
            updateInlineWeatherView(
                    cTemp,
                    p.getString("wx_pty",  "없음"),
                    p.getString("wx_sky",  ""),
                    p.getString("wx_pm25val", "-"),
                    p.getString("wx_pm10val", "-"),
                    p.getString("wx_pm25g",   "0"),
                    p.getString("wx_pm10g",   "0"));
            // 동시에 백그라운드에서 최신 API 호출 → 완료되면 부드럽게 교체
            LinearLayout dummyCard = new LinearLayout(this);
            TextView dummyLoading = new TextView(this);
            loadWeatherData(dummyCard, dummyLoading, () -> {
                android.content.SharedPreferences p2 = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                updateInlineWeatherView(
                        p2.getString("wx_temp", cTemp),
                        p2.getString("wx_pty",  "없음"),
                        p2.getString("wx_sky",  ""),
                        p2.getString("wx_pm25val", "-"),
                        p2.getString("wx_pm10val", "-"),
                        p2.getString("wx_pm25g",   "0"),
                        p2.getString("wx_pm10g",   "0"));
                isWeatherLoading = false;
            });
            return;
        }

        // ── 케이스 3: 캐시 전혀 없음 (최초 설치) → "불러오는 중..." 표시 후 API 완료시 표시
        LinearLayout dummyCard = new LinearLayout(this);
        TextView dummyLoading = new TextView(this);
        loadWeatherData(dummyCard, dummyLoading, () -> {
            android.content.SharedPreferences p2 = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            updateInlineWeatherView(
                    p2.getString("wx_temp", "--"),
                    p2.getString("wx_pty",  "없음"),
                    p2.getString("wx_sky",  ""),
                    p2.getString("wx_pm25val", "-"),
                    p2.getString("wx_pm10val", "-"),
                    p2.getString("wx_pm25g",   "0"),
                    p2.getString("wx_pm10g",   "0"));
            isWeatherLoading = false;
        });
    }

    // ── 최근 거래 ticker 슬라이딩 업데이트 ────────────────
    // ── 오늘 거래 텍스트에서 입금/출금 여부 파악 (간단히 블록에서 추출)
    private boolean isDepositBlock(String block) {
        // 기상청 SMS 패턴: "입금" 포함이면 입금, "출금" 포함이면 출금
        return block.contains("입금");
    }

    private void updateTickerDot(String dotColor) {
        if (tickerFrame == null) return;
        View v = tickerFrame.findViewWithTag("ticker_dot");
        if (v instanceof android.widget.ImageView) {
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            try { d.setColor(Color.parseColor(dotColor)); }
            catch (Exception e) { d.setColor(Color.parseColor("#2ECC71")); }
            v.setBackground(d);
        }
    }

    // tickerItems 항목별 도트 색상 (입금=초록, 출금=빨강, 없음=회색)
    private java.util.List<String> tickerDotColors = new java.util.ArrayList<>();

    private void updateTickerNotices(String[][] latest, String[] names) {
        if (tickerFrame == null || tvRecentNotice == null) return;
        if (tickerRunnable != null) {
            tickerHandler.removeCallbacks(tickerRunnable);
            tickerRunnable = null;
        }
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA);
        String today = sdf.format(new java.util.Date());
        tickerItems.clear();
        tickerDotColors.clear();

        // 캐시 블록에서 오늘 거래 추출 (입금/출금 구분)
        if (cachedBlocks != null) {
            String[] acctKeys = {"5510-13","5510-83","5510-53","5510-23"};
            for (int i = 0; i < 4; i++) {
                // 해당 계좌의 오늘 거래 블록 찾기
                for (String block : cachedBlocks) {
                    if (!block.contains(acctKeys[i])) continue;
                    // 날짜 확인
                    java.util.regex.Matcher tm = java.util.regex.Pattern
                            .compile("(\\d{4}-\\d{2}-\\d{2})").matcher(block);
                    if (!tm.find() || !tm.group(1).equals(today)) continue;
                    // 금액
                    java.util.regex.Matcher am = java.util.regex.Pattern
                            .compile("([입출]금)([\\d,]+)원").matcher(block);
                    if (!am.find()) continue;
                    String type   = am.group(1);   // 입금 or 출금
                    String amount = am.group(2);   // 숫자,
                    String sign   = type.equals("입금") ? "+" : "-";
                    String dotCol = type.equals("입금") ? "#2ECC71" : "#E74C3C";
                    tickerItems.add("오늘 " + names[i] + " " + type + " " + sign + amount + "원");
                    tickerDotColors.add(dotCol);
                    break; // 계좌당 최신 1건만
                }
            }
        }

        if (tickerItems.isEmpty()) {
            tvRecentNotice.setText("최근 거래 내역이 없습니다");
            tvRecentNotice.setAlpha(1f);
            tvRecentNotice.setTranslationY(0f);
            updateTickerDot("#888888");
            return;
        }
        // 첫 항목 표시
        tvRecentNotice.setText(tickerItems.get(0));
        tvRecentNotice.setAlpha(1f);
        tvRecentNotice.setTranslationY(0f);
        if (!tickerDotColors.isEmpty()) updateTickerDot(tickerDotColors.get(0));
        if (tickerItems.size() == 1) return;

        // 2건 이상 → 슬라이딩
        final int[] curIdx = {0};
        int tickerH = tickerFrame.getLayoutParams() != null
                ? tickerFrame.getLayoutParams().height : dpToPx(28);
        final int slideH = tickerH;
        tickerRunnable = new Runnable() {
            @Override public void run() {
                if (tickerFrame == null || tvRecentNotice == null) return;
                int nextIdx = (curIdx[0] + 1) % tickerItems.size();
                android.animation.ObjectAnimator outAnim = android.animation.ObjectAnimator
                        .ofFloat(tvRecentNotice, "translationY", 0f, -slideH);
                outAnim.setDuration(280);
                outAnim.setInterpolator(new android.view.animation.AccelerateInterpolator());
                outAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        curIdx[0] = nextIdx;
                        if (tickerItems.isEmpty() || nextIdx >= tickerItems.size()) return;
                        tvRecentNotice.setText(tickerItems.get(nextIdx));
                        tvRecentNotice.setTranslationY(slideH);
                        if (nextIdx < tickerDotColors.size())
                            updateTickerDot(tickerDotColors.get(nextIdx));
                        android.animation.ObjectAnimator inAnim = android.animation.ObjectAnimator
                                .ofFloat(tvRecentNotice, "translationY", slideH, 0f);
                        inAnim.setDuration(280);
                        inAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
                        inAnim.start();
                    }
                });
                outAnim.start();
                tickerHandler.postDelayed(this, 3000);
            }
        };
        tickerHandler.postDelayed(tickerRunnable, 3000);
    }

    private android.view.View makeDivider(int verticalMargin) {
        android.view.View div = new android.view.View(this);
        div.setBackgroundColor(Color.parseColor("#F0EEF8"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, verticalMargin, 0, verticalMargin);
        div.setLayoutParams(lp);
        return div;
    }


    private void showSplashScreen() {
        RelativeLayout splash = new RelativeLayout(this);
        splash.setBackgroundColor(Color.parseColor("#EEE8F5"));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        RelativeLayout.LayoutParams centerLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        center.setLayoutParams(centerLp);

        // 앱 아이콘 (런처 아이콘)
        android.widget.ImageView ivIcon = new android.widget.ImageView(this);
        ivIcon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(88), dpToPx(88));
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.setMargins(0, 0, 0, dpToPx(20));
        ivIcon.setLayoutParams(iconLp);
        ivIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        center.addView(ivIcon);

        // 앱 이름
        TextView tvName = new TextView(this);
        tvName.setText("네이처뷰 경로당");
        tvName.setTextColor(Color.parseColor("#6C5CE7"));
        tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.setMargins(0, 0, 0, dpToPx(8));
        tvName.setLayoutParams(nameLp);
        center.addView(tvName);

        // 버전
        TextView tvVer = new TextView(this);
        tvVer.setText("v" + getMyVersion());
        tvVer.setTextColor(Color.parseColor("#A89CD0"));
        tvVer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        tvVer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.setMargins(0, 0, 0, dpToPx(48));
        tvVer.setLayoutParams(verLp);
        center.addView(tvVer);

        // 로딩 인디케이터
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pb.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#6C5CE7")));
        }
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40));
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        pb.setLayoutParams(pbLp);
        center.addView(pb);

        // 로딩 문구
        TextView tvLoading = new TextView(this);
        splashLoadingTv = tvLoading;
        tvLoading.setText("로그인 중...");
        tvLoading.setTextColor(Color.parseColor("#A89CD0"));
        tvLoading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        tvLoading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        loadLp.setMargins(0, dpToPx(12), 0, 0);
        tvLoading.setLayoutParams(loadLp);
        center.addView(tvLoading);

        // 다운로드 프로그레스 영역 (초기 숨김)
        LinearLayout progressArea = new LinearLayout(this);
        progressArea.setOrientation(LinearLayout.VERTICAL);
        progressArea.setGravity(Gravity.CENTER);
        progressArea.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        paLp.setMargins(dpToPx(40), dpToPx(16), dpToPx(40), 0);
        progressArea.setLayoutParams(paLp);

        // 프로그레스바
        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progressBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#6C5CE7")));
            progressBar.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E0D9F5")));
        }
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(8)));
        progressArea.addView(progressBar);
        splashProgressBar = progressBar;

        // 퍼센트 텍스트
        TextView tvPercent = new TextView(this);
        tvPercent.setText("0%");
        tvPercent.setTextColor(Color.parseColor("#A89CD0"));
        tvPercent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        tvPercent.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pctLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pctLp.setMargins(0, dpToPx(6), 0, 0);
        tvPercent.setLayoutParams(pctLp);
        progressArea.addView(tvPercent);
        splashProgressTv = tvPercent;

        center.addView(progressArea);
        splashProgressArea = progressArea;

        splash.addView(center);
        setContentView(splash);
    }

    private String getMyVersion() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { return "1.0"; }
    }

    private void showSplashProgress() {
        if (splashProgressArea != null)
            splashProgressArea.setVisibility(android.view.View.VISIBLE);
        if (splashProgressBar != null) splashProgressBar.setProgress(0);
        if (splashProgressTv  != null) splashProgressTv.setText("0%");
    }

    private void updateSplashProgress(int pct) {
        if (splashProgressBar != null) splashProgressBar.setProgress(pct);
        if (splashProgressTv  != null) splashProgressTv.setText(pct + "%");
    }

    private void hideSplashProgress() {
        if (splashProgressArea != null)
            splashProgressArea.setVisibility(android.view.View.GONE);
    }

    private void checkVersionThenShowMenu() {
        if (busDbNeedsUpdate()) {
            if (splashLoadingTv != null) splashLoadingTv.setText("버스 노선 데이터 다운로드 중...");
            showSplashProgress();
            downloadBusRouteDb(() -> {
                hideSplashProgress();
                loadBusDbToMemory();
                loadStopDbFromDriveIfNeeded(() -> doCheckVersionThenShowMenu());
            }, pct -> updateSplashProgress(pct));
        } else {
            loadBusDbToMemory();
            loadStopDbFromDriveIfNeeded(() -> doCheckVersionThenShowMenu());
        }
    }

    /** Drive에서 dj_stops.json 다운로드 (없으면 패스) */
    private void loadStopDbFromDriveIfNeeded(Runnable onDone) {
        android.content.SharedPreferences p = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE);
        // 내부 파일 우선 확인
        String internalStop = loadStopDb();
        if (!internalStop.isEmpty() && internalStop.contains("\"routes\":")) {
            if (stopDbList == null || nodeNoToRoutes.isEmpty()) loadStopJsonToMemory(internalStop);
            if (onDone != null) onDone.run();
            return;
        }

        // 배차시간표 로드 - 내부 파일 우선, 없으면 SharedPreferences, 없으면 Drive
        if (busTimesMap.isEmpty()) {
            String btCached = loadBusTimes(); // 내부 파일에서 읽기
            if (btCached.isEmpty()) btCached = p.getString("bustimes_txt_cache", "");
            // 유효성 검사: v4 형식 확인 (|| 구분자 포함 여부)
            boolean cacheValid = !btCached.isEmpty() && btCached.contains("||");
            if (cacheValid) {
                loadBusTimesFromJson(btCached);
            } else {
                // 구버전 캐시 삭제 후 Drive에서 새로 받기
                p.edit().remove("bustimes_txt_cache").apply();
                saveBusTimes(""); // 내부 파일도 초기화
                new Thread(() -> {
                    try {
                        DriveReadHelper dr = new DriveReadHelper(this);
                        dr.readFile(BUS_TIME_FILE, new DriveReadHelper.ReadCallback() {
                            @Override public void onSuccess(String txt) {
                                if (!txt.isEmpty()) {
                                    p.edit().putString("bustimes_txt_cache", txt).apply();
                                    saveBusTimes(txt); // 내부 파일에도 저장
                                    loadBusTimesFromJson(txt);
                                }
                            }
                            @Override public void onFailure(String e) {}
                        });
                    } catch (Exception ignored) {}
                }).start();
            }
        }

        String cached = p.getString("stop_json_cache", "");
        // routes 필드가 있는 새 버전 캐시인지 확인
        boolean isNewVersion = cached.contains("\"routes\":");
        if (!cached.isEmpty() && isNewVersion) {
            if (stopDbList == null || nodeNoToRoutes.isEmpty()) loadStopJsonToMemory(cached);
            if (onDone != null) onDone.run();
            return;
        }
        // 캐시 없거나 구버전(routes 없음) → Drive에서 새로 다운로드
        if (splashLoadingTv != null) splashLoadingTv.setText("정류장 데이터 로딩 중...");
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(STOP_DB_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    if (!content.isEmpty()) {
                        p.edit().putString("stop_json_cache", content).apply();
                        saveStopDb(content); // 내부 파일에도 저장
                        loadStopJsonToMemory(content);
                    }
                    if (onDone != null) runOnUiThread(onDone);
                }
                @Override public void onFailure(String error) {
                    if (onDone != null) runOnUiThread(onDone);
                }
            });
        } catch (Exception e) {
            if (onDone != null) onDone.run();
        }
    }

    private void doCheckVersionThenShowMenu() {
        String myVersion = getMyVersion();
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(VERSION_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    String driveVersion = content.trim().split("\\r?\\n")[0].trim();
                    // 위젯에서 버전 비교할 수 있도록 prefs에 저장
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putString("drive_version", driveVersion)
                            .apply();
                    boolean needUpdate  = !driveVersion.equals(myVersion);
                    if (needUpdate) {
                        // 버전 불일치 → 전체화면 차단
                        runOnUiThread(() -> showUpdateRequiredScreen(driveVersion));
                    } else {
                        runOnUiThread(() -> userMenuBuilder.build(false));
                    }
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() -> userMenuBuilder.build(false));
                }
            });
        } catch (Exception e) {
            userMenuBuilder.build(false);
        }
    }

    /** 버전 불일치 시 전체화면 업데이트 강제 차단 */
    private void showUpdateRequiredScreen(String newVersion) {
        // 위젯 즉시 강제 갱신 (잔액 숨기고 업데이트 경고 표시)
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putString("drive_version", newVersion)
                .apply();
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        int[] ids = awm.getAppWidgetIds(
                new ComponentName(this, BalanceWidget.class));
        for (int id : ids) BalanceWidget.updateWidget(this, awm, id);

        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(Color.parseColor("#1A0A2E"));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dpToPx(40), 0, dpToPx(40), 0);
        RelativeLayout.LayoutParams centerLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        center.setLayoutParams(centerLp);

        // 아이콘 (노란 원 + 느낌표)
        android.widget.FrameLayout iconFrame = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams iconFrameLp = new LinearLayout.LayoutParams(
                dpToPx(80), dpToPx(80));
        iconFrameLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconFrameLp.setMargins(0, 0, 0, dpToPx(24));
        iconFrame.setLayoutParams(iconFrameLp);

        android.graphics.drawable.GradientDrawable iconBg =
                new android.graphics.drawable.GradientDrawable();
        iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        iconBg.setColor(Color.parseColor("#F39C12"));
        iconFrame.setBackground(iconBg);

        TextView tvIcon = new TextView(this);
        tvIcon.setText("!");
        tvIcon.setTextColor(Color.parseColor("#1A0A2E"));
        tvIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 44);
        tvIcon.setTypeface(null, Typeface.BOLD);
        tvIcon.setGravity(Gravity.CENTER);
        android.widget.FrameLayout.LayoutParams iconTvLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        tvIcon.setLayoutParams(iconTvLp);
        iconFrame.addView(tvIcon);
        center.addView(iconFrame);

        // 앱 이름
        TextView tvAppName = new TextView(this);
        tvAppName.setText("네이처뷰 경로당");
        tvAppName.setTextColor(Color.WHITE);
        tvAppName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22);
        tvAppName.setTypeface(null, Typeface.BOLD);
        tvAppName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.setMargins(0, 0, 0, dpToPx(16));
        tvAppName.setLayoutParams(nameLp);
        center.addView(tvAppName);

        // 안내 문구
        TextView tvMsg = new TextView(this);
        tvMsg.setText("새로운 버전이 출시되었습니다.\n앱을 계속 사용하려면\n업데이트가 필요합니다.");
        tvMsg.setTextColor(Color.parseColor("#CCCCCC"));
        tvMsg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        tvMsg.setGravity(Gravity.CENTER);
        tvMsg.setLineSpacing(dpToPx(4), 1f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, 0, 0, dpToPx(12));
        tvMsg.setLayoutParams(msgLp);
        center.addView(tvMsg);

        // 버전 정보
        TextView tvVer = new TextView(this);
        tvVer.setText("현재 v" + getMyVersion() + "  →  최신 v" + newVersion);
        tvVer.setTextColor(Color.parseColor("#E74C3C"));
        tvVer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        tvVer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.setMargins(0, 0, 0, dpToPx(40));
        tvVer.setLayoutParams(verLp);
        center.addView(tvVer);

        // 업데이트 버튼
        Button btnUpdate = new Button(this);
        btnUpdate.setText("⬆  Play Store에서 업데이트");
        btnUpdate.setBackground(makeShadowCardDrawable("#E74C3C", 16, 8));
        btnUpdate.setTextColor(Color.WHITE);
        btnUpdate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        btnUpdate.setTypeface(null, Typeface.BOLD);
        btnUpdate.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(56));
        btnUpdate.setLayoutParams(btnLp);
        btnUpdate.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
            }
        });
        center.addView(btnUpdate);

        root.addView(center);
        setContentView(root);
    }

    // ═══════════════════════════════════════════════════════
    //  차단 확인
    // ═══════════════════════════════════════════════════════
    private void checkBlockedThenStart() {
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(USERS_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    String status = getUserStatus(content, currentUserEmail);
                    runOnUiThread(() -> {
                        if ("차단".equals(status)) showBlockedScreen();
                        else checkVersionThenShowMenu();
                    });
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() -> checkVersionThenShowMenu());
                }
            });
        } catch (Exception e) {
            checkVersionThenShowMenu();
        }
    }

    private String getUserStatus(String content, String email) {
        for (String line : content.split("\\r?\\n")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 2 &&
                    parts[0].trim().equalsIgnoreCase(email)) {
                return parts[1].trim();
            }
        }
        return "";
    }

    private void showBlockedScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#EEE8F5"));
        layout.setPadding(80, 80, 80, 80);

        TextView tv = new TextView(this);
        tv.setText("차단된 사용자입니다.\n관리자에게 문의하세요.");
        tv.setTextColor(Color.parseColor("#C0392B"));
        tv.setTextSize(18);
        tv.setGravity(Gravity.CENTER);
        layout.addView(tv);
        setContentView(layout);
    }

    // ── FCM 토큰 Drive 등록 ─────────────────────────────────
    private void uploadFcmTokenIfNeeded() {
        // 앱 실행마다 Firebase에서 최신 토큰을 가져와서 Drive에 등록
        // (dirty 플래그 무시 - 토큰 누락 방지)
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    prefs.edit()
                            .putString("fcm_token", token)
                            .putBoolean("fcm_token_dirty", false)
                            .apply();
                    android.util.Log.d("FCM", "토큰 갱신 → Drive 등록: " + token.substring(0, 10) + "...");
                    saveFcmTokenToDrive(token);
                })
                .addOnFailureListener(e -> {
                    android.util.Log.e("FCM", "토큰 가져오기 실패: " + e.getMessage());
                    // Firebase 실패 시 로컬 저장 토큰으로 재시도
                    String localToken = prefs.getString("fcm_token", "");
                    if (!localToken.isEmpty()) saveFcmTokenToDrive(localToken);
                });
    }

    private void saveFcmTokenToDrive(String token) {
        if (token == null || token.isEmpty()) return;
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(this);
                reader.readFile(FCM_TOKENS_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        // 이미 같은 이메일+토큰이 있으면 스킵
                        String newLine = currentUserEmail + "|" + token;
                        boolean found = false;
                        StringBuilder sb = new StringBuilder();
                        for (String line : existing.split("\r?\n")) {
                            if (line.trim().isEmpty()) continue;
                            // 같은 이메일이면 토큰 갱신
                            if (line.toLowerCase().startsWith(currentUserEmail.toLowerCase())) {
                                sb.append(newLine).append("\n");
                                found = true;
                            } else {
                                sb.append(line.trim()).append("\n");
                            }
                        }
                        if (!found) sb.append(newLine).append("\n");
                        uploadFcmTokensFile(sb.toString().trim());
                    }
                    @Override public void onFailure(String error) {
                        // 파일 없으면 새로 생성
                        uploadFcmTokensFile(currentUserEmail + "|" + token);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("FCM", "토큰 Drive 읽기 오류: " + e.getMessage());
            }
        }).start();
    }

    private void uploadFcmTokensFile(String content2) {
        new Thread(() -> {
            try {
                DriveUploadHelper up = new DriveUploadHelper(this);
                up.uploadFileSync(content2, FCM_TOKENS_FILE);
                android.util.Log.d("FCM", "토큰 Drive 저장 완료");
            } catch (Exception e) {
                android.util.Log.e("FCM", "토큰 Drive 저장 실패: " + e.getMessage());
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    private void updateLastAccessTime() {
        if (currentUserEmail.isEmpty()) return;
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(this);
                reader.readFile(USERS_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String content) {
                        String nowTime = new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA)
                                .format(new java.util.Date());
                        StringBuilder sb = new StringBuilder();
                        boolean found = false;
                        for (String line : content.split("\r?\n")) {
                            if (line.trim().isEmpty()) continue;
                            if (line.toLowerCase().startsWith(currentUserEmail.toLowerCase())) {
                                String[] parts = line.split("\\|");
                                String email  = parts[0].trim();
                                String status = parts.length > 1 ? parts[1].trim() : "허용";
                                String myVer = getMyVersion();
                                sb.append(email).append("|").append(status).append("|").append(nowTime).append("|").append(myVer).append("\n");
                                found = true;
                            } else {
                                sb.append(line.trim()).append("\n");
                            }
                        }
                        if (!found) return; // registerUser가 처리
                        try {
                            DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                            up.uploadFileSync(sb.toString().trim(), USERS_FILE);
                        } catch (Exception ignored) {}
                    }
                    @Override public void onFailure(String error) {}
                });
            } catch (Exception e) {
                android.util.Log.e("Access", "접속 시간 업데이트 실패: " + e.getMessage());
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    private void registerUser() {
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(USERS_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    boolean found = false;
                    for (String line : content.split("\\r?\\n")) {
                        if (line.toLowerCase().startsWith(
                                currentUserEmail.toLowerCase())) {
                            found = true; break;
                        }
                    }
                    String nowTime = new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA)
                            .format(new java.util.Date());
                    final String newContent = found ? content
                            : content.trim() + "\n" + currentUserEmail + "|허용|" + nowTime;
                    try {
                        DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                        new Thread(() -> {
                            try {
                                up.uploadFileSync(newContent, USERS_FILE);
                                runOnUiThread(() -> checkVersionThenShowMenu());
                            } catch (Exception ex) {
                                runOnUiThread(() -> checkVersionThenShowMenu());
                            }
                        }).start();
                    } catch (Exception e) {
                        runOnUiThread(() -> checkVersionThenShowMenu());
                    }
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() -> checkVersionThenShowMenu());
                }
            });
        } catch (Exception e) {
            checkVersionThenShowMenu();
        }
    }

    //  관리자 메뉴
    // ═══════════════════════════════════════════════════════
    void buildOwnerMenuInternal() {
        // 상태바 흰색 복원
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        isOnMenuScreen    = true;
        isOnBalanceScreen = false;
        stopAutoRefresh();
        tvRecentNotice = null;  // 관리자 메뉴 재진입 시 초기화

        // ── 색상 상수 (일반사용자 메뉴와 동일) ───────────────────
        final String BG      = "#F5F3FA";
        final String PURPLE  = "#6C5CE7";
        final String TEXT1   = "#1A1A2E";

        // ── layout: 헤더 포함 전체 콘텐츠를 ScrollView 안에서 함께 스크롤
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        layout.setBackgroundColor(Color.parseColor(BG));
        layout.setPadding(0, 0, 0, dpToPx(40));

        LinearLayout headerBg = new LinearLayout(this);
        headerBg.setOrientation(LinearLayout.VERTICAL);
        headerBg.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable hGrad =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Color.parseColor("#7C6FE0"), Color.parseColor("#9B8FF5")});
        hGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(28),dpToPx(28),dpToPx(28),dpToPx(28)});
        headerBg.setBackground(hGrad);
        headerBg.setPadding(dpToPx(24), dpToPx(10), dpToPx(24), dpToPx(14));
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerBg.setLayoutParams(hLp);



        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER);
        titleRow.setPadding(0, 0, 0, dpToPx(4));

        android.widget.ImageView ivIcon = new android.widget.ImageView(this);
        ivIcon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams ownerIconLp = new LinearLayout.LayoutParams(
                dpToPx(26), dpToPx(26));
        ownerIconLp.setMargins(0, 0, dpToPx(8), 0);
        ivIcon.setLayoutParams(ownerIconLp);
        ivIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        titleRow.addView(ivIcon);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("네이처뷰 경로당");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(26);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setShadowLayer(6f, 0f, 2f, 0x40000000);
        titleRow.addView(tvTitle);
        headerBg.addView(titleRow);

        View hDiv = new View(this);
        hDiv.setBackgroundColor(Color.parseColor("#40FFFFFF"));
        LinearLayout.LayoutParams hDivLp = new LinearLayout.LayoutParams(dpToPx(60), dpToPx(2));
        hDivLp.gravity = Gravity.CENTER_HORIZONTAL;
        hDivLp.setMargins(0, dpToPx(2), 0, dpToPx(8));
        hDiv.setLayoutParams(hDivLp);
        headerBg.addView(hDiv);

        // ── 헤더 하단: 관리자 접속(좌) + 접근성 상태(우) ──────
        LinearLayout descRow = new LinearLayout(this);
        descRow.setOrientation(LinearLayout.HORIZONTAL);
        descRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams descRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descRow.setLayoutParams(descRowLp);

        // 왼쪽: 녹색 동그라미 + 관리자 접속
        LinearLayout leftDesc = new LinearLayout(this);
        leftDesc.setOrientation(LinearLayout.HORIZONTAL);
        leftDesc.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftDesc.setLayoutParams(leftLp);

        // 녹색 동그라미
        android.widget.TextView tvDot = new android.widget.TextView(this);
        tvDot.setText("●");
        tvDot.setTextColor(Color.parseColor("#2ECC71"));
        tvDot.setTextSize(10);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dotLp.setMargins(0, 0, dpToPx(5), 0);
        tvDot.setLayoutParams(dotLp);
        leftDesc.addView(tvDot);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("관리자");
        tvDesc.setTextColor(Color.WHITE);
        tvDesc.setTextSize(13);
        leftDesc.addView(tvDesc);
        descRow.addView(leftDesc);

        // 가운데: 배터리 최적화 상태
        boolean batteryOn = isBatteryOptimizationExempt();
        LinearLayout midDesc = new LinearLayout(this);
        midDesc.setOrientation(LinearLayout.HORIZONTAL);
        midDesc.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        midDesc.setLayoutParams(midLp);

        android.graphics.drawable.GradientDrawable batBadgeBg = new android.graphics.drawable.GradientDrawable();
        batBadgeBg.setColor(Color.parseColor(batteryOn ? "#2ECC71" : "#E74C3C"));
        batBadgeBg.setCornerRadius(dpToPx(10));
        TextView tvBatBadge = new TextView(this);
        tvBatBadge.setText(batteryOn ? "● 최적화 ON" : "● 최적화 OFF");
        tvBatBadge.setTextColor(Color.WHITE);
        tvBatBadge.setTextSize(11);
        tvBatBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        tvBatBadge.setBackground(batBadgeBg);
        tvBatBadge.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));
        if (!batteryOn) {
            tvBatBadge.setOnClickListener(vv -> {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            });
        }
        midDesc.addView(tvBatBadge);
        descRow.addView(midDesc);

        // 오른쪽: 접근성 상태
        boolean accOn = isAccessibilityEnabled();
        LinearLayout rightDesc = new LinearLayout(this);
        rightDesc.setOrientation(LinearLayout.HORIZONTAL);
        rightDesc.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rightDesc.setLayoutParams(rightLp);

        android.graphics.drawable.GradientDrawable accBadgeBg = new android.graphics.drawable.GradientDrawable();
        accBadgeBg.setColor(Color.parseColor(accOn ? "#2ECC71" : "#E74C3C"));
        accBadgeBg.setCornerRadius(dpToPx(10));
        TextView tvAccBadge = new TextView(this);
        tvAccBadge.setText(accOn ? "● 접근성 ON" : "● 접근성 OFF");
        tvAccBadge.setTextColor(Color.WHITE);
        tvAccBadge.setTextSize(11);
        tvAccBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAccBadge.setBackground(accBadgeBg);
        tvAccBadge.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));
        if (!accOn) {
            tvAccBadge.setOnClickListener(vv -> startActivity(new Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }
        rightDesc.addView(tvAccBadge);
        descRow.addView(rightDesc);

        headerBg.addView(descRow);
        layout.addView(headerBg);

        // ── 잔액 카드 그리드 (헤더 바로 아래) ─────────────────────
        menuBalTv = new TextView[4];
        for (int i = 0; i < 4; i++) menuBalTv[i] = new TextView(this);
        if (cachedBalValues != null) {
            applyBalanceCache();
        } else if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
            updateBalanceValues(cachedBlocks);
        }

        String[][] ownerBalInfo = {
                {"5510-13","운영비","#4A90D9","#EBF4FF"},
                {"5510-83","부식비","#27AE60","#EAFAF1"},
                {"5510-53","냉난방비","#E67E22","#FEF9E7"},
                {"5510-23","회비","#8E44AD","#F5EEF8"}
        };

        LinearLayout ownerBalGrid = new LinearLayout(this);
        ownerBalGrid.setOrientation(LinearLayout.VERTICAL);
        ownerBalGrid.setClipChildren(false);
        ownerBalGrid.setClipToPadding(false);
        LinearLayout.LayoutParams ownerGridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ownerGridLp.setMargins(dpToPx(12), dpToPx(10), dpToPx(12), 0);
        ownerBalGrid.setLayoutParams(ownerGridLp);

        for (int row = 0; row < 2; row++) {
            LinearLayout ownerBalRow = new LinearLayout(this);
            ownerBalRow.setOrientation(LinearLayout.HORIZONTAL);
            ownerBalRow.setClipChildren(false);
            ownerBalRow.setClipToPadding(false);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dpToPx(6));
            ownerBalRow.setLayoutParams(rowLp);

            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                card.setBackground(makeShadowCardDrawable(ownerBalInfo[idx][3], 14, 4));
                card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                card.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                cp.setMargins(0, 0, col == 0 ? dpToPx(6) : 0, 0);
                card.setLayoutParams(cp);

                TextView tvLabel = new TextView(this);
                tvLabel.setText("● " + ownerBalInfo[idx][1]);
                tvLabel.setTextColor(Color.parseColor(ownerBalInfo[idx][2]));
                tvLabel.setTextSize(14);
                tvLabel.setTypeface(null, Typeface.BOLD);
                tvLabel.setGravity(Gravity.CENTER);
                card.addView(tvLabel);

                menuBalTv[idx].setText("-");
                menuBalTv[idx].setTextColor(Color.parseColor("#1A1A2E"));
                menuBalTv[idx].setTextSize(15);
                menuBalTv[idx].setTypeface(null, Typeface.BOLD);
                menuBalTv[idx].setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                valLp.setMargins(0, dpToPx(2), 0, 0);
                menuBalTv[idx].setLayoutParams(valLp);
                card.addView(menuBalTv[idx]);

                final String filterKey = ownerBalInfo[idx][0];
                card.setOnClickListener(v -> {
                    currentTabFilter = filterKey;
                    showBalanceScreen();
                });

                ownerBalRow.addView(card);
            }
            ownerBalGrid.addView(ownerBalRow);
        }
        layout.addView(ownerBalGrid);

        // 최근 거래 내역 안내 텍스트
        tvRecentNotice = new TextView(this);
        tvRecentNotice.setText("최근 거래 내역이 없습니다");
        tvRecentNotice.setTextColor(Color.parseColor("#888888"));
        tvRecentNotice.setTextSize(12);
        tvRecentNotice.setGravity(Gravity.CENTER);
        tvRecentNotice.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams ownerNoticeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ownerNoticeLp.setMargins(dpToPx(16), dpToPx(2), dpToPx(16), dpToPx(4));
        tvRecentNotice.setLayoutParams(ownerNoticeLp);
        layout.addView(tvRecentNotice);

        // 잔액 로드
        if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
            updateMenuBalCards(cachedBlocks);
        } else {
            try {
                readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fileContent) {
                        runOnUiThread(() -> {
                            String[] blocks2 = fileContent.split("-----------------------------------\r?\n");
                            List<String> all2 = new ArrayList<>();
                            for (String b : blocks2) { if (!b.trim().isEmpty()) all2.add(b); }
                            cachedBlocks = all2;
                            lastKnownBlockCount = all2.size();
                            updateMenuBalCards(cachedBlocks);
                        });
                    }
                    @Override public void onFailure(String error) {
                        runOnUiThread(() -> { if (menuBalTv != null) for (TextView tv : menuBalTv) tv.setText("-"); });
                    }
                });
            } catch (Exception e) {
                if (menuBalTv != null) for (TextView tv : menuBalTv) tv.setText("-");
            }
        }


        // ── 5번: FCM 수신 확인 카드 ──────────────────────────────
        LinearLayout fcmTitleRow = new LinearLayout(this);
        fcmTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        fcmTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams secFcmLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secFcmLp.setMargins(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(6));
        fcmTitleRow.setLayoutParams(secFcmLp);

        LinearLayout secFcmLeft = new LinearLayout(this);
        secFcmLeft.setOrientation(LinearLayout.HORIZONTAL);
        secFcmLeft.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams secFcmLeftLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        secFcmLeft.setLayoutParams(secFcmLeftLp);
        LinearLayout secFcm = makeSectionTitle("FCM 수신 현황", "#27AE60", TEXT1);
        secFcmLeft.addView(secFcm);
        fcmTitleRow.addView(secFcmLeft);

        TextView btnRefreshFcm = new TextView(this);
        btnRefreshFcm.setText("🔄  새로고침");
        btnRefreshFcm.setTextSize(13);
        btnRefreshFcm.setTextColor(Color.parseColor("#C45E00"));
        btnRefreshFcm.setGravity(Gravity.CENTER);
        btnRefreshFcm.setBackground(makeShadowCardDrawable("#FFE0B2", 20, 5));
        btnRefreshFcm.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        btnRefreshFcm.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams rfLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rfLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
        btnRefreshFcm.setLayoutParams(rfLp);
        fcmTitleRow.addView(btnRefreshFcm);
        layout.addView(fcmTitleRow);

        LinearLayout fcmStatusCard = new LinearLayout(this);
        fcmStatusCard.setOrientation(LinearLayout.VERTICAL);
        fcmStatusCard.setBackground(makeShadowCardDrawable("#FFFFFF", 14, 4));
        fcmStatusCard.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        fcmStatusCard.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
        LinearLayout.LayoutParams fscLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fscLp.setMargins(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        fcmStatusCard.setLayoutParams(fscLp);

        TextView tvFcmLoading = new TextView(this);
        tvFcmLoading.setText("📋  사용자별 FCM 토큰 확인 중...");
        tvFcmLoading.setTextColor(Color.parseColor("#888888"));
        tvFcmLoading.setTextSize(13);
        fcmStatusCard.addView(tvFcmLoading);

        LinearLayout fcmListContainer = new LinearLayout(this);
        fcmListContainer.setOrientation(LinearLayout.VERTICAL);
        fcmStatusCard.addView(fcmListContainer);

        layout.addView(fcmStatusCard);

        // FCM 수신 기록 로드 Runnable
        Runnable loadFcmLog = () -> {
            btnRefreshFcm.setEnabled(false);
            btnRefreshFcm.setText("⏳ 로딩중...");
            tvFcmLoading.setText("📋  사용자별 FCM 토큰 확인 중...");
            if (fcmStatusCard.indexOfChild(tvFcmLoading) < 0) fcmStatusCard.addView(tvFcmLoading, 0);
            fcmListContainer.removeAllViews();
            new Thread(() -> {
                try {
                    DriveReadHelper reader2 = new DriveReadHelper(this);
                    reader2.readFile("fcm_received.txt", new DriveReadHelper.ReadCallback() {
                        @Override public void onSuccess(String fcmData) {
                            // users.txt에서 버전 정보 매핑
                            DriveReadHelper usersReader = new DriveReadHelper(PinActivity.this);
                            usersReader.readFile(USERS_FILE, new DriveReadHelper.ReadCallback() {
                                @Override public void onSuccess(String usersData) {
                                    java.util.Map<String,String> versionMap = new java.util.HashMap<>();
                                    java.util.Map<String,String> lastAccessMap = new java.util.HashMap<>();
                                    for (String ul : usersData.split("\\r?\\n")) {
                                        String[] up = ul.split("\\|");
                                        if (up.length > 2) lastAccessMap.put(up[0].trim().toLowerCase(), up[2].trim());
                                        if (up.length > 3) versionMap.put(up[0].trim().toLowerCase(), up[3].trim());
                                    }
                                    runOnUiThread(() -> {
                                        renderFcmList(fcmStatusCard, tvFcmLoading, fcmListContainer, fcmData, versionMap, lastAccessMap);
                                        btnRefreshFcm.setEnabled(true);
                                        btnRefreshFcm.setText("🔄 새로고침");
                                    });
                                }
                                @Override public void onFailure(String e2) {
                                    runOnUiThread(() -> {
                                        renderFcmList(fcmStatusCard, tvFcmLoading, fcmListContainer, fcmData, new java.util.HashMap<>(), new java.util.HashMap<>());
                                        btnRefreshFcm.setEnabled(true);
                                        btnRefreshFcm.setText("🔄 새로고침");
                                    });
                                }
                            });
                        }
                        @Override public void onFailure(String error) {
                            runOnUiThread(() -> {
                                tvFcmLoading.setText("수신 기록 없음");
                                tvFcmLoading.setTextColor(Color.parseColor("#888888"));
                                btnRefreshFcm.setEnabled(true);
                                btnRefreshFcm.setText("🔄 새로고침");
                            });
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvFcmLoading.setText("오류: " + e.getMessage());
                        btnRefreshFcm.setEnabled(true);
                        btnRefreshFcm.setText("🔄 새로고침");
                    });
                }
            }).start();
        }; // end loadFcmLog

        btnRefreshFcm.setOnClickListener(v -> {
            // FCM 수신 기록 새로고침
            loadFcmLog.run();
            // 잔액 동시 새로고침
            cachedBlocks    = null;
            cachedBalValues = null;
            for (int i = 0; i < 4; i++) if (menuBalTv[i] != null) menuBalTv[i].setText("-");
            try {
                readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fileContent) {
                        runOnUiThread(() -> {
                            String[] blocks = fileContent.split("-----------------------------------\r?\n");
                            java.util.List<String> newBlocks = new java.util.ArrayList<>();
                            for (String b : blocks) if (!b.trim().isEmpty()) newBlocks.add(b);
                            cachedBlocks = newBlocks;
                            lastKnownBlockCount = newBlocks.size();
                            updateMenuBalCards(newBlocks);
                        });
                    }
                    @Override public void onFailure(String error) {}
                });
            } catch (Exception ignored) {}
        });
        loadFcmLog.run();

        // ── 선결제 가게 관리 섹션 ─────────────────────────
        LinearLayout.LayoutParams secSlotLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secSlotLp.setMargins(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(6));
        LinearLayout secSlot = makeSectionTitle("선결제 가게 관리", "#27AE60", TEXT1);
        secSlot.setLayoutParams(secSlotLp);
        layout.addView(secSlot);

        LinearLayout slotCard = makeAdminMenuCard("🏪", "선결제 가게 추가/수정",
                "slots.txt 기반으로 가게를 관리합니다", "#27AE60", "#EAFAF1");
        slotCard.setOnClickListener(v -> showSlotManageScreen());
        layout.addView(slotCard);

        // ── 개발자 테스트 ─────────────────────────────────
        LinearLayout.LayoutParams secTestLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secTestLp.setMargins(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(6));
        LinearLayout secTest = makeSectionTitle("개발자 테스트", "#E74C3C", TEXT1);
        secTest.setLayoutParams(secTestLp);
        layout.addView(secTest);

        LinearLayout fcmTestCard = makeAdminMenuCard("📡", "FCM PUSH TEST",
                "일반사용자 SMS 테스트", "#E74C3C", "#FDEDEC");
        fcmTestCard.setOnClickListener(v -> {
            // ── 커스텀 FCM 테스트 다이얼로그 ──
            android.app.Dialog fcmDlg = new android.app.Dialog(this,
                    android.R.style.Theme_Material_Light_Dialog);
            LinearLayout dlg = new LinearLayout(this);
            dlg.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable dlgBg =
                    new android.graphics.drawable.GradientDrawable();
            dlgBg.setColor(Color.WHITE);
            dlgBg.setCornerRadius(dpToPx(20));
            dlg.setBackground(dlgBg);
            dlg.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));

            // 제목
            TextView fcmDlgTitle = new TextView(this);
            fcmDlgTitle.setText("📡  FCM 테스트 전송");
            fcmDlgTitle.setTextColor(Color.parseColor("#E74C3C"));
            fcmDlgTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
            fcmDlgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            fcmDlgTitle.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ttLp.setMargins(0, 0, 0, dpToPx(6));
            fcmDlgTitle.setLayoutParams(ttLp);
            dlg.addView(fcmDlgTitle);

            // 부제목
            TextView tvSub = new TextView(this);
            tvSub.setText("전송 대상을 선택하세요");
            tvSub.setTextColor(Color.parseColor("#888888"));
            tvSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            tvSub.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.setMargins(0, 0, 0, dpToPx(18));
            tvSub.setLayoutParams(subLp);
            dlg.addView(tvSub);

            // 버튼 생성 헬퍼
            java.util.function.BiFunction<String, String, TextView> makeBtn = (text, color) -> {
                TextView btn = new TextView(this);
                btn.setText(text);
                btn.setTextColor(Color.WHITE);
                btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(0, dpToPx(14), 0, dpToPx(14));
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setColor(Color.parseColor(color));
                bg.setCornerRadius(dpToPx(12));
                btn.setBackground(bg);
                btn.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dpToPx(10));
                btn.setLayoutParams(lp);
                return btn;
            };

            // 전체 전송 버튼
            TextView btnAll = makeBtn.apply("전체 사용자에게 전송", "#0984E3");
            btnAll.setOnClickListener(vv -> {
                fcmDlg.dismiss();
                // 확인 다이얼로그
                showFcmConfirmDialog("전체 사용자에게 전송하시겠습니까?", () -> {
                    String today = new java.text.SimpleDateFormat("MM/dd", java.util.Locale.KOREA)
                            .format(new java.util.Date());
                    String fakeBody = "[Web발신]\n농협 출금10,000원\n" + today
                            + " 12:00\n351-****-5510-13\nTEST거래\n잔액999,000원";
                    new SmsReceiver().processMessage(PinActivity.this, "15882100", fakeBody);
                    new MyFirebaseMessagingService().saveFcmReceivedLogPublic(PinActivity.this);
                    android.widget.Toast.makeText(PinActivity.this,
                            "FCM 전체 전송 완료", android.widget.Toast.LENGTH_SHORT).show();
                });
            });
            dlg.addView(btnAll);

            // 특정 사용자 버튼
            TextView btnSpec = makeBtn.apply("kisseyes4uu@gmail.com 에게만 전송", "#6C5CE7");
            btnSpec.setOnClickListener(vv -> {
                fcmDlg.dismiss();
                showFcmConfirmDialog("kisseyes4uu@gmail.com 에게\n전송하시겠습니까?", () -> {
                    String today = new java.text.SimpleDateFormat("MM/dd", java.util.Locale.KOREA)
                            .format(new java.util.Date());
                    String fakeBody = "[Web발신]\n농협 출금10,000원\n" + today
                            + " 12:00\n351-****-5510-13\nTEST거래\n잔액999,000원";
                    sendFcmTestToSpecificUser(fakeBody, "kisseyes4uu@gmail.com");
                    android.widget.Toast.makeText(PinActivity.this,
                            "FCM 전송 완료 → kisseyes4uu",
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            });
            dlg.addView(btnSpec);

            // 취소 버튼
            TextView btnCancel = new TextView(this);
            btnCancel.setText("취소");
            btnCancel.setTextColor(Color.parseColor("#888888"));
            btnCancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
            btnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
            btnCancel.setGravity(Gravity.CENTER);
            btnCancel.setPadding(0, dpToPx(14), 0, dpToPx(14));
            android.graphics.drawable.GradientDrawable cancelBg =
                    new android.graphics.drawable.GradientDrawable();
            cancelBg.setColor(Color.parseColor("#F0F0F0"));
            cancelBg.setCornerRadius(dpToPx(12));
            cancelBg.setStroke(dpToPx(1), Color.parseColor("#CCCCCC"));
            btnCancel.setBackground(cancelBg);
            btnCancel.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            btnCancel.setOnClickListener(vv -> fcmDlg.dismiss());
            dlg.addView(btnCancel);

            fcmDlg.setContentView(dlg);
            fcmDlg.setCancelable(true);
            if (fcmDlg.getWindow() != null) {
                fcmDlg.getWindow().setLayout(
                        (int)(getResources().getDisplayMetrics().widthPixels * 0.82),
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                fcmDlg.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT));
            }
            fcmDlg.show();
        });
        layout.addView(fcmTestCard);

        // ── 3번: Drive 업로드 실패 재시도 카드 ───────────────────
        android.content.SharedPreferences prefs2 = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String failedSms    = prefs2.getString("upload_failed_sms", "");
        String failedSender = prefs2.getString("upload_failed_sender", "");
        boolean hasFailed   = !failedSms.isEmpty();
        int failCount2 = hasFailed
                ? Math.max(1, failedSms.trim().split("-----------------------------------").length)
                : 0;

        LinearLayout secRetryRow = new LinearLayout(this);
        secRetryRow.setOrientation(LinearLayout.HORIZONTAL);
        secRetryRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams secRetryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secRetryLp.setMargins(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(6));
        secRetryRow.setLayoutParams(secRetryLp);
        LinearLayout secRetry = makeSectionTitle("Drive 업로드", "#E67E22", TEXT1);
        LinearLayout.LayoutParams secRetryTitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secRetry.setLayoutParams(secRetryTitleLp);
        secRetryRow.addView(secRetry);
        if (hasFailed) {
            TextView tvFailBadge = new TextView(this);
            tvFailBadge.setText(String.valueOf(failCount2));
            tvFailBadge.setTextColor(Color.WHITE);
            tvFailBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvFailBadge.setTypeface(null, Typeface.BOLD);
            tvFailBadge.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable badgeBg =
                    new android.graphics.drawable.GradientDrawable();
            badgeBg.setColor(Color.parseColor("#E74C3C"));
            badgeBg.setCornerRadius(dpToPx(10));
            tvFailBadge.setBackground(badgeBg);
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    dpToPx(20), dpToPx(20));
            badgeLp.setMargins(dpToPx(6), 0, 0, 0);
            tvFailBadge.setLayoutParams(badgeLp);
            secRetryRow.addView(tvFailBadge);
        }
        layout.addView(secRetryRow);

        // ── Drive 업로드 상태 카드 ──────────────────────────────

        LinearLayout driveCard = new LinearLayout(this);
        driveCard.setOrientation(LinearLayout.VERTICAL);
        driveCard.setBackground(makeShadowCardDrawable("#FFFFFF", 14, 4));
        driveCard.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        driveCard.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
        LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dcLp.setMargins(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        driveCard.setLayoutParams(dcLp);

        // 상태 텍스트
        TextView tvDriveStatus = new TextView(this);
        tvDriveStatus.setTextSize(14);
        tvDriveStatus.setTypeface(null, Typeface.BOLD);

        if (!hasFailed) {
            tvDriveStatus.setText("✅  업로드 실패한 SMS 없습니다");
            tvDriveStatus.setTextColor(Color.parseColor("#27AE60"));
        } else {
            // 실패 건수 계산 (줄바꿈 기준)
            tvDriveStatus.setText("⚠️  " + failCount2 + "건의 업로드 실패한 SMS 있습니다");
            tvDriveStatus.setTextColor(Color.parseColor("#E74C3C"));
        }
        driveCard.addView(tvDriveStatus);

        // 실패한 SMS 내역 표시
        TextView tvFailedDetail = new TextView(this);
        tvFailedDetail.setTextSize(12);
        tvFailedDetail.setTextColor(Color.parseColor("#888888"));
        LinearLayout.LayoutParams fdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fdLp.setMargins(0, dpToPx(6), 0, 0);
        tvFailedDetail.setLayoutParams(fdLp);

        if (!hasFailed) {
            tvFailedDetail.setText("업로드 실패한 문자 내역이 없습니다");
        } else {
            // 실패한 SMS 내용 요약 표시
            StringBuilder detailSb = new StringBuilder();
            String[] lines = failedSms.split("\n");
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.equals("-----------------------------------")) continue;
                detailSb.append("\u2022 ").append(t).append("\n");
            }
            tvFailedDetail.setText(detailSb.toString().trim());
        }
        driveCard.addView(tvFailedDetail);

        // 재시도 버튼 (항상 표시, 실패 없을 때는 비활성화)
        LinearLayout.LayoutParams btnRetryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRetryLp.setMargins(0, dpToPx(10), 0, 0);
        TextView btnRetry = new TextView(this);
        btnRetry.setText("🔄  실패한 SMS 업로드 재시도");
        btnRetry.setTextSize(13);
        btnRetry.setGravity(Gravity.CENTER);
        btnRetry.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        btnRetry.setLayoutParams(btnRetryLp);
        btnRetry.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);

        if (hasFailed) {
            btnRetry.setTextColor(Color.parseColor("#C45E00"));
            btnRetry.setBackground(makeShadowCardDrawable("#FFE0B2", 20, 5));
            btnRetry.setOnClickListener(v -> {
                new SmsReceiver().processMessage(this, failedSender, failedSms);
                prefs2.edit().remove("upload_failed_sms").remove("upload_failed_sender").apply();
                android.widget.Toast.makeText(this, "재시도 완료", android.widget.Toast.LENGTH_SHORT).show();
                ownerMenuBuilder.build();
            });
        } else {
            btnRetry.setTextColor(Color.parseColor("#AAAAAA"));
            btnRetry.setBackground(makeShadowCardDrawable("#F0F0F0", 20, 2));
            btnRetry.setEnabled(false);
        }
        driveCard.addView(btnRetry);
        layout.addView(driveCard);

        // ── 접속 사용자 섹션 (Drive 업로드 아래) ─────────────────
        LinearLayout usersTitleRow = new LinearLayout(this);
        usersTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        usersTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams utrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        utrLp.setMargins(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(6));
        usersTitleRow.setLayoutParams(utrLp);

        LinearLayout secUsers = new LinearLayout(this);
        secUsers.setOrientation(LinearLayout.HORIZONTAL);
        secUsers.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams suLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        secUsers.setLayoutParams(suLp);

        View accentBar2 = new View(this);
        android.graphics.drawable.GradientDrawable barGrad2 =
                new android.graphics.drawable.GradientDrawable();
        barGrad2.setColor(Color.parseColor(PURPLE));
        barGrad2.setCornerRadius(dpToPx(4));
        accentBar2.setBackground(barGrad2);
        accentBar2.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(4), dpToPx(20)));
        secUsers.addView(accentBar2);

        TextView tvUsersTitle = new TextView(this);
        tvUsersTitle.setText("접속 사용자 목록");
        tvUsersTitle.setTextColor(Color.parseColor(TEXT1));
        tvUsersTitle.setTextSize(15);
        tvUsersTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams utLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        utLp.setMargins(dpToPx(10), 0, 0, 0);
        tvUsersTitle.setLayoutParams(utLp);
        secUsers.addView(tvUsersTitle);
        usersTitleRow.addView(secUsers);

        // 사용자 목록 새로고침 버튼
        TextView btnRefreshUsers2 = new TextView(this);
        btnRefreshUsers2.setText("🔄  새로고침");
        btnRefreshUsers2.setTextSize(13);
        btnRefreshUsers2.setTextColor(Color.parseColor("#C45E00"));
        btnRefreshUsers2.setGravity(Gravity.CENTER);
        btnRefreshUsers2.setBackground(makeShadowCardDrawable("#FFE0B2", 20, 5));
        btnRefreshUsers2.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        btnRefreshUsers2.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams refUser2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        refUser2Lp.setMargins(0, dpToPx(4), 0, dpToPx(4));
        btnRefreshUsers2.setLayoutParams(refUser2Lp);
        usersTitleRow.addView(btnRefreshUsers2);
        layout.addView(usersTitleRow);

        LinearLayout usersContainer = new LinearLayout(this);
        usersContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ucLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ucLp.setMargins(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(10));
        usersContainer.setLayoutParams(ucLp);
        layout.addView(usersContainer);

        TextView tvLoadingUsers = new TextView(this);
        tvLoadingUsers.setText("불러오는 중...");
        tvLoadingUsers.setTextColor(Color.parseColor("#888888"));
        tvLoadingUsers.setTextSize(13);
        tvLoadingUsers.setPadding(0, dpToPx(8), 0, dpToPx(8));

        String cachedUsers = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getString("cached_users_list", null);
        if (cachedUsers != null && !cachedUsers.isEmpty()) {
            renderUsersList(usersContainer, cachedUsers);
        } else {
            usersContainer.addView(tvLoadingUsers);
            loadUsersList(usersContainer, tvLoadingUsers);
        }

        btnRefreshUsers2.setOnClickListener(v -> {
            usersContainer.removeAllViews();
            usersContainer.addView(tvLoadingUsers);
            loadUsersList(usersContainer, tvLoadingUsers);
        });

        // ── 버스 데이터 관리 섹션 ────────────────────────────
        LinearLayout busSecRow = makeSectionTitle("버스 데이터 관리", "#0984E3", TEXT1);
        LinearLayout.LayoutParams busSecLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        busSecLp.setMargins(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(6));
        busSecRow.setLayoutParams(busSecLp);
        layout.addView(busSecRow);

        LinearLayout busManageCard = new LinearLayout(this);
        busManageCard.setOrientation(LinearLayout.VERTICAL);
        busManageCard.setBackground(makeShadowCardDrawable("#FFFFFF", 14, 4));
        busManageCard.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        busManageCard.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
        LinearLayout.LayoutParams bmcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bmcLp.setMargins(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(10));
        busManageCard.setLayoutParams(bmcLp);

        boolean hasRouteDb = routeDbList != null && !routeDbList.isEmpty();
        boolean hasStopDb2 = stopDbList != null && !stopDbList.isEmpty();

        TextView tvRouteStatus = new TextView(this);
        tvRouteStatus.setText("🚌 노선 DB: " + (hasRouteDb ? routeDbList.size() + "개 (매일 자동 갱신)" : "없음"));
        tvRouteStatus.setTextColor(Color.parseColor(hasRouteDb ? "#27AE60" : "#E74C3C"));
        tvRouteStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        busManageCard.addView(tvRouteStatus);

        TextView tvStopStatus = new TextView(this);
        tvStopStatus.setText("🚏 정류장 DB: " + (hasStopDb2 ? stopDbList.size() + "개" : "없음"));
        tvStopStatus.setTextColor(Color.parseColor(hasStopDb2 ? "#27AE60" : "#E74C3C"));
        tvStopStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.setMargins(0, dpToPx(4), 0, dpToPx(12));
        tvStopStatus.setLayoutParams(stLp);
        busManageCard.addView(tvStopStatus);

        // ── 진단: routes 포함 여부 확인 ──────────────────────────────
        TextView tvRoutesCheck = new TextView(this);
        String cachedJson = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE).getString("stop_json_cache", "");
        boolean hasRoutes = cachedJson.contains("\"routes\":\"2") || cachedJson.contains("\"routes\":\"1") || cachedJson.contains("\"routes\":\"6") || cachedJson.contains("\"routes\":\"7");
        int routeDbSize = routeDbList != null ? routeDbList.size() : 0;
        tvRoutesCheck.setText("routes 포함: " + (hasRoutes ? "✅ 있음" : "❌ 없음 → DB 재빌드 필요")
                + "\n노선DB: " + routeDbSize + "개");
        tvRoutesCheck.setTextColor(Color.parseColor(hasRoutes ? "#27AE60" : "#E74C3C"));
        tvRoutesCheck.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rcLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
        tvRoutesCheck.setLayoutParams(rcLp);
        busManageCard.addView(tvRoutesCheck);

        TextView btnBusManage = new TextView(this);
        btnBusManage.setText(hasStopDb2 ? "🚏 정류장 DB 업데이트" : "🚏 정류장 DB 생성 (최초 1회)");
        btnBusManage.setTextColor(Color.WHITE);
        btnBusManage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        btnBusManage.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBusManage.setGravity(Gravity.CENTER);
        btnBusManage.setPadding(0, dpToPx(12), 0, dpToPx(12));
        android.graphics.drawable.GradientDrawable btnBusBg = new android.graphics.drawable.GradientDrawable();
        btnBusBg.setColor(Color.parseColor("#0984E3"));
        btnBusBg.setCornerRadius(dpToPx(10));
        btnBusManage.setBackground(btnBusBg);
        btnBusManage.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnBusManage.setOnClickListener(v -> showConfirmDialog("🚏", "정류장 DB 업데이트", "정류장 DB를 업데이트 하시겠습니까?\n\n대전 전체 정류장을 수집하여\nDrive에 업로드합니다.\n수 분이 소요됩니다.", () -> {
            // ── 프로그레스 다이얼로그 ──
            android.app.Dialog dlg = new android.app.Dialog(this,
                    android.R.style.Theme_Material_Light_Dialog_Alert);
            LinearLayout dlgLayout = new LinearLayout(this);
            dlgLayout.setOrientation(LinearLayout.VERTICAL);
            dlgLayout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));
            dlgLayout.setBackgroundColor(Color.WHITE);
            TextView tvDlgTitle = new TextView(this);
            tvDlgTitle.setText("🚏 정류장 DB 수집 중");
            tvDlgTitle.setTextColor(Color.parseColor("#0984E3"));
            tvDlgTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
            tvDlgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams dlgTLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlgTLp.setMargins(0, 0, 0, dpToPx(6));
            tvDlgTitle.setLayoutParams(dlgTLp);
            dlgLayout.addView(tvDlgTitle);
            TextView tvDlgDesc = new TextView(this);
            tvDlgDesc.setText("대전 전체 정류장 데이터를 수집하고\nDrive에 업로드합니다.");
            tvDlgDesc.setTextColor(Color.parseColor("#666666"));
            tvDlgDesc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            LinearLayout.LayoutParams dlgDLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlgDLp.setMargins(0, 0, 0, dpToPx(16));
            tvDlgDesc.setLayoutParams(dlgDLp);
            dlgLayout.addView(tvDlgDesc);
            android.widget.ProgressBar dlgPb = new android.widget.ProgressBar(
                    this, null, android.R.attr.progressBarStyleHorizontal);
            dlgPb.setMax(100); dlgPb.setProgress(0);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                dlgPb.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0984E3")));
                dlgPb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#DDEEFF")));
            }
            dlgPb.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(10)));
            dlgLayout.addView(dlgPb);
            TextView tvDlgPct = new TextView(this);
            tvDlgPct.setText("0%");
            tvDlgPct.setTextColor(Color.parseColor("#0984E3"));
            tvDlgPct.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            tvDlgPct.setTypeface(null, android.graphics.Typeface.BOLD);
            tvDlgPct.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams pctLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pctLp2.setMargins(0, dpToPx(8), 0, 0);
            tvDlgPct.setLayoutParams(pctLp2);
            dlgLayout.addView(tvDlgPct);
            dlg.setContentView(dlgLayout);
            dlg.setCancelable(false);
            dlg.show();
            btnBusManage.setEnabled(false);
            btnBusBg.setColor(Color.parseColor("#AAAAAA"));
            buildAndUploadStopDb(() -> {
                dlg.dismiss();
                int cnt = stopDbList != null ? stopDbList.size() : 0;
                tvStopStatus.setText("🚏 정류장 DB: " + cnt + "개");
                tvStopStatus.setTextColor(Color.parseColor("#27AE60"));
                btnBusManage.setText("🚏 정류장 DB 업데이트");
                btnBusBg.setColor(Color.parseColor("#0984E3"));
                btnBusManage.setEnabled(true);
                android.widget.Toast.makeText(this, "✓ " + cnt + "개 정류장 DB 업로드 완료!", android.widget.Toast.LENGTH_LONG).show();
            }, pct -> { dlgPb.setProgress(pct); tvDlgPct.setText(pct + "%"); });
        }));
        busManageCard.addView(btnBusManage);

        // ── 배차시간표 업로드 버튼 ──────────────────────────────────
        boolean hasBusTimes = !busTimesMap.isEmpty();
        TextView tvBusTimesStatus = new TextView(this);
        tvBusTimesStatus.setText("🕐 배차시간표: " + (hasBusTimes ? busTimesMap.size() + "개 노선 ✓" : "없음"));
        tvBusTimesStatus.setTextColor(Color.parseColor(hasBusTimes ? "#27AE60" : "#E74C3C"));
        tvBusTimesStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        LinearLayout.LayoutParams btStLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btStLp.setMargins(0, dpToPx(8), 0, dpToPx(4));
        tvBusTimesStatus.setLayoutParams(btStLp);
        busManageCard.addView(tvBusTimesStatus);

        TextView btnBusTimes = new TextView(this);
        btnBusTimes.setText("🕐 배차시간표 업로드");
        btnBusTimes.setTextColor(Color.WHITE);
        btnBusTimes.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        btnBusTimes.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBusTimes.setGravity(Gravity.CENTER);
        btnBusTimes.setPadding(0, dpToPx(12), 0, dpToPx(12));
        android.graphics.drawable.GradientDrawable btnBtBg = new android.graphics.drawable.GradientDrawable();
        btnBtBg.setColor(Color.parseColor("#00B894"));
        btnBtBg.setCornerRadius(dpToPx(10));
        btnBusTimes.setBackground(btnBtBg);
        LinearLayout.LayoutParams btnBtLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnBtLp.setMargins(0, 0, 0, dpToPx(8));
        btnBusTimes.setLayoutParams(btnBtLp);
        btnBusTimes.setOnClickListener(v -> showConfirmDialog("🕐", "배차시간표 업데이트",
                "traffic.daejeon.go.kr에서 최신 배차시간표를\n자동으로 다운로드하여 Drive에 업로드합니다.\n잠시 시간이 걸릴 수 있습니다.", () -> {
            btnBusTimes.setEnabled(false);
            btnBtBg.setColor(Color.parseColor("#AAAAAA"));
            new Thread(() -> {
                try {
                    // 1) traffic.daejeon.go.kr에서 엑셀 다운로드
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "배차시간표 다운로드 중...", android.widget.Toast.LENGTH_SHORT).show());
                    String xlsUrl = "https://traffic.daejeon.go.kr/web-notice/api/v1/notice/getNoticeFileName/bustime/1";
                    java.net.URL url = new java.net.URL(xlsUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    byte[] xlsBytes = readBytes(conn.getInputStream());
                    conn.disconnect();

                    // 2) 엑셀 파싱 → TXT 생성 (9필드: rno|src|dst|ws|wd|ss|sd|hs|hd)
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "배차시간표 파싱 중...", android.widget.Toast.LENGTH_SHORT).show());
                    String json = parseBusTimesXls(xlsBytes);
                    if (json.isEmpty()) throw new Exception("파싱 실패");

                    // 3) Drive에 업로드
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "Drive에 업로드 중...", android.widget.Toast.LENGTH_SHORT).show());
                    new DriveUploadHelper(this).uploadFileSync(json, BUS_TIME_FILE);
                    getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE).edit()
                            .putString("bustimes_txt_cache", json).apply();
                    saveBusTimes(json); // 내부 파일에 영구 저장
                    loadBusTimesFromJson(json);

                    runOnUiThread(() -> {
                        btnBusTimes.setEnabled(true);
                        btnBtBg.setColor(Color.parseColor("#00B894"));
                        tvBusTimesStatus.setText("🕐 배차시간표: " + busTimesMap.size() + "개 노선 ✓");
                        tvBusTimesStatus.setTextColor(Color.parseColor("#27AE60"));
                        android.widget.Toast.makeText(this,
                                "✓ 배차시간표 " + busTimesMap.size() + "개 노선 업로드 완료!",
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        btnBusTimes.setEnabled(true);
                        btnBtBg.setColor(Color.parseColor("#00B894"));
                        android.widget.Toast.makeText(this,
                                "실패: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }));
        busManageCard.addView(btnBusTimes);

        layout.addView(busManageCard);

        layout.setPadding(0, 0, 0, dpToPx(40));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setClipToPadding(true);
        scrollView.setPadding(0, statusBarHeight, 0, 0);
        scrollView.setBackgroundColor(Color.parseColor(BG));
        RelativeLayout.LayoutParams svParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        scrollView.setLayoutParams(svParams);
        scrollView.addView(layout);

        RelativeLayout rootView = new RelativeLayout(this);
        rootView.setBackgroundColor(Color.parseColor(BG));
        rootView.addView(scrollView);
        rootView.addView(makeVersionLabel());
        setContentView(rootView);

        // 상단 padding 보정: Android 11+ (S25+) 대응
        scrollView.post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.view.WindowInsets wi = scrollView.getRootWindowInsets();
                if (wi != null) {
                    int realTop = wi.getInsets(android.view.WindowInsets.Type.systemBars()).top;
                    if (realTop > 0) scrollView.setPadding(0, realTop, 0, 0);
                }
            } else {
                android.view.WindowInsets wi = scrollView.getRootWindowInsets();
                if (wi != null) {
                    int realTop = wi.getSystemWindowInsetTop();
                    if (realTop > 0) scrollView.setPadding(0, realTop, 0, 0);
                }
            }
        });
    }
    // 섹션 타이틀 헬퍼 (왼쪽 보라 바 + 텍스트)
    private LinearLayout makeSectionTitle(String text, String accentColor, String textColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View bar = new View(this);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor(accentColor));
        bg.setCornerRadius(dpToPx(4));
        bar.setBackground(bg);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(4), dpToPx(20)));
        row.addView(bar);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(textColor));
        tv.setTextSize(15);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.setMargins(dpToPx(10), 0, 0, 0);
        tv.setLayoutParams(tvLp);
        row.addView(tv);
        return row;
    }

    // 관리자 메뉴 카드 헬퍼 (일반사용자 메뉴카드와 동일 스타일)
    private LinearLayout makeAdminMenuCard(String emoji, String title,
                                           String desc, String accentColor, String iconBgColor) {
        LinearLayout mc = new LinearLayout(this);
        mc.setOrientation(LinearLayout.HORIZONTAL);
        mc.setGravity(Gravity.CENTER_VERTICAL);
        mc.setBackground(makeShadowCardDrawable("#FFFFFF", 16, 6));
        mc.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(68));
        mcLp.setMargins(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        mc.setLayoutParams(mcLp);
        mc.setPadding(dpToPx(16), 0, dpToPx(16), 0);

        android.graphics.drawable.GradientDrawable iconBg =
                new android.graphics.drawable.GradientDrawable();
        iconBg.setColor(Color.parseColor(iconBgColor));
        iconBg.setCornerRadius(dpToPx(12));
        TextView tvIcon = new TextView(this);
        tvIcon.setText(emoji);
        tvIcon.setTextSize(22);
        tvIcon.setGravity(Gravity.CENTER);
        tvIcon.setBackground(iconBg);
        LinearLayout.LayoutParams adminIconLp = new LinearLayout.LayoutParams(
                dpToPx(46), dpToPx(46));
        tvIcon.setLayoutParams(adminIconLp);
        mc.addView(tvIcon);

        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams taLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        taLp.setMargins(dpToPx(14), 0, 0, 0);
        textArea.setLayoutParams(taLp);

        TextView tvMTitle = new TextView(this);
        tvMTitle.setText(title);
        tvMTitle.setTextColor(Color.parseColor("#1A1A2E"));
        tvMTitle.setTextSize(16);
        tvMTitle.setTypeface(null, Typeface.BOLD);
        textArea.addView(tvMTitle);

        TextView tvMDesc = new TextView(this);
        tvMDesc.setText(desc);
        tvMDesc.setTextColor(Color.parseColor("#9CA3AF"));
        tvMDesc.setTextSize(12);
        tvMDesc.setSingleLine(true);
        tvMDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.setMargins(0, dpToPx(2), 0, 0);
        tvMDesc.setLayoutParams(descLp);
        textArea.addView(tvMDesc);
        mc.addView(textArea);

        TextView tvArrow = new TextView(this);
        tvArrow.setText("›");
        tvArrow.setTextColor(Color.parseColor("#D1D5DB"));
        tvArrow.setTextSize(24);
        tvArrow.setTypeface(null, Typeface.BOLD);
        tvArrow.setGravity(Gravity.CENTER_VERTICAL);
        mc.addView(tvArrow);

        return mc;
    }

    private void loadUsersList(LinearLayout container, TextView tvLoading) {
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(USERS_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String fileContent) {
                    // SharedPreferences에 캐시 저장
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putString("cached_users_list", fileContent).apply();
                    runOnUiThread(() -> {
                        container.removeView(tvLoading);
                        renderUsersList(container, fileContent);
                    });
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() ->
                            tvLoading.setText("사용자 목록 불러오기 실패"));
                }
            });
        } catch (Exception e) {
            tvLoading.setText("오류 발생");
        }
    }

    /** FCM 수신 현황 렌더링 - 이메일 옆에 앱 버전 + 마지막 접속 시간 표시 */
    private void renderFcmList(LinearLayout fcmStatusCard, TextView tvFcmLoading,
                               LinearLayout fcmListContainer, String data,
                               java.util.Map<String, String> versionMap,
                               java.util.Map<String, String> lastAccessMap) {
        fcmStatusCard.removeView(tvFcmLoading);
        fcmListContainer.removeAllViews();
        String myVer = getMyVersion();
        String[] lines = data.split("\\r?\\n");
        int count = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", 2);
            String email    = parts.length > 0 ? parts[0].trim() : line.trim();
            String recvTime = parts.length > 1 ? parts[1].trim() : "시간 없음";
            String version    = versionMap.getOrDefault(email.toLowerCase(), "");
            String lastAccess = lastAccessMap.getOrDefault(email.toLowerCase(), "");

            if (count > 0) {
                View div = new View(this);
                div.setBackgroundColor(Color.parseColor("#F0F0F0"));
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                divLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
                div.setLayoutParams(divLp);
                fcmListContainer.addView(div);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
            row.setLayoutParams(rowLp);

            TextView tvDot = new TextView(this);
            tvDot.setText("📱");
            tvDot.setTextSize(16);
            row.addView(tvDot);

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            icLp.setMargins(dpToPx(8), 0, 0, 0);
            infoCol.setLayoutParams(icLp);

            // 이메일 + 버전 한 줄
            LinearLayout emailRow = new LinearLayout(this);
            emailRow.setOrientation(LinearLayout.HORIZONTAL);
            emailRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvEmail = new TextView(this);
            tvEmail.setText(email);
            tvEmail.setTextColor(Color.parseColor("#1A1A2E"));
            tvEmail.setTextSize(13);
            tvEmail.setTypeface(null, Typeface.BOLD);
            emailRow.addView(tvEmail);

            // 버전 표시
            if (!version.isEmpty()) {
                TextView tvVer = new TextView(this);
                tvVer.setText("  v" + version);
                tvVer.setTextSize(11);
                tvVer.setTypeface(null, Typeface.BOLD);
                tvVer.setTextColor(version.equals(myVer)
                        ? Color.parseColor("#27AE60")   // 최신: 초록
                        : Color.parseColor("#E74C3C")); // 구버전: 빨강
                emailRow.addView(tvVer);
            }
            infoCol.addView(emailRow);

            TextView tvTime = new TextView(this);
            tvTime.setText("FCM 수신: " + recvTime);
            tvTime.setTextColor(Color.parseColor("#27AE60"));
            tvTime.setTextSize(11);
            infoCol.addView(tvTime);

            // 마지막 앱 접속 시간
            if (!lastAccess.isEmpty()) {
                TextView tvAccess = new TextView(this);
                tvAccess.setText("앱 접속: " + lastAccess);
                tvAccess.setTextColor(Color.parseColor("#888888"));
                tvAccess.setTextSize(11);
                infoCol.addView(tvAccess);
            }
            row.addView(infoCol);

            TextView tvOk = new TextView(this);
            tvOk.setText("✅");
            tvOk.setTextSize(16);
            row.addView(tvOk);

            fcmListContainer.addView(row);
            count++;
        }
        if (count == 0) {
            tvFcmLoading.setText("아직 수신 기록이 없습니다");
            tvFcmLoading.setTextColor(Color.parseColor("#888888"));
            fcmStatusCard.addView(tvFcmLoading);
        }
    }

    private void renderUsersList(LinearLayout container, String fileContent) {
        String[] lines = fileContent.split("\r?\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;
            String email      = parts[0].trim();
            String status     = parts[1].trim();
            String lastAccess = parts.length > 2 ? parts[2].trim() : "";
            String version    = parts.length > 3 ? parts[3].trim() : "";
            addUserRow(container, email, status, lastAccess, version, fileContent);
        }
    }

    private void addUserRow(LinearLayout container, String email,
                            String status, String lastAccess, String version, String fullContent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(16f);
        card.setBackground(bg);
        card.setElevation(8f);
        card.setPadding(24, 16, 24, 16);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 6, 0, 12);
        card.setLayoutParams(cp);

        // 이메일 + 버튼 한 줄
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvEmail = new TextView(this);
        tvEmail.setText(email);
        tvEmail.setTextColor(Color.parseColor("#333333"));
        tvEmail.setTextSize(12);
        LinearLayout.LayoutParams emailParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvEmail.setLayoutParams(emailParams);
        btnRow.addView(tvEmail);

        TextView btnAllow = new TextView(this);
        btnAllow.setText("승인");
        TextView btnBlock = new TextView(this);
        btnBlock.setText("차단");

        boolean isAllowed = "허용".equals(status);
        styleUserBtn(btnAllow, isAllowed ? "#27AE60" : "#AAAAAA");
        styleUserBtn(btnBlock, isAllowed ? "#AAAAAA" : "#C0392B");

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(8, 0, 4, 0);
        btnAllow.setLayoutParams(bp);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp2.setMargins(4, 0, 0, 0);
        btnBlock.setLayoutParams(bp2);

        btnAllow.setOnClickListener(v -> {
            if (!"허용".equals(status)) {
                android.app.AlertDialog dlg =
                        new android.app.AlertDialog.Builder(this,
                                android.R.style.Theme_Material_Light_Dialog_Alert)
                                .setTitle("승인 확인")
                                .setMessage(email + "\n승인 하시겠습니까?")
                                .setPositiveButton("승인", null)
                                .setNegativeButton("취소", null)
                                .create();
                dlg.show();
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setTextColor(Color.parseColor("#27AE60"));
                dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                        .setTextColor(Color.parseColor("#888888"));
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(cv -> {
                            updateUserStatus(email, "허용", fullContent, container);
                            dlg.dismiss();
                        });
            }
        });
        btnBlock.setOnClickListener(v -> {
            if (!"차단".equals(status)) {
                android.app.AlertDialog dlg =
                        new android.app.AlertDialog.Builder(this,
                                android.R.style.Theme_Material_Light_Dialog_Alert)
                                .setTitle("차단 확인")
                                .setMessage(email + "\n차단 하시겠습니까?")
                                .setPositiveButton("차단", null)
                                .setNegativeButton("취소", null)
                                .create();
                dlg.show();
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setTextColor(Color.parseColor("#C0392B"));
                dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                        .setTextColor(Color.parseColor("#888888"));
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(cv -> {
                            updateUserStatus(email, "차단", fullContent, container);
                            removeFcmToken(email);
                            dlg.dismiss();
                        });
            }
        });

        btnRow.addView(btnAllow);
        btnRow.addView(btnBlock);
        card.addView(btnRow);

        container.addView(card);
    }

    /**
     * 화면 중앙 로딩 오버레이 표시.
     * @return 오버레이 View (제거 시 parent.removeView(overlay) 호출)
     */
    private android.view.View showLoadingOverlay(android.view.ViewGroup parent, String message) {
        android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#88EEE8F5")); // 반투명 연보라
        android.widget.FrameLayout.LayoutParams overlayLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        overlay.setLayoutParams(overlayLp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dpToPx(32), dpToPx(28), dpToPx(32), dpToPx(28));
        android.graphics.drawable.GradientDrawable boxBg =
                new android.graphics.drawable.GradientDrawable();
        boxBg.setColor(Color.WHITE);
        boxBg.setCornerRadius(dpToPx(16));
        box.setBackground(boxBg);
        box.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        android.widget.FrameLayout.LayoutParams boxLp =
                new android.widget.FrameLayout.LayoutParams(
                        dpToPx(160), android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        box.setLayoutParams(boxLp);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            pb.setIndeterminateTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#6C5CE7")));
        }
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40));
        pbLp.gravity = Gravity.CENTER_HORIZONTAL;
        pbLp.setMargins(0, 0, 0, dpToPx(12));
        pb.setLayoutParams(pbLp);
        box.addView(pb);

        TextView tvMsg = new TextView(this);
        tvMsg.setText(message);
        tvMsg.setTextColor(Color.parseColor("#A89CD0"));
        tvMsg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        tvMsg.setGravity(Gravity.CENTER);
        box.addView(tvMsg);

        overlay.addView(box);
        parent.addView(overlay);
        return overlay;
    }

    private void styleUserBtn(TextView btn, String color) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(20f);
        btn.setBackground(d);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(11);
        btn.setPadding(14, 4, 14, 4);
        btn.setGravity(Gravity.CENTER);
    }

    private void removeFcmToken(String email) {
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(this);
                reader.readFile(FCM_TOKENS_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        StringBuilder sb = new StringBuilder();
                        for (String line : existing.split("\r?\n")) {
                            if (line.trim().isEmpty()) continue;
                            if (!line.toLowerCase().startsWith(email.toLowerCase())) {
                                sb.append(line.trim()).append("\n");
                            }
                        }
                        new Thread(() -> {
                            try {
                                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                                up.uploadFileSync(sb.toString().trim(), FCM_TOKENS_FILE);
                                android.util.Log.d("Block", "FCM 토큰 삭제 완료: " + email);
                            } catch (Exception e) {
                                android.util.Log.e("Block", "FCM 토큰 삭제 실패: " + e.getMessage());
                            }
                        }).start();
                    }
                    @Override public void onFailure(String error) {
                        android.util.Log.e("Block", "FCM 토큰 파일 읽기 실패: " + error);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("Block", "removeFcmToken 오류: " + e.getMessage());
            }
        }).start();
    }

    private void updateUserStatus(String email, String newStatus,
                                  String oldContent, LinearLayout container) {
        StringBuilder sb = new StringBuilder();
        for (String line : oldContent.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|");
            if (parts.length >= 1 &&
                    parts[0].trim().equalsIgnoreCase(email)) {
                sb.append(email).append("|").append(newStatus).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        String newContent = sb.toString().trim();
        new Thread(() -> {
            try {
                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                up.uploadFileSync(newContent, USERS_FILE);
                runOnUiThread(() -> {
                    Toast.makeText(PinActivity.this,
                            email + " → " + newStatus,
                            Toast.LENGTH_SHORT).show();
                    ownerMenuBuilder.build();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(PinActivity.this,
                                "업데이트 실패", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════
    //  일반사용자 메뉴
    // ═══════════════════════════════════════════════════════
    void buildUserMenuInternal(boolean needUpdate) {
        // 상태바 흰색 복원
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        isOnMenuScreen    = true;
        isOnBalanceScreen = false;
        stopAutoRefresh();
        tvRecentNotice = null;
        tickerFrame    = null;
        if (tickerRunnable != null) {
            tickerHandler.removeCallbacks(tickerRunnable);
            tickerRunnable = null;
        }

        final String BG     = "#F5F3FA";
        final String PURPLE = "#6C5CE7";
        final String TEXT1  = "#1A1A2E";

        // ── 루트 레이아웃 ──────────────────────────────────────
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        layout.setBackgroundColor(Color.parseColor(BG));
        layout.setPadding(0, 0, 0, dpToPx(40));
        layout.setClipChildren(false);
        layout.setClipToPadding(false);

        // ══════════════════════════════════════════════════════
        //  히어로 배경 (그라디언트)
        // ══════════════════════════════════════════════════════
        LinearLayout heroBg = new LinearLayout(this);
        heroBg.setOrientation(LinearLayout.VERTICAL);
        heroBg.setClipChildren(false);
        heroBg.setClipToPadding(false);
        android.graphics.drawable.GradientDrawable heroGrad =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Color.parseColor("#7C6FE0"), Color.parseColor("#9B8FF5")});
        heroGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(24),dpToPx(24),dpToPx(24),dpToPx(24)});
        heroBg.setBackground(heroGrad);
        heroBg.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(10));
        heroBg.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(heroBg, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(dpToPx(16), top + dpToPx(6), dpToPx(16), dpToPx(10));
            return insets;
        });

        // ── 앱 아이콘 + 앱명 (좌측 정렬) ─────────────────────
        LinearLayout appNameRow = new LinearLayout(this);
        appNameRow.setOrientation(LinearLayout.HORIZONTAL);
        appNameRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams anrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        anrLp.setMargins(0, 0, 0, dpToPx(8));
        appNameRow.setLayoutParams(anrLp);

        android.widget.ImageView ivIcon = new android.widget.ImageView(this);
        ivIcon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
        iconLp.setMargins(0, 0, dpToPx(8), 0);
        ivIcon.setLayoutParams(iconLp);
        ivIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        appNameRow.addView(ivIcon);

        // 앱명 — 왼쪽 고정 (flex)
        TextView tvAppName = new TextView(this);
        tvAppName.setText("네이처뷰 경로당");
        tvAppName.setTextColor(Color.WHITE);
        tvAppName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(17));
        tvAppName.setTypeface(null, android.graphics.Typeface.BOLD);
        tvAppName.setShadowLayer(6f, 0f, 2f, 0x40000000);
        tvAppName.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        appNameRow.addView(tvAppName);

        // 최적화 배지 — 오른쪽 끝 고정, 항상 클릭 가능
        boolean batteryOn = isBatteryOptimizationExempt();
        android.graphics.drawable.GradientDrawable batBg =
                new android.graphics.drawable.GradientDrawable();
        batBg.setColor(Color.parseColor(batteryOn ? "#888888" : "#E74C3C"));
        batBg.setCornerRadius(dpToPx(10));
        TextView tvBatBadge = new TextView(this);
        tvBatBadge.setText(batteryOn ? "⚡ 최적화 ON" : "⚡ 최적화 OFF");
        tvBatBadge.setTextColor(Color.WHITE);
        tvBatBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        tvBatBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        tvBatBadge.setBackground(batBg);
        tvBatBadge.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));
        tvBatBadge.setShadowLayer(3f, 0f, 1f, 0x40000000);
        tvBatBadge.setOnClickListener(vv -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        appNameRow.addView(tvBatBadge);
        heroBg.addView(appNameRow);



        // ══════════════════════════════════════════════════════
        //  날씨 인라인 영역 (히어로 배경 안에 직접 표시)
        // ══════════════════════════════════════════════════════
        if (savedInlineWeatherView != null) {
            if (savedInlineWeatherView.getParent() != null)
                ((android.view.ViewGroup) savedInlineWeatherView.getParent())
                        .removeView(savedInlineWeatherView);
            heroBg.addView(savedInlineWeatherView);
        } else {
            LinearLayout inlineWx = buildInlineWeatherView();
            heroBg.addView(inlineWx);
            savedInlineWeatherView = inlineWx;
            loadWeatherInline();
        }

        // ── 잔액 현황 섹션 헤더 ──────────────────────────────
        LinearLayout balTitleRow = new LinearLayout(this);
        balTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        balTitleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btrLp.setMargins(0, dpToPx(14), 0, dpToPx(8));
        balTitleRow.setLayoutParams(btrLp);

        // 왼쪽: 세로바 + 잔액 현황
        LinearLayout balSecLeft = new LinearLayout(this);
        balSecLeft.setOrientation(LinearLayout.HORIZONTAL);
        balSecLeft.setGravity(Gravity.CENTER_VERTICAL);
        balSecLeft.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        View balBar = new View(this);
        android.graphics.drawable.GradientDrawable balBarBg =
                new android.graphics.drawable.GradientDrawable();
        balBarBg.setColor(Color.parseColor("#99FFFFFF"));
        balBarBg.setCornerRadius(dpToPx(3));
        balBar.setBackground(balBarBg);
        balBar.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(3), dpToPx(14)));
        balSecLeft.addView(balBar);
        TextView tvBalSec = new TextView(this);
        tvBalSec.setText("잔액 현황");
        tvBalSec.setTextColor(Color.WHITE);
        tvBalSec.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
        tvBalSec.setTypeface(null, android.graphics.Typeface.BOLD);
        tvBalSec.setShadowLayer(4f, 0f, 1f, 0x50000000);
        LinearLayout.LayoutParams tvBalSecLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvBalSecLp.setMargins(dpToPx(6), 0, 0, 0);
        tvBalSec.setLayoutParams(tvBalSecLp);
        balSecLeft.addView(tvBalSec);
        balTitleRow.addView(balSecLeft);

        // 오른쪽: 잔액 새로고침 — 버튼 직접 추가
        TextView btnBalRefresh = new TextView(this);
        btnBalRefresh.setText("↻  새로고침");
        btnBalRefresh.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnBalRefresh.setTextColor(Color.WHITE);
        btnBalRefresh.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBalRefresh.setGravity(Gravity.CENTER);
        btnBalRefresh.setPadding(dpToPx(12), dpToPx(5), dpToPx(12), dpToPx(5));
        android.graphics.drawable.GradientDrawable balBtnBg =
                new android.graphics.drawable.GradientDrawable();
        balBtnBg.setCornerRadius(dpToPx(10));
        balBtnBg.setColor(Color.parseColor("#8C6CE7"));
        balBtnBg.setAlpha(140);
        balBtnBg.setStroke(dpToPx(3), Color.parseColor("#AAFFFFFF"));
        btnBalRefresh.setBackground(balBtnBg);
        btnBalRefresh.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        balTitleRow.addView(btnBalRefresh);
        // ── 구분선: 그라데이션 (가운데 밝고 양끝 투명) ──────
        android.view.View divider = new android.view.View(this);
        android.graphics.drawable.GradientDrawable divGd =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0x00FFFFFF, 0x80FFFFFF, 0x00FFFFFF});
        divider.setBackground(divGd);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.setMargins(0, dpToPx(6), 0, 0);
        divider.setLayoutParams(divLp);
        heroBg.addView(divider);

        heroBg.addView(balTitleRow);

        // ── 잔액 카드 2×2 ─────────────────────────────────────
        String[][] menuBalInfo = {
                {"5510-13", "운영비",   "#4A90D9", "#EBF4FF"},
                {"5510-83", "부식비",   "#27AE60", "#EAFAF1"},
                {"5510-53", "냉난방비", "#E67E22", "#FEF9E7"},
                {"5510-23", "회비",     "#8E44AD", "#F5EEF8"}
        };
        menuBalTv = new TextView[4];

        LinearLayout balGrid = new LinearLayout(this);
        balGrid.setOrientation(LinearLayout.VERTICAL);
        balGrid.setClipChildren(false);
        balGrid.setClipToPadding(false);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.setMargins(-dpToPx(4), 0, -dpToPx(4), dpToPx(2));
        balGrid.setLayoutParams(gridLp);

        LinearLayout balRow1 = new LinearLayout(this);
        balRow1.setOrientation(LinearLayout.HORIZONTAL);
        balRow1.setClipChildren(false);
        balRow1.setClipToPadding(false);
        LinearLayout.LayoutParams br1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        br1Lp.setMargins(0, 0, 0, dpToPx(3));
        balRow1.setLayoutParams(br1Lp);

        LinearLayout balRow2 = new LinearLayout(this);
        balRow2.setOrientation(LinearLayout.HORIZONTAL);
        balRow2.setClipChildren(false);
        balRow2.setClipToPadding(false);
        balRow2.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setBackground(makeShadowCardDrawable(menuBalInfo[i][3], 14, 6));
            card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cp.setMargins(i % 2 == 0 ? 0 : dpToPx(3), 0, 0, 0);
            card.setLayoutParams(cp);
            card.setPadding(dpToPx(8), dpToPx(16), dpToPx(8), dpToPx(16));

            TextView tvName = new TextView(this);
            tvName.setText("●  " + menuBalInfo[i][1]);
            tvName.setTextColor(Color.parseColor(menuBalInfo[i][2]));
            tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setGravity(Gravity.CENTER);
            tvName.setShadowLayer(3f, 0f, 1f, 0x28000000);
            card.addView(tvName);

            TextView tvVal = new TextView(this);
            tvVal.setText("로딩중...");
            tvVal.setTextColor(Color.parseColor(TEXT1));
            tvVal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(18));
            tvVal.setTypeface(null, Typeface.BOLD);
            tvVal.setGravity(Gravity.CENTER);
            tvVal.setShadowLayer(3f, 0f, 1f, 0x20000000);
            LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            valLp.setMargins(0, dpToPx(3), 0, dpToPx(1));
            tvVal.setLayoutParams(valLp);
            card.addView(tvVal);
            menuBalTv[i] = tvVal;

            final String filterKey = menuBalInfo[i][0];
            card.setOnClickListener(v -> {
                if (isSelectMode) exitSelectMode();
                currentTabFilter = filterKey;
                showBalanceScreen();
            });

            if (i < 2) balRow1.addView(card);
            else        balRow2.addView(card);
        }
        balGrid.addView(balRow1);
        balGrid.addView(balRow2);
        heroBg.addView(balGrid);

        // ── 최근 거래 알림 ticker (반투명 카드 + 컬러 도트) ──
        int tickerH = dpToPx(34);
        tickerFrame = new android.widget.FrameLayout(this);
        tickerFrame.setClipChildren(true);
        tickerFrame.setClipToPadding(true);
        LinearLayout.LayoutParams tickerFLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, tickerH);
        tickerFLp.setMargins(0, dpToPx(6), 0, 0);
        tickerFrame.setLayoutParams(tickerFLp);

        // 반투명 카드 배경
        android.graphics.drawable.GradientDrawable tickerBg =
                new android.graphics.drawable.GradientDrawable();
        tickerBg.setColor(Color.parseColor("#22FFFFFF"));
        tickerBg.setCornerRadius(dpToPx(10));
        tickerBg.setStroke(1, Color.parseColor("#44FFFFFF"));

        // 초기 ticker 행 (컬러 도트 + 텍스트)
        LinearLayout tickerChip = new LinearLayout(this);
        tickerChip.setOrientation(LinearLayout.HORIZONTAL);
        tickerChip.setGravity(Gravity.CENTER_VERTICAL);
        tickerChip.setBackground(tickerBg);
        tickerChip.setPadding(dpToPx(10), 0, dpToPx(10), 0);
        tickerChip.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, tickerH));

        // 도트: FrameLayout으로 흰 도트(큰) 위에 컬러 도트(작은) 겹침
        // → 컬러 도트에 흰 테두리 효과
        android.widget.FrameLayout dotFrame = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams dotFrameLp =
                new android.widget.FrameLayout.LayoutParams(dpToPx(13), dpToPx(13));
        dotFrameLp.setMargins(0, 0, dpToPx(8), 0);
        dotFrameLp.gravity = Gravity.CENTER_VERTICAL;
        dotFrame.setLayoutParams(dotFrameLp);

        // 흰 도트 (뒤, 큰 것 = 흰 테두리 역할)
        android.graphics.drawable.GradientDrawable whiteDotBg =
                new android.graphics.drawable.GradientDrawable();
        whiteDotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        whiteDotBg.setColor(Color.WHITE);
        android.widget.ImageView whiteDot = new android.widget.ImageView(this);
        whiteDot.setBackground(whiteDotBg);
        android.widget.FrameLayout.LayoutParams wdLp =
                new android.widget.FrameLayout.LayoutParams(dpToPx(13), dpToPx(13));
        wdLp.gravity = Gravity.CENTER;
        whiteDot.setLayoutParams(wdLp);
        dotFrame.addView(whiteDot);

        // 컬러 도트 (앞, 작은 것 = 실제 색상)
        android.graphics.drawable.GradientDrawable dotBg =
                new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#2ECC71"));
        android.widget.ImageView dot = new android.widget.ImageView(this);
        dot.setBackground(dotBg);
        dot.setTag("ticker_dot");
        android.widget.FrameLayout.LayoutParams cdLp =
                new android.widget.FrameLayout.LayoutParams(dpToPx(9), dpToPx(9));
        cdLp.gravity = Gravity.CENTER;
        dot.setLayoutParams(cdLp);
        dotFrame.addView(dot);
        tickerChip.addView(dotFrame);

        tvRecentNotice = new TextView(this);
        tvRecentNotice.setText("최근 거래 내역이 없습니다");
        tvRecentNotice.setTextColor(Color.parseColor("#F0FFFFFF"));
        tvRecentNotice.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvRecentNotice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tvRecentNotice.setTypeface(null, Typeface.BOLD);
        tvRecentNotice.setShadowLayer(3f, 0f, 1f, 0x40000000);
        tvRecentNotice.setSingleLine(true);
        tvRecentNotice.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvRecentNotice.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tickerChip.addView(tvRecentNotice);
        tickerFrame.addView(tickerChip);
        heroBg.addView(tickerFrame);
        layout.addView(heroBg);

        // ── 잔액 데이터 로드 ──────────────────────────────────
        if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
            updateMenuBalCards(cachedBlocks);
        } else {
            try {
                readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fileContent) {
                        runOnUiThread(() -> {
                            String[] blocks = fileContent.split(
                                    "-----------------------------------\\r?\\n");
                            java.util.List<String> all = new java.util.ArrayList<>();
                            for (String b : blocks) {
                                if (!b.trim().isEmpty()) all.add(b);
                            }
                            cachedBlocks = all;
                            lastKnownBlockCount = all.size();
                            updateMenuBalCards(cachedBlocks);
                        });
                    }
                    @Override public void onFailure(String error) {
                        runOnUiThread(() -> {
                            for (TextView tv : menuBalTv) tv.setText("-");
                        });
                    }
                });
            } catch (Exception e) {
                for (TextView tv : menuBalTv) tv.setText("-");
            }
        }

        // ══════════════════════════════════════════════════════
        //  바디 (글자크기 → 메뉴 순서)
        // ══════════════════════════════════════════════════════
        LinearLayout bodyLayout = new LinearLayout(this);
        bodyLayout.setOrientation(LinearLayout.VERTICAL);
        bodyLayout.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), 0);
        bodyLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));



        // ── 메뉴 섹션 ─────────────────────────────────────────
        LinearLayout menuSecRow = new LinearLayout(this);
        menuSecRow.setOrientation(LinearLayout.HORIZONTAL);
        menuSecRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams msrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msrLp.setMargins(0, 0, 0, dpToPx(6));
        menuSecRow.setLayoutParams(msrLp);
        View menuBar = new View(this);
        android.graphics.drawable.GradientDrawable menuBarBg =
                new android.graphics.drawable.GradientDrawable();
        menuBarBg.setColor(Color.parseColor(PURPLE));
        menuBarBg.setCornerRadius(dpToPx(4));
        menuBar.setBackground(menuBarBg);
        menuBar.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(4), dpToPx(18)));
        menuSecRow.addView(menuBar);
        TextView tvMenuSec = new TextView(this);
        tvMenuSec.setText("메뉴");
        tvMenuSec.setTextColor(Color.parseColor(TEXT1));
        tvMenuSec.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
        tvMenuSec.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams msTvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msTvLp.setMargins(dpToPx(8), 0, 0, 0);
        tvMenuSec.setLayoutParams(msTvLp);
        menuSecRow.addView(tvMenuSec);
        // menuSecRow는 fontCard 다음에 menuContainer에 추가

        // ── 메뉴 카드들 ───────────────────────────────────────
        String[][] menuItems = {
                {"🚌", "버스 노선 검색",   "버스번호·정류장으로 검색",       "#0984E3", "#EBF5FB"},
                {"💰", "통장 잔액 보기",   "계좌별 문자 내역 상세 확인",    "#6C5CE7", "#EDE9FF"},
                {"🥩", "선결제 잔액 보기", "선결제 입출금 내역을 확인합니다", "#27AE60", "#EAFAF1"},
                {"📊", "월별 지출 통계",   "계좌별 월별 수입/지출 차트",     "#E74C3C", "#FDEDEC"},
                {"📠", "팩스 전송 방법",   "팩스 전송 절차를 확인합니다",    "#E67E22", "#FEF9E7"},
                {"🍱", "식단표",           "이번 달 식단을 확인합니다",      "#4A90D9", "#EBF4FF"},
                {"📋", "경로당 회원명부",  "경로당 회원 명단을 확인합니다",  "#8E44AD", "#F5EEF8"}
        };
        android.view.View.OnClickListener[] menuClicks = {
                v -> showBusSearchScreen(),
                v -> showBalanceScreen(),
                v -> showMeatClubScreen(),
                v -> showStatsScreen(),
                v -> showFaxGuideScreen(),
                v -> showMealPlanScreen(),
                v -> showMemberListScreen()
        };

        LinearLayout menuContainer = new LinearLayout(this);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        menuContainer.setClipChildren(false);
        menuContainer.setClipToPadding(false);
        menuContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 글자 크기 섹션 ────────────────────────────────────
        LinearLayout fontSecRow = new LinearLayout(this);
        fontSecRow.setOrientation(LinearLayout.HORIZONTAL);
        fontSecRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fsrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fsrLp.setMargins(0, 0, 0, dpToPx(5));
        fontSecRow.setLayoutParams(fsrLp);
        View fontBar = new View(this);
        android.graphics.drawable.GradientDrawable fontBarBg =
                new android.graphics.drawable.GradientDrawable();
        fontBarBg.setColor(Color.parseColor(PURPLE));
        fontBarBg.setCornerRadius(dpToPx(4));
        fontBar.setBackground(fontBarBg);
        fontBar.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(4), dpToPx(18)));
        fontSecRow.addView(fontBar);
        TextView tvFontSec = new TextView(this);
        tvFontSec.setText("글자 크기");
        tvFontSec.setTextColor(Color.parseColor(TEXT1));
        tvFontSec.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
        tvFontSec.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tvFsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvFsLp.setMargins(dpToPx(8), 0, 0, 0);
        tvFontSec.setLayoutParams(tvFsLp);
        fontSecRow.addView(tvFontSec);

        // 글자크기 카드
        LinearLayout fontCard = new LinearLayout(this);
        fontCard.setOrientation(LinearLayout.HORIZONTAL);
        fontCard.setGravity(Gravity.CENTER);
        fontCard.setBackground(makeShadowCardDrawable("#FFFFFF", 16, 6));
        fontCard.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams fontCardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(58));
        fontCardLp.setMargins(0, 0, 0, dpToPx(14));
        fontCard.setLayoutParams(fontCardLp);
        fontCard.setPadding(dpToPx(10), 0, dpToPx(10), 0);
        int curLevel = getFontLevel();
        String[] fontLabels = {"작게", "기본", "크게"};
        float[] fontSizes   = {14f, 17f, 21f};
        for (int fontIdx = 0; fontIdx < 3; fontIdx++) {
            final int fLevel = fontIdx;
            boolean isActive = (curLevel == fontIdx);
            android.graphics.drawable.GradientDrawable fBtnBg =
                    new android.graphics.drawable.GradientDrawable();
            fBtnBg.setCornerRadius(dpToPx(12));
            fBtnBg.setColor(Color.parseColor(isActive ? "#6C5CE7" : "#F0EEF8"));
            TextView tvFont = new TextView(this);
            tvFont.setText(fontLabels[fontIdx]);
            tvFont.setTextSize(fontSizes[fontIdx]);
            tvFont.setTypeface(null, android.graphics.Typeface.BOLD);
            tvFont.setTextColor(isActive ? Color.WHITE : Color.parseColor("#6C5CE7"));
            tvFont.setGravity(Gravity.CENTER);
            tvFont.setBackground(fBtnBg);
            if (isActive) tvFont.setShadowLayer(3f, 0f, 1f, 0x40000000);
            LinearLayout.LayoutParams fBtnLp = new LinearLayout.LayoutParams(
                    0, dpToPx(44), 1f);
            fBtnLp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            tvFont.setLayoutParams(fBtnLp);
            tvFont.setOnClickListener(vv -> {
                saveFontLevel(fLevel);
                userMenuBuilder.build(false);
            });
            fontCard.addView(tvFont);
        }
        // ── 메뉴 카드 공통 빌더 헬퍼 (람다 불가 → 인라인) ──────
        // i=0 먼저: 통장 잔액 보기 (글자크기 위)
        {
            int i = 0;
            LinearLayout mc0 = new LinearLayout(this);
            mc0.setOrientation(LinearLayout.HORIZONTAL);
            mc0.setGravity(Gravity.CENTER_VERTICAL);
            mc0.setBackground(makeShadowCardDrawable("#FFFFFF", 16, 6));
            mc0.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams mcLp0 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(68));
            mcLp0.setMargins(0, dpToPx(4), 0, dpToPx(10));
            mc0.setLayoutParams(mcLp0);
            mc0.setPadding(dpToPx(16), 0, dpToPx(16), 0);
            android.graphics.drawable.GradientDrawable iconBg0 =
                    new android.graphics.drawable.GradientDrawable();
            iconBg0.setColor(Color.parseColor(menuItems[i][4]));
            iconBg0.setCornerRadius(dpToPx(12));
            TextView tvIcon0 = new TextView(this);
            tvIcon0.setText(menuItems[i][0]);
            tvIcon0.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22);
            tvIcon0.setGravity(Gravity.CENTER);
            tvIcon0.setBackground(iconBg0);
            tvIcon0.setShadowLayer(4f, 0f, 2f, 0x30000000);
            tvIcon0.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(46), dpToPx(46)));
            mc0.addView(tvIcon0);
            LinearLayout ta0 = new LinearLayout(this);
            ta0.setOrientation(LinearLayout.VERTICAL);
            ta0.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams ta0Lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            ta0Lp.setMargins(dpToPx(14), 0, 0, 0);
            ta0.setLayoutParams(ta0Lp);
            TextView tvT0 = new TextView(this);
            tvT0.setText(menuItems[i][1]);
            tvT0.setTextColor(Color.parseColor(TEXT1));
            tvT0.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
            tvT0.setTypeface(null, Typeface.BOLD);
            tvT0.setShadowLayer(2f, 0f, 1f, 0x18000000);
            ta0.addView(tvT0);
            TextView tvD0 = new TextView(this);
            tvD0.setText(menuItems[i][2]);
            tvD0.setTextColor(Color.parseColor("#9CA3AF"));
            tvD0.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            tvD0.setSingleLine(true);
            tvD0.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams d0Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            d0Lp.setMargins(0, dpToPx(2), 0, 0);
            tvD0.setLayoutParams(d0Lp);
            ta0.addView(tvD0);
            mc0.addView(ta0);
            TextView arr0 = new TextView(this);
            arr0.setText("›");
            arr0.setTextColor(Color.parseColor(PURPLE));
            arr0.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
            arr0.setTypeface(null, Typeface.BOLD);
            arr0.setGravity(Gravity.CENTER_VERTICAL);
            arr0.setShadowLayer(3f, 0f, 1f, 0x30000000);
            mc0.addView(arr0);
            mc0.setOnClickListener(menuClicks[0]);
            menuContainer.addView(mc0);
        }

        // 글자 크기 섹션 (통장잔액 바로 아래)
        menuContainer.addView(fontSecRow);
        menuContainer.addView(fontCard);

        // 메뉴 섹션 타이틀 (선결제 잔액보기 카드 위)
        LinearLayout.LayoutParams menuSecInsertLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        menuSecInsertLp.setMargins(0, dpToPx(4), 0, dpToPx(6));
        menuSecRow.setLayoutParams(menuSecInsertLp);
        menuContainer.addView(menuSecRow);

        // i=1 이후: 선결제~회원명부
        for (int i = 1; i < menuItems.length; i++) {
            final int fi = i;
            LinearLayout mc = new LinearLayout(this);
            mc.setOrientation(LinearLayout.HORIZONTAL);
            mc.setGravity(Gravity.CENTER_VERTICAL);
            mc.setBackground(makeShadowCardDrawable("#FFFFFF", 16, 6));
            mc.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(68));
            mcLp.setMargins(0, dpToPx(4), 0, dpToPx(10));
            mc.setLayoutParams(mcLp);
            mc.setPadding(dpToPx(16), 0, dpToPx(16), 0);

            android.graphics.drawable.GradientDrawable iconBg =
                    new android.graphics.drawable.GradientDrawable();
            iconBg.setColor(Color.parseColor(menuItems[i][4]));
            iconBg.setCornerRadius(dpToPx(12));
            TextView tvIcon = new TextView(this);
            tvIcon.setText(menuItems[i][0]);
            tvIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22);
            tvIcon.setGravity(Gravity.CENTER);
            tvIcon.setBackground(iconBg);
            tvIcon.setShadowLayer(4f, 0f, 2f, 0x30000000);
            LinearLayout.LayoutParams iconMcLp = new LinearLayout.LayoutParams(dpToPx(46), dpToPx(46));
            tvIcon.setLayoutParams(iconMcLp);
            mc.addView(tvIcon);

            LinearLayout textArea = new LinearLayout(this);
            textArea.setOrientation(LinearLayout.VERTICAL);
            textArea.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams taLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            taLp.setMargins(dpToPx(14), 0, 0, 0);
            textArea.setLayoutParams(taLp);
            TextView tvMTitle = new TextView(this);
            tvMTitle.setText(menuItems[i][1]);
            tvMTitle.setTextColor(Color.parseColor(TEXT1));
            tvMTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
            tvMTitle.setTypeface(null, Typeface.BOLD);
            tvMTitle.setShadowLayer(2f, 0f, 1f, 0x18000000);
            textArea.addView(tvMTitle);
            TextView tvMDesc = new TextView(this);
            tvMDesc.setText(menuItems[i][2]);
            tvMDesc.setTextColor(Color.parseColor("#9CA3AF"));
            tvMDesc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            tvMDesc.setSingleLine(true);
            tvMDesc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            descLp.setMargins(0, dpToPx(2), 0, 0);
            tvMDesc.setLayoutParams(descLp);
            textArea.addView(tvMDesc);
            mc.addView(textArea);

            TextView tvArrow = new TextView(this);
            tvArrow.setText("›");
            tvArrow.setTextColor(Color.parseColor(PURPLE));
            tvArrow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
            tvArrow.setTypeface(null, Typeface.BOLD);
            tvArrow.setGravity(Gravity.CENTER_VERTICAL);
            tvArrow.setShadowLayer(3f, 0f, 1f, 0x30000000);
            mc.addView(tvArrow);

            mc.setOnClickListener(menuClicks[i]);
            menuContainer.addView(mc);
        }
        bodyLayout.addView(menuContainer);
        layout.addView(bodyLayout);

        // ── ScrollView + rootView ──────────────────────────────
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor(BG));
        scrollView.setLayoutParams(new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT));
        scrollView.setClipToPadding(false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, insets) -> {
            int bot = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, 0, 0, bot);
            return insets;
        });
        scrollView.addView(layout);

        RelativeLayout rootView = new RelativeLayout(this);
        rootView.setBackgroundColor(Color.parseColor(BG));
        rootView.addView(scrollView);
        rootView.addView(makeUserVersionLabel());
        setContentView(rootView);

        // ── 잔액 새로고침 클릭 ────────────────────────────────
        btnBalRefresh.setOnClickListener(v -> {
            // 로딩중 표시
            btnBalRefresh.setText("로딩중...");
            btnBalRefresh.setEnabled(false);
            for (TextView tv : menuBalTv) tv.setText("...");
            final String[] snapBal = lastMenuBalValues.clone();
            try {
                readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fileContent) {
                        String[] blks = fileContent.split(
                                "-----------------------------------\\r?\\n");
                        java.util.List<String> nb = new java.util.ArrayList<>();
                        for (String b : blks) { if (!b.trim().isEmpty()) nb.add(b); }
                        runOnUiThread(() -> {
                            updateMenuBalCards(nb);
                            int diff = 0;
                            for (int i = 0; i < 4; i++) {
                                if (!snapBal[i].isEmpty()
                                        && !lastMenuBalValues[i].equals(snapBal[i])) diff++;
                            }
                            String msg = diff > 0
                                    ? "최근 거래 내역이 " + diff + "건 있습니다"
                                    : "최근 거래 내역이 없습니다";
                            android.widget.Toast toast = android.widget.Toast.makeText(
                                    PinActivity.this, msg, android.widget.Toast.LENGTH_SHORT);
                            toast.setGravity(android.view.Gravity.CENTER, 0, 0);
                            toast.show();
                            cachedBlocks = nb;
                            btnBalRefresh.setText("↻  새로고침");
                            btnBalRefresh.setEnabled(true);
                        });
                    }
                    @Override public void onFailure(String error) {
                        runOnUiThread(() -> {
                            for (TextView tv : menuBalTv) tv.setText("-");
                            btnBalRefresh.setText("↻  새로고침");
                            btnBalRefresh.setEnabled(true);
                        });
                    }
                });
            } catch (Exception e) {
                for (TextView tv : menuBalTv) tv.setText("-");
                btnBalRefresh.setText("↻  새로고침");
                btnBalRefresh.setEnabled(true);
            }
        });

        // ── 메뉴 화면 잔액 1시간 자동갱신 ──────────────────
        final long ONE_HOUR = 60 * 60 * 1000L;
        refreshRunnable = new Runnable() {
            @Override public void run() {
                if (!isOnMenuScreen) return;
                new Thread(() -> {
                    try {
                        DriveReadHelper vReader = new DriveReadHelper(PinActivity.this);
                        vReader.readFile(VERSION_FILE, new DriveReadHelper.ReadCallback() {
                            @Override public void onSuccess(String content) {
                                String driveVer = content.trim().split("\\r?\\n")[0].trim();
                                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                        .putString("drive_version", driveVer).apply();
                                String myVer = getMyVersion();
                                if (!driveVer.equals(myVer)) {
                                    runOnUiThread(() -> showUpdateRequiredScreen(driveVer));
                                }
                            }
                            @Override public void onFailure(String e) {}
                        });
                    } catch (Exception ignored) {}
                }).start();

                if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
                    updateMenuBalCards(cachedBlocks);
                } else {
                    try {
                        readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                            @Override public void onSuccess(String fileContent) {
                                String[] blocks = fileContent.split(
                                        "-----------------------------------\\r?\\n");
                                java.util.List<String> newBlocks = new java.util.ArrayList<>();
                                for (String b : blocks) {
                                    if (!b.trim().isEmpty()) newBlocks.add(b);
                                }
                                cachedBlocks = newBlocks;
                                runOnUiThread(() -> updateMenuBalCards(cachedBlocks));
                            }
                            @Override public void onFailure(String error) {}
                        });
                    } catch (Exception ignored) {}
                }
                refreshHandler.postDelayed(this, ONE_HOUR);
            }
        };
        refreshHandler.postDelayed(refreshRunnable, ONE_HOUR);
    }

    private void showBalanceScreen() {
        isOnBalanceScreen  = true;
        isOnMenuScreen     = false;
        isShowingFiltered  = false;
        isSelectMode       = false;
        currentTabFilter   = null;
        // memo.txt 로드 (공유 메모 최신화)
        loadMemoFromDrive(() -> {
            if (isOnBalanceScreen && msgContainer != null) renderLatest(displayedCount);
        });
        selectedIdx        = new ArrayList<>();
        pendingSelectIdx   = new ArrayList<>();
        selectActionBar    = null;
        tvSelectCount      = null;
        // cachedBlocks는 유지 — 화면 이동해도 메모리 캐시 보존
        lastKnownBlockCount = cachedBlocks != null ? cachedBlocks.size() : 0;

        // ── 루트: RelativeLayout ──────────────────────────
        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(Color.parseColor("#F5F3FA"));

        // ── 선택모드 액션바 (최상단, 숨김 시작) ──────────
        selectActionBar = new LinearLayout(this);
        LinearLayout actionBar = selectActionBar;
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        actionBar.setBackgroundColor(Color.parseColor("#6C5CE7"));
        actionBar.setPadding(16, statusBarHeight + 8, 16, 8);
        actionBar.setGravity(Gravity.CENTER_VERTICAL);
        actionBar.setVisibility(View.GONE);
        RelativeLayout.LayoutParams abParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, statusBarHeight + dpToPx(48));
        abParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        actionBar.setLayoutParams(abParams);
        int actionBarId = View.generateViewId();
        actionBar.setId(actionBarId);

        tvSelectCount = new TextView(this);
        tvSelectCount.setText("0개 선택");
        tvSelectCount.setTextColor(Color.WHITE);
        tvSelectCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        LinearLayout.LayoutParams scParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvSelectCount.setLayoutParams(scParams);
        actionBar.addView(tvSelectCount);

        Button btnRegister = new Button(this);
        btnRegister.setText("등록");
        styleActionBtn(btnRegister, isOwner ? "#E67E22" : "#C0392B");
        btnRegister.setOnClickListener(v -> registerSelected());
        actionBar.addView(btnRegister);

        Button btnDelete = new Button(this);
        if (isOwner) {
            btnDelete.setText("삭제");
            styleActionBtn(btnDelete, "#C0392B");
            btnDelete.setOnClickListener(v -> deleteSelected());
            actionBar.addView(btnDelete);
        }

        Button btnCancel = new Button(this);
        btnCancel.setText("취소");
        styleActionBtn(btnCancel, "#888888");
        btnCancel.setOnClickListener(v -> exitSelectMode());
        actionBar.addView(btnCancel);

        root.addView(actionBar);

        // ── 상단 헤더 + 잔액 카드 영역 ────────────────────
        LinearLayout topLayout = new LinearLayout(this);
        topLayout.setOrientation(LinearLayout.VERTICAL);
        topLayout.setBackgroundColor(Color.parseColor("#F5F3FA"));
        if (!isOwner) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topLayout, (v, insets) -> {
                int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(0, top + dpToPx(4), 0, dpToPx(4));
                return insets;
            });
        } else {
            topLayout.setPadding(0, statusBarHeight + dpToPx(4), 0, dpToPx(4));
        }
        RelativeLayout.LayoutParams topParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        topParams.addRule(RelativeLayout.BELOW, actionBarId);
        topLayout.setLayoutParams(topParams);
        int topLayoutId = View.generateViewId();
        topLayout.setId(topLayoutId);

        // ── 헤더 그라디언트 바 ───────────────────────────
        final LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable hGrad2 =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#7C6FE0"), Color.parseColor("#9B8FF5")});
        hGrad2.setCornerRadii(new float[]{0,0,0,0,dpToPx(20),dpToPx(20),dpToPx(20),dpToPx(20)});
        headerBar.setBackground(hGrad2);
        headerBar.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hbLp.setMargins(0, 0, 0, dpToPx(12));
        headerBar.setLayoutParams(hbLp);

        TextView tvHeaderIcon = new TextView(this);
        tvHeaderIcon.setText("💳");
        tvHeaderIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        tvHeaderIcon.setPadding(0, 0, dpToPx(10), 0);
        headerBar.addView(tvHeaderIcon);

        LinearLayout headerTxt = new LinearLayout(this);
        headerTxt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerTxt.setLayoutParams(htLp);

        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText("통장 잔액 현황");
        tvHeaderTitle.setTextColor(Color.WHITE);
        tvHeaderTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
        tvHeaderTitle.setTypeface(null, Typeface.BOLD);
        headerTxt.addView(tvHeaderTitle);

        TextView tvHeaderSub = new TextView(this);
        tvHeaderSub.setText("카드를 눌러 계좌별 내역을 확인하세요");
        tvHeaderSub.setTextColor(Color.parseColor("#D4C8FF"));
        tvHeaderSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dpToPx(2), 0, 0);
        tvHeaderSub.setLayoutParams(subLp);
        headerTxt.addView(tvHeaderSub);
        headerBar.addView(headerTxt);

        topLayout.addView(headerBar);

        // ── 잔액 카드 4개 (2열 2행) ──────────────────────
        balInfo = new String[][]{
                {"5510-13", "운영비",   "#4A90D9", "#EBF4FF"},
                {"5510-83", "부식비",   "#27AE60", "#EAFAF1"},
                {"5510-53", "냉난방비", "#E67E22", "#FEF9E7"},
                {"5510-23", "회비",     "#8E44AD", "#F5EEF8"}
        };

        tvBalValues = new TextView[4];
        for (int i = 0; i < 4; i++) {
            tvBalValues[i] = new TextView(this);
        }
        // 카드 생성 직후 캐시에서 즉시 잔액 채우기 (로딩중 표시 없음)
        if (cachedBalValues != null) {
            applyBalanceCache();
        } else if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
            updateBalanceValues(cachedBlocks);
        }

        balCards = new LinearLayout[4];

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setClipChildren(false);
        row1.setClipToPadding(false);
        LinearLayout.LayoutParams r1p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        r1p.setMargins(dpToPx(10), 0, dpToPx(10), dpToPx(2));
        row1.setLayoutParams(r1p);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setClipChildren(false);
        row2.setClipToPadding(false);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        r2p.setMargins(dpToPx(10), 0, dpToPx(10), 0);
        row2.setLayoutParams(r2p);

        for (int i = 0; i < 4; i++) {
            final int bi = i;
            final String filterKey = balInfo[i][0];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setBackground(makeShadowCardDrawable(balInfo[i][3], 16, 5));
            card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            card.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(12));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cp.setMargins(0, 0, i % 2 == 0 ? dpToPx(0) : 0, 0);
            card.setLayoutParams(cp);

            // "● 운영비 잔액" 형태
            TextView tvName = new TextView(this);
            tvName.setText("●  " + balInfo[i][1]);
            tvName.setTextColor(Color.parseColor(balInfo[i][2]));
            tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setGravity(Gravity.CENTER);
            card.addView(tvName);

            // 금액 - 캐시 있으면 즉시 표시, 없으면 로딩중
            tvBalValues[i].setText("-");
            tvBalValues[i].setTextColor(Color.parseColor("#1A1A2E"));
            tvBalValues[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
            tvBalValues[i].setTypeface(null, Typeface.BOLD);
            tvBalValues[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams valLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            valLp2.setMargins(0, dpToPx(3), 0, dpToPx(1));
            tvBalValues[i].setLayoutParams(valLp2);
            card.addView(tvBalValues[i]);

            balCards[bi] = card;

            card.setOnClickListener(v -> {
                // 선택 모드 중 카드 탭 → 선택 모드 해제
                if (isSelectMode) {
                    exitSelectMode();
                    return;
                }
                if (cachedBlocks == null) return;
                if (filterKey.equals(currentTabFilter)) {
                    currentTabFilter = null;
                    updateBalCardColors(balCards, balInfo, -1);
                } else {
                    currentTabFilter = filterKey;
                    updateBalCardColors(balCards, balInfo, bi);
                }
                renderLatest(displayedCount);
                if (msgScrollView != null)
                    msgScrollView.post(() -> msgScrollView.scrollTo(0, 0));
            });

            if (i < 2) row1.addView(card);
            else        row2.addView(card);
        }
        topLayout.addView(row1);
        topLayout.addView(row2);
        root.addView(topLayout);

        // ── 메시지 스크롤 영역 ────────────────────────────
        msgScrollView = new ScrollView(this);
        msgScrollView.setBackgroundColor(Color.parseColor("#F5F3FA"));
        RelativeLayout.LayoutParams msgParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        msgParams.addRule(RelativeLayout.BELOW, topLayoutId);

        msgContainer = new LinearLayout(this);
        msgContainer.setOrientation(LinearLayout.VERTICAL);
        msgContainer.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(80));
        msgContainer.setClipChildren(false);
        msgContainer.setClipToPadding(false);

        TextView tvLoading = new TextView(this);
        tvLoading.setText("문자 불러오는 중...");
        tvLoading.setTextColor(Color.parseColor("#888888"));
        tvLoading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        tvLoading.setGravity(Gravity.CENTER);
        tvLoading.setPadding(0, 40, 0, 0);
        msgContainer.addView(tvLoading);
        msgScrollView.addView(msgContainer);

        // ── 하단 버튼 영역 (돌아가기 + 새로고침) ──────────
        int btnBarId = View.generateViewId();

        if (!isOwner) {
            // 일반사용자: 돌아가기(좌) + 새로고침(우) 나란히
            LinearLayout btnBar = new LinearLayout(this);
            btnBar.setId(btnBarId);
            btnBar.setOrientation(LinearLayout.HORIZONTAL);
            btnBar.setGravity(Gravity.CENTER);

            // 배경에 흰색 카드 + 상단 그림자 효과
            android.graphics.drawable.GradientDrawable btnBarBg =
                    new android.graphics.drawable.GradientDrawable();
            btnBarBg.setColor(Color.parseColor("#F5F3FA"));
            btnBar.setBackground(btnBarBg);
            btnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
            btnBar.setClipChildren(false);
            btnBar.setClipToPadding(false);

            RelativeLayout.LayoutParams barParams = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT);
            barParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            barParams.setMargins(0, 0, 0, 0);
            btnBar.setLayoutParams(barParams);

            // 돌아가기 버튼 (진한 연보라)
            android.graphics.drawable.GradientDrawable backBg2 =
                    new android.graphics.drawable.GradientDrawable();
            Button btnBack = new Button(this);
            btnBack.setText("← 돌아가기");
            btnBack.setBackground(makeShadowCardDrawable("#C8BFEF", 14, 6));
            btnBack.setTextColor(Color.parseColor("#4A3DBF"));
            btnBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            btnBack.setTypeface(null, Typeface.BOLD);
            btnBack.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                    0, dpToPx(50), 1f);
            backLp.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
            btnBack.setLayoutParams(backLp);
            btnBack.setOnClickListener(v -> {
                if (isSelectMode) {
                    exitSelectMode();
                } else if (currentTabFilter != null) {
                    currentTabFilter = null;
                    updateBalCardColors(balCards, balInfo, -1);
                    renderLatest(displayedCount);
                } else {
                    stopAutoRefresh();
                    goBackFromBalance();
                }
            });

            // 선결제 잔액 버튼 (민트-틸 계열)
            Button btnMeatUser = new Button(this);
            btnMeatUser.setText("🥩 선결제 잔액");
            btnMeatUser.setBackground(makeShadowCardDrawable("#EAFAF1", 14, 6));
            btnMeatUser.setTextColor(Color.parseColor("#1A7A4A"));
            btnMeatUser.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            btnMeatUser.setTypeface(null, Typeface.BOLD);
            btnMeatUser.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams meatUserLp = new LinearLayout.LayoutParams(
                    0, dpToPx(50), 1f);
            meatUserLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
            btnMeatUser.setLayoutParams(meatUserLp);
            btnMeatUser.setOnClickListener(v -> {
                if (isSelectMode) exitSelectMode();
                showMeatClubScreen();
            });

            // 차트보기 버튼
            Button btnChartUser = new Button(this);
            btnChartUser.setText("📊 차트보기");
            btnChartUser.setBackground(makeShadowCardDrawable("#FDE8E8", 14, 6));
            btnChartUser.setTextColor(Color.parseColor("#C0392B"));
            btnChartUser.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            btnChartUser.setTypeface(null, Typeface.BOLD);
            btnChartUser.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams chartUserLp = new LinearLayout.LayoutParams(
                    0, dpToPx(50), 1f);
            chartUserLp.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
            btnChartUser.setLayoutParams(chartUserLp);
            btnChartUser.setOnClickListener(v -> {
                stopAutoRefresh();
                showStatsScreen();
            });

            btnMeatUser.setText("🥩 선결제");
            backLp.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));

            btnBar.addView(btnBack);
            btnBar.addView(btnChartUser);
            btnBar.addView(btnMeatUser);
            root.addView(btnBar);

        } else {
            // 관리자: 돌아가기 + 선결제 잔액 버튼
            LinearLayout ownerBtnBar = new LinearLayout(this);
            ownerBtnBar.setId(btnBarId);
            ownerBtnBar.setOrientation(LinearLayout.HORIZONTAL);
            ownerBtnBar.setGravity(Gravity.CENTER);
            ownerBtnBar.setBackgroundColor(Color.parseColor("#F5F3FA"));
            ownerBtnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
            ownerBtnBar.setClipChildren(false);
            ownerBtnBar.setClipToPadding(false);
            RelativeLayout.LayoutParams ownerBarParams = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT);
            ownerBarParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            ownerBarParams.setMargins(0, 0, 0, navBarHeight);
            ownerBtnBar.setLayoutParams(ownerBarParams);

            // 돌아가기 버튼
            Button btnBack = new Button(this);
            btnBack.setText("← 돌아가기");
            btnBack.setBackground(makeShadowCardDrawable("#C8BFEF", 14, 6));
            btnBack.setTextColor(Color.parseColor("#4A3DBF"));
            btnBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            btnBack.setTypeface(null, Typeface.BOLD);
            btnBack.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams backLpO = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
            backLpO.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
            btnBack.setLayoutParams(backLpO);
            btnBack.setOnClickListener(v -> {
                if (isSelectMode) {
                    exitSelectMode();
                } else if (currentTabFilter != null) {
                    currentTabFilter = null;
                    updateBalCardColors(balCards, balInfo, -1);
                    renderLatest(displayedCount);
                } else {
                    stopAutoRefresh();
                    goBackFromBalance();
                }
            });
            ownerBtnBar.addView(btnBack);

            // 차트보기 버튼
            Button btnChartOwner = new Button(this);
            btnChartOwner.setText("📊 차트보기");
            btnChartOwner.setBackground(makeShadowCardDrawable("#FDE8E8", 14, 6));
            btnChartOwner.setTextColor(Color.parseColor("#C0392B"));
            btnChartOwner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            btnChartOwner.setTypeface(null, Typeface.BOLD);
            btnChartOwner.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams chartLpO = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
            chartLpO.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
            btnChartOwner.setLayoutParams(chartLpO);
            btnChartOwner.setOnClickListener(v -> {
                stopAutoRefresh();
                showStatsScreen();
            });
            ownerBtnBar.addView(btnChartOwner);

            // 선결제 버튼
            Button btnMeat = new Button(this);
            btnMeat.setText("🥩 선결제");
            btnMeat.setBackground(makeShadowCardDrawable("#EAFAF1", 14, 6));
            btnMeat.setTextColor(Color.parseColor("#1A7A4A"));
            btnMeat.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            btnMeat.setTypeface(null, Typeface.BOLD);
            btnMeat.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            LinearLayout.LayoutParams meatLpO = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
            meatLpO.setMargins(0, dpToPx(4), 0, dpToPx(4));
            btnMeat.setLayoutParams(meatLpO);
            btnMeat.setOnClickListener(v -> {
                if (isSelectMode) exitSelectMode();
                showMeatClubScreen();
            });
            ownerBtnBar.addView(btnMeat);

            root.addView(ownerBtnBar);
        }

        // msgScrollView ABOVE 버튼 영역
        msgParams.addRule(RelativeLayout.ABOVE, btnBarId);
        msgScrollView.setLayoutParams(msgParams);

        root.addView(msgScrollView);
        setContentView(root);

        // 관리자 하단 버튼 navBar 동적 보정
        if (isOwner) {
            final int fixedBtnBarId = btnBarId;
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                int botInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                android.view.View bar = root.findViewById(fixedBtnBarId);
                if (bar != null) {
                    RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
                    lp.setMargins(0, 0, 0, botInset);
                    bar.setLayoutParams(lp);
                }
                return insets;
            });
        }
        // ── 헤더 자동 사라짐 애니메이션 (2초 표시 → 페이드+축소) ──
        headerBar.post(() -> {
            final int origHeight = headerBar.getMeasuredHeight()
                    + dpToPx(12); // 하단 마진 포함
            // 1.8초 후 애니메이션 시작
            headerBar.postDelayed(() -> {
                android.animation.ValueAnimator anim =
                        android.animation.ValueAnimator.ofFloat(1f, 0f);
                anim.setDuration(600);
                anim.setInterpolator(
                        new android.view.animation.AccelerateDecelerateInterpolator());
                anim.addUpdateListener(va -> {
                    float f = (float) va.getAnimatedValue();
                    headerBar.setAlpha(f);
                    // 높이 + 마진을 줄여서 공간 자연스럽게 축소
                    int h = (int)(origHeight * f);
                    android.view.ViewGroup.LayoutParams vlp = headerBar.getLayoutParams();
                    vlp.height = Math.max(h - dpToPx(12), 0);
                    if (vlp instanceof LinearLayout.LayoutParams) {
                        ((LinearLayout.LayoutParams) vlp).bottomMargin = (int)(dpToPx(12) * f);
                    } else if (vlp instanceof android.widget.FrameLayout.LayoutParams) {
                        ((android.widget.FrameLayout.LayoutParams) vlp).bottomMargin = (int)(dpToPx(12) * f);
                    }
                    headerBar.setLayoutParams(vlp);
                    headerBar.requestLayout();
                });
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator a) {
                        headerBar.setVisibility(View.GONE);
                    }
                });
                anim.start();
            }, 1800);
        });

        // ── Drive에서 데이터 로드 ─────────────────────────
        loadBalanceData(tvBalValues, tvLoading);

        // ── 자동 새로고침: 일반사용자만 1시간 주기 ────────
        // 관리자는 접근성 서비스 문자 수신 시 브로드캐스트로 자동 갱신됨
        if (!isOwner) {
            final long ONE_HOUR = 60 * 60 * 1000L;
            refreshRunnable = new Runnable() {
                @Override public void run() {
                    if (!isOnBalanceScreen) return;
                    incrementalLoad(tvLoading);
                    refreshHandler.postDelayed(this, ONE_HOUR);
                }
            };
            refreshHandler.postDelayed(refreshRunnable, ONE_HOUR);
        }

        // 차단 여부는 30초마다 별도 체크 (일반사용자만)
        if (!isOwner) {
            blockedCheckRunnable = new Runnable() {
                @Override public void run() {
                    if (!isOnBalanceScreen) return;
                    checkBlockedStatus();
                    refreshHandler.postDelayed(this, 30000);
                }
            };
            refreshHandler.postDelayed(blockedCheckRunnable, 30000);
        }
    }

    // ── 잔액 데이터 로드 ───────────────────────────────────
    private void loadBalanceData(TextView[] tvBal, TextView tvLoading) {
        // ── 캐시가 있으면 Drive 읽기 생략 ──────────────────
        if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
            msgContainer.removeView(tvLoading);
            lastKnownBlockCount = cachedBlocks.size();
            displayedCount = Math.min(PAGE_SIZE, cachedBlocks.size());
            // 잔액: cachedBalValues 있으면 적용, 없으면 cachedBlocks에서 파싱
            if (cachedBalValues != null) {
                applyBalanceCache();
            } else {
                updateBalanceValues(cachedBlocks);
            }
            renderLatest(displayedCount);
            return;
        }
        // ── 캐시 없음: Drive에서 전체 읽기 ────────────────
        // 잔액 카드: balance.txt에서 빠르게 로드
        readBalanceFileForBalScreen();
        // 메시지 목록: sms_raw 전체 → 메모리 저장 → 최신 20건만 렌더링
        try {
            readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    String[] blocks = content.split("-----------------------------------\\r?\\n");
                    List<String> all = new ArrayList<>();
                    for (String b : blocks) {
                        if (!b.trim().isEmpty()) all.add(b);
                    }
                    runOnUiThread(() -> {
                        cachedBlocks = all;
                        lastKnownBlockCount = all.size();
                        displayedCount = Math.min(PAGE_SIZE, all.size());
                        msgContainer.removeView(tvLoading);
                        renderLatest(displayedCount);
                    });
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() -> tvLoading.setText("문자를 불러올 수 없습니다"));
                }
            });
        } catch (Exception e) {
            tvLoading.setText("Drive 연결 실패");
        }
    }

    // balance.txt에서 잔액 카드 빠르게 로드 + static 캐시 저장
    private void readBalanceFileForBalScreen() {
        if (tvBalValues == null) return;
        // 캐시 있으면 즉시 적용
        if (cachedBalValues != null) {
            applyBalanceCache();
            return;
        }
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(BALANCE_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    cachedBalValues = content.trim().split("\\r?\\n");
                    runOnUiThread(() -> applyBalanceCache());
                }
                @Override public void onFailure(String error) {
                    // balance.txt 없으면 무시
                }
            });
        } catch (Exception ignored) {}
    }

    // cachedBalValues → tvBalValues에 적용 + 위젯용 SharedPreferences 저장
    private void applyBalanceCache() {
        if (cachedBalValues == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        boolean anyUpdated = false;
        for (String line : cachedBalValues) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            String acct   = parts[0].trim();
            String amount = parts[2].trim();
            String time   = parts.length > 3 ? parts[3].trim() : "";

            // ★ 타임스탬프 비교: balance.txt의 시간이 현재 저장된 시간보다 최신일 때만 덮어씌움
            // Drive 업로드 지연으로 인해 구버전 balance.txt가 최신 SharedPreferences를 덮어쓰는 버그 방지
            String savedTime = prefs.getString("bal_time_" + acct, "");
            if (!savedTime.isEmpty() && !time.isEmpty() && time.compareTo(savedTime) < 0) {
                // balance.txt 값이 더 오래됨 → 위젯/SP는 건드리지 않고 UI만 갱신
                android.util.Log.d("BAL_CACHE", "balance.txt 구버전 스킵: " + acct
                        + " bal.txt=" + time + " saved=" + savedTime);
                // UI는 amount로 표시 (화면 표시는 허용)
                if (tvBalValues != null && balInfo != null) {
                    for (int i = 0; i < balInfo.length; i++) {
                        if (balInfo[i][0].equals(acct) && tvBalValues[i] != null) {
                            // SharedPreferences의 최신값을 UI에 표시
                            String latestAmount = prefs.getString("bal_" + acct, amount);
                            tvBalValues[i].setText(latestAmount);
                        }
                    }
                }
                continue;
            }

            // 위젯용 저장 (최신 값이거나 비교 불가 시 저장)
            editor.putString("bal_" + acct, amount);
            editor.putString("bal_time_" + acct, time);
            anyUpdated = true;

            // UI 업데이트
            if (tvBalValues != null && balInfo != null) {
                for (int i = 0; i < balInfo.length; i++) {
                    if (balInfo[i][0].equals(acct) && tvBalValues[i] != null) {
                        tvBalValues[i].setText(amount);
                    }
                }
            }
        }
        editor.apply();
        // 위젯 갱신 (최신값이 저장된 경우만)
        if (anyUpdated) {
            android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(this);
            int[] ids = awm.getAppWidgetIds(
                    new android.content.ComponentName(this, BalanceWidget.class));
            if (ids != null && ids.length > 0) {
                for (int wid : ids) {
                    BalanceWidget.updateWidget(this, awm, wid);
                }
            }
        } else {
            // balance.txt가 구버전이면 현재 SharedPreferences 값으로 위젯만 갱신 (UI는 이미 최신)
            android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(this);
            int[] ids = awm.getAppWidgetIds(
                    new android.content.ComponentName(this, BalanceWidget.class));
            if (ids != null && ids.length > 0) {
                for (int wid : ids) {
                    BalanceWidget.updateWidget(this, awm, wid);
                }
            }
        }
    }

    // 전체 cachedBlocks에서 최신 n건만 화면에 렌더링 + 더 보기 버튼 처리
    private void renderLatest(int count) {
        if (msgContainer == null || cachedBlocks == null) return;
        int total = cachedBlocks.size();
        int from = Math.max(0, total - count);
        // 전체 리스트 + from 오프셋 전달 → blockIdx가 cachedBlocks 전체 기준으로 정확히 계산됨
        renderMessages(cachedBlocks, currentTabFilter, from);
        // 더 이전 내역이 있으면 더 보기 버튼 추가
        if (from > 0) addLoadMoreButton(count);
    }

    // 더 보기 버튼 — 메모리에서 즉시 추가 20건 표시
    private void addLoadMoreButton(int currentCount) {
        if (msgContainer == null) return;

        Button btnMore = new Button(this);
        btnMore.setText("⬇  이전 내역 더 보기");
        btnMore.setBackground(makeShadowCardDrawable("#EDE9FF", 14, 4));
        btnMore.setTextColor(Color.parseColor("#5B4A8A"));
        btnMore.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnMore.setTypeface(null, Typeface.BOLD);
        btnMore.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50));
        btnLp.setMargins(0, dpToPx(4), 0, dpToPx(16));
        btnMore.setLayoutParams(btnLp);

        btnMore.setOnClickListener(v -> {
            if (isSelectMode) exitSelectMode();
            displayedCount = Math.min(currentCount + PAGE_SIZE, cachedBlocks.size());
            renderLatest(displayedCount);
        });

        msgContainer.addView(btnMore);
    }

    // ── 차단 여부 실시간 확인 ────────────────────────────
    private void checkBlockedStatus() {
        if (isOwner) return;
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(USERS_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    String status = getUserStatus(content, currentUserEmail);
                    if ("차단".equals(status)) {
                        runOnUiThread(() -> {
                            stopAutoRefresh();
                            showBlockedScreen();
                        });
                    }
                }
                @Override public void onFailure(String error) {}
            });
        } catch (Exception ignored) {}
    }

    // ── 삭제 후 강제 재로드 (일반사용자용 - 블록 수 비교 없이 무조건 Drive 읽기) ──
    private void forceReloadAfterDelete() {
        android.util.Log.d("FORCE_RELOAD", "삭제 후 재로드 시작 isOwner=" + isOwner);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fileContent) {
                        runOnUiThread(() -> {
                            String[] blocks = fileContent.split("-----------------------------------\r?\n");
                            List<String> newBlocks = new ArrayList<>();
                            for (String b : blocks) if (!b.trim().isEmpty()) newBlocks.add(b);
                            cachedBlocks = newBlocks;
                            lastKnownBlockCount = newBlocks.size();
                            android.util.Log.d("FORCE_RELOAD", "삭제 후 재로드 완료=" + newBlocks.size() + "개");
                            if (tvBalValues != null) updateBalanceValues(newBlocks);
                            if (isOnBalanceScreen && msgContainer != null) {
                                displayedCount = Math.min(Math.max(displayedCount, PAGE_SIZE), newBlocks.size());
                                renderMessages(newBlocks, currentTabFilter);
                            }
                            if (menuBalTv != null && isOnMenuScreen) updateMenuBalCards(newBlocks);
                        });
                    }
                    @Override public void onFailure(String error) {
                        android.util.Log.e("FORCE_RELOAD", "삭제 후 재로드 실패=" + error);
                    }
                });
            } catch (Exception ignored) {}
        }, 500L); // Drive 캐시 무효화 후라 빠르게 읽기 가능
    }

    // ── 증분 로드 (SMS 수신 브로드캐스트 시 호출) ─────────
    private void forceReloadMessages() {
        if (isDeleting) return;
        cachedBlocks    = null;
        cachedBalValues = null;
        android.util.Log.d("FORCE_RELOAD", "시작 isOwner=" + isOwner + " isOnBalanceScreen=" + isOnBalanceScreen + " isOnMenuScreen=" + isOnMenuScreen);

        // ★ Drive 캐시 무효화 (캐시된 구버전 파일을 읽는 버그 방지)
        int curYear  = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        DriveReadHelper.invalidateCache(SmsReceiver.getSmsRawFile(curYear));
        DriveReadHelper.invalidateCache(SmsReceiver.getSmsRawFile(curYear - 1));

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (isDeleting) return;
            android.util.Log.d("FORCE_RELOAD", "Drive 읽기 시작");
            try {
                readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fileContent) {
                        android.util.Log.d("FORCE_RELOAD", "Drive 읽기 성공 길이=" + fileContent.length());
                        runOnUiThread(() -> {
                            if (isDeleting) return;
                            String[] blocks = fileContent.split("-----------------------------------\r?\n");
                            List<String> newBlocks = new ArrayList<>();
                            for (String b : blocks) if (!b.trim().isEmpty()) newBlocks.add(b);
                            // 블록 수가 이전과 같으면 Drive가 아직 갱신 안된 것 → 3초 후 재시도
                            // (단, 삭제 직후에는 블록 수가 줄어드는 게 정상이므로 재시도 생략)
                            if (!isDeleting && newBlocks.size() == lastKnownBlockCount && lastKnownBlockCount > 0) {
                                android.util.Log.d("FORCE_RELOAD", "블록수 동일(" + newBlocks.size() + ") → 3초 후 재시도");
                                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                    try {
                                        readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                                            @Override public void onSuccess(String fc2) {
                                                runOnUiThread(() -> {
                                                    String[] b2 = fc2.split("-----------------------------------\r?\n");
                                                    List<String> nb2 = new ArrayList<>();
                                                    for (String b : b2) if (!b.trim().isEmpty()) nb2.add(b);
                                                    cachedBlocks = nb2;
                                                    lastKnownBlockCount = nb2.size();
                                                    android.util.Log.d("FORCE_RELOAD", "재시도 성공=" + nb2.size() + "개");
                                                    if (tvBalValues != null) updateBalanceValues(nb2);
                                                    else updateWidgetFromBlocks(nb2);
                                                    if (isOnBalanceScreen && msgContainer != null) renderMessages(nb2, currentTabFilter);
                                                    if (menuBalTv != null && isOnMenuScreen) updateMenuBalCards(nb2);
                                                });
                                            }
                                            @Override public void onFailure(String e) {}
                                        });
                                    } catch (Exception ignored) {}
                                }, 3000L);
                                return;
                            }
                            cachedBlocks = newBlocks;
                            lastKnownBlockCount = newBlocks.size();
                            android.util.Log.d("FORCE_RELOAD", "캐시 갱신=" + newBlocks.size() + "개"
                                    + " isOnBalanceScreen=" + isOnBalanceScreen
                                    + " msgContainer=" + (msgContainer != null)
                                    + " menuBalTv=" + (menuBalTv != null)
                                    + " isOnMenuScreen=" + isOnMenuScreen);
                            if (tvBalValues != null) updateBalanceValues(newBlocks);
                            else updateWidgetFromBlocks(newBlocks); // 화면 없어도 위젯/SP 갱신
                            if (isOnBalanceScreen) {
                                displayedCount = Math.min(Math.max(displayedCount, PAGE_SIZE), newBlocks.size());
                                if (msgContainer != null) renderMessages(newBlocks, currentTabFilter);
                            }
                            if (menuBalTv != null && isOnMenuScreen) updateMenuBalCards(newBlocks);
                        });
                    }
                    @Override public void onFailure(String error) {
                        android.util.Log.e("FORCE_RELOAD", "Drive 읽기 실패=" + error);
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (isDeleting) return;
                            try {
                                readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                                    @Override public void onSuccess(String fc) {
                                        runOnUiThread(() -> {
                                            String[] blocks = fc.split("-----------------------------------\r?\n");
                                            List<String> nb = new ArrayList<>();
                                            for (String b : blocks) if (!b.trim().isEmpty()) nb.add(b);
                                            cachedBlocks = nb;
                                            lastKnownBlockCount = nb.size();
                                            android.util.Log.d("FORCE_RELOAD", "재시도 성공=" + nb.size() + "개");
                                            if (tvBalValues != null) updateBalanceValues(nb);
                                            else updateWidgetFromBlocks(nb);
                                            if (isOnBalanceScreen && msgContainer != null) renderMessages(nb, currentTabFilter);
                                            if (menuBalTv != null && isOnMenuScreen) updateMenuBalCards(nb);
                                        });
                                    }
                                    @Override public void onFailure(String e) {
                                        android.util.Log.e("FORCE_RELOAD", "재시도도 실패=" + e);
                                    }
                                });
                            } catch (Exception ignored) {}
                        }, 2000L);
                    }
                });
            } catch (Exception ignored) {}
        }, 0L);
    }

    private void updateMenuBalCards(List<String> blocks) {
        if (menuBalTv == null) return;
        String[][] latest = {
                {"5510-13", "", ""}, {"5510-83", "", ""},
                {"5510-53", "", ""}, {"5510-23", "", ""}
        };
        for (String block : blocks) {
            String ts = "";
            java.util.regex.Matcher tm = java.util.regex.Pattern
                    .compile("(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})")
                    .matcher(block);
            if (tm.find()) ts = tm.group(1);
            for (String[] info : latest) {
                if (block.contains(info[0])) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("잔액\\s*([\\d,]+)원").matcher(block);
                    if (m.find() && ts.compareTo(info[1]) >= 0) {
                        info[1] = ts;
                        info[2] = m.group(1);
                    }
                }
            }
        }
        String[] names = {"운영비", "부식비", "냉난방비", "회비"};
        int changedNow = 0;
        for (int i = 0; i < 4; i++) {
            final String val = latest[i][2].isEmpty() ? "데이터 없음" : latest[i][2] + "원";
            final int idx = i;
            final String name = names[i];
            // 잔액 변경 감지 → 알림 발송
            if (!latest[i][2].isEmpty() && !val.equals(lastMenuBalValues[i])) {
                if (!lastMenuBalValues[i].isEmpty()) {
                    // 처음 로드가 아닐 때만 알림
                    sendBalanceChangedNotification(name, lastMenuBalValues[i], val);
                    recentChangedCount++;
                    changedNow++;
                }
                lastMenuBalValues[i] = val;
            }
            runOnUiThread(() -> menuBalTv[idx].setText(val));
        }
        // 위젯용 SharedPreferences 저장 (일반사용자도 위젯에 잔액 표시)
        String[] acctKeys = {"5510-13", "5510-83", "5510-53", "5510-23"};
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        for (int i = 0; i < 4; i++) {
            if (!latest[i][2].isEmpty()) {
                editor.putString("bal_" + acctKeys[i], latest[i][2] + "원");
            }
        }
        editor.apply();
        // 위젯 직접 갱신 (브로드캐스트 대신 직접 호출)
        android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(this);
        int[] widgetIds = awm.getAppWidgetIds(
                new android.content.ComponentName(this, BalanceWidget.class));
        if (widgetIds != null && widgetIds.length > 0) {
            for (int wid : widgetIds) {
                BalanceWidget.updateWidget(this, awm, wid);
            }
        }
        // 최근 거래 내역 ticker 업데이트
        final String[][] latestSnap = new String[4][3];
        for (int i = 0; i < 4; i++) latestSnap[i] = latest[i].clone();
        final String[] namesSnap = names.clone();
        runOnUiThread(() -> updateTickerNotices(latestSnap, namesSnap));
    }

    // ── 미트클럽스토어 화면 ─────────────────────────────

    // 미트클럽스토어 전용 상태 변수
    private ScrollView     meatScrollView     = null;
    private LinearLayout   meatMsgContainer   = null;
    private TextView[]     meatBalTv          = new TextView[4];
    private LinearLayout[] meatCards          = new LinearLayout[4];
    private int            meatDisplayedCount = PAGE_SIZE;
    private boolean        isOnMeatScreen     = false;
    private String         meatTabFilter      = null; // null=전체, 아니면 MEAT_SLOTS[i][0] 키워드
    private List<String>   meatBlocks         = null; // 선결제 화면 전용 블록 (sms_raw+prepaid 합산)
    private boolean        meatSelectMode     = false;
    private List<Integer>  meatSelectedIdx    = new ArrayList<>();
    private LinearLayout   meatSelectBar      = null; // 선택 모드 액션바

    // 미트클럽 잔액 슬롯 정의 (키워드, 레이블, 주색, 배경색)
    // 나중에 추가할 가게는 빈 슬롯에 채우면 됨
    private static final String SLOTS_FILE = "slots.txt";

    // 기본 슬롯 (slots.txt 없을 때 폴백)
    private static final String[][] DEFAULT_MEAT_SLOTS = {
            {"미트클럽스토어", "미트클럽", "#27AE60", "#EAFAF1"},
            {"중도매인43번",    "중도매인43번", "#E67E22", "#FEF9E7"},
            {"",               "준비중",   "#AAAAAA", "#F5F5F5"},
            {"",               "준비중",   "#AAAAAA", "#F5F5F5"},
    };

    // 런타임 슬롯 (Drive slots.txt에서 읽어 채움, 항상 4칸)
    private String[][] MEAT_SLOTS = copySlots(DEFAULT_MEAT_SLOTS);

    private String[][] copySlots(String[][] src) {
        String[][] dst = new String[4][4];
        for (int i = 0; i < 4; i++) {
            if (i < src.length) dst[i] = src[i].clone();
            else dst[i] = new String[]{"", "준비중", "#AAAAAA", "#F5F5F5"};
        }
        return dst;
    }

    /** slots.txt를 Drive에서 읽어 MEAT_SLOTS 갱신 후 콜백 */
    private void loadSlotsFromDrive(Runnable onDone) {
        new Thread(() -> {
            try {
                DriveReadHelper r = new DriveReadHelper(this);
                r.readFile(SLOTS_FILE, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String content) {
                        String[][] loaded = new String[4][4];
                        for (int i = 0; i < 4; i++)
                            loaded[i] = new String[]{"", "준비중", "#AAAAAA", "#F5F5F5"};
                        int idx = 0;
                        for (String line : content.split("\\r?\\n")) {
                            if (line.trim().isEmpty() || idx >= 4) continue;
                            String[] p = line.split("\\|", -1);
                            loaded[idx][0] = p.length > 0 ? p[0].trim() : "";
                            loaded[idx][1] = p.length > 1 ? p[1].trim() : "준비중";
                            loaded[idx][2] = p.length > 2 ? p[2].trim() : "#AAAAAA";
                            loaded[idx][3] = p.length > 3 ? p[3].trim() : "#F5F5F5";
                            idx++;
                        }
                        MEAT_SLOTS = loaded;
                        runOnUiThread(() -> { if (onDone != null) onDone.run(); });
                    }
                    @Override public void onFailure(String e) {
                        // slots.txt 없으면 기본값 유지
                        runOnUiThread(() -> { if (onDone != null) onDone.run(); });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (onDone != null) onDone.run(); });
            }
        }).start();
    }

    /** 현재 MEAT_SLOTS를 slots.txt로 Drive에 저장 */
    private void saveSlotsToDrive(Runnable onDone) {
        StringBuilder sb = new StringBuilder();
        for (String[] slot : MEAT_SLOTS) {
            if (slot[0].isEmpty() && slot[1].equals("준비중")) continue; // 빈 슬롯 저장 안 함
            sb.append(slot[0]).append("|").append(slot[1]).append("|")
                    .append(slot[2]).append("|").append(slot[3]).append("\n");
        }
        final String content = sb.toString().trim();
        new Thread(() -> {
            try {
                DriveUploadHelper up = new DriveUploadHelper(this);
                up.uploadFileSync(content, SLOTS_FILE);
                DriveReadHelper.invalidateCache(SLOTS_FILE);
                runOnUiThread(() -> { if (onDone != null) onDone.run(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showMeatClubScreen() {
        isOnMeatScreen     = true;
        isOnSubScreen      = true;
        isOnMenuScreen     = false;
        isOnBalanceScreen  = false;
        meatDisplayedCount = PAGE_SIZE;
        meatTabFilter      = null;
        meatBlocks         = null;
        meatSelectMode     = false;
        meatSelectedIdx    = new ArrayList<>();
        meatSelectBar      = null;

        // slots.txt 로드 후 화면 구성
        loadSlotsFromDrive(() -> buildMeatClubScreen());
    }

    private void buildMeatClubScreen() {        // ── 루트 ──────────────────────────────────────────
        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(Color.parseColor("#F5F3FA"));

        // ── 선택 모드 액션바 (최상단 고정, 처음엔 숨김) ──
        meatSelectBar = new LinearLayout(this);
        LinearLayout meatActionBar = meatSelectBar;
        meatActionBar.setOrientation(LinearLayout.HORIZONTAL);
        meatActionBar.setBackgroundColor(Color.parseColor("#6C5CE7"));
        meatActionBar.setPadding(16, statusBarHeight + 8, 16, 8);
        meatActionBar.setGravity(Gravity.CENTER_VERTICAL);
        meatActionBar.setVisibility(View.GONE);
        int meatActionBarId = View.generateViewId();
        meatActionBar.setId(meatActionBarId);
        RelativeLayout.LayoutParams mabParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, statusBarHeight + dpToPx(48));
        mabParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        meatActionBar.setLayoutParams(mabParams);

        // "N개 선택" 텍스트
        TextView tvMeatSelectCount = new TextView(this);
        tvMeatSelectCount.setText("0개 선택");
        tvMeatSelectCount.setTextColor(Color.WHITE);
        tvMeatSelectCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        LinearLayout.LayoutParams mscLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvMeatSelectCount.setLayoutParams(mscLp);
        meatActionBar.addView(tvMeatSelectCount);

        // 삭제 버튼
        Button btnMeatDelete = new Button(this);
        btnMeatDelete.setText("삭제");
        styleActionBtn(btnMeatDelete, "#C0392B");
        meatActionBar.addView(btnMeatDelete);

        // 취소 버튼
        Button btnMeatCancel = new Button(this);
        btnMeatCancel.setText("취소");
        styleActionBtn(btnMeatCancel, "#888888");
        btnMeatCancel.setOnClickListener(v -> {
            meatSelectMode = false;
            meatSelectedIdx = new ArrayList<>();
            meatActionBar.setVisibility(View.GONE);
            if (meatBlocks != null) renderMeatMessages(meatBlocks, meatDisplayedCount);
        });
        meatActionBar.addView(btnMeatCancel);
        root.addView(meatActionBar);

        // ── 하단 버튼 영역 (돌아가기 + 새로고침) ──────────
        int btnBarId = View.generateViewId();
        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setId(btnBarId);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.CENTER);
        btnBar.setBackgroundColor(Color.parseColor("#F5F3FA"));
        btnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        btnBar.setClipChildren(false);
        btnBar.setClipToPadding(false);
        RelativeLayout.LayoutParams barLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        barLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        barLp.setMargins(0, 0, 0, 0);
        btnBar.setLayoutParams(barLp);

        // 돌아가기 버튼
        Button btnBack = new Button(this);
        btnBack.setText("← 돌아가기");
        btnBack.setBackground(makeShadowCardDrawable("#C8BFEF", 14, 6));
        btnBack.setTextColor(Color.parseColor("#4A3DBF"));
        btnBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnBack.setTypeface(null, Typeface.BOLD);
        btnBack.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        backLp.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
        btnBack.setLayoutParams(backLp);
        btnBack.setOnClickListener(v -> {
            if (meatSelectMode) {
                meatSelectMode = false;
                meatSelectedIdx = new ArrayList<>();
                if (meatSelectBar != null) meatSelectBar.setVisibility(View.GONE);
                if (meatBlocks != null) renderMeatMessages(meatBlocks, meatDisplayedCount);
            } else if (meatTabFilter != null) {
                meatTabFilter = null;
                updateMeatCardColors();
                meatDisplayedCount = PAGE_SIZE;
                if (meatBlocks != null)
                    renderMeatMessages(meatBlocks, meatDisplayedCount);
            } else {
                isOnMeatScreen = false;
                isOnSubScreen  = false;
                if (isOwner) ownerMenuBuilder.build();
                else userMenuBuilder.build(false);
            }
        });

        // 새로고침 버튼
        Button btnRefresh = new Button(this);
        btnRefresh.setText("🔄  새로고침");
        btnRefresh.setBackground(makeShadowCardDrawable("#B8E4DC", 14, 6));
        btnRefresh.setTextColor(Color.parseColor("#1A7A63"));
        btnRefresh.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnRefresh.setTypeface(null, Typeface.BOLD);
        btnRefresh.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        refreshLp.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
        btnRefresh.setLayoutParams(refreshLp);
        btnRefresh.setOnClickListener(v -> {
            if (meatSelectMode) {
                meatSelectMode = false;
                meatSelectedIdx = new ArrayList<>();
                if (meatSelectBar != null) meatSelectBar.setVisibility(View.GONE);
            }
            final android.view.View refOverlay = showLoadingOverlay(root, "불러오는 중...");
            readMeatSmsRaw(new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    String[] blks = content.split("-----------------------------------\r?\n");
                    List<String> nb = new ArrayList<>();
                    for (String b : blks) { if (!b.trim().isEmpty()) nb.add(b); }
                    runOnUiThread(() -> {
                        meatBlocks = nb;
                        meatDisplayedCount = Math.min(PAGE_SIZE, nb.size());
                        root.removeView(refOverlay);
                        updateMeatBalCards(nb);
                        updateMeatCardColors();
                        renderMeatMessages(nb, meatDisplayedCount);
                    });
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() -> root.removeView(refOverlay));
                }
            });
        });

        btnBar.addView(btnBack);
        btnBar.addView(btnRefresh);

        // 구매 버튼
        Button btnPurchase = new Button(this);
        btnPurchase.setText("🛒 구매");
        btnPurchase.setBackground(makeShadowCardDrawable("#C0392B", 14, 6));
        btnPurchase.setTextColor(Color.WHITE);
        btnPurchase.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnPurchase.setTypeface(null, Typeface.BOLD);
        btnPurchase.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams purchaseLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        purchaseLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
        btnPurchase.setLayoutParams(purchaseLp);
        btnPurchase.setOnClickListener(v -> {
            if (meatSelectMode) {
                meatSelectMode = false;
                meatSelectedIdx = new ArrayList<>();
                if (meatSelectBar != null) meatSelectBar.setVisibility(View.GONE);
                if (meatBlocks != null) renderMeatMessages(meatBlocks, meatDisplayedCount);
            }
            showPurchaseDialog();
        });
        btnBar.addView(btnPurchase);

        root.addView(btnBar);

        // ── 상단 헤더 + 잔액 카드 영역 ────────────────────
        LinearLayout topLayout = new LinearLayout(this);
        topLayout.setOrientation(LinearLayout.VERTICAL);
        topLayout.setBackgroundColor(Color.parseColor("#F5F3FA"));
        if (!isOwner) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(topLayout, (v, insets) -> {
                int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(0, top + dpToPx(4), 0, dpToPx(4));
                return insets;
            });
        } else {
            topLayout.setPadding(0, statusBarHeight + dpToPx(4), 0, dpToPx(4));
        }
        int topLayoutId = View.generateViewId();
        topLayout.setId(topLayoutId);
        RelativeLayout.LayoutParams topLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        topLp.addRule(RelativeLayout.BELOW, meatActionBarId);
        topLayout.setLayoutParams(topLp);

        // ── 헤더 그라디언트 바 ───────────────────────────
        final LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable hGrad =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#27AE60"), Color.parseColor("#52D68A")});
        hGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(20),dpToPx(20),dpToPx(20),dpToPx(20)});
        headerBar.setBackground(hGrad);
        headerBar.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hbLp.setMargins(0, 0, 0, dpToPx(12));
        headerBar.setLayoutParams(hbLp);

        TextView tvHIcon = new TextView(this);
        tvHIcon.setText("🥩");
        tvHIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        tvHIcon.setPadding(0, 0, dpToPx(10), 0);
        headerBar.addView(tvHIcon);

        LinearLayout headerTxt = new LinearLayout(this);
        headerTxt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerTxt.setLayoutParams(htLp);

        TextView tvHTitle = new TextView(this);
        tvHTitle.setText("선결제 잔액 내역");
        tvHTitle.setTextColor(Color.WHITE);
        tvHTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(17));
        tvHTitle.setTypeface(null, Typeface.BOLD);
        headerTxt.addView(tvHTitle);

        TextView tvHSub = new TextView(this);
        tvHSub.setText("입금 및 출금 내역을 확인합니다");
        tvHSub.setTextColor(Color.parseColor("#C8F7DC"));
        tvHSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dpToPx(2), 0, 0);
        tvHSub.setLayoutParams(subLp);
        headerTxt.addView(tvHSub);
        headerBar.addView(headerTxt);
        topLayout.addView(headerBar);

        // ── 잔액 카드 4개 (2열 2행) ──────────────────────
        for (int i = 0; i < 4; i++) meatBalTv[i] = new TextView(this);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setClipChildren(false);
        row1.setClipToPadding(false);
        LinearLayout.LayoutParams r1p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r1p.setMargins(dpToPx(10), 0, dpToPx(10), dpToPx(2));
        row1.setLayoutParams(r1p);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setClipChildren(false);
        row2.setClipToPadding(false);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2p.setMargins(dpToPx(10), 0, dpToPx(10), 0);
        row2.setLayoutParams(r2p);

        for (int i = 0; i < 4; i++) {
            final int fi = i;
            boolean hasData = !MEAT_SLOTS[i][0].isEmpty();
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setBackground(makeShadowCardDrawable(MEAT_SLOTS[i][3], 16, 5));
            card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            card.setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(12));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            card.setLayoutParams(cp);
            meatCards[i] = card;

            TextView tvName = new TextView(this);
            tvName.setText("●  " + MEAT_SLOTS[i][1]);
            tvName.setTextColor(Color.parseColor(MEAT_SLOTS[i][2]));
            tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setGravity(Gravity.CENTER);
            card.addView(tvName);

            meatBalTv[i].setText(hasData ? "-" : "");
            meatBalTv[i].setTextColor(Color.parseColor(hasData ? "#1A1A2E" : "#CCCCCC"));
            meatBalTv[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(17));
            meatBalTv[i].setTypeface(null, Typeface.BOLD);
            meatBalTv[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            valLp.setMargins(0, dpToPx(3), 0, dpToPx(1));
            meatBalTv[i].setLayoutParams(valLp);
            card.addView(meatBalTv[i]);

            // 탭 필터: 데이터 있는 슬롯만 클릭 가능
            if (hasData) {
                card.setOnClickListener(v -> {
                    // 선택 모드 중 카드 탭 → 선택 모드 해제
                    if (meatSelectMode) {
                        meatSelectMode = false;
                        meatSelectedIdx = new ArrayList<>();
                        if (meatSelectBar != null) meatSelectBar.setVisibility(View.GONE);
                        if (meatBlocks != null) renderMeatMessages(meatBlocks, meatDisplayedCount);
                        return;
                    }
                    String keyword = MEAT_SLOTS[fi][0];
                    if (keyword.equals(meatTabFilter)) {
                        meatTabFilter = null;
                    } else {
                        meatTabFilter = keyword;
                    }
                    updateMeatCardColors();
                    meatDisplayedCount = PAGE_SIZE;
                    if (meatBlocks != null)
                        renderMeatMessages(meatBlocks, meatDisplayedCount);
                    if (meatScrollView != null)
                        meatScrollView.post(() -> meatScrollView.scrollTo(0, 0));
                });
            }

            if (i < 2) row1.addView(card);
            else        row2.addView(card);
        }
        topLayout.addView(row1);
        topLayout.addView(row2);
        root.addView(topLayout);

        // ── 메시지 스크롤 영역 ────────────────────────────
        meatScrollView = new ScrollView(this);
        meatScrollView.setBackgroundColor(Color.parseColor("#F5F3FA"));
        RelativeLayout.LayoutParams msgLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        msgLp.addRule(RelativeLayout.BELOW, topLayoutId);
        msgLp.addRule(RelativeLayout.ABOVE, btnBarId);
        meatScrollView.setLayoutParams(msgLp);

        meatMsgContainer = new LinearLayout(this);
        meatMsgContainer.setOrientation(LinearLayout.VERTICAL);
        meatMsgContainer.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(16));
        meatMsgContainer.setClipChildren(false);
        meatMsgContainer.setClipToPadding(false);

        meatScrollView.addView(meatMsgContainer);
        root.addView(meatScrollView);

        setContentView(root);

        // 화면 중앙 로딩 오버레이 표시
        final android.view.View loadingOverlay = showLoadingOverlay(root, "불러오는 중...");

        // 하단 버튼바 navBar 보정
        final int fixedBtnBarId = btnBarId;
        if (isOwner) {
            // 관리자: 통장잔액과 동일하게 navBarHeight 직접 적용
            android.view.View bar = root.findViewById(fixedBtnBarId);
            if (bar != null) {
                RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
                lp.setMargins(0, 0, 0, navBarHeight);
                bar.setLayoutParams(lp);
            }
        } else {
            // 일반사용자: WindowInsets 동적 보정
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                int botInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                android.view.View bar = root.findViewById(fixedBtnBarId);
                if (bar != null) {
                    RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
                    lp.setMargins(0, 0, 0, botInset);
                    bar.setLayoutParams(lp);
                }
                return insets;
            });
        }

        // 삭제 버튼 리스너 (meatBlocks 참조 필요해서 setContentView 후 연결)
        btnMeatDelete.setOnClickListener(v -> {
            if (meatSelectedIdx.isEmpty()) return;
            android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                    .setMessage("삭제 하시겠습니까?")
                    .setPositiveButton("확인", (d, w) ->
                            deleteMeatSelected(meatActionBar, tvMeatSelectCount))
                    .setNegativeButton("취소", null)
                    .create();
            dlg.show();
            // 버튼 색상 커스텀
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(Color.parseColor("#C0392B"));
            dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(Color.parseColor("#888888"));
        });

        // ── 헤더 자동 페이드 (1.8초 후) ─────────────────
        headerBar.post(() -> {
            final int origH = headerBar.getMeasuredHeight() + dpToPx(12);
            headerBar.postDelayed(() -> {
                android.animation.ValueAnimator anim =
                        android.animation.ValueAnimator.ofFloat(1f, 0f);
                anim.setDuration(600);
                anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                anim.addUpdateListener(va -> {
                    float f = (float) va.getAnimatedValue();
                    headerBar.setAlpha(f);
                    int h = (int)(origH * f);
                    android.view.ViewGroup.LayoutParams vlp2 = headerBar.getLayoutParams();
                    vlp2.height = Math.max(h - dpToPx(12), 0);
                    if (vlp2 instanceof LinearLayout.LayoutParams) {
                        ((LinearLayout.LayoutParams) vlp2).bottomMargin = (int)(dpToPx(12) * f);
                    } else if (vlp2 instanceof android.widget.FrameLayout.LayoutParams) {
                        ((android.widget.FrameLayout.LayoutParams) vlp2).bottomMargin = (int)(dpToPx(12) * f);
                    }
                    headerBar.setLayoutParams(vlp2);
                    headerBar.requestLayout();
                });
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        headerBar.setVisibility(View.GONE);
                    }
                });
                anim.start();
            }, 1800);
        });

        // ── 데이터 로드 ───────────────────────────────────
        // cachedBlocks는 sms_raw 전체 캐시이므로 미트클럽 화면은 항상 별도로 읽음
        readMeatSmsRaw(new DriveReadHelper.ReadCallback() {
            @Override public void onSuccess(String content) {
                String[] blks = content.split("-----------------------------------\r?\n");
                List<String> nb = new ArrayList<>();
                for (String b : blks) { if (!b.trim().isEmpty()) nb.add(b); }
                runOnUiThread(() -> {
                    meatBlocks = nb;                          // 선결제 전용 저장
                    meatDisplayedCount = Math.min(PAGE_SIZE, nb.size());
                    root.removeView(loadingOverlay);
                    updateMeatBalCards(nb);
                    updateMeatCardColors();
                    renderMeatMessages(nb, meatDisplayedCount);
                });
            }
            @Override public void onFailure(String error) {
                runOnUiThread(() -> {
                    root.removeView(loadingOverlay);
                    TextView tvErr = new TextView(PinActivity.this);
                    tvErr.setText("불러오기 실패: " + error);
                    tvErr.setTextColor(Color.parseColor("#C0392B"));
                    tvErr.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
                    tvErr.setGravity(Gravity.CENTER);
                    tvErr.setPadding(0, dpToPx(40), 0, 0);
                    meatMsgContainer.addView(tvErr);
                });
            }
        });
    }

    /** 선결제 화면 선택 블록 삭제 */
    private void deleteMeatSelected(LinearLayout actionBar, TextView tvCount) {
        if (meatSelectedIdx.isEmpty() || meatBlocks == null) return;

        // filtered를 renderMeatMessages의 현재 상태와 동일하게 재계산
        // meatTabFilter 유무에 따라 동일하게 분기
        java.util.regex.Pattern prepaidPat =
                java.util.regex.Pattern.compile("선입금\\s*([\\d,]+)원");
        List<String> filtered = new ArrayList<>();

        if (meatTabFilter != null) {
            List<String> slotBlocks = new ArrayList<>();
            for (String b : meatBlocks) {
                if (b.contains(meatTabFilter) && (b.contains("선입금") || b.contains("구매")))
                    slotBlocks.add(b);
            }
            slotBlocks.sort((a, b2) -> extractTimestamp(a).compareTo(extractTimestamp(b2)));
            long cumTotal = 0;
            List<String> cumBlocks = new ArrayList<>();
            for (String b : slotBlocks) {
                for (String line : b.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.contains("선입금") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = prepaidPat.matcher(t);
                        if (lm.find()) { try { cumTotal += Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    } else if (t.contains("구매") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = java.util.regex.Pattern.compile("구매\\s*([\\d,]+)원").matcher(t);
                        if (lm.find()) { try { cumTotal -= Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    }
                }
                cumBlocks.add(injectTotalBalance(b, cumTotal));
            }
            for (int i = cumBlocks.size() - 1; i >= 0; i--) filtered.add(cumBlocks.get(i));
        } else {
            java.util.Map<String, Long> slotCum = new java.util.LinkedHashMap<>();
            for (String[] slot : MEAT_SLOTS) {
                if (!slot[0].isEmpty()) slotCum.put(slot[0], 0L);
            }
            List<String> allSlotBlocks = new ArrayList<>();
            for (String b : meatBlocks) {
                for (String[] slot : MEAT_SLOTS) {
                    if (!slot[0].isEmpty() && b.contains(slot[0])
                            && (b.contains("선입금") || b.contains("구매"))) {
                        allSlotBlocks.add(b); break;
                    }
                }
            }
            allSlotBlocks.sort((a, b2) -> extractTimestamp(a).compareTo(extractTimestamp(b2)));
            List<String> cumBlocks = new ArrayList<>();
            for (String b : allSlotBlocks) {
                String bSlot = "";
                for (String[] slot : MEAT_SLOTS) {
                    if (!slot[0].isEmpty() && b.contains(slot[0])) { bSlot = slot[0]; break; }
                }
                if (bSlot.isEmpty()) continue;
                long cum = slotCum.get(bSlot);
                for (String line : b.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.contains("선입금") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = prepaidPat.matcher(t);
                        if (lm.find()) { try { cum += Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    } else if (t.contains("구매") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = java.util.regex.Pattern.compile("구매\\s*([\\d,]+)원").matcher(t);
                        if (lm.find()) { try { cum -= Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    }
                }
                slotCum.put(bSlot, cum);
                cumBlocks.add(injectTotalBalance(b, cum));
            }
            for (int i = cumBlocks.size() - 1; i >= 0; i--) filtered.add(cumBlocks.get(i));
        }

        // 선택된 인덱스 → 타임스탬프 수집
        java.util.Set<String> toDeleteTs = new java.util.HashSet<>();
        for (int idx : meatSelectedIdx) {
            if (idx >= 0 && idx < filtered.size()) {
                String ts = extractTimestamp(filtered.get(idx));
                android.util.Log.d("DELETE_MEAT", "삭제 예정 ts=" + ts + " / idx=" + idx);
                if (!ts.isEmpty()) toDeleteTs.add(ts);
            }
        }
        if (toDeleteTs.isEmpty()) {
            Toast.makeText(this, "삭제할 항목을 찾지 못했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(PinActivity.this);
                final String[] existing = {""};
                final Object lock = new Object();
                final boolean[] done = {false};
                reader.readFile("prepaid.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String c) {
                        synchronized (lock) { existing[0] = c; done[0] = true; lock.notifyAll(); }
                    }
                    @Override public void onFailure(String e) {
                        synchronized (lock) { existing[0] = ""; done[0] = true; lock.notifyAll(); }
                    }
                });
                synchronized (lock) { while (!done[0]) lock.wait(5000); }

                List<String> remaining = new ArrayList<>();
                for (String b : existing[0].split("-----------------------------------\r?\n")) {
                    if (b.trim().isEmpty()) continue;
                    if (!toDeleteTs.contains(extractTimestamp(b.trim()))) remaining.add(b.trim());
                }

                StringBuilder fileSb = new StringBuilder();
                for (String b : remaining) fileSb.append(b).append("\n-----------------------------------\n");

                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                up.uploadFileSync(fileSb.toString(), "prepaid.txt");
                DriveReadHelper.invalidateCache("prepaid.txt");

                runOnUiThread(() -> {
                    meatSelectMode = false;
                    meatSelectedIdx = new ArrayList<>();
                    if (actionBar != null) actionBar.setVisibility(View.GONE);
                    Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                    readMeatSmsRaw(new DriveReadHelper.ReadCallback() {
                        @Override public void onSuccess(String content) {
                            String[] blks = content.split("-----------------------------------\r?\n");
                            List<String> nb = new ArrayList<>();
                            for (String b : blks) { if (!b.trim().isEmpty()) nb.add(b); }
                            runOnUiThread(() -> {
                                meatBlocks = nb;
                                updateMeatBalCards(nb);
                                updateMeatCardColors();
                                renderMeatMessages(nb, meatDisplayedCount);
                            });
                        }
                        @Override public void onFailure(String e) {}
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "삭제 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 구매 다이얼로그: 슬롯 카드 선택 → 금액 입력 → 저장 */
    private void showPurchaseDialog() {
        // 데이터 있는 슬롯만 추출 + 현재 잔액 계산
        List<String[]> activeSlots = new ArrayList<>();
        for (String[] slot : MEAT_SLOTS) {
            if (!slot[0].isEmpty()) activeSlots.add(slot);
        }
        if (activeSlots.isEmpty()) {
            Toast.makeText(this, "등록된 선결제 가게가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 슬롯별 현재 잔액 계산
        long[] slotBalances = new long[activeSlots.size()];
        if (meatBlocks != null) {
            for (int si = 0; si < activeSlots.size(); si++) {
                String keyword = activeSlots.get(si)[0];
                long bal = 0;
                List<String> slotBlocks = new ArrayList<>();
                for (String b : meatBlocks) {
                    if (b.contains(keyword) && (b.contains("선입금") || b.contains("구매"))) slotBlocks.add(b);
                }
                slotBlocks.sort((a, b2) -> extractTimestamp(a).compareTo(extractTimestamp(b2)));
                for (String b : slotBlocks) {
                    for (String line : b.split("\\r?\\n")) {
                        String t = line.trim();
                        if (t.contains("선입금") && !t.contains("잔액")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("선입금\\s*([\\d,]+)원").matcher(t);
                            if (m.find()) { try { bal += Long.parseLong(m.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                            break;
                        } else if (t.contains("구매") && !t.contains("잔액")) {
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("구매\\s*([\\d,]+)원").matcher(t);
                            if (m.find()) { try { bal -= Long.parseLong(m.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                            break;
                        }
                    }
                }
                slotBalances[si] = bal;
            }
        }

        // ── 커스텀 다이얼로그 레이아웃 ───────────────────
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // 화면 상단 고정 - 키보드와 무관하게 위치 고정
            android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
            wlp.gravity = android.view.Gravity.TOP;
            wlp.y = 0; // 상단에 딱 붙게
            wlp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            wlp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            dialog.getWindow().setAttributes(wlp);
            // 키보드가 올라와도 다이얼로그 위치 절대 고정
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }

        ScrollView dlgScroll = new ScrollView(this);
        LinearLayout dlgRoot = new LinearLayout(this);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setBackgroundColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable dlgBg = new android.graphics.drawable.GradientDrawable();
        dlgBg.setColor(Color.WHITE);
        dlgBg.setCornerRadii(new float[]{dpToPx(20),dpToPx(20),dpToPx(20),dpToPx(20),0,0,0,0});
        dlgRoot.setBackground(dlgBg);
        dlgRoot.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));

        // 가게 카드 목록
        final int[] selectedSlotIdx = {0};
        final LinearLayout[] slotCards = new LinearLayout[activeSlots.size()];

        for (int si = 0; si < activeSlots.size(); si++) {
            final int fi = si;
            String[] slot = activeSlots.get(si);
            long bal = slotBalances[si];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(makeShadowCardDrawable(si == 0 ? slot[2] : slot[3], 14, 5));
            card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            card.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cardLp.setMargins(0, 0, 0, dpToPx(10));
            card.setLayoutParams(cardLp);
            slotCards[si] = card;

            // ── 1행: O 가게이름 ──────────────────────────
            LinearLayout row1 = new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.setGravity(Gravity.CENTER_VERTICAL);
            row1.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 체크 원
            TextView tvCheck = new TextView(this);
            tvCheck.setText(si == 0 ? "✓" : "");
            tvCheck.setTextColor(si == 0 ? Color.WHITE : Color.TRANSPARENT);
            tvCheck.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
            tvCheck.setTypeface(null, Typeface.BOLD);
            tvCheck.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(dpToPx(28), dpToPx(28));
            checkLp.setMargins(0, 0, dpToPx(10), 0);
            android.graphics.drawable.GradientDrawable checkBg =
                    new android.graphics.drawable.GradientDrawable();
            checkBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (si == 0) {
                checkBg.setColor(Color.parseColor("#00000044"));
                checkBg.setStroke(dpToPx(2), Color.WHITE);
            } else {
                checkBg.setColor(Color.TRANSPARENT);
                checkBg.setStroke(dpToPx(2), Color.parseColor("#AAAAAA"));
            }
            tvCheck.setBackground(checkBg);
            tvCheck.setLayoutParams(checkLp);
            row1.addView(tvCheck);

            // 가게명
            TextView tvName = new TextView(this);
            tvName.setText(slot[1]);
            tvName.setTextColor(si == 0 ? Color.WHITE : Color.parseColor(slot[2]));
            tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row1.addView(tvName);
            card.addView(row1);

            // ── 2행: 잔액 : 200,000원 ────────────────────
            TextView tvBal = new TextView(this);
            tvBal.setText("잔액 : " + String.format("%,d원", bal));
            tvBal.setTextColor(si == 0 ? Color.WHITE : Color.parseColor("#666666"));
            tvBal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
            tvBal.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams balLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            balLp.setMargins(dpToPx(38), dpToPx(4), 0, 0); // 체크 원 너비만큼 들여쓰기
            tvBal.setLayoutParams(balLp);
            card.addView(tvBal);

            card.setOnClickListener(v -> {
                // ── 이전 선택 카드 해제 ──────────────────
                int prevIdx = selectedSlotIdx[0];
                String[] prev = activeSlots.get(prevIdx);
                slotCards[prevIdx].setBackground(makeShadowCardDrawable(prev[3], 14, 5));
                // row1 (index 0): LinearLayout 안의 체크원(0)과 이름(1)
                View prevRow1 = slotCards[prevIdx].getChildAt(0);
                if (prevRow1 instanceof LinearLayout) {
                    View ck = ((LinearLayout) prevRow1).getChildAt(0);
                    View nm = ((LinearLayout) prevRow1).getChildAt(1);
                    if (ck instanceof TextView) {
                        TextView tc = (TextView) ck;
                        tc.setText(""); tc.setTextColor(Color.TRANSPARENT);
                        android.graphics.drawable.GradientDrawable ub =
                                new android.graphics.drawable.GradientDrawable();
                        ub.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        ub.setColor(Color.TRANSPARENT);
                        ub.setStroke(dpToPx(2), Color.parseColor("#AAAAAA"));
                        tc.setBackground(ub);
                    }
                    if (nm instanceof TextView)
                        ((TextView) nm).setTextColor(Color.parseColor(prev[2]));
                }
                // tvBal (index 1)
                View prevBal = slotCards[prevIdx].getChildAt(1);
                if (prevBal instanceof TextView)
                    ((TextView) prevBal).setTextColor(Color.parseColor("#666666"));

                // ── 새 카드 선택 ─────────────────────────
                selectedSlotIdx[0] = fi;
                slotCards[fi].setBackground(makeShadowCardDrawable(slot[2], 14, 5));
                View newRow1 = slotCards[fi].getChildAt(0);
                if (newRow1 instanceof LinearLayout) {
                    View ck = ((LinearLayout) newRow1).getChildAt(0);
                    View nm = ((LinearLayout) newRow1).getChildAt(1);
                    if (ck instanceof TextView) {
                        TextView tc = (TextView) ck;
                        tc.setText("✓"); tc.setTextColor(Color.WHITE);
                        android.graphics.drawable.GradientDrawable cb =
                                new android.graphics.drawable.GradientDrawable();
                        cb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        cb.setColor(Color.parseColor("#00000044"));
                        cb.setStroke(dpToPx(2), Color.WHITE);
                        tc.setBackground(cb);
                    }
                    if (nm instanceof TextView)
                        ((TextView) nm).setTextColor(Color.WHITE);
                }
                View newBal = slotCards[fi].getChildAt(1);
                if (newBal instanceof TextView)
                    ((TextView) newBal).setTextColor(Color.WHITE);
            });
            dlgRoot.addView(card);
        }

        // 구매 금액 입력
        TextView tvAmtLabel = new TextView(this);
        tvAmtLabel.setText("구매 금액 (원)");
        tvAmtLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(20));
        tvAmtLabel.setTypeface(null, Typeface.BOLD);
        tvAmtLabel.setTextColor(Color.parseColor("#1A1A2E"));
        LinearLayout.LayoutParams amtLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        amtLabelLp.setMargins(0, dpToPx(8), 0, dpToPx(8));
        tvAmtLabel.setLayoutParams(amtLabelLp);
        dlgRoot.addView(tvAmtLabel);

        android.widget.EditText etAmount = new android.widget.EditText(this);
        setBlackCursor(etAmount);
        etAmount.setHint("금액 입력");
        etAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etAmount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(22));
        etAmount.setTextColor(Color.parseColor("#1A1A2E"));
        etAmount.setHintTextColor(Color.parseColor("#AAAAAA"));
        android.graphics.drawable.GradientDrawable etBg = new android.graphics.drawable.GradientDrawable();
        etBg.setColor(Color.parseColor("#F5F3FA"));
        etBg.setCornerRadius(dpToPx(10));
        etBg.setStroke(1, Color.parseColor("#DDD8F0"));
        etAmount.setBackground(etBg);
        etAmount.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.setMargins(0, 0, 0, dpToPx(16));
        etAmount.setLayoutParams(etLp);

        // 3자리 콤마 TextWatcher + 라벨 실시간 업데이트
        etAmount.addTextChangedListener(new android.text.TextWatcher() {
            private boolean isFormatting = false;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (isFormatting) return;
                isFormatting = true;
                String raw = s.toString().replace(",", "");
                if (!raw.isEmpty()) {
                    try {
                        long val = Long.parseLong(raw);
                        String formatted = String.format("%,d", val);
                        s.replace(0, s.length(), formatted);
                        // 라벨 업데이트: "구매 금액  50,000원" (금액 빨간색)
                        String amtPart = formatted + "원";
                        android.text.SpannableString sp =
                                new android.text.SpannableString("구매 금액    " + amtPart);
                        int start2 = sp.length() - amtPart.length();
                        sp.setSpan(new android.text.style.ForegroundColorSpan(
                                        Color.parseColor("#C0392B")),
                                start2, sp.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        tvAmtLabel.setText(sp);
                    } catch (NumberFormatException ignored) {}
                } else {
                    tvAmtLabel.setText("구매 금액 (원)");
                }
                isFormatting = false;
            }
        });

        dlgRoot.addView(etAmount);

        // 확인/취소 버튼
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button btnCancel = new Button(this);
        btnCancel.setText("취소");
        btnCancel.setBackground(makeShadowCardDrawable("#E0E0E0", 12, 4));
        btnCancel.setTextColor(Color.parseColor("#555555"));
        btnCancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        btnCancel.setTypeface(null, Typeface.BOLD);
        btnCancel.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dpToPx(52), 1f);
        cancelLp.setMargins(0, 0, dpToPx(8), 0);
        btnCancel.setLayoutParams(cancelLp);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(btnCancel);

        Button btnConfirm = new Button(this);
        btnConfirm.setText("확인");
        btnConfirm.setBackground(makeShadowCardDrawable("#C0392B", 12, 4));
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        btnConfirm.setTypeface(null, Typeface.BOLD);
        btnConfirm.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(0, dpToPx(52), 1f);
        btnConfirm.setLayoutParams(confirmLp);
        btnConfirm.setOnClickListener(v -> {
            String amtStr = etAmount.getText().toString().trim().replace(",", "");
            if (amtStr.isEmpty()) {
                Toast.makeText(this, "금액을 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            long purchaseAmt;
            try { purchaseAmt = Long.parseLong(amtStr); }
            catch (NumberFormatException e) {
                Toast.makeText(this, "올바른 금액을 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            String slotName = activeSlots.get(selectedSlotIdx[0])[1];
            String amtFormatted = String.format("%,d", purchaseAmt);
            // 최종 구매 확인 다이얼로그
            android.app.AlertDialog confirmDlg = new android.app.AlertDialog.Builder(this)
                    .setMessage(slotName + "  " + amtFormatted + "원\n구매 하시겠습니까?")
                    .setPositiveButton("확인", (d, w) -> {
                        dialog.dismiss();
                        savePurchase(activeSlots.get(selectedSlotIdx[0]), purchaseAmt);
                    })
                    .setNegativeButton("취소", null)
                    .create();
            confirmDlg.show();
            confirmDlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(Color.parseColor("#C0392B"));
            confirmDlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(Color.parseColor("#888888"));
        });
        btnRow.addView(btnConfirm);
        dlgRoot.addView(btnRow);

        dlgScroll.addView(dlgRoot);
        dialog.setContentView(dlgScroll);
        dialog.show();

        // 키보드 자동 표시
        etAmount.requestFocus();
        etAmount.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etAmount, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    /** 구매 내역을 prepaid.txt에 저장 (날짜순 정렬, 누적 잔액 재계산) */
    private void savePurchase(String[] slot, long purchaseAmt) {
        // 현재 시각 포맷
        java.util.Date now = new java.util.Date();
        String timestamp = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.KOREA).format(now);

        // 한국어 날짜시간 변환
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int month  = cal.get(java.util.Calendar.MONTH) + 1;
        int day    = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int hour   = cal.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = cal.get(java.util.Calendar.MINUTE);
        String ampm   = hour < 12 ? "오전" : "오후";
        int hour12    = hour % 12 == 0 ? 12 : hour % 12;
        String korDate = month + "월 " + day + "일 " + ampm + " " + hour12 + "시 " + minute + "분";

        // 구매 금액 포맷
        String amtFmt = String.format("%,d", purchaseAmt);

        // 블록 구성 (잔액은 저장 시점에 재계산 예정)
        String newBlock =
                timestamp + "\n" +
                        "농협 구매 " + amtFmt + "원\n" +
                        korDate + "\n" +
                        "000-****-0000-00 (경로당)\n" +
                        slot[0] + "\n" +    // 키워드 (미트클럽스토어 등)
                        "잔액 0원";         // 임시 - 아래서 재계산

        new Thread(() -> {
            try {
                // prepaid.txt 읽기
                DriveReadHelper reader = new DriveReadHelper(PinActivity.this);
                final String[] existing = {""};
                final Object lock = new Object();
                final boolean[] done = {false};
                reader.readFile("prepaid.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String c) {
                        synchronized (lock) { existing[0] = c; done[0] = true; lock.notifyAll(); }
                    }
                    @Override public void onFailure(String e) {
                        synchronized (lock) { existing[0] = ""; done[0] = true; lock.notifyAll(); }
                    }
                });
                synchronized (lock) { while (!done[0]) lock.wait(5000); }

                // 기존 블록 파싱
                List<String> allBlocks = new ArrayList<>();
                String raw = convertToPrePaid(existing[0]);
                if (!raw.trim().isEmpty()) {
                    for (String b : raw.split("-----------------------------------\r?\n")) {
                        if (!b.trim().isEmpty()) allBlocks.add(b.trim());
                    }
                }
                allBlocks.add(newBlock.trim());

                // 날짜+시간 오름차순 정렬
                allBlocks.sort((a, b2) -> extractTimestamp(a).compareTo(extractTimestamp(b2)));

                // 슬롯별 누적 잔액 재계산하여 잔액 줄 교체
                java.util.Map<String, Long> slotRunning = new java.util.HashMap<>();
                List<String> finalBlocks = new ArrayList<>();
                for (String b : allBlocks) {
                    // 어떤 슬롯인지 판별
                    String bSlot = "";
                    for (String[] s : MEAT_SLOTS) {
                        if (!s[0].isEmpty() && b.contains(s[0])) { bSlot = s[0]; break; }
                    }
                    if (bSlot.isEmpty()) { finalBlocks.add(b); continue; }

                    long running = slotRunning.getOrDefault(bSlot, 0L);

                    // 선입금: 더하기 / 구매: 빼기
                    java.util.regex.Matcher mIn =
                            java.util.regex.Pattern.compile("선입금\\s*([\\d,]+)원").matcher(b);
                    java.util.regex.Matcher mOut =
                            java.util.regex.Pattern.compile("구매\\s*([\\d,]+)원").matcher(b);
                    if (mIn.find()) {
                        try { running += Long.parseLong(mIn.group(1).replace(",", "")); } catch (NumberFormatException ignored) {}
                    } else if (mOut.find()) {
                        try { running -= Long.parseLong(mOut.group(1).replace(",", "")); } catch (NumberFormatException ignored) {}
                    }
                    slotRunning.put(bSlot, running);

                    // 잔액 줄 교체
                    finalBlocks.add(injectTotalBalance(b, running));
                }

                // 파일 재작성
                StringBuilder fileSb = new StringBuilder();
                for (String b : finalBlocks) {
                    fileSb.append(b).append("\n-----------------------------------\n");
                }
                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                up.uploadFileSync(fileSb.toString(), "prepaid.txt");
                DriveReadHelper.invalidateCache("prepaid.txt");

                // 화면 갱신
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            slot[1] + " 구매 " + String.format("%,d", purchaseAmt) + "원 저장되었습니다.",
                            Toast.LENGTH_SHORT).show();
                    // 선결제 화면 새로고침
                    readMeatSmsRaw(new DriveReadHelper.ReadCallback() {
                        @Override public void onSuccess(String content) {
                            String[] blks = content.split("-----------------------------------\r?\n");
                            List<String> nb = new ArrayList<>();
                            for (String b : blks) { if (!b.trim().isEmpty()) nb.add(b); }
                            runOnUiThread(() -> {
                                meatBlocks = nb;
                                updateMeatBalCards(nb);
                                updateMeatCardColors();
                                renderMeatMessages(nb, meatDisplayedCount);
                            });
                        }
                        @Override public void onFailure(String e) {}
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "저장 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 미트클럽 슬롯별 선입금 합산 - 구매 차감 후 카드에 반영 */
    private void updateMeatBalCards(List<String> blocks) {
        java.util.regex.Pattern prepaidPat =
                java.util.regex.Pattern.compile("선입금\\s*([\\d,]+)원");
        java.util.regex.Pattern purchasePat =
                java.util.regex.Pattern.compile("구매\\s*([\\d,]+)원");
        for (int s = 0; s < 4; s++) {
            if (MEAT_SLOTS[s][0].isEmpty()) continue;
            String keyword = MEAT_SLOTS[s][0];
            long totalAmt = 0;
            boolean hasData = false;
            for (String block : blocks) {
                if (!block.contains(keyword)) continue;
                for (String line : block.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.contains("선입금") && !t.contains("잔액")) {
                        java.util.regex.Matcher m = prepaidPat.matcher(t);
                        if (m.find()) {
                            try { totalAmt += Long.parseLong(m.group(1).replace(",", "")); hasData = true; } catch (NumberFormatException ignored) {}
                        }
                        break;
                    } else if (t.contains("구매") && !t.contains("잔액")) {
                        java.util.regex.Matcher m = purchasePat.matcher(t);
                        if (m.find()) {
                            try { totalAmt -= Long.parseLong(m.group(1).replace(",", "")); hasData = true; } catch (NumberFormatException ignored) {}
                        }
                        break;
                    }
                }
            }
            final String display = hasData
                    ? String.format("%,d원", totalAmt)
                    : "데이터 없음";
            final int si = s;
            if (meatBalTv[si] != null)
                meatBalTv[si].post(() -> meatBalTv[si].setText(display));
        }
    }

    /** 탭 필터 선택 상태에 따라 카드 배경색 + 텍스트색 업데이트 */
    private void updateMeatCardColors() {
        if (meatCards == null) return;
        for (int i = 0; i < 4; i++) {
            if (meatCards[i] == null) continue;
            boolean isActive = MEAT_SLOTS[i][0].equals(meatTabFilter);
            if (isActive) {
                // 선택됨: 진한 주색 배경, 모든 텍스트 흰색
                meatCards[i].setBackground(makeShadowCardDrawable(MEAT_SLOTS[i][2], 16, 5));
                meatCards[i].setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                for (int j = 0; j < meatCards[i].getChildCount(); j++) {
                    View child = meatCards[i].getChildAt(j);
                    if (child instanceof TextView)
                        ((TextView) child).setTextColor(Color.WHITE);
                }
            } else {
                // 비선택: 파스텔 배경, 이름=주색 / 잔액=다크
                meatCards[i].setBackground(makeShadowCardDrawable(MEAT_SLOTS[i][3], 16, 5));
                meatCards[i].setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                for (int j = 0; j < meatCards[i].getChildCount(); j++) {
                    View child = meatCards[i].getChildAt(j);
                    if (child instanceof TextView) {
                        if (j == 0)
                            ((TextView) child).setTextColor(Color.parseColor(MEAT_SLOTS[i][2]));
                        else
                            ((TextView) child).setTextColor(
                                    MEAT_SLOTS[i][0].isEmpty()
                                            ? Color.parseColor("#CCCCCC")
                                            : Color.parseColor("#1A1A2E"));
                    }
                }
            }
        }
    }

    /** 미트클럽 필터 메시지 렌더링 (통장잔액 화면의 renderMessages와 동일 구조, 키워드 고정) */
    private void renderMeatMessages(List<String> allBlocks, int count) {
        if (meatMsgContainer == null) return;
        meatMsgContainer.removeAllViews();

        // 선입금 블록 전부 표시, 각 블록의 잔액은 그 시점까지 누적 합산
        java.util.regex.Pattern prepaidPat =
                java.util.regex.Pattern.compile("선입금\\s*([\\d,]+)원");
        List<String> filtered = new ArrayList<>();

        if (meatTabFilter != null) {
            List<String> slotBlocks = new ArrayList<>();
            for (String b : allBlocks) {
                if (b.contains(meatTabFilter) && (b.contains("선입금") || b.contains("구매"))) slotBlocks.add(b);
            }
            // 오름차순 정렬로 누적 계산 (선입금=더하기, 구매=빼기)
            slotBlocks.sort((a, b2) -> extractTimestamp(a).compareTo(extractTimestamp(b2)));
            long cumTotal = 0;
            List<String> cumBlocks = new ArrayList<>();
            for (String b : slotBlocks) {
                for (String line : b.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.contains("선입금") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = prepaidPat.matcher(t);
                        if (lm.find()) { try { cumTotal += Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    } else if (t.contains("구매") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = java.util.regex.Pattern.compile("구매\\s*([\\d,]+)원").matcher(t);
                        if (lm.find()) { try { cumTotal -= Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    }
                }
                cumBlocks.add(injectTotalBalance(b, cumTotal));
            }
            // 최신이 위로 오도록 역순으로 filtered에 추가
            for (int i = cumBlocks.size() - 1; i >= 0; i--) filtered.add(cumBlocks.get(i));
        } else {
            // 전체 보기: 모든 슬롯 블록을 날짜+시간순으로 합쳐서 표시
            // 슬롯별 누적 잔액은 독립적으로 계산
            java.util.Map<String, Long> slotCum = new java.util.LinkedHashMap<>();
            for (String[] slot : MEAT_SLOTS) {
                if (!slot[0].isEmpty()) slotCum.put(slot[0], 0L);
            }
            // 모든 슬롯 블록 수집
            List<String> allSlotBlocks = new ArrayList<>();
            for (String b : allBlocks) {
                for (String[] slot : MEAT_SLOTS) {
                    if (!slot[0].isEmpty() && b.contains(slot[0])
                            && (b.contains("선입금") || b.contains("구매"))) {
                        allSlotBlocks.add(b);
                        break;
                    }
                }
            }
            // 날짜 오름차순 정렬
            allSlotBlocks.sort((a, b2) -> extractTimestamp(a).compareTo(extractTimestamp(b2)));
            // 슬롯별 누적 계산하며 cumBlocks 생성
            List<String> cumBlocks = new ArrayList<>();
            for (String b : allSlotBlocks) {
                String bSlot = "";
                for (String[] slot : MEAT_SLOTS) {
                    if (!slot[0].isEmpty() && b.contains(slot[0])) { bSlot = slot[0]; break; }
                }
                if (bSlot.isEmpty()) continue;
                long cum = slotCum.get(bSlot);
                for (String line : b.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.contains("선입금") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = prepaidPat.matcher(t);
                        if (lm.find()) { try { cum += Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    } else if (t.contains("구매") && !t.contains("잔액")) {
                        java.util.regex.Matcher lm = java.util.regex.Pattern.compile("구매\\s*([\\d,]+)원").matcher(t);
                        if (lm.find()) { try { cum -= Long.parseLong(lm.group(1).replace(",", "")); } catch (NumberFormatException ignored) {} }
                        break;
                    }
                }
                slotCum.put(bSlot, cum);
                cumBlocks.add(injectTotalBalance(b, cum));
            }
            // 최신이 위로 오도록 역순 추가
            for (int i = cumBlocks.size() - 1; i >= 0; i--) filtered.add(cumBlocks.get(i));
        }

        if (filtered.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText(meatTabFilter != null
                    ? meatTabFilter + " 관련 내역이 없습니다."
                    : "관련 내역이 없습니다.");
            tvEmpty.setTextColor(Color.parseColor("#888888"));
            tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(0, dpToPx(40), 0, 0);
            meatMsgContainer.addView(tvEmpty);
            return;
        }

        // 슬롯별 블록 렌더링 (꾹 누르기 선택 모드 지원)
        // 선택 모드 액션바
        if (meatSelectMode && meatSelectBar == null) {
            meatSelectBar = new LinearLayout(this);
        }

        for (int i = 0; i < filtered.size(); i++) {
            final int fi = i;
            String block = filtered.get(i);
            boolean isPurchase = block.contains("구매") && !block.contains("선입금");
            boolean isPrepaid  = !isPurchase;
            boolean isWithdraw = isPurchase;
            boolean isSelected = meatSelectedIdx.contains(fi);

            // ── 카드 ────────────────────────────────────────
            android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            wp.setMargins(0, 0, 0, 12);
            wrapper.setLayoutParams(wp);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable cardBg =
                    new android.graphics.drawable.GradientDrawable();
            if (isSelected) {
                cardBg.setColor(Color.parseColor("#D8CCFF"));
                cardBg.setStroke(2, Color.parseColor("#5B4A8A"));
            } else {
                cardBg.setColor(Color.WHITE);
                cardBg.setStroke(1, Color.parseColor("#DDD8F0"));
            }
            cardBg.setCornerRadius(20f);
            card.setBackground(cardBg);
            card.setElevation(isSelected ? 8f : 4f);
            card.setPadding(20, 16, 20, 16);
            android.widget.FrameLayout.LayoutParams cardFp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            card.setLayoutParams(cardFp);

            // 꾹 누르기 → 선택 모드 진입 + 액션바 표시
            card.setOnLongClickListener(v -> {
                if (!meatSelectMode) {
                    meatSelectMode = true;
                    meatSelectedIdx = new ArrayList<>();
                }
                if (meatSelectedIdx.contains(fi)) meatSelectedIdx.remove((Integer) fi);
                else meatSelectedIdx.add(fi);
                // 액션바 표시 + 선택 수 갱신
                if (meatSelectBar != null) {
                    meatSelectBar.setVisibility(View.VISIBLE);
                    TextView tv = (TextView) meatSelectBar.getChildAt(0);
                    if (tv != null) tv.setText(meatSelectedIdx.size() + "개 선택");
                }
                renderMeatMessages(allBlocks, count);
                return true;
            });
            // 선택 모드에서 탭 → 선택/해제 + 선택 수 갱신
            card.setOnClickListener(v -> {
                if (!meatSelectMode) return;
                if (meatSelectedIdx.contains(fi)) meatSelectedIdx.remove((Integer) fi);
                else meatSelectedIdx.add(fi);
                if (meatSelectBar != null) {
                    TextView tv = (TextView) meatSelectBar.getChildAt(0);
                    if (tv != null) tv.setText(meatSelectedIdx.size() + "개 선택");
                }
                renderMeatMessages(allBlocks, count);
            });

            // ── 블록 파싱 ────────────────────────────────────
            String[] rawLines = block.split("\\r?\\n");
            java.util.List<String> lines = new java.util.ArrayList<>();

            boolean isOldFormat = false;
            for (String rl : rawLines) {
                if (rl.trim().matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}.*")) {
                    isOldFormat = true; break;
                }
            }

            if (isOldFormat) {
                String out1="", out2="", out3="", out4="", out5="";
                for (String rl : rawLines) {
                    String t = rl.trim();
                    if (t.isEmpty()) continue;
                    if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) continue;
                    if ((t.contains("출금")||t.contains("입금")) && !t.contains("잔액")) {
                        out1 = t.replaceAll("(출금|입금)(\\d)", "$1 $2");
                    } else if (t.matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}\\s*")) {
                        out2 = convertDateTimeToKorean(t.trim());
                    } else if (t.matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}\\s+.*")) {
                        String[] parts = t.split("\\s+");
                        out2 = convertDateTimeToKorean(parts[0]+" "+parts[1]);
                        if (parts.length >= 3) {
                            out3 = parts[2];
                            for (String[] info : new String[][]{
                                    {"5510-13","운영비"},{"5510-83","부식비"},
                                    {"5510-53","냉난방비"},{"5510-23","회비"}}) {
                                if (out3.contains(info[0])) { out3 += " ("+info[1]+")"; break; }
                            }
                        }
                    } else if (t.contains("잔액")) {
                        int ix = t.indexOf("잔액");
                        String before = t.substring(0, ix).trim();
                        String after  = t.substring(ix).replaceAll("잔액(\\d)", "잔액 $1");
                        if (!before.isEmpty()) out4 = before;
                        out5 = after;
                    } else {
                        if (out4.isEmpty()) out4 = t;
                    }
                }
                for (String o : new String[]{out1,out2,out3,out4,out5}) {
                    if (!o.isEmpty()) lines.add(o);
                }
            } else {
                // 신형: 타임스탬프 제거 + 선결제/구매 공백 정규화
                for (String rl : rawLines) {
                    String t = rl.trim();
                    if (t.isEmpty()) continue;
                    if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) continue;
                    if (t.equals("[선입금]")) continue;
                    if (isPrepaid) t = t.replace("출금", "선입금");
                    t = t.replaceAll("(선입금|입금|구매)(\\d)", "$1 $2");
                    t = t.replaceAll("잔액(\\d)", "잔액 $1");
                    lines.add(t);
                }
            }

            // ── 출금/입금/선입금/구매 줄 인덱스 찾기 ──────────
            int firstContentLine = -1;
            for (int j = 0; j < lines.size(); j++) {
                String l = lines.get(j);
                if (l.contains("출금") || l.contains("입금") || l.contains("선입금") || l.contains("구매")) {
                    firstContentLine = j; break;
                }
            }

            // ── 줄별 TextView 렌더링 (renderMessages와 동일) ──
            for (int j = 0; j < lines.size(); j++) {
                String line = lines.get(j);
                if (line.trim().isEmpty()) continue;

                if (j == firstContentLine) {
                    android.text.SpannableString sp =
                            new android.text.SpannableString(line);
                    String colorWord = isPurchase ? "구매" : (isPrepaid ? "선입금" : "입금");
                    int wordColor = isPurchase
                            ? Color.parseColor("#E74C3C")   // 구매: 빨강
                            : Color.parseColor("#2980B9");  // 선입금: 파랑
                    int wordIdx = line.indexOf(colorWord);
                    if (wordIdx >= 0) {
                        sp.setSpan(
                                new android.text.style.ForegroundColorSpan(wordColor),
                                wordIdx, wordIdx + colorWord.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sp.setSpan(
                                new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                wordIdx, wordIdx + colorWord.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    TextView tv = new TextView(this);
                    tv.setText(sp);
                    tv.setTextColor(Color.parseColor("#222222"));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setShadowLayer(2f, 1f, 1f, Color.parseColor("#15000000"));
                    tv.setPadding(0, 3, 0, 3);
                    card.addView(tv);
                } else {
                    TextView tv = new TextView(this);
                    tv.setText(line);
                    tv.setTextColor(Color.parseColor("#222222"));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setPadding(0, 3, 0, 3);
                    card.addView(tv);
                }
            }

            // ── 구분선 ────────────────────────────────────────
            View divider = new View(this);
            divider.setBackgroundColor(Color.parseColor("#DDD8F0"));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            dp.setMargins(0, 12, 0, 4);
            divider.setLayoutParams(dp);
            card.addView(divider);

            wrapper.addView(card);

            // ── 선택 원 오버레이 (통장잔액과 동일) ──────────
            if (meatSelectMode) {
                android.widget.FrameLayout circleWrapper = new android.widget.FrameLayout(this);
                int outerSize = dpToPx(34);
                android.widget.FrameLayout.LayoutParams owlp =
                        new android.widget.FrameLayout.LayoutParams(outerSize, outerSize);
                owlp.gravity = Gravity.TOP | Gravity.END;
                owlp.setMargins(0, dpToPx(6), dpToPx(6), 0);
                circleWrapper.setLayoutParams(owlp);
                circleWrapper.setElevation(dpToPx(6));

                TextView tvCircle = new TextView(this);
                tvCircle.setLayoutParams(new android.widget.FrameLayout.LayoutParams(outerSize, outerSize));
                tvCircle.setGravity(Gravity.CENTER);
                tvCircle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
                tvCircle.setTypeface(null, Typeface.BOLD);

                android.graphics.drawable.GradientDrawable circleD =
                        new android.graphics.drawable.GradientDrawable();
                circleD.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                if (isSelected) {
                    circleD.setColor(Color.parseColor("#5B4A8A"));
                    circleD.setStroke(0, Color.TRANSPARENT);
                    tvCircle.setBackground(circleD);
                    tvCircle.setText("✓");
                    tvCircle.setTextColor(Color.WHITE);
                } else {
                    circleD.setColor(Color.WHITE);
                    circleD.setStroke(dpToPx(2), Color.parseColor("#777777"));
                    tvCircle.setBackground(circleD);
                    tvCircle.setText("");
                    tvCircle.setTextColor(Color.TRANSPARENT);
                }
                circleWrapper.addView(tvCircle);
                wrapper.addView(circleWrapper);
            }

            meatMsgContainer.addView(wrapper);
        }

        // 선택 수 갱신 (렌더링 후)
        if (meatSelectBar != null) {
            TextView tv = (TextView) meatSelectBar.getChildAt(0);
            if (tv != null) tv.setText(meatSelectedIdx.size() + "개 선택");
        }
    }

    // ── 월별 점프 다이얼로그 ─────────────────────────────
    // ── 팩스 전송 방법 화면 ─────────────────────────────

    /** 관리자: 선결제 가게 추가/수정 화면 */

    private void renderSlotList(LinearLayout layout, String[] COLORS, String[] BG_COLORS) {
        layout.removeAllViews();

        // ── 현재 슬롯 목록 ──────────────────────────────────
        TextView tvSec1 = new TextView(this);
        tvSec1.setText("등록된 가게");
        tvSec1.setTextColor(Color.parseColor("#1A1A2E"));
        tvSec1.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        tvSec1.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams sec1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sec1Lp.setMargins(0, 0, 0, dpToPx(8));
        tvSec1.setLayoutParams(sec1Lp);
        layout.addView(tvSec1);

        for (int i = 0; i < 4; i++) {
            final int fi = i;
            String[] slot = MEAT_SLOTS[i];
            boolean isEmpty = slot[0].isEmpty();

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(makeShadowCardDrawable(isEmpty ? "#F5F5F5" : "#FFFFFF", 12, 4));
            card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, dpToPx(8));
            card.setLayoutParams(cardLp);

            // 색상 동그라미
            TextView tvDot = new TextView(this);
            tvDot.setText("●");
            tvDot.setTextColor(Color.parseColor(isEmpty ? "#CCCCCC" : slot[2]));
            tvDot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dotLp.setMargins(0, 0, dpToPx(10), 0);
            tvDot.setLayoutParams(dotLp);
            card.addView(tvDot);

            // 이름/키워드
            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            infoCol.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvLabel = new TextView(this);
            tvLabel.setText(isEmpty ? "빈 슬롯" : slot[1]);
            tvLabel.setTextColor(Color.parseColor(isEmpty ? "#AAAAAA" : "#1A1A2E"));
            tvLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            tvLabel.setTypeface(null, isEmpty ? Typeface.NORMAL : Typeface.BOLD);
            infoCol.addView(tvLabel);

            if (!isEmpty) {
                TextView tvKey = new TextView(this);
                tvKey.setText("키워드: " + slot[0]);
                tvKey.setTextColor(Color.parseColor("#888888"));
                tvKey.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
                infoCol.addView(tvKey);
            }
            card.addView(infoCol);

            // 수정/삭제 버튼 (데이터 있는 슬롯만)
            if (!isEmpty) {
                Button btnEdit = new Button(this);
                btnEdit.setText("수정");
                btnEdit.setBackground(makeShadowCardDrawable("#F0EEF8", 8, 2));
                btnEdit.setTextColor(Color.parseColor("#6C5CE7"));
                btnEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
                LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                        dpToPx(48), dpToPx(32));
                editLp.setMargins(0, 0, dpToPx(6), 0);
                btnEdit.setLayoutParams(editLp);
                btnEdit.setOnClickListener(v -> showSlotEditDialog(fi, MEAT_SLOTS[fi], COLORS, BG_COLORS, layout));
                card.addView(btnEdit);

                Button btnDel = new Button(this);
                btnDel.setText("삭제");
                btnDel.setBackground(makeShadowCardDrawable("#FCEBEB", 8, 2));
                btnDel.setTextColor(Color.parseColor("#C0392B"));
                btnDel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
                btnDel.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(48), dpToPx(32)));
                btnDel.setOnClickListener(v -> {
                    android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                            .setMessage(MEAT_SLOTS[fi][1] + "\n삭제하시겠습니까?")
                            .setPositiveButton("삭제", (d, w) -> {
                                MEAT_SLOTS[fi] = new String[]{"", "준비중", "#AAAAAA", "#F5F5F5"};
                                saveSlotsToDrive(() -> {
                                    Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                                    renderSlotList(layout, COLORS, BG_COLORS);
                                });
                            })
                            .setNegativeButton("취소", null).create();
                    dlg.show();
                    dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#C0392B"));
                    dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#888888"));
                });
                card.addView(btnDel);
            }
            layout.addView(card);
        }

        // ── 새 가게 추가 폼 ──────────────────────────────────
        // 빈 슬롯이 있을 때만 표시
        int emptySlot = -1;
        for (int i = 0; i < 4; i++) if (MEAT_SLOTS[i][0].isEmpty()) { emptySlot = i; break; }

        if (emptySlot >= 0) {
            final int fEmpty = emptySlot;
            TextView tvSec2 = new TextView(this);
            tvSec2.setText("새 가게 추가");
            tvSec2.setTextColor(Color.parseColor("#1A1A2E"));
            tvSec2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            tvSec2.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams sec2Lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sec2Lp.setMargins(0, dpToPx(16), 0, dpToPx(8));
            tvSec2.setLayoutParams(sec2Lp);
            layout.addView(tvSec2);

            showSlotEditDialog(fEmpty, null, COLORS, BG_COLORS, layout);
        } else {
            TextView tvFull = new TextView(this);
            tvFull.setText("가게 슬롯이 모두 사용 중입니다.\n기존 가게를 삭제하면 새로 추가할 수 있습니다.");
            tvFull.setTextColor(Color.parseColor("#888888"));
            tvFull.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
            LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            fullLp.setMargins(0, dpToPx(16), 0, 0);
            tvFull.setLayoutParams(fullLp);
            layout.addView(tvFull);
        }
    }

    private void showSlotEditDialog(int slotIdx, String[] existingSlot,
                                    String[] COLORS, String[] BG_COLORS,
                                    LinearLayout parentLayout) {
        boolean isNew = (existingSlot == null || existingSlot[0].isEmpty());
        final int[] selectedColorIdx = {0};

        // 기존 슬롯이면 현재 색상 인덱스 찾기
        if (!isNew) {
            for (int i = 0; i < COLORS.length; i++) {
                if (COLORS[i].equals(existingSlot[2])) { selectedColorIdx[0] = i; break; }
            }
        }

        // 폼 컨테이너
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(makeShadowCardDrawable("#FFFFFF", 14, 4));
        form.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        form.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        LinearLayout.LayoutParams formLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        form.setLayoutParams(formLp);

        // 레이블 입력
        TextView tvLabelHint = new TextView(this);
        tvLabelHint.setText("가게 이름 (카드에 표시)");
        tvLabelHint.setTextColor(Color.parseColor("#888888"));
        tvLabelHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        form.addView(tvLabelHint);

        android.widget.EditText etLabel = new android.widget.EditText(this);
        setBlackCursor(etLabel);
        etLabel.setHint("예: 와이마트");
        if (!isNew) etLabel.setText(existingSlot[1]);
        etLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        etLabel.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        android.graphics.drawable.GradientDrawable etBg1 = new android.graphics.drawable.GradientDrawable();
        etBg1.setColor(Color.parseColor("#F5F3FA"));
        etBg1.setCornerRadius(dpToPx(8));
        etBg1.setStroke(1, Color.parseColor("#DDD8F0"));
        etLabel.setBackground(etBg1);
        etLabel.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        LinearLayout.LayoutParams etLp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp1.setMargins(0, dpToPx(4), 0, dpToPx(10));
        etLabel.setLayoutParams(etLp1);
        form.addView(etLabel);

        // 키워드 입력
        TextView tvKeyHint = new TextView(this);
        tvKeyHint.setText("SMS 키워드 (농협 문자에 포함된 가게명)");
        tvKeyHint.setTextColor(Color.parseColor("#888888"));
        tvKeyHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        form.addView(tvKeyHint);

        android.widget.EditText etKey = new android.widget.EditText(this);
        setBlackCursor(etKey);
        etKey.setHint("예: 와이마트");
        if (!isNew) etKey.setText(existingSlot[0]);
        etKey.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        android.graphics.drawable.GradientDrawable etBg2 = new android.graphics.drawable.GradientDrawable();
        etBg2.setColor(Color.parseColor("#F5F3FA"));
        etBg2.setCornerRadius(dpToPx(8));
        etBg2.setStroke(1, Color.parseColor("#DDD8F0"));
        etKey.setBackground(etBg2);
        etKey.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        LinearLayout.LayoutParams etLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp2.setMargins(0, dpToPx(4), 0, dpToPx(10));
        etKey.setLayoutParams(etLp2);
        form.addView(etKey);

        // 색상 선택
        TextView tvColorHint = new TextView(this);
        tvColorHint.setText("색상 선택");
        tvColorHint.setTextColor(Color.parseColor("#888888"));
        tvColorHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        form.addView(tvColorHint);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams colorRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        colorRowLp.setMargins(0, dpToPx(6), 0, dpToPx(14));
        colorRow.setLayoutParams(colorRowLp);
        TextView[] colorBtns = new TextView[COLORS.length];

        for (int ci = 0; ci < COLORS.length; ci++) {
            final int fci = ci;
            TextView tvColor = new TextView(this);
            tvColor.setText("●");
            tvColor.setTextColor(Color.parseColor(COLORS[ci]));
            tvColor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
            tvColor.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dpToPx(36), 1f);
            tvColor.setLayoutParams(clp);

            // 선택된 색상 표시
            if (ci == selectedColorIdx[0]) {
                android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
                selBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                selBg.setColor(Color.parseColor(BG_COLORS[ci]));
                selBg.setStroke(dpToPx(2), Color.parseColor(COLORS[ci]));
                tvColor.setBackground(selBg);
            }
            colorBtns[ci] = tvColor;

            tvColor.setOnClickListener(v -> {
                // 이전 선택 해제
                colorBtns[selectedColorIdx[0]].setBackground(null);
                selectedColorIdx[0] = fci;
                // 새 선택 표시
                android.graphics.drawable.GradientDrawable selBg2 = new android.graphics.drawable.GradientDrawable();
                selBg2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                selBg2.setColor(Color.parseColor(BG_COLORS[fci]));
                selBg2.setStroke(dpToPx(2), Color.parseColor(COLORS[fci]));
                tvColor.setBackground(selBg2);
            });
            colorRow.addView(tvColor);
        }
        form.addView(colorRow);

        // 저장 버튼
        Button btnSave = new Button(this);
        btnSave.setText(isNew ? "+ Drive에 저장" : "수정 저장");
        btnSave.setBackground(makeShadowCardDrawable("#27AE60", 12, 4));
        btnSave.setTextColor(Color.WHITE);
        btnSave.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48)));
        btnSave.setOnClickListener(v -> {
            String label = etLabel.getText().toString().trim();
            String key   = etKey.getText().toString().trim();
            if (label.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "가게 이름과 키워드를 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            MEAT_SLOTS[slotIdx] = new String[]{key, label, COLORS[selectedColorIdx[0]], BG_COLORS[selectedColorIdx[0]]};
            saveSlotsToDrive(() -> {
                Toast.makeText(this, isNew ? "추가되었습니다." : "수정되었습니다.", Toast.LENGTH_SHORT).show();
                renderSlotList(parentLayout, COLORS, BG_COLORS);
            });
        });
        form.addView(btnSave);

        parentLayout.addView(form);
    }

    /** 관리자 전용: 선결제 가게 추가/수정 화면 */
    private void showSlotManageScreen() {
        isOnSubScreen     = true;
        isOnMenuScreen    = false;
        isOnBalanceScreen = false;

        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(Color.parseColor("#F5F3FA"));

        // ── 하단 버튼 (돌아가기 + 선결제 잔액) ──────────
        LinearLayout slotBtnBar = new LinearLayout(this);
        slotBtnBar.setOrientation(LinearLayout.HORIZONTAL);
        slotBtnBar.setGravity(Gravity.CENTER);
        slotBtnBar.setBackgroundColor(Color.parseColor("#F5F3FA"));
        slotBtnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        slotBtnBar.setClipChildren(false);
        slotBtnBar.setClipToPadding(false);
        slotBtnBar.setId(View.generateViewId());
        int btnBackId = slotBtnBar.getId();
        RelativeLayout.LayoutParams slotBarLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        slotBarLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        slotBarLp.setMargins(0, 0, 0, navBarHeight);
        slotBtnBar.setLayoutParams(slotBarLp);

        Button btnBack = new Button(this);
        btnBack.setText("← 돌아가기");
        btnBack.setBackground(makeShadowCardDrawable("#C8BFEF", 14, 6));
        btnBack.setTextColor(Color.parseColor("#4A3DBF"));
        btnBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnBack.setTypeface(null, Typeface.BOLD);
        btnBack.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams backLpS = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        backLpS.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
        btnBack.setLayoutParams(backLpS);
        btnBack.setOnClickListener(v -> { isOnSubScreen = false; ownerMenuBuilder.build(); });
        slotBtnBar.addView(btnBack);

        Button btnMeatSlot = new Button(this);
        btnMeatSlot.setText("🥩 선결제 잔액");
        btnMeatSlot.setBackground(makeShadowCardDrawable("#EAFAF1", 14, 6));
        btnMeatSlot.setTextColor(Color.parseColor("#1A7A4A"));
        btnMeatSlot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnMeatSlot.setTypeface(null, Typeface.BOLD);
        btnMeatSlot.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams meatSlotLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        meatSlotLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
        btnMeatSlot.setLayoutParams(meatSlotLp);
        btnMeatSlot.setOnClickListener(v -> { isOnSubScreen = false; showMeatClubScreen(); });
        slotBtnBar.addView(btnMeatSlot);

        root.addView(slotBtnBar);

        // ── 상태바 영역 ───────────────────────────────────
        View statusBg = new View(this);
        statusBg.setBackgroundColor(Color.WHITE);
        statusBg.setId(View.generateViewId());
        int statusBgId = statusBg.getId();
        RelativeLayout.LayoutParams sbLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, statusBarHeight);
        sbLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        statusBg.setLayoutParams(sbLp);
        root.addView(statusBg);

        // ── 헤더 ─────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable hGrad =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#27AE60"), Color.parseColor("#52D68A")});
        hGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(20),dpToPx(20),dpToPx(20),dpToPx(20)});
        header.setBackground(hGrad);
        header.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        header.setId(View.generateViewId());
        int headerId = header.getId();
        RelativeLayout.LayoutParams hLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        hLp.addRule(RelativeLayout.BELOW, statusBgId);
        header.setLayoutParams(hLp);

        TextView tvHIcon = new TextView(this);
        tvHIcon.setText("🏪");
        tvHIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        tvHIcon.setPadding(0, 0, dpToPx(10), 0);
        header.addView(tvHIcon);

        LinearLayout hTxt = new LinearLayout(this);
        hTxt.setOrientation(LinearLayout.VERTICAL);
        hTxt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvHTitle = new TextView(this);
        tvHTitle.setText("선결제 가게 관리");
        tvHTitle.setTextColor(Color.WHITE);
        tvHTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
        tvHTitle.setTypeface(null, Typeface.BOLD);
        hTxt.addView(tvHTitle);

        TextView tvHSub = new TextView(this);
        tvHSub.setText("slots.txt → Google Drive 자동 저장");
        tvHSub.setTextColor(Color.parseColor("#C8F7DC"));
        tvHSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        hTxt.addView(tvHSub);
        header.addView(hTxt);
        root.addView(header);

        // ── 스크롤 영역 ───────────────────────────────────
        ScrollView sv = new ScrollView(this);
        RelativeLayout.LayoutParams svLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        svLp.addRule(RelativeLayout.BELOW, headerId);
        svLp.addRule(RelativeLayout.ABOVE, btnBackId);
        sv.setLayoutParams(svLp);
        sv.setBackgroundColor(Color.parseColor("#F5F3FA"));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(16));
        sv.addView(content);

        // slots.txt 로드 후 UI 구성
        loadSlotsFromDrive(() -> buildSlotManageUI(content, root));

        root.addView(sv);
        setContentView(root);

        // ── 헤더 자동 페이드 (1.8초 후) ─────────────────
        header.post(() -> {
            final int origH = header.getMeasuredHeight() + dpToPx(12);
            header.postDelayed(() -> {
                android.animation.ValueAnimator anim =
                        android.animation.ValueAnimator.ofFloat(1f, 0f);
                anim.setDuration(600);
                anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                anim.addUpdateListener(va -> {
                    float f = (float) va.getAnimatedValue();
                    header.setAlpha(f);
                    int h = (int)(origH * f);
                    RelativeLayout.LayoutParams lp =
                            (RelativeLayout.LayoutParams) header.getLayoutParams();
                    lp.height = Math.max(h - dpToPx(12), 0);
                    header.setLayoutParams(lp);
                    header.requestLayout();
                });
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        header.setVisibility(View.GONE);
                    }
                });
                anim.start();
            }, 1800);
        });
    }

    private void buildSlotManageUI(LinearLayout content, RelativeLayout root) {
        content.removeAllViews();

        // 색상 옵션
        String[] colorNames  = {"초록", "주황", "파랑", "보라", "빨강", "청록"};
        String[] colorHex    = {"#27AE60", "#E67E22", "#4A90D9", "#8E44AD", "#C0392B", "#1A7A63"};
        String[] colorBgHex  = {"#EAFAF1", "#FEF9E7", "#EBF4FF", "#F5EEF8", "#FDEDEC", "#E8F8F5"};
        final int[] editingIdx = {-1}; // -1 = 새 추가 모드

        // ── 등록된 가게 목록 ──────────────────────────────
        TextView tvListTitle = new TextView(this);
        tvListTitle.setText("등록된 가게");
        tvListTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        tvListTitle.setTextColor(Color.parseColor("#666666"));
        tvListTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ttLp.setMargins(0, 0, 0, dpToPx(8));
        tvListTitle.setLayoutParams(ttLp);
        content.addView(tvListTitle);

        // 가게 카드들 (동적 갱신용 컨테이너)
        LinearLayout slotListContainer = new LinearLayout(this);
        slotListContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(slotListContainer);

        // ── 새 가게 추가 폼 ──────────────────────────────
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#DDD8F0"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(0, dpToPx(16), 0, dpToPx(16));
        divider.setLayoutParams(divLp);
        content.addView(divider);

        // 폼 제목 (동적 변경용)
        TextView tvFormTitle = new TextView(this);
        tvFormTitle.setText("새 가게 추가");
        tvFormTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        tvFormTitle.setTextColor(Color.parseColor("#666666"));
        tvFormTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams ftLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ftLp.setMargins(0, 0, 0, dpToPx(10));
        tvFormTitle.setLayoutParams(ftLp);
        content.addView(tvFormTitle);

        // 가게 이름 입력
        TextView tvNameLabel = new TextView(this);
        tvNameLabel.setText("카드 표시 이름");
        tvNameLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        tvNameLabel.setTextColor(Color.parseColor("#888888"));
        LinearLayout.LayoutParams nlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlLp.setMargins(0, 0, 0, dpToPx(4));
        tvNameLabel.setLayoutParams(nlLp);
        content.addView(tvNameLabel);

        android.widget.EditText etName = new android.widget.EditText(this);
        etName.setHint("예: 미트클럽");
        etName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        styleEditText(etName);
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        enLp.setMargins(0, 0, 0, dpToPx(10));
        etName.setLayoutParams(enLp);
        content.addView(etName);

        // SMS 키워드 입력
        TextView tvKeyLabel = new TextView(this);
        tvKeyLabel.setText("SMS 키워드 (문자에 포함된 가게명)");
        tvKeyLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        tvKeyLabel.setTextColor(Color.parseColor("#888888"));
        LinearLayout.LayoutParams klLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        klLp.setMargins(0, 0, 0, dpToPx(4));
        tvKeyLabel.setLayoutParams(klLp);
        content.addView(tvKeyLabel);

        android.widget.EditText etKeyword = new android.widget.EditText(this);
        etKeyword.setHint("예: 미트클럽스토어");
        etKeyword.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        styleEditText(etKeyword);
        LinearLayout.LayoutParams ekLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ekLp.setMargins(0, 0, 0, dpToPx(12));
        etKeyword.setLayoutParams(ekLp);
        content.addView(etKeyword);

        // 색상 선택
        TextView tvColorLabel = new TextView(this);
        tvColorLabel.setText("색상 선택");
        tvColorLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        tvColorLabel.setTextColor(Color.parseColor("#888888"));
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clLp.setMargins(0, 0, 0, dpToPx(8));
        tvColorLabel.setLayoutParams(clLp);
        content.addView(tvColorLabel);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crLp.setMargins(0, 0, 0, dpToPx(14));
        colorRow.setLayoutParams(crLp);

        final int[] selectedColor = {0}; // 기본 초록
        View[] colorDots = new View[colorHex.length];
        for (int ci = 0; ci < colorHex.length; ci++) {
            final int fci = ci;
            android.graphics.drawable.GradientDrawable dot =
                    new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(Color.parseColor(colorHex[ci]));
            View dotView = new View(this);
            dotView.setBackground(dot);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dpToPx(32), dpToPx(32));
            dotLp.setMargins(0, 0, dpToPx(10), 0);
            dotView.setLayoutParams(dotLp);
            dotView.setOnClickListener(v -> {
                selectedColor[0] = fci;
                // 테두리 업데이트
                for (int j = 0; j < colorDots.length; j++) {
                    android.graphics.drawable.GradientDrawable d2 =
                            new android.graphics.drawable.GradientDrawable();
                    d2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    d2.setColor(Color.parseColor(colorHex[j]));
                    if (j == fci) d2.setStroke(dpToPx(3), Color.parseColor("#1A1A2E"));
                    colorDots[j].setBackground(d2);
                }
            });
            colorDots[ci] = dotView;
            if (ci == 0) {
                android.graphics.drawable.GradientDrawable sel =
                        new android.graphics.drawable.GradientDrawable();
                sel.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                sel.setColor(Color.parseColor(colorHex[0]));
                sel.setStroke(dpToPx(3), Color.parseColor("#1A1A2E"));
                dotView.setBackground(sel);
            }
            colorRow.addView(dotView);
        }
        content.addView(colorRow);

        // 저장/취소 버튼 행
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRow.setLayoutParams(brLp);

        Button btnCancel2 = new Button(this);
        btnCancel2.setText("취소");
        btnCancel2.setBackground(makeShadowCardDrawable("#E0E0E0", 12, 4));
        btnCancel2.setTextColor(Color.parseColor("#555555"));
        btnCancel2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnCancel2.setTypeface(null, Typeface.BOLD);
        btnCancel2.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams bcLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        bcLp.setMargins(0, 0, dpToPx(8), 0);
        btnCancel2.setLayoutParams(bcLp);
        btnCancel2.setVisibility(View.GONE); // 수정 모드에서만 표시
        btnRow.addView(btnCancel2);

        Button btnSave = new Button(this);
        btnSave.setText("+ Drive에 저장");
        btnSave.setBackground(makeShadowCardDrawable("#27AE60", 12, 4));
        btnSave.setTextColor(Color.WHITE);
        btnSave.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, dpToPx(50), 2f));
        btnRow.addView(btnSave);
        content.addView(btnRow);

        // 안내 텍스트
        TextView tvHint = new TextView(this);
        tvHint.setText("저장 후 선결제 화면을 새로고침하면 즉시 반영됩니다.");
        tvHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        tvHint.setTextColor(Color.parseColor("#27AE60"));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dpToPx(8), 0, 0);
        tvHint.setLayoutParams(hintLp);
        content.addView(tvHint);

        // ── 가게 목록 렌더링 헬퍼 ────────────────────────
        Runnable renderList = new Runnable() {
            @Override public void run() {
                slotListContainer.removeAllViews();
                boolean hasAny = false;
                for (int si = 0; si < MEAT_SLOTS.length; si++) {
                    if (MEAT_SLOTS[si][0].isEmpty()) continue;
                    hasAny = true;
                    final int fsi = si;
                    LinearLayout row = new LinearLayout(PinActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackground(makeShadowCardDrawable("#FFFFFF", 12, 4));
                    row.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                    row.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    rowLp.setMargins(0, 0, 0, dpToPx(8));
                    row.setLayoutParams(rowLp);

                    // 컬러 도트
                    View dot = new View(PinActivity.this);
                    android.graphics.drawable.GradientDrawable dBg =
                            new android.graphics.drawable.GradientDrawable();
                    dBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    dBg.setColor(Color.parseColor(MEAT_SLOTS[si][2]));
                    dot.setBackground(dBg);
                    LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dpToPx(12), dpToPx(12));
                    dLp.setMargins(0, 0, dpToPx(10), 0);
                    dot.setLayoutParams(dLp);
                    row.addView(dot);

                    // 이름 + 키워드
                    LinearLayout info = new LinearLayout(PinActivity.this);
                    info.setOrientation(LinearLayout.VERTICAL);
                    info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                    TextView tvN = new TextView(PinActivity.this);
                    tvN.setText(MEAT_SLOTS[si][1]);
                    tvN.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
                    tvN.setTypeface(null, Typeface.BOLD);
                    tvN.setTextColor(Color.parseColor("#1A1A2E"));
                    info.addView(tvN);

                    TextView tvK = new TextView(PinActivity.this);
                    tvK.setText("키워드: " + MEAT_SLOTS[si][0]);
                    tvK.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
                    tvK.setTextColor(Color.parseColor("#888888"));
                    info.addView(tvK);
                    row.addView(info);

                    // 수정 버튼
                    Button btnEdit = new Button(PinActivity.this);
                    btnEdit.setText("수정");
                    btnEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
                    btnEdit.setTextColor(Color.parseColor("#4A3DBF"));
                    android.graphics.drawable.GradientDrawable editBg =
                            new android.graphics.drawable.GradientDrawable();
                    editBg.setColor(Color.parseColor("#EDE9FF"));
                    editBg.setCornerRadius(dpToPx(8));
                    btnEdit.setBackground(editBg);
                    LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    editLp.setMargins(0, 0, dpToPx(6), 0);
                    btnEdit.setLayoutParams(editLp);
                    btnEdit.setOnClickListener(v -> {
                        editingIdx[0] = fsi;
                        etName.setText(MEAT_SLOTS[fsi][1]);
                        etKeyword.setText(MEAT_SLOTS[fsi][0]);
                        // 색상 선택 업데이트
                        for (int j = 0; j < colorHex.length; j++) {
                            if (colorHex[j].equalsIgnoreCase(MEAT_SLOTS[fsi][2])) {
                                selectedColor[0] = j;
                                for (int k = 0; k < colorDots.length; k++) {
                                    android.graphics.drawable.GradientDrawable d2 =
                                            new android.graphics.drawable.GradientDrawable();
                                    d2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                                    d2.setColor(Color.parseColor(colorHex[k]));
                                    if (k == j) d2.setStroke(dpToPx(3), Color.parseColor("#1A1A2E"));
                                    colorDots[k].setBackground(d2);
                                }
                                break;
                            }
                        }
                        tvFormTitle.setText("가게 수정");
                        btnSave.setText("수정 저장");
                        btnCancel2.setVisibility(View.VISIBLE);
                        etName.requestFocus();
                    });
                    row.addView(btnEdit);

                    // 삭제 버튼
                    Button btnDel = new Button(PinActivity.this);
                    btnDel.setText("삭제");
                    btnDel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
                    btnDel.setTextColor(Color.parseColor("#A32D2D"));
                    android.graphics.drawable.GradientDrawable delBg =
                            new android.graphics.drawable.GradientDrawable();
                    delBg.setColor(Color.parseColor("#FCEBEB"));
                    delBg.setCornerRadius(dpToPx(8));
                    btnDel.setBackground(delBg);
                    btnDel.setOnClickListener(v -> {
                        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(PinActivity.this)
                                .setMessage(MEAT_SLOTS[fsi][1] + "을(를) 삭제하시겠습니까?")
                                .setPositiveButton("삭제", (d, w) -> {
                                    MEAT_SLOTS[fsi] = new String[]{"", "준비중", "#AAAAAA", "#F5F5F5"};
                                    saveSlotsToDrive(() -> {
                                        Toast.makeText(PinActivity.this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                                        buildSlotManageUI(content, root);
                                    });
                                })
                                .setNegativeButton("취소", null)
                                .create();
                        dlg.show();
                        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                                .setTextColor(Color.parseColor("#C0392B"));
                        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                                .setTextColor(Color.parseColor("#888888"));
                    });
                    row.addView(btnDel);
                    slotListContainer.addView(row);
                }
                if (!hasAny) {
                    TextView tvEmpty = new TextView(PinActivity.this);
                    tvEmpty.setText("등록된 가게가 없습니다. 아래에서 추가하세요.");
                    tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
                    tvEmpty.setTextColor(Color.parseColor("#AAAAAA"));
                    tvEmpty.setPadding(0, dpToPx(8), 0, dpToPx(8));
                    slotListContainer.addView(tvEmpty);
                }
            }
        };
        renderList.run();

        // 취소 버튼 클릭
        btnCancel2.setOnClickListener(v -> {
            editingIdx[0] = -1;
            etName.setText("");
            etKeyword.setText("");
            selectedColor[0] = 0;
            for (int j = 0; j < colorDots.length; j++) {
                android.graphics.drawable.GradientDrawable d2 =
                        new android.graphics.drawable.GradientDrawable();
                d2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                d2.setColor(Color.parseColor(colorHex[j]));
                if (j == 0) d2.setStroke(dpToPx(3), Color.parseColor("#1A1A2E"));
                colorDots[j].setBackground(d2);
            }
            tvFormTitle.setText("새 가게 추가");
            btnSave.setText("+ Drive에 저장");
            btnCancel2.setVisibility(View.GONE);
        });

        // 저장 버튼 클릭
        btnSave.setOnClickListener(v -> {
            String name    = etName.getText().toString().trim();
            String keyword = etKeyword.getText().toString().trim();
            if (name.isEmpty() || keyword.isEmpty()) {
                Toast.makeText(this, "이름과 키워드를 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            int ci = selectedColor[0];
            String hex   = colorHex[ci];
            String hexBg = colorBgHex[ci];

            if (editingIdx[0] >= 0) {
                // 수정 모드
                MEAT_SLOTS[editingIdx[0]] = new String[]{keyword, name, hex, hexBg};
            } else {
                // 추가 모드: 빈 슬롯 찾기
                int emptySlot = -1;
                for (int si = 0; si < MEAT_SLOTS.length; si++) {
                    if (MEAT_SLOTS[si][0].isEmpty()) { emptySlot = si; break; }
                }
                if (emptySlot < 0) {
                    Toast.makeText(this, "슬롯이 가득 찼습니다. (최대 4개)\n기존 가게를 삭제 후 추가해 주세요.", Toast.LENGTH_LONG).show();
                    return;
                }
                MEAT_SLOTS[emptySlot] = new String[]{keyword, name, hex, hexBg};
            }

            saveSlotsToDrive(() -> {
                Toast.makeText(this, "저장되었습니다.", Toast.LENGTH_SHORT).show();
                editingIdx[0] = -1;
                etName.setText("");
                etKeyword.setText("");
                tvFormTitle.setText("새 가게 추가");
                btnSave.setText("+ Drive에 저장");
                btnCancel2.setVisibility(View.GONE);
                renderList.run();
            });
        });
    }

    /** EditText 공통 스타일 */
    private void styleEditText(android.widget.EditText et) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#F5F3FA"));
        bg.setCornerRadius(dpToPx(10));
        bg.setStroke(1, Color.parseColor("#DDD8F0"));
        et.setBackground(bg);
        et.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        et.setTextColor(Color.parseColor("#1A1A2E"));
        et.setHintTextColor(Color.parseColor("#AAAAAA"));
        setBlackCursor(et);
    }

    /** EditText 커서 검은색으로 설정 (공통) */
    private void setBlackCursor(android.widget.EditText et) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            et.setTextCursorDrawable(null); // null이면 textColor 따라감 → 검은색
        } else {
            try {
                java.lang.reflect.Field f = android.widget.TextView.class.getDeclaredField("mCursorDrawableRes");
                f.setAccessible(true);
                f.set(et, 0);
            } catch (Exception ignored) {}
        }
        et.setCursorVisible(true);
    }

    private void showSingleImageScreen(String title, String subtitle, String assetFile) {
        isOnSubScreen     = true;
        isOnMenuScreen    = false;
        isOnBalanceScreen = false;
        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(Color.parseColor("#F5F3FA"));
        root.setPadding(0, 0, 0, 0);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            int bot = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, top, 0, bot);
            return insets;
        });

        // ── 하단 고정 돌아가기 버튼 (통장잔액 화면과 동일 스타일) ──
        android.graphics.drawable.GradientDrawable backBg =
                new android.graphics.drawable.GradientDrawable();
        Button btnBack = new Button(this);
        btnBack.setText("← 돌아가기");
        btnBack.setBackground(makeShadowCardDrawable("#C8BFEF", 14, 6));
        btnBack.setTextColor(Color.parseColor("#4A3DBF"));
        btnBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnBack.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBack.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        btnBack.setId(View.generateViewId());
        int btnId = btnBack.getId();
        RelativeLayout.LayoutParams backLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, dpToPx(50));
        backLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        backLp.setMargins(dpToPx(16), 0, dpToPx(16), dpToPx(10));
        btnBack.setLayoutParams(backLp);
        btnBack.setOnClickListener(v -> { isOnSubScreen = false; if (isOwner) ownerMenuBuilder.build(); else userMenuBuilder.build(false); });
        root.addView(btnBack);

        // ── 상태바 공간 (root padding으로 처리하므로 높이 0) ────────────────────
        View statusBarBg = new View(this);
        statusBarBg.setBackgroundColor(Color.WHITE);
        statusBarBg.setId(View.generateViewId());
        int statusBarBgId = statusBarBg.getId();
        RelativeLayout.LayoutParams sbLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, 0);
        sbLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        statusBarBg.setLayoutParams(sbLp);
        root.addView(statusBarBg);

        // ── 상단 헤더 (상태바 바로 아래) ──────────────────────
        LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable hGrad =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#7C6FE0"), Color.parseColor("#9B8FF5")});
        hGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(20),dpToPx(20),dpToPx(20),dpToPx(20)});
        headerBar.setBackground(hGrad);
        headerBar.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        headerBar.setId(View.generateViewId());
        int headerId = headerBar.getId();
        RelativeLayout.LayoutParams hLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        hLp.addRule(RelativeLayout.BELOW, statusBarBgId);
        headerBar.setLayoutParams(hLp);

        LinearLayout headerTxtArea = new LinearLayout(this);
        headerTxtArea.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htaLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        headerTxtArea.setLayoutParams(htaLp);

        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText(title);
        tvHeaderTitle.setTextColor(Color.WHITE);
        tvHeaderTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
        tvHeaderTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        headerTxtArea.addView(tvHeaderTitle);

        TextView tvHeaderSub2 = new TextView(this);
        tvHeaderSub2.setText(subtitle);
        tvHeaderSub2.setTextColor(Color.parseColor("#D4C8FF"));
        tvHeaderSub2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        LinearLayout.LayoutParams subLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp2.setMargins(0, dpToPx(2), 0, 0);
        tvHeaderSub2.setLayoutParams(subLp2);
        headerTxtArea.addView(tvHeaderSub2);

        headerBar.addView(headerTxtArea);
        root.addView(headerBar);

        // ── 줌 이미지 뷰 (헤더 아래 ~ 버튼 위) ──────────────────
        ZoomImageView ziv = new ZoomImageView(this);
        ziv.setBackgroundColor(Color.parseColor("#F5F3FA"));
        RelativeLayout.LayoutParams zivLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        zivLp.addRule(RelativeLayout.BELOW, headerId);
        zivLp.addRule(RelativeLayout.ABOVE, btnId);
        ziv.setLayoutParams(zivLp);

        try {
            java.io.InputStream is = getAssets().open(assetFile);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
            is.close();
            if (bmp != null) {
                ziv.setImageBitmap(bmp);
            } else {
                ziv.setBackgroundColor(Color.parseColor("#F8F8F8"));
                TextView tvErr = new TextView(this);
                tvErr.setText("이미지 로드 실패: " + assetFile);
                tvErr.setTextColor(Color.RED);
                tvErr.setGravity(Gravity.CENTER);
                root.addView(tvErr);
            }
        } catch (Exception e) {
            ziv.setBackgroundColor(Color.parseColor("#F8F8F8"));
            TextView tvErr = new TextView(this);
            tvErr.setText("오류: " + e.getMessage());
            tvErr.setTextColor(Color.RED);
            tvErr.setGravity(Gravity.CENTER);
            root.addView(tvErr);
        }
        root.addView(ziv);
        setContentView(root);
    }

    private void showStatsScreen() {
        final String[] acctNames = {"운영비","부식비","냉난방비","회비"};
        final String[] acctKeys  = {"5510-13","5510-83","5510-53","5510-23"};
        final String[] acctColors= {"#4A90D9","#27AE60","#E67E22","#8E44AD"};
        final String[] acctBgCol = {"#EBF4FF","#EAFAF1","#FEF9E7","#F5EEF8"};
        final String[] qNames    = {"1분기","2분기","3분기","4분기"};
        final int[][] qMonths    = {{1,2,3},{4,5,6},{7,8,9},{10,11,12}};

        // ── 루트 ─────────────────────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F4F8"));
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(0, top, 0, 0);
            return insets;
        });

        // 액션바 없음

        // ── 헤더 카드 (통장잔액 화면과 동일 스타일, 자동 페이드아웃) ────
        final LinearLayout statsHeaderBar = new LinearLayout(this);
        statsHeaderBar.setOrientation(LinearLayout.HORIZONTAL);
        statsHeaderBar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable shGrad =
                new android.graphics.drawable.GradientDrawable();
        shGrad.setColor(Color.parseColor("#7C6FE0"));
        shGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(16),dpToPx(16),dpToPx(16),dpToPx(16)});
        statsHeaderBar.setBackground(shGrad);
        statsHeaderBar.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shLp.setMargins(0, 0, 0, dpToPx(12));
        statsHeaderBar.setLayoutParams(shLp);

        TextView shIcon = new TextView(this);
        shIcon.setText("📊");
        shIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        shIcon.setPadding(0, 0, dpToPx(10), 0);
        statsHeaderBar.addView(shIcon);

        LinearLayout shTxt = new LinearLayout(this);
        shTxt.setOrientation(LinearLayout.VERTICAL);
        TextView shTitle = new TextView(this);
        shTitle.setText("월별 지출 통계");
        shTitle.setTextColor(Color.WHITE);
        shTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
        shTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        shTxt.addView(shTitle);
        TextView shSub = new TextView(this);
        shSub.setText("계좌별 수입/지출을 월별로 확인하세요");
        shSub.setTextColor(Color.parseColor("#D4C8FF"));
        shSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        shTxt.addView(shSub);
        statsHeaderBar.addView(shTxt);
        root.addView(statsHeaderBar);

        // ── ScrollView ────────────────────────────────────────
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dpToPx(10), 0, dpToPx(10), dpToPx(20));
        sv.addView(inner);
        root.addView(sv);

        // ── 상태 변수 ─────────────────────────────────────────
        final int[] selAcct = {0};
        final int[] selQ    = {0};
        final int[] selM    = {-1};
        final int[][][] mIn  = new int[4][13][6];
        final int[][][] mOut = new int[4][13][6];

        // ── 계좌 카드 2×2 (버전B 스타일) ──────────────────────
        final LinearLayout[] acctCards = new LinearLayout[4];
        LinearLayout acctGrid = new LinearLayout(this);
        acctGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams agLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        agLp.setMargins(0, 0, 0, dpToPx(10));
        acctGrid.setLayoutParams(agLp);

        LinearLayout[] acctRows = {new LinearLayout(this), new LinearLayout(this)};
        acctRows[0].setOrientation(LinearLayout.HORIZONTAL);
        acctRows[1].setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ar0lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ar0lp.setMargins(0,0,0,dpToPx(6));
        acctRows[0].setLayoutParams(ar0lp);
        acctRows[1].setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 잔액 SharedPreferences에서 읽기
        android.content.SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String[] balTexts = {
                prefs.getString("bal_5510-13", "-"),
                prefs.getString("bal_5510-83", "-"),
                prefs.getString("bal_5510-53", "-"),
                prefs.getString("bal_5510-23", "-"),
        };

        for (int i = 0; i < 4; i++) {
            final int ai = i;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dpToPx(10), dpToPx(14), dpToPx(10), dpToPx(14));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            cp.setMargins(i%2==1 ? dpToPx(6) : 0, 0, 0, 0);
            card.setLayoutParams(cp);
            card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            acctCards[i] = card;

            // 이름
            TextView tvN = new TextView(this);
            tvN.setText(acctNames[i]);
            tvN.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvN.setGravity(Gravity.CENTER);
            tvN.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(tvN);

            // 잔액
            TextView tvBal = new TextView(this);
            tvBal.setText(balTexts[i]);
            tvBal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            tvBal.setTypeface(null, android.graphics.Typeface.BOLD);
            tvBal.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams balLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            balLp.setMargins(0, dpToPx(3), 0, 0);
            tvBal.setLayoutParams(balLp);
            card.addView(tvBal);

            if (i < 2) acctRows[0].addView(card);
            else       acctRows[1].addView(card);
        }
        acctGrid.addView(acctRows[0]);
        acctGrid.addView(acctRows[1]);
        inner.addView(acctGrid);

        // ── 차트 카드 (분기/월 버튼 + 수입/지출 요약 + 차트) ──
        LinearLayout chartCard = new LinearLayout(this);
        chartCard.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable ccBg =
                new android.graphics.drawable.GradientDrawable();
        ccBg.setColor(Color.WHITE);
        ccBg.setCornerRadius(dpToPx(14));
        chartCard.setBackground(ccBg);
        chartCard.setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14));
        inner.addView(chartCard);

        // 차트카드 헤더 (제목 + 분기버튼)
        LinearLayout ccHeader = new LinearLayout(this);
        ccHeader.setOrientation(LinearLayout.HORIZONTAL);
        ccHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ccHlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ccHlp.setMargins(0,0,0,dpToPx(10));
        ccHeader.setLayoutParams(ccHlp);

        final TextView tvChartTitle = new TextView(this);
        tvChartTitle.setTextColor(Color.parseColor("#333333"));
        tvChartTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvChartTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvChartTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ccHeader.addView(tvChartTitle);

        // 분기 버튼 (오른쪽)
        LinearLayout qBtnRow = new LinearLayout(this);
        qBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        qBtnRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView[] qBtns = new TextView[4];
        for (int q = 0; q < 4; q++) {
            final int qi = q;
            TextView btn = new TextView(this);
            btn.setText(qNames[q]);
            btn.setGravity(Gravity.CENTER);
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
            btn.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
            LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            qp.setMargins(q > 0 ? dpToPx(3) : 0, 0, 0, 0);
            btn.setLayoutParams(qp);
            qBtns[q] = btn;
            qBtnRow.addView(btn);
        }
        ccHeader.addView(qBtnRow);
        chartCard.addView(ccHeader);

        // 월 버튼 행
        LinearLayout mRow = new LinearLayout(this);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mrLp.setMargins(0, 0, 0, dpToPx(10));
        mRow.setLayoutParams(mrLp);
        chartCard.addView(mRow);

        // 수입/지출 요약 행
        LinearLayout sumRow = new LinearLayout(this);
        sumRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.setMargins(0, 0, 0, dpToPx(10));
        sumRow.setLayoutParams(srLp);

        LinearLayout inBox = new LinearLayout(this);
        inBox.setOrientation(LinearLayout.VERTICAL);
        inBox.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inLp.setMargins(0, 0, dpToPx(4), 0);
        inBox.setLayoutParams(inLp);
        android.graphics.drawable.GradientDrawable inBg = new android.graphics.drawable.GradientDrawable();
        inBg.setColor(Color.parseColor("#F0FFF4"));
        inBg.setCornerRadius(dpToPx(8));
        inBox.setBackground(inBg);
        inBox.setPadding(0, dpToPx(8), 0, dpToPx(8));
        TextView tvInLbl = new TextView(this);
        tvInLbl.setText("수입");
        tvInLbl.setTextColor(Color.parseColor("#27AE60"));
        tvInLbl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        tvInLbl.setGravity(Gravity.CENTER);
        inBox.addView(tvInLbl);
        final TextView tvInSum = new TextView(this);
        tvInSum.setTextColor(Color.parseColor("#27AE60"));
        tvInSum.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvInSum.setTypeface(null, android.graphics.Typeface.BOLD);
        tvInSum.setGravity(Gravity.CENTER);
        inBox.addView(tvInSum);
        sumRow.addView(inBox);

        LinearLayout outBox = new LinearLayout(this);
        outBox.setOrientation(LinearLayout.VERTICAL);
        outBox.setGravity(Gravity.CENTER);
        outBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.graphics.drawable.GradientDrawable outBg = new android.graphics.drawable.GradientDrawable();
        outBg.setColor(Color.parseColor("#FFF5F5"));
        outBg.setCornerRadius(dpToPx(8));
        outBox.setBackground(outBg);
        outBox.setPadding(0, dpToPx(8), 0, dpToPx(8));
        TextView tvOutLbl = new TextView(this);
        tvOutLbl.setText("지출");
        tvOutLbl.setTextColor(Color.parseColor("#E24B4A"));
        tvOutLbl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        tvOutLbl.setGravity(Gravity.CENTER);
        outBox.addView(tvOutLbl);
        final TextView tvOutSum = new TextView(this);
        tvOutSum.setTextColor(Color.parseColor("#E24B4A"));
        tvOutSum.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvOutSum.setTypeface(null, android.graphics.Typeface.BOLD);
        tvOutSum.setGravity(Gravity.CENTER);
        outBox.addView(tvOutSum);
        sumRow.addView(outBox);
        chartCard.addView(sumRow);

        // 차트 WebView
        android.webkit.WebView chartView = new android.webkit.WebView(this);
        chartView.getSettings().setJavaScriptEnabled(true);
        chartView.setBackgroundColor(Color.TRANSPARENT);
        chartView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        chartView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(200)));
        chartCard.addView(chartView);

        // ── 렌더 Runnable ─────────────────────────────────────
        final Runnable[] render = {null};
        render[0] = () -> {
            // 계좌 카드 스타일
            for (int i = 0; i < 4; i++) {
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dpToPx(12));
                if (i == selAcct[0]) {
                    bg.setColor(Color.parseColor(acctColors[i]));
                    acctCards[i].setBackground(bg);
                    ((TextView)acctCards[i].getChildAt(0)).setTextColor(Color.parseColor("#FFFFFF99".substring(0,9)));
                    // 흰색 반투명 텍스트
                    acctCards[i].getChildAt(0).setAlpha(0.85f);
                    ((TextView)acctCards[i].getChildAt(0)).setTextColor(Color.WHITE);
                    ((TextView)acctCards[i].getChildAt(1)).setTextColor(Color.WHITE);
                } else {
                    bg.setColor(Color.parseColor(acctBgCol[i]));
                    bg.setStroke(dpToPx(1), Color.parseColor(acctColors[i]));
                    acctCards[i].setBackground(bg);
                    acctCards[i].getChildAt(0).setAlpha(1f);
                    ((TextView)acctCards[i].getChildAt(0)).setTextColor(Color.parseColor(acctColors[i]));
                    ((TextView)acctCards[i].getChildAt(1)).setTextColor(Color.parseColor("#333333"));
                }
            }
            // 분기 버튼
            for (int q = 0; q < 4; q++) {
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dpToPx(12));
                if (q == selQ[0]) {
                    bg.setColor(Color.parseColor(acctColors[selAcct[0]]));
                    qBtns[q].setBackground(bg);
                    qBtns[q].setTextColor(Color.WHITE);
                } else {
                    bg.setColor(Color.parseColor("#F0F0F0"));
                    qBtns[q].setBackground(bg);
                    qBtns[q].setTextColor(Color.parseColor("#999999"));
                }
            }
            // 월 버튼 재생성
            mRow.removeAllViews();
            int[] months = qMonths[selQ[0]];
            for (int mi = 0; mi < months.length; mi++) {
                final int mo = months[mi];
                TextView mb = new TextView(this);
                mb.setText(mo + "월");
                mb.setGravity(Gravity.CENTER);
                mb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                mb.setPadding(0, dpToPx(7), 0, dpToPx(7));
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                mp.setMargins(mi > 0 ? dpToPx(5) : 0, 0, 0, 0);
                mb.setLayoutParams(mp);
                android.graphics.drawable.GradientDrawable mbBg =
                        new android.graphics.drawable.GradientDrawable();
                mbBg.setCornerRadius(dpToPx(8));
                if (mo == selM[0]) {
                    mbBg.setColor(Color.parseColor(acctBgCol[selAcct[0]]));
                    mbBg.setStroke(dpToPx(2), Color.parseColor(acctColors[selAcct[0]]));
                    mb.setTextColor(Color.parseColor(acctColors[selAcct[0]]));
                    mb.setTypeface(null, android.graphics.Typeface.BOLD);
                } else {
                    mbBg.setColor(Color.parseColor("#F5F5F5"));
                    mb.setTextColor(Color.parseColor("#999999"));
                    mb.setTypeface(null, android.graphics.Typeface.NORMAL);
                }
                mb.setBackground(mbBg);
                mb.setOnClickListener(vv -> {
                    selM[0] = (selM[0] == mo) ? -1 : mo;
                    render[0].run();
                });
                mRow.addView(mb);
            }
            // 차트 데이터
            final String[] labels;
            final int[] inArr, outArr;
            final String titleText;
            if (selM[0] == -1) {
                labels = new String[months.length];
                inArr  = new int[months.length];
                outArr = new int[months.length];
                for (int mi = 0; mi < months.length; mi++) {
                    int mo2 = months[mi];
                    labels[mi] = mo2 + "월";
                    for (int w = 0; w < 5; w++) {
                        inArr[mi]  += mIn[selAcct[0]][mo2][w];
                        outArr[mi] += mOut[selAcct[0]][mo2][w];
                    }
                }
                titleText = acctNames[selAcct[0]] + "  ·  " + qNames[selQ[0]];
            } else {
                int wkCnt = 0;
                for (int w = 4; w >= 0; w--) {
                    if (mIn[selAcct[0]][selM[0]][w]>0 || mOut[selAcct[0]][selM[0]][w]>0) {
                        wkCnt=w+1; break;
                    }
                }
                if (wkCnt==0) wkCnt=4;
                labels = new String[wkCnt];
                inArr  = new int[wkCnt];
                outArr = new int[wkCnt];
                for (int w=0;w<wkCnt;w++) {
                    labels[w]=(w+1)+"주차";
                    inArr[w] =mIn[selAcct[0]][selM[0]][w];
                    outArr[w]=mOut[selAcct[0]][selM[0]][w];
                }
                titleText = acctNames[selAcct[0]] + "  ·  " + selM[0] + "월";
            }
            tvChartTitle.setText(titleText);
            int inTotal=0, outTotal=0;
            for (int v:inArr)  inTotal+=v;
            for (int v:outArr) outTotal+=v;
            tvInSum.setText(String.format("%,d원", inTotal));
            tvOutSum.setText(String.format("%,d원", outTotal));

            StringBuilder lb=new StringBuilder("[");
            for (String l:labels) lb.append("'").append(l).append("',");
            lb.append("]");
            StringBuilder ia=new StringBuilder("[");
            for (int v:inArr) ia.append(v).append(",");
            ia.append("]");
            StringBuilder oa=new StringBuilder("[");
            for (int v:outArr) oa.append(v).append(",");
            oa.append("]");
            String acctColor = acctColors[selAcct[0]];

            String html="<!DOCTYPE html><html><head>"
                    +"<meta name='viewport' content='width=device-width,initial-scale=1'>"
                    +"<style>body{margin:0;padding:0;background:transparent;}</style></head><body>"
                    +"<div style='width:100%;height:195px;position:relative;'><canvas id='c'></canvas></div>"
                    +"<script src='https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.js'></script>"
                    +"<script>new Chart(document.getElementById('c'),{type:'bar',data:{"
                    +"labels:"+lb+","
                    +"datasets:["
                    +"{label:'수입',data:"+ia+",backgroundColor:'rgba(39,174,96,0.55)',borderColor:'#27AE60',borderWidth:1.5,borderRadius:5},"
                    +"{label:'지출',data:"+oa+",backgroundColor:'rgba(226,75,74,0.55)',borderColor:'#E24B4A',borderWidth:1.5,borderRadius:5}"
                    +"]},options:{responsive:true,maintainAspectRatio:false,"
                    +"plugins:{legend:{display:false}},"
                    +"scales:{x:{grid:{display:false},ticks:{font:{size:11},color:'#aaa'}},"
                    +"y:{grid:{color:'rgba(0,0,0,0.04)'},ticks:{font:{size:10},color:'#aaa',"
                    +"callback:function(v){return v>=10000?(v/10000).toFixed(0)+'만':v;}}}}}});"
                    +"</script></body></html>";
            chartView.loadDataWithBaseURL(
                    "https://cdnjs.cloudflare.com", html, "text/html", "UTF-8", null);
        };

        // 계좌 카드 클릭 재연결
        for (int i=0;i<4;i++) {
            final int ai=i;
            acctCards[i].setOnClickListener(v -> { selAcct[0]=ai; selM[0]=-1; render[0].run(); });
        }
        // 분기 버튼 클릭 재연결
        for (int q=0;q<4;q++) {
            final int qi=q;
            qBtns[q].setOnClickListener(v -> { selQ[0]=qi; selM[0]=-1; render[0].run(); });
        }

        // ── sms_raw에서 파싱 후 stats.txt 저장 ───────────────
        tvChartTitle.setText("데이터 로딩 중...");
        render[0].run();

        new Thread(() -> {
            try {
                java.util.Calendar cal2 = java.util.Calendar.getInstance();
                int curYear2 = cal2.get(java.util.Calendar.YEAR);
                String rawFile = SmsReceiver.getSmsRawFile(curYear2);
                android.util.Log.d("STATS","읽을 파일: "+rawFile);

                DriveReadHelper reader = new DriveReadHelper(PinActivity.this);
                final String[] rawContent = {null};
                final Object lock = new Object();

                reader.readFile(rawFile, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fc) {
                        synchronized(lock){ rawContent[0]=fc; lock.notifyAll(); }
                    }
                    @Override public void onFailure(String error) {
                        android.util.Log.e("STATS","읽기 실패: "+error);
                        synchronized(lock){ rawContent[0]=""; lock.notifyAll(); }
                    }
                });
                synchronized(lock){ if(rawContent[0]==null) lock.wait(15000); }

                String raw = rawContent[0];
                if (raw==null||raw.trim().isEmpty()) {
                    runOnUiThread(()->tvChartTitle.setText("SMS 데이터 없음"));
                    return;
                }

                StringBuilder statsSb = new StringBuilder();
                String[] parts = raw.split("-----------------------------------\\r?\\n");
                int parsedCount=0;
                for (String block:parts) {
                    if (block.trim().isEmpty()) continue;
                    for (int ai=0;ai<4;ai++) {
                        if (!block.contains(acctKeys[ai])) continue;
                        java.util.regex.Matcher dm=java.util.regex.Pattern
                                .compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(block);
                        if (!dm.find()) continue;
                        int yr =Integer.parseInt(dm.group(1));
                        int mo =Integer.parseInt(dm.group(2));
                        int day=Integer.parseInt(dm.group(3));
                        int wk =Math.min((day-1)/7,4);
                        java.util.regex.Matcher am=java.util.regex.Pattern
                                .compile("([입출]금)\\s*([\\d,]+)원").matcher(block);
                        if (!am.find()) continue;
                        int amt=Integer.parseInt(am.group(2).replace(",",""));
                        int in2 =am.group(1).equals("입금")?amt:0;
                        int out2=am.group(1).equals("출금")?amt:0;
                        if (mo>=1&&mo<=12) {
                            mIn[ai][mo][wk] +=in2;
                            mOut[ai][mo][wk]+=out2;
                        }
                        statsSb.append(acctKeys[ai]).append("|")
                                .append(yr).append("|").append(mo).append("|")
                                .append(wk).append("|").append(in2).append("|")
                                .append(out2).append("\n");
                        parsedCount++;
                        break;
                    }
                }
                android.util.Log.d("STATS","파싱: "+parsedCount+"건");
                final String sc=statsSb.toString().trim();
                if (!sc.isEmpty()) {
                    new Thread(()->{
                        try { new DriveUploadHelper(PinActivity.this).uploadFileSync(sc,"stats.txt"); }
                        catch(Exception ignored){}
                    }).start();
                }
                runOnUiThread(()->render[0].run());
            } catch(Exception e) {
                android.util.Log.e("STATS","오류: "+e.getMessage());
                runOnUiThread(()->tvChartTitle.setText("오류: "+e.getMessage()));
            }
        }).start();

        // ── 헤더 페이드 아웃 (1.8초 후) ─────────────────────
        statsHeaderBar.post(() -> {
            final int origH = statsHeaderBar.getMeasuredHeight() + dpToPx(12);
            statsHeaderBar.postDelayed(() -> {
                android.animation.ValueAnimator anim =
                        android.animation.ValueAnimator.ofFloat(1f, 0f);
                anim.setDuration(600);
                anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                anim.addUpdateListener(va -> {
                    float f = (float) va.getAnimatedValue();
                    statsHeaderBar.setAlpha(f);
                    int h = (int)(origH * f);
                    android.view.ViewGroup.LayoutParams vlp = statsHeaderBar.getLayoutParams();
                    vlp.height = Math.max(h - dpToPx(12), 0);
                    if (vlp instanceof LinearLayout.LayoutParams)
                        ((LinearLayout.LayoutParams) vlp).bottomMargin = (int)(dpToPx(12) * f);
                    statsHeaderBar.setLayoutParams(vlp);
                    statsHeaderBar.requestLayout();
                });
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        statsHeaderBar.setVisibility(View.GONE);
                    }
                });
                anim.start();
            }, 1800);
        });

        // ── 하단 버튼바 (통장 잔액 화면과 동일 구조) ─────────
        LinearLayout statsBtnBar = new LinearLayout(this);
        statsBtnBar.setOrientation(LinearLayout.HORIZONTAL);
        statsBtnBar.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable statsBtnBarBg =
                new android.graphics.drawable.GradientDrawable();
        statsBtnBarBg.setColor(Color.parseColor("#F5F3FA"));
        statsBtnBar.setBackground(statsBtnBarBg);
        statsBtnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        statsBtnBar.setClipChildren(false);
        statsBtnBar.setClipToPadding(false);
        statsBtnBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 돌아가기
        Button sbBack = new Button(this);
        sbBack.setText("← 돌아가기");
        sbBack.setBackground(makeShadowCardDrawable("#C8BFEF", 14, 6));
        sbBack.setTextColor(Color.parseColor("#4A3DBF"));
        sbBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        sbBack.setTypeface(null, android.graphics.Typeface.BOLD);
        sbBack.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams sbBackLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        sbBackLp.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
        sbBack.setLayoutParams(sbBackLp);
        sbBack.setOnClickListener(v -> {
            isOnSubScreen = false;
            if (isOwner) ownerMenuBuilder.build();
            else userMenuBuilder.build(false);
        });
        statsBtnBar.addView(sbBack);

        // 통장 잔액
        Button sbBal = new Button(this);
        sbBal.setText("💰 통장 잔액");
        sbBal.setBackground(makeShadowCardDrawable("#EDE9FF", 14, 6));
        sbBal.setTextColor(Color.parseColor("#4A3DBF"));
        sbBal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        sbBal.setTypeface(null, android.graphics.Typeface.BOLD);
        sbBal.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams sbBalLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        sbBalLp.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
        sbBal.setLayoutParams(sbBalLp);
        sbBal.setOnClickListener(v -> {
            showBalanceScreen();
        });
        statsBtnBar.addView(sbBal);

        // 선결제
        Button sbMeat = new Button(this);
        sbMeat.setText("🥩 선결제");
        sbMeat.setBackground(makeShadowCardDrawable("#EAFAF1", 14, 6));
        sbMeat.setTextColor(Color.parseColor("#1A7A4A"));
        sbMeat.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        sbMeat.setTypeface(null, android.graphics.Typeface.BOLD);
        sbMeat.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams sbMeatLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        sbMeatLp.setMargins(0, dpToPx(4), 0, dpToPx(4));
        sbMeat.setLayoutParams(sbMeatLp);
        sbMeat.setOnClickListener(v -> {
            showMeatClubScreen();
        });
        statsBtnBar.addView(sbMeat);

        root.addView(statsBtnBar);

        setContentView(root);
        isOnSubScreen=true;

        // 하단 navBar 높이 보정 (통장 잔액 화면과 동일)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(statsBtnBar, (v, insets) -> {
            int botInset = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            statsBtnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6) + botInset);
            return insets;
        });
    }

    private void showMemberListScreen() {
        showSingleImageScreen("📋 경로당 회원명부", "경로당 회원 명단을 확인합니다", "nature.png");
    }

    private void showBusDataManageScreen() {
        isOnSubScreen = true;
        isOnMenuScreen = false;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F4F8"));
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            root.setPadding(0, top, 0, 0);
            return insets;
        });

        // 헤더
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable hBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#0984E3"), Color.parseColor("#74B9FF")});
        hBg.setCornerRadii(new float[]{0,0,0,0,dpToPx(16),dpToPx(16),dpToPx(16),dpToPx(16)});
        header.setBackground(hBg);
        header.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        root.addView(header);

        TextView tvBack = new TextView(this);
        tvBack.setText("‹");
        tvBack.setTextColor(Color.WHITE);
        tvBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(24));
        tvBack.setTypeface(null, android.graphics.Typeface.BOLD);
        tvBack.setPadding(0, 0, dpToPx(12), 0);
        tvBack.setOnClickListener(v -> { isOnSubScreen = false; ownerMenuBuilder.build(); });
        header.addView(tvBack);

        LinearLayout hTxt = new LinearLayout(this);
        hTxt.setOrientation(LinearLayout.VERTICAL);
        hTxt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvTitle = new TextView(this);
        tvTitle.setText("버스 데이터 관리");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(17));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        hTxt.addView(tvTitle);
        TextView tvSub = new TextView(this);
        tvSub.setText("노선 · 정류장 DB 업데이트");
        tvSub.setTextColor(Color.parseColor("#D6EAF8"));
        tvSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        LinearLayout.LayoutParams subLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp2.setMargins(0, dpToPx(2), 0, 0);
        tvSub.setLayoutParams(subLp2);
        hTxt.addView(tvSub);
        header.addView(hTxt);

        // 컨텐츠
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dpToPx(16), dpToPx(20), dpToPx(16), dpToPx(20));
        content.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 노선 DB 카드 ──
        buildDbCard(content, "🚌", "버스 노선 DB",
                routeDbList != null && !routeDbList.isEmpty()
                        ? routeDbList.size() + "개 노선 (매일 자동 갱신)"
                        : "데이터 없음",
                routeDbList != null && !routeDbList.isEmpty(),
                "노선 DB는 앱 실행 시 매일 자동으로 갱신됩니다.",
                false, null);

        // ── 정류장 DB 카드 ──
        boolean hasStopDb = stopDbList != null && !stopDbList.isEmpty();
        TextView[] btnStopRef = {null};
        TextView[] descRef = {null};
        android.graphics.drawable.GradientDrawable[] btnBgRef = {null};

        LinearLayout stopCard = new LinearLayout(this);
        stopCard.setOrientation(LinearLayout.VERTICAL);
        stopCard.setBackground(makeShadowCardDrawable("#FFFFFF", 12, 5));
        stopCard.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        stopCard.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.setMargins(0, dpToPx(12), 0, 0);
        stopCard.setLayoutParams(scLp);

        // 카드 상단: 아이콘 + 제목 + 상태배지
        LinearLayout stopTop = new LinearLayout(this);
        stopTop.setOrientation(LinearLayout.HORIZONTAL);
        stopTop.setGravity(Gravity.CENTER_VERTICAL);
        stopTop.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvStopIcon = new TextView(this);
        tvStopIcon.setText("🚏");
        tvStopIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(22));
        tvStopIcon.setPadding(0, 0, dpToPx(10), 0);
        stopTop.addView(tvStopIcon);

        LinearLayout stopInfo = new LinearLayout(this);
        stopInfo.setOrientation(LinearLayout.VERTICAL);
        stopInfo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvStopTitle = new TextView(this);
        tvStopTitle.setText("정류장 DB");
        tvStopTitle.setTextColor(Color.parseColor("#1A1A2E"));
        tvStopTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
        tvStopTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        stopInfo.addView(tvStopTitle);
        TextView tvStopCount = new TextView(this);
        tvStopCount.setText(hasStopDb ? stopDbList.size() + "개 정류장" : "데이터 없음");
        tvStopCount.setTextColor(Color.parseColor("#555555"));
        tvStopCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
        stopInfo.addView(tvStopCount);
        stopTop.addView(stopInfo);

        // 상태 배지
        TextView tvStopBadge = new TextView(this);
        tvStopBadge.setText(hasStopDb ? "✓ 있음" : "! 없음");
        tvStopBadge.setTextColor(Color.WHITE);
        tvStopBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        tvStopBadge.setTypeface(null, android.graphics.Typeface.BOLD);
        tvStopBadge.setGravity(Gravity.CENTER);
        tvStopBadge.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setColor(Color.parseColor(hasStopDb ? "#27AE60" : "#E74C3C"));
        badgeBg.setCornerRadius(dpToPx(6));
        tvStopBadge.setBackground(badgeBg);
        stopTop.addView(tvStopBadge);
        stopCard.addView(stopTop);

        // 구분선
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#F0F0F0"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.setMargins(0, dpToPx(12), 0, dpToPx(12));
        div.setLayoutParams(divLp);
        stopCard.addView(div);

        // 설명 텍스트
        TextView tvStopDesc = new TextView(this);
        tvStopDesc.setText(hasStopDb
                ? "업데이트 파일이 있습니다.\n버튼을 눌러 정류장 DB를 갱신하세요."
                : "정류장 DB가 없습니다.\n버튼을 눌러 최초 생성을 진행하세요.");
        tvStopDesc.setTextColor(Color.parseColor(hasStopDb ? "#E67E22" : "#E74C3C"));
        tvStopDesc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.setMargins(0, 0, 0, dpToPx(12));
        tvStopDesc.setLayoutParams(descLp);
        stopCard.addView(tvStopDesc);
        descRef[0] = tvStopDesc;

        // 업데이트 버튼 (항상 활성)
        TextView btnStop = new TextView(this);
        btnStop.setText(hasStopDb ? "정류장 DB 업데이트" : "정류장 DB 생성");
        btnStop.setTextColor(Color.WHITE);
        btnStop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        btnStop.setTypeface(null, android.graphics.Typeface.BOLD);
        btnStop.setGravity(Gravity.CENTER);
        btnStop.setPadding(0, dpToPx(13), 0, dpToPx(13));
        android.graphics.drawable.GradientDrawable btnStopBg = new android.graphics.drawable.GradientDrawable();
        btnStopBg.setColor(Color.parseColor("#0984E3"));
        btnStopBg.setCornerRadius(dpToPx(10));
        btnStop.setBackground(btnStopBg);
        btnStop.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnStopRef[0] = btnStop;
        btnBgRef[0] = btnStopBg;

        btnStop.setOnClickListener(v -> showConfirmDialog("🚏", "정류장 DB 업데이트", "정류장 DB를 업데이트 하시겠습니까?\n\n대전 전체 정류장을 수집하여\nDrive에 업로드합니다.\n수 분이 소요됩니다.", () -> {
            btnStop.setText("⏳ 수집 중... (수분 소요)");
            btnStopBg.setColor(Color.parseColor("#AAAAAA"));
            btnStop.setEnabled(false);
            buildAndUploadStopDb(() -> {
                int cnt = stopDbList != null ? stopDbList.size() : 0;
                tvStopCount.setText(cnt + "개 정류장");
                tvStopBadge.setText("✓ 있음");
                badgeBg.setColor(Color.parseColor("#27AE60"));
                tvStopDesc.setText("정류장 DB가 최신 상태입니다.");
                tvStopDesc.setTextColor(Color.parseColor("#27AE60"));
                btnStop.setText("정류장 DB 업데이트");
                btnStopBg.setColor(Color.parseColor("#0984E3"));
                btnStop.setEnabled(true);
                android.widget.Toast.makeText(this,
                        "✓ " + cnt + "개 정류장 DB 업로드 완료!",
                        android.widget.Toast.LENGTH_LONG).show();
            }, null);
        }));
        stopCard.addView(btnStop);
        content.addView(stopCard);

        root.addView(content);

        // 하단 뒤로가기
        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.CENTER);
        btnBar.setBackgroundColor(Color.WHITE);
        btnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));
        LinearLayout.LayoutParams bbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnBar.setLayoutParams(bbLp);
        TextView btnBackBar = new TextView(this);
        btnBackBar.setText("← 돌아가기");
        btnBackBar.setTextColor(Color.parseColor("#0984E3"));
        btnBackBar.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        btnBackBar.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBackBar.setGravity(Gravity.CENTER);
        btnBackBar.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12));
        btnBackBar.setOnClickListener(v -> { isOnSubScreen = false; ownerMenuBuilder.build(); });
        btnBar.addView(btnBackBar);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(btnBar, (v, insets) -> {
            int bot = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            btnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6) + bot);
            return insets;
        });

        // root를 weight로 구성
        LinearLayout outerRoot = new LinearLayout(this);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackgroundColor(Color.parseColor("#F2F4F8"));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(outerRoot, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            outerRoot.setPadding(0, top, 0, 0);
            return insets;
        });
        root.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.setLayoutParams(rootLp);
        outerRoot.addView(root);
        outerRoot.addView(btnBar);
        setContentView(outerRoot);
    }

    /** 정류장 DB 업데이트 확인 커스텀 다이얼로그 */
    /** 공통 확인/취소 다이얼로그 (앞으로 모든 확인/취소는 이 함수 사용) */
    private void showConfirmDialog(String icon, String title, String message, Runnable onConfirm) {
        android.app.Dialog dlg = new android.app.Dialog(this,
                android.R.style.Theme_Material_Light_Dialog);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable dlgBg = new android.graphics.drawable.GradientDrawable();
        dlgBg.setColor(Color.WHITE);
        dlgBg.setCornerRadius(dpToPx(16));
        layout.setBackground(dlgBg);
        layout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));

        // 아이콘
        TextView tvIcon = new TextView(this);
        tvIcon.setText(icon);
        tvIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 36);
        tvIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iconLp.setMargins(0, 0, 0, dpToPx(10));
        tvIcon.setLayoutParams(iconLp);
        layout.addView(tvIcon);

        // 제목
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor("#0984E3"));
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(17));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, 0, 0, dpToPx(10));
        tvTitle.setLayoutParams(titleLp);
        layout.addView(tvTitle);

        // 구분선
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.setMargins(0, 0, 0, dpToPx(14));
        div.setLayoutParams(divLp);
        layout.addView(div);

        // 메시지
        TextView tvMsg = new TextView(this);
        tvMsg.setText(message);
        tvMsg.setTextColor(Color.parseColor("#444444"));
        tvMsg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvMsg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, 0, 0, dpToPx(20));
        tvMsg.setLayoutParams(msgLp);
        layout.addView(tvMsg);

        // 버튼 행
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 취소 버튼
        TextView btnCancel = new TextView(this);
        btnCancel.setText("취소");
        btnCancel.setTextColor(Color.parseColor("#888888"));
        btnCancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding(0, dpToPx(13), 0, dpToPx(13));
        android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
        cancelBg.setColor(Color.parseColor("#F0F0F0"));
        cancelBg.setCornerRadius(dpToPx(10));
        btnCancel.setBackground(cancelBg);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.setMargins(0, 0, dpToPx(8), 0);
        btnCancel.setLayoutParams(cancelLp);
        btnCancel.setOnClickListener(v -> dlg.dismiss());
        btnRow.addView(btnCancel);

        // 확인 버튼
        TextView btnOk = new TextView(this);
        btnOk.setText("확인");
        btnOk.setTextColor(Color.WHITE);
        btnOk.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnOk.setTypeface(null, android.graphics.Typeface.BOLD);
        btnOk.setGravity(Gravity.CENTER);
        btnOk.setPadding(0, dpToPx(13), 0, dpToPx(13));
        android.graphics.drawable.GradientDrawable okBg = new android.graphics.drawable.GradientDrawable();
        okBg.setColor(Color.parseColor("#0984E3"));
        okBg.setCornerRadius(dpToPx(10));
        btnOk.setBackground(okBg);
        btnOk.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnOk.setOnClickListener(v -> {
            dlg.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        btnRow.addView(btnOk);
        layout.addView(btnRow);

        dlg.setContentView(layout);
        dlg.setCancelable(true);
        // 다이얼로그 너비 설정
        if (dlg.getWindow() != null) {
            dlg.getWindow().setLayout(
                    (int)(getResources().getDisplayMetrics().widthPixels * 0.85),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        dlg.show();
    }

    private void buildDbCard(LinearLayout parent, String icon, String title,
                             String countText, boolean hasData, String desc,
                             boolean btnEnabled, Runnable onBtnClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeShadowCardDrawable("#FFFFFF", 12, 5));
        card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        card.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 0);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvIcon = new TextView(this);
        tvIcon.setText(icon);
        tvIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(22));
        tvIcon.setPadding(0, 0, dpToPx(10), 0);
        top.addView(tvIcon);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvTitle2 = new TextView(this);
        tvTitle2.setText(title);
        tvTitle2.setTextColor(Color.parseColor("#1A1A2E"));
        tvTitle2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
        tvTitle2.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(tvTitle2);
        TextView tvCount = new TextView(this);
        tvCount.setText(countText);
        tvCount.setTextColor(Color.parseColor("#555555"));
        tvCount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
        info.addView(tvCount);
        top.addView(info);

        TextView tvBadge2 = new TextView(this);
        tvBadge2.setText(hasData ? "✓ 있음" : "! 없음");
        tvBadge2.setTextColor(Color.WHITE);
        tvBadge2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        tvBadge2.setTypeface(null, android.graphics.Typeface.BOLD);
        tvBadge2.setGravity(Gravity.CENTER);
        tvBadge2.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        android.graphics.drawable.GradientDrawable b2Bg = new android.graphics.drawable.GradientDrawable();
        b2Bg.setColor(Color.parseColor(hasData ? "#27AE60" : "#AAAAAA"));
        b2Bg.setCornerRadius(dpToPx(6));
        tvBadge2.setBackground(b2Bg);
        top.addView(tvBadge2);
        card.addView(top);

        View divider3 = new View(this);
        divider3.setBackgroundColor(Color.parseColor("#F0F0F0"));
        LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp2.setMargins(0, dpToPx(12), 0, dpToPx(12));
        divider3.setLayoutParams(divLp2);
        card.addView(divider3);

        TextView tvDesc2 = new TextView(this);
        tvDesc2.setText(desc);
        tvDesc2.setTextColor(Color.parseColor("#888888"));
        tvDesc2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
        card.addView(tvDesc2);

        parent.addView(card);
    }

    private void showBusSearchScreen() {
        isOnSubScreen     = true;
        isOnMenuScreen    = false;
        isOnBalanceScreen = false;
        busBackStack.clear(); // 백스택 초기화

        // ── 루트: LinearLayout VERTICAL (월별 통계와 동일) ─
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F4F8"));
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        // 상단 statusBar inset → root에 직접 적용
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            root.setPadding(0, top, 0, 0);
            return insets;
        });

        // ── 헤더 바 (1.8초 후 자동 사라짐) ───────────────
        final LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable hGrad = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#0984E3"), Color.parseColor("#74B9FF")});
        hGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(16),dpToPx(16),dpToPx(16),dpToPx(16)});
        headerBar.setBackground(hGrad);
        headerBar.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hbLp.setMargins(0, 0, 0, dpToPx(0));
        headerBar.setLayoutParams(hbLp);

        android.widget.ImageView ivHeaderIcon = new android.widget.ImageView(this);
        android.graphics.Bitmap busHdrBmp = getBusIconWhite();
        if (busHdrBmp != null) {
            ivHeaderIcon.setImageBitmap(busHdrBmp);
        } else {
            ivHeaderIcon.setImageResource(android.R.drawable.ic_menu_directions);
        }
        ivHeaderIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams hIconLp = new LinearLayout.LayoutParams(dpToPx(30), dpToPx(30));
        hIconLp.setMargins(0, 0, dpToPx(10), 0);
        hIconLp.gravity = Gravity.CENTER_VERTICAL;
        ivHeaderIcon.setLayoutParams(hIconLp);
        headerBar.addView(ivHeaderIcon);

        LinearLayout headerTxt = new LinearLayout(this);
        headerTxt.setOrientation(LinearLayout.VERTICAL);
        headerTxt.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText("버스 노선 검색");
        tvHeaderTitle.setTextColor(Color.WHITE);
        tvHeaderTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
        tvHeaderTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        headerTxt.addView(tvHeaderTitle);
        TextView tvHeaderSub = new TextView(this);
        tvHeaderSub.setText("버스번호 · 정류장 · 장소로 검색");
        tvHeaderSub.setTextColor(Color.parseColor("#D6EAF8"));
        tvHeaderSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dpToPx(2), 0, 0);
        tvHeaderSub.setLayoutParams(subLp);
        headerTxt.addView(tvHeaderSub);
        headerBar.addView(headerTxt);
        root.addView(headerBar);

        // 1.8초 후 headerBar 자동 사라짐 (월별 통계/통장잔액과 동일)
        headerBar.post(() -> {
            final int origHeight = headerBar.getMeasuredHeight() + dpToPx(12);
            headerBar.postDelayed(() -> {
                android.animation.ValueAnimator anim =
                        android.animation.ValueAnimator.ofFloat(1f, 0f);
                anim.setDuration(600);
                anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                anim.addUpdateListener(va -> {
                    float f = (float) va.getAnimatedValue();
                    headerBar.setAlpha(f);
                    int h = (int)(origHeight * f);
                    android.view.ViewGroup.LayoutParams vlp = headerBar.getLayoutParams();
                    vlp.height = Math.max(h - dpToPx(12), 0);
                    if (vlp instanceof LinearLayout.LayoutParams)
                        ((LinearLayout.LayoutParams) vlp).bottomMargin = (int)(dpToPx(12) * f);
                    headerBar.setLayoutParams(vlp);
                    headerBar.requestLayout();
                });
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        headerBar.setVisibility(View.GONE);
                    }
                });
                anim.start();
            }, 1800);
        });

        // ── 통합 검색창 ───────────────────────────────────
        // ── 탭 + 검색창 영역 ──────────────────────────────
        LinearLayout searchArea = new LinearLayout(this);
        searchArea.setOrientation(LinearLayout.VERTICAL);
        searchArea.setBackgroundColor(Color.parseColor("#F2F4F8"));
        LinearLayout.LayoutParams saLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saLp.setMargins(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(4));
        searchArea.setLayoutParams(saLp);

        android.view.inputmethod.InputMethodManager immBus =
                (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);

        // 탭 행: [🚌 버스] [🚏 정류장]
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        trLp.setMargins(0, 0, 0, dpToPx(8));
        tabRow.setLayoutParams(trLp);

        final boolean[] isBusTab = busIsBusTab; // true=버스, false=정류장

        busTabBus = new TextView(this);
        TextView tabBus = busTabBus;
        busIsBusTab[0] = true;
        tabBus.setText("버스 번호 검색");
        tabBus.setGravity(Gravity.CENTER);
        tabBus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
        tabBus.setTypeface(null, android.graphics.Typeface.BOLD);
        tabBus.setPadding(dpToPx(12), dpToPx(11), dpToPx(12), dpToPx(11));

        busTabStop = new TextView(this);
        TextView tabStop = busTabStop;
        tabStop.setText("정류장 검색");
        tabStop.setGravity(Gravity.CENTER);
        tabStop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
        tabStop.setTypeface(null, android.graphics.Typeface.BOLD);
        tabStop.setPadding(dpToPx(12), dpToPx(11), dpToPx(12), dpToPx(11));

        // 탭 스타일 업데이트 함수 (선택색 연하게)
        android.graphics.drawable.GradientDrawable selBg = new android.graphics.drawable.GradientDrawable();
        selBg.setColor(Color.parseColor("#5BA9F0"));
        selBg.setCornerRadius(dpToPx(8));
        android.graphics.drawable.GradientDrawable unselBg = new android.graphics.drawable.GradientDrawable();
        unselBg.setColor(Color.WHITE);
        unselBg.setCornerRadius(dpToPx(8));
        unselBg.setStroke(dpToPx(1), Color.parseColor("#CCCCCC"));

        busUpdateTabStyle = () -> {
            if (isBusTab[0]) {
                android.graphics.drawable.GradientDrawable b1 = new android.graphics.drawable.GradientDrawable();
                b1.setColor(Color.parseColor("#5BA9F0")); b1.setCornerRadius(dpToPx(8));
                tabBus.setBackground(b1); tabBus.setTextColor(Color.WHITE);
                android.graphics.drawable.GradientDrawable b2 = new android.graphics.drawable.GradientDrawable();
                b2.setColor(Color.WHITE); b2.setCornerRadius(dpToPx(8));
                b2.setStroke(dpToPx(1), Color.parseColor("#CCCCCC"));
                tabStop.setBackground(b2); tabStop.setTextColor(Color.parseColor("#555555"));
            } else {
                android.graphics.drawable.GradientDrawable b1 = new android.graphics.drawable.GradientDrawable();
                b1.setColor(Color.WHITE); b1.setCornerRadius(dpToPx(8));
                b1.setStroke(dpToPx(1), Color.parseColor("#CCCCCC"));
                tabBus.setBackground(b1); tabBus.setTextColor(Color.parseColor("#555555"));
                android.graphics.drawable.GradientDrawable b2 = new android.graphics.drawable.GradientDrawable();
                b2.setColor(Color.parseColor("#5BA9F0")); b2.setCornerRadius(dpToPx(8));
                tabStop.setBackground(b2); tabStop.setTextColor(Color.WHITE);
            }
        };
        if (busUpdateTabStyle != null) busUpdateTabStyle.run();

        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tabLp1.setMargins(0, 0, dpToPx(6), 0);
        tabBus.setLayoutParams(tabLp1);
        tabStop.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabRow.addView(tabBus);
        tabRow.addView(tabStop);
        searchArea.addView(tabRow);

        // 검색창 행
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        RelativeLayout etWrapper = new RelativeLayout(this);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wLp.setMargins(0, 0, dpToPx(8), 0);
        etWrapper.setLayoutParams(wLp);

        android.widget.EditText etSearch = new android.widget.EditText(this);
        setBlackCursor(etSearch);
        busEtSearch = etSearch;
        etSearch.setHint("버스 번호 입력");
        etSearch.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etSearch.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        android.graphics.drawable.GradientDrawable eBg = new android.graphics.drawable.GradientDrawable();
        eBg.setColor(Color.WHITE);
        eBg.setCornerRadius(dpToPx(10));
        eBg.setStroke(dpToPx(1), Color.parseColor("#C8BFEF"));
        etSearch.setBackground(eBg);
        etSearch.setPadding(dpToPx(14), dpToPx(12), dpToPx(54), dpToPx(12));
        etSearch.setLayoutParams(new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT));
        etWrapper.addView(etSearch);

        TextView btnClear = new TextView(this);
        btnClear.setText("삭제");
        btnClear.setTextColor(Color.WHITE);
        btnClear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(10));
        btnClear.setTypeface(null, android.graphics.Typeface.BOLD);
        btnClear.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable clrBg = new android.graphics.drawable.GradientDrawable();
        clrBg.setColor(Color.parseColor("#E74C3C"));
        clrBg.setCornerRadius(dpToPx(4));
        btnClear.setBackground(clrBg);
        btnClear.setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3));
        btnClear.setVisibility(android.view.View.GONE);
        RelativeLayout.LayoutParams xLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        xLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        xLp.addRule(RelativeLayout.CENTER_VERTICAL);
        xLp.setMargins(0, 0, dpToPx(8), 0);
        btnClear.setLayoutParams(xLp);
        etWrapper.addView(btnClear);
        searchRow.addView(etWrapper);

        TextView btnGo = new TextView(this);
        btnGo.setText("검색");
        btnGo.setTextColor(Color.parseColor("#555555"));
        btnGo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnGo.setTypeface(null, android.graphics.Typeface.BOLD);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setPadding(dpToPx(18), dpToPx(12), dpToPx(18), dpToPx(12));
        android.graphics.drawable.GradientDrawable goBg = new android.graphics.drawable.GradientDrawable();
        goBg.setColor(Color.parseColor("#E0E0E0"));
        goBg.setCornerRadius(dpToPx(10));
        btnGo.setBackground(goBg);
        searchRow.addView(btnGo);
        searchArea.addView(searchRow);

        busSearchArea = searchArea;
        root.addView(searchArea);


        // ── 타임라인 고정 헤더 (708번+방향카드) ──────────
        LinearLayout fixedHeader = new LinearLayout(this);
        fixedHeader.setOrientation(LinearLayout.VERTICAL);
        fixedHeader.setBackgroundColor(Color.parseColor("#F2F4F8"));
        fixedHeader.setVisibility(android.view.View.GONE);
        fixedHeader.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        busFixedHeader = fixedHeader;
        root.addView(fixedHeader);

        // ── 스크롤 (weight=1 로 남은 공간 모두 차지) ─────
        ScrollView sv = new ScrollView(this);
        busTimelineSv = sv; // 타임라인 스크롤 제어용
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        sv.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                if (immBus != null) immBus.hideSoftInputFromWindow(sv.getWindowToken(), 0);
                if (getCurrentFocus() != null) getCurrentFocus().clearFocus();
            }
            return false;
        });
        LinearLayout svInner = new LinearLayout(this);
        svInner.setOrientation(LinearLayout.VERTICAL);
        svInner.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(12));
        sv.addView(svInner);

        // 즐겨찾기 섹션
        LinearLayout favSection = new LinearLayout(this);
        favSection.setOrientation(LinearLayout.VERTICAL);
        favSection.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        svInner.addView(favSection);
        busFavSection2 = favSection;
        busFavSection  = favSection; // refreshBusFavorites에서 참조할 수 있도록 즉시 설정

        // 결과 컨테이너 (단일)
        LinearLayout resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        svInner.addView(resultContainer);
        busResultContainer = resultContainer;

        // 즐겨찾기 로드 & 렌더링
        refreshBusFavorites(favSection, resultContainer);

        root.addView(sv);

        // ── 오너 전용: 정류장 DB 업데이트 버튼 ────────────
        if (isOwner) {
            LinearLayout ownerBar = new LinearLayout(this);
            ownerBar.setOrientation(LinearLayout.HORIZONTAL);
            ownerBar.setGravity(Gravity.CENTER);
            ownerBar.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), 0);
            ownerBar.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView btnStopDb = new TextView(this);
            String stopStatus = stopDbList != null && !stopDbList.isEmpty()
                    ? "🚏 정류장 DB: " + stopDbList.size() + "개 ✓ (업데이트)"
                    : "🚏 정류장 DB 생성 (최초 1회)";
            btnStopDb.setText(stopStatus);
            btnStopDb.setTextColor(Color.parseColor("#0984E3"));
            btnStopDb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            btnStopDb.setGravity(Gravity.CENTER);
            btnStopDb.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
            android.graphics.drawable.GradientDrawable sdbBg = new android.graphics.drawable.GradientDrawable();
            sdbBg.setColor(Color.parseColor("#EBF5FB")); sdbBg.setCornerRadius(dpToPx(8));
            sdbBg.setStroke(dpToPx(1), Color.parseColor("#AED6F1"));
            btnStopDb.setBackground(sdbBg);
            btnStopDb.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            btnStopDb.setOnClickListener(v -> {
                btnStopDb.setText("⏳ 수집 중... (수분 소요)");
                btnStopDb.setEnabled(false);
                buildAndUploadStopDb(() -> {
                    int cnt = stopDbList != null ? stopDbList.size() : 0;
                    btnStopDb.setText("🚏 정류장 DB: " + cnt + "개 ✓ (업데이트)");
                    btnStopDb.setEnabled(true);
                    android.widget.Toast.makeText(this,
                            "정류장 DB " + cnt + "개 Drive 업로드 완료!",
                            android.widget.Toast.LENGTH_LONG).show();
                }, null);
            });
            ownerBar.addView(btnStopDb);
            root.addView(ownerBar);
        }


        // ── 검색 로직 ─────────────────────────────────────
        android.os.Handler debounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable[] debounceRunnable = {null};

        Runnable doSearch = () -> {
            String kw = etSearch.getText().toString().trim();
            if (kw.isEmpty()) { resultContainer.removeAllViews(); return; }
            if (isBusTab[0]) busScreenSearchByNo(kw, resultContainer);
            else             busScreenSearchByStop(kw, resultContainer);
        };

        // 탭 클릭
        tabBus.setOnClickListener(v -> {
            if (!isBusTab[0]) {
                isBusTab[0] = true;
                if (busUpdateTabStyle != null) busUpdateTabStyle.run();
                etSearch.setHint("버스 번호 입력");
                etSearch.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                etSearch.setText("");
                resultContainer.removeAllViews();
                if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.VISIBLE);
                if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.GONE); busFixedHeader.removeAllViews(); }
            }
        });
        tabStop.setOnClickListener(v -> {
            if (isBusTab[0]) {
                isBusTab[0] = false;
                if (busUpdateTabStyle != null) busUpdateTabStyle.run();
                etSearch.setHint("정류장 이름 입력");
                etSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                etSearch.setText("");
                resultContainer.removeAllViews();
                if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.VISIBLE);
                if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.GONE); busFixedHeader.removeAllViews(); }
            }
        });

        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String kw = s.toString().trim();
                btnClear.setVisibility(kw.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                if (busFavSection2 != null)
                    busFavSection2.setVisibility(kw.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
                if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.GONE); busFixedHeader.removeAllViews(); }
                if (debounceRunnable[0] != null) debounceHandler.removeCallbacks(debounceRunnable[0]);
                if (kw.isEmpty()) { resultContainer.removeAllViews(); return; }
                if (isBusTab[0]) {
                    // 버스: 200ms 자동검색
                    debounceRunnable[0] = doSearch;
                    debounceHandler.postDelayed(debounceRunnable[0], 200);
                } else if (stopDbList != null && !stopDbList.isEmpty()) {
                    // 정류장: DB 메모리 있으면 300ms 자동검색
                    if (kw.length() >= 2) {
                        debounceRunnable[0] = doSearch;
                        debounceHandler.postDelayed(debounceRunnable[0], 300);
                    }
                }
                // DB 없으면 검색버튼/엔터만
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });

        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            resultContainer.removeAllViews();
            if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.VISIBLE);
            if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.GONE); busFixedHeader.removeAllViews(); }
            etSearch.requestFocus();
            if (immBus != null) immBus.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });

        btnGo.setOnClickListener(v -> {
            if (immBus != null) immBus.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            etSearch.clearFocus();
            doSearch.run();
        });

        etSearch.setOnEditorActionListener((tv2, actionId, event) -> {
            if (immBus != null) immBus.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            etSearch.clearFocus();
            doSearch.run();
            return true;
        });

        // ── 하단 돌아가기 버튼 + navigationBar inset ──────
        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // 하단 navBar inset 동적 적용 (월별 통계와 동일)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(btnBar, (v, insets) -> {
            int bot = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            btnBar.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6) + bot);
            return insets;
        });

        Button btnBack = new Button(this);
        btnBack.setText("← 돌아가기");
        btnBack.setBackground(makeShadowCardDrawable("#C8BFEF", 14, 6));
        btnBack.setTextColor(Color.parseColor("#4A3DBF"));
        btnBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnBack.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBack.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        backLp.setMargins(0, 0, 0, 0);
        btnBack.setLayoutParams(backLp);
        btnBack.setOnClickListener(v -> busNavigateBack());
        btnBar.addView(btnBack);

        // 새로고침 버튼
        Button btnRefreshBar = new Button(this);
        btnRefreshBar.setText("↺ 새로고침");
        btnRefreshBar.setBackground(makeShadowCardDrawable("#D5EFF9", 14, 6));
        btnRefreshBar.setTextColor(Color.parseColor("#0984E3"));
        btnRefreshBar.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        btnRefreshBar.setTypeface(null, android.graphics.Typeface.BOLD);
        btnRefreshBar.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, dpToPx(50), 1f);
        btnRefreshBar.setLayoutParams(refreshLp);
        btnRefreshBar.setOnClickListener(v -> {
            if (!busBackStack.isEmpty()) {
                String[] cur = busBackStack.peek();
                if ("timeline".equals(cur[0])) {
                    // 타임라인 새로고침 - 자동갱신 재시작
                    if (busRefreshRunnable != null) busRefreshHandler.removeCallbacks(busRefreshRunnable);
                    busResultContainer.removeAllViews();
                    busFixedHeader.removeAllViews();
                    busScreenLoadStops(cur[1], cur[2], busResultContainer, cur[3], cur[4]);
                } else if ("arrival".equals(cur[0])) {
                    // 도착화면 새로고침 - 시간정보(API)만 재조회
                    arrivalSessionCache.remove(cur[1]);
                    // container만 지우고 헤더는 유지 (태그 유지)
                    if (busResultContainer != null) busResultContainer.removeAllViews();
                    final String nId = cur[1], nNm = cur[2], nNo = cur[3], fRno2 = cur[4];
                    new Thread(() -> fetchAndRenderArrival(nId, nNm, nNo, fRno2, busResultContainer, true)).start();
                }
            }
        });
        btnBar.addView(btnRefreshBar);
        root.addView(btnBar);

        setContentView(root);
    }

    /** 검색 패널 공통 빌더 (type: 0=버스번호, 1=정류장, 2=장소) */
    private void buildSearchPanel(LinearLayout panel, LinearLayout resultContainer, int type) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);

        // ── 검색창 행 ─────────────────────────────────────
        // EditText를 RelativeLayout으로 감싸서 오른쪽 끝에 X버튼 오버레이
        RelativeLayout etWrapper = new RelativeLayout(this);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wLp.setMargins(0, 0, dpToPx(8), 0);
        etWrapper.setLayoutParams(wLp);

        android.widget.EditText etSearch = new android.widget.EditText(this);
        setBlackCursor(etSearch);
        String[] hints = {"버스 번호 입력 (예: 708, 104)", "정류장 이름 입력 (예: 지족동)", "장소 이름 입력 (예: 유성온천)"};
        int[] inputTypes = {android.text.InputType.TYPE_CLASS_NUMBER,
                android.text.InputType.TYPE_CLASS_TEXT, android.text.InputType.TYPE_CLASS_TEXT};
        etSearch.setHint(hints[type]);
        etSearch.setInputType(inputTypes[type]);
        etSearch.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        etSearch.setCursorVisible(true);
        try {
            // 커서 색상을 진한 파란색으로
            java.lang.reflect.Field f = android.widget.TextView.class.getDeclaredField("mCursorDrawableRes");
            f.setAccessible(true);
        } catch (Exception ignored) {}
        // 커서 색상: API 29+ 직접 지원
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            etSearch.setTextCursorDrawable(null); // 기본 커서 사용
        }
        android.graphics.drawable.GradientDrawable eBg = new android.graphics.drawable.GradientDrawable();
        eBg.setColor(Color.WHITE);
        eBg.setCornerRadius(dpToPx(10));
        eBg.setStroke(dpToPx(1), Color.parseColor("#C8BFEF"));
        etSearch.setBackground(eBg);
        // 오른쪽 패딩 넉넉히 줘서 X버튼과 안 겹치게
        etSearch.setPadding(dpToPx(12), dpToPx(10), dpToPx(50), dpToPx(10));
        etSearch.setLayoutParams(new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT));
        etWrapper.addView(etSearch);

        // X 버튼 (처음엔 숨김)
        TextView btnClear = new TextView(this);
        btnClear.setText("삭제");
        btnClear.setTextColor(Color.WHITE);
        btnClear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(10));
        btnClear.setTypeface(null, android.graphics.Typeface.BOLD);
        btnClear.setGravity(Gravity.CENTER);
        btnClear.setVisibility(android.view.View.GONE);
        android.graphics.drawable.GradientDrawable clrBg = new android.graphics.drawable.GradientDrawable();
        clrBg.setColor(Color.parseColor("#E74C3C"));
        clrBg.setCornerRadius(dpToPx(4));
        btnClear.setBackground(clrBg);
        btnClear.setPadding(dpToPx(5), dpToPx(3), dpToPx(5), dpToPx(3));
        RelativeLayout.LayoutParams xLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        xLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        xLp.addRule(RelativeLayout.CENTER_VERTICAL);
        xLp.setMargins(0, 0, dpToPx(6), 0);
        btnClear.setLayoutParams(xLp);
        btnClear.setOnClickListener(v -> {
            etSearch.setText("");
            etSearch.requestFocus();
            if (imm != null) imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            resultContainer.removeAllViews();
        });
        etWrapper.addView(btnClear);

        // 텍스트 변화 → X버튼 표시/숨김 + 버스번호 실시간 검색
        android.os.Handler debounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable[] debounceRunnable = {null};
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String kw = s.toString().trim();
                // X버튼 표시 제어
                btnClear.setVisibility(kw.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                // 버스번호 탭만 실시간 검색
                if (type != 0) return;
                if (debounceRunnable[0] != null) debounceHandler.removeCallbacks(debounceRunnable[0]);
                if (kw.isEmpty()) { resultContainer.removeAllViews(); return; }
                debounceRunnable[0] = () -> busScreenSearchByNo(kw, resultContainer);
                debounceHandler.postDelayed(debounceRunnable[0], 300);
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.setMargins(0, 0, 0, dpToPx(10));
        searchRow.setLayoutParams(srLp);
        searchRow.addView(etWrapper);

        TextView btnGo = new TextView(this);
        btnGo.setText("검색");
        btnGo.setTextColor(Color.WHITE);
        btnGo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        btnGo.setTypeface(null, android.graphics.Typeface.BOLD);
        btnGo.setGravity(Gravity.CENTER);
        btnGo.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
        android.graphics.drawable.GradientDrawable bBg = new android.graphics.drawable.GradientDrawable();
        bBg.setColor(Color.parseColor("#0984E3"));
        bBg.setCornerRadius(dpToPx(10));
        btnGo.setBackground(bBg);
        searchRow.addView(btnGo);

        panel.addView(searchRow);

        // ── 스크롤시 키보드 숨김 ──────────────────────────
        // resultContainer를 터치 감지 ScrollView 안에 넣기
        // 단, resultContainer 자체에 터치 리스너 → 스크롤 시작하면 키보드 내림
        resultContainer.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                etSearch.clearFocus();
            }
            return false; // 이벤트 소비하지 않음 (스크롤 정상 동작)
        });
        panel.addView(resultContainer);

        btnGo.setOnClickListener(v -> {
            String kw = etSearch.getText().toString().trim();
            if (kw.isEmpty()) return;
            if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            etSearch.clearFocus();
            if (type == 0) busScreenSearchByNo(kw, resultContainer);
            else           busScreenSearchByStop(kw, resultContainer);
        });
        etSearch.setOnEditorActionListener((tv, actionId, event) -> {
            String kw = etSearch.getText().toString().trim();
            if (!kw.isEmpty()) {
                if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                etSearch.clearFocus();
                if (type == 0) busScreenSearchByNo(kw, resultContainer);
                else           busScreenSearchByStop(kw, resultContainer);
            }
            return true;
        });
    }

    /** 버스 노선 검색 화면 - 버스번호로 노선 검색 */
    private void busScreenSearchByNo(String routeNo, LinearLayout container) {
        container.removeAllViews();

        // 메모리 DB 있으면 백그라운드에서 즉시 검색
        if (routeDbList != null) {
            new Thread(() -> {
                java.util.List<String[]> result = new java.util.ArrayList<>();
                for (String[] p : routeDbList) {
                    if (p[1].startsWith(routeNo)) result.add(p);
                }
                runOnUiThread(() -> {
                    container.removeAllViews();
                    if (result.isEmpty()) {
                        TextView tv = new TextView(this);
                        tv.setText(routeNo + "번 노선을 찾을 수 없습니다");
                        tv.setTextColor(Color.parseColor("#AAAAAA"));
                        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                        container.addView(tv);
                    } else {
                        renderBusRouteCards(result, routeNo, container);
                    }
                });
            }).start();
            return;
        }
        TextView tvL = new TextView(this); tvL.setText("검색 중...");
        tvL.setTextColor(Color.parseColor("#AAAAAA"));
        tvL.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
        container.addView(tvL);
        new Thread(() -> {
            try {
                String url = BUS_BASE2 + "BusRouteInfoInqireService/getRouteNoList"
                        + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                        + "&routeNo=" + java.net.URLEncoder.encode(routeNo, "UTF-8")
                        + "&numOfRows=20&pageNo=1&_type=xml";
                String xml = httpGet(url);
                java.util.List<String[]> routes = new java.util.ArrayList<>();
                for (String item : xml.split("<item>")) {
                    if (!item.contains("<routeid>")) continue;
                    String rno = tag(item, "routeno");
                    if (!rno.startsWith(routeNo)) continue;
                    routes.add(new String[]{tag(item,"routeid"), rno,
                            tag(item,"startnodenm"), tag(item,"endnodenm"),
                            tag(item,"routetp")});
                }
                runOnUiThread(() -> {
                    container.removeAllViews();
                    if (routes.isEmpty()) {
                        TextView tv = new TextView(this);
                        tv.setText(routeNo + "번 노선을 찾을 수 없습니다");
                        tv.setTextColor(Color.parseColor("#AAAAAA"));
                        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                        container.addView(tv);
                    } else {
                        renderBusRouteCards(routes, routeNo, container);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { container.removeAllViews();
                    TextView tv = new TextView(this); tv.setText("검색 실패: " + e.getMessage());
                    tv.setTextColor(Color.parseColor("#E74C3C"));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                    container.addView(tv); });
            }
        }).start();
    }

    /** 노선 카드 렌더링 공통 */
    private void renderBusRouteCards(java.util.List<String[]> routes, String routeNo,
                                     LinearLayout container) {
        for (String[] r : routes) {
            String routeTp = r.length > 4 ? r[4] : "";
            LinearLayout card = makeBusCard(
                    r[1] + "번",
                    (r[2].isEmpty()?"기점":r[2]) + "  ↔  " + (r[3].isEmpty()?"종점":r[3]),
                    "", "#0984E3", routeTp);
            card.setOnClickListener(v -> {
                if (busEtSearch != null) busEtSearch.setText("");
                if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.GONE);
                busScreenLoadStops(r[0], r[1], container);
            });
            container.addView(card);
        }
    }

    /** 노선별 정류소 목록 로드 */
    private void busScreenLoadStops(String routeId, String routeNo, LinearLayout container) {
        busScreenLoadStops(routeId, routeNo, container, "forward", "");
    }


    private void startBusAutoRefresh(
            String routeId, String routeNo, String direction, LinearLayout container,
            String sNm, String eNm, String stF, String etF, String interval, String rTp,
            java.util.List<String[]> stops, String turnOrd) {
        if (busRefreshRunnable != null) busRefreshHandler.removeCallbacks(busRefreshRunnable);
        final String fRId=routeId, fRNo=routeNo, fDir=direction;
        final String fSNm=sNm, fENm=eNm, fStF=stF, fEtF=etF, fInterval=interval, fRTp=rTp, fTurnOrd=turnOrd;
        final java.util.List<String[]> fStops = stops;
        busRefreshRunnable = new Runnable() {
            @Override public void run() {
                if (!isOnSubScreen) return;
                // 검색화면(busSearchArea 보임)이면 갱신 안 함
                if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.VISIBLE) {
                    busRefreshHandler.postDelayed(this, 20000);
                    return;
                }
                new Thread(() -> {
                    try {
                        String lcXml = httpGet(BUS_BASE2 + "BusLcInfoInqireService/getRouteAcctoBusLcList"
                                + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                                + "&routeId=" + fRId + "&numOfRows=50&pageNo=1&_type=xml");
                        int cnt = 0;
                        try { cnt = Integer.parseInt(tag(lcXml,"totalCount")); } catch(Exception ig){}
                        java.util.Set<String> ordSet = new java.util.HashSet<>();
                        java.util.Map<String,String> vehMap = new java.util.HashMap<>();
                        for (String item : lcXml.split("<item>")) {
                            String ord=tag(item,"nodeord"), vno=tag(item,"vehicleno");
                            if (!ord.isEmpty()) { ordSet.add(ord); if (!vno.isEmpty()) vehMap.put(ord,vno); }
                        }
                        final int fCnt=cnt;
                        final java.util.Set<String> fOrd=ordSet;
                        final java.util.Map<String,String> fVeh=vehMap;
                        runOnUiThread(() -> {
                            if (!isOnSubScreen) return;
                            // 검색화면이면 타임라인 갱신 차단
                            if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.VISIBLE) return;
                            renderBusTimeline(fRId, fRNo, fDir, container,
                                    fSNm, fENm, fStF, fEtF, fInterval, fRTp,
                                    fCnt, fVeh, fOrd, fStops, fTurnOrd);
                        });
                    } catch (Exception ignored) {}
                }).start();
                busRefreshHandler.postDelayed(this, 20000);
            }
        };
        busRefreshHandler.postDelayed(busRefreshRunnable, 0); // 즉시 첫 갱신
    }

    /** 버스 화면 뒤로가기 - 백스택 기반 */
    private void busNavigateBack() {
        // 현재 화면을 스택에서 제거
        if (!busBackStack.isEmpty()) busBackStack.pop();

        if (busBackStack.isEmpty()) {
            // 스택 비면 버스 검색 화면으로 - 완전 리셋
            if (busRefreshRunnable != null) {
                busRefreshHandler.removeCallbacks(busRefreshRunnable);
                busRefreshRunnable = null;
            }
            if (busFixedHeader != null) { busFixedHeader.removeAllViews(); busFixedHeader.setVisibility(android.view.View.GONE); }
            if (busSearchArea != null) busSearchArea.setVisibility(android.view.View.VISIBLE);
            if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.VISIBLE);
            // 입력창 초기화 + 탭을 버스번호 검색으로 리셋
            if (busEtSearch != null) {
                busEtSearch.setText("");
                busEtSearch.setHint("버스 번호 입력");
                busEtSearch.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            }
            // 탭 버스번호 검색으로 리셋
            busIsBusTab[0] = true;
            if (busUpdateTabStyle != null) busUpdateTabStyle.run();
            // 키보드 숨김
            android.view.inputmethod.InputMethodManager immBack =
                (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (immBack != null && busEtSearch != null)
                immBack.hideSoftInputFromWindow(busEtSearch.getWindowToken(), 0);
            // 결과 컨테이너 초기화
            // 검색화면 완전 리셋 (즐겨찾기 제외)
            if (busEtSearch != null) {
                busEtSearch.setText("");
                busEtSearch.setHint("버스 번호 입력");
                busEtSearch.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            }
            busIsBusTab[0] = true;
            if (busUpdateTabStyle != null) busUpdateTabStyle.run();
            // 키보드 숨김
            android.view.inputmethod.InputMethodManager immBack2 =
                (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (immBack2 != null && busEtSearch != null)
                immBack2.hideSoftInputFromWindow(busEtSearch.getWindowToken(), 0);
            // 결과 컨테이너 초기화
            if (busResultContainer != null) {
                busResultContainer.removeAllViews();
                busResultContainer.post(() -> busResultContainer.removeAllViews());
            }
            // 즐겨찾기 변경됐을 때만 갱신
            if (busFavDirty && busFavSection != null && busResultContainer != null) {
                refreshBusFavorites(busFavSection, busResultContainer);
                busFavDirty = false;
            }
            return;
        }

        // 이전 화면 복원
        String[] prev = busBackStack.peek();
        busBackStack.pop();

        String type = prev[0];
        if ("timeline".equals(type)) {
            String routeId = prev[1], routeNo = prev[2], dir = prev[3], rtp = prev[4];
            if (busFixedHeader != null) { busFixedHeader.removeAllViews(); busFixedHeader.setVisibility(android.view.View.GONE); }
            if (busResultContainer != null) busResultContainer.removeAllViews();
            busScreenLoadStops(routeId, routeNo, busResultContainer, dir, rtp);
        } else if ("arrival".equals(type)) {
            String nodeId = prev[1], nodeNm = prev[2], nodeNo = prev[3], filter = prev[4];
            if (busFixedHeader != null) { busFixedHeader.removeAllViews(); }
            if (busResultContainer != null) busResultContainer.removeAllViews();
            busScreenLoadArrival(nodeId, nodeNm, nodeNo, filter, busResultContainer);
        } else {
            // search
            if (busRefreshRunnable != null) {
                busRefreshHandler.removeCallbacks(busRefreshRunnable);
                busRefreshRunnable = null;
            }
            if (busFixedHeader != null) { busFixedHeader.removeAllViews(); busFixedHeader.setVisibility(android.view.View.GONE); }
            if (busSearchArea != null) busSearchArea.setVisibility(android.view.View.VISIBLE);
            if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.VISIBLE);
            if (busResultContainer != null) busResultContainer.removeAllViews();
            // 즐겨찾기 변경됐을 때만 갱신
            if (busFavDirty && busFavSection != null && busResultContainer != null) {
                refreshBusFavorites(busFavSection, busResultContainer);
                busFavDirty = false;
            }
        }
    }

    private void busScreenLoadStops(String routeId, String routeNo, LinearLayout container,
                                    String direction, String routeType) {
        // 백스택에 타임라인 저장 (같은 routeId timeline이 top이면 중복 push 방지)
        boolean alreadyTimeline = !busBackStack.isEmpty()
            && "timeline".equals(busBackStack.peek()[0])
            && routeId.equals(busBackStack.peek()[1]);
        if (!alreadyTimeline) {
            busBackStack.push(new String[]{"timeline", routeId, routeNo, direction, routeType});
        } else {
            // 방향만 업데이트
            busBackStack.peek()[3] = direction;
        }
        container.removeAllViews();
        // 기존 자동 갱신 중단
        if (busRefreshRunnable != null) {
            busRefreshHandler.removeCallbacks(busRefreshRunnable);
            busRefreshRunnable = null;
        }
        if (busSearchArea != null) busSearchArea.setVisibility(android.view.View.GONE);

        android.view.inputmethod.InputMethodManager immStop =
                (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (immStop != null) immStop.hideSoftInputFromWindow(container.getWindowToken(), 0);
        if (getCurrentFocus() != null) getCurrentFocus().clearFocus();

        final String CACHE_PREF = "bus_cache";
        final String cKey = "route_" + routeId;
        android.content.SharedPreferences cache = getSharedPreferences(CACHE_PREF, MODE_PRIVATE);
        boolean hasCachedStatic = cache.contains(cKey + "_startNm")
                && !cache.getString(cKey + "_stops", "").isEmpty();

        if (hasCachedStatic) {
            // ── 캐시 있음: 즉시 정적 UI 그리기 + 실시간만 백그라운드 ──
            String startNm   = cache.getString(cKey + "_startNm",   "기점");
            String endNm     = cache.getString(cKey + "_endNm",     "종점");
            String startTime = cache.getString(cKey + "_startTime", "");
            String endTime   = cache.getString(cKey + "_endTime",   "");
            String interval  = cache.getString(cKey + "_interval",  "");
            String rTp       = routeType.isEmpty() ? cache.getString(cKey + "_rTp", "") : routeType;
            String stF = startTime.length()==4 ? startTime.substring(0,2)+":"+startTime.substring(2) : startTime;
            String etF = endTime.length()==4   ? endTime.substring(0,2)+":"+endTime.substring(2)   : endTime;

            java.util.List<String[]> stops = new java.util.ArrayList<>();
            for (String line : cache.getString(cKey + "_stops", "").split(";")) {
                String[] parts = line.split("\\|", -1);
                if (parts.length == 4) stops.add(parts);
            }
            boolean isReverse = "reverse".equals(direction);
            if (isReverse) java.util.Collections.reverse(stops);

            final String fStartNm = startNm, fEndNm = endNm;
            final String fStF = stF, fEtF = etF, fInterval = interval, fRTp = rTp;
            final java.util.List<String[]> fStops = stops;

            // 실시간 데이터 자리 확보 (runningCount=0, busOrdSet 빈값으로 즉시 그리기)
            renderBusTimeline(routeId, routeNo, direction, container,
                    fStartNm, fEndNm, fStF, fEtF, fInterval, fRTp,
                    0, new java.util.HashMap<>(), new java.util.HashSet<>(), fStops,
                    cache.getString(cKey+"_turnOrd",""));

            // 실시간 버스 위치 백그라운드 업데이트
            new Thread(() -> {
                try {
                    String lcXml = httpGet(BUS_BASE2 + "BusLcInfoInqireService/getRouteAcctoBusLcList"
                            + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                            + "&routeId=" + routeId + "&numOfRows=50&pageNo=1&_type=xml");
                    int cnt = 0;
                    try { cnt = Integer.parseInt(tag(lcXml,"totalCount")); } catch (Exception ig) {}
                    java.util.Set<String> ordSet = new java.util.HashSet<>();
                    java.util.Map<String,String> vehMap = new java.util.HashMap<>();
                    for (String item : lcXml.split("<item>")) {
                        String ord = tag(item,"nodeord"), vno = tag(item,"vehicleno");
                        if (!ord.isEmpty()) { ordSet.add(ord); if (!vno.isEmpty()) vehMap.put(ord,vno); }
                    }
                    final int fCnt = cnt;
                    final java.util.Set<String> fOrd = ordSet;
                    final java.util.Map<String,String> fVeh = vehMap;
                    final String fTurnOrd = cache.getString(cKey+"_turnOrd","");
                    runOnUiThread(() -> {
                        renderBusTimeline(routeId, routeNo, direction, container,
                                fStartNm, fEndNm, fStF, fEtF, fInterval, fRTp,
                                fCnt, fVeh, fOrd, fStops, fTurnOrd);
                        // 30초마다 자동 갱신 시작
                        startBusAutoRefresh(routeId, routeNo, direction, container,
                                fStartNm, fEndNm, fStF, fEtF, fInterval, fRTp, fStops, fTurnOrd);
                    });
                } catch (Exception ignored) {}
            }).start();

        } else {
            // ── 캐시 없음: 로딩 표시 후 전체 API 호출 ──
            TextView tvL = new TextView(this); tvL.setText("노선 정보 불러오는 중...");
            tvL.setTextColor(Color.parseColor("#AAAAAA"));
            tvL.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            container.addView(tvL);

            new Thread(() -> {
                try {
                    // ① 노선 상세
                    String infoXml = httpGet(BUS_BASE2 + "BusRouteInfoInqireService/getRouteInfoIem"
                            + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                            + "&routeId=" + routeId + "&_type=xml");
                    String startNm   = tag(infoXml,"startnodenm");
                    String endNm     = tag(infoXml,"endnodenm");
                    String startTime = tag(infoXml,"startvehicletime");
                    String endTime   = tag(infoXml,"endvehicletime");
                    String interval  = tag(infoXml,"intervaltime");
                    String rTp       = routeType.isEmpty() ? tag(infoXml,"routetp") : routeType;
                    String turnOrd   = tag(infoXml,"turnnodeord");
                    String stF = startTime.length()==4 ? startTime.substring(0,2)+":"+startTime.substring(2) : startTime;
                    String etF = endTime.length()==4   ? endTime.substring(0,2)+":"+endTime.substring(2)   : endTime;
                    cache.edit()
                            .putString(cKey+"_startNm", startNm).putString(cKey+"_endNm", endNm)
                            .putString(cKey+"_startTime", startTime).putString(cKey+"_endTime", endTime)
                            .putString(cKey+"_interval", interval).putString(cKey+"_rTp", rTp)
                            .putString(cKey+"_turnOrd", turnOrd).apply();

                    // ② 실시간
                    String lcXml = httpGet(BUS_BASE2 + "BusLcInfoInqireService/getRouteAcctoBusLcList"
                            + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                            + "&routeId=" + routeId + "&numOfRows=50&pageNo=1&_type=xml");
                    int cnt = 0;
                    try { cnt = Integer.parseInt(tag(lcXml,"totalCount")); } catch (Exception ig) {}
                    java.util.Set<String> ordSet = new java.util.HashSet<>();
                    java.util.Map<String,String> vehMap = new java.util.HashMap<>();
                    for (String item : lcXml.split("<item>")) {
                        String ord = tag(item,"nodeord"), vno = tag(item,"vehicleno");
                        if (!ord.isEmpty()) { ordSet.add(ord); if (!vno.isEmpty()) vehMap.put(ord,vno); }
                    }

                    // ③ 정류소 목록
                    String stXml = httpGet(BUS_BASE2 + "BusRouteInfoInqireService/getRouteAcctoThrghSttnList"
                            + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                            + "&routeId=" + routeId + "&numOfRows=100&pageNo=1&_type=xml");
                    java.util.List<String[]> stops = new java.util.ArrayList<>();
                    StringBuilder sb = new StringBuilder();
                    for (String item : stXml.split("<item>")) {
                        if (!item.contains("<nodeid>")) continue;
                        String[] stop = {tag(item,"nodeid"),tag(item,"nodenm"),
                                tag(item,"nodeord"),tag(item,"nodeno")};
                        stops.add(stop);
                        if (sb.length()>0) sb.append(";");
                        sb.append(stop[0]).append("|").append(stop[1]).append("|")
                                .append(stop[2]).append("|").append(stop[3]);
                    }
                    cache.edit().putString(cKey+"_stops", sb.toString()).apply();

                    boolean isReverse = "reverse".equals(direction);
                    if (isReverse) java.util.Collections.reverse(stops);

                    final int fCnt = cnt;
                    final java.util.Set<String> fOrd = ordSet;
                    final java.util.Map<String,String> fVeh = vehMap;
                    final java.util.List<String[]> fStops = stops;
                    final String fStartNm = startNm.isEmpty()?"기점":startNm;
                    final String fEndNm   = endNm.isEmpty()  ?"종점":endNm;
                    final String fStF=stF, fEtF=etF, fInterval=interval, fRTp=rTp;
                    final String fTurnOrd2 = cache.getString(cKey+"_turnOrd","");

                    runOnUiThread(() -> {
                        renderBusTimeline(routeId, routeNo, direction, container,
                                fStartNm, fEndNm, fStF, fEtF, fInterval, fRTp,
                                fCnt, fVeh, fOrd, fStops, fTurnOrd2);
                        startBusAutoRefresh(routeId, routeNo, direction, container,
                                fStartNm, fEndNm, fStF, fEtF, fInterval, fRTp, fStops, fTurnOrd2);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { container.removeAllViews();
                        TextView tv = new TextView(this); tv.setText("조회 실패: "+e.getMessage());
                        tv.setTextColor(Color.parseColor("#E74C3C"));
                        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                        container.addView(tv); });
                }
            }).start();
        }
    }
    private void busScreenSearchByStop(String keyword, LinearLayout container) {
        container.removeAllViews();

        // ① 메모리 DB (Drive에서 받은 파일) → 즉시 백그라운드 검색
        if (stopDbList != null && !stopDbList.isEmpty()) {
            new Thread(() -> {
                java.util.List<String[]> result = new java.util.ArrayList<>();
                String kw = keyword.toLowerCase();
                for (String[] p : stopDbList) {
                    if (p[1].toLowerCase().contains(kw)) {
                        result.add(p);
                        if (result.size() >= 30) break;
                    }
                }
                if (!result.isEmpty()) {
                    runOnUiThread(() -> {
                        container.removeAllViews();
                        renderStopCards(result, keyword, container);
                    });
                    return;
                }
                // 로컬 DB에 없으면 API로 폴백
                try {
                    String url = BUS_BASE2 + "BusSttnInfoInqireService/getSttnNoList"
                            + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                            + "&nodeNm=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                            + "&numOfRows=30&pageNo=1&_type=xml";
                    String xml = httpGet(url);
                    java.util.List<String[]> apiStops = new java.util.ArrayList<>();
                    for (String item : xml.split("<item>")) {
                        if (!item.contains("<nodeid>")) continue;
                        apiStops.add(new String[]{tag(item,"nodeid"), tag(item,"nodenm"), tag(item,"nodeno")});
                    }
                    runOnUiThread(() -> {
                        container.removeAllViews();
                        if (apiStops.isEmpty()) {
                            TextView tv = new TextView(this);
                            tv.setText("'" + keyword + "' 정류소를 찾을 수 없습니다");
                            tv.setTextColor(Color.parseColor("#AAAAAA"));
                            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                            container.addView(tv);
                        } else {
                            renderStopCards(apiStops, keyword, container);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        container.removeAllViews();
                        TextView tv = new TextView(this);
                        tv.setText("'" + keyword + "' 정류소를 찾을 수 없습니다");
                        tv.setTextColor(Color.parseColor("#AAAAAA"));
                        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                        container.addView(tv);
                    });
                }
            }).start();
            return;
        }

        // ② 세션 캐시 hit → 즉시 표시
        java.util.List<String[]> cached = stopSearchCache.get(keyword);
        if (cached != null) {
            if (cached.isEmpty()) {
                TextView tv = new TextView(this);
                tv.setText("'" + keyword + "' 정류소를 찾을 수 없습니다");
                tv.setTextColor(Color.parseColor("#AAAAAA"));
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                container.addView(tv);
            } else {
                renderStopCards(cached, keyword, container);
            }
            return;
        }

        // ③ API 호출 + 세션 캐시 저장
        TextView tvL = new TextView(this); tvL.setText("정류소 검색 중...");
        tvL.setTextColor(Color.parseColor("#AAAAAA"));
        tvL.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
        container.addView(tvL);
        new Thread(() -> {
            try {
                String url = BUS_BASE2 + "BusSttnInfoInqireService/getSttnNoList"
                        + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                        + "&nodeNm=" + java.net.URLEncoder.encode(keyword, "UTF-8")
                        + "&numOfRows=30&pageNo=1&_type=xml";
                String xml = httpGet(url);
                java.util.List<String[]> stops = new java.util.ArrayList<>();
                for (String item : xml.split("<item>")) {
                    if (!item.contains("<nodeid>")) continue;
                    stops.add(new String[]{tag(item,"nodeid"), tag(item,"nodenm"), tag(item,"nodeno")});
                }
                stopSearchCache.put(keyword, stops);
                runOnUiThread(() -> {
                    container.removeAllViews();
                    if (stops.isEmpty()) {
                        TextView tv = new TextView(this);
                        tv.setText("'" + keyword + "' 정류소를 찾을 수 없습니다");
                        tv.setTextColor(Color.parseColor("#AAAAAA"));
                        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                        container.addView(tv);
                    } else {
                        renderStopCards(stops, keyword, container);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> { container.removeAllViews();
                    TextView tv = new TextView(this); tv.setText("검색 실패: " + e.getMessage());
                    tv.setTextColor(Color.parseColor("#E74C3C"));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                    container.addView(tv); });
            }
        }).start();
    }

    /** 정류소 카드 렌더링 공통 */
    private void renderStopCards(java.util.List<String[]> stops, String keyword, LinearLayout container) {
        for (String[] s : stops) {
            LinearLayout card = makeBusCard(s[1],
                    s[2].isEmpty() ? "" : s[2],
                    "", "#0984E3");
            card.setOnClickListener(v -> {
                android.view.inputmethod.InputMethodManager imm2 =
                    (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm2 != null) imm2.hideSoftInputFromWindow(v.getWindowToken(), 0);
                if (getCurrentFocus() != null) getCurrentFocus().clearFocus();
                if (busSearchArea  != null) busSearchArea.setVisibility(android.view.View.GONE);
                if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.GONE);
                busScreenLoadArrival(s[0], s[1], s[2], "", busResultContainer);
            });
            container.addView(card);
        }
    }

    /** 로컬 정류장 DB에서 키워드 검색 */


    /** 버스 타임라인 UI 렌더링 (정적+실시간 데이터 합산) */
    private void renderBusTimeline(
            String routeId, String routeNo, String direction, LinearLayout container,
            String fStartNm, String fEndNm, String fStF, String fEtF, String fInterval, String fRTp,
            int fRunning, java.util.Map<String,String> fBusVehicle,
            java.util.Set<String> busOrdSet, java.util.List<String[]> stops, String turnOrd) {
        // ★★★ 절대 규칙: 검색화면이 열려있으면 타임라인 렌더링 완전 차단
        if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.VISIBLE) return;

        container.removeAllViews();
        // ★ 절대 규칙: 타임라인 진입 시 검색화면 반드시 숨김
        if (busSearchArea  != null) busSearchArea.setVisibility(android.view.View.GONE);
        if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.GONE);
        if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.VISIBLE); busFixedHeader.setTag("timeline"); }
        // forward 방향 전환 시 맨 위로 스크롤
        if ("forward".equals(busPendingScrollDir)) {
            busPendingScrollDir = null;
            if (busTimelineSv != null) busTimelineSv.post(() -> busTimelineSv.smoothScrollTo(0, 0));
        }

        // ── 헤더: ‹ [배지] 번호 + 즐겨찾기 ─────────────────
        LinearLayout topHeader = new LinearLayout(this);
        topHeader.setOrientation(LinearLayout.HORIZONTAL);
        topHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thLp.setMargins(0, 0, 0, dpToPx(6));
        topHeader.setLayoutParams(thLp);

        TextView btnBack2 = new TextView(this);
        btnBack2.setText("\u2039");
        btnBack2.setTextColor(Color.parseColor("#0984E3"));
        btnBack2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(24));
        btnBack2.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBack2.setPadding(0, 0, dpToPx(8), 0);
        btnBack2.setOnClickListener(v -> {
            // 자동 갱신 중단
            if (busRefreshRunnable != null) {
                busRefreshHandler.removeCallbacks(busRefreshRunnable);
                busRefreshRunnable = null;
            }
            // 고정 헤더 숨김
            if (busFixedHeader != null) {
                busFixedHeader.setVisibility(android.view.View.GONE);
                busFixedHeader.removeAllViews();
            }
            if (busSearchArea != null) busSearchArea.setVisibility(android.view.View.VISIBLE);
            if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.GONE); busFixedHeader.removeAllViews(); }
            if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.VISIBLE);
            if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.GONE); busFixedHeader.removeAllViews(); }
            container.removeAllViews();
        });
        topHeader.addView(btnBack2);

        String[] badge = routeTypeBadge(fRTp);
        if (!badge[0].isEmpty()) {
            TextView tvBadge = new TextView(this);
            tvBadge.setText(badge[0]);
            tvBadge.setTextColor(Color.WHITE);
            tvBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            tvBadge.setGravity(Gravity.CENTER);
            tvBadge.setPadding(dpToPx(7), dpToPx(3), dpToPx(7), dpToPx(3));
            android.graphics.drawable.GradientDrawable bBg = new android.graphics.drawable.GradientDrawable();
            bBg.setColor(Color.parseColor(badge[1]));
            bBg.setCornerRadius(dpToPx(4));
            tvBadge.setBackground(bBg);
            LinearLayout.LayoutParams bdLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bdLp.setMargins(0, 0, dpToPx(8), 0);
            tvBadge.setLayoutParams(bdLp);
            topHeader.addView(tvBadge);
        }
        TextView tvRouteNo = new TextView(this);
        tvRouteNo.setText(routeNo);
        tvRouteNo.setTextColor(Color.parseColor("#1A1A2E"));
        tvRouteNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(20));
        tvRouteNo.setShadowLayer(5f, 0f, 2f, 0x50000000);
        tvRouteNo.setTypeface(null, android.graphics.Typeface.BOLD);
        topHeader.addView(tvRouteNo);

        View hSpace = new View(this);
        hSpace.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        topHeader.addView(hSpace);

        // 노선 즐겨찾기 버튼
        boolean isRevDir = "reverse".equals(direction);
        String dirLabel  = isRevDir ? fStartNm : fEndNm;
        String shortDir  = dirLabel + " 방면";
        String routeFavKey = "fav_route_" + routeId + "_" + direction;
        boolean isRouteFav = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(routeFavKey, false);

        TextView tvRouteStar = new TextView(this);
        tvRouteStar.setText("즐겨찾기");
        tvRouteStar.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
        tvRouteStar.setTypeface(null, android.graphics.Typeface.BOLD);
        tvRouteStar.setGravity(Gravity.CENTER);
        tvRouteStar.setPadding(dpToPx(9), dpToPx(5), dpToPx(9), dpToPx(5));
        android.graphics.drawable.GradientDrawable rsStarBg = new android.graphics.drawable.GradientDrawable();
        rsStarBg.setCornerRadius(dpToPx(5));
        if (isRouteFav) {
            rsStarBg.setColor(Color.parseColor("#F39C12"));
            rsStarBg.setStroke(dpToPx(1), Color.parseColor("#F39C12"));
            tvRouteStar.setTextColor(Color.WHITE);
        } else {
            rsStarBg.setColor(Color.WHITE);
            rsStarBg.setStroke(dpToPx(1), Color.parseColor("#AAAAAA"));
            tvRouteStar.setTextColor(Color.parseColor("#888888"));
        }
        tvRouteStar.setBackground(rsStarBg);
        LinearLayout.LayoutParams rsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rsLp.gravity = Gravity.CENTER_VERTICAL;
        tvRouteStar.setLayoutParams(rsLp);

        tvRouteStar.setOnClickListener(vr -> {
            boolean wasFav2 = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(routeFavKey, false);
            if (wasFav2) {
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .remove(routeFavKey)
                        .remove("fav_route_no_"    + routeId + "_" + direction)
                        .remove("fav_route_dir_"   + routeId + "_" + direction)
                        .remove("fav_route_id_"    + routeId + "_" + direction)
                        .remove("fav_route_dirkey_"+ routeId + "_" + direction)
                        .remove("fav_route_memo_"  + routeId + "_" + direction).apply();
                android.graphics.drawable.GradientDrawable offBg = new android.graphics.drawable.GradientDrawable();
                offBg.setCornerRadius(dpToPx(5)); offBg.setColor(Color.WHITE);
                offBg.setStroke(dpToPx(1), Color.parseColor("#AAAAAA"));
                tvRouteStar.setTextColor(Color.parseColor("#888888"));
                tvRouteStar.setBackground(offBg);
                android.widget.Toast.makeText(this, routeNo + "번 즐겨찾기 해제", android.widget.Toast.LENGTH_SHORT).show();
                busFavDirty = true;
            } else {
                String existingMemo = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                        .getString("fav_route_memo_" + routeId + "_" + direction, "");

                // ── 커스텀 즐겨찾기 다이얼로그 ──
                android.app.Dialog favDlg = new android.app.Dialog(this,
                        android.R.style.Theme_Material_Light_Dialog);
                LinearLayout dlgLayout = new LinearLayout(this);
                dlgLayout.setOrientation(LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable dlgCardBg = new android.graphics.drawable.GradientDrawable();
                dlgCardBg.setColor(Color.WHITE);
                dlgCardBg.setCornerRadius(dpToPx(16));
                dlgLayout.setBackground(dlgCardBg);
                dlgLayout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));

                // 제목
                TextView tvDlgTitle = new TextView(this);
                tvDlgTitle.setText(routeNo + "번  " + shortDir);
                tvDlgTitle.setTextColor(Color.parseColor("#0984E3"));
                tvDlgTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
                tvDlgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvDlgTitle.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                dtLp.setMargins(0, 0, 0, dpToPx(14));
                tvDlgTitle.setLayoutParams(dtLp);
                dlgLayout.addView(tvDlgTitle);

                // 구분선
                View favDiv = new View(this);
                favDiv.setBackgroundColor(Color.parseColor("#EEEEEE"));
                LinearLayout.LayoutParams favDivLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                favDivLp.setMargins(0, 0, 0, dpToPx(14));
                favDiv.setLayoutParams(favDivLp);
                dlgLayout.addView(favDiv);

                // 메모 라벨
                TextView tvMemoLabel = new TextView(this);
                tvMemoLabel.setText("메모 (선택)");
                tvMemoLabel.setTextColor(Color.parseColor("#555555"));
                tvMemoLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                LinearLayout.LayoutParams mlLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                mlLp.setMargins(0, 0, 0, dpToPx(6));
                tvMemoLabel.setLayoutParams(mlLp);
                dlgLayout.addView(tvMemoLabel);

                // 메모 입력
                android.widget.EditText etMemo = new android.widget.EditText(this);
                setBlackCursor(etMemo);
                etMemo.setHint("예) 출근길, 집앞 정류장");
                etMemo.setText(existingMemo);
                etMemo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
                etMemo.setSingleLine(true);
                android.graphics.drawable.GradientDrawable memoBg = new android.graphics.drawable.GradientDrawable();
                memoBg.setColor(Color.parseColor("#F8F8F8"));
                memoBg.setCornerRadius(dpToPx(10));
                memoBg.setStroke(dpToPx(1), Color.parseColor("#DDDDDD"));
                etMemo.setBackground(memoBg);
                etMemo.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
                LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                etLp.setMargins(0, 0, 0, dpToPx(20));
                etMemo.setLayoutParams(etLp);
                dlgLayout.addView(etMemo);

                // 버튼 행
                LinearLayout btnRow = new LinearLayout(this);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                TextView btnFavCancel = new TextView(this);
                btnFavCancel.setText("취소");
                btnFavCancel.setTextColor(Color.parseColor("#888888"));
                btnFavCancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnFavCancel.setTypeface(null, android.graphics.Typeface.BOLD);
                btnFavCancel.setGravity(Gravity.CENTER);
                btnFavCancel.setPadding(0, dpToPx(13), 0, dpToPx(13));
                android.graphics.drawable.GradientDrawable cancelFavBg = new android.graphics.drawable.GradientDrawable();
                cancelFavBg.setColor(Color.parseColor("#F0F0F0"));
                cancelFavBg.setCornerRadius(dpToPx(10));
                btnFavCancel.setBackground(cancelFavBg);
                LinearLayout.LayoutParams cancelFavLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                cancelFavLp.setMargins(0, 0, dpToPx(8), 0);
                btnFavCancel.setLayoutParams(cancelFavLp);
                btnFavCancel.setOnClickListener(vv -> favDlg.dismiss());
                btnRow.addView(btnFavCancel);

                TextView btnFavOk = new TextView(this);
                btnFavOk.setText("확인");
                btnFavOk.setTextColor(Color.WHITE);
                btnFavOk.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnFavOk.setTypeface(null, android.graphics.Typeface.BOLD);
                btnFavOk.setGravity(Gravity.CENTER);
                btnFavOk.setPadding(0, dpToPx(13), 0, dpToPx(13));
                android.graphics.drawable.GradientDrawable okFavBg = new android.graphics.drawable.GradientDrawable();
                okFavBg.setColor(Color.parseColor("#5BA9F0"));
                okFavBg.setCornerRadius(dpToPx(10));
                btnFavOk.setBackground(okFavBg);
                btnFavOk.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                btnFavOk.setOnClickListener(vv -> {
                    favDlg.dismiss();
                    String memo = etMemo.getText().toString().trim();
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(routeFavKey, true)
                            .putString("fav_route_no_"    + routeId + "_" + direction, routeNo)
                            .putString("fav_route_dir_"   + routeId + "_" + direction, shortDir)
                            .putString("fav_route_id_"    + routeId + "_" + direction, routeId)
                            .putString("fav_route_dirkey_"+ routeId + "_" + direction, direction)
                            .putString("fav_route_memo_"  + routeId + "_" + direction, memo).apply();
                    android.graphics.drawable.GradientDrawable onBg = new android.graphics.drawable.GradientDrawable();
                    onBg.setCornerRadius(dpToPx(5)); onBg.setColor(Color.parseColor("#F39C12"));
                    onBg.setStroke(dpToPx(1), Color.parseColor("#F39C12"));
                    tvRouteStar.setTextColor(Color.WHITE); tvRouteStar.setBackground(onBg);
                    android.widget.Toast.makeText(this, routeNo + "번 " + shortDir + " 즐겨찾기 추가",
                            android.widget.Toast.LENGTH_SHORT).show();
                    busFavDirty = true;
                });
                btnRow.addView(btnFavOk);
                dlgLayout.addView(btnRow);

                favDlg.setContentView(dlgLayout);
                favDlg.setCancelable(true);
                if (favDlg.getWindow() != null) {
                    favDlg.getWindow().setLayout(
                            (int)(getResources().getDisplayMetrics().widthPixels * 0.85),
                            android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                    favDlg.getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                }
                favDlg.show();
                etMemo.post(() -> {
                    etMemo.requestFocus();
                    android.view.inputmethod.InputMethodManager immDlg =
                            (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (immDlg != null) immDlg.showSoftInput(etMemo, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                });
            }
        });
        topHeader.addView(tvRouteStar);

        // ── 고정 헤더: topHeader + dirRow ──────────────
        LinearLayout fixedArea = (busFixedHeader != null) ? busFixedHeader : container;
        fixedArea.removeAllViews();
        fixedArea.setVisibility(android.view.View.VISIBLE);
        fixedArea.setBackgroundColor(Color.parseColor("#F2F4F8"));
        LinearLayout.LayoutParams thLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        thLp2.setMargins(dpToPx(12), dpToPx(4), dpToPx(12), 0);
        topHeader.setLayoutParams(thLp2);
        fixedArea.addView(topHeader);

        // ── 기점↔종점 ────────────────────────────────────
        TextView tvRoute = new TextView(this);
        tvRoute.setText(fStartNm + "  \u2194  " + fEndNm);
        tvRoute.setTextColor(Color.parseColor("#555555"));
        tvRoute.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
        tvRoute.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rtLp.setMargins(0,0,0,dpToPx(6)); tvRoute.setLayoutParams(rtLp);
        container.addView(tvRoute);

        // ── 퀵 메뉴 (홈 추가/운행정보/지도/주변정류장) - 스크롤 영역, 기점↔종점 바로 아래 ──
        LinearLayout quickMenu = new LinearLayout(this);
        quickMenu.setOrientation(LinearLayout.HORIZONTAL);
        quickMenu.setClipChildren(false); quickMenu.setClipToPadding(false);
        LinearLayout.LayoutParams qmLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qmLp.setMargins(0, dpToPx(8), 0, dpToPx(8));
        quickMenu.setLayoutParams(qmLp);
        String[] qLabels = {"홈 추가", "운행정보", "지도", "주변정류장"};
        for (int qi = 0; qi < 4; qi++) {
            LinearLayout qCard = new LinearLayout(this);
            qCard.setOrientation(LinearLayout.VERTICAL); qCard.setGravity(Gravity.CENTER);
            qCard.setBackground(makeShadowCardDrawable("#FFFFFF", 10, 5));
            qCard.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            qCard.setElevation(dpToPx(2));
            qCard.setPadding(dpToPx(4), dpToPx(10), dpToPx(4), dpToPx(10));
            LinearLayout.LayoutParams qcLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            qcLp.setMargins(0, 0, qi < 3 ? dpToPx(6) : 0, 0); qCard.setLayoutParams(qcLp);
            TextView tvQLabel = new TextView(this);
            tvQLabel.setText(qLabels[qi]); tvQLabel.setGravity(Gravity.CENTER);
            tvQLabel.setTextColor(Color.parseColor("#1A1A2E"));
            tvQLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            tvQLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            qCard.addView(tvQLabel);
            if (qi == 1) {
                qCard.setOnClickListener(v2 -> {
                    String msg = "노선번호: " + routeNo + "번\n기점: " + fStartNm + "\n종점: " + fEndNm
                            + "\n첫차: " + fStF + "\n막차: " + fEtF
                            + "\n배차간격: " + (fInterval.isEmpty() ? "-" : fInterval + "분");
                    new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                            .setTitle("\u23f1 운행 정보").setMessage(msg).setPositiveButton("확인", null).show();
                });
            }
            quickMenu.addView(qCard);
        }
        container.addView(quickMenu);

        // ── 방향 카드 ─────────────────────────────────────
        LinearLayout dirRow = new LinearLayout(this);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        dirRow.setClipChildren(false); dirRow.setClipToPadding(false);
        LinearLayout.LayoutParams drLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        drLp.setMargins(0,0,0,dpToPx(8));
        String[] dirLabels = {fEndNm + " 방향", fStartNm + " 방향"};
        String[] dirKeys   = {"forward","reverse"};
        for (int d = 0; d < 2; d++) {
            boolean isCur = direction.equals(dirKeys[d]);
            LinearLayout dc = new LinearLayout(this);
            dc.setOrientation(LinearLayout.VERTICAL); dc.setGravity(Gravity.CENTER);
            dc.setBackground(makeShadowCardDrawable(isCur?"#0984E3":"#FFFFFF",10,6));
            dc.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            dc.setElevation(dpToPx(3));
            dc.setPadding(dpToPx(8),dpToPx(12),dpToPx(8),dpToPx(12));
            LinearLayout.LayoutParams dcLp = new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);
            dcLp.setMargins(0,0,d==0?dpToPx(8):0,0); dc.setLayoutParams(dcLp);
            TextView tvDir = new TextView(this);
            tvDir.setText(dirLabels[d]);
            tvDir.setTextColor(isCur?Color.WHITE:Color.parseColor("#1A1A2E"));
            tvDir.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
            tvDir.setShadowLayer(4f, 0f, 1.5f, 0x40000000);
            tvDir.setTypeface(null,isCur?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
            tvDir.setGravity(Gravity.CENTER); tvDir.setSingleLine(true);
            tvDir.setEllipsize(android.text.TextUtils.TruncateAt.END); dc.addView(tvDir);
            TextView tvTime = new TextView(this);
            tvTime.setText(fStF + " ~ " + fEtF);
            tvTime.setTextColor(isCur?Color.parseColor("#D6EAF8"):Color.parseColor("#AAAAAA"));
            tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
            tvTime.setShadowLayer(3f, 0f, 1f, 0x25000000);
            tvTime.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tLp2.setMargins(0,dpToPx(3),0,0); tvTime.setLayoutParams(tLp2); dc.addView(tvTime);
            final String dKey = dirKeys[d];
            dc.setOnClickListener(v2 -> {
                if (direction.equals(dKey)) {
                    // 이미 선택된 방향 - 스크롤만
                    if (busTimelineSv != null) {
                        if ("forward".equals(dKey)) {
                            busTimelineSv.smoothScrollTo(0, 0);
                        } else {
                            if (busTurnRowY >= 0) {
                                int offset = busTimelineSv.getHeight() / 3;
                                busTimelineSv.smoothScrollTo(0, Math.max(0, busTurnRowY - offset));
                            }
                        }
                    }
                } else {
                    // 다른 방향 - 재로드 후 자동 스크롤 플래그 설정
                    busTurnRowY = -1;
                    busPendingScrollDir = dKey;
                    busScreenLoadStops(routeId, routeNo, container, dKey, fRTp);
                }
            });
            dirRow.addView(dc);
        }
        LinearLayout.LayoutParams drLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        drLp2.setMargins(dpToPx(12), 0, dpToPx(12), dpToPx(6));
        dirRow.setLayoutParams(drLp2);
        fixedArea.addView(dirRow);

        // ── 구분선 ────────────────────────────────────────
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E8E8E8"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
        container.addView(divider);

        // ── 운행 대수 배너 ────────────────────────────────
        LinearLayout runBanner = new LinearLayout(this);
        runBanner.setOrientation(LinearLayout.HORIZONTAL);
        runBanner.setGravity(Gravity.CENTER_VERTICAL);
        runBanner.setPadding(dpToPx(12),dpToPx(8),dpToPx(12),dpToPx(8));
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rbLp.setMargins(0,0,0,dpToPx(4)); runBanner.setLayoutParams(rbLp);
        View rbSpace = new View(this);
        rbSpace.setLayoutParams(new LinearLayout.LayoutParams(0,1,1f)); runBanner.addView(rbSpace);
        // 버스 이미지
        android.widget.ImageView ivRunBus = new android.widget.ImageView(this);
        android.graphics.Bitmap runBusBmp = getBusIcon();
        if (runBusBmp != null) ivRunBus.setImageBitmap(runBusBmp);
        LinearLayout.LayoutParams rbImgLp = new LinearLayout.LayoutParams(dpToPx(20), dpToPx(20));
        rbImgLp.setMargins(0, 0, dpToPx(4), 0);
        rbImgLp.gravity = Gravity.CENTER_VERTICAL;
        ivRunBus.setLayoutParams(rbImgLp);
        ivRunBus.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        runBanner.addView(ivRunBus);
        TextView tvRunFull = new TextView(this);
        tvRunFull.setText("현재  ");
        tvRunFull.setTextColor(Color.parseColor("#555555"));
        tvRunFull.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13)); runBanner.addView(tvRunFull);
        TextView tvRunCnt = new TextView(this);
        tvRunCnt.setText(fRunning + "대");
        tvRunCnt.setTextColor(Color.parseColor("#E74C3C"));
        tvRunCnt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        tvRunCnt.setTypeface(null, android.graphics.Typeface.BOLD); runBanner.addView(tvRunCnt);
        TextView tvRunTxt = new TextView(this);
        tvRunTxt.setText("  운행중    ");
        tvRunTxt.setTextColor(Color.parseColor("#555555"));
        tvRunTxt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13)); runBanner.addView(tvRunTxt);
        TextView tvIntervalBtn = new TextView(this);
        tvIntervalBtn.setText("배차시간 \u203a");
        tvIntervalBtn.setTextColor(Color.parseColor("#0984E3"));
        tvIntervalBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
        tvIntervalBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        tvIntervalBtn.setPadding(dpToPx(10),dpToPx(6),dpToPx(10),dpToPx(6));
        android.graphics.drawable.GradientDrawable intBg = new android.graphics.drawable.GradientDrawable();
        intBg.setColor(Color.parseColor("#EBF5FB")); intBg.setCornerRadius(dpToPx(8));
        intBg.setStroke(dpToPx(1), Color.parseColor("#AED6F1")); tvIntervalBtn.setBackground(intBg);
        tvIntervalBtn.setOnClickListener(v2 -> showBusTimeTableDialog(routeNo, true));
        runBanner.addView(tvIntervalBtn);
        container.addView(runBanner);

        // ── 타임라인 ──────────────────────────────────────
        // 회차 지점: 정류소 번호(s[3])가 비어있는 곳이 딱 한 곳 (기점/종점 제외)
        int turnIdx = -1;
        for (int i = 1; i < stops.size() - 1; i++) {
            if (stops.get(i)[3].isEmpty()) { turnIdx = i; break; }
        }
        final int fTurnIdx = turnIdx;

        for (int si = 0; si < stops.size(); si++) {
            String[] s = stops.get(si);
            boolean isFirst = (si==0), isLast = (si==stops.size()-1);
            boolean hasBus  = busOrdSet.contains(s[2]);
            String vehicleNo = hasBus ? fBusVehicle.getOrDefault(s[2],"") : "";

            // 회차 지점 여부 (정류소번호 없는 곳)
            boolean isTurn   = (fTurnIdx > 0 && si == fTurnIdx);
            boolean isReturn = (fTurnIdx > 0 && si >= fTurnIdx); // 회차 포함 이후 (복귀 구간)

            // 회차 지점이면 유턴 화살표 행 삽입
            // 버스 번호 (hasBus 시 사용)
            final String fShortNo;
            if (hasBus) {
                String sn = vehicleNo.replaceAll("[^0-9]","");
                fShortNo = sn.length()>4 ? sn.substring(sn.length()-4) : sn;
            } else { fShortNo = ""; }

            // 정류소 행
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.TOP);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            final boolean fFirst2=isFirst, fLast2=isLast, fIsReturn=isReturn;

            // ── 타임라인 FrameLayout (세로줄 + 원 + 버스오버레이) ──
            android.widget.FrameLayout tlFrame = new android.widget.FrameLayout(this);
            tlFrame.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(40), LinearLayout.LayoutParams.MATCH_PARENT));

            // 세로줄 View (항상 연파랑)
            android.view.View lineView = new android.view.View(this) {
                @Override protected void onDraw(android.graphics.Canvas canvas) {
                    super.onDraw(canvas);
                    int w=getWidth(), h=getHeight(); float cx=w/2f;
                    android.graphics.Paint lPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    lPaint.setColor(Color.parseColor("#AED6F1")); lPaint.setStrokeWidth(dpToPx(2));
                    if (!fFirst2) canvas.drawLine(cx, 0, cx, h, lPaint);
                    else          canvas.drawLine(cx, h/2f, cx, h, lPaint);
                    if (fLast2) {
                        android.graphics.Paint clearP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        clearP.setColor(Color.parseColor("#F2F4F8"));
                        canvas.drawRect(cx-dpToPx(2), h/2f, cx+dpToPx(2), h, clearP);
                    }
                }
            };
            lineView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            tlFrame.addView(lineView);

            // 원 + 화살표 View
            android.view.View circleView = new android.view.View(this) {
                @Override protected void onDraw(android.graphics.Canvas canvas) {
                    super.onDraw(canvas);
                    int w=getWidth(), h=getHeight(); float cx=w/2f, cr=dpToPx(9);
                    String circleColor = fIsReturn ? "#E74C3C" : "#0984E3";
                    android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    bgPaint.setColor(Color.parseColor("#F2F4F8"));
                    canvas.drawRect(cx-cr-dpToPx(2), h/2f-cr-dpToPx(1), cx+cr+dpToPx(2), h/2f+cr+dpToPx(1), bgPaint);
                    android.graphics.Paint cPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    cPaint.setColor(Color.parseColor(circleColor));
                    cPaint.setStyle(android.graphics.Paint.Style.STROKE);
                    cPaint.setStrokeWidth(dpToPx(1));
                    canvas.drawCircle(cx, h/2f, cr, cPaint);
                    android.graphics.Paint wPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    wPaint.setColor(Color.WHITE);
                    canvas.drawCircle(cx, h/2f, cr-dpToPx(1), wPaint);
                    android.graphics.Paint vPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    vPaint.setColor(Color.parseColor("#0984E3"));
                    vPaint.setStyle(android.graphics.Paint.Style.STROKE);
                    vPaint.setStrokeWidth(dpToPx(2));
                    vPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    float vSize=dpToPx(4), vy=h/2f;
                    android.graphics.Path vPath = new android.graphics.Path();
                    vPath.moveTo(cx-vSize, vy-vSize*0.5f);
                    vPath.lineTo(cx, vy+vSize*0.5f);
                    vPath.lineTo(cx+vSize, vy-vSize*0.5f);
                    canvas.drawPath(vPath, vPaint);
                }
            };
            circleView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            tlFrame.addView(circleView);

            // 회차 오버레이: isTurn일 때 tlFrame 위에 겹침 (별도 행 없음)
            if (isTurn) {
                TextView tvTurn = new TextView(this);
                tvTurn.setText("회차");
                tvTurn.setTextColor(Color.parseColor("#E74C3C"));
                tvTurn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                tvTurn.setTypeface(null, android.graphics.Typeface.BOLD);
                tvTurn.setGravity(Gravity.CENTER);
                tvTurn.setBackgroundColor(Color.parseColor("#F2F4F8"));
                android.widget.FrameLayout.LayoutParams tvTurnLp = new android.widget.FrameLayout.LayoutParams(
                        dpToPx(40), android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
                tvTurnLp.gravity = Gravity.START;
                tvTurn.setLayoutParams(tvTurnLp);
                tlFrame.addView(tvTurn);
            }

            // 버스 오버레이: hasBus일 때 tlFrame 위에 겹침
            if (hasBus) {
                android.view.View busOverlay = new android.view.View(this) {
                    @Override protected void onDraw(android.graphics.Canvas canvas) {
                        int w=getWidth(), h=getHeight();
                        float cx = dpToPx(20);
                        float boxW=dpToPx(24), imgH=dpToPx(20), numH=dpToPx(10), gap=dpToPx(1);
                        float totalH = imgH + gap + numH;
                        float startY = h/2f - totalH/2f - dpToPx(2);
                        float left = cx - boxW/2f;
                        android.graphics.Paint bgP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        bgP.setColor(Color.parseColor("#F2F4F8"));
                        canvas.drawRect(left-dpToPx(1), startY-dpToPx(1), left+boxW+dpToPx(1), startY+totalH+dpToPx(1), bgP);
                        android.graphics.Bitmap bmp = getBusIcon();
                        if (bmp != null) {
                            android.graphics.Paint imgP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
                            canvas.drawBitmap(bmp, null, new android.graphics.RectF(left, startY, left+boxW, startY+imgH), imgP);
                        }
                        if (!fShortNo.isEmpty()) {
                            android.graphics.Paint np2 = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                            np2.setColor(Color.parseColor("#E74C3C")); np2.setTextSize(dpToPx(8));
                            np2.setTextAlign(android.graphics.Paint.Align.CENTER); np2.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                            canvas.drawText(fShortNo, cx, startY+imgH+gap+numH*0.8f, np2);
                        }
                    }
                };
                busOverlay.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                tlFrame.addView(busOverlay);
            }
            row.addView(tlFrame);


            LinearLayout stopInfo = new LinearLayout(this);
            stopInfo.setOrientation(LinearLayout.VERTICAL); stopInfo.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams siLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,1f);
            siLp2.setMargins(dpToPx(6),dpToPx(10),0,dpToPx(10)); stopInfo.setLayoutParams(siLp2);
            TextView tvName = new TextView(this);
            tvName.setText(s[1]); tvName.setTextColor(Color.parseColor("#1A1A2E"));
            tvName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
            tvName.setShadowLayer(3f, 0f, 1f, 0x30000000);
            tvName.setTypeface(null,(isFirst||isLast)?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
            stopInfo.addView(tvName);
            if (!s[3].isEmpty()) {
                TextView tvNo = new TextView(this); tvNo.setText(s[3]);
                tvNo.setTextColor(Color.parseColor("#AAAAAA")); tvNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                LinearLayout.LayoutParams noLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                noLp.setMargins(0,dpToPx(1),0,0); tvNo.setLayoutParams(noLp); stopInfo.addView(tvNo);
            }
            if (isFirst||isLast) {
                TextView tvTag = new TextView(this); tvTag.setText(isFirst?"기점":"종점");
                tvTag.setTextColor(Color.WHITE); tvTag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(10));
                tvTag.setTypeface(null,android.graphics.Typeface.BOLD); tvTag.setGravity(Gravity.CENTER);
                tvTag.setPadding(dpToPx(6),dpToPx(2),dpToPx(6),dpToPx(2));
                android.graphics.drawable.GradientDrawable tagBg = new android.graphics.drawable.GradientDrawable();
                tagBg.setColor(Color.parseColor("#0984E3")); tagBg.setCornerRadius(dpToPx(4)); tvTag.setBackground(tagBg);
                LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tagLp.setMargins(0,dpToPx(2),0,0); tvTag.setLayoutParams(tagLp); stopInfo.addView(tvTag);
            }
            row.addView(stopInfo);

            // 즐겨찾기 버튼
            final String favKey = "fav_stop_" + routeId + "_" + s[0];
            final String favNameKey = "fav_stop_name_" + routeId + "_" + s[0];
            final String favNoKey   = "fav_stop_no_"   + routeId + "_" + s[0];
            final String favRouteKey2 = "fav_stop_route_"   + routeId + "_" + s[0];
            final String favRouteIdKey = "fav_stop_routeid_" + routeId + "_" + s[0];
            boolean isFav = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(favKey, false);
            TextView tvStar = new TextView(this); tvStar.setText("즐겨찾기");
            tvStar.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(10));
            tvStar.setTypeface(null, android.graphics.Typeface.BOLD); tvStar.setGravity(Gravity.CENTER);
            tvStar.setPadding(dpToPx(7),dpToPx(4),dpToPx(7),dpToPx(4));
            android.graphics.drawable.GradientDrawable starBg = new android.graphics.drawable.GradientDrawable();
            starBg.setCornerRadius(dpToPx(4));
            if (isFav) { starBg.setColor(Color.parseColor("#F39C12")); starBg.setStroke(dpToPx(1),Color.parseColor("#F39C12")); tvStar.setTextColor(Color.WHITE); }
            else       { starBg.setColor(Color.WHITE); starBg.setStroke(dpToPx(1),Color.parseColor("#AAAAAA")); tvStar.setTextColor(Color.parseColor("#888888")); }
            tvStar.setBackground(starBg);
            LinearLayout.LayoutParams starLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            starLp.gravity = Gravity.CENTER_VERTICAL; starLp.setMargins(dpToPx(4),0,dpToPx(8),0); tvStar.setLayoutParams(starLp);
            final String stopName=s[1], stopNo=s[3], nodeId2=s[0];
            tvStar.setOnClickListener(v2 -> {
                boolean wasFav3 = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(favKey, false);
                if (wasFav3) {
                    // 즐겨찾기 해제
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .remove(favKey).remove(favNameKey).remove(favNoKey)
                            .remove(favRouteKey2).remove(favRouteIdKey)
                            .remove(favKey + "_memo").apply();
                    android.graphics.drawable.GradientDrawable offBg = new android.graphics.drawable.GradientDrawable();
                    offBg.setCornerRadius(dpToPx(4)); offBg.setColor(Color.WHITE);
                    offBg.setStroke(dpToPx(1), Color.parseColor("#AAAAAA"));
                    tvStar.setTextColor(Color.parseColor("#888888")); tvStar.setBackground(offBg);
                    android.widget.Toast.makeText(this, stopName + " 즐겨찾기 해제", android.widget.Toast.LENGTH_SHORT).show();
                    busFavDirty = true;
                } else {
                    // 즐겨찾기 추가 - 노선 즐겨찾기와 동일한 다이얼로그
                    String existMemo = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(favKey + "_memo", "");
                    android.app.Dialog stopFavDlg = new android.app.Dialog(this, android.R.style.Theme_Material_Light_Dialog);
                    LinearLayout dlgLayout = new LinearLayout(this);
                    dlgLayout.setOrientation(LinearLayout.VERTICAL);
                    android.graphics.drawable.GradientDrawable dlgBg = new android.graphics.drawable.GradientDrawable();
                    dlgBg.setColor(Color.WHITE); dlgBg.setCornerRadius(dpToPx(16));
                    dlgLayout.setBackground(dlgBg);
                    dlgLayout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));

                    // 제목
                    TextView tvDlgTitle = new TextView(this);
                    tvDlgTitle.setText(stopName + " (" + routeNo + "번)");
                    tvDlgTitle.setTextColor(Color.parseColor("#0984E3"));
                    tvDlgTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
                    tvDlgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvDlgTitle.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    dtLp.setMargins(0, 0, 0, dpToPx(14)); tvDlgTitle.setLayoutParams(dtLp);
                    dlgLayout.addView(tvDlgTitle);

                    // 구분선
                    android.view.View favDiv = new android.view.View(this);
                    favDiv.setBackgroundColor(Color.parseColor("#EEEEEE"));
                    LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                    divLp.setMargins(0, 0, 0, dpToPx(14)); favDiv.setLayoutParams(divLp);
                    dlgLayout.addView(favDiv);

                    // 메모 라벨
                    TextView tvMemoLabel = new TextView(this);
                    tvMemoLabel.setText("메모 (선택)");
                    tvMemoLabel.setTextColor(Color.parseColor("#555555"));
                    tvMemoLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                    LinearLayout.LayoutParams mlLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    mlLp.setMargins(0, 0, 0, dpToPx(6)); tvMemoLabel.setLayoutParams(mlLp);
                    dlgLayout.addView(tvMemoLabel);

                    // 메모 입력
                    android.widget.EditText etMemo = new android.widget.EditText(this);
                    setBlackCursor(etMemo);
                    etMemo.setHint("예) 집앞, 출근길");
                    etMemo.setText(existMemo);
                    etMemo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
                    etMemo.setSingleLine(true);
                    android.graphics.drawable.GradientDrawable memoBg = new android.graphics.drawable.GradientDrawable();
                    memoBg.setColor(Color.parseColor("#F8F8F8")); memoBg.setCornerRadius(dpToPx(10));
                    memoBg.setStroke(dpToPx(1), Color.parseColor("#DDDDDD"));
                    etMemo.setBackground(memoBg);
                    etMemo.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
                    LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    etLp.setMargins(0, 0, 0, dpToPx(20)); etMemo.setLayoutParams(etLp);
                    dlgLayout.addView(etMemo);

                    // 버튼 행
                    LinearLayout btnRow = new LinearLayout(this);
                    btnRow.setOrientation(LinearLayout.HORIZONTAL);
                    btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                    // 취소
                    TextView btnCancel = new TextView(this);
                    btnCancel.setText("취소");
                    btnCancel.setTextColor(Color.parseColor("#888888"));
                    btnCancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    btnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
                    btnCancel.setGravity(Gravity.CENTER);
                    btnCancel.setPadding(0, dpToPx(13), 0, dpToPx(13));
                    android.graphics.drawable.GradientDrawable cancelBg = new android.graphics.drawable.GradientDrawable();
                    cancelBg.setColor(Color.parseColor("#F0F0F0")); cancelBg.setCornerRadius(dpToPx(10));
                    btnCancel.setBackground(cancelBg);
                    LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    cancelLp.setMargins(0, 0, dpToPx(8), 0); btnCancel.setLayoutParams(cancelLp);
                    btnCancel.setOnClickListener(vv -> stopFavDlg.dismiss());
                    btnRow.addView(btnCancel);

                    // 확인
                    TextView btnOk = new TextView(this);
                    btnOk.setText("확인");
                    btnOk.setTextColor(Color.WHITE);
                    btnOk.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    btnOk.setTypeface(null, android.graphics.Typeface.BOLD);
                    btnOk.setGravity(Gravity.CENTER);
                    btnOk.setPadding(0, dpToPx(13), 0, dpToPx(13));
                    android.graphics.drawable.GradientDrawable okBg = new android.graphics.drawable.GradientDrawable();
                    okBg.setColor(Color.parseColor("#5BA9F0")); okBg.setCornerRadius(dpToPx(10));
                    btnOk.setBackground(okBg);
                    btnOk.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    btnOk.setOnClickListener(vv -> {
                        stopFavDlg.dismiss();
                        String memo = etMemo.getText().toString().trim();
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                                .putBoolean(favKey, true)
                                .putString(favNameKey, stopName)
                                .putString(favNoKey, stopNo)
                                .putString(favRouteKey2, routeNo)
                                .putString(favRouteIdKey, routeId)
                                .putString(favKey + "_memo", memo).apply();
                        android.graphics.drawable.GradientDrawable onBg = new android.graphics.drawable.GradientDrawable();
                        onBg.setCornerRadius(dpToPx(4)); onBg.setColor(Color.parseColor("#F39C12"));
                        onBg.setStroke(dpToPx(1), Color.parseColor("#F39C12"));
                        tvStar.setTextColor(Color.WHITE); tvStar.setBackground(onBg);
                        android.widget.Toast.makeText(this, stopName + " 즐겨찾기 추가",
                                android.widget.Toast.LENGTH_SHORT).show();
                        busFavDirty = true;
                    });
                    btnRow.addView(btnOk);
                    dlgLayout.addView(btnRow);

                    stopFavDlg.setContentView(dlgLayout);
                    stopFavDlg.setCancelable(true);
                    if (stopFavDlg.getWindow() != null)
                        stopFavDlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                    stopFavDlg.show();
                }
            });
            row.addView(tvStar);

            final String nodeId=s[0], nodeNm=s[1], nodeNo2=s[3];
            // 회차 지점(nodeno 없음)은 실제 정류소가 아니므로 클릭 비활성화
            if (!s[3].isEmpty()) {
                row.setOnClickListener(v -> busScreenLoadArrival(nodeId, nodeNm, nodeNo2, "", container));
            }
            // 회차 행 Y좌표 저장 (reverse 방향 클릭 시 스크롤용)
            if (isTurn) {
                final LinearLayout fRow = row;
                row.post(() -> {
                    int[] loc = new int[2];
                    fRow.getLocationOnScreen(loc);
                    if (busTimelineSv != null) {
                        int[] svLoc = new int[2];
                        busTimelineSv.getLocationOnScreen(svLoc);
                        busTurnRowY = busTimelineSv.getScrollY() + (loc[1] - svLoc[1]);
                        // 대기 중인 스크롤 처리
                        if ("reverse".equals(busPendingScrollDir)) {
                            busPendingScrollDir = null;
                            busTimelineSv.post(() -> {
                                int offset = busTimelineSv.getHeight() / 3;
                                busTimelineSv.smoothScrollTo(0, Math.max(0, busTurnRowY - offset));
                            });
                        }
                    }
                });
            }
            container.addView(row);

            if (!isLast) {
                LinearLayout divRow = new LinearLayout(this);
                divRow.setOrientation(LinearLayout.HORIZONTAL);
                divRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                android.view.View divLine = new android.view.View(this) {
                    @Override protected void onDraw(android.graphics.Canvas canvas) {
                        super.onDraw(canvas);
                        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                        p.setColor(Color.parseColor("#BBBBBB")); p.setStrokeWidth(dpToPx(2));
                        canvas.drawLine(getWidth()/2f,0,getWidth()/2f,getHeight(),p);
                    }
                };
                divLine.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(40),dpToPx(1)));
                divRow.addView(divLine);
                View divider2 = new View(this);
                divider2.setBackgroundColor(Color.parseColor("#CCCCCC"));
                LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(0,dpToPx(1),1f);
                dvLp.setMargins(dpToPx(6),0,0,0); divider2.setLayoutParams(dvLp);
                divRow.addView(divider2); container.addView(divRow);
            }
        }
    }

    /** 정류소 도착정보 표시 */
    private void busScreenLoadArrival(String nodeId, String nodeNm, String filterRouteNo, LinearLayout container) {
        busScreenLoadArrival(nodeId, nodeNm, "", filterRouteNo, container);
    }

    private void busScreenLoadArrival(String nodeId, String nodeNm, String nodeNo, String filterRouteNo, LinearLayout container) {
        // ★★★ 절대 규칙: 검색화면이 열려있으면 도착화면 렌더링 차단
        if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.VISIBLE) return;
        // 백스택에 arrival 저장 (이미 같은 nodeId arrival이 top이면 중복 push 방지)
        boolean alreadyOnStack = !busBackStack.isEmpty()
            && "arrival".equals(busBackStack.peek()[0])
            && nodeId.equals(busBackStack.peek()[1]);
        if (!alreadyOnStack) {
            busBackStack.push(new String[]{"arrival", nodeId, nodeNm, nodeNo, filterRouteNo});
        }
        // ① 타임라인 자동갱신 타이머 중단
        if (busRefreshRunnable != null) {
            busRefreshHandler.removeCallbacks(busRefreshRunnable);
            busRefreshRunnable = null;
        }
        // ② 검색창·즐겨찾기 숨기기
        if (busSearchArea  != null) busSearchArea.setVisibility(android.view.View.GONE);
        if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.GONE);

        // ③ busFixedHeader → 정류장 타이틀바
        if (busFixedHeader != null) {
            busFixedHeader.removeAllViews();
            busFixedHeader.setTag("arrival_" + nodeId); // 화면 식별 태그
            busFixedHeader.setVisibility(android.view.View.VISIBLE);
            busFixedHeader.setBackgroundColor(Color.WHITE);
            busFixedHeader.setPadding(0, 0, 0, 0);

            LinearLayout titleBar = new LinearLayout(this);
            titleBar.setOrientation(LinearLayout.HORIZONTAL);
            titleBar.setGravity(Gravity.CENTER_VERTICAL);
            titleBar.setBackgroundColor(Color.parseColor("#F2F4F8"));
            LinearLayout.LayoutParams titleBarLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            titleBarLp.setMargins(dpToPx(12), dpToPx(4), dpToPx(12), 0);
            titleBar.setLayoutParams(titleBarLp);

            TextView tvBack = new TextView(this);
            tvBack.setText("\u2039");
            tvBack.setTextColor(Color.parseColor("#333333"));
            tvBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(26));
            tvBack.setTypeface(null, android.graphics.Typeface.BOLD);
            tvBack.setPadding(0, 0, dpToPx(10), 0);
            tvBack.setOnClickListener(v -> busNavigateBack());
            titleBar.addView(tvBack);

            TextView tvTitle = new TextView(this);
            tvTitle.setText(nodeNm);
            tvTitle.setTextColor(Color.parseColor("#111111"));
            tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(17));
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            titleBar.addView(tvTitle);

            // 정류소 즐겨찾기 버튼 (기존 스타일)
            final String stopOnlyFavKey = "fav_stop_" + nodeId;
            boolean stopOnlyFaved = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(stopOnlyFavKey, false);
            TextView tvStopOnlyFav = new TextView(this);
            tvStopOnlyFav.setText("즐겨찾기");
            tvStopOnlyFav.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvStopOnlyFav.setTypeface(null, android.graphics.Typeface.BOLD);
            tvStopOnlyFav.setGravity(Gravity.CENTER);
            tvStopOnlyFav.setPadding(dpToPx(9), dpToPx(5), dpToPx(9), dpToPx(5));
            android.graphics.drawable.GradientDrawable hFavBg = new android.graphics.drawable.GradientDrawable();
            hFavBg.setCornerRadius(dpToPx(5));
            if (stopOnlyFaved) { hFavBg.setColor(Color.parseColor("#F39C12")); hFavBg.setStroke(dpToPx(1),Color.parseColor("#F39C12")); tvStopOnlyFav.setTextColor(Color.WHITE); }
            else               { hFavBg.setColor(Color.WHITE); hFavBg.setStroke(dpToPx(1),Color.parseColor("#AAAAAA")); tvStopOnlyFav.setTextColor(Color.parseColor("#888888")); }
            tvStopOnlyFav.setBackground(hFavBg);
            LinearLayout.LayoutParams hFavLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hFavLp.gravity = Gravity.CENTER_VERTICAL; hFavLp.setMargins(dpToPx(4),0,0,0);
            tvStopOnlyFav.setLayoutParams(hFavLp);
            tvStopOnlyFav.setOnClickListener(vf -> {
                boolean cur = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(stopOnlyFavKey, false);
                android.graphics.drawable.GradientDrawable newBg = new android.graphics.drawable.GradientDrawable();
                newBg.setCornerRadius(dpToPx(4));
                if (cur) {
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .remove(stopOnlyFavKey).remove("fav_stop_name_"+nodeId).remove("fav_stop_no_"+nodeId).apply();
                    newBg.setColor(Color.WHITE); newBg.setStroke(dpToPx(1),Color.parseColor("#AAAAAA"));
                    tvStopOnlyFav.setTextColor(Color.parseColor("#888888")); tvStopOnlyFav.setBackground(newBg);
                    android.widget.Toast.makeText(this, nodeNm+" 즐겨찾기 해제", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .putBoolean(stopOnlyFavKey, true)
                        .putString("fav_stop_name_"+nodeId, nodeNm)
                        .putString("fav_stop_no_"+nodeId, nodeNo).apply();
                    newBg.setColor(Color.parseColor("#F39C12")); newBg.setStroke(dpToPx(1),Color.parseColor("#F39C12"));
                    tvStopOnlyFav.setTextColor(Color.WHITE); tvStopOnlyFav.setBackground(newBg);
                    android.widget.Toast.makeText(this, nodeNm+" 즐겨찾기 추가", android.widget.Toast.LENGTH_SHORT).show();
                }
                busFavDirty = true;
            });
            titleBar.addView(tvStopOnlyFav);
            busFixedHeader.addView(titleBar);

            View divT = new View(this);
            divT.setBackgroundColor(Color.parseColor("#EEEEEE"));
            divT.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
            busFixedHeader.addView(divT);

            LinearLayout infoBox = new LinearLayout(this);
            infoBox.setOrientation(LinearLayout.VERTICAL);
            infoBox.setGravity(Gravity.CENTER_HORIZONTAL);
            infoBox.setBackgroundColor(Color.parseColor("#F7F7F7"));
            infoBox.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
            infoBox.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // nodeNo가 비어있으면 JSON 캐시에서 nodeId로 no 찾기
            String displayNo = nodeNo;
            if (displayNo.isEmpty()) {
                String sc = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE)
                        .getString("stop_json_cache", "");
                int ix = sc.indexOf("\"id\":\"" + nodeId + "\"");
                if (ix >= 0) {
                    int os = sc.lastIndexOf("{", ix);
                    int oe = sc.indexOf("}", ix);
                    if (os >= 0 && oe >= 0) displayNo = jsonVal(sc.substring(os, oe+1), "no");
                }
            }

            TextView tvNo = new TextView(this);
            tvNo.setText(displayNo.isEmpty() ? nodeId : displayNo);
            tvNo.setTextColor(Color.parseColor("#999999"));
            tvNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            tvNo.setGravity(Gravity.CENTER_HORIZONTAL);
            infoBox.addView(tvNo);

            // 정류소에 정차하는 버스 종류 태그 (노선번호 → routeType → 종류별 색상)
            String routesForNode = nodeNo.isEmpty() ? "" : nodeNoToRoutes.get(nodeNo);
            if ((routesForNode == null || routesForNode.isEmpty()) && !nodeId.isEmpty()) {
                if (stopDbList != null) {
                    for (String[] s : stopDbList) {
                        if (s[0].equals(nodeId) && s.length > 4) { routesForNode = s[4]; break; }
                    }
                }
            }
            if (routesForNode != null && !routesForNode.isEmpty() && routeDbList != null) {
                // 종류 중복 없이 수집
                java.util.LinkedHashMap<String, String> typeColorMap = new java.util.LinkedHashMap<>();
                for (String rno : routesForNode.split(",")) {
                    rno = rno.trim(); if (rno.isEmpty()) continue;
                    // routeDbList에서 routeId 찾아 bus_cache에서 rTp 가져오기
                    for (String[] rd : routeDbList) {
                        if (rd[1].equals(rno)) {
                            String rTpVal = getSharedPreferences("bus_cache", MODE_PRIVATE)
                                    .getString("route_" + rd[0] + "_rTp", "");
                            String[] badge = routeTypeBadge(rTpVal);
                            if (!badge[0].isEmpty()) typeColorMap.put(badge[0], badge[1]);
                            break;
                        }
                    }
                }
                if (!typeColorMap.isEmpty()) {
                    LinearLayout typeRow = new LinearLayout(this);
                    typeRow.setOrientation(LinearLayout.HORIZONTAL);
                    typeRow.setGravity(Gravity.CENTER_HORIZONTAL);
                    LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    trLp.setMargins(0, dpToPx(4), 0, 0);
                    typeRow.setLayoutParams(trLp);
                    for (java.util.Map.Entry<String, String> tc : typeColorMap.entrySet()) {
                        TextView tvType = new TextView(this);
                        tvType.setText(tc.getKey());
                        tvType.setTextColor(Color.WHITE);
                        tvType.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(10));
                        tvType.setTypeface(null, android.graphics.Typeface.BOLD);
                        tvType.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
                        android.graphics.drawable.GradientDrawable typeBg = new android.graphics.drawable.GradientDrawable();
                        typeBg.setColor(Color.parseColor(tc.getValue()));
                        typeBg.setCornerRadius(dpToPx(4));
                        tvType.setBackground(typeBg);
                        LinearLayout.LayoutParams tvTypeLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        tvTypeLp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
                        tvType.setLayoutParams(tvTypeLp);
                        typeRow.addView(tvType);
                    }
                    infoBox.addView(typeRow);
                }
            }

            TextView tvSoonPH = new TextView(this);
            tvSoonPH.setTag("soon_ph");
            tvSoonPH.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            phLp.setMargins(0, dpToPx(3), 0, 0);
            tvSoonPH.setLayoutParams(phLp);
            infoBox.addView(tvSoonPH);
            busFixedHeader.addView(infoBox);

            View divI = new View(this);
            divI.setBackgroundColor(Color.parseColor("#DDDDDD"));
            divI.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
            busFixedHeader.addView(divI);
        }

        container.removeAllViews();

        // ── 세션 캐시 히트 → 즉시 렌더링 ──────────────────────────────
        Object[] cached = arrivalSessionCache.get(nodeId);
        if (cached != null) {
            long cacheTime = (long) cached[0];
            // 30초 이내 캐시면 즉시 표시
            if (System.currentTimeMillis() - cacheTime < 20000) {
                @SuppressWarnings("unchecked")
                java.util.List<String[]> cachedRoutes = (java.util.List<String[]>) cached[1];
                @SuppressWarnings("unchecked")
                java.util.Map<String, String[]> cachedArrMap = (java.util.Map<String, String[]>) cached[2];
                renderArrivalRows(nodeId, nodeNm, nodeNo, filterRouteNo, container, cachedRoutes, cachedArrMap);
                // 백그라운드에서 조용히 갱신
                final LinearLayout fContainer = container;
                new Thread(() -> fetchAndRenderArrival(nodeId, nodeNm, nodeNo, filterRouteNo, fContainer, false)).start();
                return;
            }
        }

        // 캐시 없음 → routes 먼저 메모리에서 즉시 추출 후 UI 그리기
        java.util.List<String[]> quickRoutes = new java.util.ArrayList<>();
        String memRoutes = nodeNo.isEmpty() ? "" : nodeNoToRoutes.get(nodeNo);
        if (memRoutes == null && !nodeId.isEmpty()) {
            // nodeNoToRoutes에 없으면 stopDbList에서 찾기
            if (stopDbList != null) {
                for (String[] s : stopDbList) {
                    if (s[0].equals(nodeId)) { memRoutes = s.length > 4 ? s[4] : ""; break; }
                }
            }
        }
        if (memRoutes != null && !memRoutes.isEmpty()) {
            for (String rno : memRoutes.split(",")) {
                rno = rno.trim(); if (rno.isEmpty()) continue;
                if (routeDbList != null) {
                    for (String[] rd : routeDbList) {
                        if (rd[1].equals(rno)) {
                            quickRoutes.add(new String[]{rd[1], rd[0], rd[2], rd[3], rd.length>4?rd[4]:""});
                            break;
                        }
                    }
                } else { quickRoutes.add(new String[]{rno, "", "", "", ""}); }
            }
        }
        // 메모리에서 routes 알고 있으면 → 즉시 골격 UI 그리기 (실시간 없이)
        if (!quickRoutes.isEmpty()) {
            renderArrivalRows(nodeId, nodeNm, nodeNo, filterRouteNo, container, quickRoutes, new java.util.HashMap<>());
        } else {
            TextView tvL = new TextView(this);
            tvL.setText("정류장 버스 정보 불러오는 중...");
            tvL.setTextColor(Color.parseColor("#AAAAAA"));
            tvL.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            tvL.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ldLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ldLp.setMargins(0, dpToPx(20), 0, 0);
            tvL.setLayoutParams(ldLp);
            container.addView(tvL);
        }

        // 백그라운드에서 실시간 API 호출 후 갱신
        final LinearLayout fContainer = container;
        new Thread(() -> fetchAndRenderArrival(nodeId, nodeNm, nodeNo, filterRouteNo, fContainer, true)).start();
    }

    /** 도착정보 API 호출 + 렌더링 (백그라운드에서 호출) */
    private void fetchAndRenderArrival(String nodeId, String nodeNm, String nodeNo,
                                       String filterRouteNo, LinearLayout container, boolean fullFetch) {
        try {
                // ── STEP 1: stop_json_cache에서 nodeno로 routes 직접 파싱 ──
                java.util.List<String[]> allRoutes = new java.util.ArrayList<>();
                String foundRoutes = "";
                String fNodeNo = nodeNo;

                // 내부 파일 우선 읽기, 없으면 SharedPreferences
                String stopCache = loadStopDb();
                if (stopCache.isEmpty())
                    stopCache = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE)
                            .getString("stop_json_cache", "");

                // 구버전 캐시(routes 없음)면 Drive에서 새로 받기
                if (!stopCache.contains("\"routes\":")) {
                    try {
                        DriveReadHelper dr = new DriveReadHelper(PinActivity.this);
                        final String[] newJson = {""};
                        final Object lock2 = new Object();
                        dr.readFile(STOP_DB_FILE, new DriveReadHelper.ReadCallback() {
                            @Override public void onSuccess(String content) {
                                synchronized(lock2) { newJson[0] = content; lock2.notifyAll(); }
                            }
                            @Override public void onFailure(String e) {
                                synchronized(lock2) { lock2.notifyAll(); }
                            }
                        });
                        synchronized(lock2) { lock2.wait(15000); }
                        if (!newJson[0].isEmpty()) {
                            getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE).edit()
                                    .putString("stop_json_cache", newJson[0]).apply();
                            saveStopDb(newJson[0]); // 내부 파일에도 저장
                            stopCache = newJson[0];
                        }
                    } catch (Exception ignored) {}
                }


                if (!stopCache.isEmpty()) {
                    // fNodeNo가 있으면 "no":"46810" 패턴으로 직접 검색
                    if (!fNodeNo.isEmpty()) {
                        String noPattern = "\"no\":\"" + fNodeNo + "\"";
                        int idx = stopCache.indexOf(noPattern);
                        while (idx >= 0) {
                            int objStart = stopCache.lastIndexOf("{", idx);
                            int objEnd   = stopCache.indexOf("}", idx);
                            if (objStart >= 0 && objEnd >= 0) {
                                String obj = stopCache.substring(objStart, objEnd + 1);
                                String r = jsonVal(obj, "routes");
                                if (!r.isEmpty()) { foundRoutes = r; break; }
                            }
                            idx = stopCache.indexOf(noPattern, idx + 1);
                        }
                    }
                    // nodeno로 못 찾으면 nodeId로 검색
                    if (foundRoutes.isEmpty()) {
                        String idPattern = "\"id\":\"" + nodeId + "\"";
                        int idx = stopCache.indexOf(idPattern);
                        if (idx >= 0) {
                            int objStart = stopCache.lastIndexOf("{", idx);
                            int objEnd   = stopCache.indexOf("}", idx);
                            if (objStart >= 0 && objEnd >= 0) {
                                String obj = stopCache.substring(objStart, objEnd + 1);
                                foundRoutes = jsonVal(obj, "routes");
                                if (fNodeNo.isEmpty()) fNodeNo = jsonVal(obj, "no");
                            }
                        }
                    }
                    // 그래도 없으면 nodeNm으로 검색
                    if (foundRoutes.isEmpty()) {
                        String nmPattern = "\"nm\":\"" + nodeNm + "\"";
                        int idx = stopCache.indexOf(nmPattern);
                        if (idx >= 0) {
                            int objStart = stopCache.lastIndexOf("{", idx);
                            int objEnd   = stopCache.indexOf("}", idx);
                            if (objStart >= 0 && objEnd >= 0) {
                                String obj = stopCache.substring(objStart, objEnd + 1);
                                foundRoutes = jsonVal(obj, "routes");
                                if (fNodeNo.isEmpty()) fNodeNo = jsonVal(obj, "no");
                            }
                        }
                    }
                }

                if (!foundRoutes.isEmpty()) {
                    // routes = "211,212,601,708" → 각 노선번호로 routeDbList에서 정보 찾기
                    for (String rno : foundRoutes.split(",")) {
                        rno = rno.trim();
                        if (rno.isEmpty()) continue;
                        boolean found = false;
                        if (routeDbList != null) {
                            for (String[] rd : routeDbList) {
                                if (rd[1].equals(rno)) {
                                    allRoutes.add(new String[]{rd[1], rd[0], rd[2], rd[3], rd.length>4?rd[4]:""});
                                    found = true;
                                    break;
                                }
                            }
                        }
                        // routeDbList에 없으면 노선번호만으로라도 추가
                        if (!found) allRoutes.add(new String[]{rno, "", "", "", ""});
                    }
                } else {
                    // routes 정보 없음 → bus_cache stops 캐시 fallback
                    android.content.SharedPreferences busCache = getSharedPreferences("bus_cache", MODE_PRIVATE);
                    java.util.Map<String, ?> allKeys = busCache.getAll();
                    java.util.Set<String> foundRouteIds = new java.util.HashSet<>();

                    for (java.util.Map.Entry<String, ?> entry : allKeys.entrySet()) {
                        String key = entry.getKey();
                        if (!key.endsWith("_stops")) continue;
                        Object val = entry.getValue();
                        if (!(val instanceof String)) continue;
                        String stopsRaw = (String) val;
                        if (stopsRaw.isEmpty()) continue;
                        String routeId2 = key.replace("route_", "").replace("_stops", "");
                        for (String line : stopsRaw.split(";")) {
                            String[] p = line.split("\\|", -1);
                            if (p.length < 2) continue;
                            boolean match = p[0].equals(nodeId) || p[1].equals(nodeNm)
                                    || (!fNodeNo.isEmpty() && p.length > 3 && p[3].equals(fNodeNo));
                            if (match) { foundRouteIds.add(routeId2); break; }
                        }
                    }
                    if (routeDbList != null) {
                        for (String[] rd : routeDbList) {
                            if (foundRouteIds.contains(rd[0]))
                                allRoutes.add(new String[]{rd[1], rd[0], rd[2], rd[3], rd.length>4?rd[4]:""});
                        }
                    }
                }

                // 로컬 캐시 없으면 실시간 API 결과만 사용 (fallback)
                boolean localDataFound = !allRoutes.isEmpty();

                // ── STEP 2: 실시간 도착정보 API ──────────────────────────────
                java.util.Map<String, String[]> arrMap = new java.util.HashMap<>();
                try {
                    String arvlUrl = BUS_BASE2 + "ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList"
                            + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                            + "&nodeId=" + nodeId + "&numOfRows=50&pageNo=1&_type=xml";
                    String arvlXml = httpGet(arvlUrl);

                    java.util.Map<String, Integer> minSec = new java.util.HashMap<>();
                    for (String item : arvlXml.split("<item>")) {
                        if (!item.contains("<routeno>")) continue;
                        String rno    = tag(item, "routeno");
                        String rid    = tag(item, "routeid");
                        String arrt   = tag(item, "arrtime");
                        String arrc   = tag(item, "arrprevstationcnt");
                        String nextnm = tag(item, "nodenm");
                        String endnm  = tag(item, "endnodenm");

                        int sec = -1;
                        try { sec = Integer.parseInt(arrt); } catch (Exception ig) {}
                        int prev = -1;
                        try { prev = Integer.parseInt(arrc); } catch (Exception ig) {}

                        if (minSec.containsKey(rno) && sec >= 0 && sec >= minSec.get(rno)) continue;
                        if (sec >= 0) minSec.put(rno, sec);

                        String timeStr, timeColor;
                        if (sec <= 0 || prev == 0) {
                            // 기점 대기 또는 정보 없음 → 배차시간표에서 다음 출발 시간
                            String nd = getNextDeparture(rno, true);
                            if (nd.isEmpty()) nd = getNextDeparture(rno, false);
                            if (!nd.isEmpty()) {
                                timeStr = nd; timeColor = "#555555";
                            } else {
                                java.util.Calendar cal = java.util.Calendar.getInstance();
                                timeStr = cal.get(java.util.Calendar.HOUR_OF_DAY) + "시 "
                                        + String.format("%02d", cal.get(java.util.Calendar.MINUTE)) + "분 출발";
                                timeColor = "#555555";
                            }
                        } else if (sec < 60) {
                            timeStr = "곧 도착"; timeColor = "#E74C3C";
                        } else if (sec / 60 <= 5) {
                            timeStr = "약 " + (sec/60) + "분"; timeColor = "#E74C3C";
                        } else {
                            timeStr = "약 " + (sec/60) + "분"; timeColor = "#333333";
                        }
                        String prevStr = prev == 0 ? "[기점]" : prev > 0 ? "[" + prev + "번째 전]" : "";
                        arrMap.put(rno, new String[]{timeStr, prevStr, timeColor, endnm, nextnm});

                        // 로컬 캐시 없으면 API 노선도 목록에 추가
                        if (!localDataFound) {
                            boolean already = false;
                            for (String[] r : allRoutes) if (r[0].equals(rno)) { already = true; break; }
                            if (!already) allRoutes.add(new String[]{rno, rid, "", endnm, ""});
                        }
                    }
                } catch (Exception ignored) {}

                // 노선번호 오름차순 정렬
                allRoutes.sort((a, b) -> {
                    try { return Integer.parseInt(a[0]) - Integer.parseInt(b[0]); }
                    catch (Exception ig) { return a[0].compareTo(b[0]); }
                });

                // 가장 빠른 버스
                String soonRno = ""; int soonSec = Integer.MAX_VALUE;
                for (java.util.Map.Entry<String, String[]> en : arrMap.entrySet()) {
                    String ts = en.getValue()[0];
                    int s2 = Integer.MAX_VALUE;
                    if (ts.equals("곧 도착")) s2 = 0;
                    else if (ts.contains("분")) {
                        try { s2 = Integer.parseInt(ts.replaceAll("[^0-9]","")) * 60; } catch(Exception ig){}
                    }
                    if (s2 < soonSec) { soonSec = s2; soonRno = en.getKey(); }
                }

                final java.util.List<String[]> fAllRoutes = allRoutes;
                final java.util.Map<String, String[]> fArrMap = arrMap;
                final String fSoonRno = soonRno;
                final int fSoonSec = soonSec;

                // 세션 캐시 저장
                arrivalSessionCache.put(nodeId, new Object[]{
                    System.currentTimeMillis(), allRoutes, arrMap
                });

                runOnUiThread(() -> {
                    // 검색화면이면 렌더링 차단
                    if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.VISIBLE) return;
                    // 도착화면 태그 검증 - 다른 화면으로 이미 전환됐으면 중단
                    if (busFixedHeader == null || !("arrival_" + nodeId).equals(busFixedHeader.getTag())) return;
                    // 헤더 곧도착 업데이트
                    if (busFixedHeader != null && busFixedHeader.getChildCount() >= 3) {
                        android.view.View infoBoxV = busFixedHeader.getChildAt(2);
                        if (infoBoxV instanceof LinearLayout) {
                            android.view.View ph = ((LinearLayout)infoBoxV).findViewWithTag("soon_ph");
                            if (ph instanceof TextView && !fSoonRno.isEmpty() && fSoonSec < Integer.MAX_VALUE) {
                                int fm = fSoonSec / 60;
                                String soonTxt = fSoonSec == 0 ? "곧 도착  " + fSoonRno + "번"
                                        : fm + "분 후  " + fSoonRno + "번";
                                ((TextView)ph).setText(soonTxt);
                                ((TextView)ph).setTextColor(Color.parseColor("#E74C3C"));
                                ((TextView)ph).setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
                                ((TextView)ph).setTypeface(null, android.graphics.Typeface.BOLD);
                            }
                        }
                    }

                    container.removeAllViews();

                    if (fAllRoutes.isEmpty()) {
                        TextView tvEmpty = new TextView(this);
                        tvEmpty.setText("이 정류장 노선 정보가 없습니다\n타임라인을 먼저 한 번 열어주세요");
                        tvEmpty.setTextColor(Color.parseColor("#AAAAAA"));
                        tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
                        tvEmpty.setGravity(Gravity.CENTER);
                        LinearLayout.LayoutParams emLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                        emLp.setMargins(dpToPx(16), dpToPx(24), dpToPx(16), 0);
                        tvEmpty.setLayoutParams(emLp);
                        container.addView(tvEmpty);
                    } else {
                        renderArrivalRows(nodeId, nodeNm, nodeNo, filterRouteNo, container, fAllRoutes, fArrMap);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.VISIBLE) return;
                    container.removeAllViews();
                    TextView tv = new TextView(this);
                    tv.setText("조회 실패: " + e.getMessage());
                    tv.setTextColor(Color.parseColor("#E74C3C"));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                    tv.setGravity(Gravity.CENTER);
                    container.addView(tv);
                });
            }
        }


    /** 도착정보 행 즉시 렌더링 (캐시 히트 or 골격 UI용) */
    private void renderArrivalRows(String nodeId, String nodeNm, String nodeNo,
                                   String filterRouteNo, LinearLayout container,
                                   java.util.List<String[]> allRoutes,
                                   java.util.Map<String, String[]> arrMap) {
        // 검색화면이면 차단
        if (busSearchArea != null && busSearchArea.getVisibility() == android.view.View.VISIBLE) return;
        // 도착화면 태그 검증
        if (busFixedHeader == null || !("arrival_" + nodeId).equals(busFixedHeader.getTag())) return;
        // 가장 빠른 버스 계산
        String soonRno = ""; int soonSec = Integer.MAX_VALUE;
        for (java.util.Map.Entry<String, String[]> en : arrMap.entrySet()) {
            String ts = en.getValue()[0];
            int s2 = Integer.MAX_VALUE;
            if (ts.equals("곧 도착")) s2 = 0;
            else if (ts.contains("분")) {
                try { s2 = Integer.parseInt(ts.replaceAll("[^0-9]","")) * 60; } catch(Exception ig){}
            }
            if (s2 < soonSec) { soonSec = s2; soonRno = en.getKey(); }
        }
        // 헤더 곧도착 업데이트
        if (busFixedHeader != null && busFixedHeader.getChildCount() >= 3) {
            android.view.View infoBoxV = busFixedHeader.getChildAt(2);
            if (infoBoxV instanceof LinearLayout) {
                android.view.View ph = ((LinearLayout)infoBoxV).findViewWithTag("soon_ph");
                if (ph instanceof TextView && !soonRno.isEmpty() && soonSec < Integer.MAX_VALUE) {
                    int fm = soonSec / 60;
                    String soonTxt = soonSec == 0 ? "곧 도착  " + soonRno + "번"
                            : fm + "분 후  " + soonRno + "번";
                    ((TextView)ph).setText(soonTxt);
                    ((TextView)ph).setTextColor(Color.parseColor("#E74C3C"));
                    ((TextView)ph).setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
                    ((TextView)ph).setTypeface(null, android.graphics.Typeface.BOLD);
                } else if (ph instanceof TextView && arrMap.isEmpty()) {
                    ((TextView)ph).setText("실시간 정보 불러오는 중...");
                    ((TextView)ph).setTextColor(Color.parseColor("#AAAAAA"));
                    ((TextView)ph).setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                }
            }
        }
        container.removeAllViews();
        // fetchAndRenderArrival의 runOnUiThread 내부와 동일한 로직 호출
        // allRoutes와 arrMap을 final로 넘겨 렌더링
        final java.util.List<String[]> fR = allRoutes;
        final java.util.Map<String,String[]> fA = arrMap;
        final boolean fEmpty = arrMap.isEmpty();
        if (allRoutes.isEmpty()) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("이 정류장 노선 정보가 없습니다\n타임라인을 먼저 한 번 열어주세요");
            tvEmpty.setTextColor(Color.parseColor("#AAAAAA"));
            tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            tvEmpty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams emLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            emLp.setMargins(dpToPx(16), dpToPx(24), dpToPx(16), 0);
            tvEmpty.setLayoutParams(emLp);
            container.addView(tvEmpty);
            return;
        }
        for (String[] route : fR) {
            String rno = route[0], stnm = route[2], etnm = route[3];
            if (!filterRouteNo.isEmpty() && !rno.equals(filterRouteNo)) continue;
            String[] ai = fA.get(rno);
            String timeStr, prevStr, timeColor, endNm, nextNm;
            if (ai != null) {
                timeStr = ai[0]; prevStr = ai[1]; timeColor = ai[2];
                endNm = !ai[3].isEmpty() ? ai[3] : etnm; nextNm = ai[4];
            } else {
                String nextDep = getNextDeparture(rno, true);
                timeStr = nextDep.isEmpty() ? (fEmpty ? "조회중..." : "도착정보 없음") : nextDep;
                prevStr = nextDep.isEmpty() ? "" : "[기점]";
                timeColor = "#555555"; endNm = etnm; nextNm = "";
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dpToPx(14), dpToPx(8), dpToPx(10), dpToPx(8));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            // 배경 먼저 설정 (자식뷰 addView 전)
            android.graphics.drawable.StateListDrawable sldEarly = new android.graphics.drawable.StateListDrawable();
            sldEarly.addState(new int[]{android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(Color.parseColor("#E3F2FD")));
            sldEarly.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.WHITE));
            row.setBackground(sldEarly);

            // ─ 왼쪽: 노선번호 + 서브텍스트 ─
            LinearLayout leftCol = new LinearLayout(this);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            leftCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvRno = new TextView(this);
            tvRno.setText(rno);
            tvRno.setTextColor(Color.parseColor("#0984E3"));
            tvRno.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(18));
            tvRno.setTypeface(null, android.graphics.Typeface.BOLD);
            tvRno.setSingleLine(true);
            tvRno.setEllipsize(null);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                tvRno, 8, 18, 1, android.util.TypedValue.COMPLEX_UNIT_DIP);
            leftCol.addView(tvRno);

            // 서브텍스트 (방면+다음정류소)
            String validNextNm = (!nextNm.isEmpty() && !nextNm.equals(nodeNm)) ? nextNm : "";
            String subTxt = "";
            if (!endNm.isEmpty() && !validNextNm.isEmpty()) {
                subTxt = endNm + "방면  다음 : " + validNextNm;
            } else if (!endNm.isEmpty()) {
                subTxt = endNm + "방면";
            } else if (!validNextNm.isEmpty()) {
                subTxt = "다음 : " + validNextNm;
            }
            if (!subTxt.isEmpty()) {
                TextView tvSub = new TextView(this);
                tvSub.setText(subTxt);
                tvSub.setTextColor(Color.parseColor("#888888"));
                tvSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                tvSub.setSingleLine(true);
                tvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                subLp.setMargins(0, dpToPx(2), 0, 0);
                tvSub.setLayoutParams(subLp);
                leftCol.addView(tvSub);
            }
            row.addView(leftCol);

            // ─ 오른쪽: 시간 + 즐겨찾기/알림 버튼 (세로) ─
            final String fRno = rno, fRid = route[1], fRtp = route.length > 4 ? route[4] : "";
            final String favKey = "fav_stop_" + fRid + "_" + nodeId;
            final String favNameKey = "fav_stop_name_" + fRid + "_" + nodeId;
            final String favNoKey   = "fav_stop_no_"   + fRid + "_" + nodeId;
            final String favRnoKey  = "fav_stop_route_"    + fRid + "_" + nodeId;
            final String favRidKey  = "fav_stop_routeid_"  + fRid + "_" + nodeId;
            boolean isFavArr = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(favKey, false);

            LinearLayout rightCol = new LinearLayout(this);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setGravity(Gravity.END);
            rightCol.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 시간 행
            LinearLayout timeRow = new LinearLayout(this);
            timeRow.setOrientation(LinearLayout.HORIZONTAL);
            timeRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            timeRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView tvTime = new TextView(this);
            tvTime.setText(timeStr);
            tvTime.setTextColor(Color.parseColor(timeColor));
            tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
            tvTime.setTypeface(null, android.graphics.Typeface.BOLD);
            timeRow.addView(tvTime);
            if (!prevStr.isEmpty()) {
                TextView tvPrev = new TextView(this);
                tvPrev.setText(" " + prevStr);
                tvPrev.setTextColor(Color.parseColor("#888888"));
                tvPrev.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
                timeRow.addView(tvPrev);
            }
            rightCol.addView(timeRow);

            // 즐겨찾기 버튼
            TextView tvStar2 = new TextView(this);
            tvStar2.setText("즐겨찾기");
            tvStar2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvStar2.setTypeface(null, android.graphics.Typeface.BOLD);
            tvStar2.setGravity(Gravity.CENTER);
            tvStar2.setPadding(dpToPx(9), dpToPx(5), dpToPx(9), dpToPx(5));
            android.graphics.drawable.GradientDrawable starBg2 = new android.graphics.drawable.GradientDrawable();
            starBg2.setCornerRadius(dpToPx(5));
            if (isFavArr) { starBg2.setColor(Color.parseColor("#F39C12")); starBg2.setStroke(dpToPx(1), Color.parseColor("#F39C12")); tvStar2.setTextColor(Color.WHITE); }
            else          { starBg2.setColor(Color.WHITE); starBg2.setStroke(dpToPx(1), Color.parseColor("#AAAAAA")); tvStar2.setTextColor(Color.parseColor("#888888")); }
            tvStar2.setBackground(starBg2);
            tvStar2.setClickable(true);
            tvStar2.setFocusable(true);
            tvStar2.setOnClickListener(v2 -> {
                boolean wasFav = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(favKey, false);
                if (wasFav) {
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .remove(favKey).remove(favNameKey).remove(favNoKey)
                        .remove(favRnoKey).remove(favRidKey).remove(favKey + "_memo").apply();
                    android.graphics.drawable.GradientDrawable offBg = new android.graphics.drawable.GradientDrawable();
                    offBg.setCornerRadius(dpToPx(4)); offBg.setColor(Color.WHITE);
                    offBg.setStroke(dpToPx(1), Color.parseColor("#AAAAAA"));
                    tvStar2.setTextColor(Color.parseColor("#888888")); tvStar2.setBackground(offBg);
                    android.widget.Toast.makeText(this, nodeNm + " 즐겨찾기 해제", android.widget.Toast.LENGTH_SHORT).show();
                    busFavDirty = true;
                } else {
                    String exMemo = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(favKey + "_memo", "");
                    android.app.Dialog d2 = new android.app.Dialog(this, android.R.style.Theme_Material_Light_Dialog);
                    LinearLayout dl = new LinearLayout(this); dl.setOrientation(LinearLayout.VERTICAL);
                    android.graphics.drawable.GradientDrawable dlBg = new android.graphics.drawable.GradientDrawable();
                    dlBg.setColor(Color.WHITE); dlBg.setCornerRadius(dpToPx(16)); dl.setBackground(dlBg);
                    dl.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));
                    TextView ttl = new TextView(this); ttl.setText(nodeNm + " (" + fRno + "번)");
                    ttl.setTextColor(Color.parseColor("#0984E3")); ttl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
                    ttl.setTypeface(null, android.graphics.Typeface.BOLD); ttl.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams ttlLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    ttlLp.setMargins(0, 0, 0, dpToPx(14)); ttl.setLayoutParams(ttlLp); dl.addView(ttl);
                    android.widget.EditText et2 = new android.widget.EditText(this);
                    et2.setHint("메모 (선택사항)"); et2.setText(exMemo);
                    et2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    android.graphics.drawable.GradientDrawable etBg2 = new android.graphics.drawable.GradientDrawable();
                    etBg2.setColor(Color.parseColor("#F8F9FA")); etBg2.setCornerRadius(dpToPx(8));
                    etBg2.setStroke(dpToPx(1), Color.parseColor("#DDDDDD")); et2.setBackground(etBg2);
                    et2.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
                    LinearLayout.LayoutParams etLp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    etLp2.setMargins(0, 0, 0, dpToPx(16)); et2.setLayoutParams(etLp2); dl.addView(et2);
                    LinearLayout btnRow2 = new LinearLayout(this); btnRow2.setOrientation(LinearLayout.HORIZONTAL);
                    btnRow2.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    TextView btnC2 = new TextView(this); btnC2.setText("취소");
                    btnC2.setTextColor(Color.parseColor("#888888")); btnC2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    btnC2.setGravity(Gravity.CENTER); btnC2.setPadding(0, dpToPx(14), 0, dpToPx(14));
                    android.graphics.drawable.GradientDrawable cBg2 = new android.graphics.drawable.GradientDrawable();
                    cBg2.setColor(Color.parseColor("#F0F0F0")); cBg2.setCornerRadius(dpToPx(12)); btnC2.setBackground(cBg2);
                    LinearLayout.LayoutParams cLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    cLp2.setMargins(0, 0, dpToPx(8), 0); btnC2.setLayoutParams(cLp2);
                    btnC2.setOnClickListener(vc -> d2.dismiss()); btnRow2.addView(btnC2);
                    TextView btnOk2 = new TextView(this); btnOk2.setText("확인");
                    btnOk2.setTextColor(Color.WHITE); btnOk2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    btnOk2.setGravity(Gravity.CENTER); btnOk2.setPadding(0, dpToPx(14), 0, dpToPx(14));
                    android.graphics.drawable.GradientDrawable okBg2 = new android.graphics.drawable.GradientDrawable();
                    okBg2.setColor(Color.parseColor("#0984E3")); okBg2.setCornerRadius(dpToPx(12)); btnOk2.setBackground(okBg2);
                    btnOk2.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    btnOk2.setOnClickListener(vo -> {
                        String memo2 = et2.getText().toString().trim();
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(favKey, true).putString(favNameKey, nodeNm)
                            .putString(favNoKey, nodeNo).putString(favRnoKey, fRno)
                            .putString(favRidKey, fRid).putString(favKey + "_memo", memo2).apply();
                        android.graphics.drawable.GradientDrawable onBg2 = new android.graphics.drawable.GradientDrawable();
                        onBg2.setCornerRadius(dpToPx(4)); onBg2.setColor(Color.parseColor("#F39C12"));
                        onBg2.setStroke(dpToPx(1), Color.parseColor("#F39C12"));
                        tvStar2.setTextColor(Color.WHITE); tvStar2.setBackground(onBg2);
                        android.widget.Toast.makeText(this, nodeNm + " 즐겨찾기 추가", android.widget.Toast.LENGTH_SHORT).show();
                        busFavDirty = true; d2.dismiss();
                    });
                    btnRow2.addView(btnOk2); dl.addView(btnRow2);
                    d2.setContentView(dl);
                    if (d2.getWindow() != null) d2.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                    d2.show();
                }
            });

            // 알림 버튼
            final String alarmKey2 = "bus_alarm_" + fRid + "_" + nodeId;
            boolean isAlarmed2 = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(alarmKey2, false);
            TextView tvBell2 = new TextView(this);
            tvBell2.setText("알림");
            tvBell2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvBell2.setTypeface(null, android.graphics.Typeface.BOLD);
            tvBell2.setGravity(Gravity.CENTER);
            tvBell2.setPadding(dpToPx(9), dpToPx(4), dpToPx(9), dpToPx(4));
            android.graphics.drawable.GradientDrawable bellBg2 = new android.graphics.drawable.GradientDrawable();
            bellBg2.setCornerRadius(dpToPx(6));
            if (isAlarmed2) { bellBg2.setColor(Color.parseColor("#5BA9F0")); bellBg2.setStroke(dpToPx(1), Color.parseColor("#5BA9F0")); tvBell2.setTextColor(Color.WHITE); }
            else            { bellBg2.setColor(Color.WHITE); bellBg2.setStroke(dpToPx(1), Color.parseColor("#AAAAAA")); tvBell2.setTextColor(Color.parseColor("#888888")); }
            tvBell2.setBackground(bellBg2);
            tvBell2.setClickable(true);
            tvBell2.setFocusable(true);
            tvBell2.setOnClickListener(vb -> android.widget.Toast.makeText(this, fRno + "번 알림 (준비중)", android.widget.Toast.LENGTH_SHORT).show());

            // 버튼 행 (즐겨찾기 + 알림)
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnRowLp.setMargins(0, dpToPx(4), 0, 0);
            btnRow.setLayoutParams(btnRowLp);
            btnRow.addView(tvStar2);
            LinearLayout.LayoutParams bellLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bellLp2.setMargins(dpToPx(4), 0, 0, 0);
            tvBell2.setLayoutParams(bellLp2);
            btnRow.addView(tvBell2);
            rightCol.addView(btnRow);
            row.addView(rightCol);

            // 노선 카드 클릭 → 타임라인
            row.setClickable(true); row.setFocusable(true);
            row.setOnClickListener(vr -> {
                String foundRid = fRid;
                String foundRtp = fRtp;
                if ((foundRid == null || foundRid.isEmpty()) && routeDbList != null) {
                    for (String[] rd : routeDbList) {
                        if (rd[1].equals(fRno)) { foundRid = rd[0]; foundRtp = rd.length > 4 ? rd[4] : ""; break; }
                    }
                }
                if (foundRid != null && !foundRid.isEmpty()) {
                    busFixedHeader.removeAllViews();
                    busResultContainer.removeAllViews();
                    busScreenLoadStops(foundRid, fRno, busResultContainer, "forward", foundRtp);
                } else {
                    android.widget.Toast.makeText(this, fRno + "번 노선 정보를 찾을 수 없습니다", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            container.addView(row);
            android.view.View div = new android.view.View(this);
            div.setBackgroundColor(Color.parseColor("#EEEEEE"));
            div.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
            container.addView(div);
        }
    }

    /** 버스 검색 결과 카드 공통 빌더 */
    private LinearLayout makeBusCard(String title, String sub, String tap, String titleColor) {
        return makeBusCard(title, sub, tap, titleColor, "");
    }

    private LinearLayout makeBusCard(String title, String sub, String tap, String titleColor, String routeType) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeShadowCardDrawable("#FFFFFF", 10, 4));
        card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(10));
        card.setLayoutParams(lp);
        card.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));

        // 제목 행 (배지 + 번호)
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 노선유형 배지
        if (!routeType.isEmpty()) {
            String[] badgeInfo = routeTypeBadge(routeType);
            TextView tvBadge = new TextView(this);
            tvBadge.setText(badgeInfo[0]);
            tvBadge.setTextColor(Color.WHITE);
            tvBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
            tvBadge.setTypeface(null, android.graphics.Typeface.BOLD);
            tvBadge.setGravity(Gravity.CENTER);
            tvBadge.setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3));
            android.graphics.drawable.GradientDrawable badgeBg =
                    new android.graphics.drawable.GradientDrawable();
            badgeBg.setColor(Color.parseColor(badgeInfo[1]));
            badgeBg.setCornerRadius(dpToPx(5));
            tvBadge.setBackground(badgeBg);
            LinearLayout.LayoutParams bdLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bdLp.setMargins(0, 0, dpToPx(8), 0);
            tvBadge.setLayoutParams(bdLp);
            titleRow.addView(tvBadge);
        }

        TextView tvT = new TextView(this);
        tvT.setText(title);
        tvT.setTextColor(Color.parseColor(titleColor));
        tvT.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(17));
        tvT.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(tvT);
        card.addView(titleRow);

        if (!sub.isEmpty()) {
            TextView tvS = new TextView(this);
            tvS.setText(sub);
            tvS.setTextColor(Color.parseColor("#555555"));
            tvS.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sl.setMargins(0, dpToPx(4), 0, 0);
            tvS.setLayoutParams(sl);
            card.addView(tvS);
        }
        // tap 문구 제거
        return card;
    }

    /** 노선유형 → [표시명, 배지색] */
    private String[] routeTypeBadge(String routeType) {
        if (routeType == null || routeType.isEmpty()) return new String[]{"", "#0984E3"};
        if (routeType.contains("광역"))   return new String[]{"광역", "#8E44AD"};
        if (routeType.contains("직행"))   return new String[]{"직행", "#C0392B"};
        if (routeType.contains("급행"))   return new String[]{"급행", "#E74C3C"};
        if (routeType.contains("간선"))   return new String[]{"도시", "#0984E3"};
        if (routeType.contains("지선"))   return new String[]{"지선", "#27AE60"};
        if (routeType.contains("마을"))   return new String[]{"마을", "#00B894"};
        if (routeType.contains("외곽"))   return new String[]{"외곽", "#E67E22"};
        if (routeType.contains("순환"))   return new String[]{"순환", "#16A085"};
        if (routeType.contains("공항"))   return new String[]{"공항", "#2980B9"};
        if (routeType.contains("좌석"))   return new String[]{"좌석", "#D35400"};
        if (routeType.contains("도시"))   return new String[]{"도시", "#0984E3"};
        if (routeType.contains("계룡"))   return new String[]{"계룡", "#6C5CE7"};
        if (routeType.contains("농어촌")) return new String[]{"농촌", "#FDCB6E"};
        if (routeType.contains("시외"))   return new String[]{"시외", "#636E72"};
        // 그 외는 앞 2글자
        String label = routeType.length() > 2 ? routeType.substring(0, 2) : routeType;
        return new String[]{label, "#636E72"};
    }

    private void showMealPlanScreen() {
        showSingleImageScreen("🍱 식단표", "이번 달 식단을 확인합니다", "nature3.png");
    }

    private void showFaxGuideScreen() {
        isOnSubScreen     = true;
        isOnMenuScreen    = false;
        isOnBalanceScreen = false;
        // 루트
        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(Color.parseColor("#F5F3FA"));
        root.setPadding(0, 0, 0, 0);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            int bot = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(0, top, 0, bot);
            return insets;
        });

        // ── 하단 고정 돌아가기 버튼 (통장잔액과 동일 스타일) ──
        android.graphics.drawable.GradientDrawable backBg =
                new android.graphics.drawable.GradientDrawable();
        backBg.setColor(Color.parseColor("#C8BFEF"));
        backBg.setCornerRadius(dpToPx(14));
        Button btnBack = new Button(this);
        btnBack.setText("← 돌아가기");
        btnBack.setBackground(backBg);
        btnBack.setTextColor(Color.parseColor("#4A3DBF"));
        btnBack.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
        btnBack.setTypeface(null, android.graphics.Typeface.BOLD);
        btnBack.setId(View.generateViewId());
        int btnId = btnBack.getId();
        RelativeLayout.LayoutParams backLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, dpToPx(50));
        backLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        backLp.setMargins(dpToPx(16), 0, dpToPx(16), dpToPx(10));
        btnBack.setLayoutParams(backLp);
        btnBack.setOnClickListener(v -> { isOnSubScreen = false; if (isOwner) ownerMenuBuilder.build(); else userMenuBuilder.build(false); });
        root.addView(btnBack);

        // ── 상태바 공간 (root padding으로 처리하므로 높이 0) ────────────────────
        View faxStatusBg = new View(this);
        faxStatusBg.setBackgroundColor(Color.WHITE);
        faxStatusBg.setId(View.generateViewId());
        int faxStatusBgId = faxStatusBg.getId();
        RelativeLayout.LayoutParams fsbLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, 0);
        fsbLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        faxStatusBg.setLayoutParams(fsbLp);
        root.addView(faxStatusBg);

        // ── 상단 헤더 (상태바 바로 아래) ──────────────────────
        LinearLayout faxHeader = new LinearLayout(this);
        faxHeader.setOrientation(LinearLayout.HORIZONTAL);
        faxHeader.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable fhGrad =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#7C6FE0"), Color.parseColor("#9B8FF5")});
        fhGrad.setCornerRadii(new float[]{0,0,0,0,dpToPx(20),dpToPx(20),dpToPx(20),dpToPx(20)});
        faxHeader.setBackground(fhGrad);
        faxHeader.setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(18));
        faxHeader.setId(View.generateViewId());
        int faxHeaderId = faxHeader.getId();
        RelativeLayout.LayoutParams fhLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        fhLp.addRule(RelativeLayout.BELOW, faxStatusBgId);
        faxHeader.setLayoutParams(fhLp);

        TextView tvFaxIcon = new TextView(this);
        tvFaxIcon.setText("📠");
        tvFaxIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20);
        tvFaxIcon.setPadding(0, 0, dpToPx(10), 0);
        faxHeader.addView(tvFaxIcon);

        LinearLayout faxHeaderTxt = new LinearLayout(this);
        faxHeaderTxt.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fhtLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        faxHeaderTxt.setLayoutParams(fhtLp);

        TextView tvFaxTitle = new TextView(this);
        tvFaxTitle.setText("팩스 전송 방법");
        tvFaxTitle.setTextColor(Color.WHITE);
        tvFaxTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
        tvFaxTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        faxHeaderTxt.addView(tvFaxTitle);

        TextView tvFaxSub = new TextView(this);
        tvFaxSub.setText("순서대로 따라하세요");
        tvFaxSub.setTextColor(Color.parseColor("#D4C8FF"));
        tvFaxSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
        LinearLayout.LayoutParams fsubLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fsubLp.setMargins(0, dpToPx(2), 0, 0);
        tvFaxSub.setLayoutParams(fsubLp);
        faxHeaderTxt.addView(tvFaxSub);
        faxHeader.addView(faxHeaderTxt);
        root.addView(faxHeader);

        // ── 스크롤 영역 (헤더 아래, 버튼 위) ─────────────
        ScrollView sv = new ScrollView(this);
        RelativeLayout.LayoutParams svLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        svLp.addRule(RelativeLayout.BELOW, faxHeaderId);
        svLp.addRule(RelativeLayout.ABOVE, btnId);
        sv.setLayoutParams(svLp);
        sv.setBackgroundColor(Color.parseColor("#F5F3FA"));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(16));
        sv.addView(layout);

        String[] steps = {
                "1. 팩스 전환  –  팩스 전환 버튼 누름",
                "2. 전송지 투입  –  전송지 정면(위로 향하게)",
                "3. 팩스준비  –  받을 곳 번호 입력",
                "4. 송신 중 메시지  –  삐~ 소리 나면 시작 버튼",
                "5. 송신 완료  –  길게 삐~~ 소리 나면 발송 완료"
        };
        String[] imgFiles = {"fax1.png","fax2.png","fax3.png","fax4.png","fax5.png"};
        String[] stepColors = {"#4A90D9","#27AE60","#8E44AD","#E67E22","#C0392B"};

        for (int i = 0; i < 5; i++) {
            android.graphics.drawable.GradientDrawable cardBg =
                    new android.graphics.drawable.GradientDrawable();
            cardBg.setColor(Color.WHITE);
            cardBg.setCornerRadius(dpToPx(16));
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(cardBg);
            card.setElevation(dpToPx(4));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, dpToPx(16));
            card.setLayoutParams(cardLp);
            card.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

            TextView tvStep = new TextView(this);
            tvStep.setText(steps[i]);
            tvStep.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
            tvStep.setTypeface(null, android.graphics.Typeface.BOLD);
            tvStep.setTextColor(Color.parseColor(stepColors[i]));
            tvStep.setShadowLayer(2f, 1f, 1f, Color.parseColor("#20000000"));
            tvStep.setPadding(0, 0, 0, dpToPx(12));
            card.addView(tvStep);

            try {
                java.io.InputStream is = getAssets().open(imgFiles[i]);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                if (bmp != null) {
                    ZoomImageView ziv = new ZoomImageView(this);
                    ziv.setImageBitmap(bmp);
                    ziv.setBackgroundColor(Color.parseColor("#F0F0F0"));
                    int imgH = (int)(bmp.getHeight() * (getResources().getDisplayMetrics().widthPixels - dpToPx(72)) / (float)bmp.getWidth());
                    LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, imgH);
                    ziv.setLayoutParams(ivLp);
                    card.addView(ziv);
                } else {
                    TextView tvErr = new TextView(this);
                    tvErr.setText("이미지 로드 실패: " + imgFiles[i]);
                    tvErr.setTextColor(Color.RED);
                    card.addView(tvErr);
                }
            } catch (Exception e) {
                TextView tvErr = new TextView(this);
                tvErr.setText("오류: " + e.getMessage());
                tvErr.setTextColor(Color.RED);
                card.addView(tvErr);
            }

            layout.addView(card);
        }

        root.addView(sv);
        setContentView(root);
    }

    private void showMonthJumpDialog() {
        if (cachedBlocks == null || cachedBlocks.isEmpty()) return;
        // 블록에서 년월 목록 추출 (중복 제거, 최신순)
        java.util.LinkedHashSet<String> monthSet = new java.util.LinkedHashSet<>();
        for (int i = cachedBlocks.size() - 1; i >= 0; i--) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{4}-\\d{2})-\\d{2}").matcher(cachedBlocks.get(i));
            if (m.find()) monthSet.add(m.group(1));
        }
        if (monthSet.isEmpty()) return;
        String[] months = monthSet.toArray(new String[0]);
        // 화면에 표시할 레이블 (yyyy-MM → yyyy년 MM월)
        String[] labels = new String[months.length];
        for (int i = 0; i < months.length; i++) {
            String[] p = months[i].split("-");
            labels[i] = p[0] + "년 " + p[1] + "월";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("월 선택")
                .setItems(labels, (d, which) -> {
                    String target = months[which];
                    // msgContainer에서 해당 월 첫 뷰 위치 찾기
                    for (int i = 0; i < msgContainer.getChildCount(); i++) {
                        android.view.View child = msgContainer.getChildAt(i);
                        Object tag = child.getTag();
                        if (tag instanceof String && ((String) tag).startsWith(target)) {
                            final int top = child.getTop();
                            msgScrollView.post(() -> msgScrollView.smoothScrollTo(0, top));
                            break;
                        }
                    }
                })
                .show();
    }

    private void sendBalanceChangedNotification(String accountName, String oldVal, String newVal) {
        try {
            String CHANNEL_ID = "sms2drive_balance";
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        CHANNEL_ID, "잔액 변경 알림",
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(channel);
            }
            android.content.Intent launchIntent = new android.content.Intent(this, PinActivity.class);
            launchIntent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 0, launchIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            androidx.core.app.NotificationCompat.Builder builder =
                    new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(accountName + " 잔액 변경")
                            .setContentText(oldVal + " → " + newVal)
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH);
            nm.notify((int) System.currentTimeMillis(), builder.build());
        } catch (Exception e) {
            android.util.Log.e("PinActivity", "알림 실패: " + e.getMessage());
        }
    }

    private void incrementalLoad(TextView tvLoading) {
        // 캐시가 있으면 Drive 읽기 생략 — 화면만 갱신
        if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
            if (isOnBalanceScreen) renderLatest(displayedCount);
            if (isOnMenuScreen && menuBalTv != null) updateMenuBalCards(cachedBlocks);
            return;
        }
        // 캐시 없을 때만 Drive에서 읽기
        if (!isOnBalanceScreen && !isOnMenuScreen) return;
        try {
            readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String content) {
                    runOnUiThread(() -> {
                        String[] blocks = content.split(
                                "-----------------------------------\\r?\\n");
                        List<String> newBlocks = new ArrayList<>();
                        for (String b : blocks) {
                            if (!b.trim().isEmpty()) newBlocks.add(b);
                        }
                        cachedBlocks = newBlocks;
                        lastKnownBlockCount = newBlocks.size();
                        if (tvBalValues != null) updateBalanceValues(newBlocks);
                        if (isOnBalanceScreen) renderLatest(displayedCount);
                        if (isOnMenuScreen && menuBalTv != null) updateMenuBalCards(newBlocks);
                    });
                }
                @Override public void onFailure(String error) {}
            });
        } catch (Exception ignored) {}
    }

    // ── 잔액 카드 값 갱신 (시간 기준 가장 최신 잔액) ─────────
    /** tvBalValues 없을 때도 (백그라운드/다른화면) SharedPreferences + 위젯 갱신 */
    /** FCM 전송 확인 다이얼로그 (예쁜 카드 스타일) */
    private void showFcmConfirmDialog(String message, Runnable onConfirm) {
        android.app.Dialog d = new android.app.Dialog(this,
                android.R.style.Theme_Material_Light_Dialog);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dpToPx(20));
        layout.setBackground(bg);
        layout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));

        // 메시지
        TextView tvMsg = new TextView(this);
        tvMsg.setText(message);
        tvMsg.setTextColor(Color.parseColor("#1A1A2E"));
        tvMsg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
        tvMsg.setTypeface(null, android.graphics.Typeface.BOLD);
        tvMsg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.setMargins(0, 0, 0, dpToPx(20));
        tvMsg.setLayoutParams(msgLp);
        layout.addView(tvMsg);

        // 버튼 행
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 취소
        TextView btnNo = new TextView(this);
        btnNo.setText("취소");
        btnNo.setTextColor(Color.parseColor("#888888"));
        btnNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnNo.setTypeface(null, android.graphics.Typeface.BOLD);
        btnNo.setGravity(Gravity.CENTER);
        btnNo.setPadding(0, dpToPx(13), 0, dpToPx(13));
        android.graphics.drawable.GradientDrawable noBg =
                new android.graphics.drawable.GradientDrawable();
        noBg.setColor(Color.parseColor("#F0F0F0"));
        noBg.setCornerRadius(dpToPx(10));
        btnNo.setBackground(noBg);
        LinearLayout.LayoutParams noLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        noLp.setMargins(0, 0, dpToPx(8), 0);
        btnNo.setLayoutParams(noLp);
        btnNo.setOnClickListener(vv -> d.dismiss());
        btnRow.addView(btnNo);

        // 확인
        TextView btnYes = new TextView(this);
        btnYes.setText("전송");
        btnYes.setTextColor(Color.WHITE);
        btnYes.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        btnYes.setTypeface(null, android.graphics.Typeface.BOLD);
        btnYes.setGravity(Gravity.CENTER);
        btnYes.setPadding(0, dpToPx(13), 0, dpToPx(13));
        android.graphics.drawable.GradientDrawable yesBg =
                new android.graphics.drawable.GradientDrawable();
        yesBg.setColor(Color.parseColor("#E74C3C"));
        yesBg.setCornerRadius(dpToPx(10));
        btnYes.setBackground(yesBg);
        btnYes.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnYes.setOnClickListener(vv -> { d.dismiss(); onConfirm.run(); });
        btnRow.addView(btnYes);

        layout.addView(btnRow);
        d.setContentView(layout);
        d.setCancelable(true);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(
                    (int)(getResources().getDisplayMetrics().widthPixels * 0.78),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            d.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(
                            android.graphics.Color.TRANSPARENT));
        }
        d.show();
    }

    /** [TEST] 블록 삭제 시 특정 사용자 + 관리자에게만 삭제 신호 FCM 전송 */
    private void sendTestDeleteSignal(String targetEmail) {
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(this);
                reader.readFile("fcm_tokens.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String tokensContent) {
                        String targetToken = null;
                        String ownerToken  = null;
                        for (String line : tokensContent.split("\r?\n")) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            String[] parts = line.split("\\|");
                            if (parts.length < 2) continue;
                            String email = parts[0].trim();
                            String token = parts[1].trim();
                            if (token.isEmpty()) continue;
                            if (email.equalsIgnoreCase(targetEmail)) targetToken = token;
                            if (email.equalsIgnoreCase(OWNER_EMAIL))  ownerToken  = token;
                        }
                        // 대상 사용자에게 삭제 신호
                        if (targetToken != null)
                            SmsReceiver.sendDeleteSignalToToken(PinActivity.this, targetToken);
                        // 관리자에게도 (자신의 화면은 이미 갱신됐지만 pending 처리용)
                        if (ownerToken != null && !ownerToken.equals(targetToken))
                            SmsReceiver.sendDeleteSignalToToken(PinActivity.this, ownerToken);
                    }
                    @Override public void onFailure(String error) {
                        android.util.Log.e("FCM_TEST", "토큰 읽기 실패: " + error);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("FCM_TEST", "sendTestDeleteSignal 오류: " + e.getMessage());
            }
        }).start();
    }

    /** FCM 테스트를 특정 이메일에게만 전송 */
    private void sendFcmTestToSpecificUser(String fakeBody, String targetEmail) {
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.KOREA).format(new java.util.Date());
        final String[] convertedArr = {fakeBody};
        try {
            String c = new SmsReceiver().convertToNewFormatPublic(fakeBody.trim());
            if (c != null && !c.isEmpty()) convertedArr[0] = c;
        } catch (Exception ignored) {}
        final String converted = convertedArr[0];
        // ★ [TEST] 태그 추가 - Test.txt에 저장, sms_raw는 건드리지 않음
        final String newBlock = timestamp + "\n[TEST]\n" + converted;
        // Test.txt 저장 엔트리 (구분자 포함)
        final String testEntry = newBlock + "\n-----------------------------------\n";

        // 제목/본문 파싱
        String title = "[TEST] 잔액 변경", body2 = "[테스트] 통장 잔액이 변경되었습니다.";
        for (String line : converted.split("\n")) {
            String t = line.trim();
            if ((t.contains("출금") || t.contains("입금"))
                    && !title.contains("출금") && !title.contains("입금")) title = "[TEST] " + t;
            if (t.startsWith("잔액")) body2 = t;
        }
        final String fTitle = title, fBody = body2;

        new Thread(() -> {
            try {
                // ── 1. Test.txt에 이어쓰기 (sms_raw는 건드리지 않음) ──
                DriveReadHelper reader = new DriveReadHelper(this);
                reader.readFile("Test.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String existing) {
                        saveTestFile(existing + testEntry);
                    }
                    @Override public void onFailure(String error) {
                        // Test.txt 없으면 새로 생성
                        saveTestFile(testEntry);
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("FCM_TEST", "Test.txt 읽기 오류: " + e.getMessage());
                saveTestFile(testEntry); // 오류 시 새로 생성
            }

            // ── 2. fcm_tokens.txt에서 토큰 수집 후 FCM 전송 ──
            try {
                DriveReadHelper reader2 = new DriveReadHelper(this);
                reader2.readFile("fcm_tokens.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String tokensContent) {
                        String targetToken = null;
                        String ownerToken  = null;
                        for (String line : tokensContent.split("\r?\n")) {
                            line = line.trim();
                            if (line.isEmpty()) continue;
                            String[] parts = line.split("\\|");
                            if (parts.length < 2) continue;
                            String email = parts[0].trim();
                            String token = parts[1].trim();
                            if (token.isEmpty()) continue;
                            if (email.equalsIgnoreCase(targetEmail)) targetToken = token;
                            if (email.equalsIgnoreCase(OWNER_EMAIL))  ownerToken  = token;
                        }
                        if (targetToken == null) {
                            runOnUiThread(() -> android.widget.Toast.makeText(PinActivity.this,
                                    targetEmail + " 토큰 없음", android.widget.Toast.LENGTH_SHORT).show());
                            return;
                        }
                        // 대상 사용자에게 FCM 전송
                        SmsReceiver.sendFcmToSpecificToken(
                                PinActivity.this, targetToken, fTitle, fBody, newBlock);
                        android.util.Log.d("FCM_TEST", "특정 사용자 전송: " + targetEmail);
                        // 관리자에게도 FCM 전송 (테스트 확인용)
                        if (ownerToken != null && !ownerToken.equals(targetToken)) {
                            SmsReceiver.sendFcmToSpecificToken(
                                    PinActivity.this, ownerToken, fTitle, fBody, newBlock);
                            android.util.Log.d("FCM_TEST", "관리자 동시 전송: " + OWNER_EMAIL);
                        }
                    }
                    @Override public void onFailure(String error) {
                        runOnUiThread(() -> android.widget.Toast.makeText(PinActivity.this,
                                "토큰 파일 읽기 실패", android.widget.Toast.LENGTH_SHORT).show());
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("FCM_TEST", "FCM 전송 오류: " + e.getMessage());
            }
        }).start();
    }

    /** Test.txt Drive 저장 */
    private void saveTestFile(String content) {
        new Thread(() -> {
            try {
                DriveUploadHelper up = new DriveUploadHelper(this);
                up.uploadFileSync(content, "Test.txt");
                android.util.Log.d("FCM_TEST", "Test.txt 저장 완료");
            } catch (Exception e) {
                android.util.Log.e("FCM_TEST", "Test.txt 저장 실패: " + e.getMessage());
            }
        }).start();
    }

    private void updateWidgetFromBlocks(List<String> blocks) {
        String[][] balLatest = {
                {"5510-13", "", ""},
                {"5510-83", "", ""},
                {"5510-53", "", ""},
                {"5510-23", "", ""}
        };
        for (String block : blocks) {
            String ts = "";
            for (String line : block.split("\\r?\\n")) {
                if (line.trim().matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")) {
                    ts = line.trim(); break;
                }
            }
            for (String[] info : balLatest) {
                if (block.contains(info[0])) {
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile("잔액\\s*([\\d,]+)원").matcher(block);
                    if (m.find() && ts.compareTo(info[1]) >= 0) {
                        info[1] = ts; info[2] = m.group(1);
                    }
                }
            }
        }
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        for (String[] info : balLatest) {
            if (!info[2].isEmpty()) {
                editor.putString("bal_" + info[0], info[2] + "원"); // "원" 포함 형식 통일
                editor.putString("bal_time_" + info[0], info[1]);
            }
        }
        editor.apply();
        try {
            android.appwidget.AppWidgetManager awm =
                    android.appwidget.AppWidgetManager.getInstance(this);
            int[] ids = awm.getAppWidgetIds(
                    new android.content.ComponentName(this, BalanceWidget.class));
            for (int wid : ids) BalanceWidget.updateWidget(this, awm, wid);
        } catch (Exception ignored) {}
    }

    private void updateBalanceValues(List<String> blocks) {
        if (tvBalValues == null) return;
        // 각 계좌별: [계좌키, 최신타임스탬프, 최신잔액]
        String[][] balLatest = {
                {"5510-13", "", ""},
                {"5510-83", "", ""},
                {"5510-53", "", ""},
                {"5510-23", "", ""}
        };
        for (String block : blocks) {
            // 타임스탬프 추출 (첫 줄: "yyyy-MM-dd HH:mm:ss")
            String ts = "";
            String[] lines = block.split("\\r?\\n");
            for (String line : lines) {
                if (line.trim().matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")) {
                    ts = line.trim();
                    break;
                }
            }
            for (String[] info : balLatest) {
                if (block.contains(info[0])) {
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile("잔액\\s*([\\d,]+)원")
                                    .matcher(block);
                    if (m.find()) {
                        String amt = m.group(1);
                        // 타임스탬프가 더 최신이면 업데이트 (문자열 비교로 충분)
                        if (ts.compareTo(info[1]) >= 0) {
                            info[1] = ts;
                            info[2] = amt;
                        }
                    }
                }
            }
        }
        // UI 갱신 + SharedPreferences 저장 (위젯이 SharedPreferences를 읽으므로 반드시 저장)
        android.content.SharedPreferences.Editor editor =
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            final String val = balLatest[i][2];
            final String ts2 = balLatest[i][1];
            final String acct = balLatest[i][0];
            // SharedPreferences 저장 (위젯용) - "999,000원" 형태로 통일
            if (!val.isEmpty()) {
                editor.putString("bal_" + acct, val + "원");
                editor.putString("bal_time_" + acct, ts2);
            }
            // UI 갱신
            tvBalValues[idx].post(() ->
                    tvBalValues[idx].setText(
                            val.isEmpty() ? "데이터 없음" : val + "원"));
        }
        editor.apply();
        // 위젯 갱신 (SharedPreferences 저장 후 호출해야 최신값 반영)
        try {
            android.appwidget.AppWidgetManager awm =
                    android.appwidget.AppWidgetManager.getInstance(this);
            int[] ids = awm.getAppWidgetIds(
                    new android.content.ComponentName(this, BalanceWidget.class));
            for (int wid : ids) BalanceWidget.updateWidget(this, awm, wid);
        } catch (Exception ignored) {}
    }

    // ── 메시지 렌더링 ──────────────────────────────────────
    /** Drive memo.txt 읽어서 memoCache 구성 */
    private void loadMemoFromDrive(Runnable onDone) {
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(this);
                final String[] fc = {null};
                final Object lk = new Object();
                reader.readFile("memo.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String s) { synchronized(lk){fc[0]=s;lk.notifyAll();} }
                    @Override public void onFailure(String e) { synchronized(lk){fc[0]="";lk.notifyAll();} }
                });
                synchronized(lk){ if(fc[0]==null) lk.wait(10000); }
                java.util.Map<String,String[]> newCache = new java.util.HashMap<>();
                if (fc[0] != null && !fc[0].trim().isEmpty()) {
                    for (String line : fc[0].split("\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] parts = line.split("\\|", -1);
                        if (parts.length < 1) continue;
                        String ts = parts[0].trim();
                        String[] items = new String[5];
                        for (int i=0;i<5;i++) items[i] = (i+1 < parts.length) ? parts[i+1].trim() : "";
                        newCache.put(ts, items);
                    }
                }
                memoCache = newCache;
                memoCacheLoaded = true;
                android.util.Log.d("MEMO","memo.txt 로드: "+memoCache.size()+"건");
                if (onDone != null) runOnUiThread(onDone);
            } catch (Exception e) {
                android.util.Log.e("MEMO","loadMemo 오류: "+e.getMessage());
                memoCacheLoaded = true;
                if (onDone != null) runOnUiThread(onDone);
            }
        }).start();
    }

    /** memoCache → Drive memo.txt 저장 */
    private void saveMemoFile() {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<String,String[]> e : memoCache.entrySet()) {
                    sb.append(e.getKey());
                    for (String it : e.getValue()) sb.append("|").append(it == null ? "" : it);
                    sb.append("\n");
                }
                new DriveUploadHelper(this).uploadFileSync(sb.toString().trim(), "memo.txt");
                android.util.Log.d("MEMO","memo.txt 저장: "+memoCache.size()+"건");
            } catch (Exception e) {
                android.util.Log.e("MEMO","saveMemoFile 오류: "+e.getMessage());
            }
        }).start();
    }

    private void saveMemoToDrive(String timestamp, String shopOrig, String memoSuffix) {
        if (timestamp == null || timestamp.isEmpty() || shopOrig == null) return;
        new Thread(() -> {
            try {
                int year = Integer.parseInt(timestamp.substring(0, 4));
                String rawFileName = SmsReceiver.getSmsRawFile(year);
                final String SEP = "-----------------------------------\n";
                DriveReadHelper reader = new DriveReadHelper(PinActivity.this);
                final String[] fileHolder = {null};
                final Object lock2 = new Object();
                reader.readFile(rawFileName, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String fc) {
                        synchronized(lock2){ fileHolder[0]=fc; lock2.notifyAll(); }
                    }
                    @Override public void onFailure(String err) {
                        android.util.Log.e("MEMO_DRIVE","읽기 실패: "+err);
                        synchronized(lock2){ fileHolder[0]=""; lock2.notifyAll(); }
                    }
                });
                synchronized(lock2){ if(fileHolder[0]==null) lock2.wait(15000); }
                String fileContent = fileHolder[0];
                if (fileContent == null || fileContent.trim().isEmpty()) {
                    android.util.Log.e("MEMO_DRIVE","파일 비어있음: "+rawFileName);
                    return;
                }
                String[] blocks = fileContent.split("-----------------------------------\\r?\\n");
                StringBuilder sb = new StringBuilder();
                boolean found = false;
                for (String block : blocks) {
                    if (block.trim().isEmpty()) continue;
                    String[] lines2 = block.split("\\r?\\n");
                    if (!found && lines2.length > 0 && lines2[0].trim().equals(timestamp.trim())) {
                        found = true;
                        StringBuilder newBlock = new StringBuilder();
                        for (String ln : lines2) {
                            if (ln.trim().isEmpty()) continue;
                            String lnT = ln.trim();
                            boolean isShop = lnT.equals(shopOrig) || lnT.startsWith(shopOrig + "(");
                            if (isShop) {
                                String newLine = memoSuffix.isEmpty() ? shopOrig : shopOrig + memoSuffix;
                                newBlock.append(newLine).append("\n");
                                android.util.Log.d("MEMO_DRIVE","가게명: ["+lnT+"] -> ["+newLine+"]");
                            } else {
                                newBlock.append(lnT).append("\n");
                            }
                        }
                        sb.append(newBlock).append(SEP);
                    } else {
                        for (String ln : lines2) {
                            if (!ln.trim().isEmpty()) sb.append(ln.trim()).append("\n");
                        }
                        sb.append(SEP);
                    }
                }
                if (!found) {
                    android.util.Log.e("MEMO_DRIVE","블록 못 찾음: "+timestamp);
                    return;
                }
                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                up.uploadFileSync(sb.toString(), rawFileName);
                android.util.Log.d("MEMO_DRIVE","Drive 저장 완료: "+timestamp);
                // 캐시 업데이트
                runOnUiThread(() -> {
                    if (cachedBlocks != null) {
                        for (int ci = 0; ci < cachedBlocks.size(); ci++) {
                            String b = cachedBlocks.get(ci);
                            if (b.startsWith(timestamp.trim())) {
                                String[] bLines = b.split("\\r?\\n");
                                StringBuilder nb = new StringBuilder();
                                for (String bl : bLines) {
                                    if (bl.trim().isEmpty()) continue;
                                    boolean isSh = bl.trim().equals(shopOrig) || bl.trim().startsWith(shopOrig+"(");
                                    String newL = isSh ? (memoSuffix.isEmpty() ? shopOrig : shopOrig+memoSuffix) : bl.trim();
                                    nb.append(newL).append("\n");
                                }
                                cachedBlocks.set(ci, nb.toString());
                                break;
                            }
                        }
                    }
                });
            } catch (Exception e) {
                android.util.Log.e("MEMO_DRIVE","오류: "+e.getMessage());
            }
        }).start();
    }

    private void renderMessages(List<String> allBlocks, String filterKey) {
        renderMessages(allBlocks, filterKey, 0);
    }

    private void renderMessages(List<String> allBlocks, String filterKey, int fromOffset) {
        if (msgContainer == null) return;
        msgContainer.removeAllViews();

        boolean hasMsg = false;
        for (int i = allBlocks.size() - 1; i >= fromOffset; i--) {
            String block = allBlocks.get(i);
            if (filterKey != null && !block.contains(filterKey)) continue;
            hasMsg = true;
            final int blockIdx = i;
            final boolean isSelected = selectedIdx.contains(blockIdx);

            // ── 메모 키 생성 (루프 시작 직후 - 람다 캡처용) ──
            String memoDate2 = "";
            java.util.regex.Matcher mdm2 = java.util.regex.Pattern
                    .compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})").matcher(block);
            if (mdm2.find()) memoDate2 = mdm2.group(1);
            String memoAcct2 = "";
            for (String[] ai3 : new String[][]{
                    {"5510-13"},{"5510-83"},{"5510-53"},{"5510-23"}}) {
                if (block.contains(ai3[0])) { memoAcct2 = ai3[0]; break; }
            }
            final String memoKey = "memo_" + memoDate2 + "_" + memoAcct2;
            final String memoTimestamp = memoDate2;
            // memoCache에서만 읽기 (Drive memo.txt 기준 - 공유)
            String[] cachedItems = memoCache.get(memoTimestamp);
            String memoFromCache = "";
            if (cachedItems != null) {
                java.util.List<String> nonEmpty = new java.util.ArrayList<>();
                for (String it : cachedItems) if (it!=null&&!it.isEmpty()) nonEmpty.add(it);
                if (!nonEmpty.isEmpty()) memoFromCache = "("+android.text.TextUtils.join(",",nonEmpty)+")";
            }
            final String[] memoHolder = {memoFromCache};
            final TextView[] tvShopRef = {null};
            final String[] shopOrigHolder = {""}; // 항상 현재 화면 텍스트에서 괄호 제거로 추출

            // ── wrapper: FrameLayout 사용 (카드 위에 원 오버레이) ──
            android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            wp.setMargins(0, 0, 0, 12);
            wrapper.setLayoutParams(wp);

            // ── 메시지 카드 ──────────────────────────────────
            boolean isTestBlock = block.contains("[TEST]"); // 테스트 블록 여부
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable cardBg =
                    new android.graphics.drawable.GradientDrawable();
            if (isSelected) {
                cardBg.setColor(Color.parseColor("#D8CCFF"));
                cardBg.setStroke(2, Color.parseColor("#5B4A8A"));
            } else if (isTestBlock) {
                // [TEST] 블록: 회색 점선 테두리로 구분
                cardBg.setColor(Color.parseColor("#F5F5F5"));
                cardBg.setStroke(dpToPx(1), Color.parseColor("#AAAAAA"));
            } else {
                cardBg.setColor(Color.WHITE);
                cardBg.setStroke(1, Color.parseColor("#DDD8F0"));
            }
            cardBg.setCornerRadius(20f);
            card.setBackground(cardBg);
            card.setElevation(isSelected ? 8f : 4f);
            card.setPadding(20, 16, 20, 16);
            android.widget.FrameLayout.LayoutParams cardFp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            card.setLayoutParams(cardFp);

            // [TEST] 블록이면 상단에 테스트 배지 표시
            if (isTestBlock) {
                TextView tvTestBadge = new TextView(this);
                tvTestBadge.setText("[TEST] 테스트 문자 (Drive 미저장)");
                tvTestBadge.setTextColor(Color.parseColor("#E74C3C"));
                tvTestBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                tvTestBadge.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                badgeLp.setMargins(0, 0, 0, dpToPx(4));
                tvTestBadge.setLayoutParams(badgeLp);
                card.addView(tvTestBadge);
            }

            // ── 텍스트 내용: 구형/신형 문자 정규화 후 표시 ──
            String[] rawLines = block.split("\\r?\\n");
            boolean isPrepaid  = block.contains("선입금");  // 저장 시 이미 선입금으로 변환됨
            boolean isWithdraw = !isPrepaid && block.contains("출금");

            // ── 구형/신형 판별 및 정규화 ────────────────────
            // [구형 원본 3줄]
            //   줄1: 농협 출금65,000원
            //   줄2: 03/09 15:47 351-****-5510-83
            //   줄3: 해동상회 잔액70,060원
            //
            // [변환 결과 5줄]
            //   1. 농협 출금 65,000원
            //   2. 3월 9일 오후 3시 47분
            //   3. 351-****-5510-83 (부식비)
            //   4. 해동상회
            //   5. 잔액 70,060원
            java.util.List<String> lines = new java.util.ArrayList<>();

            // 구형 감지: MM/DD HH:mm 패턴 줄 존재 여부
            boolean isOldFormat = false;
            for (String rl : rawLines) {
                if (rl.trim().matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}.*")) {
                    isOldFormat = true;
                    break;
                }
            }

            if (isOldFormat) {
                String out1 = ""; // 1. 농협 출금 65,000원
                String out2 = ""; // 2. 3월 9일 오후 3시 47분
                String out3 = ""; // 3. 351-****-5510-83 (부식비)
                String out4 = ""; // 4. 가게명
                String out5 = ""; // 5. 잔액 70,060원

                for (String rl : rawLines) {
                    String t = rl.trim();
                    if (t.isEmpty()) continue;
                    if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) continue; // 저장 타임스탬프 제거
                    if (t.equals("[TEST]")) continue; // 테스트 태그 제거

                    // ── 줄1: 출금/입금 줄 ──────────────────────
                    if ((t.contains("출금") || t.contains("입금")) && !t.contains("잔액")) {
                        // "농협 출금65,000원" → "농협 출금 65,000원"
                        out1 = t.replaceAll("(출금|입금)(\\d)", "$1 $2");

                        // ── 줄2: MM/DD HH:mm → 한국어 날짜 ──────────
                    } else if (t.matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}\\s*")) {
                        // 날짜시간만 있는 줄: "03/03 18:30"
                        out2 = convertDateTimeToKorean(t.trim());

                        // ── 줄2+3(+4): MM/DD HH:mm 뒤에 계좌번호까지 한 줄 ──
                    } else if (t.matches("\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}\\s+.*")) {
                        String[] parts = t.split("\\s+");
                        out2 = convertDateTimeToKorean(parts[0] + " " + parts[1]);
                        if (parts.length >= 3) {
                            out3 = parts[2];
                            for (String[] info : new String[][]{
                                    {"5510-13","운영비"},{"5510-83","부식비"},
                                    {"5510-53","냉난방비"},{"5510-23","회비"}}) {
                                if (out3.contains(info[0])) {
                                    out3 += " (" + info[1] + ")"; break;
                                }
                            }
                        }
                        if (parts.length >= 4) {
                            String lastPart = parts[parts.length - 1];
                            if (lastPart.contains("잔액")) {
                                // 마지막 토큰이 잔액: 가게명+잔액이 같은 줄
                                // "03/07 13:30 351-****-5510-83 중도매인43번 잔액135,060원"
                                if (parts.length >= 5) out4 = parts[3]; // 가게명
                                out5 = lastPart.replaceAll("잔액(\\d)", "잔액 $1");
                            } else {
                                out4 = parts[3]; // 가게명만
                            }
                        }

                        // ── 줄3: 계좌번호 줄 (뒤에 가게명 있을 수 있음) ──
                        // 예: "351-****-5510-83 노은상회"
                    } else if (t.matches("351-.*")) {
                        String[] parts = t.split("\\s+", 2);
                        out3 = parts[0];
                        for (String[] info : new String[][]{
                                {"5510-13","운영비"},{"5510-83","부식비"},
                                {"5510-53","냉난방비"},{"5510-23","회비"}}) {
                            if (out3.contains(info[0])) {
                                out3 += " (" + info[1] + ")"; break;
                            }
                        }
                        // 계좌번호 뒤에 가게명이 있으면 out4에 저장
                        if (parts.length >= 2 && out4.isEmpty()) {
                            out4 = parts[1].trim();
                        }

                        // ── 줄5: 잔액 줄 ─────────────────────────────
                    } else if (t.contains("잔액")) {
                        int idx = t.indexOf("잔액");
                        String before = t.substring(0, idx).trim();
                        String after  = t.substring(idx)
                                .replaceAll("잔액(\\d)", "잔액 $1");
                        if (!before.isEmpty()) out4 = before;
                        out5 = after;

                        // ── 줄4: 가게명 (위 조건에 해당 안 되는 나머지) ──
                    } else {
                        if (out4.isEmpty()) out4 = t;
                    }
                }

                if (!out1.isEmpty()) lines.add(out1);
                if (!out2.isEmpty()) lines.add(out2);
                if (!out3.isEmpty()) lines.add(out3);
                if (!out4.isEmpty()) lines.add(out4);
                if (!out5.isEmpty()) lines.add(out5);

            } else {
                // 신형: 저장 타임스탬프 제거 + 출금/잔액 공백 정규화
                for (String rl : rawLines) {
                    String t = rl.trim();
                    if (t.isEmpty()) continue;
                    if (t.matches("\\d{4}-\\d{2}-\\d{2}.*")) continue;
                    if (t.equals("[TEST]")) continue; // 테스트 태그 제거
                    t = t.replaceAll("(출금|입금|선입금)(\\d)", "$1 $2");
                    t = t.replaceAll("잔액(\\d)", "잔액 $1");
                    if (!t.trim().isEmpty()) lines.add(t);
                }
            }

            // ── 출금/입금/선입금 줄 인덱스 찾기 ──────────────
            int firstContentLine = -1;
            for (int j = 0; j < lines.size(); j++) {
                if (lines.get(j).contains("출금") || lines.get(j).contains("입금") || lines.get(j).contains("선입금")) {
                    firstContentLine = j;
                    break;
                }
            }

            for (int j = 0; j < lines.size(); j++) {
                String line = lines.get(j);
                if (line.trim().isEmpty()) continue;

                if (j == firstContentLine) {
                    // 출금/입금/선입금 단어만 색상
                    android.text.SpannableString sp =
                            new android.text.SpannableString(line);
                    String colorWord = isPrepaid ? "선입금" : (isWithdraw ? "출금" : "입금");
                    int wordColor = isWithdraw
                            ? Color.parseColor("#E74C3C")
                            : Color.parseColor("#2980B9");
                    int wordIdx = line.indexOf(colorWord);
                    if (wordIdx >= 0) {
                        sp.setSpan(
                                new android.text.style.ForegroundColorSpan(wordColor),
                                wordIdx, wordIdx + colorWord.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sp.setSpan(
                                new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                wordIdx, wordIdx + colorWord.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    // ── 첫째 줄 + 📝 버튼 가로 행 ──────────────
                    LinearLayout topRow = new LinearLayout(this);
                    topRow.setOrientation(LinearLayout.HORIZONTAL);
                    topRow.setGravity(Gravity.CENTER_VERTICAL);
                    topRow.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));

                    TextView tv = new TextView(this);
                    tv.setText(sp);
                    tv.setTextColor(Color.parseColor("#222222"));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setShadowLayer(2f, 1f, 1f, Color.parseColor("#15000000"));
                    tv.setPadding(0, 3, 0, 3);
                    tv.setLayoutParams(new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    topRow.addView(tv);

                    // 📝 버튼 + 메모 뱃지 (메모 왼쪽, 아이콘 오른쪽)
                    final LinearLayout memoBtn2 = new LinearLayout(this);
                    memoBtn2.setOrientation(LinearLayout.HORIZONTAL);
                    memoBtn2.setGravity(Gravity.CENTER_VERTICAL);
                    memoBtn2.setPadding(dpToPx(6), dpToPx(1), 0, 0);
                    memoBtn2.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
                    memoBtn2.setClipChildren(true);

                    // 메모 뱃지 (왼쪽)
                    final TextView tvMemoBadge = new TextView(this);
                    tvMemoBadge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11);
                    tvMemoBadge.setTypeface(null, Typeface.BOLD);
                    if (!memoHolder[0].isEmpty()) {
                        tvMemoBadge.setText("메모");
                        tvMemoBadge.setTextColor(Color.parseColor("#7A5800"));
                        android.graphics.drawable.GradientDrawable badgeBg =
                                new android.graphics.drawable.GradientDrawable();
                        badgeBg.setColor(Color.parseColor("#FFF3CD"));
                        badgeBg.setCornerRadius(dpToPx(10));
                        tvMemoBadge.setBackground(badgeBg);
                        tvMemoBadge.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
                        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        badgeLp.setMargins(0, 0, dpToPx(4), 0);
                        tvMemoBadge.setLayoutParams(badgeLp);
                    }
                    memoBtn2.addView(tvMemoBadge);

                    // 📝 아이콘 원형 배경 (오른쪽)
                    final TextView tvMemoIcon = new TextView(this);
                    tvMemoIcon.setText("📝");
                    tvMemoIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
                    tvMemoIcon.setGravity(Gravity.CENTER);
                    int iconSize = dpToPx(26);
                    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
                    tvMemoIcon.setLayoutParams(iconLp);

                    // 원형 배경: 메모 없으면 회색, 있으면 노란색
                    android.graphics.drawable.GradientDrawable iconBg =
                            new android.graphics.drawable.GradientDrawable();
                    iconBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    if (!memoHolder[0].isEmpty()) {
                        iconBg.setColor(Color.parseColor("#FFF3CD"));
                        iconBg.setStroke(dpToPx(1), Color.parseColor("#F0A500"));
                    } else {
                        iconBg.setColor(Color.parseColor("#F5F5F5"));
                        iconBg.setStroke(dpToPx(1), Color.parseColor("#F0A500"));
                    }
                    tvMemoIcon.setBackground(iconBg);
                    memoBtn2.addView(tvMemoIcon);

                    // 메모 아이템 키 (5개 칸)
                    final String[] itemKeys = new String[5];
                    for (int mi2 = 0; mi2 < 5; mi2++)
                        itemKeys[mi2] = memoKey + "_item" + mi2;
                    final android.widget.EditText[] etItems = new android.widget.EditText[5];

                    memoBtn2.setOnClickListener(bv -> {
                        if (isSelectMode) return; // 선택모드에서는 메모 실행 안 함
                        // ── 팝업 레이아웃 ─────────────────────────────
                        LinearLayout popLayout = new LinearLayout(this);
                        popLayout.setOrientation(LinearLayout.VERTICAL);
                        popLayout.setPadding(0, 0, 0, 0);

                        // ① 보라 그라데이션 타이틀 카드
                        LinearLayout titleCard = new LinearLayout(this);
                        titleCard.setOrientation(LinearLayout.HORIZONTAL);
                        titleCard.setGravity(Gravity.CENTER_VERTICAL);
                        titleCard.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
                        android.graphics.drawable.GradientDrawable titleBg =
                                new android.graphics.drawable.GradientDrawable();
                        titleBg.setColor(Color.parseColor("#7C6FE0"));
                        titleCard.setBackground(titleBg);
                        TextView tvTitleIcon = new TextView(this);
                        tvTitleIcon.setText("📝");
                        tvTitleIcon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
                        tvTitleIcon.setPadding(0, 0, dpToPx(8), 0);
                        titleCard.addView(tvTitleIcon);
                        TextView tvTitleText = new TextView(this);
                        tvTitleText.setText("거래 메모");
                        tvTitleText.setTextColor(Color.WHITE);
                        tvTitleText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
                        tvTitleText.setTypeface(null, android.graphics.Typeface.BOLD);
                        titleCard.addView(tvTitleText);
                        popLayout.addView(titleCard);

                        // ② 문자 내용 (잔액보기 화면과 동일 스타일, 잔액 제외)
                        LinearLayout msgCard = new LinearLayout(this);
                        msgCard.setOrientation(LinearLayout.VERTICAL);
                        msgCard.setBackgroundColor(Color.parseColor("#F8F6FF"));
                        msgCard.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
                        for (String ln : lines) {
                            if (ln.contains("잔액")) continue;
                            TextView tvLn = new TextView(this);
                            // 출금/입금 색상 처리 (잔액보기와 동일)
                            if (ln.contains("출금") || ln.contains("입금") || ln.contains("선입금")) {
                                android.text.SpannableString sp2 = new android.text.SpannableString(ln);
                                String cw = ln.contains("출금") ? "출금" : ln.contains("선입금") ? "선입금" : "입금";
                                int wc2 = ln.contains("출금") ? Color.parseColor("#E74C3C") : Color.parseColor("#2980B9");
                                int wi = ln.indexOf(cw);
                                if (wi >= 0) {
                                    sp2.setSpan(new android.text.style.ForegroundColorSpan(wc2),
                                            wi, wi+cw.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    sp2.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                            wi, wi+cw.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                }
                                tvLn.setText(sp2);
                                tvLn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
                            } else {
                                tvLn.setText(ln);
                                tvLn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
                                tvLn.setTextColor(Color.parseColor("#555555"));
                            }
                            tvLn.setTextColor(Color.parseColor("#333333"));
                            tvLn.setPadding(0, dpToPx(1), 0, dpToPx(1));
                            msgCard.addView(tvLn);
                        }
                        popLayout.addView(msgCard);

                        // 구분선
                        View divPop = new View(this);
                        divPop.setBackgroundColor(Color.parseColor("#E0DCF5"));
                        divPop.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
                        popLayout.addView(divPop);

                        // ③ 입력 영역 (항목 칸 크게)
                        LinearLayout inputArea = new LinearLayout(this);
                        inputArea.setOrientation(LinearLayout.VERTICAL);
                        inputArea.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(4));

                        // memoCache에서 최신 항목 읽기 (Drive 공유 데이터 우선)
                        String[] driveItems = memoCache.get(memoTimestamp);
                        android.content.SharedPreferences prefs2 =
                                getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                        android.view.inputmethod.InputMethodManager immMemo =
                                (android.view.inputmethod.InputMethodManager)
                                        getSystemService(android.content.Context.INPUT_METHOD_SERVICE);

                        String[] hints = {"항목 1", "항목 2", "항목 3", "항목 4", "항목 5"};
                        android.widget.Button[] btnNextArr = new android.widget.Button[5];
                        int[] btnState = new int[5]; // 0=입력, 1=확인중, 2=완료

                        for (int mi2 = 0; mi2 < 5; mi2++) {
                            final int finalMi = mi2;

                            LinearLayout itemRow = new LinearLayout(this);
                            itemRow.setOrientation(LinearLayout.HORIZONTAL);
                            itemRow.setGravity(Gravity.CENTER_VERTICAL);
                            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT);
                            rowLp.setMargins(0, 0, 0, dpToPx(6));
                            itemRow.setLayoutParams(rowLp);

                            // ── EditText + X버튼을 RelativeLayout으로 묶기 ──
                            RelativeLayout etWrapper = new RelativeLayout(this);
                            LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                                    0, dpToPx(54), 1f);
                            etWrapper.setLayoutParams(wrapLp);

                            android.widget.EditText et2 = new android.widget.EditText(this);
                            setBlackCursor(et2);
                            et2.setHint(hints[mi2]);
                            et2.setHintTextColor(Color.parseColor("#CCCCCC"));
                            String etVal = (driveItems!=null && mi2<driveItems.length)
                                    ? (driveItems[mi2] != null ? driveItems[mi2] : "") : "";
                            et2.setText(etVal);
                            et2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 22);
                            et2.setTextColor(Color.parseColor("#222222"));
                            et2.setSingleLine(true);
                            et2.setGravity(Gravity.CENTER_VERTICAL);
                            et2.setCursorVisible(true);
                            // 커서 색상: 보라색으로 눈에 잘 보이게
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                android.graphics.drawable.GradientDrawable cursorDrawable =
                                        new android.graphics.drawable.GradientDrawable();
                                cursorDrawable.setColor(Color.parseColor("#222222"));
                                cursorDrawable.setSize(dpToPx(2), (int)(et2.getTextSize()));
                                et2.setTextCursorDrawable(cursorDrawable);
                            }
                            et2.setBackgroundTintList(
                                    android.content.res.ColorStateList.valueOf(
                                            Color.parseColor("#9B8EC4")));
                            // 오른쪽 패딩 넉넉히 줘서 X버튼과 겹치지 않게
                            et2.setPadding(dpToPx(4), 0, dpToPx(46), 0);
                            RelativeLayout.LayoutParams etRlp = new RelativeLayout.LayoutParams(
                                    RelativeLayout.LayoutParams.MATCH_PARENT,
                                    RelativeLayout.LayoutParams.MATCH_PARENT);
                            et2.setLayoutParams(etRlp);
                            etItems[mi2] = et2;
                            etWrapper.addView(et2);

                            // 삭제 버튼 (오른쪽 끝, 텍스트 있을 때만 표시)
                            TextView btnX = new TextView(this);
                            btnX.setText("삭제");
                            btnX.setTextColor(Color.WHITE);
                            btnX.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
                            btnX.setTypeface(null, android.graphics.Typeface.BOLD);
                            btnX.setGravity(Gravity.CENTER);
                            android.graphics.drawable.GradientDrawable xBg =
                                    new android.graphics.drawable.GradientDrawable();
                            xBg.setColor(Color.parseColor("#E74C3C"));
                            xBg.setCornerRadius(dpToPx(4));
                            btnX.setBackground(xBg);
                            btnX.setVisibility(etVal.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                            btnX.setPadding(dpToPx(5), dpToPx(3), dpToPx(5), dpToPx(3));
                            RelativeLayout.LayoutParams xRlp = new RelativeLayout.LayoutParams(
                                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                                    RelativeLayout.LayoutParams.WRAP_CONTENT);
                            xRlp.addRule(RelativeLayout.ALIGN_PARENT_END);
                            xRlp.addRule(RelativeLayout.CENTER_VERTICAL);
                            xRlp.setMargins(0, 0, dpToPx(4), 0);
                            btnX.setLayoutParams(xRlp);
                            btnX.setOnClickListener(xv -> {
                                et2.setText("");
                                btnX.setVisibility(android.view.View.GONE);
                                et2.clearFocus();
                                if (immMemo != null)
                                    immMemo.hideSoftInputFromWindow(et2.getWindowToken(), 0);
                                android.graphics.drawable.GradientDrawable resetBg =
                                        new android.graphics.drawable.GradientDrawable();
                                resetBg.setCornerRadius(dpToPx(6));
                                resetBg.setColor(Color.parseColor("#BBBBBB"));
                                btnState[finalMi] = 0;
                                if (btnNextArr[finalMi] != null) {
                                    btnNextArr[finalMi].setText("입력");
                                    btnNextArr[finalMi].setBackground(resetBg);
                                }
                            });
                            etWrapper.addView(btnX);

                            // 텍스트 변화 → X버튼 표시/숨김
                            et2.addTextChangedListener(new android.text.TextWatcher() {
                                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                                    btnX.setVisibility(s.length() > 0
                                            ? android.view.View.VISIBLE : android.view.View.GONE);
                                }
                                @Override public void afterTextChanged(android.text.Editable e) {}
                            });

                            // 확인 버튼
                            android.widget.Button btnNext = new android.widget.Button(this);
                            btnNextArr[mi2] = btnNext;

                            android.graphics.drawable.GradientDrawable nextBg =
                                    new android.graphics.drawable.GradientDrawable();
                            nextBg.setCornerRadius(dpToPx(6));
                            if (!etVal.isEmpty()) {
                                btnState[mi2] = 2;
                                btnNext.setText("완료");
                                nextBg.setColor(Color.parseColor("#27AE60"));
                            } else {
                                btnState[mi2] = 0;
                                btnNext.setText("입력");
                                nextBg.setColor(Color.parseColor("#BBBBBB"));
                            }
                            btnNext.setTextColor(Color.WHITE);
                            btnNext.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
                            btnNext.setTypeface(null, android.graphics.Typeface.BOLD);
                            btnNext.setBackground(nextBg);
                            LinearLayout.LayoutParams btnLlp = new LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(42));
                            btnLlp.setMargins(dpToPx(6), 0, 0, dpToPx(8));
                            btnNext.setMinWidth(dpToPx(56));
                            btnNext.setPadding(dpToPx(10), 0, dpToPx(10), 0);
                            btnNext.setLayoutParams(btnLlp);

                            // 포커스 → 오렌지 확인
                            et2.setOnFocusChangeListener((fv, hasFocus) -> {
                                android.graphics.drawable.GradientDrawable bg2 =
                                        new android.graphics.drawable.GradientDrawable();
                                bg2.setCornerRadius(dpToPx(6));
                                if (hasFocus) {
                                    btnState[finalMi] = 1;
                                    btnNext.setText("확인");
                                    bg2.setColor(Color.parseColor("#E67E22"));
                                } else {
                                    if (!et2.getText().toString().trim().isEmpty()) {
                                        btnState[finalMi] = 2;
                                        btnNext.setText("완료");
                                        bg2.setColor(Color.parseColor("#27AE60"));
                                    } else {
                                        btnState[finalMi] = 0;
                                        btnNext.setText("입력");
                                        bg2.setColor(Color.parseColor("#BBBBBB"));
                                    }
                                }
                                btnNext.setBackground(bg2);
                            });

                            // 확인 버튼 클릭 → 완료 + 키보드 내림 (다음 자동이동 없음)
                            btnNext.setOnClickListener(nbv -> {
                                // 입력 상태면 → 포커스+키보드 올리기
                                if (btnState[finalMi] == 0) {
                                    et2.requestFocus();
                                    et2.setSelection(et2.getText().length());
                                    if (immMemo != null)
                                        immMemo.showSoftInput(et2,
                                                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                                    return;
                                }
                                // 확인 상태면 → 완료 + 키보드 내림
                                android.graphics.drawable.GradientDrawable bg3 =
                                        new android.graphics.drawable.GradientDrawable();
                                bg3.setCornerRadius(dpToPx(6));
                                btnState[finalMi] = 2;
                                btnNext.setText("완료");
                                bg3.setColor(Color.parseColor("#27AE60"));
                                btnNext.setBackground(bg3);
                                et2.clearFocus();
                                if (immMemo != null)
                                    immMemo.hideSoftInputFromWindow(et2.getWindowToken(), 0);
                            });

                            itemRow.addView(etWrapper);
                            itemRow.addView(btnNext);
                            inputArea.addView(itemRow);
                        }

                        // 팝업 스크롤 시 키보드 내림
                        inputArea.setOnTouchListener((v2, ev) -> {
                            if (ev.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                                if (immMemo != null)
                                    immMemo.hideSoftInputFromWindow(
                                            inputArea.getWindowToken(), 0);
                                if (getCurrentFocus() != null) getCurrentFocus().clearFocus();
                            }
                            return false;
                        });
                        popLayout.addView(inputArea);

                        // 커스텀 버튼행 (A 디자인)
                        LinearLayout btnRow = new LinearLayout(this);
                        btnRow.setOrientation(LinearLayout.HORIZONTAL);
                        btnRow.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(10));
                        btnRow.setGravity(Gravity.CENTER_VERTICAL);

                        android.widget.Button btnCancel = new android.widget.Button(this);
                        btnCancel.setText("취소");
                        btnCancel.setTextColor(Color.parseColor("#888888"));
                        btnCancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
                        btnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
                        android.graphics.drawable.GradientDrawable cancelBg =
                                new android.graphics.drawable.GradientDrawable();
                        cancelBg.setColor(Color.parseColor("#F5F5F5"));
                        cancelBg.setCornerRadius(dpToPx(8));
                        btnCancel.setBackground(cancelBg);
                        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dpToPx(42), 1f);
                        cancelLp.setMargins(0, 0, dpToPx(6), 0);
                        btnCancel.setLayoutParams(cancelLp);
                        btnRow.addView(btnCancel);

                        final android.widget.Button btnDel = new android.widget.Button(this);
                        btnDel.setText("메모삭제");
                        btnDel.setTextColor(Color.parseColor("#E24B4A"));
                        btnDel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
                        btnDel.setTypeface(null, android.graphics.Typeface.BOLD);
                        android.graphics.drawable.GradientDrawable delBg =
                                new android.graphics.drawable.GradientDrawable();
                        delBg.setColor(Color.parseColor("#FEF2F2"));
                        delBg.setStroke(dpToPx(1), Color.parseColor("#F7C1C1"));
                        delBg.setCornerRadius(dpToPx(8));
                        btnDel.setBackground(delBg);
                        btnDel.setVisibility(memoHolder[0].isEmpty()
                                ? android.view.View.GONE : android.view.View.VISIBLE);
                        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, dpToPx(42), 1f);
                        delLp.setMargins(0, 0, dpToPx(6), 0);
                        btnDel.setLayoutParams(delLp);
                        btnRow.addView(btnDel);

                        android.widget.Button btnSave = new android.widget.Button(this);
                        btnSave.setText("저장");
                        btnSave.setTextColor(Color.WHITE);
                        btnSave.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
                        btnSave.setTypeface(null, android.graphics.Typeface.BOLD);
                        android.graphics.drawable.GradientDrawable saveBg =
                                new android.graphics.drawable.GradientDrawable();
                        saveBg.setColor(Color.parseColor("#7C6FE0"));
                        saveBg.setCornerRadius(dpToPx(8));
                        btnSave.setBackground(saveBg);
                        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, dpToPx(42), 1f));
                        btnRow.addView(btnSave);

                        popLayout.addView(btnRow);

                        android.app.AlertDialog.Builder builder =
                                new android.app.AlertDialog.Builder(this,
                                        android.R.style.Theme_Material_Light_Dialog_Alert);
                        builder.setView(popLayout);
                        final android.app.AlertDialog[] dlgHolder = {null};

                        builder.setPositiveButton("저장_hidden", (dlg, w) -> {
                            // 아이템 저장
                            java.util.List<String> itemList = new java.util.ArrayList<>();
                            for (int mi2 = 0; mi2 < 5; mi2++) {
                                String val = etItems[mi2].getText().toString().trim();
                                if (!val.isEmpty()) itemList.add(val);
                            }
                            // 메모 합산 (콤마 구분)
                            String combined = itemList.isEmpty() ? ""
                                    : "(" + android.text.TextUtils.join(",", itemList) + ")";
                            memoHolder[0] = combined;

                            // memoCache 업데이트 + memo.txt Drive 저장
                            String[] cacheItems = new String[5];
                            for (int ci=0;ci<5;ci++) cacheItems[ci]=etItems[ci].getText().toString().trim();
                            if (combined.isEmpty()) memoCache.remove(memoTimestamp);
                            else memoCache.put(memoTimestamp, cacheItems);
                            saveMemoFile();

                            // 가게명 TextView 업데이트 (원본 가게명 보존)
                            if (tvShopRef[0] != null) {
                                // 원본 가게명: prefs에 저장된 것 우선, 없으면 현재 텍스트에서 메모부분 제거
                                String shopOrig = ""  /* _shop 제거 */;
                                if (shopOrig.isEmpty()) {
                                    // 최초 저장: 현재 텍스트가 곧 원본
                                    shopOrig = tvShopRef[0].getText().toString();
                                    // 이전에 메모가 붙어있으면 제거 (괄호로 시작하는 마지막 토큰)
                                    if (!memoHolder[0].isEmpty() && shopOrig.endsWith(memoHolder[0])) {
                                        shopOrig = shopOrig.substring(0, shopOrig.length() - memoHolder[0].length()).trim();
                                    }
                                    // 원본 저장
                                }
                                if (!combined.isEmpty()) {
                                    tvShopRef[0].setText(shopOrig + combined);
                                } else {
                                    tvShopRef[0].setText(shopOrig);
                                }
                            }

                            // Drive 저장 (가게명 줄만 수정)
                            // shopOrigHolder에서 가져오되 없으면 화면 텍스트에서 괄호 제거
                            String sOrig = shopOrigHolder[0].isEmpty()
                                    ? (tvShopRef[0]!=null
                                    ? tvShopRef[0].getText().toString().replaceAll("\\(.*\\)$","").trim()
                                    : "")
                                    : shopOrigHolder[0].replaceAll("\\(.*\\)$","").trim();
                            if (!sOrig.isEmpty())
                                saveMemoToDrive(memoTimestamp, sOrig, combined);

                            // 뱃지 + 아이콘 업데이트
                            if (!combined.isEmpty()) {
                                tvMemoBadge.setText("메모");
                                tvMemoBadge.setTextColor(Color.parseColor("#7A5800"));
                                android.graphics.drawable.GradientDrawable bg2 =
                                        new android.graphics.drawable.GradientDrawable();
                                bg2.setColor(Color.parseColor("#FFF3CD"));
                                bg2.setCornerRadius(dpToPx(10));
                                tvMemoBadge.setBackground(bg2);
                                tvMemoBadge.setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2));
                                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT);
                                blp.setMargins(0, 0, dpToPx(4), 0);
                                tvMemoBadge.setLayoutParams(blp);
                                android.graphics.drawable.GradientDrawable ib2 =
                                        new android.graphics.drawable.GradientDrawable();
                                ib2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                                ib2.setColor(Color.parseColor("#FFF3CD"));
                                ib2.setStroke(dpToPx(1), Color.parseColor("#F0A500"));
                                tvMemoIcon.setBackground(ib2);
                            } else {
                                tvMemoBadge.setText("");
                                tvMemoBadge.setBackground(null);
                                android.graphics.drawable.GradientDrawable ib3 =
                                        new android.graphics.drawable.GradientDrawable();
                                ib3.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                                ib3.setColor(Color.parseColor("#F5F5F5"));
                                ib3.setStroke(dpToPx(1), Color.parseColor("#F0A500"));
                                tvMemoIcon.setBackground(ib3);
                            }
                        });
                        builder.setNegativeButton("취소_hidden", null);
                        if (!memoHolder[0].isEmpty()) {
                            builder.setNeutralButton("메모삭제_hidden", (dlg, w) -> {
                                android.content.SharedPreferences.Editor ed2 =
                                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
                                memoHolder[0] = "";
                                // memoCache에서 제거 + memo.txt 갱신
                                memoCache.remove(memoTimestamp);
                                saveMemoFile();
                                // Drive 삭제 (가게명만 원본으로 복원)
                                String sOrig2 = shopOrigHolder[0].isEmpty()
                                        ? (tvShopRef[0]!=null ? tvShopRef[0].getText().toString()
                                        .replaceAll("\\(.*\\)$","").trim() : "")
                                        : shopOrigHolder[0];
                                if (!sOrig2.isEmpty())
                                    saveMemoToDrive(memoTimestamp, sOrig2, "");
                                if (tvShopRef[0] != null) {
                                    // 원본 가게명 복원 (prefs에서)
                                    String shopOrig = ""  /* _shop 제거 */;
                                    if (!shopOrig.isEmpty()) {
                                        tvShopRef[0].setText(shopOrig);
                                    }
                                    // _shop 키도 삭제
                                }
                                tvMemoBadge.setText("");
                                tvMemoBadge.setBackground(null);
                                android.graphics.drawable.GradientDrawable ib4 =
                                        new android.graphics.drawable.GradientDrawable();
                                ib4.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                                ib4.setColor(Color.parseColor("#F5F5F5"));
                                ib4.setStroke(dpToPx(1), Color.parseColor("#F0A500"));
                                tvMemoIcon.setBackground(ib4);
                            });
                        }
                        dlgHolder[0] = builder.show();
                        // 기본 버튼 숨기기
                        try {
                            dlgHolder[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE).setVisibility(android.view.View.GONE);
                            dlgHolder[0].getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setVisibility(android.view.View.GONE);
                            dlgHolder[0].getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setVisibility(android.view.View.GONE);
                        } catch (Exception ignored) {}
                        // 커스텀 버튼 클릭 연결
                        btnCancel.setOnClickListener(bv2 -> dlgHolder[0].dismiss());
                        btnSave.setOnClickListener(bv2 -> {
                            // 입력값 있는지 먼저 확인
                            boolean hasAny = false;
                            for (int mi2 = 0; mi2 < 5; mi2++) {
                                if (!etItems[mi2].getText().toString().trim().isEmpty()) {
                                    hasAny = true;
                                    break;
                                }
                            }
                            if (!hasAny) {
                                new android.app.AlertDialog.Builder(this,
                                        android.R.style.Theme_Material_Light_Dialog_Alert)
                                        .setMessage("입력 내용이 없습니다.")
                                        .setPositiveButton("확인", (d2, w2) -> {
                                            if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                                        })
                                        .show();
                                return;
                            }
                            new android.app.AlertDialog.Builder(this,
                                    android.R.style.Theme_Material_Light_Dialog_Alert)
                                    .setMessage("저장하시겠습니까?")
                                    .setNegativeButton("취소", null)
                                    .setPositiveButton("저장", (d2, w2) ->
                                            dlgHolder[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick())
                                    .show();
                        });
                        btnDel.setOnClickListener(bv2 -> {
                            new android.app.AlertDialog.Builder(this,
                                    android.R.style.Theme_Material_Light_Dialog_Alert)
                                    .setMessage("메모를 삭제하시겠습니까?")
                                    .setNegativeButton("취소", null)
                                    .setPositiveButton("삭제", (d2, w2) ->
                                            dlgHolder[0].getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick())
                                    .show();
                        });
                        // 첫 번째 입력칸 포커스 + 키보드 자동 표시
                        if (etItems[0] != null) {
                            etItems[0].requestFocus();
                            android.view.inputmethod.InputMethodManager imm =
                                    (android.view.inputmethod.InputMethodManager)
                                            getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                            etItems[0].postDelayed(() -> {
                                if (imm != null) imm.showSoftInput(etItems[0],
                                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                            }, 150);
                        }
                    });
                    topRow.addView(memoBtn2);
                    card.addView(topRow);
                } else {
                    TextView tv = new TextView(this);
                    // 가게명 줄 감지: 계좌번호/날짜/잔액 아닌 줄 중 마지막 의미있는 줄
                    boolean isShopLine = !line.contains("351-****")
                            && !line.contains("잔액")
                            && !line.matches(".*\\d{4}-\\d{2}-\\d{2}.*")
                            && !line.matches(".*월.*일.*")
                            && !line.contains("농협");
                    // 가게명 줄이면 메모 내용 합산 표시
                    String displayLine = line;
                    if (isShopLine) {
                        tvShopRef[0] = tv;
                        // 원본 가게명 prefs에 없으면 저장
                        String savedShop = ""  /* _shop 제거 */;
                        if (savedShop.isEmpty()) {
                            // 항상 괄호 이전까지만 원본으로 저장 (다른 사용자가 이미 메모를 넣었을 수 있음)
                            String orig = line.replaceAll("\\(.*\\)$", "").trim();
                        }
                        if (!memoHolder[0].isEmpty()) {
                            String orig2 = shopOrigHolder[0].isEmpty()
                                    ? line.replaceAll("\\(.*\\)$","").trim()
                                    : shopOrigHolder[0];
                            displayLine = orig2 + memoHolder[0];
                        }
                    }
                    tv.setText(displayLine);
                    tv.setTextColor(Color.parseColor("#222222"));
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setPadding(0, 3, 0, 3);
                    card.addView(tv);
                }
            }

            View divider = new View(this);
            divider.setBackgroundColor(
                    Color.parseColor(isSelected ? "#9B8EC4" : "#DDD8F0"));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            dp.setMargins(0, 12, 0, 4);
            divider.setLayoutParams(dp);
            card.addView(divider);

            // 월별 점프용 날짜 태그 설정
            java.util.regex.Matcher tagM = java.util.regex.Pattern
                    .compile("(\\d{4}-\\d{2}-\\d{2})").matcher(block);
            if (tagM.find()) wrapper.setTag(tagM.group(1));

            wrapper.addView(card);



            // ── 선택 원: FrameLayout 위에 오버레이 ───────────
            if (isSelectMode) {
                int circleSize = dpToPx(30);
                android.widget.FrameLayout.LayoutParams clp =
                        new android.widget.FrameLayout.LayoutParams(circleSize, circleSize);
                clp.gravity = Gravity.TOP | Gravity.END;
                clp.setMargins(0, dpToPx(8), dpToPx(8), 0);

                // 그림자용 외부 원 (약간 더 큰 흰 원)
                android.widget.FrameLayout circleWrapper = new android.widget.FrameLayout(this);
                int outerSize = dpToPx(34);
                android.widget.FrameLayout.LayoutParams owlp =
                        new android.widget.FrameLayout.LayoutParams(outerSize, outerSize);
                owlp.gravity = Gravity.TOP | Gravity.END;
                owlp.setMargins(0, dpToPx(6), dpToPx(6), 0);
                circleWrapper.setLayoutParams(owlp);
                circleWrapper.setElevation(dpToPx(6));  // 그림자 높이

                // 실제 원
                TextView tvCircle = new TextView(this);
                tvCircle.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        outerSize, outerSize));
                tvCircle.setGravity(Gravity.CENTER);
                tvCircle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
                tvCircle.setTypeface(null, Typeface.BOLD);

                android.graphics.drawable.GradientDrawable circleD =
                        new android.graphics.drawable.GradientDrawable();
                circleD.setShape(android.graphics.drawable.GradientDrawable.OVAL);

                if (isSelected) {
                    // 선택됨: 진한 보라 + 흰 ✓
                    circleD.setColor(Color.parseColor("#5B4A8A"));
                    circleD.setStroke(0, Color.TRANSPARENT);
                    tvCircle.setBackground(circleD);
                    tvCircle.setText("✓");
                    tvCircle.setTextColor(Color.WHITE);
                } else {
                    // 미선택: 흰 바탕 + 진한 회색 테두리
                    circleD.setColor(Color.WHITE);
                    circleD.setStroke(dpToPx(2), Color.parseColor("#777777"));
                    tvCircle.setBackground(circleD);
                    tvCircle.setText("");
                    tvCircle.setTextColor(Color.TRANSPARENT);
                }

                circleWrapper.addView(tvCircle);
                wrapper.addView(circleWrapper);
            }

            // ── 롱클릭 → 선택모드 + 즉시 선택 ─────────────
            card.setOnLongClickListener(v -> {
                isSelectMode = true;
                if (!selectedIdx.contains(blockIdx)) selectedIdx.add(blockIdx);
                showSelectActionBar();
                renderLatest(displayedCount);
                return true;
            });

            // ── 클릭 → 선택 토글 ─────────────────────────
            card.setOnClickListener(v -> {
                if (!isSelectMode) return;
                if (selectedIdx.contains(blockIdx)) {
                    selectedIdx.remove(Integer.valueOf(blockIdx));
                } else {
                    selectedIdx.add(blockIdx);
                }
                showSelectActionBar();
                renderLatest(displayedCount);
            });

            msgContainer.addView(wrapper);
        }

        if (!hasMsg) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("문자 내역이 없습니다");
            tvEmpty.setTextColor(Color.parseColor("#888888"));
            tvEmpty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14);
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(0, 40, 0, 0);
            msgContainer.addView(tvEmpty);
        }
    }
    // ── 잔액 카드 선택 색상 업데이트 ──────────────────────
    private void updateBalCardColors(LinearLayout[] cards, String[][] balInfo, int activeIdx) {
        String[] pastelBgs = {"#EBF4FF","#EAFAF1","#FEF9E7","#F5EEF8"};
        for (int i = 0; i < cards.length; i++) {
            if (cards[i] == null) continue;
            String pastel = (balInfo[i].length > 3) ? balInfo[i][3] : pastelBgs[i];
            if (i == activeIdx) {
                // 선택됨: 진한 컬러, shadow drawable
                cards[i].setBackground(
                        makeShadowCardDrawable(balInfo[i][2], 16, 5));
                cards[i].setLayerType(
                        android.view.View.LAYER_TYPE_SOFTWARE, null);
                for (int j = 0; j < cards[i].getChildCount(); j++) {
                    View child = cards[i].getChildAt(j);
                    if (child instanceof TextView)
                        ((TextView) child).setTextColor(Color.WHITE);
                }
            } else {
                // 비선택: 파스텔, shadow drawable
                cards[i].setBackground(
                        makeShadowCardDrawable(pastel, 16, 5));
                cards[i].setLayerType(
                        android.view.View.LAYER_TYPE_SOFTWARE, null);
                for (int j = 0; j < cards[i].getChildCount(); j++) {
                    View child = cards[i].getChildAt(j);
                    if (child instanceof TextView) {
                        if (j == 0) ((TextView) child).setTextColor(Color.parseColor(balInfo[i][2]));
                        else        ((TextView) child).setTextColor(Color.parseColor("#1A1A2E"));
                    }
                }
            }
        }
    }

    private String filterKeyToName(String key) {
        switch (key) {
            case "5510-13": return "운영비";
            case "5510-83": return "부식비";
            case "5510-53": return "냉난방비";
            case "5510-23": return "회비";
            default: return "전체";
        }
    }

    // ── 선택 모드 ──────────────────────────────────────────
    private void enterSelectMode() {
        isSelectMode = true;
        renderLatest(displayedCount);
        showSelectActionBar();
    }

    private void exitSelectMode() {
        isSelectMode = false;
        selectedIdx.clear();
        pendingSelectIdx.clear();
        if (selectActionBar != null) selectActionBar.setVisibility(View.GONE);
        renderLatest(displayedCount);
    }

    private void toggleSelect(int idx, LinearLayout card) {
        if (selectedIdx.contains(idx)) {
            selectedIdx.remove(Integer.valueOf(idx));
        } else {
            selectedIdx.add(idx);
        }
        showSelectActionBar();
    }

    private void updateSelectUI() {}

    private void showSelectActionBar() {
        if (selectActionBar == null) return;
        selectActionBar.setVisibility(View.VISIBLE);
        if (tvSelectCount != null) {
            tvSelectCount.setText(selectedIdx.size() + "개 선택");
        }
    }

    /**
     * 등록: 선택된 블록 중 MEAT_SLOTS 키워드(출금) 포함 것만 prepaid.txt에 저장.
     * 기존 prepaid.txt 내용과 합산 후 날짜+시간 오름차순 정렬하여 저장.
     */
    private void registerSelected() {
        if (cachedBlocks == null || selectedIdx.isEmpty()) return;

        // 등록 가능한 블록만 필터 (MEAT_SLOTS 키워드 + 출금 포함)
        List<String> toRegister = new ArrayList<>();
        for (int idx : selectedIdx) {
            if (idx < 0 || idx >= cachedBlocks.size()) continue;
            String block = cachedBlocks.get(idx);
            boolean matchSlot = false;
            for (String[] slot : MEAT_SLOTS) {
                if (!slot[0].isEmpty() && block.contains(slot[0])) {
                    matchSlot = true; break;
                }
            }
            if (matchSlot && block.contains("출금")) {
                toRegister.add(block);
            }
        }

        if (toRegister.isEmpty()) {
            Toast.makeText(this, "등록 가능한 항목이 없습니다.\n선결제 가게의 출금 내역만 등록할 수 있습니다.", Toast.LENGTH_LONG).show();
            return;
        }

        // 이미 등록된 블록 체크 (prepaid.txt의 타임스탬프와 비교)
        new Thread(() -> {
            try {
                DriveReadHelper reader = new DriveReadHelper(PinActivity.this);
                final String[] existingContent = {""};
                final Object lock = new Object();
                final boolean[] done = {false};
                reader.readFile("prepaid.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String content) {
                        synchronized (lock) { existingContent[0] = content; done[0] = true; lock.notifyAll(); }
                    }
                    @Override public void onFailure(String error) {
                        synchronized (lock) { existingContent[0] = ""; done[0] = true; lock.notifyAll(); }
                    }
                });
                synchronized (lock) { while (!done[0]) lock.wait(5000); }

                // 기존 타임스탬프 수집
                java.util.Set<String> existingTs = new java.util.HashSet<>();
                for (String b : existingContent[0].split("-----------------------------------\r?\n")) {
                    String ts = extractTimestamp(b.trim());
                    if (!ts.isEmpty()) existingTs.add(ts);
                }

                // 이미 등록된 항목 체크
                List<String> alreadyRegistered = new ArrayList<>();
                List<String> newItems = new ArrayList<>();
                for (String block : toRegister) {
                    String ts = extractTimestamp(block);
                    if (!ts.isEmpty() && existingTs.contains(ts)) {
                        alreadyRegistered.add(block);
                    } else {
                        newItems.add(block);
                    }
                }

                runOnUiThread(() -> {
                    // 이미 등록된 항목 있으면 Toast
                    if (!alreadyRegistered.isEmpty()) {
                        Toast.makeText(this, "이미 등록되어있습니다.", Toast.LENGTH_SHORT).show();
                        if (newItems.isEmpty()) return; // 전부 중복이면 종료
                    }
                    // 등록 가능한 항목 있으면 확인 다이얼로그
                    android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                            .setMessage(newItems.size() + "개 항목을 등록하시겠습니까?")
                            .setPositiveButton("확인", (d, w) ->
                                    doRegister(newItems, existingContent[0]))
                            .setNegativeButton("취소", null)
                            .create();
                    dlg.show();
                    dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                            .setTextColor(Color.parseColor("#27AE60"));
                    dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                            .setTextColor(Color.parseColor("#888888"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "확인 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 실제 등록 저장 처리 */
    private void doRegister(List<String> toRegister, String existingRaw) {
        // 신규 블록 변환: "출금 XXX원" → "선입금 XXX원", "잔액 YYY원" → "잔액 XXX원"
        List<String> newConverted = new ArrayList<>();
        for (String block : toRegister) {
            String[] lines = block.split("\\r?\\n");
            String prepaidAmt = "";
            for (String line : lines) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("출금\\s*([\\d,]+)원").matcher(line.trim());
                if (m.find()) { prepaidAmt = m.group(1); break; }
            }
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.contains("출금") && !t.contains("잔액")) {
                    t = t.replaceAll("출금\\s*([\\d,]+원)", "선입금 $1");
                } else if (t.contains("잔액") && !prepaidAmt.isEmpty()) {
                    t = "잔액 " + prepaidAmt + "원";
                }
                sb.append(t).append("\n");
            }
            newConverted.add(sb.toString().trim());
        }

        final int count = newConverted.size();

        new Thread(() -> {
            try {
                // 기존 블록 파싱
                List<String> allBlocks = new ArrayList<>();
                if (!existingRaw.trim().isEmpty()) {
                    for (String b : existingRaw.split("-----------------------------------\r?\n")) {
                        if (!b.trim().isEmpty()) allBlocks.add(b.trim());
                    }
                }
                for (String nb : newConverted) allBlocks.add(nb);

                // 타임스탬프 오름차순 정렬
                allBlocks.sort((a, b2) -> extractTimestamp(a).compareTo(extractTimestamp(b2)));

                StringBuilder fileSb = new StringBuilder();
                for (String b : allBlocks) {
                    fileSb.append(b).append("\n").append("-----------------------------------\n");
                }

                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                up.uploadFileSync(fileSb.toString(), "prepaid.txt");
                DriveReadHelper.invalidateCache("prepaid.txt");

                runOnUiThread(() -> {
                    Toast.makeText(this, count + "개 항목이 선결제 내역에 등록되었습니다.", Toast.LENGTH_SHORT).show();
                    exitSelectMode();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "등록 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /** 블록에서 타임스탬프("yyyy-MM-dd HH:mm:ss") 추출 */
    private String extractTimestamp(String block) {
        for (String line : block.split("\\r?\\n")) {
            String t = line.trim();
            if (t.matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")) return t;
        }
        return "";
    }

    /** 블록의 "잔액 XXX원" 줄을 합산 금액으로 교체한 새 블록 반환 */
    private String injectTotalBalance(String block, long total) {
        String totalStr = String.format("잔액 %,d원", total);
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("잔액")) {
                sb.append(totalStr).append("\n");
            } else {
                sb.append(t).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 남은 블록 전체에서 계좌별 최신 잔액을 파싱해 balance.txt 갱신 */
    private void updateBalanceTxtFromBlocks(List<String> blocks) {
        new Thread(() -> {
            try {
                // 계좌별 최신 잔액 Map (나중에 나온 블록이 최신)
                java.util.Map<String, String[]> latestMap = new java.util.LinkedHashMap<>();
                String[][] accountInfo = {
                        {"5510-13", "운영비"},
                        {"5510-83", "부식비"},
                        {"5510-53", "냉난방비"},
                        {"5510-23", "회비"}
                };
                for (String block : blocks) {
                    for (String[] info : accountInfo) {
                        if (block.contains(info[0])) {
                            java.util.regex.Matcher bm = java.util.regex.Pattern
                                    .compile("잔액\\s*([\\d,]+원)").matcher(block);
                            if (bm.find()) {
                                latestMap.put(info[0], new String[]{info[0], info[1], bm.group(1)});
                            }
                        }
                    }
                }
                if (latestMap.isEmpty()) return;
                String nowTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(new java.util.Date());
                StringBuilder sb = new StringBuilder();
                for (String[] row : latestMap.values()) {
                    sb.append(row[0]).append("|").append(row[1]).append("|").append(row[2]).append("|").append(nowTime).append("\n");
                }
                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                up.uploadFileSync(sb.toString().trim(), "balance.txt");
                android.util.Log.d("DELETE_DEBUG", "balance.txt 갱신 완료");
                // stats.txt 도 갱신
                updateStatsTxt(blocks, up);
            } catch (Exception e) {
                android.util.Log.e("DELETE_DEBUG", "balance.txt 갱신 실패: " + e.getMessage());
            }
        }).start();
    }

    /** sms_raw 블록 전체에서 stats.txt 재생성 후 Drive 업로드 */
    private void updateStatsTxt(List<String> blocks, DriveUploadHelper up) {
        final String[] acctKeys2 = {"5510-13","5510-83","5510-53","5510-23"};
        StringBuilder sb2 = new StringBuilder();
        for (String block : blocks) {
            for (String key : acctKeys2) {
                if (!block.contains(key)) continue;
                java.util.regex.Matcher dm = java.util.regex.Pattern
                        .compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(block);
                if (!dm.find()) continue;
                int yr  = Integer.parseInt(dm.group(1));
                int mo  = Integer.parseInt(dm.group(2));
                int day = Integer.parseInt(dm.group(3));
                int wk  = Math.min((day - 1) / 7, 4);
                java.util.regex.Matcher am = java.util.regex.Pattern
                        .compile("([입출]금)\\s*([\\d,]+)원").matcher(block);
                if (!am.find()) continue;
                int amt  = Integer.parseInt(am.group(2).replace(",",""));
                int in2  = am.group(1).equals("입금") ? amt : 0;
                int out2 = am.group(1).equals("출금") ? amt : 0;
                sb2.append(key).append("|")
                        .append(yr).append("|")
                        .append(mo).append("|")
                        .append(wk).append("|")
                        .append(in2).append("|")
                        .append(out2).append("\n");
                break;
            }
        }
        try {
            up.uploadFileSync(sb2.toString().trim(), "stats.txt");
            android.util.Log.d("STATS", "stats.txt 갱신 완료 " + blocks.size() + "건");
        } catch (Exception e) {
            android.util.Log.e("STATS", "stats.txt 갱신 실패: " + e.getMessage());
        }
    }

    private void deleteSelected() {
        if (!isOwner) {
            android.app.AlertDialog dlgNo =
                    new android.app.AlertDialog.Builder(this,
                            android.R.style.Theme_Material_Light_Dialog_Alert)
                            .setTitle("삭제 불가")
                            .setMessage("삭제 권한이 없습니다.\n관리자에게 문의하세요.")
                            .setPositiveButton("확인", null)
                            .create();
            dlgNo.show();
            dlgNo.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(Color.parseColor("#5B4A8A"));
            return;
        }
        if (cachedBlocks == null || selectedIdx.isEmpty()) return;
        android.app.AlertDialog dlg =
                new android.app.AlertDialog.Builder(this,
                        android.R.style.Theme_Material_Light_Dialog_Alert)
                        .setTitle("삭제 확인")
                        .setMessage(selectedIdx.size() + "개의 문자를 삭제하시겠습니까?")
                        .setPositiveButton("삭제", (d, w) -> doDelete())
                        .setNegativeButton("취소", null)
                        .create();
        dlg.show();
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(Color.parseColor("#E74C3C"));  // 삭제 = 빨간색
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(Color.parseColor("#5B4A8A"));  // 취소 = 보라색
    }

    private void doDelete() {
        isDeleting = true;
        android.util.Log.d("DELETE_DEBUG", "삭제 시작 - 전체블록: " + cachedBlocks.size() + " / 선택인덱스: " + selectedIdx.toString());
        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < cachedBlocks.size(); i++) {
            if (!selectedIdx.contains(i)) {
                remaining.add(cachedBlocks.get(i));
            } else {
                android.util.Log.d("DELETE_DEBUG", "삭제 블록[" + i + "]: " + cachedBlocks.get(i).substring(0, Math.min(50, cachedBlocks.get(i).length())));
            }
        }
        android.util.Log.d("DELETE_DEBUG", "삭제 후 남은 블록: " + remaining.size());
        // ── 연도별 + Test.txt로 분리해서 각 파일에 저장 ──
        java.util.Map<String, StringBuilder> yearMap = new java.util.LinkedHashMap<>();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int curYear  = cal.get(java.util.Calendar.YEAR);
        int prevYear = curYear - 1;
        String curFile  = SmsReceiver.getSmsRawFile(curYear);
        String prevFile = SmsReceiver.getSmsRawFile(prevYear);
        yearMap.put(curFile,  new StringBuilder());
        yearMap.put(prevFile, new StringBuilder());
        yearMap.put("Test.txt", new StringBuilder()); // 테스트 블록용

        for (String b : remaining) {
            if (b.contains("[TEST]")) {
                // [TEST] 블록은 Test.txt에 저장
                yearMap.get("Test.txt").append(b).append("-----------------------------------\n");
                continue;
            }
            // 블록 첫 줄에서 연도 추출 (yyyy-MM-dd HH:mm:ss)
            String blockYear = String.valueOf(curYear); // 기본값: 현재 연도
            java.util.regex.Matcher ym = java.util.regex.Pattern
                    .compile("(\\d{4})-\\d{2}-\\d{2}").matcher(b);
            if (ym.find()) blockYear = ym.group(1);
            String targetFile = "sms_raw_" + blockYear + ".txt";
            if (!yearMap.containsKey(targetFile)) yearMap.put(targetFile, new StringBuilder());
            yearMap.get(targetFile).append(b).append("-----------------------------------\n");
        }

        new Thread(() -> {
            try {
                DriveUploadHelper up = new DriveUploadHelper(PinActivity.this);
                for (java.util.Map.Entry<String, StringBuilder> entry : yearMap.entrySet()) {
                    String fileName = entry.getKey();
                    String fileContent = entry.getValue().toString();
                    android.util.Log.d("DELETE_DEBUG", "업로드 시도: " + fileName + " / 내용길이: " + fileContent.length());
                    up.uploadFileSync(fileContent, fileName);
                    // 업로드 직후 Drive 캐시 무효화 → 일반사용자 재로드 시 최신 데이터 보장
                    DriveReadHelper.invalidateCache(fileName);
                    android.util.Log.d("DELETE_DEBUG", "업로드 완료: " + fileName);
                }
                // 삭제 후 balance.txt 갱신 (남은 블록에서 최신 잔액 재파싱)
                updateBalanceTxtFromBlocks(remaining);
                // 삭제된 블록 분류: 실제 블록 / [TEST] 블록
                boolean hasRealDelete = false;
                boolean hasTestDelete = false;
                for (int si = 0; si < cachedBlocks.size(); si++) {
                    if (selectedIdx.contains(si)) {
                        String delBlock = cachedBlocks.get(si);
                        if (delBlock.contains("[TEST]")) hasTestDelete = true;
                        else hasRealDelete = true;
                    }
                }
                if (hasRealDelete) {
                    // 실제 블록 삭제 → 전체 사용자에게 삭제 신호
                    SmsReceiver.sendFcmDeleteSignal(PinActivity.this);
                }
                if (hasTestDelete && !hasRealDelete) {
                    // [TEST] 블록만 삭제 → kisseyes4uu + 관리자에게만 삭제 신호
                    android.util.Log.d("DELETE_DEBUG", "[TEST] 블록 삭제 → kisseyes4uu + 관리자에게만 FCM 전송");
                    sendTestDeleteSignal("kisseyes4uu@gmail.com");
                }
                runOnUiThread(() -> {
                    // 캐시를 삭제 결과로 즉시 교체 (Drive 재읽기 불필요)
                    cachedBlocks = remaining;
                    cachedBalValues = null; // 잔액 캐시도 초기화
                    lastKnownBlockCount = remaining.size();
                    isDeleting = false;
                    // 선택 모드 종료
                    isSelectMode = false;
                    selectedIdx.clear();
                    if (pendingSelectIdx != null) pendingSelectIdx.clear();
                    if (selectActionBar != null) selectActionBar.setVisibility(View.GONE);
                    // 잔액 카드 갱신 (관리자/일반 공통)
                    if (tvBalValues != null) updateBalanceValues(remaining);
                    if (menuBalTv != null) updateMenuBalCards(remaining);
                    // 문자 목록 다시 렌더링
                    displayedCount = Math.min(PAGE_SIZE, remaining.size());
                    renderMessages(remaining, currentTabFilter);
                    Toast.makeText(PinActivity.this,
                            "삭제 완료", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                android.util.Log.e("DELETE_DEBUG", "업로드 실패: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    isDeleting = false;
                    Toast.makeText(PinActivity.this,
                            "삭제 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ── 뒤로가기 ───────────────────────────────────────────
    private void goBackFromBalance() {
        isOnBalanceScreen = false;
        if (isOwner) ownerMenuBuilder.build();
        else checkVersionThenShowMenu();
    }

    // ── 자동 새로고침 제어 ────────────────────────────────
    private void stopAutoRefresh() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
        if (blockedCheckRunnable != null) {
            refreshHandler.removeCallbacks(blockedCheckRunnable);
            blockedCheckRunnable = null;
        }
        // ticker 정리
        if (tickerRunnable != null) {
            tickerHandler.removeCallbacks(tickerRunnable);
            tickerRunnable = null;
        }
        // 타이틀 토글 정리
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // ★ SMS 수신 후 저장된 pending_new_block 즉시 처리
        // (관리자: SmsReceiver에서 저장 / 일반사용자: MyFirebaseMessagingService에서 저장)
        android.content.SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String pendingBlock = prefs.getString("pending_new_block", "");
        boolean hadPending = false;
        if (!pendingBlock.isEmpty()) {
            prefs.edit().remove("pending_new_block").apply();
            hadPending = true;
            android.util.Log.d("RESUME", "pending_new_block 처리: " + pendingBlock.length() + "자");
            if (cachedBlocks == null) cachedBlocks = new java.util.ArrayList<>();
            boolean alreadyExists = false;
            for (String b : cachedBlocks) {
                if (b.trim().equals(pendingBlock.trim())) { alreadyExists = true; break; }
            }
            if (!alreadyExists) {
                cachedBlocks.add(pendingBlock);
                lastKnownBlockCount = cachedBlocks.size();
                android.util.Log.d("RESUME", "캐시 추가 완료 총 " + cachedBlocks.size() + "개");
                if (tvBalValues != null) updateBalanceValues(cachedBlocks);
                else updateWidgetFromBlocks(cachedBlocks);
                if (isOnBalanceScreen && msgContainer != null) {
                    displayedCount = Math.min(Math.max(displayedCount, PAGE_SIZE), cachedBlocks.size());
                    renderMessages(cachedBlocks, currentTabFilter);
                }
                if (menuBalTv != null && isOnMenuScreen) updateMenuBalCards(cachedBlocks);
                cachedBalValues = null;
            }
        }

        // ★ 삭제 후 저장된 pending_delete 즉시 처리
        // (일반사용자: MyFirebaseMessagingService에서 저장)
        boolean pendingDelete = prefs.getBoolean("pending_delete", false);
        if (pendingDelete) {
            prefs.edit().remove("pending_delete").apply();
            hadPending = true;
            android.util.Log.d("RESUME", "pending_delete 처리 → Drive 재로드");
            cachedBlocks    = null;
            cachedBalValues = null;
            // Drive 캐시 무효화 후 최신 데이터 로드
            int curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            DriveReadHelper.invalidateCache(SmsReceiver.getSmsRawFile(curYear));
            DriveReadHelper.invalidateCache(SmsReceiver.getSmsRawFile(curYear - 1));
            DriveReadHelper.invalidateCache(BALANCE_FILE);
            runOnUiThread(() -> forceReloadAfterDelete());
            hadPending = false; // 삭제 후에는 forceReload가 필요하므로 hadPending 유지 안 함
        }

        // 접근성/배터리 설정 화면에서 돌아올 때 관리자 메뉴 다시 그리기
        if (isOwner && isOnMenuScreen) {
            ownerMenuBuilder.build();
            return;
        }
        // 일반사용자 메뉴: 배터리 설정 화면에서 돌아올 때 헤더 배지 즉시 갱신
        if (!isOwner && isOnMenuScreen) {
            userMenuBuilder.build(false);
            return;
        }
        // 백그라운드에서 포그라운드로 올라올 때 갱신
        // pending_new_block을 이미 처리한 경우 Drive 읽기 생략 (구버전 덮어씌움 방지)
        if (!hadPending && !currentUserEmail.isEmpty() && (isOnBalanceScreen || isOnMenuScreen)) {
            forceReloadMessages();
        }
        // 일반사용자 백그라운드 복귀 시 마지막 접속 시간 갱신
        if (!isOwner && !currentUserEmail.isEmpty()) {
            updateLastAccessTime();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoRefresh();
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(smsUpdateReceiver);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(smsDeleteReceiver);
    }

    // ═══════════════════════════════════════════════════════
    //  공통 유틸
    // ═══════════════════════════════════════════════════════
    private LinearLayout makeMenuCard(String title, String desc, String color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(20f);
        card.setBackground(bg);
        card.setElevation(10f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 16);
        card.setLayoutParams(lp);
        card.setPadding(32, 28, 32, 28);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.parseColor(color));
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setShadowLayer(3f, 1f, 1f, Color.parseColor("#22000000"));
        card.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(desc);
        tvDesc.setTextColor(Color.parseColor("#888888"));
        tvDesc.setTextSize(13);
        tvDesc.setPadding(0, 6, 0, 0);
        tvDesc.setShadowLayer(2f, 1f, 1f, Color.parseColor("#15000000"));
        card.addView(tvDesc);

        return card;
    }

    private Button makeButton(String text, String color) {
        Button btn = new Button(this);
        btn.setText(text);
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(50f);
        btn.setBackground(d);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void styleActionBtn(Button btn, String color) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(12f);
        btn.setBackground(d);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(8, 0, 0, 0);
        btn.setLayoutParams(lp);
    }

    // 관리자용: 버전 표시만 (비교 없음)
    private TextView makeVersionLabel() {
        TextView tv = new TextView(this);
        tv.setText("v" + getMyVersion());
        tv.setTextColor(Color.parseColor("#888888"));
        tv.setTextSize(16);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, 0, 24, 0);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.setMargins(0, 0, 16, navBarHeight + dpToPx(10));
        tv.setLayoutParams(lp);
        return tv;
    }

    // 일반사용자용: Drive 버전과 다르면 빨간색 경고
    private TextView makeUserVersionLabel() {
        TextView tv = new TextView(this);
        String myVer = getMyVersion();
        tv.setText("v" + myVer);
        tv.setTextColor(Color.parseColor("#888888"));
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, 0, 24, 0);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.setMargins(0, 0, 16, dpToPx(4));
        tv.setLayoutParams(lp);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(tv, (v, insets) -> {
            int navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            lp.setMargins(0, 0, 16, navBottom + dpToPx(4));
            tv.setLayoutParams(lp);
            return insets;
        });
        try {
            DriveReadHelper reader = new DriveReadHelper(this);
            reader.readFile(VERSION_FILE, new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String driveVer) {
                    runOnUiThread(() -> {
                        if (!driveVer.trim().equals(myVer)) {
                            tv.setText("v" + myVer + " ⚠");
                            tv.setTextColor(Color.parseColor("#E74C3C"));
                        }
                    });
                }
                @Override public void onFailure(String e) {}
            });
        } catch (Exception ignored) {}
        return tv;
    }


    /** 버전 불일치 시 전체화면 차단 - 앱 사용 불가 */
    private void showForceUpdateScreen() {
        // 상태바 빨간색
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(Color.parseColor("#C0392B"));
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        // 전체 배경 (어두운 빨강)
        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(Color.parseColor("#1A0000"));

        // 중앙 컨텐츠 레이아웃
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dpToPx(32), 0, dpToPx(32), 0);
        RelativeLayout.LayoutParams centerParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        center.setLayoutParams(centerParams);

        // 경고 아이콘
        TextView tvIcon = new TextView(this);
        tvIcon.setText("🚨");
        tvIcon.setTextSize(80);
        tvIcon.setGravity(Gravity.CENTER);
        center.addView(tvIcon);

        // 큰 제목
        TextView tvTitle = new TextView(this);
        tvTitle.setText("업데이트 필요");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(36);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, dpToPx(16), 0, 0);
        center.addView(tvTitle);

        // 구분선
        View divider = new View(this);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                dpToPx(80), dpToPx(3));
        divParams.gravity = Gravity.CENTER_HORIZONTAL;
        divParams.setMargins(0, dpToPx(16), 0, dpToPx(16));
        divider.setLayoutParams(divParams);
        divider.setBackgroundColor(Color.parseColor("#E74C3C"));
        center.addView(divider);

        // 설명 텍스트
        TextView tvDesc = new TextView(this);
        tvDesc.setText("최신 버전으로 업데이트해야\n앱을 사용할 수 있습니다.");
        tvDesc.setTextColor(Color.parseColor("#FFCCCC"));
        tvDesc.setTextSize(18);
        tvDesc.setGravity(Gravity.CENTER);
        tvDesc.setLineSpacing(6f, 1f);
        center.addView(tvDesc);

        // 다운로드 상태 텍스트
        tvDownloadStatus = new TextView(this);
        tvDownloadStatus.setText("⏳  최신 버전 다운로드 중...");
        tvDownloadStatus.setTextColor(Color.parseColor("#FFD700"));
        tvDownloadStatus.setTextSize(16);
        tvDownloadStatus.setGravity(Gravity.CENTER);
        tvDownloadStatus.setPadding(0, dpToPx(28), 0, dpToPx(8));
        center.addView(tvDownloadStatus);

        // 프로그레스바
        downloadProgressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        downloadProgressBar.setMax(100);
        downloadProgressBar.setProgress(0);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(8));
        pbParams.setMargins(0, 0, 0, dpToPx(28));
        downloadProgressBar.setLayoutParams(pbParams);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            downloadProgressBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E74C3C")));
        }
        center.addView(downloadProgressBar);

        // 설치 버튼 (처음에는 비활성)
        btnInstall = new Button(this);
        btnInstall.setText("⬇  지금 설치");
        btnInstall.setTextColor(Color.parseColor("#AAAAAA"));
        btnInstall.setTextSize(22);
        btnInstall.setTypeface(null, Typeface.BOLD);
        android.graphics.drawable.GradientDrawable btnBg =
                new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(Color.parseColor("#555555"));
        btnBg.setCornerRadius(dpToPx(16));
        btnInstall.setBackground(btnBg);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(72));
        btnInstall.setLayoutParams(btnParams);
        btnInstall.setEnabled(false);
        center.addView(btnInstall);

        // 안내 텍스트
        TextView tvHint = new TextView(this);
        tvHint.setText("다운로드 완료 후 설치 버튼이 활성화됩니다");
        tvHint.setTextColor(Color.parseColor("#FF9999"));
        tvHint.setTextSize(13);
        tvHint.setGravity(Gravity.CENTER);
        tvHint.setPadding(0, dpToPx(14), 0, 0);
        center.addView(tvHint);

        root.addView(center);
        setContentView(root);
    }

    private Button makeUpdateButton() {
        Button btn = new Button(this);
        btn.setText("업데이트");
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(Color.parseColor("#C0392B"));
        d.setCornerRadius(12f);
        btn.setBackground(d);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.setMargins(0, 0, 16, navBarHeight + dpToPx(10));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> openPlayStore());
        return btn;
    }

    private void startBackgroundDownload() {
        // Play Store 방식: 다운로드 없이 바로 업데이트 준비 완료 처리
        runOnUiThread(() -> onDownloadComplete());
    }

    private void onDownloadComplete() {
        if (tvDownloadStatus != null) {
            tvDownloadStatus.setText("✅  새 버전이 준비됐습니다");
            tvDownloadStatus.setTextColor(Color.parseColor("#00FF88"));
        }
        if (downloadProgressBar != null) downloadProgressBar.setProgress(100);
        if (btnInstall != null) {
            btnInstall.setText("✅  Play 스토어에서 업데이트");
            btnInstall.setTextColor(Color.WHITE);
            btnInstall.setEnabled(true);
            android.graphics.drawable.GradientDrawable activeBg =
                    new android.graphics.drawable.GradientDrawable();
            activeBg.setColor(Color.parseColor("#E74C3C"));
            activeBg.setCornerRadius(dpToPx(16));
            btnInstall.setBackground(activeBg);
            btnInstall.setOnClickListener(v -> openPlayStore());
        }
    }

    private void onDownloadFailed() {
        if (tvDownloadStatus != null) {
            tvDownloadStatus.setText("Play 스토어에서 업데이트하세요");
            tvDownloadStatus.setTextColor(Color.parseColor("#FFD700"));
        }
        if (btnInstall != null) {
            btnInstall.setText("🌐  Play 스토어 열기");
            btnInstall.setTextColor(Color.WHITE);
            btnInstall.setEnabled(true);
            android.graphics.drawable.GradientDrawable fallbackBg =
                    new android.graphics.drawable.GradientDrawable();
            fallbackBg.setColor(Color.parseColor("#C0392B"));
            fallbackBg.setCornerRadius(dpToPx(16));
            btnInstall.setBackground(fallbackBg);
            btnInstall.setOnClickListener(v -> openPlayStore());
        }
    }

    private void openPlayStore() {
        String pkg = getPackageName();
        try {
            // Play 스토어 앱으로 열기 시도
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + pkg)));
        } catch (android.content.ActivityNotFoundException e) {
            // Play 스토어 앱 없으면 브라우저로 열기
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
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

    // ── 핀치줌 + 더블탭 ImageView ─────────────────────────
    private static class ZoomImageView extends android.widget.ImageView {
        private android.graphics.Matrix matrix = new android.graphics.Matrix();
        private float[] matrixValues = new float[9];
        private float minScale = 1f;
        private float maxScale = 5f;
        private android.view.ScaleGestureDetector scaleDetector;
        private android.view.GestureDetector gestureDetector;
        private float lastX, lastY;
        private boolean isDragging = false;
        private int viewW, viewH, imgW, imgH;

        public ZoomImageView(android.content.Context ctx) {
            super(ctx);
            setScaleType(ScaleType.MATRIX);
            scaleDetector = new android.view.ScaleGestureDetector(ctx,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(android.view.ScaleGestureDetector d) {
                            float factor = d.getScaleFactor();
                            matrix.getValues(matrixValues);
                            float curScale = matrixValues[android.graphics.Matrix.MSCALE_X];
                            float newScale = curScale * factor;
                            if (newScale < minScale) factor = minScale / curScale;
                            if (newScale > maxScale) factor = maxScale / curScale;
                            matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                            clampMatrix();
                            setImageMatrix(matrix);
                            return true;
                        }
                    });
            gestureDetector = new android.view.GestureDetector(ctx,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(android.view.MotionEvent e) {
                            matrix.getValues(matrixValues);
                            float cur = matrixValues[android.graphics.Matrix.MSCALE_X];
                            float target = (cur > minScale * 1.5f) ? minScale : minScale * 2.5f;
                            float factor = target / cur;
                            matrix.postScale(factor, factor, e.getX(), e.getY());
                            clampMatrix();
                            setImageMatrix(matrix);
                            return true;
                        }
                    });
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            viewW = w; viewH = h;
            initMatrix();
        }

        @Override
        public void setImageBitmap(android.graphics.Bitmap bm) {
            super.setImageBitmap(bm);
            if (bm != null) { imgW = bm.getWidth(); imgH = bm.getHeight(); }
            if (viewW > 0) initMatrix();
        }

        private void initMatrix() {
            if (imgW == 0 || imgH == 0 || viewW == 0) return;
            float scale = Math.min((float)viewW / imgW, (float)viewH / imgH);
            minScale = scale;
            float dx = (viewW - imgW * scale) / 2f;
            float dy = (viewH - imgH * scale) / 2f;
            matrix.reset();
            matrix.postScale(scale, scale);
            matrix.postTranslate(dx, dy);
            setImageMatrix(matrix);
        }

        private void clampMatrix() {
            matrix.getValues(matrixValues);
            float scaleX = matrixValues[android.graphics.Matrix.MSCALE_X];
            float transX = matrixValues[android.graphics.Matrix.MTRANS_X];
            float transY = matrixValues[android.graphics.Matrix.MTRANS_Y];
            float scaledW = imgW * scaleX;
            float scaledH = imgH * scaleX;
            float newX = transX, newY = transY;
            if (scaledW <= viewW) newX = (viewW - scaledW) / 2f;
            else { if (newX > 0) newX = 0; if (newX < viewW - scaledW) newX = viewW - scaledW; }
            if (scaledH <= viewH) newY = (viewH - scaledH) / 2f;
            else { if (newY > 0) newY = 0; if (newY < viewH - scaledH) newY = viewH - scaledH; }
            matrixValues[android.graphics.Matrix.MTRANS_X] = newX;
            matrixValues[android.graphics.Matrix.MTRANS_Y] = newY;
            matrix.setValues(matrixValues);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            scaleDetector.onTouchEvent(e);
            gestureDetector.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    lastX = e.getX(); lastY = e.getY(); isDragging = true; break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (isDragging && !scaleDetector.isInProgress()) {
                        float dx = e.getX() - lastX, dy = e.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        clampMatrix();
                        setImageMatrix(matrix);
                        lastX = e.getX(); lastY = e.getY();
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    isDragging = false; break;
            }
            return true;
        }
    }

    /**
     * 선결제 잔액 화면 전용: sms_raw(현재+이전연도) + prepaid.txt 합산
     * prepaid.txt는 미트클럽스토어 등 선결제 거래 테스트/보조 데이터
     */
    private void readMeatSmsRaw(DriveReadHelper.ReadCallback callback) {
        // 1단계: sms_raw 합산 (기존 로직 재사용)
        readMergedSmsRaw(new DriveReadHelper.ReadCallback() {
            @Override public void onSuccess(String rawContent) {
                // 2단계: prepaid.txt 추가 읽기
                DriveReadHelper testReader = new DriveReadHelper(PinActivity.this);
                testReader.readFile("prepaid.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String testContent) {
                        // prepaid.txt: 출금 → 선입금 자동 변환 (기존 데이터 호환)
                        callback.onSuccess(rawContent + convertToPrePaid(testContent));
                    }
                    @Override public void onFailure(String error) {
                        callback.onSuccess(rawContent);
                    }
                });
            }
            @Override public void onFailure(String error) {
                DriveReadHelper testReader = new DriveReadHelper(PinActivity.this);
                testReader.readFile("prepaid.txt", new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String testContent) {
                        callback.onSuccess(convertToPrePaid(testContent));
                    }
                    @Override public void onFailure(String err2) {
                        callback.onFailure("파일 없음");
                    }
                });
            }
        });
    }

    /**
     * prepaid.txt 내용에서 블록별로 "출금 XXX원" → "선입금 XXX원" 변환
     * 잔액은 해당 블록의 선입금 금액과 동일하게 교체
     * 이미 "선입금"으로 저장된 블록은 그대로 유지
     */
    private String convertToPrePaid(String content) {
        if (content == null || content.isEmpty()) return content;
        String[] rawBlocks = content.split("-----------------------------------\r?\n");
        StringBuilder result = new StringBuilder();
        for (String block : rawBlocks) {
            if (block.trim().isEmpty()) continue;
            // 이미 선입금 또는 구매로 변환된 블록은 그대로
            if (block.contains("선입금") || block.contains("구매")) {
                result.append(block.trim()).append("\n-----------------------------------\n");
                continue;
            }
            // 출금 금액 추출
            String prepaidAmt = "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("출금\\s*([\\d,]+)원").matcher(block);
            if (m.find()) prepaidAmt = m.group(1);
            // 줄별 변환
            StringBuilder converted = new StringBuilder();
            for (String line : block.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.contains("출금") && !t.contains("잔액")) {
                    t = t.replaceAll("출금\\s*([\\d,]+원)", "선입금 $1");
                } else if (t.contains("잔액") && !prepaidAmt.isEmpty()) {
                    t = "잔액 " + prepaidAmt + "원";
                }
                converted.append(t).append("\n");
            }
            result.append(converted.toString().trim())
                    .append("\n-----------------------------------\n");
        }
        return result.toString();
    }

    /**
     * 현재 연도 + 이전 연도 sms_raw 파일을 합쳐서 읽어 콜백으로 전달
     * 예: 2027년이면 sms_raw_2027.txt + sms_raw_2026.txt 합산
     */
    private void readMergedSmsRaw(DriveReadHelper.ReadCallback callback) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int curYear  = cal.get(java.util.Calendar.YEAR);
        int prevYear = curYear - 1;
        String curFile  = SmsReceiver.getSmsRawFile(curYear);
        String prevFile = SmsReceiver.getSmsRawFile(prevYear);

        DriveReadHelper reader = new DriveReadHelper(this);
        // 현재 연도 파일 읽기
        reader.readFile(curFile, new DriveReadHelper.ReadCallback() {
            @Override public void onSuccess(String curContent) {
                DriveReadHelper reader2 = new DriveReadHelper(PinActivity.this);
                reader2.readFile(prevFile, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String prevContent) {
                        // 이전 + 현재 + Test.txt 순서로 합침
                        readTestTxtAndMerge(prevContent + curContent, callback);
                    }
                    @Override public void onFailure(String error) {
                        readTestTxtAndMerge(curContent, callback);
                    }
                });
            }
            @Override public void onFailure(String error) {
                DriveReadHelper reader2 = new DriveReadHelper(PinActivity.this);
                reader2.readFile(prevFile, new DriveReadHelper.ReadCallback() {
                    @Override public void onSuccess(String prevContent) {
                        readTestTxtAndMerge(prevContent, callback);
                    }
                    @Override public void onFailure(String err2) {
                        // sms_raw 없어도 Test.txt는 읽기 시도
                        readTestTxtAndMerge("", callback);
                    }
                });
            }
        });
    }

    /** Test.txt를 읽어서 기존 내용 뒤에 합쳐서 callback */
    private void readTestTxtAndMerge(String baseContent, DriveReadHelper.ReadCallback callback) {
        try {
            DriveReadHelper testReader = new DriveReadHelper(this);
            testReader.readFile("Test.txt", new DriveReadHelper.ReadCallback() {
                @Override public void onSuccess(String testContent) {
                    // Test.txt 내용을 뒤에 붙임 (최신 순 정렬은 renderMessages에서 역순으로)
                    callback.onSuccess(baseContent + testContent);
                }
                @Override public void onFailure(String error) {
                    // Test.txt 없으면 기존 내용만
                    callback.onSuccess(baseContent);
                }
            });
        } catch (Exception e) {
            callback.onSuccess(baseContent);
        }
    }

    /** 시스템 글자 크기 설정 무시 - dp 고정 크기 적용 */
    private void setTextSizeDp(TextView tv, float dp) {
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, dp);
    }

    // ── 폰트 크기 설정 (소=0, 중=1, 대=2) ─────────────────
    private int getFontLevel() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getInt("font_level", 1); // 기본 중간
    }

    private void saveFontLevel(int level) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putInt("font_level", level).apply();
    }

    // 기준 크기에 레벨 적용 (소=-2, 중=0, 대=+4)
    private float fs(float base) {
        int level = getFontLevel();
        if (level == 0) return base - 2f;
        if (level == 2) return base + 4f;
        return base;
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    /**
     * 카드용 그림자 Drawable 생성
     * ScrollView 내부에서도 잘리지 않는 Paint 기반 커스텀 그림자
     */
    private android.graphics.drawable.Drawable makeShadowCardDrawable(
            String cardColor, int cornerDp, int shadowDp) {
        int corner = dpToPx(cornerDp);
        int sh     = dpToPx(shadowDp);
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint shadowPaint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Paint cardPaint   = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            {
                shadowPaint.setColor(0x00000000);
                shadowPaint.setShadowLayer(sh, 0, sh / 2f, 0x28000000);
                cardPaint.setColor(Color.parseColor(cardColor));
            }
            @Override
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.RectF r = new android.graphics.RectF(getBounds());
                r.inset(sh, sh / 2f);
                r.offset(0, -sh / 4f);
                canvas.drawRoundRect(r, corner, corner, shadowPaint);
                r.offset(0, sh / 4f);
                canvas.drawRoundRect(r, corner, corner, cardPaint);
            }
            @Override public void setAlpha(int a) { cardPaint.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    // ══════════════════════════════════════════════════════
    //  버스 도착정보 섹션 - TAGO API (apis.data.go.kr/1613000)
    //  흐름: 버스번호 입력 → 노선목록 → 정류소선택 → 도착정보
    // ══════════════════════════════════════════════════════
    private static final String BUS_KEY   = "4f9182aa6a8d775a6013c074fc5620578371c0031a6f97e9c0434e3973bcf1d5";
    private static final String BUS_BASE2 = "https://apis.data.go.kr/1613000/";
    private static final String BUS_CITY  = "25"; // 대전
    private static final String BUS_DB_PREF   = "bus_route_db";
    private static final String BUS_DB_KEY    = "all_routes";
    private static final String BUS_DB_VER    = "db_version";
    private static final String BUS_DB_SCHEMA = "db_schema";
    private static final int    BUS_DB_SCHEMA_VER = 3;
    private static final String STOP_DB_FILE  = "dj_stops.json"; // Drive 정류장 파일
    private static final String BUS_TIME_FILE = "bustimes.txt"; // Drive 배차시간표 파일

    /** SharedPreferences DB를 메모리에 로드 (앱 시작 시 1회) */
    private void loadBusDbToMemory() {
        new Thread(() -> {
            android.content.SharedPreferences p = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE);
            // 노선 (로컬 SharedPreferences)
            String rawRoute = p.getString(BUS_DB_KEY, "");
            java.util.List<String[]> rList = new java.util.ArrayList<>();
            if (!rawRoute.isEmpty()) {
                for (String line : rawRoute.split(";")) {
                    String[] parts = line.split("\\|", -1);
                    if (parts.length >= 5) rList.add(parts);
                }
            }
            routeDbList = rList;

            // 정류장 (Drive dj_stops.json 로컬 캐시)
            String stopJson = p.getString("stop_json_cache", "");
            if (!stopJson.isEmpty()) {
                loadStopJsonToMemory(stopJson);
            }
        }).start();
    }

    /** JSON 문자열 → stopDbList 파싱 */
    private void loadStopJsonToMemory(String json) {
        try {
            java.util.List<String[]> sList = new java.util.ArrayList<>();
            java.util.Map<String, String> nMap = new java.util.HashMap<>();
            // JSON 배열: [{"id":"...","nm":"...","no":"...","routes":"211,212,601,708"},...]
            int i = 0;
            while (true) {
                int s = json.indexOf('{', i);
                if (s < 0) break;
                // routes 배열 포함 가능하므로 중괄호 depth 추적
                int depth = 1, e = s + 1;
                while (e < json.length() && depth > 0) {
                    char c = json.charAt(e);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                    e++;
                }
                String obj = json.substring(s, e);
                String id     = jsonVal(obj, "id");
                String nm     = jsonVal(obj, "nm");
                String no     = jsonVal(obj, "no");
                String routes = jsonVal(obj, "routes");
                if (!id.isEmpty() && !nm.isEmpty()) {
                    sList.add(new String[]{id, nm, no, routes});
                    // nodeno(표시번호)를 키로 저장 (타임라인 nodeId 형식과 무관하게 매칭 가능)
                    if (!routes.isEmpty() && !no.isEmpty()) nMap.put(no, routes);
                }
                i = e;
            }
            stopDbList = sList;
            nodeNoToRoutes = nMap;
        } catch (Exception ignored) {}
    }

    /** 정류장 DB를 내부 저장소 파일에 저장 */
    private void saveStopDb(String content) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "stop_db.json");
            if (content.isEmpty()) { f.delete(); return; }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    /** 내부 저장소에서 정류장 DB 읽기 */
    private String loadStopDb() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "stop_db.json");
            if (!f.exists()) return "";
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            fis.read(buf);
            fis.close();
            return new String(buf, "UTF-8");
        } catch (Exception ignored) { return ""; }
    }

    /** 배차시간표를 내부 저장소 파일에 저장 */
    private void saveBusTimes(String content) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "bustimes.txt");
            if (content.isEmpty()) { f.delete(); return; }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    /** 내부 저장소에서 배차시간표 읽기 */
    private String loadBusTimes() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "bustimes.txt");
            if (!f.exists()) return "";
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            fis.read(buf);
            fis.close();
            return new String(buf, "UTF-8");
        } catch (Exception ignored) { return ""; }
    }

    /**
     * bustimes_v4.txt → busTimesMap 파싱
     * 형식: rno|src|dst|company|oneway||W|interval|rows||S|interval|rows||H|interval|rows
     * rows: "구분레이블:c2,c3,...c21" ~ 구분
     * busTimesMap value: [src,dst,company,oneway, W_inv,W_rows, S_inv,S_rows, H_inv,H_rows]
     */
    private void loadBusTimesFromJson(String txt) {
        try {
            java.util.Map<String, String[]> map = new java.util.HashMap<>();
            for (String line : txt.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // 헤더와 요일 블록 분리 (|| 구분)
                String[] blocks = line.split("\\|\\|");
                if (blocks.length < 2) continue;
                String[] h = blocks[0].split("\\|", 5);
                if (h.length < 5) continue;
                String rno=h[0], src=h[1], dst=h[2], company=h[3], oneway=h[4];
                // 요일 블록 파싱
                String wInv="", wRows="", sInv="", sRows="", hInv="", hRows="";
                for (int bi = 1; bi < blocks.length && bi <= 3; bi++) {
                    String[] dp = blocks[bi].split("\\|", 3);
                    if (dp.length < 3) continue;
                    String dayKey=dp[0], inv=dp[1], rows=dp[2];
                    if ("W".equals(dayKey)) { wInv=inv; wRows=rows; }
                    else if ("S".equals(dayKey)) { sInv=inv; sRows=rows; }
                    else if ("H".equals(dayKey)) { hInv=inv; hRows=rows; }
                }
                // 기존 호환: ws/wd 추출 (짝수/홀수 열 합산)
                String ws2=extractTimesFromRows(wRows,true), wd=extractTimesFromRows(wRows,false);
                String ss=extractTimesFromRows(sRows,true), sd=extractTimesFromRows(sRows,false);
                String hs=extractTimesFromRows(hRows,true), hd=extractTimesFromRows(hRows,false);
                if (!rno.isEmpty()) {
                    map.put(rno, new String[]{src,dst,company,oneway,
                            wInv,wRows, sInv,sRows, hInv,hRows,
                            ws2,wd, ss,sd, hs,hd});
                }
            }
            busTimesMap = map;
            android.util.Log.d("BusTimes", "loaded: " + map.size() + " routes");
        } catch (Exception e) {
            android.util.Log.e("BusTimes", "parse error: " + e.getMessage());
        }
    }

    /** rows 문자열에서 출발 시간 추출 (src=true: 짝수열, false: 홀수열) */
    private String extractTimesFromRows(String rows, boolean src) {
        if (rows == null || rows.isEmpty()) return "";
        java.util.Set<String> times = new java.util.TreeSet<>();
        for (String row : rows.split("~")) {
            int colon = row.indexOf(':');
            if (colon < 0) continue;
            String[] cols = row.substring(colon + 1).split(",", -1);
            // cols[0]=B, cols[1]=C, ... 짝수인덱스(0,2,4...)=기점, 홀수=종점
            for (int i = src ? 0 : 1; i < cols.length; i += 2) {
                String t = cols[i].trim().replace(":", "");
                if (t.length() == 4) {
                    try { Integer.parseInt(t); times.add(t); } catch (Exception ig) {}
                }
            }
        }
        return String.join(",", times);
    }

    /** 배차시간표 표에 셀 추가 헬퍼 */
    private void addCell(LinearLayout row, String text, int width, int height,
                         int color, float size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size);
        tv.setGravity(Gravity.CENTER);
        tv.setWidth(width);
        if (height > 0) tv.setMinHeight(height);
        tv.setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2));
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(tv);
    }

    private String[] splitTimes(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split(",");
    }

    private String getNextDeparture(String routeNo, boolean fromSrc) {
        String[] data = busTimesMap.get(routeNo);
        if (data == null || data.length < 16) return "";
        // v4: [10]=ws,[11]=wd,[12]=ss,[13]=sd,[14]=hs,[15]=hd
        java.util.Calendar now = java.util.Calendar.getInstance();
        int dow = now.get(java.util.Calendar.DAY_OF_WEEK);
        int srcIdx, dstIdx;
        if (dow == java.util.Calendar.SATURDAY) { srcIdx=12; dstIdx=13; }
        else if (dow == java.util.Calendar.SUNDAY) { srcIdx=14; dstIdx=15; }
        else { srcIdx=10; dstIdx=11; }
        String timesStr = fromSrc ? data[srcIdx] : data[dstIdx];
        if (timesStr.isEmpty()) { timesStr = fromSrc ? data[2] : data[3]; } // fallback 평일
        if (timesStr.isEmpty()) return "";
        int nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        for (String t : timesStr.split(",")) {
            if (t.length() != 4) continue;
            try {
                int h = Integer.parseInt(t.substring(0, 2));
                int m = Integer.parseInt(t.substring(2, 4));
                if (h * 60 + m >= nowMin) {
                    return h + "시 " + String.format("%02d", m) + "분 출발";
                }
            } catch (Exception ig) {}
        }
        return "";
    }

    /** HTTP 스트림 → byte[] */
    private byte[] readBytes(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    /**
     * 대전 배차시간표 엑셀(xlsx) → bustimes.json 파싱
     * 엑셀 구조: 각 노선 헤더행(1열=노선번호번), 데이터행+6부터 시간 데이터
     * 짝수열(B,D,F...) = 기점출발, 홀수열(C,E,G...) = 종점출발
     */
    /**
     * 대전 배차시간표 xlsx → 9필드 TXT 파싱
     * 형식: rno|src|dst|ws|wd|ss|sd|hs|hd
     * ws=평일기점출발, wd=평일종점출발, ss=토요일기점, sd=토요일종점, hs=휴일기점, hd=휴일종점
     * 엑셀 구조: 한 노선이 평일/토요일/휴일 3번 반복 (40행 간격)
     */
    private String parseBusTimesXls(byte[] xlsBytes) {
        try {
            // ZIP(xlsx)에서 sheet1.xml, sharedStrings.xml 추출
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    new java.io.ByteArrayInputStream(xlsBytes));
            byte[] sheetXml = null, sharedStrXml = null;
            java.util.zip.ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                String name = ze.getName();
                if (name.equals("xl/worksheets/sheet1.xml")) sheetXml = readBytes(zis);
                if (name.equals("xl/sharedStrings.xml")) sharedStrXml = readBytes(zis);
                zis.closeEntry();
            }
            zis.close();
            if (sheetXml == null) return "";

            // sharedStrings 파싱
            java.util.List<String> ss2 = new java.util.ArrayList<>();
            if (sharedStrXml != null) {
                String ss = new String(sharedStrXml, "UTF-8");
                int i = 0;
                while (true) {
                    int ts = ss.indexOf("<t", i); if (ts < 0) break;
                    int te = ss.indexOf("</t>", ts); if (te < 0) break;
                    int gt = ss.indexOf('>', ts);
                    ss2.add(gt < te ? ss.substring(gt + 1, te) : "");
                    i = te + 4;
                }
            }

            // 시트 XML → rows[rowNum][colNum]=value
            String sheet = new String(sheetXml, "UTF-8");
            java.util.TreeMap<Integer, java.util.TreeMap<Integer, String>> rows = new java.util.TreeMap<>();
            int ri = 0;
            while (true) {
                int rs = sheet.indexOf("<row ", ri); if (rs < 0) break;
                int re = sheet.indexOf("</row>", rs); if (re < 0) break;
                int rAttr = sheet.indexOf("r=\"", rs);
                int rno2 = Integer.parseInt(sheet.substring(rAttr + 3, sheet.indexOf('"', rAttr + 3)));
                java.util.TreeMap<Integer, String> cols = new java.util.TreeMap<>();
                String rowStr = sheet.substring(rs, re);
                int ci = 0;
                while (true) {
                    int cs = rowStr.indexOf("<c ", ci); if (cs < 0) break;
                    int ce = rowStr.indexOf("</c>", cs); if (ce < 0) break;
                    String cell = rowStr.substring(cs, ce + 4);
                    int ca = cell.indexOf("r=\"");
                    String addr = cell.substring(ca + 3, cell.indexOf('"', ca + 3));
                    int colNum = 0;
                    for (char ch : addr.toCharArray()) {
                        if (!Character.isLetter(ch)) break;
                        colNum = colNum * 26 + (Character.toUpperCase(ch) - 'A' + 1);
                    }
                    String val = "";
                    boolean isShared = cell.contains("t=\"s\"");
                    int vs = cell.indexOf("<v>"); int ve = cell.indexOf("</v>");
                    if (vs >= 0 && ve >= 0) {
                        String raw = cell.substring(vs + 3, ve);
                        if (isShared) { try { val = ss2.get(Integer.parseInt(raw)); } catch (Exception ig) {} }
                        else val = raw;
                    }
                    cols.put(colNum, val);
                    ci = ce + 4;
                }
                rows.put(rno2, cols);
                ri = re + 6;
            }

            // 노선 헤더 행 수집 (1열에 "N번" 포함) - 한 노선당 3개 (평일/토/휴)
            java.util.Map<String, java.util.List<Integer>> routeBlocks = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<Integer, java.util.TreeMap<Integer, String>> entry : rows.entrySet()) {
                String c1 = entry.getValue().getOrDefault(1, "");
                if (c1.contains("번") && c1.length() <= 10) {
                    String rno = c1.replace("번","").trim();
                    routeBlocks.computeIfAbsent(rno, k -> new java.util.ArrayList<>()).add(entry.getKey());
                }
            }

            // 각 노선 평일/토/휴 파싱
            StringBuilder sb = new StringBuilder();
            String[] dayKeys = {"W", "S", "H"};
            for (java.util.Map.Entry<String, java.util.List<Integer>> entry : routeBlocks.entrySet()) {
                String rno = entry.getKey();
                java.util.List<Integer> blocks = entry.getValue();
                if (blocks.isEmpty()) continue;

                int hdr0 = blocks.get(0);
                String srcNm = rows.getOrDefault(hdr0, new java.util.TreeMap<>()).getOrDefault(8, "");
                String dstNm = rows.getOrDefault(hdr0, new java.util.TreeMap<>()).getOrDefault(12, "");
                // 운행사, 편도
                String company = rows.getOrDefault(hdr0+2, new java.util.TreeMap<>()).getOrDefault(3, "");
                String oneway = excelTimeToHHMM(rows.getOrDefault(hdr0+2, new java.util.TreeMap<>()).getOrDefault(20, ""));
                if (oneway == null) oneway = "";
                else {
                    // HHMM → H:MM
                    if (oneway.length()==4) oneway = Integer.parseInt(oneway.substring(0,2)) + ":" + oneway.substring(2);
                }

                if (sb.length() > 0) sb.append("\n");
                sb.append(rno).append("|").append(srcNm).append("|").append(dstNm)
                  .append("|").append(company.trim()).append("|").append(oneway);

                for (int bi = 0; bi < Math.min(blocks.size(), 3); bi++) {
                    int hdr = blocks.get(bi);
                    // 평균간격 계산
                    java.util.List<Integer> srcTimes = new java.util.ArrayList<>();
                    for (int dr = hdr+6; dr < hdr+50; dr++) {
                        java.util.TreeMap<Integer, String> drow = rows.get(dr);
                        if (drow == null || drow.getOrDefault(1,"").isEmpty()) break;
                        for (int dc = 2; dc <= 21; dc += 2) {
                            String ts2 = excelTimeToHHMM(drow.getOrDefault(dc,""));
                            if (ts2 != null) {
                                int h=Integer.parseInt(ts2.substring(0,2)), m=Integer.parseInt(ts2.substring(2,4));
                                srcTimes.add(h*60+m);
                            }
                        }
                    }
                    java.util.Collections.sort(srcTimes);
                    int interval = 0;
                    if (srcTimes.size() >= 2) {
                        java.util.List<Integer> gaps = new java.util.ArrayList<>();
                        for (int g=0; g<srcTimes.size()-1; g++) {
                            int gap = srcTimes.get(g+1)-srcTimes.get(g);
                            if (gap>0 && gap<120) gaps.add(gap);
                        }
                        if (!gaps.isEmpty()) {
                            int sum=0; for(int g:gaps) sum+=g;
                            interval = Math.round((float)sum/gaps.size());
                        }
                    }

                    // 데이터 행 파싱
                    StringBuilder rowsSb = new StringBuilder();
                    for (int dr = hdr+6; dr < hdr+50; dr++) {
                        java.util.TreeMap<Integer, String> drow = rows.get(dr);
                        if (drow == null || drow.getOrDefault(1,"").isEmpty()) break;
                        String labelRaw = drow.getOrDefault(1,"").replace("\n"," ").replace("|","").replace("~","").trim();
                        if (labelRaw.isEmpty()) break;
                        if (rowsSb.length() > 0) rowsSb.append("~");
                        rowsSb.append(labelRaw).append(":");
                        for (int dc = 2; dc <= 21; dc++) {
                            if (dc > 2) rowsSb.append(",");
                            String ts2 = excelTimeToHHMM(drow.getOrDefault(dc,""));
                            if (ts2 != null) {
                                rowsSb.append(Integer.parseInt(ts2.substring(0,2)))
                                      .append(":").append(ts2.substring(2,4));
                            }
                        }
                    }

                    sb.append("||").append(dayKeys[bi]).append("|").append(interval)
                      .append("|").append(rowsSb);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            android.util.Log.e("BusTimes", "parseBusTimesXls error: " + e.getMessage());
            return "";
        }
    }

    /** 평일/토/휴 블록별 시간 파싱 → [[ws,wd],[ss,sd],[hs,hd]] */
    private String[][] parseAllDayBlocks(
            java.util.TreeMap<Integer, java.util.TreeMap<Integer, String>> rows,
            java.util.List<Integer> blocks) {
        String[][] result = new String[blocks.size()][2];
        for (int bi = 0; bi < blocks.size(); bi++) {
            int headerRow = blocks.get(bi);
            java.util.List<String> sTimes = new java.util.ArrayList<>();
            java.util.List<String> dTimes = new java.util.ArrayList<>();
            for (int dr = headerRow + 6; dr < headerRow + 50; dr++) {
                java.util.TreeMap<Integer, String> drow = rows.get(dr);
                if (drow == null || drow.getOrDefault(1, "").isEmpty()) break;
                for (int dc = 2; dc <= 21; dc += 2) {
                    String ts = excelTimeToHHMM(drow.getOrDefault(dc, ""));
                    if (ts != null && !sTimes.contains(ts)) sTimes.add(ts);
                }
                for (int dc = 3; dc <= 21; dc += 2) {
                    String ts = excelTimeToHHMM(drow.getOrDefault(dc, ""));
                    if (ts != null && !dTimes.contains(ts)) dTimes.add(ts);
                }
            }
            java.util.Collections.sort(sTimes);
            java.util.Collections.sort(dTimes);
            result[bi][0] = String.join(",", sTimes);
            result[bi][1] = String.join(",", dTimes);
        }
        return result;
    }

    /** 엑셀 시간값(소수) → "HHMM" 문자열 */
    private String excelTimeToHHMM(String val) {
        if (val == null || val.isEmpty()) return null;
        try {
            double d = Double.parseDouble(val);
            if (d <= 0 || d >= 1) return null;
            int totalMin = (int) Math.round(d * 24 * 60);
            int h = totalMin / 60, m = totalMin % 60;
            if (h > 23) return null;
            return String.format("%02d%02d", h, m);
        } catch (Exception ig) { return null; }
    }

    /** 배차시간표 다이얼로그 표시 */
    private void showBusTimeTableDialog(String routeNo, boolean fromSrc) {
        // busTimesMap 비어있거나 구버전이면 재로드
        if (busTimesMap.isEmpty() || (busTimesMap.get(routeNo) != null && busTimesMap.get(routeNo).length < 16)) {
            busTimesMap.clear();
            String cached = loadBusTimes(); // 내부 파일 우선
            if (cached.isEmpty()) cached = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE)
                    .getString("bustimes_txt_cache", "");
            if (!cached.isEmpty() && cached.contains("||")) loadBusTimesFromJson(cached);
        }
        String[] data = busTimesMap.get(routeNo);
        if (data == null || data.length < 16) {
            android.widget.Toast.makeText(this, "배차시간표 로딩 중...", android.widget.Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    android.content.SharedPreferences p = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE);
                    DriveReadHelper dr = new DriveReadHelper(this);
                    final Object lock = new Object();
                    dr.readFile(BUS_TIME_FILE, new DriveReadHelper.ReadCallback() {
                        @Override public void onSuccess(String content) {
                            if (!content.isEmpty()) {
                                p.edit().putString("bustimes_txt_cache", content).apply();
                                saveBusTimes(content); // 내부 파일에 영구 저장
                                loadBusTimesFromJson(content);
                            }
                            synchronized(lock) { lock.notifyAll(); }
                        }
                        @Override public void onFailure(String e) { synchronized(lock) { lock.notifyAll(); } }
                    });
                    synchronized(lock) { lock.wait(10000); }
                } catch (Exception ignored) {}
                runOnUiThread(() -> {
                    String[] d2 = busTimesMap.get(routeNo);
                    if (d2 != null && d2.length >= 16) showBusTimeTableDialog(routeNo, fromSrc);
                    else android.widget.Toast.makeText(this,
                            routeNo + "번 배차시간표 없음. 관리자 메뉴에서 업데이트하세요.",
                            android.widget.Toast.LENGTH_LONG).show();
                });
            }).start();
            return;
        }

        // 요일별 시간 데이터
        // v4: [10]=ws,[11]=wd,[12]=ss,[13]=sd,[14]=hs,[15]=hd
        String[] weekdayTimes = splitTimes(fromSrc ? data[10] : data[11]);
        String[] satTimes     = splitTimes(fromSrc ? data[12] : data[13]);
        String[] holTimes     = splitTimes(fromSrc ? data[14] : data[15]);
        // 상세 정보
        String company = data.length > 2 ? data[2] : "";
        String oneway  = data.length > 3 ? data[3] : "";
        // 요일별 평균간격
        java.util.Calendar nowForDay = java.util.Calendar.getInstance();
        int todayDow = nowForDay.get(java.util.Calendar.DAY_OF_WEEK);
        String todayInterval = todayDow == java.util.Calendar.SATURDAY ? (data.length>6?data[6]:"")
                : todayDow == java.util.Calendar.SUNDAY ? (data.length>8?data[8]:"")
                : (data.length>4?data[4]:"");

        // 다이얼로그 구성
        android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setCancelable(true);
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dlg.getWindow().setLayout(
                    (int)(getResources().getDisplayMetrics().widthPixels * 0.92),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable rootBg = new android.graphics.drawable.GradientDrawable();
        rootBg.setColor(Color.WHITE);
        rootBg.setCornerRadius(dpToPx(16));
        root.setBackground(rootBg);
        root.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        // 제목
        TextView tvTitle = new TextView(this);
        tvTitle.setText(routeNo + "번 배차시간표");
        tvTitle.setTextColor(Color.parseColor("#1A1A2E"));
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(tvTitle);

        // 운행사 / 평균간격 / 편도 정보 행
        if (!company.isEmpty()) {
            LinearLayout infoRow = new LinearLayout(this);
            infoRow.setOrientation(LinearLayout.HORIZONTAL);
            infoRow.setGravity(Gravity.CENTER_VERTICAL);
            infoRow.setBackgroundColor(Color.parseColor("#F8F8F8"));
            infoRow.setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5));
            LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            irLp.setMargins(0, dpToPx(6), 0, 0);
            infoRow.setLayoutParams(irLp);

            // 운행사 (좌측 flex)
            TextView tvCompany = new TextView(this);
            tvCompany.setText("🚌 " + company.trim());
            tvCompany.setTextColor(Color.parseColor("#555555"));
            tvCompany.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvCompany.setSingleLine(true);
            tvCompany.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvCompany.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            infoRow.addView(tvCompany);

            // 편도 시간
            if (!oneway.isEmpty()) {
                TextView tvOneway = new TextView(this);
                tvOneway.setText("편도 " + oneway);
                tvOneway.setTextColor(Color.parseColor("#0984E3"));
                tvOneway.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                tvOneway.setPadding(dpToPx(8), 0, dpToPx(4), 0);
                infoRow.addView(tvOneway);
            }

            // 평균간격
            if (!todayInterval.isEmpty() && !todayInterval.equals("0")) {
                TextView tvInterval = new TextView(this);
                tvInterval.setText("배차 " + todayInterval + "분");
                tvInterval.setTextColor(Color.parseColor("#E67E22"));
                tvInterval.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                tvInterval.setTypeface(null, android.graphics.Typeface.BOLD);
                infoRow.addView(tvInterval);
            }
            root.addView(infoRow);
        }

        // 평일 / 토요일 / 공휴일 탭
        String[] dayLabels = {"평일", "토요일", "공휴일"};
        final int[] curDay = {0};
        TextView[] dayTabs = new TextView[3];
        LinearLayout dayTabRow = new LinearLayout(this);
        dayTabRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dtrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dtrLp.setMargins(0, dpToPx(8), 0, dpToPx(6));
        dayTabRow.setLayoutParams(dtrLp);

        // 시간 그리드 영역 - 세로스크롤(ScrollView) + 가로스크롤(HorizontalScrollView)
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(320)));
        HorizontalScrollView hsv = new HorizontalScrollView(PinActivity.this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hsv.setHorizontalScrollBarEnabled(true);
        LinearLayout gridWrap = new LinearLayout(this);
        gridWrap.setOrientation(LinearLayout.VERTICAL);
        hsv.addView(gridWrap);
        sv.addView(hsv);

        // 현재 시간
        java.util.Calendar nowCal = java.util.Calendar.getInstance();
        int nowMin = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE);

        Runnable buildGrid = new Runnable() {
            @Override public void run() {
                gridWrap.removeAllViews();
                // 요일별 rows 데이터 가져오기
                // v4: [4]=wInv,[5]=wRows, [6]=sInv,[7]=sRows, [8]=hInv,[9]=hRows
                String rowsStr = (curDay[0] == 0) ? (data.length>5?data[5]:"")
                               : (curDay[0] == 1) ? (data.length>7?data[7]:"")
                               : (data.length>9?data[9]:"");
                String curInterval = (curDay[0] == 0) ? (data.length>4?data[4]:"")
                                   : (curDay[0] == 1) ? (data.length>6?data[6]:"")
                                   : (data.length>8?data[8]:"");

                if (rowsStr.isEmpty()) {
                    TextView tvEmpty = new TextView(PinActivity.this);
                    tvEmpty.setText(dayLabels[curDay[0]] + " 시간표 데이터가 없습니다");
                    tvEmpty.setTextColor(Color.parseColor("#AAAAAA"));
                    tvEmpty.setGravity(Gravity.CENTER);
                    tvEmpty.setPadding(0, dpToPx(30), 0, dpToPx(30));
                    gridWrap.addView(tvEmpty);
                    return;
                }

                // 현재 시간
                java.util.Calendar nowBg = java.util.Calendar.getInstance();
                int nowMinBg = nowBg.get(java.util.Calendar.HOUR_OF_DAY)*60 + nowBg.get(java.util.Calendar.MINUTE);

                // 간격 표시
                if (!curInterval.isEmpty() && !curInterval.equals("0")) {
                    TextView tvInv = new TextView(PinActivity.this);
                    tvInv.setText("평균 배차간격: " + curInterval + "분");
                    tvInv.setTextColor(Color.parseColor("#E67E22"));
                    tvInv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                    tvInv.setPadding(dpToPx(4), dpToPx(4), 0, dpToPx(4));
                    gridWrap.addView(tvInv);
                }

                // 각 행 파싱: "구분레이블:c1,c2,...c20"
                String[] rowArr = rowsStr.split("~");

                // 헤더: 구분 | 기점 | 종점 반복 (최대 10쌍)
                // 열 수 파악
                int maxCols = 0;
                for (String row : rowArr) {
                    int ci = row.indexOf(':');
                    if (ci < 0) continue;
                    String[] cs = row.substring(ci+1).split(",", -1);
                    int nonEmpty = 0;
                    for (String c : cs) if (!c.trim().isEmpty()) nonEmpty = cs.length;
                    if (nonEmpty > maxCols) maxCols = nonEmpty;
                }
                maxCols = Math.min(maxCols, 20);
                // 실제 데이터 있는 최대 열 찾기
                int lastDataCol = 0;
                for (String row : rowArr) {
                    int ci = row.indexOf(':');
                    if (ci < 0) continue;
                    String[] cs = row.substring(ci+1).split(",", -1);
                    for (int c = cs.length-1; c >= 0; c--) {
                        if (!cs[c].trim().isEmpty()) { if(c>lastDataCol) lastDataCol=c; break; }
                    }
                }
                int visibleCols = Math.min(lastDataCol+1, 20);

                // 열 너비 계산
                int cellW = dpToPx(42);
                int labelW = dpToPx(50);

                // 열 쌍 수 (구암역/보훈병원 반복)
                int pairCount = (visibleCols + 1) / 2;

                // 헤더1: 구분 | 1 | 2 | 3 ...
                LinearLayout hdr1 = new LinearLayout(PinActivity.this);
                hdr1.setOrientation(LinearLayout.HORIZONTAL);
                hdr1.setBackgroundColor(Color.parseColor("#00ACC1")); // 청록
                addCell(hdr1, "구분", labelW, dpToPx(22), Color.WHITE, fs(10), true);
                for (int p = 0; p < pairCount; p++) {
                    // 쌍 헤더 (숫자, 2열 span)
                    TextView tvH = new TextView(PinActivity.this);
                    tvH.setText(String.valueOf(p+1));
                    tvH.setTextColor(Color.WHITE);
                    tvH.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                    tvH.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvH.setGravity(Gravity.CENTER);
                    tvH.setWidth(cellW * 2);
                    tvH.setPadding(0, dpToPx(3), 0, dpToPx(3));
                    hdr1.addView(tvH);
                }
                gridWrap.addView(hdr1);

                // 헤더2: 구분 | 기점 | 종점 반복
                LinearLayout hdr2 = new LinearLayout(PinActivity.this);
                hdr2.setOrientation(LinearLayout.HORIZONTAL);
                hdr2.setBackgroundColor(Color.parseColor("#B2EBF2"));
                addCell(hdr2, "", labelW, dpToPx(20), Color.parseColor("#555555"), fs(10), false);
                for (int p = 0; p < pairCount; p++) {
                    addCell(hdr2, data[0], cellW, dpToPx(20), Color.parseColor("#0984E3"), fs(9), false);
                    addCell(hdr2, data[1], cellW, dpToPx(20), Color.parseColor("#E74C3C"), fs(9), false);
                }
                gridWrap.addView(hdr2);

                // 데이터 행
                for (int ri = 0; ri < rowArr.length; ri++) {
                    String rowLine = rowArr[ri];
                    int ci = rowLine.indexOf(':');
                    if (ci < 0) continue;
                    String label = rowLine.substring(0, ci).trim();
                    String[] cs = rowLine.substring(ci+1).split(",", -1);
                    if (label.isEmpty()) continue;

                    // 빈 행 스킵 - 모든 열이 비어있으면 표시 안 함
                    boolean hasAnyData = false;
                    for (int c = 0; c < cs.length && c < visibleCols; c++) {
                        if (!cs[c].trim().isEmpty()) { hasAnyData = true; break; }
                    }
                    if (!hasAnyData) continue;

                    // 이 행의 기점 출발 시간 (짝수 인덱스) 중 현재 시간 이후 첫 번째
                    boolean hasNext = false;
                    for (int c = 0; c < cs.length && c < visibleCols; c+=2) {
                        String t = cs[c].trim();
                        if (t.length() == 5) {
                            try {
                                int h=Integer.parseInt(t.substring(0,2)), m=Integer.parseInt(t.substring(3,5));
                                if (h*60+m >= nowMinBg) { hasNext = true; break; }
                            } catch(Exception ig){}
                        }
                    }

                    LinearLayout dataRow = new LinearLayout(PinActivity.this);
                    dataRow.setOrientation(LinearLayout.HORIZONTAL);
                    dataRow.setBackgroundColor((ri%2==0) ? Color.WHITE : Color.parseColor("#FAFAFA"));

                    // 구분 레이블
                    TextView tvLabel = new TextView(PinActivity.this);
                    tvLabel.setText(label);
                    tvLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(9));
                    tvLabel.setTextColor(Color.parseColor("#333333"));
                    tvLabel.setGravity(Gravity.CENTER);
                    tvLabel.setWidth(labelW);
                    tvLabel.setPadding(dpToPx(2), dpToPx(4), dpToPx(2), dpToPx(4));
                    dataRow.addView(tvLabel);

                    // 시간 셀들
                    for (int c = 0; c < visibleCols; c++) {
                        String t = c < cs.length ? cs[c].trim() : "";
                        boolean isSrc = (c % 2 == 0); // 짝수=기점, 홀수=종점

                        TextView tvCell = new TextView(PinActivity.this);
                        tvCell.setText(t.isEmpty() ? "" : t);
                        tvCell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
                        tvCell.setGravity(Gravity.CENTER);
                        tvCell.setWidth(cellW);
                        tvCell.setPadding(dpToPx(1), dpToPx(3), dpToPx(1), dpToPx(3));

                        // 현재 시간 이후 첫 번째 → 노란 강조
                        if (!t.isEmpty() && isSrc) {
                            try {
                                int h=Integer.parseInt(t.substring(0,2)), m=Integer.parseInt(t.substring(3,5));
                                if (h*60+m >= nowMinBg) {
                                    tvCell.setBackgroundColor(Color.parseColor("#FFF176"));
                                    tvCell.setTypeface(null, android.graphics.Typeface.BOLD);
                                    tvCell.setTextColor(Color.parseColor("#D32F2F"));
                                } else {
                                    tvCell.setTextColor(Color.parseColor("#AAAAAA"));
                                }
                            } catch(Exception ig){ tvCell.setTextColor(Color.parseColor("#333333")); }
                        } else if (!t.isEmpty()) {
                            try {
                                int h=Integer.parseInt(t.substring(0,2)), m=Integer.parseInt(t.substring(3,5));
                                tvCell.setTextColor(h*60+m < nowMinBg ? Color.parseColor("#CCCCCC") : Color.parseColor("#555555"));
                            } catch(Exception ig){ tvCell.setTextColor(Color.parseColor("#555555")); }
                        } else {
                            tvCell.setTextColor(Color.TRANSPARENT);
                        }
                        dataRow.addView(tvCell);
                    }
                    gridWrap.addView(dataRow);

                    // 구분선
                    android.view.View div = new android.view.View(PinActivity.this);
                    div.setBackgroundColor(Color.parseColor("#EEEEEE"));
                    div.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
                    gridWrap.addView(div);
                }
            }
        };

        // 헬퍼: 셀 TextView 추가
        final TextView[] fTabs = dayTabs;
        for (int i = 0; i < 3; i++) {
            final int di = i;
            dayTabs[i] = new TextView(this);
            dayTabs[i].setText(dayLabels[i]);
            dayTabs[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
            dayTabs[i].setGravity(Gravity.CENTER);
            dayTabs[i].setPadding(dpToPx(6), dpToPx(7), dpToPx(6), dpToPx(7));
            LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tbLp.setMargins(i == 0 ? 0 : dpToPx(4), 0, 0, 0);
            dayTabs[i].setLayoutParams(tbLp);
            dayTabs[i].setOnClickListener(vt -> {
                curDay[0] = di;
                for (int j = 0; j < 3; j++) {
                    boolean sel = j == di;
                    android.graphics.drawable.GradientDrawable tbg =
                            new android.graphics.drawable.GradientDrawable();
                    tbg.setColor(sel ? Color.parseColor("#0984E3") : Color.parseColor("#F0F0F0"));
                    tbg.setCornerRadius(dpToPx(8));
                    fTabs[j].setBackground(tbg);
                    fTabs[j].setTextColor(sel ? Color.WHITE : Color.parseColor("#555555"));
                }
                buildGrid.run();
            });
            // 초기 스타일
            android.graphics.drawable.GradientDrawable tbg0 =
                    new android.graphics.drawable.GradientDrawable();
            tbg0.setColor(i == 0 ? Color.parseColor("#0984E3") : Color.parseColor("#F0F0F0"));
            tbg0.setCornerRadius(dpToPx(8));
            dayTabs[i].setBackground(tbg0);
            dayTabs[i].setTextColor(i == 0 ? Color.WHITE : Color.parseColor("#555555"));
            dayTabRow.addView(dayTabs[i]);
        }

        root.addView(dayTabRow);
        root.addView(sv);
        buildGrid.run();

        // 닫기 버튼
        TextView tvClose = new TextView(this);
        tvClose.setText("닫기");
        tvClose.setGravity(Gravity.CENTER);
        tvClose.setTextColor(Color.parseColor("#888888"));
        tvClose.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.setMargins(0, dpToPx(10), 0, 0);
        tvClose.setLayoutParams(closeLp);
        tvClose.setOnClickListener(vc -> dlg.dismiss());
        root.addView(tvClose);

        dlg.setContentView(root);
        dlg.show();
    }

    private String jsonVal(String obj, String key) {
        String k = "\"" + key + "\":\"";
        int s = obj.indexOf(k);
        if (s < 0) return "";
        s += k.length();
        int e = obj.indexOf('"', s);
        return e < 0 ? "" : obj.substring(s, e);
    }

    /** 오너 전용: 대전 정류장 전체 수집 → Drive 업로드 */
    private void buildAndUploadStopDb(Runnable onDone, ProgressCallback onProgress) {
        new Thread(() -> {
            try {
                // ── STEP 1: routeDbList에서 노선 목록 가져오기 ──────────────
                // SharedPreferences에서 직접 읽기 (타이밍 문제 방지)
                java.util.List<String[]> routeSnap = new java.util.ArrayList<>();
                if (routeDbList != null && !routeDbList.isEmpty()) {
                    routeSnap = new java.util.ArrayList<>(routeDbList);
                } else {
                    String rawRoute = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE)
                            .getString(BUS_DB_KEY, "");
                    if (!rawRoute.isEmpty()) {
                        for (String line : rawRoute.split(";")) {
                            String[] parts = line.split("\\|", -1);
                            if (parts.length >= 2) routeSnap.add(parts);
                        }
                    }
                }
                android.util.Log.d("BusDB", "buildStop: routeSnap=" + routeSnap.size());

                // ── STEP 2: 모든 노선의 정류장 목록 수집 → nodeno→routes 역인덱스 ──
                // nodeno → Set<routeNo>
                java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> nodeNoRouteMap =
                        new java.util.concurrent.ConcurrentHashMap<>();
                // nodeId → [nm, no] (정류장 정보)
                java.util.concurrent.ConcurrentHashMap<String, String[]> nodeInfoMap =
                        new java.util.concurrent.ConcurrentHashMap<>();

                int totalR = routeSnap.size();
                java.util.concurrent.ExecutorService poolR =
                        java.util.concurrent.Executors.newFixedThreadPool(4);
                java.util.concurrent.atomic.AtomicInteger doneR =
                        new java.util.concurrent.atomic.AtomicInteger(0);

                for (String[] rd : routeSnap) {
                    final String fRid = rd[0];
                    final String fRno = rd.length > 1 ? rd[1] : "";
                    poolR.submit(() -> {
                        try {
                            String url = BUS_BASE2 + "BusRouteInfoInqireService/getRouteAcctoThrghSttnList"
                                    + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                                    + "&routeId=" + fRid + "&numOfRows=200&pageNo=1&_type=xml";
                            String xml = httpGet(url);
                            for (String item : xml.split("<item>")) {
                                if (!item.contains("<nodeid>")) continue;
                                String nid = tag(item, "nodeid");
                                String nnm = tag(item, "nodenm");
                                String nno = tag(item, "nodeno");
                                if (nid.isEmpty() || nno.isEmpty()) continue;
                                // 정류장 정보 저장
                                nodeInfoMap.putIfAbsent(nid, new String[]{nid, nnm, nno});
                                // nodeno → 노선번호 매핑
                                nodeNoRouteMap.computeIfAbsent(nno,
                                        k -> java.util.Collections.synchronizedSet(
                                                new java.util.TreeSet<>())).add(fRno);
                            }
                        } catch (Exception ignored) {}
                        int d = doneR.incrementAndGet();
                        final int pct = (int)(d * 49.0 / Math.max(totalR, 1));
                        if (onProgress != null) runOnUiThread(() -> onProgress.onProgress(Math.min(pct, 49)));
                    });
                }
                poolR.shutdown();
                poolR.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES);
                android.util.Log.d("BusDB", "nodeInfoMap=" + nodeInfoMap.size() + " nodeNoRouteMap=" + nodeNoRouteMap.size());

                // ── STEP 3: getSttnNoList로 추가 정류장 수집 (노선 미경유 정류장 보완) ──
                int[] choIdxArr = {0,2,3,5,6,7,9,11,12,14,15,16,17,18};
                java.util.List<String> allPrefixes = new java.util.ArrayList<>();
                for (int ci : choIdxArr)
                    for (int ji = 0; ji < 21; ji++)
                        allPrefixes.add(String.valueOf((char)(0xAC00 + ci*21*28 + ji*28)));
                for (int d = 0; d < 10; d++) allPrefixes.add(String.valueOf(d));

                int totalP = allPrefixes.size();
                java.util.concurrent.ExecutorService poolP =
                        java.util.concurrent.Executors.newFixedThreadPool(4);
                java.util.concurrent.atomic.AtomicInteger doneP =
                        new java.util.concurrent.atomic.AtomicInteger(0);

                for (String prefix : allPrefixes) {
                    poolP.submit(() -> {
                        int page = 1;
                        while (true) {
                            try {
                                String url = BUS_BASE2 + "BusSttnInfoInqireService/getSttnNoList"
                                        + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                                        + "&nodeNm=" + java.net.URLEncoder.encode(prefix, "UTF-8")
                                        + "&numOfRows=100&pageNo=" + page + "&_type=xml";
                                String xml = httpGet(url);
                                int cnt = 0;
                                for (String item : xml.split("<item>")) {
                                    if (!item.contains("<nodeid>")) continue;
                                    String nid = tag(item, "nodeid");
                                    String nnm = tag(item, "nodenm");
                                    String nno = tag(item, "nodeno");
                                    if (!nid.isEmpty())
                                        nodeInfoMap.putIfAbsent(nid, new String[]{nid, nnm, nno});
                                    cnt++;
                                }
                                if (cnt < 100) break;
                                page++;
                                if (page > 20) break;
                            } catch (Exception ignored) { break; }
                        }
                        int d = doneP.incrementAndGet();
                        final int pct = 50 + (int)(d * 49.0 / Math.max(totalP, 1));
                        if (onProgress != null) runOnUiThread(() -> onProgress.onProgress(Math.min(pct, 99)));
                    });
                }
                poolP.shutdown();
                poolP.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);

                // ── STEP 4: JSON 조립 ──────────────────────────────────────
                StringBuilder jsonSb = new StringBuilder("[");
                boolean first = true;
                for (String[] info : nodeInfoMap.values()) {
                    String nid = info[0], nnm = info[1], nno = info[2];
                    // nodeno로 routes 조회
                    java.util.Set<String> rnoSet = nodeNoRouteMap.get(nno);
                    String routes = "";
                    if (rnoSet != null && !rnoSet.isEmpty()) {
                        routes = String.join(",", rnoSet);
                    }
                    if (!first) jsonSb.append(',');
                    jsonSb.append("{\"id\":\"").append(nid)
                            .append("\",\"nm\":\"").append(nnm.replace("\"","\\\""))
                            .append("\",\"no\":\"").append(nno)
                            .append("\",\"routes\":\"").append(routes).append("\"}");
                    first = false;
                }
                jsonSb.append("]");
                android.util.Log.d("BusDB", "JSON length=" + jsonSb.length());

                String json = jsonSb.toString();
                new DriveUploadHelper(this).uploadFileSync(json, STOP_DB_FILE);
                getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE).edit()
                        .putString("stop_json_cache", json).apply();
                saveStopDb(json); // 내부 파일에도 저장
                loadStopJsonToMemory(json);

                if (onProgress != null) runOnUiThread(() -> onProgress.onProgress(100));
                if (onDone != null) runOnUiThread(onDone);
            } catch (Exception e) {
                android.util.Log.e("BusDB", "buildStop error: " + e.getMessage());
                if (onDone != null) runOnUiThread(onDone);
            }
        }).start();
    }

    /** 로컬 노선 DB에서 검색 (메모리 우선) */
    private java.util.List<String[]> busSearchLocal(String keyword) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        // 메모리 DB 우선
        if (routeDbList != null) {
            for (String[] p : routeDbList) {
                if (p[1].startsWith(keyword)) result.add(p);
            }
            return result;
        }
        // 폴백: SharedPreferences에서 직접 읽기
        String raw = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE).getString(BUS_DB_KEY, "");
        if (raw.isEmpty()) return result;
        for (String line : raw.split(";")) {
            String[] p = line.split("\\|", -1);
            if (p.length < 5) continue;
            if (p[1].startsWith(keyword)) result.add(p);
        }
        return result;
    }

    /** 로컬 정류장 DB에서 검색 (메모리 우선) */
    private java.util.List<String[]> stopSearchLocal(String keyword) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String kw = keyword.toLowerCase();
        // 메모리 DB 우선
        if (stopDbList != null) {
            for (String[] p : stopDbList) {
                if (p[1].toLowerCase().contains(kw)) {
                    result.add(p);
                    if (result.size() >= 30) break;
                }
            }
            return result;
        }
        // 폴백: SharedPreferences에서 직접 읽기
        String raw = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE).getString("all_stops", "");
        if (raw.isEmpty()) return result;
        for (String line : raw.split(";")) {
            String[] p = line.split("\\|", -1);
            if (p.length < 3) continue;
            if (p[1].toLowerCase().contains(kw)) {
                result.add(p);
                if (result.size() >= 30) break;
            }
        }
        return result;
    }

    /** 대전 전체 노선 + 정류장 DB 다운로드 후 로컬 저장 */
    interface ProgressCallback { void onProgress(int pct); }

    private void downloadBusRouteDb(Runnable onDone) {
        downloadBusRouteDb(onDone, null);
    }

    private void downloadBusRouteDb(Runnable onDone, ProgressCallback onProgress) {
        new Thread(() -> {
            try {
                // ── 전체 개수 미리 파악 ────────────────────────
                // 노선: 첫 페이지 totalCount
                String firstRouteXml = httpGet(BUS_BASE2 + "BusRouteInfoInqireService/getRouteNoList"
                        + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                        + "&routeNo=&numOfRows=1&pageNo=1&_type=xml");
                int totalRoute = 0;
                try { totalRoute = Integer.parseInt(tag(firstRouteXml,"totalCount")); } catch(Exception ig){}
                // 정류장 총계는 prefix 분할 방식으로 계산하므로 별도 API 호출 불필요
                final int grandTotal = Math.max(totalRoute, 1);

                // ① 노선 목록
                StringBuilder sbRoute = new StringBuilder();
                int page = 1, doneRoute = 0;
                while (true) {
                    String url = BUS_BASE2 + "BusRouteInfoInqireService/getRouteNoList"
                            + "?serviceKey=" + BUS_KEY + "&cityCode=" + BUS_CITY
                            + "&routeNo=&numOfRows=100&pageNo=" + page + "&_type=xml";
                    String xml = httpGet(url);
                    int count = 0;
                    for (String item : xml.split("<item>")) {
                        if (!item.contains("<routeid>")) continue;
                        if (sbRoute.length() > 0) sbRoute.append(";");
                        sbRoute.append(tag(item,"routeid")).append("|")
                                .append(tag(item,"routeno")).append("|")
                                .append(tag(item,"startnodenm")).append("|")
                                .append(tag(item,"endnodenm")).append("|")
                                .append(tag(item,"routetp"));
                        count++;
                    }
                    doneRoute += count;
                    final int pct1 = (int)(doneRoute * 100.0 / Math.max(totalRoute, 1));
                    if (onProgress != null) runOnUiThread(() -> onProgress.onProgress(Math.min(pct1, 99)));
                    if (count < 100) break;
                    page++;
                    if (page > 20) break;
                }


                String today = new java.text.SimpleDateFormat("yyyyMMdd",
                        java.util.Locale.getDefault()).format(new java.util.Date());
                getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE).edit()
                        .putString(BUS_DB_KEY, sbRoute.toString())
                        .putString(BUS_DB_VER,  today)
                        .putInt(BUS_DB_SCHEMA,  BUS_DB_SCHEMA_VER)
                        .apply();
                if (onProgress != null) runOnUiThread(() -> onProgress.onProgress(100));
                if (onDone != null) runOnUiThread(onDone);
            } catch (Exception ignored) {
                if (onDone != null) runOnUiThread(onDone);
            }
        }).start();
    }

    /** 로컬 DB 존재 여부 및 날짜 확인 */
    private boolean busDbNeedsUpdate() {
        android.content.SharedPreferences p = getSharedPreferences(BUS_DB_PREF, MODE_PRIVATE);
        if (!p.contains(BUS_DB_KEY) || p.getString(BUS_DB_KEY,"").isEmpty()) return true;
        if (p.getInt(BUS_DB_SCHEMA, 0) < BUS_DB_SCHEMA_VER) return true;
        String saved = p.getString(BUS_DB_VER, "");
        String today = new java.text.SimpleDateFormat("yyyyMMdd",
                java.util.Locale.getDefault()).format(new java.util.Date());
        return !saved.equals(today);
    }

    // ── 즐겨찾기 렌더링 ────────────────────────────────────
    private LinearLayout busFavSection;   // 검색 화면에서 참조용

    private void refreshBusFavorites(LinearLayout favSection, LinearLayout resultContainer) {
        busFavSection = favSection;
        favSection.removeAllViews();
        // 화면 이동 없이 즐겨찾기 섹션만 갱신 (busSearchArea/busFixedHeader 건드리지 않음)
        android.content.SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        // 키: fav_stop_routeId_nodeId (boolean=true인 것만)
        java.util.List<String> favKeys = new java.util.ArrayList<>();
        // 키: fav_route_routeId_direction (노선 즐겨찾기)
        java.util.List<String> favRouteKeys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("fav_stop_") && !k.contains("_name_")
                    && !k.contains("_no_") && !k.contains("_route_")) {
                if (Boolean.TRUE.equals(e.getValue()))
                    favKeys.add(k.substring("fav_stop_".length()));
            } else if (k.startsWith("fav_route_") && !k.contains("_no_")
                    && !k.contains("_dir_") && !k.contains("_id_") && !k.contains("_dirkey_")) {
                if (Boolean.TRUE.equals(e.getValue()))
                    favRouteKeys.add(k.substring("fav_route_".length()));
            }
        }
        if (favKeys.isEmpty() && favRouteKeys.isEmpty()) return;

        // 즐겨찾기 타이틀 (숨김)

        // ── 노선 즐겨찾기 카드 (2열 그리드) ──────────────────
        LinearLayout routeGrid = new LinearLayout(this);
        routeGrid.setOrientation(LinearLayout.VERTICAL);
        routeGrid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout routeRow = null;
        int routeColIdx = 0;

        for (String rKey : favRouteKeys) {
            String rNo     = prefs.getString("fav_route_no_"     + rKey, rKey);
            String rDir    = prefs.getString("fav_route_dir_"    + rKey, "");
            String rId     = prefs.getString("fav_route_id_"     + rKey, "");
            String rDirKey = prefs.getString("fav_route_dirkey_" + rKey, "forward");
            String rMemo   = prefs.getString("fav_route_memo_"   + rKey, "");

            // 새 행 시작
            if (routeColIdx % 2 == 0) {
                routeRow = new LinearLayout(this);
                routeRow.setOrientation(LinearLayout.HORIZONTAL);
                routeRow.setWeightSum(2f);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dpToPx(8));
                routeRow.setLayoutParams(rowLp);
                routeGrid.addView(routeRow);
            }

            LinearLayout rCard = new LinearLayout(this);
            rCard.setOrientation(LinearLayout.VERTICAL);
            rCard.setBackground(makeShadowCardDrawable("#FFFFFF", 10, 3));
            rCard.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            rCard.setPadding(dpToPx(12), dpToPx(14), dpToPx(12), dpToPx(12));
            LinearLayout.LayoutParams rCardLp = new LinearLayout.LayoutParams(0, dpToPx(110), 1f);
            rCardLp.setMargins(0, 0, routeColIdx % 2 == 0 ? dpToPx(6) : 0, 0);
            rCard.setLayoutParams(rCardLp);

            // 오른쪽 위: 버스이미지 + 설정 + 알림 버튼 행
            final String fRKey = rKey;
            // routeType 가져오기 (bus_cache에서)
            String rTpCached = getSharedPreferences("bus_cache", MODE_PRIVATE)
                    .getString("route_" + rId + "_rTp", "");
            String[] rBadge = routeTypeBadge(rTpCached);
            String rTypeColor = rBadge[1];
            int rTypeColorInt = Color.parseColor(rTypeColor);

            LinearLayout iconBtnRow = new LinearLayout(this);
            iconBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            iconBtnRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            iconBtnRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 버스 이미지 (설정 왼쪽)
            android.widget.ImageView ivFavBus = new android.widget.ImageView(this);
            android.graphics.Bitmap favBusBmp = getBusIconColor(0xFF000000 | (rTypeColorInt & 0xFFFFFF));
            if (favBusBmp != null) ivFavBus.setImageBitmap(favBusBmp);
            LinearLayout.LayoutParams favBusLp = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
            favBusLp.setMargins(0, 0, dpToPx(6), 0);
            favBusLp.gravity = Gravity.CENTER_VERTICAL;
            ivFavBus.setLayoutParams(favBusLp);
            ivFavBus.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            // 버스 이미지는 왼쪽 공간 차지용 - Gravity.START
            LinearLayout busImgWrapper = new LinearLayout(this);
            busImgWrapper.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            busImgWrapper.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            busImgWrapper.addView(ivFavBus);
            iconBtnRow.addView(busImgWrapper);

            // 설정 버튼 (왼쪽)
            TextView tvGear = new TextView(this);
            tvGear.setText("설정");
            tvGear.setTextColor(Color.parseColor("#888888"));
            tvGear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvGear.setTypeface(null, android.graphics.Typeface.BOLD);
            tvGear.setGravity(Gravity.CENTER);
            tvGear.setPadding(dpToPx(9), dpToPx(4), dpToPx(9), dpToPx(4));
            android.graphics.drawable.GradientDrawable gearBg = new android.graphics.drawable.GradientDrawable();
            gearBg.setColor(Color.parseColor("#F0F0F0"));
            gearBg.setCornerRadius(dpToPx(6));
            gearBg.setStroke(dpToPx(1), Color.parseColor("#CCCCCC"));
            tvGear.setBackground(gearBg);
            LinearLayout.LayoutParams gearLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            gearLp.setMargins(0, 0, dpToPx(5), 0);
            tvGear.setLayoutParams(gearLp);
            tvGear.setOnClickListener(v2 -> {
                // ── 커스텀 수정/삭제/취소 카드 다이얼로그 ──
                android.app.Dialog settingDlg = new android.app.Dialog(this,
                        android.R.style.Theme_Material_Light_Dialog);
                LinearLayout settingLayout = new LinearLayout(this);
                settingLayout.setOrientation(LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable settingCardBg = new android.graphics.drawable.GradientDrawable();
                settingCardBg.setColor(Color.WHITE);
                settingCardBg.setCornerRadius(dpToPx(20));
                settingLayout.setBackground(settingCardBg);
                settingLayout.setPadding(dpToPx(20), dpToPx(22), dpToPx(20), dpToPx(20));

                // 제목
                TextView tvSettingTitle = new TextView(this);
                tvSettingTitle.setText(rNo + "번  " + rDir);
                tvSettingTitle.setTextColor(Color.parseColor("#0984E3"));
                tvSettingTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
                tvSettingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvSettingTitle.setGravity(Gravity.CENTER);
                tvSettingTitle.setShadowLayer(4f, 0f, 1.5f, 0x40000000);
                LinearLayout.LayoutParams stTitleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                stTitleLp.setMargins(0, 0, 0, dpToPx(6));
                tvSettingTitle.setLayoutParams(stTitleLp);
                settingLayout.addView(tvSettingTitle);

                // 메모 표시 (있는 경우)
                String curMemo = prefs.getString("fav_route_memo_" + fRKey, "");
                if (!curMemo.isEmpty()) {
                    TextView tvCurMemo = new TextView(this);
                    tvCurMemo.setText(curMemo);
                    tvCurMemo.setTextColor(Color.parseColor("#888888"));
                    tvCurMemo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                    tvCurMemo.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams cmLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    cmLp.setMargins(0, 0, 0, dpToPx(16));
                    tvCurMemo.setLayoutParams(cmLp);
                    settingLayout.addView(tvCurMemo);
                } else {
                    View spacer = new View(this);
                    spacer.setLayoutParams(new LinearLayout.LayoutParams(0, dpToPx(10)));
                    // 구분선
                    View divSetting = new View(this);
                    divSetting.setBackgroundColor(Color.parseColor("#EEEEEE"));
                    LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                    divLp.setMargins(0, 0, 0, dpToPx(16));
                    divSetting.setLayoutParams(divLp);
                    settingLayout.addView(divSetting);
                }

                // 수정 버튼
                TextView btnEdit = new TextView(this);
                btnEdit.setText("수정");
                btnEdit.setTextColor(Color.WHITE);
                btnEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnEdit.setTypeface(null, android.graphics.Typeface.BOLD);
                btnEdit.setGravity(Gravity.CENTER);
                btnEdit.setPadding(0, dpToPx(14), 0, dpToPx(14));
                android.graphics.drawable.GradientDrawable editBg = new android.graphics.drawable.GradientDrawable();
                editBg.setColor(Color.parseColor("#5BA9F0"));
                editBg.setCornerRadius(dpToPx(12));
                btnEdit.setBackground(editBg);
                btnEdit.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                editLp.setMargins(0, 0, 0, dpToPx(10));
                btnEdit.setLayoutParams(editLp);
                btnEdit.setOnClickListener(vv -> {
                    settingDlg.dismiss();
                    // 즐겨찾기 추가 다이얼로그와 동일한 메모 수정창
                    String existingMemo = prefs.getString("fav_route_memo_" + fRKey, "");
                    android.app.Dialog memoDlg = new android.app.Dialog(this,
                            android.R.style.Theme_Material_Light_Dialog);
                    LinearLayout memoLayout = new LinearLayout(this);
                    memoLayout.setOrientation(LinearLayout.VERTICAL);
                    android.graphics.drawable.GradientDrawable memoCardBg = new android.graphics.drawable.GradientDrawable();
                    memoCardBg.setColor(Color.WHITE);
                    memoCardBg.setCornerRadius(dpToPx(16));
                    memoLayout.setBackground(memoCardBg);
                    memoLayout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));
                    // 제목
                    TextView tvMTitle = new TextView(this);
                    tvMTitle.setText(rNo + "번  " + rDir);
                    tvMTitle.setTextColor(Color.parseColor("#0984E3"));
                    tvMTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
                    tvMTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvMTitle.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    mtLp.setMargins(0, 0, 0, dpToPx(14));
                    tvMTitle.setLayoutParams(mtLp);
                    memoLayout.addView(tvMTitle);
                    // 구분선
                    View mDiv = new View(this);
                    mDiv.setBackgroundColor(Color.parseColor("#EEEEEE"));
                    LinearLayout.LayoutParams mDivLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                    mDivLp.setMargins(0, 0, 0, dpToPx(14));
                    mDiv.setLayoutParams(mDivLp);
                    memoLayout.addView(mDiv);
                    // 메모 라벨
                    TextView tvMLabel = new TextView(this);
                    tvMLabel.setText("메모 (선택)");
                    tvMLabel.setTextColor(Color.parseColor("#555555"));
                    tvMLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                    LinearLayout.LayoutParams mlLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    mlLp.setMargins(0, 0, 0, dpToPx(6));
                    tvMLabel.setLayoutParams(mlLp);
                    memoLayout.addView(tvMLabel);
                    // 메모 입력창
                    android.widget.EditText etMemo2 = new android.widget.EditText(this);
                    setBlackCursor(etMemo2);
                    etMemo2.setHint("예) 출근길, 집앞 정류장");
                    etMemo2.setText(existingMemo);
                    etMemo2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(13));
                    etMemo2.setSingleLine(true);
                    android.graphics.drawable.GradientDrawable memoBg2 = new android.graphics.drawable.GradientDrawable();
                    memoBg2.setColor(Color.parseColor("#F8F8F8"));
                    memoBg2.setCornerRadius(dpToPx(10));
                    memoBg2.setStroke(dpToPx(1), Color.parseColor("#DDDDDD"));
                    etMemo2.setBackground(memoBg2);
                    etMemo2.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
                    LinearLayout.LayoutParams etLp2 = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    etLp2.setMargins(0, 0, 0, dpToPx(20));
                    etMemo2.setLayoutParams(etLp2);
                    memoLayout.addView(etMemo2);
                    // 버튼 행
                    LinearLayout mBtnRow = new LinearLayout(this);
                    mBtnRow.setOrientation(LinearLayout.HORIZONTAL);
                    mBtnRow.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    TextView mBtnCancel = new TextView(this);
                    mBtnCancel.setText("취소");
                    mBtnCancel.setTextColor(Color.parseColor("#888888"));
                    mBtnCancel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    mBtnCancel.setTypeface(null, android.graphics.Typeface.BOLD);
                    mBtnCancel.setGravity(Gravity.CENTER);
                    mBtnCancel.setPadding(0, dpToPx(13), 0, dpToPx(13));
                    android.graphics.drawable.GradientDrawable mCancelBg = new android.graphics.drawable.GradientDrawable();
                    mCancelBg.setColor(Color.parseColor("#F0F0F0"));
                    mCancelBg.setCornerRadius(dpToPx(10));
                    mBtnCancel.setBackground(mCancelBg);
                    LinearLayout.LayoutParams mCancelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    mCancelLp.setMargins(0, 0, dpToPx(8), 0);
                    mBtnCancel.setLayoutParams(mCancelLp);
                    mBtnCancel.setOnClickListener(vvv -> memoDlg.dismiss());
                    mBtnRow.addView(mBtnCancel);
                    TextView mBtnOk = new TextView(this);
                    mBtnOk.setText("저장");
                    mBtnOk.setTextColor(Color.WHITE);
                    mBtnOk.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    mBtnOk.setTypeface(null, android.graphics.Typeface.BOLD);
                    mBtnOk.setGravity(Gravity.CENTER);
                    mBtnOk.setPadding(0, dpToPx(13), 0, dpToPx(13));
                    android.graphics.drawable.GradientDrawable mOkBg = new android.graphics.drawable.GradientDrawable();
                    mOkBg.setColor(Color.parseColor("#5BA9F0"));
                    mOkBg.setCornerRadius(dpToPx(10));
                    mBtnOk.setBackground(mOkBg);
                    mBtnOk.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    mBtnOk.setOnClickListener(vvv -> {
                        memoDlg.dismiss();
                        prefs.edit().putString("fav_route_memo_" + fRKey, etMemo2.getText().toString().trim()).apply();
                        android.widget.Toast.makeText(this, "메모가 저장되었습니다", android.widget.Toast.LENGTH_SHORT).show();
                        refreshBusFavorites(favSection, resultContainer);
                    });
                    mBtnRow.addView(mBtnOk);
                    memoLayout.addView(mBtnRow);
                    memoDlg.setContentView(memoLayout);
                    memoDlg.setCancelable(true);
                    if (memoDlg.getWindow() != null) {
                        memoDlg.getWindow().setLayout(
                                (int)(getResources().getDisplayMetrics().widthPixels * 0.85),
                                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                        memoDlg.getWindow().setBackgroundDrawable(
                                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                    }
                    memoDlg.show();
                    etMemo2.post(() -> {
                        etMemo2.requestFocus();
                        etMemo2.setSelection(etMemo2.getText().length());
                    });
                });
                settingLayout.addView(btnEdit);

                // 삭제 버튼
                TextView btnDel = new TextView(this);
                btnDel.setText("삭제");
                btnDel.setTextColor(Color.WHITE);
                btnDel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnDel.setTypeface(null, android.graphics.Typeface.BOLD);
                btnDel.setGravity(Gravity.CENTER);
                btnDel.setPadding(0, dpToPx(14), 0, dpToPx(14));
                android.graphics.drawable.GradientDrawable delBg = new android.graphics.drawable.GradientDrawable();
                delBg.setColor(Color.parseColor("#E74C3C"));
                delBg.setCornerRadius(dpToPx(12));
                btnDel.setBackground(delBg);
                btnDel.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                delLp.setMargins(0, 0, 0, dpToPx(10));
                btnDel.setLayoutParams(delLp);
                btnDel.setOnClickListener(vv -> {
                    settingDlg.dismiss();
                    showConfirmDialog("삭제", rNo + "번 즐겨찾기 삭제",
                            "즐겨찾기에서 삭제하시겠습니까?", () -> {
                                prefs.edit().remove("fav_route_" + fRKey)
                                        .remove("fav_route_no_"     + fRKey)
                                        .remove("fav_route_dir_"    + fRKey)
                                        .remove("fav_route_id_"     + fRKey)
                                        .remove("fav_route_dirkey_" + fRKey)
                                        .remove("fav_route_memo_"   + fRKey).apply();
                                busFavDirty = true;
                                android.widget.Toast.makeText(this, rNo + "번 즐겨찾기 삭제",
                                        android.widget.Toast.LENGTH_SHORT).show();
                                refreshBusFavorites(favSection, resultContainer);
                            });
                });
                settingLayout.addView(btnDel);

                // 취소 버튼
                TextView btnCancel2 = new TextView(this);
                btnCancel2.setText("취소");
                btnCancel2.setTextColor(Color.parseColor("#888888"));
                btnCancel2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnCancel2.setTypeface(null, android.graphics.Typeface.BOLD);
                btnCancel2.setGravity(Gravity.CENTER);
                btnCancel2.setPadding(0, dpToPx(14), 0, dpToPx(14));
                android.graphics.drawable.GradientDrawable cancelBg2 = new android.graphics.drawable.GradientDrawable();
                cancelBg2.setColor(Color.parseColor("#F0F0F0"));
                cancelBg2.setCornerRadius(dpToPx(12));
                cancelBg2.setStroke(dpToPx(1), Color.parseColor("#CCCCCC"));
                btnCancel2.setBackground(cancelBg2);
                btnCancel2.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                btnCancel2.setOnClickListener(vv -> settingDlg.dismiss());
                settingLayout.addView(btnCancel2);

                settingDlg.setContentView(settingLayout);
                settingDlg.setCancelable(true);
                if (settingDlg.getWindow() != null) {
                    settingDlg.getWindow().setLayout(
                            (int)(getResources().getDisplayMetrics().widthPixels * 0.80),
                            android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                    settingDlg.getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                }
                settingDlg.show();
            });
            iconBtnRow.addView(tvGear);

            // 알림 버튼 (오른쪽)
            TextView tvBell = new TextView(this);
            tvBell.setText("알림");
            tvBell.setTextColor(Color.parseColor("#5BA9F0"));
            tvBell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvBell.setTypeface(null, android.graphics.Typeface.BOLD);
            tvBell.setGravity(Gravity.CENTER);
            tvBell.setPadding(dpToPx(9), dpToPx(4), dpToPx(9), dpToPx(4));
            android.graphics.drawable.GradientDrawable bellBg = new android.graphics.drawable.GradientDrawable();
            bellBg.setColor(Color.parseColor("#EBF5FB"));
            bellBg.setCornerRadius(dpToPx(6));
            bellBg.setStroke(dpToPx(1), Color.parseColor("#5BA9F0"));
            tvBell.setBackground(bellBg);
            tvBell.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tvBell.setOnClickListener(v2 -> android.widget.Toast.makeText(this,
                    rNo + "번 알림 (준비중)", android.widget.Toast.LENGTH_SHORT).show());
            iconBtnRow.addView(tvBell);
            rCard.addView(iconBtnRow);

            // 버스 번호
            TextView tvRNo = new TextView(this);
            tvRNo.setText(rNo + "번");
            tvRNo.setTextColor(Color.parseColor(rTypeColor));
            tvRNo.setSingleLine(true);
            tvRNo.setEllipsize(null);
            // 글자수에 따라 크기 조정
            {
                String rNoTxt = rNo + "번";
                if (rNoTxt.length() > 9) {
                    tvRNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                    tvRNo.setEllipsize(android.text.TextUtils.TruncateAt.END);
                } else if (rNoTxt.length() > 6) {
                    tvRNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                    tvRNo.setEllipsize(null);
                }
            }
            tvRNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(20));
            tvRNo.setShadowLayer(4f, 0f, 1.5f, 0x40000000);
            tvRNo.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams rNoLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rNoLp.setMargins(0, dpToPx(4), 0, 0);
            tvRNo.setLayoutParams(rNoLp);
            rCard.addView(tvRNo);

            // 방면 (메모 or 방향)
            String subText = rMemo.isEmpty() ? rDir : rMemo;
            if (!subText.isEmpty()) {
                TextView tvRSub = new TextView(this);
                tvRSub.setText(subText);
                tvRSub.setTextColor(Color.parseColor("#555555"));
                tvRSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                tvRSub.setSingleLine(true);
                tvRSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                subLp.setMargins(0, dpToPx(3), 0, 0);
                tvRSub.setLayoutParams(subLp);
                rCard.addView(tvRSub);
            }


            // 카드 탭 → 즉시 타임라인 (캐시 있으면 UI 스레드에서 바로 렌더링)
            final String fRId = rId, fRNo = rNo, fRDirKey = rDirKey;
            rCard.setOnClickListener(v2 -> {
                if (fRId.isEmpty()) return;
                busResultContainer.removeAllViews();
                if (busSearchArea  != null) busSearchArea.setVisibility(android.view.View.GONE);
                if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.GONE);
                if (busFixedHeader != null) { busFixedHeader.setVisibility(android.view.View.GONE); busFixedHeader.removeAllViews(); }
                android.view.inputmethod.InputMethodManager immF =
                        (android.view.inputmethod.InputMethodManager) getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (immF != null) immF.hideSoftInputFromWindow(resultContainer.getWindowToken(), 0);

                // 캐시에서 즉시 읽기
                android.content.SharedPreferences fc = getSharedPreferences("bus_cache", MODE_PRIVATE);
                String fcKey = "route_" + fRId;
                boolean hasCache = fc.contains(fcKey + "_startNm")
                        && !fc.getString(fcKey + "_stops", "").isEmpty();

                if (hasCache) {
                    // ── 캐시 HIT: UI 스레드에서 즉시 렌더링 ──
                    String sNm = fc.getString(fcKey+"_startNm","기점");
                    String eNm = fc.getString(fcKey+"_endNm","종점");
                    String sTm = fc.getString(fcKey+"_startTime","");
                    String eTm = fc.getString(fcKey+"_endTime","");
                    String inv = fc.getString(fcKey+"_interval","");
                    String rTp = fc.getString(fcKey+"_rTp","");
                    String stF = sTm.length()==4 ? sTm.substring(0,2)+":"+sTm.substring(2) : sTm;
                    String etF = eTm.length()==4 ? eTm.substring(0,2)+":"+eTm.substring(2) : eTm;
                    java.util.List<String[]> stops = new java.util.ArrayList<>();
                    for (String line : fc.getString(fcKey+"_stops","").split(";")) {
                        String[] p = line.split("\\|",-1);
                        if (p.length==4) stops.add(p);
                    }
                    if ("reverse".equals(fRDirKey)) java.util.Collections.reverse(stops);
                    // 즉시 표시 (운행대수 0, 버스위치 없음)
                    String fcTurnOrd = fc.getString(fcKey+"_turnOrd","");
                    renderBusTimeline(fRId, fRNo, fRDirKey, resultContainer,
                            sNm, eNm, stF, etF, inv, rTp,
                            0, new java.util.HashMap<>(), new java.util.HashSet<>(), stops, fcTurnOrd);
                    // 실시간만 백그라운드
                    final java.util.List<String[]> fStops = stops;
                    final String fSNm=sNm,fENm=eNm,fStF=stF,fEtF=etF,fInv=inv,fRTp2=rTp;
                    new Thread(() -> {
                        try {
                            String lcXml = httpGet(BUS_BASE2+"BusLcInfoInqireService/getRouteAcctoBusLcList"
                                    +"?serviceKey="+BUS_KEY+"&cityCode="+BUS_CITY
                                    +"&routeId="+fRId+"&numOfRows=50&pageNo=1&_type=xml");
                            int cnt=0;
                            try{cnt=Integer.parseInt(tag(lcXml,"totalCount"));}catch(Exception ig){}
                            java.util.Set<String> ordSet=new java.util.HashSet<>();
                            java.util.Map<String,String> vehMap=new java.util.HashMap<>();
                            for (String item:lcXml.split("<item>")) {
                                String ord=tag(item,"nodeord"),vno=tag(item,"vehicleno");
                                if(!ord.isEmpty()){ordSet.add(ord);if(!vno.isEmpty())vehMap.put(ord,vno);}
                            }
                            final int fCnt=cnt;
                            final java.util.Set<String> fOrd=ordSet;
                            final java.util.Map<String,String> fVeh=vehMap;
                            final String fFcTurnOrd = fc.getString(fcKey+"_turnOrd","");
                            runOnUiThread(()->renderBusTimeline(fRId,fRNo,fRDirKey,resultContainer,
                                    fSNm,fENm,fStF,fEtF,fInv,fRTp2,fCnt,fVeh,fOrd,fStops,fFcTurnOrd));
                        } catch(Exception ignored){}
                    }).start();
                } else {
                    // 캐시 없으면 기존 방식
                    busScreenLoadStops(fRId, fRNo, resultContainer, fRDirKey, "");
                }
            });
            if (routeRow != null) routeRow.addView(rCard);
            routeColIdx++;
        }
        // 홀수개면 빈 카드로 채우기
        if (routeColIdx % 2 == 1 && routeRow != null) {
            View empty = new View(this);
            LinearLayout.LayoutParams emLp = new LinearLayout.LayoutParams(0, 1, 1f);
            emLp.setMargins(0, 0, 0, 0);
            empty.setLayoutParams(emLp);
            routeRow.addView(empty);
        }
        if (!favRouteKeys.isEmpty()) favSection.addView(routeGrid);

        // ── 정류소 즐겨찾기 2열 그리드 ─────────────────────
        LinearLayout stopGrid = new LinearLayout(this);
        stopGrid.setOrientation(LinearLayout.VERTICAL);
        stopGrid.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout stopRow = null;
        int stopColIdx = 0;

        for (String compositeKey : favKeys) {
            // compositeKey = "routeId_nodeId"
            String stopName = prefs.getString("fav_stop_name_"    + compositeKey, compositeKey);
            String stopNo   = prefs.getString("fav_stop_no_"      + compositeKey, "");
            String routeNo  = prefs.getString("fav_stop_route_"   + compositeKey, "");
            String routeId  = prefs.getString("fav_stop_routeid_" + compositeKey, "");

            // 카드: 버스이모지 + 노선번호 + 정류소명
            // 새 행 시작
            if (stopColIdx % 2 == 0) {
                stopRow = new LinearLayout(this);
                stopRow.setOrientation(LinearLayout.HORIZONTAL);
                stopRow.setWeightSum(2f);
                LinearLayout.LayoutParams stopRowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                stopRowLp.setMargins(0, 0, 0, dpToPx(8));
                stopRow.setLayoutParams(stopRowLp);
                stopGrid.addView(stopRow);
            }

            // ── 정류소 즐겨찾기 카드 (노선 즐겨찾기와 동일한 디자인) ──
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(makeShadowCardDrawable("#FFFFFF", 10, 3));
            card.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            card.setPadding(dpToPx(12), dpToPx(14), dpToPx(12), dpToPx(12));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, dpToPx(110), 1f);
            cardLp.setMargins(0, 0, stopColIdx % 2 == 0 ? dpToPx(6) : 0, 0);
            card.setLayoutParams(cardLp);

            // 오른쪽 위: 버스이미지 + 설정 + 알림 버튼 행
            final String fCompositeKey = compositeKey;
            final String fStopName = stopName, fRouteNo = routeNo, fRouteId = routeId;
            final String favKey2 = "fav_stop_" + compositeKey;

            LinearLayout sIconBtnRow = new LinearLayout(this);
            sIconBtnRow.setOrientation(LinearLayout.HORIZONTAL);
            sIconBtnRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            sIconBtnRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 버스 아이콘 (왼쪽) - routeType 색상
            String sTpCached = getSharedPreferences("bus_cache", MODE_PRIVATE)
                    .getString("route_" + routeId + "_rTp", "");
            String[] sBadge = routeTypeBadge(sTpCached);
            String sTypeColor = sBadge[1];
            int sTypeColorInt = Color.parseColor(sTypeColor);

            LinearLayout stopBusWrapper = new LinearLayout(this);
            stopBusWrapper.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            stopBusWrapper.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            // routeId 있으면 노선+정류소(pngwing_com.png), 없으면 정류소만(stop.png=pngwing-stio_1.png)
            int stopArgbColor = 0xFF000000 | (sTypeColorInt & 0xFFFFFF);
            boolean isRouteStop = !routeId.isEmpty();
            android.graphics.Bitmap stopBusBmp = isRouteStop
                    ? getStopRouteIconColor(stopArgbColor)
                    : getStopIconColor(stopArgbColor);
            if (stopBusBmp != null) {
                android.widget.ImageView ivStopBus = new android.widget.ImageView(this);
                ivStopBus.setImageBitmap(stopBusBmp);
                int iconSize = dpToPx(32);
                LinearLayout.LayoutParams stopBusLp = new LinearLayout.LayoutParams(iconSize, iconSize);
                stopBusLp.setMargins(0, 0, dpToPx(6), 0);
                stopBusLp.gravity = Gravity.CENTER_VERTICAL;
                ivStopBus.setLayoutParams(stopBusLp);
                ivStopBus.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                stopBusWrapper.addView(ivStopBus);
            }
            sIconBtnRow.addView(stopBusWrapper);

            // 설정 버튼
            TextView tvStopGear = new TextView(this);
            tvStopGear.setText("설정");
            tvStopGear.setTextColor(Color.parseColor("#888888"));
            tvStopGear.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvStopGear.setTypeface(null, android.graphics.Typeface.BOLD);
            tvStopGear.setGravity(Gravity.CENTER);
            tvStopGear.setPadding(dpToPx(9), dpToPx(4), dpToPx(9), dpToPx(4));
            android.graphics.drawable.GradientDrawable stopGearBg = new android.graphics.drawable.GradientDrawable();
            stopGearBg.setColor(Color.parseColor("#F5F5F5"));
            stopGearBg.setCornerRadius(dpToPx(6));
            stopGearBg.setStroke(dpToPx(1), Color.parseColor("#DDDDDD"));
            tvStopGear.setBackground(stopGearBg);
            LinearLayout.LayoutParams stopGearLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            stopGearLp.setMargins(0, 0, dpToPx(6), 0);
            tvStopGear.setLayoutParams(stopGearLp);
            tvStopGear.setOnClickListener(vg -> {
                // 노선 즐겨찾기 설정과 동일한 다이얼로그
                android.app.Dialog stopSettingDlg = new android.app.Dialog(this,
                        android.R.style.Theme_Material_Light_Dialog);
                LinearLayout settingLayout2 = new LinearLayout(this);
                settingLayout2.setOrientation(LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable sgCardBg = new android.graphics.drawable.GradientDrawable();
                sgCardBg.setColor(Color.WHITE);
                sgCardBg.setCornerRadius(dpToPx(20));
                settingLayout2.setBackground(sgCardBg);
                settingLayout2.setPadding(dpToPx(20), dpToPx(22), dpToPx(20), dpToPx(20));

                // 제목
                TextView tvSgTitle = new TextView(this);
                tvSgTitle.setText(routeNo + "번  " + stopName);
                tvSgTitle.setTextColor(Color.parseColor("#0984E3"));
                tvSgTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
                tvSgTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                tvSgTitle.setGravity(Gravity.CENTER);
                tvSgTitle.setShadowLayer(4f, 0f, 1.5f, 0x40000000);
                tvSgTitle.setSingleLine(true);
                tvSgTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams sgTitleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                sgTitleLp.setMargins(0, 0, 0, dpToPx(6));
                tvSgTitle.setLayoutParams(sgTitleLp);
                settingLayout2.addView(tvSgTitle);

                // 메모 표시
                String curStopMemo = prefs.getString(favKey2 + "_memo", "");
                if (!curStopMemo.isEmpty()) {
                    TextView tvCurMemo2 = new TextView(this);
                    tvCurMemo2.setText(curStopMemo);
                    tvCurMemo2.setTextColor(Color.parseColor("#888888"));
                    tvCurMemo2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                    tvCurMemo2.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams cmLp2 = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    cmLp2.setMargins(0, 0, 0, dpToPx(16));
                    tvCurMemo2.setLayoutParams(cmLp2);
                    settingLayout2.addView(tvCurMemo2);
                } else {
                    android.view.View divSg = new android.view.View(this);
                    divSg.setBackgroundColor(Color.parseColor("#EEEEEE"));
                    LinearLayout.LayoutParams divSgLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                    divSgLp.setMargins(0, 0, 0, dpToPx(16));
                    divSg.setLayoutParams(divSgLp);
                    settingLayout2.addView(divSg);
                }

                // 수정 버튼
                TextView btnSgEdit = new TextView(this);
                btnSgEdit.setText("수정");
                btnSgEdit.setTextColor(Color.WHITE);
                btnSgEdit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnSgEdit.setTypeface(null, android.graphics.Typeface.BOLD);
                btnSgEdit.setGravity(Gravity.CENTER);
                btnSgEdit.setPadding(0, dpToPx(14), 0, dpToPx(14));
                android.graphics.drawable.GradientDrawable sgEditBg = new android.graphics.drawable.GradientDrawable();
                sgEditBg.setColor(Color.parseColor("#5BA9F0"));
                sgEditBg.setCornerRadius(dpToPx(12));
                btnSgEdit.setBackground(sgEditBg);
                btnSgEdit.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                LinearLayout.LayoutParams sgEditLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                sgEditLp.setMargins(0, 0, 0, dpToPx(10));
                btnSgEdit.setLayoutParams(sgEditLp);
                btnSgEdit.setOnClickListener(vv -> {
                    stopSettingDlg.dismiss();
                    String existMemo2 = prefs.getString(favKey2 + "_memo", "");
                    android.app.Dialog memoDlg2 = new android.app.Dialog(this, android.R.style.Theme_Material_Light_Dialog);
                    LinearLayout mLayout2 = new LinearLayout(this);
                    mLayout2.setOrientation(LinearLayout.VERTICAL);
                    android.graphics.drawable.GradientDrawable mBg2 = new android.graphics.drawable.GradientDrawable();
                    mBg2.setColor(Color.WHITE); mBg2.setCornerRadius(dpToPx(16));
                    mLayout2.setBackground(mBg2);
                    mLayout2.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));
                    TextView mTitle2 = new TextView(this);
                    mTitle2.setText(routeNo + "번  " + stopName);
                    mTitle2.setTextColor(Color.parseColor("#0984E3"));
                    mTitle2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(16));
                    mTitle2.setTypeface(null, android.graphics.Typeface.BOLD);
                    mTitle2.setGravity(Gravity.CENTER);
                    mTitle2.setSingleLine(true);
                    mTitle2.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams mt2Lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    mt2Lp.setMargins(0, 0, 0, dpToPx(14)); mTitle2.setLayoutParams(mt2Lp);
                    mLayout2.addView(mTitle2);
                    android.view.View mDiv2 = new android.view.View(this);
                    mDiv2.setBackgroundColor(Color.parseColor("#EEEEEE"));
                    LinearLayout.LayoutParams mDiv2Lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
                    mDiv2Lp.setMargins(0, 0, 0, dpToPx(14)); mDiv2.setLayoutParams(mDiv2Lp);
                    mLayout2.addView(mDiv2);
                    TextView tvML2 = new TextView(this); tvML2.setText("메모 (선택)");
                    tvML2.setTextColor(Color.parseColor("#555555")); tvML2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(12));
                    LinearLayout.LayoutParams tvML2Lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    tvML2Lp.setMargins(0, 0, 0, dpToPx(6)); tvML2.setLayoutParams(tvML2Lp);
                    mLayout2.addView(tvML2);
                    android.widget.EditText etM2 = new android.widget.EditText(this);
                    setBlackCursor(etM2); etM2.setText(existMemo2); etM2.setSingleLine(true);
                    etM2.setHint("예) 집앞, 출근길");
                    android.graphics.drawable.GradientDrawable etM2Bg = new android.graphics.drawable.GradientDrawable();
                    etM2Bg.setColor(Color.parseColor("#F8F8F8")); etM2Bg.setCornerRadius(dpToPx(10));
                    etM2Bg.setStroke(dpToPx(1), Color.parseColor("#DDDDDD"));
                    etM2.setBackground(etM2Bg); etM2.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
                    LinearLayout.LayoutParams etM2Lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    etM2Lp.setMargins(0, 0, 0, dpToPx(20)); etM2.setLayoutParams(etM2Lp);
                    mLayout2.addView(etM2);
                    LinearLayout mBtnRow2 = new LinearLayout(this);
                    mBtnRow2.setOrientation(LinearLayout.HORIZONTAL);
                    mBtnRow2.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                    TextView mCancel2 = new TextView(this); mCancel2.setText("취소");
                    mCancel2.setTextColor(Color.parseColor("#888888")); mCancel2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    mCancel2.setTypeface(null, android.graphics.Typeface.BOLD); mCancel2.setGravity(Gravity.CENTER);
                    mCancel2.setPadding(0, dpToPx(13), 0, dpToPx(13));
                    android.graphics.drawable.GradientDrawable mC2Bg = new android.graphics.drawable.GradientDrawable();
                    mC2Bg.setColor(Color.parseColor("#F0F0F0")); mC2Bg.setCornerRadius(dpToPx(10));
                    mCancel2.setBackground(mC2Bg);
                    LinearLayout.LayoutParams mC2Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    mC2Lp.setMargins(0, 0, dpToPx(8), 0); mCancel2.setLayoutParams(mC2Lp);
                    mCancel2.setOnClickListener(vvv -> memoDlg2.dismiss()); mBtnRow2.addView(mCancel2);
                    TextView mOk2 = new TextView(this); mOk2.setText("저장");
                    mOk2.setTextColor(Color.WHITE); mOk2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                    mOk2.setTypeface(null, android.graphics.Typeface.BOLD); mOk2.setGravity(Gravity.CENTER);
                    mOk2.setPadding(0, dpToPx(13), 0, dpToPx(13));
                    android.graphics.drawable.GradientDrawable mO2Bg = new android.graphics.drawable.GradientDrawable();
                    mO2Bg.setColor(Color.parseColor("#5BA9F0")); mO2Bg.setCornerRadius(dpToPx(10));
                    mOk2.setBackground(mO2Bg);
                    mOk2.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                    mOk2.setOnClickListener(vvv -> {
                        memoDlg2.dismiss();
                        prefs.edit().putString(favKey2 + "_memo", etM2.getText().toString().trim()).apply();
                        android.widget.Toast.makeText(this, "메모가 저장되었습니다", android.widget.Toast.LENGTH_SHORT).show();
                        refreshBusFavorites(favSection, resultContainer);
                    }); mBtnRow2.addView(mOk2);
                    mLayout2.addView(mBtnRow2);
                    memoDlg2.setContentView(mLayout2); memoDlg2.setCancelable(true);
                    if (memoDlg2.getWindow() != null) {
                        memoDlg2.getWindow().setLayout(
                                (int)(getResources().getDisplayMetrics().widthPixels * 0.85),
                                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                        memoDlg2.getWindow().setBackgroundDrawable(
                                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                    }
                    memoDlg2.show();
                    etM2.post(() -> { etM2.requestFocus(); etM2.setSelection(etM2.getText().length()); });
                });
                settingLayout2.addView(btnSgEdit);

                // 삭제 버튼
                TextView btnSgDel = new TextView(this);
                btnSgDel.setText("삭제");
                btnSgDel.setTextColor(Color.WHITE);
                btnSgDel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnSgDel.setTypeface(null, android.graphics.Typeface.BOLD);
                btnSgDel.setGravity(Gravity.CENTER);
                btnSgDel.setPadding(0, dpToPx(14), 0, dpToPx(14));
                android.graphics.drawable.GradientDrawable sgDelBg = new android.graphics.drawable.GradientDrawable();
                sgDelBg.setColor(Color.parseColor("#E74C3C"));
                sgDelBg.setCornerRadius(dpToPx(12));
                btnSgDel.setBackground(sgDelBg);
                btnSgDel.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
                LinearLayout.LayoutParams sgDelLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                sgDelLp.setMargins(0, 0, 0, dpToPx(10));
                btnSgDel.setLayoutParams(sgDelLp);
                btnSgDel.setOnClickListener(vv -> {
                    stopSettingDlg.dismiss();
                    showConfirmDialog("삭제", stopName + " 즐겨찾기 삭제", "즐겨찾기에서 삭제하시겠습니까?", () -> {
                        busFavDirty = true;
                        prefs.edit().remove(favKey2)
                                .remove("fav_stop_name_" + fCompositeKey)
                                .remove("fav_stop_no_" + fCompositeKey)
                                .remove("fav_stop_route_" + fCompositeKey)
                                .remove("fav_stop_routeid_" + fCompositeKey)
                                .remove(favKey2 + "_memo").apply();
                        android.widget.Toast.makeText(this, stopName + " 즐겨찾기 삭제",
                                android.widget.Toast.LENGTH_SHORT).show();
                        refreshBusFavorites(favSection, resultContainer);
                    });
                });
                settingLayout2.addView(btnSgDel);

                // 취소 버튼
                TextView btnSgCancel2 = new TextView(this);
                btnSgCancel2.setText("취소");
                btnSgCancel2.setTextColor(Color.parseColor("#888888"));
                btnSgCancel2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(14));
                btnSgCancel2.setTypeface(null, android.graphics.Typeface.BOLD);
                btnSgCancel2.setGravity(Gravity.CENTER);
                btnSgCancel2.setPadding(0, dpToPx(14), 0, dpToPx(14));
                android.graphics.drawable.GradientDrawable sgCancelBg2 = new android.graphics.drawable.GradientDrawable();
                sgCancelBg2.setColor(Color.parseColor("#F0F0F0"));
                sgCancelBg2.setCornerRadius(dpToPx(12));
                sgCancelBg2.setStroke(dpToPx(1), Color.parseColor("#CCCCCC"));
                btnSgCancel2.setBackground(sgCancelBg2);
                btnSgCancel2.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                btnSgCancel2.setOnClickListener(vv -> stopSettingDlg.dismiss());
                settingLayout2.addView(btnSgCancel2);

                stopSettingDlg.setContentView(settingLayout2);
                stopSettingDlg.setCancelable(true);
                if (stopSettingDlg.getWindow() != null) {
                    stopSettingDlg.getWindow().setLayout(
                            (int)(getResources().getDisplayMetrics().widthPixels * 0.80),
                            android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                    stopSettingDlg.getWindow().setBackgroundDrawable(
                            new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                }
                stopSettingDlg.show();
            });
            sIconBtnRow.addView(tvStopGear);

    

            // 알림 버튼
            TextView tvStopBell = new TextView(this);
            tvStopBell.setText("알림");
            tvStopBell.setTextColor(Color.parseColor("#5BA9F0"));
            tvStopBell.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(11));
            tvStopBell.setTypeface(null, android.graphics.Typeface.BOLD);
            tvStopBell.setGravity(Gravity.CENTER);
            tvStopBell.setPadding(dpToPx(9), dpToPx(4), dpToPx(9), dpToPx(4));
            android.graphics.drawable.GradientDrawable stopBellBg = new android.graphics.drawable.GradientDrawable();
            stopBellBg.setColor(Color.parseColor("#EBF5FB"));
            stopBellBg.setCornerRadius(dpToPx(6));
            stopBellBg.setStroke(dpToPx(1), Color.parseColor("#5BA9F0"));
            tvStopBell.setBackground(stopBellBg);
            tvStopBell.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            tvStopBell.setOnClickListener(v2 -> android.widget.Toast.makeText(this,
                    stopName + " 알림 (준비중)", android.widget.Toast.LENGTH_SHORT).show());
            sIconBtnRow.addView(tvStopBell);
            card.addView(sIconBtnRow);

            // 노선 번호 (보라색)
            // 노선+정류소: "708번" + 정류소명 / 정류소만: 정류소명 + 번호
            String stopMemo = prefs.getString(favKey2 + "_memo", "");
            TextView tvStopRouteNo = new TextView(this);
            if (!routeNo.isEmpty()) {
                tvStopRouteNo.setText(routeNo + "번");
            } else {
                tvStopRouteNo.setText(stopMemo.isEmpty() ? stopName : stopMemo);
            }
            tvStopRouteNo.setTextColor(Color.parseColor(sTypeColor));
            tvStopRouteNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(20));
            tvStopRouteNo.setShadowLayer(4f, 0f, 1.5f, 0x40000000);
            tvStopRouteNo.setTypeface(null, android.graphics.Typeface.BOLD);
            tvStopRouteNo.setSingleLine(true);
            tvStopRouteNo.setEllipsize(null);
            // 글자수에 따라 크기 조정
            {
                String txt = tvStopRouteNo.getText().toString();
                if (txt.length() > 9) {
                    tvStopRouteNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                    tvStopRouteNo.setEllipsize(android.text.TextUtils.TruncateAt.END);
                } else if (txt.length() > 6) {
                    tvStopRouteNo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                    tvStopRouteNo.setEllipsize(null);
                }
            }
            LinearLayout.LayoutParams rNoLp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rNoLp2.setMargins(0, dpToPx(4), 0, 0);
            tvStopRouteNo.setLayoutParams(rNoLp2);
            card.addView(tvStopRouteNo);

            // 서브 텍스트
            String stopSubText;
            if (!routeNo.isEmpty()) {
                stopSubText = stopMemo.isEmpty() ? stopName : stopMemo;
            } else {
                stopSubText = stopNo.isEmpty() ? "" : stopNo;
            }
            if (!stopSubText.isEmpty()) {
                TextView tvStopSub = new TextView(this);
                tvStopSub.setText(stopSubText);
                tvStopSub.setTextColor(Color.parseColor("#555555"));
                tvStopSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fs(15));
                tvStopSub.setSingleLine(true);
                tvStopSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams stopSubLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                stopSubLp.setMargins(0, dpToPx(3), 0, 0);
                tvStopSub.setLayoutParams(stopSubLp);
                card.addView(tvStopSub);
            }

            // 카드 탭 → 노선 있으면 타임라인, 없으면 정류소 도착화면
            card.setOnClickListener(v2 -> {
                if (!fRouteId.isEmpty()) {
                    busScreenLoadStops(fRouteId, fRouteNo, busResultContainer, "forward", "");
                } else {
                    // 정류소만 즐겨찾기 → 도착화면으로 이동
                    String nId = fCompositeKey; // compositeKey = nodeId
                    String nNm = fStopName;
                    String nNo = prefs.getString("fav_stop_no_" + nId, "");
                    if (busSearchArea  != null) busSearchArea.setVisibility(android.view.View.GONE);
                    if (busFavSection2 != null) busFavSection2.setVisibility(android.view.View.GONE);
                    busScreenLoadArrival(nId, nNm, nNo, "", busResultContainer);
                }
            });

            if (stopRow != null) stopRow.addView(card);
            stopColIdx++;
        }

        // 홀수개면 빈 공간 채우기
        if (stopColIdx % 2 == 1 && stopRow != null) {
            View emptyStop = new View(this);
            LinearLayout.LayoutParams emStopLp = new LinearLayout.LayoutParams(0, 1, 1f);
            emptyStop.setLayoutParams(emStopLp);
            stopRow.addView(emptyStop);
        }
        if (!favKeys.isEmpty()) favSection.addView(stopGrid);
        // 구분선
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#EEEEEE"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.setMargins(0, dpToPx(4), 0, dpToPx(12));
        div.setLayoutParams(divLp);
        favSection.addView(div);
    }

    // ── 헬퍼 ──────────────────────────────────────────────
    private String httpGet(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        conn.setConnectTimeout(7000);
        conn.setReadTimeout(7000);
        java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private String tag(String xml, String tagName) {
        String open = "<" + tagName + ">";
        String close = "</" + tagName + ">";
        int s = xml.indexOf(open);
        if (s < 0) return "";
        s += open.length();
        int e = xml.indexOf(close, s);
        if (e < 0) return "";
        return xml.substring(s, e).trim();
    }
}
