package com.aicallagent.app.model;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class NetworkRetryFlowTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    private void start() {
        screen.allowedWindow = true;
        flow.enable(screen, 0); screen.incoming = true;
        flow.step(screen, 0); flow.actionResult(true, 0);
        screen.incoming = false;
        screen.textCall = screen.inputReady = screen.inputFocused = true;
        flow.step(screen, 1);
    }

    private void request(String text, int count) {
        screen.remoteText = text; screen.remoteCount = count;
        flow.step(screen, now); now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
    }

    @Test public void secondTurnNetworkFailureRetriesSameHistoryAndSendsOneReply() {
        start(); request("가상 첫 문의", 1);
        flow.replyResult("가상 첫 답변", now);
        now = ContinuousCallFlowTest.send(flow, screen, now);
        request("가상 두 번째 문의", 2);
        List<CallConversation.Message> history = flow.getConversation();
        flow.networkFailure(now);
        assertEquals("WAIT_LLM_RETRY", flow.getStage().name());
        assertEquals("", flow.getReplyText());
        assertEquals(NONE, flow.step(screen, now + 1999));
        assertEquals(REQUEST_REPLY, flow.step(screen, now + 2000));
        assertEquals(history.size(), flow.getConversation().size());
        assertEquals("가상 두 번째 문의", flow.getRequestText());
        flow.replyResult("가상 두 번째 답변", now + 2000);
        ContinuousCallFlowTest.send(flow, screen, now + 2000);
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals(4, flow.getConversation().size());
    }

    @Test public void secondNetworkFailureStopsAndNeverRetriesForever() {
        start(); request("가상 문의", 1);
        flow.networkFailure(now);
        assertEquals(REQUEST_REPLY, flow.step(screen, now + 2000));
        flow.networkFailure(now + 2001);
        assertEquals(FAILED, flow.getStage());
        assertEquals(AutoReplyFlow.Failure.LLM_FAILED, flow.getFailure());
        assertEquals(NONE, flow.step(screen, now + 10000));
        assertTrue(flow.getConversation().isEmpty());
    }

    @Test public void retryBudgetIsResetForEachNewTurn() {
        start();
        for (int turn = 1; turn <= 2; turn++) {
            request("가상 문의 " + turn, turn);
            flow.networkFailure(now);
            now += 2000;
            assertEquals(REQUEST_REPLY, flow.step(screen, now));
            flow.replyResult("가상 답변", now);
            now = ContinuousCallFlowTest.send(flow, screen, now);
        }
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void additionalSpeechWhileRetryingReplacesStaleRequestWithCombinedTurn() {
        start(); request("가상 문의", 1); flow.networkFailure(now);
        screen.remoteCount = 2; screen.remoteText = "가상 추가 문의";
        assertEquals(NONE, flow.step(screen, now + 500));
        now += 2000;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
        assertEquals("가상 문의\n가상 추가 문의", flow.getRequestText());
        flow.replyResult("가상 답변", now);
        now = ContinuousCallFlowTest.send(flow, screen, now);
        flow.step(screen, now);
        assertEquals(NONE, flow.step(screen, now + 700));
        assertEquals(2, flow.getConversation().size());
    }

    @Test public void callEndAndDisablingCancelPendingRetry() {
        for (boolean disable : new boolean[]{false, true}) {
            AutoReplyFlow local = new AutoReplyFlow();
            AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
            s.allowedWindow = true; local.enable(s, 0); s.incoming = true;
            local.step(s, 0); local.actionResult(true, 0); s.incoming = false;
            s.textCall = s.inputReady = true; s.remoteText = "가상 문의"; s.remoteCount = 1;
            local.step(s, 1); local.step(s, 701); local.networkFailure(702);
            if (disable) local.disable(); else local.confirmCallEnded();
            assertEquals(NONE, local.step(s, 4000));
            assertTrue(local.getConversation().isEmpty());
            assertEquals("", local.getRequestText());
        }
    }

    @Test public void otherFailureOrLateNetworkResultDoesNotRetry() {
        start(); request("가상 문의", 1);
        flow.replyResult(null, now);
        flow.networkFailure(now + 1);
        assertEquals(FAILED, flow.getStage());
        assertEquals(NONE, flow.step(screen, now + 2000));
    }

    @Test public void expiredRequestCannotBeRevivedByNetworkFailure() {
        start(); request("가상 문의", 1);
        flow.networkFailure(now + 30001);
        assertEquals(AutoReplyFlow.Failure.TIMEOUT, flow.getFailure());
    }

    @Test public void lostWindowOrExistingInputPreventsRetryEffects() {
        start(); request("가상 문의", 1); flow.networkFailure(now);
        screen.allowedWindow = false;
        assertEquals(NONE, flow.step(screen, now + 2000));
        screen.allowedWindow = true; screen.inputEmpty = false;
        assertEquals(NONE, flow.step(screen, now + 2100));
        assertEquals(AutoReplyFlow.Failure.EXISTING_INPUT, flow.getFailure());
    }
}
