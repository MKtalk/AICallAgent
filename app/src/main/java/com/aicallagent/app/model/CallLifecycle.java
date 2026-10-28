package com.aicallagent.app.model;

/** Confirms idle from repeated system readings, never from a missing UI or permission. */
public final class CallLifecycle {
    private long idleSince = -1;

    public boolean observe(Boolean inCall, long now) {
        if (inCall == null || inCall) {
            idleSince = -1;
            return false;
        }
        if (idleSince < 0) idleSince = now;
        return now - idleSince >= 700;
    }
}
