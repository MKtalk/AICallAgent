package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class LlmReplyFlowTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    private void request() {
        screen.allowedWindow = true;
        flow.enable(screen, now);
        screen.incoming = true;
        flow.step(screen, now);
        flow.actionResult(true, now);
        screen.incoming = false;
        screen.connected = true;
        screen.textCall = true;
        screen.inputReady = true;
        screen.inputFocused = true;
        screen.remoteText = "배송 시간을 알려주세요.";
        screen.remoteCount = 1;
        flow.step(screen, now);
        now += 700;
        assertEquals("REQUEST_REPLY", flow.step(screen, now).name());
        assertEquals("WAIT_LLM", flow.getStage().name());
    }

    private void reply(String value) throws Exception {
        flow.replyResult(value, now);
    }

    private String replyText() throws Exception {
        return flow.getReplyText();
    }

    @Test public void waitsForApiAndUsesItsReply() throws Exception {
        request();
        assertEquals("NONE", flow.step(screen, now).name());
        assertEquals("", replyText());
        reply("오후 3시에 배송합니다.");
        assertEquals("오후 3시에 배송합니다.", replyText());
        assertEquals("SET_REPLY", flow.step(screen, now).name());
    }

    @Test public void disabledCallRejectsLateReply() throws Exception {
        request(); flow.disable(); reply("늦은 응답");
        assertEquals("", replyText());
        assertEquals(AutoReplyFlow.Stage.OFF, flow.getStage());
    }

    @Test public void endedCallRejectsLateReply() throws Exception {
        request(); screen.ended = true; flow.step(screen, now); reply("늦은 응답");
        assertEquals("", replyText());
        assertEquals(AutoReplyFlow.Stage.WAIT_INCOMING, flow.getStage());
    }

    @Test public void emptyApiReplyFailsWithoutFixedFallback() throws Exception {
        request(); reply("   ");
        assertEquals(AutoReplyFlow.Stage.FAILED, flow.getStage());
        assertEquals("", replyText());
        assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, now));
    }

    @Test public void replyAfterDeadlineCannotType() throws Exception {
        request(); now += 30001; reply("늦은 응답");
        assertEquals(AutoReplyFlow.Stage.FAILED, flow.getStage());
        assertEquals("", replyText());
    }

    @Test public void sendsApiReplyOnceAndConfirmsDelivery() throws Exception {
        request();
        assertEquals("배송 시간을 알려주세요.", flow.getRequestText());
        reply("배송 시간을 확인하겠습니다.");
        assertEquals("", flow.getRequestText());
        assertEquals(AutoReplyFlow.Action.SET_REPLY, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.inputEmpty = false;
        screen.inputMatchesReply = true;
        screen.submitReady = true;
        screen.submitBounds = "verified send bounds";
        now += 250;
        assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, now));
        now += 300;
        assertEquals(AutoReplyFlow.Action.SEND_REPLY, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.inputEmpty = true;
        screen.replyCount = 1;
        flow.step(screen, now);
        assertEquals(AutoReplyFlow.Stage.WAIT_REMOTE, flow.getStage());
        assertEquals("", replyText());
        screen.remoteText = "두 번째 발화";
        screen.remoteCount = 2;
        assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, now));
    }

    @Test public void timeoutStopsOutstandingRequestWithoutResubmitting() throws Exception {
        request(); now += 30001; flow.step(screen, now);
        assertEquals(AutoReplyFlow.Failure.TIMEOUT, flow.getFailure());
        reply("늦은 답변");
        assertEquals("", replyText());
        assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, now));
    }

    @Test public void nullReplyDoesNotUseAcknowledgement() throws Exception {
        request(); reply(null);
        assertEquals(AutoReplyFlow.Failure.LLM_FAILED, flow.getFailure());
        assertEquals("", replyText());
    }

    @Test public void rejectsExcessiveReplyInsteadOfSendingTruncatedText() throws Exception {
        request(); reply("가".repeat(1001));
        assertEquals(AutoReplyFlow.Failure.LLM_FAILED, flow.getFailure());
        assertEquals("", replyText());
    }

    @Test public void reasoningMarkupIsNeverSpoken() throws Exception {
        request(); reply("<think>내부 추론</think>답변");
        assertEquals(AutoReplyFlow.Failure.LLM_FAILED, flow.getFailure());
        assertEquals("", replyText());
    }

    @Test public void preservesTextUserEnteredWhileApiWasRunning() throws Exception {
        request(); screen.inputEmpty = false; reply("API 응답");
        assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, now));
        assertEquals(AutoReplyFlow.Failure.EXISTING_INPUT, flow.getFailure());
    }
}
