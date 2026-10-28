package com.aicallagent.app.model;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class ReplySendControllerTest {
    private AutoReplyFlow.Snapshot ready() {
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = s.connected = s.textCall = s.inputReady = s.inputFocused = true;
        s.inputEmpty = false;
        s.inputMatchesReply = s.submitReady = true;
        s.submitBounds = "current-button-bounds";
        return s;
    }

    @Test public void revalidatedButtonClickDoesNotDependOnPreviousCoordinates() {
        AutoReplyFlow.Snapshot s = ready();
        AtomicInteger clicks = new AtomicInteger();
        assertTrue(ReplySendController.dispatch(s, 0, () -> { clicks.incrementAndGet(); return true; }));
        assertEquals(1, clicks.get());
    }

    @Test public void editedInputOrNewLocalMessagePreventsAnyClick() {
        AutoReplyFlow.Snapshot s = ready();
        AtomicInteger clicks = new AtomicInteger();
        s.inputMatchesReply = false;
        assertFalse(ReplySendController.dispatch(s, 0, () -> { clicks.incrementAndGet(); return true; }));
        s.inputMatchesReply = true;
        s.replyCount = 1;
        assertFalse(ReplySendController.dispatch(s, 0, () -> { clicks.incrementAndGet(); return true; }));
        assertEquals(0, clicks.get());
    }

    @Test public void lostCallOrUnavailableButtonPreventsAnyClick() {
        for (int condition = 0; condition < 7; condition++) {
            AutoReplyFlow.Snapshot s = ready();
            if (condition == 0) s.allowedWindow = false;
            if (condition == 1) s.ended = true;
            if (condition == 2) s.textCall = false;
            if (condition == 3) s.inputReady = false;
            if (condition == 4) s.inputEmpty = true;
            if (condition == 5) s.submitReady = false;
            if (condition == 6) s.connected = false; // Text-call evidence is sufficient.
            AtomicInteger clicks = new AtomicInteger();
            boolean accepted = ReplySendController.dispatch(s, 0, () -> { clicks.incrementAndGet(); return true; });
            assertEquals(condition == 6, accepted);
            assertEquals(condition == 6 ? 1 : 0, clicks.get());
        }
    }

    @Test public void sixApiRepliesUseOneButtonActionEachAndRequireDeliveryEvidence() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true;
        flow.enable(s, 0);
        s.incoming = true;
        assertEquals(ANSWER, flow.step(s, 0));
        flow.actionResult(true, 0);
        s.incoming = false;
        s.connected = s.textCall = s.inputReady = s.inputFocused = true;
        flow.step(s, 1);
        long now = 1000;
        AtomicInteger clicks = new AtomicInteger();
        for (int turn = 1; turn <= 6; turn++) {
            s.remoteText = "가상 발화 " + turn;
            s.remoteCount = turn;
            flow.step(s, now);
            now += 700;
            assertEquals(REQUEST_REPLY, flow.step(s, now));
            String reply = "가상 API 답변 " + turn;
            flow.replyResult(reply, now);
            assertEquals(SET_REPLY, flow.step(s, now));
            flow.actionResult(true, now);
            assertEquals(reply, flow.getReplyText());
            s.inputEmpty = false;
            s.inputMatchesReply = s.submitReady = true;
            s.submitBounds = "before-keyboard-movement";
            flow.step(s, now + 250);
            now += 550;
            assertEquals(SEND_REPLY, flow.step(s, now));
            s.submitBounds = "fresh-button-after-keyboard-movement";
            assertTrue(ReplySendController.dispatch(s, s.replyCount,
                    () -> { clicks.incrementAndGet(); return true; }));
            flow.actionResult(true, now);
            assertEquals(NONE, flow.step(s, now));
            assertEquals(VERIFY_SENT, flow.getStage());
            // An accepted click is not delivery. Only fresh UI evidence completes the turn.
            s.inputEmpty = true;
            s.inputMatchesReply = false;
            s.replyCount++;
            assertEquals(NONE, flow.step(s, now));
            assertEquals(WAIT_REMOTE, flow.getStage());
            assertEquals(reply, flow.getConversation().get(flow.getConversation().size() - 1).content);
            now += 1000;
        }
        assertEquals(6, clicks.get());
    }

    @Test public void rejectedClickIsNotRetried() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true;
        flow.enable(s, 0);
        s.incoming = true;
        flow.step(s, 0); flow.actionResult(true, 0);
        s.incoming = false;
        s.textCall = s.inputReady = s.inputFocused = true;
        s.remoteText = "가상 발화"; s.remoteCount = 1;
        flow.step(s, 1); flow.step(s, 701);
        flow.replyResult("가상 답변", 701);
        flow.step(s, 701); flow.actionResult(true, 701);
        s.inputEmpty = false; s.inputMatchesReply = s.submitReady = true;
        s.submitBounds = "bounds";
        flow.step(s, 951);
        assertEquals(SEND_REPLY, flow.step(s, 1251));
        AtomicInteger clicks = new AtomicInteger();
        boolean accepted = ReplySendController.dispatch(s, 0,
                () -> { clicks.incrementAndGet(); return false; });
        flow.actionResult(accepted, 1251);
        assertEquals("SEND_REJECTED", flow.getFailure().name());
        assertEquals(NONE, flow.step(s, 2000));
        assertEquals(1, clicks.get());
    }

    @Test public void acceptedClickWithoutDeliveryEvidenceNeverRetriesOrAddsAssistantHistory() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true;
        flow.enable(s, 0); s.incoming = true;
        flow.step(s, 0); flow.actionResult(true, 0);
        s.incoming = false;
        s.textCall = s.inputReady = s.inputFocused = true;
        s.remoteText = "가상 발화"; s.remoteCount = 1;
        flow.step(s, 1); flow.step(s, 701);
        flow.replyResult("가상 답변", 701);
        flow.step(s, 701); flow.actionResult(true, 701);
        s.inputEmpty = false; s.inputMatchesReply = s.submitReady = true;
        s.submitBounds = "bounds";
        flow.step(s, 951); flow.step(s, 1251);
        flow.actionResult(true, 1251);
        assertEquals(NONE, flow.step(s, 1252));
        assertEquals(1, flow.getConversation().size());
        assertEquals(NONE, flow.step(s, 16252));
        assertEquals("SEND_UNCONFIRMED", flow.getFailure().name());
        assertEquals(NONE, flow.step(s, 20000));
    }
}
