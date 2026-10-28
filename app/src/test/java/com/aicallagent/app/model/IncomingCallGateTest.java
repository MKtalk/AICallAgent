package com.aicallagent.app.model;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class IncomingCallGateTest {
    private final IncomingCallGate gate = new IncomingCallGate();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();

    private void incoming(String label) {
        screen.allowedWindow = true;
        screen.incoming = IncomingCallGate.isIncomingLabel(label);
    }

    @Test public void outgoingCallWithSharedAnswerContainerNeverTriggersHeadsetHook() {
        incoming("Incoming call"); // Even a stale incoming label must not override OFFHOOK.
        AtomicInteger effects = new AtomicInteger();
        assertFalse(gate.observe(true, false, screen, 0));
        assertFalse(gate.observe(true, false, screen, 3000));
        assertFalse(gate.dispatch(false, screen, 3000, () -> { effects.incrementAndGet(); return true; }));
        assertEquals(0, effects.get());
    }

    @Test public void flowRemainsWaitingDuringOutgoingAndDelaysTheNextRealIncoming() {
        AutoReplyFlow flow = new AutoReplyFlow();
        screen.allowedWindow = true;
        flow.enable(screen, 0);
        for (long now : new long[]{0, 2000, 5000}) {
            incoming("Incoming call"); // Shared container/stale label on an outgoing screen.
            screen.incoming = gate.observe(true, false, screen, now);
            assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, now));
            assertEquals(AutoReplyFlow.Stage.WAIT_INCOMING, flow.getStage());
        }
        incoming("수신전화");
        screen.incoming = gate.observe(true, true, screen, 6000);
        assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, 6000));
        incoming("수신전화");
        screen.incoming = gate.observe(true, true, screen, 7999);
        assertEquals(AutoReplyFlow.Action.NONE, flow.step(screen, 7999));
        incoming("수신전화");
        screen.incoming = gate.observe(true, true, screen, 8000);
        assertEquals(AutoReplyFlow.Action.ANSWER, flow.step(screen, 8000));
        assertEquals(AutoReplyFlow.Stage.WAIT_CONNECTED, flow.getStage());
    }

    @Test public void incomingWaitsTwoSecondsAndDispatchesExactlyOnce() {
        incoming("수신전화");
        assertFalse(gate.observe(true, true, screen, 1000));
        assertFalse(gate.observe(true, true, screen, 2999));
        assertTrue(gate.observe(true, true, screen, 3000));
        AtomicInteger effects = new AtomicInteger();
        assertTrue(gate.dispatch(true, screen, 3000, () -> { effects.incrementAndGet(); return true; }));
        assertFalse(gate.dispatch(true, screen, 3001, () -> { effects.incrementAndGet(); return true; }));
        assertEquals(1, effects.get());
    }

    @Test public void manualAnswerOrDialingDuringDelayCancelsIt() {
        incoming("Incoming call");
        gate.observe(true, true, screen, 0);
        screen.connected = true;
        assertFalse(gate.observe(true, false, screen, 1999));
        assertFalse(gate.dispatch(false, screen, 2000, () -> { fail("must not hang up"); return true; }));
        screen.connected = false;
        assertFalse(gate.observe(true, true, screen, 3000));
        assertFalse(gate.observe(true, true, screen, 4999));
        assertTrue(gate.observe(true, true, screen, 5000));
    }

    @Test public void lossOfWindowPermissionLabelOrEnablementRestartsWholeDelay() {
        for (int kind = 0; kind < 4; kind++) {
            gate.reset(); incoming("Incoming call");
            gate.observe(true, true, screen, 0);
            if (kind == 0) screen.allowedWindow = false;
            if (kind == 1) screen.incoming = false;
            assertFalse(gate.observe(kind != 2, kind == 3 ? null : true, screen, 1500));
            incoming("Incoming call");
            assertFalse(gate.observe(true, true, screen, 2000));
            assertFalse(gate.observe(true, true, screen, 3999));
            assertTrue(gate.observe(true, true, screen, 4000));
        }
    }

    @Test public void freshSystemAndUiChecksCanVetoAnExpiredTimer() {
        incoming("Incoming call");
        gate.observe(true, true, screen, 0);
        assertTrue(gate.observe(true, true, screen, 2000));
        assertFalse(gate.dispatch(null, screen, 2000, () -> { fail(); return true; }));
        assertFalse(gate.observe(true, true, screen, 2001));
        gate.observe(true, true, screen, 4001);
        screen.textCall = true;
        assertFalse(gate.dispatch(true, screen, 4001, () -> { fail(); return true; }));
    }

    @Test public void rejectedActionIsNotAutomaticallyRepeatedAndExplicitResetStartsNewCall() {
        incoming("Incoming call");
        gate.observe(true, true, screen, 0);
        gate.observe(true, true, screen, 2000);
        assertFalse(gate.dispatch(true, screen, 2000, () -> false));
        assertFalse(gate.observe(true, true, screen, 4000));
        gate.reset();
        assertFalse(gate.observe(true, true, screen, 4000));
        assertTrue(gate.observe(true, true, screen, 6000));
    }

    @Test public void onlyFullIncomingLabelsAreAccepted() {
        assertTrue(IncomingCallGate.isIncomingLabel("Incoming call"));
        assertTrue(IncomingCallGate.isIncomingLabel("  INCOMING\nCALL  "));
        assertTrue(IncomingCallGate.isIncomingLabel("수신전화"));
        assertTrue(IncomingCallGate.isIncomingLabel("수신 전화"));
        for (String text : new String[]{null, "", "Calling", "전화 거는 중", "Outgoing call", "통화 중",
                "Incoming call from someone", "수신전화 기록"}) {
            assertFalse(IncomingCallGate.isIncomingLabel(text));
        }
    }
}
