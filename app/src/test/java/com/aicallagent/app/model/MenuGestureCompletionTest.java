package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class MenuGestureCompletionTest {
    @Test public void asyncMenusWaitForCompletionAndPreserveUnknownDraft() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true;
        flow.enable(s, 0); s.incoming = true;
        assertEquals(ANSWER, flow.step(s, 0)); flow.actionResult(true, 0);
        s.incoming = false; s.connected = s.assistReady = true;
        assertEquals(OPEN_ASSIST, flow.step(s, 1));
        var assist = new CallMenuController.GestureCompletion(OPEN_ASSIST, 1);
        s.textOptionReady = true;
        assertEquals(NONE, flow.step(s, 2));
        assertTrue(assist.accept(1, true, flow.getStage(), true));
        flow.actionResult(true, 81);
        assertEquals(OPEN_TEXT, flow.step(s, 82));
        var text = new CallMenuController.GestureCompletion(OPEN_TEXT, 1);
        s.textCall = s.inputReady = true; s.inputEmpty = false;
        assertEquals(NONE, flow.step(s, 83));
        assertTrue(text.accept(1, true, flow.getStage(), true));
        flow.actionResult(true, 162);
        assertEquals(NONE, flow.step(s, 163));
        assertEquals(AutoReplyFlow.Failure.EXISTING_INPUT, flow.getFailure());
        assertFalse(s.inputEmpty);
        assertEquals("", flow.getReplyText());
    }

    @Test public void onlyFirstCompletionIsAccepted() {
        var completion = new CallMenuController.GestureCompletion(OPEN_TEXT, 5);
        assertTrue(completion.accept(5, true, WAIT_TEXT_ACTIVE, true));
        assertFalse(completion.accept(5, true, WAIT_TEXT_ACTIVE, true));
    }

    @Test public void priorCallCannotCompleteNextCallsMatchingStage() {
        var completion = new CallMenuController.GestureCompletion(OPEN_TEXT, 5);
        assertFalse(completion.accept(6, true, WAIT_TEXT_ACTIVE, true));
    }

    @Test public void disabledEndedTimedOutOrUnknownCallCannotComplete() {
        assertFalse(new CallMenuController.GestureCompletion(OPEN_TEXT, 5).accept(5, false, WAIT_TEXT_ACTIVE, true));
        assertFalse(new CallMenuController.GestureCompletion(OPEN_TEXT, 5).accept(5, true, WAIT_INCOMING, true));
        assertFalse(new CallMenuController.GestureCompletion(OPEN_TEXT, 5).accept(5, true, FAILED, true));
        assertFalse(new CallMenuController.GestureCompletion(OPEN_TEXT, 5).accept(5, true, WAIT_TEXT_ACTIVE, false));
        assertFalse(new CallMenuController.GestureCompletion(OPEN_TEXT, 5).accept(5, true, WAIT_TEXT_ACTIVE, null));
    }

    @Test public void cancellationStopsWithoutRepeatingMenuAction() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true; flow.enable(s, 0); s.incoming = true;
        flow.step(s, 0); flow.actionResult(true, 0);
        s.incoming = false; s.connected = s.assistReady = true;
        assertEquals(OPEN_ASSIST, flow.step(s, 1));
        var completion = new CallMenuController.GestureCompletion(OPEN_ASSIST, 5);
        assertTrue(completion.accept(5, true, flow.getStage(), true));
        flow.actionResult(false, 2);
        assertEquals(FAILED, flow.getStage());
        assertEquals(NONE, flow.step(s, 3));
        assertFalse(completion.accept(5, true, flow.getStage(), true));
    }

    @Test public void nonMenuActionsCannotCreateGestureCompletion() {
        for (AutoReplyFlow.Action a : AutoReplyFlow.Action.values()) {
            if (a != OPEN_ASSIST && a != OPEN_TEXT) {
                assertThrows(IllegalArgumentException.class, () -> new CallMenuController.GestureCompletion(a, 0));
            }
        }
    }

    @Test public void negativeOrEmptyBoundsNeverBuildOrScheduleTouch() {
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = s.connected = s.assistReady = s.textOptionReady = true;
        int[][] invalidBounds = {
                {-1, 10, 50, 50},
                {10, -1, 50, 50},
                {50, 10, 50, 50},
                {60, 10, 50, 50},
                {10, 50, 50, 50},
                {10, 60, 50, 50}
        };
        for (int[] bounds : invalidBounds) {
            assertFalse(CallMenuController.dispatchTouch(OPEN_ASSIST, s,
                    bounds[0], bounds[1], bounds[2], bounds[3], () -> {
                        fail("Gesture builder must not run on invalid bounds");
                        return true;
                    }));
        }
    }

    @Test public void gestureCreationOrDispatchExceptionsFailClosedWithoutThrowing() {
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = s.connected = s.assistReady = true;
        RuntimeException[] exceptions = {
                new IllegalArgumentException("negative path"),
                new IllegalStateException("service detached"),
                new SecurityException("gesture denied")
        };
        for (RuntimeException failure : exceptions) {
            AutoReplyFlow flow = new AutoReplyFlow();
            AutoReplyFlow.Snapshot ringing = new AutoReplyFlow.Snapshot();
            ringing.allowedWindow = ringing.incoming = true;
            flow.enable(ringing, 0);
            assertEquals(ANSWER, flow.step(ringing, 0));
            flow.actionResult(true, 0);
            assertEquals(OPEN_ASSIST, flow.step(s, 1));
            boolean scheduled = CallMenuController.dispatchTouch(OPEN_ASSIST, s, 100, 200, 300, 280, () -> {
                throw failure;
            });
            assertFalse(scheduled);
            flow.actionResult(scheduled, 1);
            assertEquals(FAILED, flow.getStage());
            assertEquals(AutoReplyFlow.Failure.ACTION_REJECTED, flow.getFailure());
        }
    }

    @Test public void validBoundsAndMenuStateScheduleTouchOnce() {
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = s.connected = s.assistReady = true;
        int[] calls = {0};
        assertTrue(CallMenuController.dispatchTouch(OPEN_ASSIST, s, 0, 0, 100, 80, () -> {
            calls[0]++;
            return true;
        }));
        assertEquals(1, calls[0]);
        s.textCall = true;
        assertFalse(CallMenuController.dispatchTouch(OPEN_ASSIST, s, 0, 0, 100, 80, () -> {
            calls[0]++;
            return true;
        }));
        assertEquals(1, calls[0]);
    }
}
