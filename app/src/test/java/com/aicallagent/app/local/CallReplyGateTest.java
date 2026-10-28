package com.aicallagent.app.local;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallReplyGateTest {
    @Test public void newRequestInSameCallRejectsPreviousCallback() {
        CallReplyGate gate = new CallReplyGate();
        CallReplyGate.Ticket old = gate.start(1, 1000), next = gate.start(1, 1100);
        assertFalse(gate.accepts(old, 1, 1200));
        assertTrue(gate.accepts(next, 1, 1200));
    }
    @Test public void callEndDisableAndNextCallRejectStaleReplies() {
        CallReplyGate gate = new CallReplyGate();
        CallReplyGate.Ticket ticket = gate.start(1, 1000);
        assertFalse(gate.accepts(ticket, 2, 1001));
        gate.cancel();
        assertFalse(gate.accepts(ticket, 1, 1001));
    }
    @Test public void deadlineIsExclusiveAndResultCanBeConsumedOnlyOnce() {
        CallReplyGate gate = new CallReplyGate();
        CallReplyGate.Ticket ticket = gate.start(1, 1000);
        assertFalse(gate.accepts(ticket, 1, 31000));
        assertTrue(gate.accepts(ticket, 1, 30999));
        gate.cancel();
        assertFalse(gate.accepts(ticket, 1, 30999));
    }
}
