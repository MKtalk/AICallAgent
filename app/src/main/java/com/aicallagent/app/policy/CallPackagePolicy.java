package com.aicallagent.app.policy;

/**
 * 삼성 통화 관련 허용 패키지 검증 정책.
 * 이벤트 및 활성 창(Active Window)의 패키지가 승인된 삼성 통화 UI인지 엄격히 검증합니다.
 */
public final class CallPackagePolicy {

    public static final String PKG_INCALLUI = "com.samsung.android.incallui";
    public static final String PKG_TELEPHONYUI = "com.samsung.android.app.telephonyui";

    private CallPackagePolicy() {
    }

    /**
     * 대상 패키지가 관찰 허용된 삼성 통화 UI 패키지인지 확인합니다.
     * null이거나 비허용 패키지인 경우 false를 반환합니다.
     */
    public static boolean isAllowedPackage(CharSequence packageName) {
        if (packageName == null) {
            return false;
        }
        String pkgStr = packageName.toString();
        return PKG_INCALLUI.equals(pkgStr) || PKG_TELEPHONYUI.equals(pkgStr);
    }
}
