package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class CallLifecycleTest {
    @Test public void missedEndScreenReleasesCallAndAllowsNextIncoming() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
        screen.allowedWindow = true;
        flow.enable(screen, 0);
        screen.incoming = true;
        assertEquals(ANSWER, flow.step(screen, 0));
        flow.actionResult(true, 0);
        screen.incoming = false;
        screen.connected = screen.textCall = true;
        flow.step(screen, 1);
        assertEquals(WAIT_REMOTE, flow.getStage());
        CallLifecycle lifecycle = new CallLifecycle();
        assertFalse(lifecycle.observe(true, 0));
        assertFalse(lifecycle.observe(false, 1));
        assertTrue(lifecycle.observe(false, 701));
        assertTrue(flow.confirmCallEnded());
        assertEquals(WAIT_INCOMING, flow.getStage());
        screen.connected = screen.textCall = false;
        screen.incoming = true;
        assertEquals(ANSWER, flow.step(screen, 702));
    }

    @Test public void unknownAndTemporaryIdleNeverConfirmEnd() {
        CallLifecycle lifecycle = new CallLifecycle();
        lifecycle.observe(true, 0);
        assertFalse(lifecycle.observe(false, 1));
        assertFalse(lifecycle.observe(false, 700));
        assertFalse(lifecycle.observe(null, 701));
        assertFalse(lifecycle.observe(false, 702));
        assertFalse(lifecycle.observe(true, 1402));
    }

    @Test public void idleProofIsInvalidatedAsSoonAsAnotherCallStarts() {
        CallLifecycle lifecycle = new CallLifecycle();
        assertFalse(lifecycle.observe(false, 0));
        assertTrue(lifecycle.observe(false, 700));
        assertFalse(lifecycle.observe(true, 701));
        assertFalse(lifecycle.observe(false, 702));
    }

    @Test public void unknownDoesNotTreatMissingPermissionAsIdle() {
        CallLifecycle lifecycle = new CallLifecycle();
        assertFalse(lifecycle.observe(null, 0));
        assertFalse(lifecycle.observe(null, 10000));
    }

    @Test public void confirmedEndDiscardsPendingConversationAndLateReply() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true;
        flow.enable(s, 0);
        s.incoming = true;
        flow.step(s, 0);
        flow.actionResult(true, 0);
        s.incoming = false;
        s.textCall = s.connected = true;
        s.remoteText = "가상 상담 발화";
        s.remoteCount = 1;
        flow.step(s, 1);
        assertEquals(REQUEST_REPLY, flow.step(s, 701));
        assertFalse(flow.getConversation().isEmpty());
        flow.confirmCallEnded();
        flow.replyResult("이전 통화의 늦은 답변", 702);
        assertTrue(flow.getConversation().isEmpty());
        assertEquals("", flow.getReplyText());
        assertEquals(WAIT_INCOMING, flow.getStage());
    }

    @Test public void endWhileDisabledReleasesClaimWithoutEnablingAutomation() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = s.connected = true;
        flow.enable(s, 0);
        assertEquals(WAIT_END, flow.getStage());
        flow.disable();
        assertTrue(flow.confirmCallEnded());
        assertFalse(flow.isEnabled());
        assertEquals(OFF, flow.getStage());
        s.connected = false;
        flow.enable(s, 1);
        s.incoming = true;
        assertEquals(ANSWER, flow.step(s, 1));
    }
}
