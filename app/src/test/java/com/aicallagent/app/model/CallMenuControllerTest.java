package com.aicallagent.app.model;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class CallMenuControllerTest {
    private AutoReplyFlow.Snapshot connected() {
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = s.connected = s.assistReady = s.textOptionReady = true;
        return s;
    }

    @Test public void firstIncomingUsesOnlyOneDirectActionForEachMenuAndPreservesExistingInput() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true;
        flow.enable(s, 0); s.incoming = true;
        assertEquals(ANSWER, flow.step(s, 0));
        flow.actionResult(true, 0);
        s.incoming = false; s.connected = s.assistReady = true;
        AtomicInteger clicks = new AtomicInteger();
        assertEquals(OPEN_ASSIST, flow.step(s, 1));
        assertTrue(CallMenuController.dispatch(OPEN_ASSIST, s, () -> { clicks.incrementAndGet(); return true; }));
        flow.actionResult(true, 1);
        s.textOptionReady = true;
        assertEquals(OPEN_TEXT, flow.step(s, 2));
        assertTrue(CallMenuController.dispatch(OPEN_TEXT, s, () -> { clicks.incrementAndGet(); return true; }));
        flow.actionResult(true, 2);
        s.textCall = s.inputReady = true;
        s.inputEmpty = false;
        assertEquals(NONE, flow.step(s, 3));
        assertEquals(AutoReplyFlow.Failure.EXISTING_INPUT, flow.getFailure());
        assertFalse(s.inputEmpty);
        assertEquals(2, clicks.get());
    }

    @Test public void newlyOpenedTextScreenNeverReceivesOldMenuAction() {
        AutoReplyFlow.Snapshot s = connected();
        s.textCall = s.inputReady = true;
        AtomicInteger clicks = new AtomicInteger();
        assertFalse(CallMenuController.dispatch(OPEN_ASSIST, s, () -> { clicks.incrementAndGet(); return true; }));
        assertFalse(CallMenuController.dispatch(OPEN_TEXT, s, () -> { clicks.incrementAndGet(); return true; }));
        assertEquals(0, clicks.get());
    }

    @Test public void untrustedEndedRingingAndUnavailableMenusNeverClick() {
        for (int condition = 0; condition < 4; condition++) {
            AutoReplyFlow.Snapshot s = connected();
            if (condition == 0) s.allowedWindow = false;
            if (condition == 1) s.ended = true;
            if (condition == 2) s.incoming = true;
            if (condition == 3) s.assistReady = s.textOptionReady = false;
            AtomicInteger clicks = new AtomicInteger();
            assertFalse(CallMenuController.dispatch(OPEN_ASSIST, s, () -> { clicks.incrementAndGet(); return true; }));
            assertFalse(CallMenuController.dispatch(OPEN_TEXT, s, () -> { clicks.incrementAndGet(); return true; }));
            assertEquals(0, clicks.get());
        }
    }

    @Test public void rejectedMenuActionStopsWithoutAnotherAttempt() {
        AutoReplyFlow flow = new AutoReplyFlow();
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = true;
        flow.enable(s, 0); s.incoming = true;
        flow.step(s, 0); flow.actionResult(true, 0);
        s.incoming = false; s.connected = s.assistReady = true;
        assertEquals(OPEN_ASSIST, flow.step(s, 1));
        AtomicInteger clicks = new AtomicInteger();
        boolean accepted = CallMenuController.dispatch(OPEN_ASSIST, s, () -> { clicks.incrementAndGet(); return false; });
        flow.actionResult(accepted, 1);
        assertEquals(NONE, flow.step(s, 2));
        assertEquals(FAILED, flow.getStage());
        assertEquals(1, clicks.get());
    }

    @Test public void nonMenuActionsAndDisconnectedAssistNeverClick() {
        AutoReplyFlow.Snapshot s = connected();
        AtomicInteger clicks = new AtomicInteger();
        for (AutoReplyFlow.Action action : AutoReplyFlow.Action.values()) {
            if (action == OPEN_ASSIST || action == OPEN_TEXT) continue;
            assertFalse(CallMenuController.dispatch(action, s, () -> { clicks.incrementAndGet(); return true; }));
        }
        s.connected = false;
        assertFalse(CallMenuController.dispatch(OPEN_ASSIST, s, () -> { clicks.incrementAndGet(); return true; }));
        assertEquals(0, clicks.get());
    }
}
