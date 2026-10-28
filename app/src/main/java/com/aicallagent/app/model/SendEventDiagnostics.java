package com.aicallagent.app.model;

import com.aicallagent.app.policy.CallPackagePolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Fixed metadata only. Unknown sources must never invoke the content supplier. */
public final class SendEventDiagnostics {
    private final Map<Integer, Integer> otherCounts = new HashMap<>();

    public static boolean shouldExpand(boolean enabled, AutoReplyFlow.Stage stage) {
        return enabled && (stage == AutoReplyFlow.Stage.VERIFY_SENT || stage == AutoReplyFlow.Stage.WAIT_SPEECH);
    }

    public String record(CharSequence packageName, int type, boolean toastClass, BooleanSupplier knownNotice) {
        if (CallPackagePolicy.isAllowedPackage(packageName)) {
            return "source=PHONE type=" + type + " toast_class=" + toastClass + " matched=" + knownNotice.getAsBoolean();
        }
        if ("com.android.systemui".contentEquals(packageName == null ? "" : packageName)) {
            return "source=SYSTEM_UI type=" + type + " toast_class=" + toastClass + " matched=" + knownNotice.getAsBoolean();
        }
        int count = otherCounts.getOrDefault(type, 0) + 1;
        otherCounts.put(type, count);
        return "source=OTHER type=" + type + " count=" + count;
    }

    public void clear() { otherCounts.clear(); }
}
