package com.aicallagent.app.model;

import java.util.function.BooleanSupplier;

/** Guards one menu action; never permits input or send controls. */
public final class CallMenuController {
    private CallMenuController() { }

    /** One-shot callback guard for a menu gesture scheduled in a particular call generation. */
    public static final class GestureCompletion {
        private final long generation;
        private final AutoReplyFlow.Stage expectedStage;
        private boolean consumed;

        public GestureCompletion(AutoReplyFlow.Action action, long generation) {
            if (action == AutoReplyFlow.Action.OPEN_ASSIST) expectedStage = AutoReplyFlow.Stage.WAIT_TEXT;
            else if (action == AutoReplyFlow.Action.OPEN_TEXT) expectedStage = AutoReplyFlow.Stage.WAIT_TEXT_ACTIVE;
            else throw new IllegalArgumentException("MENU_ACTION_REQUIRED");
            this.generation = generation;
        }

        public boolean accept(long currentGeneration, boolean enabled, AutoReplyFlow.Stage stage, Boolean inCall) {
            if (consumed) return false;
            consumed = true;
            return generation == currentGeneration && enabled && stage == expectedStage
                    && Boolean.TRUE.equals(inCall);
        }
    }

    public static boolean dispatch(AutoReplyFlow.Action action, AutoReplyFlow.Snapshot fresh,
                                   BooleanSupplier buttonClick) {
        if (!fresh.allowedWindow || fresh.ended || fresh.incoming || fresh.textCall) return false;
        if (action == AutoReplyFlow.Action.OPEN_ASSIST) {
            if (!fresh.connected || !fresh.assistReady) return false;
        } else if (action == AutoReplyFlow.Action.OPEN_TEXT) {
            if (!fresh.textOptionReady) return false;
        } else {
            return false;
        }
        return buttonClick.getAsBoolean();
    }

    public static boolean dispatchTouch(AutoReplyFlow.Action action, AutoReplyFlow.Snapshot fresh,
                                        int left, int top, int right, int bottom,
                                        BooleanSupplier touchOperation) {
        if (left < 0 || top < 0 || right <= left || bottom <= top) return false;
        try {
            return dispatch(action, fresh, touchOperation);
        } catch (IllegalStateException | IllegalArgumentException | SecurityException exception) {
            return false;
        }
    }
}
