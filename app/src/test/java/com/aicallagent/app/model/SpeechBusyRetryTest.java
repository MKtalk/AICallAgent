package com.aicallagent.app.model;

import org.junit.Test;
import com.aicallagent.app.policy.CallPackagePolicy;
import com.aicallagent.app.policy.SpeechBusyNotice;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class SpeechBusyRetryTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private final long sentAt = 1251;

    @Test public void koreanPhoneRejectionReachesRetryAndDeliveryWithoutGeneratingAgain() {
        attempt();
        String notice = "현재 응답이 다 끝난 후에 새로운 응답을 보낼 수 있어요.";
        if (SpeechBusyNotice.isTransientNotice(true, false, "android.widget.Toast", false)
                && SpeechBusyNotice.matches(CallPackagePolicy.PKG_INCALLUI, true, notice)) {
            assertTrue(flow.speechRejected(screen, sentAt + 100, sentAt + 100));
        }
        assertEquals(WAIT_SPEECH, flow.getStage());
        assertEquals("가상 회신", flow.getReplyText());
        assertEquals(NONE, flow.step(screen, sentAt + 2000));
        assertEquals(SEND_REPLY, flow.step(screen, sentAt + 2100));
        flow.actionResult(true, sentAt + 2100);
        screen.inputEmpty = true;
        screen.inputMatchesReply = false;
        screen.replyCount++;
        assertEquals(NONE, flow.step(screen, sentAt + 2200));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals(2, flow.getConversation().size());
    }

    private void attempt() {
        screen.allowedWindow = true;
        flow.enable(screen, 0); screen.incoming = true;
        flow.step(screen, 0); flow.actionResult(true, 0);
        screen.incoming = false;
        screen.textCall = screen.inputReady = screen.inputFocused = true;
        screen.remoteText = "가상 문의"; screen.remoteCount = 1;
        flow.step(screen, 1); flow.step(screen, 701);
        flow.replyResult("가상 회신", 701);
        flow.step(screen, 701); flow.actionResult(true, 701);
        screen.inputEmpty = false;
        screen.inputMatchesReply = screen.submitReady = true;
        screen.submitBounds = "verified bounds";
        flow.step(screen, 951);
        assertEquals(SEND_REPLY, flow.step(screen, sentAt));
        flow.actionResult(true, sentAt);
    }

    @Test public void explicitBusyRejectionPreservesReplyAndRetriesAfterWaiting() {
        attempt();
        assertTrue(flow.speechRejected(screen, sentAt + 100, sentAt + 100));
        assertEquals("WAIT_SPEECH", flow.getStage().name());
        assertEquals("가상 회신", flow.getReplyText());
        assertEquals(NONE, flow.step(screen, sentAt + 2000));
        assertEquals(SEND_REPLY, flow.step(screen, sentAt + 2100));
        flow.actionResult(true, sentAt + 2100);
        // An accepted retry still needs actual delivery evidence.
        assertEquals(NONE, flow.step(screen, sentAt + 2200));
        assertEquals(VERIFY_SENT, flow.getStage());
        assertEquals(1, flow.getConversation().size());
        screen.inputEmpty = true; screen.inputMatchesReply = false; screen.replyCount++;
        flow.step(screen, sentAt + 2300);
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals(2, flow.getConversation().size());
    }

    @Test public void noNoticeNeverRetriesAnAmbiguousSend() {
        attempt();
        assertEquals(NONE, flow.step(screen, sentAt + 2000));
        flow.step(screen, sentAt + 15001);
        assertEquals("SEND_UNCONFIRMED", flow.getFailure().name());
    }

    @Test public void staleNoticeOrChangedInputCannotAuthorizeRetry() {
        attempt();
        assertFalse(flow.speechRejected(screen, sentAt - 1, sentAt + 100));
        assertFalse(flow.speechRejected(screen, sentAt + 1, sentAt + 4000));
        screen.inputMatchesReply = false;
        assertFalse(flow.speechRejected(screen, sentAt + 100, sentAt + 100));
        screen.inputMatchesReply = true; screen.replyCount++;
        assertFalse(flow.speechRejected(screen, sentAt + 100, sentAt + 100));
    }

    @Test public void editingInputOrEndingCallWhileWaitingPreventsRetry() {
        attempt(); flow.speechRejected(screen, sentAt + 100, sentAt + 100);
        screen.inputMatchesReply = false;
        assertEquals(NONE, flow.step(screen, sentAt + 2200));
        assertEquals("INPUT_CHANGED", flow.getFailure().name());
        flow.confirmCallEnded();
        assertEquals(NONE, flow.step(screen, sentAt + 5000));
        assertEquals("", flow.getReplyText());
    }

    @Test public void repeatedRejectionCannotExtendTotalWaitForever() {
        attempt();
        long now = sentAt + 100;
        for (int i = 0; i < 29; i++) {
            assertTrue(flow.speechRejected(screen, now, now));
            now += 2000;
            assertEquals(SEND_REPLY, flow.step(screen, now));
            flow.actionResult(true, now);
        }
        assertTrue(flow.speechRejected(screen, now, now));
        assertEquals(NONE, flow.step(screen, sentAt + 60101));
        assertEquals("SPEECH_BUSY_TIMEOUT", flow.getFailure().name());
    }

    @Test public void newCallerSpeechDuringWaitIsQueuedWithoutAnotherApiRequest() {
        attempt(); flow.speechRejected(screen, sentAt + 100, sentAt + 100);
        screen.remoteCount = 2; screen.remoteText = "가상 추가 문의";
        assertEquals(NONE, flow.step(screen, sentAt + 200));
        assertEquals(SEND_REPLY, flow.step(screen, sentAt + 2100));
        flow.actionResult(true, sentAt + 2100);
        screen.inputEmpty = true; screen.inputMatchesReply = false; screen.replyCount++;
        flow.step(screen, sentAt + 2200);
        flow.step(screen, sentAt + 2300);
        assertEquals(REQUEST_REPLY, flow.step(screen, sentAt + 3000));
        assertEquals("가상 추가 문의", flow.getRequestText());
    }

    @Test public void lostWindowOrButtonWaitsAndDoesNotSpendRetryPermission() {
        attempt(); flow.speechRejected(screen, sentAt + 100, sentAt + 100);
        screen.allowedWindow = false;
        assertEquals(NONE, flow.step(screen, sentAt + 2100));
        screen.allowedWindow = true; screen.submitReady = false;
        assertEquals(NONE, flow.step(screen, sentAt + 2200));
        screen.submitReady = true;
        assertEquals(SEND_REPLY, flow.step(screen, sentAt + 2300));
    }
}
