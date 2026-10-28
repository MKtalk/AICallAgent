package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class TranscriptReplayTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    private void start() {
        screen.allowedWindow = true; flow.enable(screen, 0); screen.incoming = true;
        flow.step(screen, 0); flow.actionResult(true, 0); screen.incoming = false;
        screen.textCall = screen.inputReady = screen.inputFocused = true;
        flow.step(screen, 1);
    }

    private void message(String text, String identity) {
        screen.remoteCount = 1; screen.remoteText = text;
        screen.remoteMessages.clear(); screen.remoteMessages.add(text);
        screen.remoteIdentities.clear(); screen.remoteIdentities.add(identity);
    }

    private void request() {
        flow.step(screen, now); now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
    }

    private void send() {
        flow.replyResult("가상 회신", now);
        now = ContinuousCallFlowTest.send(flow, screen, now);
    }

    @Test public void oneUtteranceWithLatePeriodAndChangedPositionProducesOnlyOneReply() {
        start(); message("견적 문의", "row:1"); request();
        message("견적 문의.", "row:2");
        flow.step(screen, now + 100);
        send();
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals(2, flow.getConversation().size());
    }

    @Test public void unchangedTranscriptWithNewPositionAfterSendingIsNotAnotherUtterance() {
        start(); message("견적 문의", "row:1"); request(); send();
        message("견적 문의", "row:5");
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void whitespaceAndFinalPunctuationOnlyChangesAreIgnoredAcrossPositions() {
        start(); message("서울  욕실 공사", "row:1"); request(); send();
        message(" 서울\n욕실 공사。 ", "row:2");
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
    }

    @Test public void meaningfulContentChangeAtNewPositionIsStillProcessed() {
        start(); message("면적 1.5평", "row:1"); request(); send();
        message("면적 15평.", "row:2"); request();
        assertEquals("면적 15평.", flow.getRequestText());
    }

    @Test public void increasedMessageCountProvesNewIdenticalUtterance() {
        start(); message("네", "row:1"); request(); send();
        screen.remoteCount = 2; screen.remoteText = "네.";
        screen.remoteMessages.add("네."); screen.remoteIdentities.add("row:3");
        request();
        assertEquals("네.", flow.getRequestText());
    }

    @Test public void oldTranscriptReappearingAfterAnotherTurnDoesNotProduceNewRequest() {
        start(); message("욕실 공사", "row:1"); request(); send();
        message("서울입니다", "row:3"); request(); send();
        message("욕실 공사.", "row:8");
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
    }

    @Test public void confirmedCallEndClearsReplayMemory() {
        start(); message("견적 문의", "row:1"); request(); send();
        flow.confirmCallEnded();
        screen.textCall = false; screen.remoteCount = 0; screen.remoteText = "";
        screen.remoteMessages.clear(); screen.remoteIdentities.clear(); screen.incoming = true;
        flow.step(screen, now); flow.actionResult(true, now);
        screen.incoming = false; screen.textCall = true;
        message("견적 문의.", "row:1"); request();
    }

    @Test public void meaningfulWordsArrivingWhileBusyRemainQueued() {
        start(); message("견적 문의", "row:1"); request();
        message("견적 문의.", "row:2"); flow.step(screen, now + 100);
        message("서울입니다", "row:3"); flow.step(screen, now + 200);
        flow.replyResult("이전 답변", now + 200);
        assertEquals("", flow.getReplyText());
        now += 200; request();
        assertEquals("견적 문의\n서울입니다", flow.getRequestText());
    }
}
