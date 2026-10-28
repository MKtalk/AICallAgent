package com.aicallagent.app.model;

import org.junit.Test;
import com.aicallagent.app.policy.AiTargetPolicy;

import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;
import static org.junit.Assert.*;

public class AutoReplyFlowTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    private void enable() {
        screen.allowedWindow = true;
        flow.enable(screen, now);
    }

    private AutoReplyFlow.Action step() { return flow.step(screen, now); }

    private void completeAction() { flow.actionResult(true, now); }

    private void reachRemote() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        completeAction();
        screen.incoming = false;
        screen.connected = true;
        screen.assistReady = true;
        assertEquals(OPEN_ASSIST, step());
        completeAction();
        screen.textOptionReady = true;
        assertEquals(OPEN_TEXT, step());
        completeAction();
        screen.textCall = true;
        screen.inputReady = true;
        screen.inputFocused = true;
        assertEquals(NONE, step());
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    private void reachInput() {
        reachRemote();
        screen.remoteText = "상대방 테스트 발화";
        screen.remoteCount = 1;
        assertEquals(NONE, step());
        now += 700;
        assertEquals(REQUEST_REPLY, step());
        flow.replyResult("테스트 API 응답", now);
        assertEquals(SET_REPLY, step());
        completeAction();
    }

    private void reachSend() {
        reachInput();
        screen.inputEmpty = false;
        screen.inputMatchesReply = true;
        screen.submitReady = true;
        screen.submitBounds = "10 20 50 60";
        now += 250;
        assertEquals(NONE, step());
        now += 300;
        assertEquals(SEND_REPLY, step());
    }

    @Test public void incomingRepliesThenWaitsForNextUtterance() {
        reachSend();
        completeAction();
        screen.inputEmpty = true;
        screen.replyCount = 1;
        assertEquals(NONE, step());
        assertEquals(WAIT_REMOTE, flow.getStage());
        screen.remoteText = "두 번째 발화";
        screen.remoteCount = 2;
        now += 1000;
        assertEquals(NONE, step());
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void invalidGenerationSendsRetryNoticeAndAcceptsSameWordInNextRow() {
        reachRemote();
        screen.remoteText = "과자"; screen.remoteCount = 1;
        step(); now += 700;
        assertEquals(REQUEST_REPLY, step());
        assertTrue(flow.generationFailure("INVALID_RESPONSE", now));
        assertTrue(flow.isEnabled());
        assertEquals(WordChainRules.RETRY_NOTICE, flow.getReplyText());
        assertEquals(SET_REPLY, step()); completeAction();
        screen.inputEmpty = false; screen.inputMatchesReply = true;
        screen.submitReady = true; screen.submitBounds = "10 20 50 60";
        now += 250; step(); now += 300;
        assertEquals(SEND_REPLY, step()); completeAction();
        screen.inputEmpty = true; screen.replyCount = 1;
        step(); assertEquals(WAIT_REMOTE, flow.getStage());
        screen.remoteCount = 2; now += 1000; step(); now += 700;
        assertEquals(REQUEST_REPLY, step());
        assertEquals("자장가", WordChainRules.guardReply(flow.getConversation(), "자장가"));
        flow.replyResult("자장가", now);
        assertEquals(WAIT_INPUT, flow.getStage());
        assertTrue(flow.isEnabled());
    }

    @Test public void incompleteOutputEntersNormalSendFlowAndCannotBeAppliedTwice() {
        reachRemote(); screen.remoteText = "과자"; screen.remoteCount = 1;
        step(); now += 700; assertEquals(REQUEST_REPLY, step());
        assertTrue(flow.generationFailure("INCOMPLETE_RESPONSE", now));
        assertEquals(WAIT_INPUT, flow.getStage());
        assertFalse(flow.generationFailure("INCOMPLETE_RESPONSE", now));
    }

    @Test public void expiredGenerationIsNotRevivedByRecoverableError() {
        reachRemote(); screen.remoteText = "과자"; screen.remoteCount = 1;
        step(); now += 700; assertEquals(REQUEST_REPLY, step());
        assertFalse(flow.generationFailure("INVALID_RESPONSE", now + 30_001));
        assertEquals("", flow.getReplyText());
    }

    @Test public void executionErrorsAreNotConvertedToSpokenRetryNotices() {
        reachRemote(); screen.remoteText = "과자"; screen.remoteCount = 1;
        step(); now += 700; assertEquals(REQUEST_REPLY, step());
        for (String code : new String[]{"MEMORY", "GENERATION_FAILED", "RELEASE_FAILED", "TIMEOUT"}) {
            assertFalse(flow.generationFailure(code, now));
            assertEquals(WAIT_LLM, flow.getStage());
            assertEquals("", flow.getReplyText());
        }
    }

    @Test public void defaultsOffAndIgnoresIncoming() {
        screen.allowedWindow = true;
        screen.incoming = true;
        assertEquals(NONE, step());
        assertEquals(OFF, flow.getStage());
    }

    @Test public void rejectsUntrustedWindow() {
        enable();
        screen.incoming = true;
        screen.allowedWindow = false;
        assertEquals(NONE, step());
    }

    @Test public void headsetHookNeverRepeatsWhileAnswering() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        assertEquals(NONE, step());
        completeAction();
        assertEquals(NONE, step());
    }

    @Test public void enablingDuringExistingCallDoesNotTakeOver() {
        screen.connected = true;
        enable();
        screen.assistReady = true;
        assertEquals(NONE, step());
        assertEquals(WAIT_END, flow.getStage());
    }

    @Test public void mixedIncomingAndConnectedDoesNotHangUp() {
        enable();
        screen.incoming = true;
        screen.connected = true;
        assertEquals(NONE, step());
        assertEquals(WAIT_END, flow.getStage());
    }

    @Test public void togglingAfterAnswerDoesNotIssueAnotherHook() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        completeAction();
        flow.disable();
        flow.enable(screen, now);
        assertEquals(NONE, step());
        assertEquals(WAIT_END, flow.getStage());
    }

    @Test public void cancelledActionFailsAndNeverRetries() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        flow.actionResult(false, now);
        assertEquals(FAILED, flow.getStage());
        assertEquals(NONE, step());
    }

    @Test public void neverRepliesBeforeRemoteSpeech() {
        reachRemote();
        now += 5000;
        assertEquals(NONE, step());
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void transcriptionChangesRestartStabilityTimer() {
        reachRemote();
        screen.remoteText = "첫";
        screen.remoteCount = 1;
        step();
        now += 600;
        screen.remoteText = "첫 문장";
        assertEquals(NONE, step());
        now += 600;
        assertEquals(NONE, step());
        now += 100;
        assertEquals(REQUEST_REPLY, step());
        assertEquals(WAIT_LLM, flow.getStage());
    }

    @Test public void speechAlreadyPresentWhenTextScreenAppearsIsNotLost() {
        reachRemote();
        screen.remoteCount = 1;
        screen.remoteText = "빠른 발화";
        step();
        now += 700;
        step();
        assertEquals(WAIT_LLM, flow.getStage());
    }

    @Test public void preservesExistingInput() {
        reachRemote();
        screen.inputEmpty = false;
        assertEquals(NONE, step());
        assertEquals(FAILED, flow.getStage());
        assertEquals(AutoReplyFlow.Failure.EXISTING_INPUT, flow.getFailure());
    }

    @Test public void inputRecheckPreventsSendingUserEditedText() {
        reachInput();
        screen.inputEmpty = false;
        screen.inputMatchesReply = false;
        assertEquals(NONE, step());
        assertEquals(FAILED, flow.getStage());
    }

    @Test public void changedSendPositionRestartsStabilityTimer() {
        reachInput();
        screen.inputEmpty = false;
        screen.inputMatchesReply = true;
        screen.submitReady = true;
        screen.submitBounds = "first";
        now += 250;
        step();
        now += 299;
        screen.submitBounds = "second";
        assertEquals(NONE, step());
        now += 299;
        assertEquals(NONE, step());
        now += 1;
        assertEquals(SEND_REPLY, step());
    }

    @Test public void sendCompletionRequiresBothEvidenceChecks() {
        reachSend();
        completeAction();
        screen.inputEmpty = true;
        assertEquals(NONE, step());
        assertEquals(VERIFY_SENT, flow.getStage());
        screen.inputEmpty = false;
        screen.replyCount = 1;
        assertEquals(NONE, step());
        assertEquals(VERIFY_SENT, flow.getStage());
        screen.inputEmpty = true;
        step();
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void failedSendVerificationDoesNotResend() {
        reachSend();
        completeAction();
        now += 15001;
        assertEquals(NONE, step());
        assertEquals(FAILED, flow.getStage());
        assertEquals(NONE, step());
    }

    @Test public void idleWaitDoesNotExpireWhenWindowUnavailable() {
        reachRemote();
        screen.allowedWindow = false;
        now += 120001;
        assertEquals(NONE, step());
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void pendingGestureCannotAdvanceAfterDisable() {
        reachSend();
        flow.disable();
        completeAction();
        assertEquals(NONE, step());
        assertEquals(OFF, flow.getStage());
    }

    @Test public void callEndStopsBeforeAnySend() {
        reachInput();
        screen.ended = true;
        assertEquals(NONE, step());
        assertEquals(WAIT_INCOMING, flow.getStage());
    }

    @Test public void nextCallCanBeAnsweredAfterObservedEnd() {
        reachSend();
        completeAction();
        screen.ended = true;
        step();
        screen.ended = false;
        screen.connected = false;
        screen.textCall = false;
        screen.remoteCount = 0;
        screen.remoteText = "";
        screen.incoming = true;
        assertEquals(ANSWER, step());
    }

    @Test public void unknownWindowNeverReleasesCallLock() {
        reachSend();
        completeAction();
        screen.inputEmpty = true;
        screen.replyCount = 1;
        step();
        screen.allowedWindow = false;
        now += 10000;
        step();
        screen.allowedWindow = true;
        screen.incoming = true;
        assertEquals(NONE, step());
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void emptyTransitionWindowDoesNotReleaseAnswerLock() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        completeAction();
        screen.incoming = false;
        step();
        now += 2500;
        step();
        screen.incoming = true;
        assertEquals(NONE, step());
        assertEquals(WAIT_CONNECTED, flow.getStage());
    }

    @Test public void firstSpeechDuringTextTransitionIsDetected() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        completeAction();
        screen.incoming = false;
        screen.connected = true;
        screen.assistReady = true;
        assertEquals(OPEN_ASSIST, step());
        completeAction();
        screen.textOptionReady = true;
        assertEquals(OPEN_TEXT, step());
        completeAction();
        screen.textCall = true;
        screen.remoteText = "화면 전환 중 발화";
        screen.remoteCount = 1;
        assertEquals(NONE, step());
        now += 700;
        step();
        assertEquals(WAIT_LLM, flow.getStage());
    }

    @Test public void oldRemoteBeforeTransitionIsNotANewUtterance() {
        enable();
        screen.incoming = true;
        step();
        completeAction();
        screen.incoming = false;
        screen.connected = true;
        screen.assistReady = true;
        step();
        completeAction();
        screen.textOptionReady = true;
        screen.remoteText = "기존 기록";
        screen.remoteCount = 1;
        assertEquals(OPEN_TEXT, step());
        completeAction();
        screen.textCall = true;
        step();
        now += 1000;
        step();
        assertEquals(WAIT_REMOTE, flow.getStage());
        screen.remoteCount = 2;
        step();
        now += 700;
        step();
        assertEquals(WAIT_LLM, flow.getStage());
    }

    @Test public void focusesOnceBeforeSettingReply() {
        reachRemote();
        screen.remoteText = "발화";
        screen.remoteCount = 1;
        step();
        now += 700;
        step();
        flow.replyResult("테스트 API 응답", now);
        screen.inputFocused = false;
        assertEquals(FOCUS_INPUT, step());
        completeAction();
        assertEquals(NONE, step());
        screen.inputFocused = true;
        assertEquals(SET_REPLY, step());
    }

    @Test public void textConversionTimeoutIsNotRemoteWaitTimeout() {
        enable();
        screen.incoming = true;
        step();
        completeAction();
        screen.incoming = false;
        screen.connected = true;
        screen.assistReady = true;
        step();
        completeAction();
        screen.textOptionReady = true;
        assertEquals(OPEN_TEXT, step());
        completeAction();
        now += 15001;
        assertEquals(NONE, step());
        assertEquals(FAILED, flow.getStage());
    }

    @Test public void disappearanceOfObservedSpeechDoesNotDiscardBufferedText() {
        reachRemote();
        screen.remoteText = "중간 전사";
        screen.remoteCount = 1;
        step();
        now += 700;
        screen.remoteText = "";
        assertEquals(REQUEST_REPLY, step());
        assertEquals(WAIT_LLM, flow.getStage());
        assertEquals("중간 전사", flow.getRequestText());
    }

    @Test public void confirmationNeedsNewLocalMessageBeyondExistingReply() {
        screen.replyCount = 2;
        reachSend();
        completeAction();
        screen.inputEmpty = true;
        step();
        assertEquals(VERIFY_SENT, flow.getStage());
        screen.replyCount = 3;
        step();
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void retainedAnswerContainerDoesNotBlockAssistAfterAutoAnswer() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        completeAction();
        // Current device: answer_fragment_container AND call_time remain visible together.
        screen.connected = true;
        screen.assistReady = true;
        screen.incoming = screen.isRinging();
        assertFalse("Fresh execution guard must not reject this connected call", screen.incoming);
        assertEquals(OPEN_ASSIST, step());
        completeAction();
        screen.textOptionReady = true;
        assertEquals(OPEN_TEXT, step());
    }

    @Test public void ringingClassificationRequiresNoConnectedOrTextEvidence() {
        screen.incoming = true;
        assertTrue(screen.isRinging());
        screen.connected = true;
        assertFalse(screen.isRinging());
        screen.connected = false;
        screen.textCall = true;
        assertFalse(screen.isRinging());
        screen.textCall = false;
        screen.remoteCount = 1;
        assertFalse(screen.isRinging());
        screen.remoteCount = 0;
        screen.incoming = false;
        assertFalse(screen.isRinging());
    }

    @Test public void apiReplyIsNotReplacedWithFixedAcknowledgement() {
        reachInput();
        assertEquals("테스트 API 응답", flow.getReplyText());
    }

    @Test public void englishPhoneUiMovesFromAutoAnswerToTextCall() {
        enable();
        screen.incoming = true;
        assertEquals(ANSWER, step());
        completeAction();
        screen.incoming = false;
        screen.connected = true;
        screen.assistReady = AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID,
                AiTargetPolicy.VERIFIED_LABEL, AiTargetPolicy.VERIFIED_ID, "Call assist");
        assertEquals(OPEN_ASSIST, step());
        completeAction();
        screen.textOptionReady = AiTargetPolicy.matchesTextCall(CallStateModel.ID_TEXT_CALL_BUTTON,
                "Text call, button");
        assertEquals(OPEN_TEXT, step());
        completeAction();
        screen.textCall = true;
        step();
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

}
