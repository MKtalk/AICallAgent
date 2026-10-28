package com.aicallagent.app.policy;

import org.junit.Test;
import static org.junit.Assert.*;

public class AiTargetPolicyTest {

    @Test public void englishAssistLabelIsRecognizedForVerifiedId() {
        assertTrue(AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL,
                AiTargetPolicy.VERIFIED_ID, "Call assist"));
    }

    @Test public void englishAssistButtonSuffixAndCaseAreRecognized() {
        assertTrue(AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL,
                AiTargetPolicy.VERIFIED_ID, "Call Assist, button"));
    }

    @Test public void englishTextCallLabelIsRecognized() {
        assertTrue(AiTargetPolicy.matchesTextCall("com.samsung.android.incallui:id/text_call_button",
                "Text call, button"));
        assertTrue(AiTargetPolicy.matchesTextCall("com.samsung.android.incallui:id/text_call_button",
                "Text call"));
    }

    @Test public void koreanTextCallLabelsRemainRecognized() {
        assertTrue(AiTargetPolicy.matchesTextCall("com.samsung.android.incallui:id/text_call_button",
                "텍스트 통화, 버튼"));
        assertTrue(AiTargetPolicy.matchesTextCall("com.samsung.android.incallui:id/text_call_button",
                "텍스트 통화"));
    }

    @Test public void translatedLabelNeverAuthorizesDifferentId() {
        assertFalse(AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL,
                "com.samsung.android.incallui:id/upgrade_button", "Call assist"));
        assertFalse(AiTargetPolicy.matchesTextCall("com.samsung.android.incallui:id/other_button",
                "Text call"));
    }

    @Test public void unknownOrTranslationMenuLabelIsRejected() {
        assertFalse(AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL,
                AiTargetPolicy.VERIFIED_ID, "Live translate"));
        assertFalse(AiTargetPolicy.matchesTextCall("com.samsung.android.incallui:id/text_call_button",
                "Live translate"));
        assertFalse(AiTargetPolicy.matchesTextCall("com.samsung.android.incallui:id/text_call_button", null));
    }

    @Test
    public void testVerifiedTargetAccepted() {
        assertTrue("real observed AI target must be accepted",
                AiTargetPolicy.matches(
                        AiTargetPolicy.VERIFIED_ID,
                        AiTargetPolicy.VERIFIED_LABEL,
                        "com.samsung.android.incallui:id/ai_call_assist_button",
                        "통화 어시스트"
                ));
    }

    @Test
    public void testConfiguredTargetAccepted() {
        String id = "test:id/verified_control";
        String label = "통화 어시스트";
        assertTrue("configured target matching exactly must be accepted",
                AiTargetPolicy.matches(id, label, id, label));
    }

    @Test
    public void testUnconfiguredTargetRejected() {
        assertFalse("empty configured id rejected",
                AiTargetPolicy.matches("", "통화 어시스트", "test:id/verified_control", "통화 어시스트"));
        assertFalse("null configured id rejected",
                AiTargetPolicy.matches(null, "통화 어시스트", "test:id/verified_control", "통화 어시스트"));
        assertFalse("empty configured label rejected",
                AiTargetPolicy.matches("test:id/verified_control", "", "test:id/verified_control", "통화 어시스트"));
        assertFalse("null configured label rejected",
                AiTargetPolicy.matches("test:id/verified_control", null, "test:id/verified_control", "통화 어시스트"));
    }

    @Test
    public void testMismatchedTargetRejected() {
        String id = "test:id/verified_control";
        String label = "통화 어시스트";
        assertFalse("mismatched id rejected",
                AiTargetPolicy.matches(id, label, "test:id/other", label));
        assertFalse("mismatched label rejected",
                AiTargetPolicy.matches(id, label, id, "다른 라벨"));
    }

    @Test
    public void testVideoTargetRejected() {
        String label = "통화 어시스트";
        assertFalse("video id rejected",
                AiTargetPolicy.matches("test:id/video_button", label, "test:id/video_button", label));
        assertFalse("video label containing 영상 rejected",
                AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL,
                        AiTargetPolicy.VERIFIED_ID, "영상통화"));
        assertFalse("video label containing 화상 rejected",
                AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL,
                        AiTargetPolicy.VERIFIED_ID, "화상통화"));
    }

    @Test
    public void testUpgradeTargetRejected() {
        String label = "통화 어시스트";
        assertFalse("upgrade button rejected even if configured",
                AiTargetPolicy.matches("test:id/upgrade_button", label, "test:id/upgrade_button", label));
        assertFalse("real observed video upgrade button rejected",
                AiTargetPolicy.matches(
                        AiTargetPolicy.VERIFIED_ID,
                        AiTargetPolicy.VERIFIED_LABEL,
                        "com.samsung.android.incallui:id/upgrade_button",
                        "영상통화"
                ));
    }

    @Test
    public void testDisconnectTargetRejected() {
        String label = "통화 어시스트";
        assertFalse("disconnect button rejected",
                AiTargetPolicy.matches("test:id/disconnect_button", label, "test:id/disconnect_button", label));
        assertFalse("end_call button with disconnect in id rejected",
                AiTargetPolicy.matches("test:id/call_disconnect", label, "test:id/call_disconnect", label));
    }

    @Test
    public void testNullObservedTargetRejected() {
        assertFalse("null observed id rejected",
                AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL, null, "통화 어시스트"));
        assertFalse("null observed label rejected",
                AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID, AiTargetPolicy.VERIFIED_LABEL, AiTargetPolicy.VERIFIED_ID, null));
    }
}
