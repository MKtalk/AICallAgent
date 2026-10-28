package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReplyDiagnosticsTest {
    @Test public void diagnosticExcludesTranscriptsGreetingAndCoordinates() {
        AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
        screen.remoteText = "private-caller-content";
        screen.initialGreeting = "private-greeting";
        screen.remoteMessages.add("private-message");
        screen.submitBounds = "private-coordinates";
        screen.remoteCount = 4;
        String result = ReplyDiagnostics.describe(AutoReplyFlow.Stage.VERIFY_INPUT,
                AutoReplyFlow.Failure.NONE, screen, 3, 3, 2);
        assertFalse(result.contains("private"));
        assertTrue(result.contains("stage=VERIFY_INPUT"));
        assertTrue(result.contains("remote=4"));
        assertTrue(result.contains("requests=3 responses=3 sent=2"));
    }

    @Test public void errorDiagnosticsWhitelistCodesAndDistinguishRecoveryFromShutdown() {
        assertEquals("code=INVALID_RESPONSE disposition=RETRY_NOTICE", ReplyDiagnostics.generationError("INVALID_RESPONSE", true));
        assertEquals("code=RELEASE_FAILED disposition=DISABLED", ReplyDiagnostics.generationError("RELEASE_FAILED", false));
        assertEquals("code=UNKNOWN disposition=DISABLED", ReplyDiagnostics.generationError("private transcript", false));
        assertEquals("code=LOCAL_FAILED disposition=DISABLED", ReplyDiagnostics.generationError("LOCAL_FAILED", false));
        assertTrue(ReplyDiagnostics.isRecoverableGenerationError("INCOMPLETE_RESPONSE"));
        assertFalse(ReplyDiagnostics.isRecoverableGenerationError(null));
        assertFalse(ReplyDiagnostics.isRecoverableGenerationError("GENERATION_FAILED"));
    }

    @Test public void stalledSendAndChangedInputHaveDistinctEvidence() {
        AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
        screen.allowedWindow = screen.inputReady = screen.inputMatchesReply = true;
        screen.inputEmpty = false;
        String waiting = ReplyDiagnostics.describe(AutoReplyFlow.Stage.VERIFY_INPUT,
                AutoReplyFlow.Failure.NONE, screen, 1, 1, 0);
        assertTrue(waiting.contains("input_match=true submit_ready=false"));
        screen.inputMatchesReply = false;
        screen.submitReady = true;
        String changed = ReplyDiagnostics.describe(AutoReplyFlow.Stage.FAILED,
                AutoReplyFlow.Failure.INPUT_CHANGED, screen, 1, 1, 0);
        assertTrue(changed.contains("failure=INPUT_CHANGED"));
        assertTrue(changed.contains("input_match=false submit_ready=true"));
        assertNotEquals(waiting, changed);
    }
}
