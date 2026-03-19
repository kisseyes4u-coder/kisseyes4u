package com.sms2drive;

// ═══════════════════════════════════════════════════════════════
//  OwnerMenuBuilder.java — 관리자 화면 전담
//  편집 시 이 파일만 수정하세요.
//  공통 색상 상수: BG="#F5F3FA", PURPLE="#6C5CE7", TEXT1="#1A1A2E"
// ═══════════════════════════════════════════════════════════════

public class OwnerMenuBuilder {

    private final PinActivity act;

    public OwnerMenuBuilder(PinActivity activity) {
        this.act = activity;
    }

    /** 관리자 메뉴 빌드 - PinActivity에서 buildOwnerMenu() 호출 시 실행 */
    public void build() {
        act.buildOwnerMenuInternal();
    }
}
