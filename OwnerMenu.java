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
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(PinActivity.this);
            builder.setTitle("FCM TEST");
            builder.setMessage("TEST \uBB38\uC790\uB97C \uBCF4\uB0B4\uC2DC\uACA0\uC2B5\uB2C8\uAE4C?");
            builder.setPositiveButton("\uBCF4\uB0B4\uAE30", (d, w) -> {
                // 실제 농협 은행 문자 형식으로 테스트
                String today = new java.text.SimpleDateFormat("MM/dd", java.util.Locale.KOREA).format(new java.util.Date());
                String fakeBody = "[Web발신]\n농협 출금10,000원\n" + today + " 12:00\n351-****-5510-13\nTEST거래\n잔액999,000원";
                new SmsReceiver().processMessage(PinActivity.this, "15882100", fakeBody);
                // FCM 수신 기록 저장 (테스트 문자도 기록)
                new MyFirebaseMessagingService().saveFcmReceivedLogPublic(PinActivity.this);
                android.widget.Toast.makeText(PinActivity.this, "FCM \uD14C\uC2A4\uD2B8 \uC804\uC1A1", android.widget.Toast.LENGTH_SHORT).show();
            });
            builder.setNegativeButton("\uCDE8\uC18C", null);
            android.app.AlertDialog dialog = builder.create();
            dialog.show();
            // 버튼 색상 강제 지정 (테마 무관하게 보이도록)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(Color.parseColor("#6C5CE7"));
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(Color.parseColor("#888888"));
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
