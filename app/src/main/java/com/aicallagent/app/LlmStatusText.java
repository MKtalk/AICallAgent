package com.aicallagent.app;

import android.content.Context;

/** Shared body-free local model diagnostics. */
public final class LlmStatusText {
    private LlmStatusText() { }
    public static String error(Context context, String code) { return context.getString(resource(code)); }
    public static int resource(String code) {
        switch (code) {
            case "HASH_MISMATCH": return R.string.local_hash_mismatch;
            case "MODEL_SIZE": return R.string.local_size_error;
            case "MISSING_MODEL": return R.string.local_missing;
            case "INVALID_MODEL": return R.string.local_invalid_model;
            case "STORAGE": return R.string.local_storage_error;
            case "MEMORY": return R.string.local_memory_error;
            case "CANCELLED": return R.string.local_cancelled;
            case "TIMEOUT": return R.string.local_timeout;
            case "LOAD_FAILED": return R.string.local_load_failed;
            case "GENERATION_FAILED": return R.string.local_generation_failed;
            case "RELEASE_FAILED": return R.string.local_release_failed;
            case "CONTEXT_LIMIT": return R.string.reply_context_limit;
            case "INCOMPLETE_RESPONSE": return R.string.local_incomplete;
            case "LOCAL_BUSY": return R.string.local_busy;
            default: return R.string.local_failed;
        }
    }
}
