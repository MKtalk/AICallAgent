package com.aicallagent.app.service;

import com.aicallagent.app.CallAccessibilityService;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallAccessibilityServicePackagePolicyTest {

    @Test
    public void testSamsungInCallUiPackageAllowed() {
        assertTrue(CallAccessibilityService.isAllowedPackage("com.samsung.android.incallui"));
    }

    @Test
    public void testSamsungTelephonyUiPackageAllowed() {
        assertTrue(CallAccessibilityService.isAllowedPackage("com.samsung.android.app.telephonyui"));
    }

    @Test
    public void testOtherDialerPackagesRejected() {
        assertFalse(CallAccessibilityService.isAllowedPackage("com.google.android.dialer"));
        assertFalse(CallAccessibilityService.isAllowedPackage("com.android.server.telecom"));
        assertFalse(CallAccessibilityService.isAllowedPackage("com.android.phone"));
        assertFalse(CallAccessibilityService.isAllowedPackage("com.kakao.talk"));
    }

    @Test
    public void testNullOrEmptyPackageRejected() {
        assertFalse(CallAccessibilityService.isAllowedPackage(null));
        assertFalse(CallAccessibilityService.isAllowedPackage(""));
    }
}
