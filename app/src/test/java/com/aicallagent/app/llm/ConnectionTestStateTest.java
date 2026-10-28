package com.aicallagent.app.llm;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionTestStateTest {
    private ConnectionTestState state() { return new ConnectionTestState(); }
    private long start(ConnectionTestState state, long now) {
        return state.start(now);
    }
    private boolean finish(ConnectionTestState state, long token, long now, boolean success) {
        return state.finish(token, now, success);
    }
    private String status(ConnectionTestState state) {
        return state.getStatus().toString();
    }
    @Test public void duplicateClicksDoNotStartSecondRequest() throws Exception {
        ConnectionTestState state = state();
        assertTrue(start(state, 1000) > 0);
        assertEquals(0, start(state, 1001));
    }
    @Test public void callbackAfterLeavingScreenCannotShowSuccess() throws Exception {
        ConnectionTestState state = state(); long token = start(state, 1000);
        state.cancel();
        assertFalse(finish(state, token, 1001, true));
        assertEquals("CANCELLED", status(state));
    }
    @Test public void lateSuccessReportsTimeout() throws Exception {
        ConnectionTestState state = state(); long token = start(state, 1000);
        assertTrue(finish(state, token, 31000, true));
        assertEquals("TIMED_OUT", status(state));
    }
    @Test public void oldCallbackCannotFinishNewTest() throws Exception {
        ConnectionTestState state = state(); long old = start(state, 1000);
        finish(state, old, 1001, false);
        long next = start(state, 1002);
        assertFalse(finish(state, old, 1003, true));
        assertTrue(finish(state, next, 1004, true));
        assertEquals("SUCCEEDED", status(state));
    }
}
