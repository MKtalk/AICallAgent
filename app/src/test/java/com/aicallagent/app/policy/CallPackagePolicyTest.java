package com.aicallagent.app.policy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CallPackagePolicyTest {

    @Test
    public void testAllowedPackagesAccepted() {
        assertTrue("Samsung InCallUI must be allowed",
                CallPackagePolicy.isAllowedPackage("com.samsung.android.incallui"));
        assertTrue("Samsung TelephonyUI must be allowed",
                CallPackagePolicy.isAllowedPackage("com.samsung.android.app.telephonyui"));
    }

    @Test
    public void testDisallowedPackagesRejected() {
        assertFalse("Google dialer must be rejected",
                CallPackagePolicy.isAllowedPackage("com.google.android.dialer"));
        assertFalse("Android SystemUI must be rejected",
                CallPackagePolicy.isAllowedPackage("com.android.systemui"));
        assertFalse("Samsung launcher must be rejected",
                CallPackagePolicy.isAllowedPackage("com.sec.android.app.launcher"));
        assertFalse("Arbitrary third party apps must be rejected",
                CallPackagePolicy.isAllowedPackage("com.example.untrusted"));
        assertFalse("Old probe app package must be rejected",
                CallPackagePolicy.isAllowedPackage("dev.local.hangulprobe"));
    }

    @Test
    public void testNullAndEmptyRejected() {
        assertFalse("null package must be safely rejected",
                CallPackagePolicy.isAllowedPackage(null));
        assertFalse("empty package must be rejected",
                CallPackagePolicy.isAllowedPackage(""));
        assertFalse("whitespace package must be rejected",
                CallPackagePolicy.isAllowedPackage("   "));
    }

    @Test
    public void testCharSequenceCompatibility() {
        CharSequence sb = new StringBuilder("com.samsung.android.incallui");
        assertTrue("CharSequence implementation should be accepted",
                CallPackagePolicy.isAllowedPackage(sb));
    }
}
