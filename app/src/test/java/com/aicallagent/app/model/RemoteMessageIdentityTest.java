package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class RemoteMessageIdentityTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    private void start() {
        screen.allowedWindow = true;
        flow.enable(screen, 0); screen.incoming = true;
        flow.step(screen, 0); flow.actionResult(true, 0);
        screen.incoming = false;
        screen.connected = screen.textCall = screen.inputReady = screen.inputFocused = true;
        flow.step(screen, 1);
    }

    private void message(String text, String identity) {
        screen.remoteCount = 1;
        screen.remoteText = text;
        screen.remoteMessages.clear(); screen.remoteMessages.add(text);
        screen.remoteIdentities.clear(); screen.remoteIdentities.add(identity);
    }

    private void request() {
        assertEquals(NONE, flow.step(screen, now));
        now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
    }

    private void send() {
        flow.replyResult("가상 답변", now);
        now = ContinuousCallFlowTest.send(flow, screen, now);
    }

    @Test public void sameMessageTranscriptionCorrectionDoesNotCreateSecondReply() {
        start(); message("욕실 공사", "row:1"); request();
        message("욕실 공사를 상담하고 싶습니다", "row:1");
        flow.step(screen, now + 100);
        send();
        assertEquals(NONE, flow.step(screen, now + 1000));
        assertEquals(NONE, flow.step(screen, now + 2000));
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void sameCountWithoutIdentityCannotProveAnotherUtterance() {
        start(); screen.remoteText = "욕실 공사"; screen.remoteCount = 1;
        request();
        screen.remoteText = "욕실 공사를 상담하고 싶습니다";
        flow.step(screen, now + 100);
        send();
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void identityAppearingAfterRequestCannotReplayConsumedMessage() {
        start(); screen.remoteText = "욕실 공사"; screen.remoteCount = 1;
        request();
        message("욕실 공사를 상담하고 싶습니다", "row:1");
        flow.step(screen, now + 100);
        send();
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
        assertEquals(WAIT_REMOTE, flow.getStage());
        message("서울입니다", "row:3"); request();
    }

    @Test public void repeatedCollectionPositionsAreNotUsableIdentities() {
        screen.remoteCount = 2;
        screen.remoteMessages.add("공사 문의"); screen.remoteMessages.add("지역 안내");
        screen.remoteIdentities.add("row:1"); screen.remoteIdentities.add("row:1");
        assertFalse(AutoReplyFlow.hasRemoteIdentities(screen));
    }

    @Test public void sameContentAtDifferentPositionWithSameCountCannotProveNewUtterance() {
        start(); message("네", "row:1"); request(); send();
        message("네", "row:3");
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void olderMessageReappearingAfterScrollNeverProducesAnotherReply() {
        start(); message("욕실 공사", "row:1"); request(); send();
        message("서울입니다", "row:3"); request(); send();
        message("욕실 공사", "row:1");
        flow.step(screen, now + 1000);
        assertEquals(NONE, flow.step(screen, now + 2000));
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void newMessageArrivingDuringApiRequestIsStillQueued() {
        start(); message("욕실 공사", "row:1"); request();
        message("서울입니다", "row:3");
        flow.step(screen, now + 100);
        flow.replyResult("이전 답변", now + 100);
        assertEquals("", flow.getReplyText());
        now += 100;
        request();
        assertEquals("욕실 공사\n서울입니다", flow.getRequestText());
    }

    @Test public void messageIdentitiesAreClearedAtVerifiedCallEnd() {
        start(); message("네", "row:1"); request(); send();
        flow.confirmCallEnded();
        screen.textCall = screen.connected = false; screen.remoteCount = 0;
        screen.remoteText = ""; screen.remoteMessages.clear(); screen.remoteIdentities.clear();
        screen.incoming = true;
        assertEquals(ANSWER, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.incoming = false; screen.textCall = screen.connected = true;
        message("네", "row:1");
        flow.step(screen, now);
        now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
    }
}
