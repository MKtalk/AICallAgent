package com.aicallagent.app.model;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Requires continuous incoming proof before a single headset-hook action. */
public final class IncomingCallGate {
    private static final long ANSWER_DELAY_MS = 2000;
    private long incomingSince = -1;
    private boolean attempted;

    public static boolean isIncomingLabel(CharSequence label) {
        if (label == null || label.length() > 64) return false;
        String value = label.toString().trim().replaceAll("\\s+", " ");
        return value.toLowerCase(Locale.ROOT).equals("incoming call")
                || value.replace(" ", "").equals("수신전화");
    }

    private static boolean eligible(Boolean systemRinging, AutoReplyFlow.Snapshot screen) {
        return Boolean.TRUE.equals(systemRinging) && screen.allowedWindow && !screen.ended && screen.isRinging();
    }

    public boolean observe(boolean enabled, Boolean systemRinging, AutoReplyFlow.Snapshot screen, long now) {
        if (!enabled || !eligible(systemRinging, screen)) {
            reset();
            return false;
        }
        if (attempted) return false;
        if (incomingSince < 0 || now < incomingSince) incomingSince = now;
        return now - incomingSince >= ANSWER_DELAY_MS;
    }

    public boolean dispatch(Boolean freshSystemRinging, AutoReplyFlow.Snapshot fresh, long now,
                            BooleanSupplier answer) {
        if (!eligible(freshSystemRinging, fresh)) {
            reset();
            return false;
        }
        if (attempted || incomingSince < 0 || now - incomingSince < ANSWER_DELAY_MS) return false;
        attempted = true;
        return answer.getAsBoolean();
    }

    public void reset() { incomingSince = -1; attempted = false; }
}
