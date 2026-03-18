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
