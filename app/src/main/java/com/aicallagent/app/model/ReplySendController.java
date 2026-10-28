package com.aicallagent.app.model;

import java.util.function.BooleanSupplier;

/** Revalidates delivery preconditions before one semantic button action. */
public final class ReplySendController {
    private ReplySendController() { }

    public static boolean dispatch(AutoReplyFlow.Snapshot fresh, int previousReplyCount,
                                   BooleanSupplier buttonClick) {
        if (!fresh.allowedWindow || fresh.ended || !fresh.textCall || !fresh.inputReady
                || fresh.inputEmpty || !fresh.inputMatchesReply || !fresh.submitReady
                || fresh.replyCount != previousReplyCount) return false;
        // Neither a second click nor a coordinate fallback is safe after an ambiguous result.
        return buttonClick.getAsBoolean();
    }
}
