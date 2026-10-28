package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class RetainedReplyDraftTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    private void startCall(boolean retainedInput) {
        screen = new AutoReplyFlow.Snapshot();
        screen.allowedWindow = true;
        screen.incoming = true;
        assertEquals(ANSWER, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.incoming = false;
        screen.connected = screen.textCall = screen.inputReady = screen.inputFocused = true;
        screen.inputEmpty = !retainedInput;
        screen.inputMatchesOwnedDraft = retainedInput;
        flow.step(screen, now);
    }

    private void insertReply() {
        screen.allowedWindow = true;
        flow.enable(screen, now);
        startCall(false);
        screen.remoteText = "과자";
        screen.remoteCount = 1;
        flow.step(screen, now);
        now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
        flow.replyResult("자장가", now);
        assertEquals(SET_REPLY, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.inputEmpty = false;
        screen.inputMatchesReply = true;
    }

    @Test public void hangupAfterInputMustNotBlockNextCallWithOwnDraft() {
        insertReply();
        screen.ended = true;
        flow.step(screen, ++now);
        assertEquals("", flow.getReplyText());
        startCall(true);
        assertNotEquals("An app-written draft must be recovered before listening", FAILED, flow.getStage());
        finishCleanupAndRequestNewReply();
    }

    @Test public void confirmedSystemIdleMustNotLoseDraftOwnership() {
        insertReply();
        flow.confirmCallEnded();
        startCall(true);
        assertNotEquals("System idle must allow recovery of the retained draft", FAILED, flow.getStage());
        finishCleanupAndRequestNewReply();
    }

    private void finishCleanupAndRequestNewReply() {
        assertEquals(VERIFY_DRAFT_CLEARED, flow.getStage());
        assertTrue(flow.canClearDraft(screen));
        flow.actionResult(true, now);
        assertEquals(NONE, flow.step(screen, ++now));
        assertEquals(VERIFY_DRAFT_CLEARED, flow.getStage());
        // Successful ACTION_SET_TEXT alone is insufficient; wait for an empty UI.
        screen.inputEmpty = true;
        screen.inputMatchesOwnedDraft = false;
        assertEquals(NONE, flow.step(screen, ++now));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals("", flow.getOwnedDraft());
        assertTrue(flow.getConversation().isEmpty());
        screen.remoteCount = 1;
        screen.remoteText = "기차";
        assertEquals(NONE, flow.step(screen, ++now));
        now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
        assertEquals(1, flow.getConversation().size());
        assertEquals("기차", flow.getRequestText());
    }

    @Test public void ownershipStartsOnlyAfterAcceptedInputAction() {
        screen.allowedWindow = true;
        flow.enable(screen, now);
        startCall(false);
        screen.remoteCount = 1; screen.remoteText = "과자";
        flow.step(screen, now); now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
        flow.replyResult("자장가", now);
        assertEquals(SET_REPLY, flow.step(screen, now));
        assertEquals("", flow.getOwnedDraft());
        flow.actionResult(false, now);
        assertEquals("", flow.getOwnedDraft());
    }

    @Test public void acceptedInputOwnershipSurvivesEndWithoutKeepingConversation() {
        insertReply();
        assertEquals("자장가", flow.getOwnedDraft());
        flow.confirmCallEnded();
        assertEquals("자장가", flow.getOwnedDraft());
        assertEquals("", flow.getReplyText());
        assertTrue(flow.getConversation().isEmpty());
        assertFalse(flow.canClearDraft(screen));
    }

    @Test public void editedDraftIsPreservedAndOwnershipForgotten() {
        insertReply(); flow.confirmCallEnded();
        screen = new AutoReplyFlow.Snapshot();
        screen.allowedWindow = screen.incoming = true;
        assertEquals(ANSWER, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.incoming = false;
        screen.connected = screen.textCall = screen.inputReady = true;
        screen.inputEmpty = false;
        screen.inputMatchesOwnedDraft = false;
        assertEquals(NONE, flow.step(screen, now));
        assertEquals(AutoReplyFlow.Failure.EXISTING_INPUT, flow.getFailure());
        assertEquals("", flow.getOwnedDraft());
        assertFalse(flow.canClearDraft(screen));
    }

    @Test public void matchingFlagWithoutOwnedDraftDoesNotAllowCleanup() {
        screen.allowedWindow = true; flow.enable(screen, now);
        screen.inputMatchesOwnedDraft = true;
        startCall(true);
        assertEquals(FAILED, flow.getStage());
        assertFalse(flow.canClearDraft(screen));
    }

    @Test public void clearRevalidatesWindowCallInputAndOwnershipBeforeEffect() {
        insertReply(); flow.confirmCallEnded(); startCall(true);
        assertTrue(flow.canClearDraft(screen));
        screen.inputMatchesOwnedDraft = false;
        assertFalse(flow.canClearDraft(screen));
        screen.inputMatchesOwnedDraft = true; screen.allowedWindow = false;
        assertFalse(flow.canClearDraft(screen));
        screen.allowedWindow = true; screen.ended = true;
        assertFalse(flow.canClearDraft(screen));
        screen.ended = false; screen.textCall = false;
        assertFalse(flow.canClearDraft(screen));
        screen.textCall = true; screen.inputReady = false;
        assertFalse(flow.canClearDraft(screen));
        screen.inputReady = true; screen.inputEmpty = true;
        assertFalse(flow.canClearDraft(screen));
        screen.inputEmpty = false; flow.disable();
        assertFalse(flow.canClearDraft(screen));
    }

    @Test public void userEditDuringCleanupIsNeverClearedAgain() {
        insertReply(); flow.confirmCallEnded(); startCall(true);
        flow.actionResult(true, now);
        screen.inputMatchesOwnedDraft = false;
        assertEquals(NONE, flow.step(screen, ++now));
        assertEquals(AutoReplyFlow.Failure.INPUT_CHANGED, flow.getFailure());
        assertFalse(flow.canClearDraft(screen));
        assertEquals("", flow.getOwnedDraft());
    }

    @Test public void rejectedCleanupDoesNotRepeatOrGenerateReply() {
        insertReply(); flow.confirmCallEnded(); startCall(true);
        flow.actionResult(false, now);
        assertEquals(FAILED, flow.getStage());
        assertEquals(NONE, flow.step(screen, ++now));
    }

    @Test public void unconfirmedCleanupTimesOutWithoutRepeating() {
        insertReply(); flow.confirmCallEnded(); startCall(true);
        flow.actionResult(true, now);
        assertEquals(NONE, flow.step(screen, now + 15_001));
        assertEquals(AutoReplyFlow.Failure.TIMEOUT, flow.getFailure());
    }

    @Test public void confirmedSendReleasesOwnership() {
        insertReply();
        screen.submitReady = true; screen.submitBounds = "verified bounds";
        assertEquals(NONE, flow.step(screen, now + 250));
        assertEquals(SEND_REPLY, flow.step(screen, now + 550));
        flow.actionResult(true, now + 550);
        screen.inputEmpty = true; screen.replyCount = 1;
        assertEquals(NONE, flow.step(screen, now + 551));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals("", flow.getOwnedDraft());
    }

    @Test public void togglingOffCannotClearDraftInAnExistingCall() {
        insertReply(); flow.disable(); flow.enable(screen, now);
        assertEquals(WAIT_END, flow.getStage());
        assertEquals(NONE, flow.step(screen, ++now));
        assertFalse(flow.canClearDraft(screen));
    }

    @Test public void alreadyEmptyNextCallForgetsOldOwnershipBeforeUserTypes() {
        insertReply(); flow.confirmCallEnded(); startCall(false);
        assertEquals("", flow.getOwnedDraft());
        screen.inputEmpty = false; screen.inputMatchesOwnedDraft = true;
        assertEquals(NONE, flow.step(screen, ++now));
        assertEquals(AutoReplyFlow.Failure.EXISTING_INPUT, flow.getFailure());
        assertFalse(flow.canClearDraft(screen));
    }
}
