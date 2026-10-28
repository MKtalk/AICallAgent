package com.aicallagent.app.model;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;

public class InputTraceTest {
    private final List<String> lines = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1000);
    private final InputTrace trace = new InputTrace(clock::get, lines::add);

    private AutoReplyFlow.Snapshot input(boolean empty) {
        AutoReplyFlow.Snapshot s = new AutoReplyFlow.Snapshot();
        s.allowedWindow = s.textCall = s.inputReady = true;
        s.inputEmpty = empty;
        return s;
    }

    @Test public void secondCallExistingInputIsDistinguishedFromFirstCallWrites() {
        trace.systemState(true, false);
        trace.observe(true, AutoReplyFlow.Stage.WAIT_REMOTE, input(true));
        trace.dispatch(SET_REPLY, () -> true);
        trace.observe(true, AutoReplyFlow.Stage.VERIFY_INPUT, input(false));
        trace.observe(true, AutoReplyFlow.Stage.WAIT_REMOTE, input(true));
        trace.systemState(false, true);
        trace.systemState(true, false);
        trace.dispatch(OPEN_TEXT, () -> true);
        trace.observe(true, AutoReplyFlow.Stage.WAIT_TEXT_ACTIVE, input(false));
        String last = lines.get(lines.size() - 1);
        assertTrue(last.contains("call=2"));
        assertTrue(last.contains("input_empty=false"));
        assertTrue(last.contains("call_writes=0 total_writes=1"));
    }

    @Test public void attemptIsLoggedBeforeEffectEvenWhenEffectThrows() {
        trace.systemState(true, false);
        assertThrows(IllegalStateException.class, () -> trace.dispatch(SET_REPLY, () -> {
            assertTrue(lines.get(lines.size() - 1).contains("phase=BEFORE action=SET_REPLY"));
            throw new IllegalStateException("private-content");
        }));
        String last = lines.get(lines.size() - 1);
        assertTrue(last.contains("phase=AFTER action=SET_REPLY accepted=false threw=true"));
        assertTrue(last.contains("call_writes=1 total_writes=1"));
        assertFalse(String.join("\n", lines).contains("private-content"));
    }

    @Test public void rejectedEffectsAreNotRetriedOrReportedAsSuccessful() {
        int[] attempts = {0};
        assertFalse(trace.dispatch(CLEAR_DRAFT, () -> { attempts[0]++; return false; }));
        assertEquals(1, attempts[0]);
        assertTrue(lines.get(lines.size() - 1).contains("accepted=false threw=false"));
        assertTrue(lines.get(lines.size() - 1).contains("total_writes=1"));
    }

    @Test public void nonWritingActionsNeverIncrementWriteCounter() {
        for (AutoReplyFlow.Action action : new AutoReplyFlow.Action[]{ANSWER, OPEN_ASSIST, OPEN_TEXT, FOCUS_INPUT, SEND_REPLY}) {
            assertTrue(trace.dispatch(action, () -> true));
            assertTrue(lines.get(lines.size() - 1).contains("total_writes=0"));
        }
    }

    @Test public void observationsExcludeContentAndDeduplicateWithoutLosingEmptyTransition() {
        AutoReplyFlow.Snapshot s = input(false);
        s.remoteText = s.initialGreeting = s.submitBounds = "private-content";
        s.remoteMessages.add("private-content");
        s.remoteIdentities.add("private-content");
        trace.observe(false, AutoReplyFlow.Stage.OFF, s);
        int count = lines.size();
        clock.addAndGet(100);
        trace.observe(false, AutoReplyFlow.Stage.OFF, s);
        assertEquals(count, lines.size());
        s.inputEmpty = true;
        trace.observe(false, AutoReplyFlow.Stage.OFF, s);
        assertEquals(count + 1, lines.size());
        assertFalse(String.join("\n", lines).contains("private-content"));
    }

    @Test public void unknownAndBriefIdleDoNotInventNewCall() {
        trace.systemState(true, false);
        trace.systemState(null, false);
        trace.systemState(false, false);
        trace.systemState(true, false);
        trace.observe(false, AutoReplyFlow.Stage.OFF, input(true));
        assertTrue(lines.get(lines.size() - 1).contains("call=1"));
        trace.systemState(false, true);
        trace.systemState(false, true);
        trace.systemState(true, false);
        trace.observe(false, AutoReplyFlow.Stage.OFF, input(true));
        assertTrue(lines.get(lines.size() - 1).contains("call=2"));
    }

    @Test public void timestampMeasuresActualEffectAndObservationOrder() {
        trace.systemState(true, false);
        clock.addAndGet(250);
        trace.dispatch(OPEN_TEXT, () -> { clock.addAndGet(10); return true; });
        assertTrue(lines.get(lines.size() - 2).contains("elapsed_ms=250"));
        assertTrue(lines.get(lines.size() - 1).contains("elapsed_ms=260"));
    }
}
