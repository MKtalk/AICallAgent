package com.aicallagent.app.model;

/** A fixed list of non-content fields; never serialize the snapshot itself. */
public final class ReplyDiagnostics {
    private ReplyDiagnostics() { }

    public static boolean isRecoverableGenerationError(String code) {
        return "INVALID_RESPONSE".equals(code) || "INCOMPLETE_RESPONSE".equals(code);
    }

    /** Never accepts exception messages or generated text as log values. */
    public static String generationError(String code, boolean recovered) {
        String safe = code == null ? "UNKNOWN" : switch (code) {
            case "INVALID_RESPONSE", "INCOMPLETE_RESPONSE", "GENERATION_FAILED", "MEMORY",
                    "RELEASE_FAILED", "TIMEOUT", "CONTEXT_LIMIT", "CANCEL_FAILED", "CANCELLED",
                    "LOAD_FAILED", "LOCAL_FAILED", "LOCAL_BUSY", "STORAGE", "MODEL_SIZE",
                    "INVALID_MODEL", "MISSING_MODEL", "HASH_MISMATCH" -> code;
            default -> "UNKNOWN";
        };
        return "code=" + safe + " disposition=" + (recovered ? "RETRY_NOTICE" : "DISABLED");
    }

    public static String describe(AutoReplyFlow.Stage stage, AutoReplyFlow.Failure failure,
                                  AutoReplyFlow.Snapshot screen, int requests, int responses, int sent) {
        return "stage=" + stage.name() + " failure=" + failure.name()
                + " window=" + screen.allowedWindow + " text_call=" + screen.textCall
                + " input_ready=" + screen.inputReady + " input_focused=" + screen.inputFocused
                + " input_empty=" + screen.inputEmpty + " input_match=" + screen.inputMatchesReply
                + " submit_ready=" + screen.submitReady + " remote=" + screen.remoteCount
                + " requests=" + requests + " responses=" + responses + " sent=" + sent;
    }
}
