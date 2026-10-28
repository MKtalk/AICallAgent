package com.aicallagent.app.model;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Main-thread, content-free audit of actual UI effects and input observations. */
public final class InputTrace {
    private final LongSupplier clock;
    private final Consumer<String> sink;
    private long call, effect, callStartedAt, callWrites, totalWrites;
    private boolean active;
    private String lastObservation = "";

    public InputTrace(LongSupplier clock, Consumer<String> sink) {
        this.clock = clock;
        this.sink = sink;
    }

    public void systemState(Boolean inCall, boolean confirmedIdle) {
        if (confirmedIdle && active) {
            emit("phase=CALL_END");
            active = false;
            lastObservation = "";
        } else if (Boolean.TRUE.equals(inCall) && !active) {
            active = true;
            call++;
            callWrites = 0;
            callStartedAt = clock.getAsLong();
            lastObservation = "";
            emit("phase=CALL_START");
        }
    }

    /** Call inside the existing safety guard, immediately around the actual Android effect. */
    public boolean dispatch(AutoReplyFlow.Action action, BooleanSupplier operation) {
        effect++;
        if (action == AutoReplyFlow.Action.SET_REPLY || action == AutoReplyFlow.Action.CLEAR_DRAFT) {
            callWrites++;
            totalWrites++;
        }
        emit("phase=BEFORE action=" + action.name());
        boolean accepted = false;
        boolean threw = true;
        try {
            accepted = operation.getAsBoolean();
            threw = false;
            return accepted;
        } finally {
            emit("phase=AFTER action=" + action.name() + " accepted=" + accepted + " threw=" + threw);
        }
    }

    public void observe(boolean enabled, AutoReplyFlow.Stage stage, AutoReplyFlow.Snapshot screen) {
        // Never include text, identities, bounds, hashes, exceptions or node objects.
        String state = "phase=OBSERVE enabled=" + enabled + " stage=" + stage.name()
                + " window=" + screen.allowedWindow + " text_call=" + screen.textCall
                + " input_ready=" + screen.inputReady + " input_empty=" + screen.inputEmpty
                + " focused=" + screen.inputFocused + " reply_match=" + screen.inputMatchesReply
                + " owned_match=" + screen.inputMatchesOwnedDraft + " remote=" + screen.remoteCount;
        String key = effect + ":" + state;
        if (key.equals(lastObservation)) return;
        lastObservation = key;
        emit(state);
    }

    private void emit(String detail) {
        sink.accept("call=" + call + " effect=" + effect + " active=" + active
                + " elapsed_ms=" + (active ? Math.max(0, clock.getAsLong() - callStartedAt) : 0)
                + " " + detail + " call_writes=" + callWrites + " total_writes=" + totalWrites);
    }
}
