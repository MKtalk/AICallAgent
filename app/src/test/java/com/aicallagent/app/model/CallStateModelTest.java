package com.aicallagent.app.model;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public class CallStateModelTest {

    @Test public void lastObservedTextCallIsNotErasedByEmptyPhoneScreen() throws Exception {
        Object observed = CallStateModel.lastObserved(CallStateModel.CallState.TEXT_CALL_ACTIVE, Collections.emptySet());
        assertEquals(CallStateModel.CallState.TEXT_CALL_ACTIVE, observed);
    }

    @Test public void verifiedEndReplacesLastObservedCall() throws Exception {
        Object observed = CallStateModel.lastObserved(CallStateModel.CallState.TEXT_CALL_ACTIVE,
                        Collections.singleton(CallStateModel.ID_CALL_ENDED));
        assertEquals(CallStateModel.CallState.ENDED, observed);
    }

    @Test
    public void testIdleWhenEmpty() {
        Set<String> visibleIds = Collections.emptySet();
        assertEquals(CallStateModel.CallState.IDLE, CallStateModel.determineState(visibleIds));
    }

    @Test
    public void testIncomingCallDetected() {
        Set<String> visibleIds = new HashSet<>();
        visibleIds.add(CallStateModel.ID_ANSWER_CONTAINER);
        assertEquals(CallStateModel.CallState.RINGING_INCOMING, CallStateModel.determineState(visibleIds));
    }

    @Test
    public void testConnectedVoiceCallDetected() {
        Set<String> visibleIds = new HashSet<>();
        visibleIds.add(CallStateModel.ID_CALL_TIME);
        assertEquals(CallStateModel.CallState.CONNECTED_VOICE, CallStateModel.determineState(visibleIds));
    }

    @Test
    public void testAiMenuAvailableDetected() {
        Set<String> visibleIds = new HashSet<>();
        visibleIds.add(CallStateModel.ID_CALL_TIME);
        visibleIds.add(CallStateModel.ID_AI_BUTTON);
        assertEquals(CallStateModel.CallState.AI_MENU_AVAILABLE, CallStateModel.determineState(visibleIds));
    }

    @Test
    public void testTextCallActiveDetected() {
        Set<String> visibleIds = new HashSet<>();
        visibleIds.add(CallStateModel.ID_CALL_TIME);
        visibleIds.add(CallStateModel.ID_TEXT_MESSAGE_EDIT);
        assertEquals(CallStateModel.CallState.TEXT_CALL_ACTIVE, CallStateModel.determineState(visibleIds));
    }

    @Test
    public void testCallEndedTakesPrecedence() {
        Set<String> visibleIds = new HashSet<>();
        visibleIds.add(CallStateModel.ID_CALL_TIME);
        visibleIds.add(CallStateModel.ID_TEXT_MESSAGE_EDIT);
        visibleIds.add(CallStateModel.ID_CALL_ENDED);
        assertEquals(CallStateModel.CallState.ENDED, CallStateModel.determineState(visibleIds));
    }

    @Test
    public void testNullSafety() {
        assertEquals(CallStateModel.CallState.IDLE, CallStateModel.determineState(null));
    }

    @Test
    public void testUnrelatedOrInvisibleViewsYieldIdle() {
        Set<String> visibleIds = new HashSet<>();
        visibleIds.add("com.samsung.android.incallui:id/unrelated_container");
        visibleIds.add("android:id/content");
        assertEquals("Unrelated visible IDs must result in IDLE state",
                CallStateModel.CallState.IDLE, CallStateModel.determineState(visibleIds));
    }

    @Test
    public void testEmptyVisibleIdsWhenViewsAreHiddenYieldIdle() {
        // When node.isVisibleToUser() is false, view IDs are not added to visibleIds set
        Set<String> emptyVisibleIds = new HashSet<>();
        assertEquals("Empty visible IDs (e.g. when elements are hidden) must yield IDLE",
                CallStateModel.CallState.IDLE, CallStateModel.determineState(emptyVisibleIds));
    }
}
