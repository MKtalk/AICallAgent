package com.aicallagent.app.policy;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeechBusyNoticeTest {
    private final String notice = "Wait for current response to be said before sending a new one.";

    @Test public void confirmedKoreanNoticeMatchesOnlyTheWholeRejection() {
        String korean = "현재 응답이 다 끝난 후에 새로운 응답을 보낼 수 있어요.";
        assertTrue(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true, korean));
        assertTrue(SpeechBusyNotice.matches(CallPackagePolicy.PKG_TELEPHONYUI, true,
                "  현재 응답이 다 끝난 후에\n새로운 응답을 보낼 수 있어요.  "));
        assertFalse(SpeechBusyNotice.matches("com.example.other", true, korean));
        assertFalse(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, false, korean));
        assertFalse(SpeechBusyNotice.matchesText("사용자 발화 " + korean));
        assertFalse(SpeechBusyNotice.matchesText(korean + " 추가 안내"));
        assertFalse(SpeechBusyNotice.matchesText("현재 응답이 끝났으니 새로운 응답을 보낼 수 있어요."));
        assertFalse(SpeechBusyNotice.matchesText("새로운 응답을 보낼 수 있어요."));
    }

    @Test public void knownSamsungToastMatchesOnlyTheWholeNotice() {
        assertTrue(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true, notice));
        assertTrue(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true,
                "  Wait for current response to be said before seding aw new one. "));
        assertFalse(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true, "사용자 발화 " + notice));
    }

    @Test public void otherPackageOrNonToastCannotAuthorizeResending() {
        assertFalse(SpeechBusyNotice.matches("com.example.other", true, notice));
        assertFalse(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, false, notice));
        assertFalse(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true, null));
        assertFalse(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true, "Wait a moment"));
    }

    @Test public void articleAndWhitespaceVariantsStillDescribeSameExplicitRejection() {
        assertTrue(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true,
                "Wait for the current response to be said before sending a new one."));
        assertTrue(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true,
                "WAIT FOR THE CURRENT RESPONSE TO BE SAID\nBEFORE SEDING AW NEW ONE."));
        assertFalse(SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true,
                "Wait for the current response to be said before sending a new one. Extra message"));
    }

    @Test public void phoneTransientNotificationDoesNotRequireOneSpecificToastClass() {
        assertTrue(SpeechBusyNotice.isTransientNotice(true, false, "android.widget.Toast", false));
        assertTrue(SpeechBusyNotice.isTransientNotice(true, false, null, false));
        assertTrue(SpeechBusyNotice.isTransientNotice(true, false, "vendor.TransientNotice", false));
        assertFalse(SpeechBusyNotice.isTransientNotice(true, false, "android.widget.Toast", true));
    }

    @Test public void toastWindowEventIsAcceptedButNormalConversationEventsAreNot() {
        assertTrue(SpeechBusyNotice.isTransientNotice(false, true, "android.widget.Toast", false));
        assertFalse(SpeechBusyNotice.isTransientNotice(false, true, "android.widget.TextView", false));
        assertFalse(SpeechBusyNotice.isTransientNotice(false, false, "android.widget.Toast", false));
        assertFalse(SpeechBusyNotice.isTransientNotice(false, true, "android.widget.Toast", true));
    }
}
