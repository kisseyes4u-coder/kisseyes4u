package com.sms2drive;

// ═══════════════════════════════════════════════════════════════
//  UserMenuBuilder.java — 일반사용자 화면 전담
//  편집 시 이 파일만 수정하세요.
//  공통 색상 상수: BG="#F5F3FA", PURPLE="#6C5CE7", TEXT1="#1A1A2E"
// ═══════════════════════════════════════════════════════════════

public class UserMenuBuilder {

    private final PinActivity act;

    public UserMenuBuilder(PinActivity activity) {
        this.act = activity;
    }

    /** 일반사용자 메뉴 빌드 - PinActivity에서 buildUserMenu() 호출 시 실행 */
    public void build(boolean needUpdate) {
        act.buildUserMenuInternal(needUpdate);
    }
}
