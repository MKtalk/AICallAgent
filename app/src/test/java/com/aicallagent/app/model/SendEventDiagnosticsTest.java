package com.aicallagent.app.model;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SendEventDiagnosticsTest {
    @Test public void onlyPendingSendStagesExpandEventScope() {
        for (AutoReplyFlow.Stage stage : AutoReplyFlow.Stage.values()) {
            boolean expected = stage == AutoReplyFlow.Stage.VERIFY_SENT || stage == AutoReplyFlow.Stage.WAIT_SPEECH;
            assertEquals(expected, SendEventDiagnostics.shouldExpand(true, stage));
            assertFalse(SendEventDiagnostics.shouldExpand(false, stage));
        }
    }

    @Test public void unrelatedAppCountsTypesWithoutReadingAnyContent() {
        SendEventDiagnostics trace = new SendEventDiagnostics();
        AtomicInteger reads = new AtomicInteger();
        String result = trace.record("private.other.package", 2048, false,
                () -> { reads.incrementAndGet(); return true; });
        assertEquals(0, reads.get());
        assertEquals("source=OTHER type=2048 count=1", result);
        assertEquals("source=OTHER type=2048 count=2", trace.record(null, 2048, true, () -> {
            fail("Other content must not be read"); return true;
        }));
    }

    @Test public void phoneAndSystemUiHaveOnlyFixedMetadataAndKnownNoticeFlag() {
        SendEventDiagnostics trace = new SendEventDiagnostics();
        assertEquals("source=PHONE type=2048 toast_class=false matched=true",
                trace.record("com.samsung.android.incallui", 2048, false, () -> true));
        assertEquals("source=SYSTEM_UI type=1024 toast_class=true matched=false",
                trace.record("com.android.systemui", 1024, true, () -> false));
    }

    @Test public void clearingCountsSeparatesEachPendingSend() {
        SendEventDiagnostics trace = new SendEventDiagnostics();
        trace.record("other", 2048, false, () -> false);
        trace.clear();
        assertEquals("source=OTHER type=2048 count=1", trace.record("other", 2048, false, () -> false));
    }
}
