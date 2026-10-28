package com.aicallagent.app.local;

import com.aicallagent.app.model.CallConversation;
import com.aicallagent.app.model.WordChainRules;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class WordChainGenerationTest {
    private ReplyRequest snack() {
        return ReplyRequest.forCall(List.of(new CallConversation.Message("assistant", "사과."),
                new CallConversation.Message("user", "과자")));
    }

    @Test public void validModelWordIsReturnedWithoutReplacementOrRepair() throws Exception {
        assertEquals("자장가.", WordChainGeneration.generate(snack(), () -> false, repair -> {
            assertFalse(repair);
            return "자장가.";
        }));
    }

    @Test public void englishTestAcceptsAppleWithoutRepair() throws Exception {
        ReplyRequest request = ReplyRequest.test(false);
        assertEquals("Apple", WordChainGeneration.generate(request, () -> false, repair -> {
            assertFalse(repair);
            return "Apple.";
        }));
    }

    @Test public void englishTestRepairsAndAcceptsAppleOnSecondAttempt() throws Exception {
        ReplyRequest request = ReplyRequest.test(false);
        List<Boolean> passes = new ArrayList<>();
        String answer = WordChainGeneration.generate(request, () -> false, repair -> {
            passes.add(repair);
            return repair ? "Apple" : "Banana";
        });
        assertEquals(List.of(false, true), passes);
        assertEquals("Apple", answer);
    }

    @Test public void oneSyllableIsRepairedByAnotherModelAttemptAndNeverInventedByCode() throws Exception {
        ReplyRequest request = snack();
        List<Boolean> passes = new ArrayList<>();
        String answer = WordChainGeneration.generate(request, () -> false, repair -> {
            passes.add(repair);
            return repair ? "자장가." : "자";
        });
        assertEquals(List.of(false, true), passes);
        assertEquals("자장가.", answer);
        assertEquals(2, request.history.size());
        assertEquals("과자", request.latest().content);
    }

    @Test public void earlyModelConcessionIsCheckedOnceAndCanBecomeANewModelWord() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String answer = WordChainGeneration.generate(snack(), () -> false, repair -> {
            calls.incrementAndGet();
            return repair ? "자장가." : WordChainRules.LOSS;
        });
        assertEquals(2, calls.get());
        assertEquals("자장가.", answer);
    }

    @Test public void formerlyBlockedWordStillReachesModelAndConfirmedConcessionIsPreserved() throws Exception {
        ReplyRequest request = ReplyRequest.forCall(List.of(new CallConversation.Message("user", "차례")));
        AtomicInteger calls = new AtomicInteger();
        String answer = WordChainGeneration.generate(request, () -> false, repair -> {
            calls.incrementAndGet();
            return repair ? WordChainRules.LOSS : "례";
        });
        assertEquals(2, calls.get());
        assertEquals("차례", request.latest().content);
        assertEquals(WordChainRules.LOSS, answer);
    }

    @Test public void twoInvalidAttemptsFailWithoutAProgrammaticWordOrConcession() {
        AtomicInteger calls = new AtomicInteger();
        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                WordChainGeneration.generate(snack(), () -> false, repair -> {
                    calls.incrementAndGet(); return "자";
                }));
        assertEquals(2, calls.get());
        assertEquals("INVALID_RESPONSE", failure.code);
    }

    @Test public void confirmedConcessionRequiresExactlyTwoModelAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(WordChainRules.LOSS, WordChainGeneration.generate(snack(), () -> false, repair -> {
            calls.incrementAndGet(); return WordChainRules.LOSS;
        }));
        assertEquals(2, calls.get());
    }

    @Test public void cancellationDiscardsAnswerAndPreventsRepair() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        assertEquals("", WordChainGeneration.generate(snack(), cancelled::get, repair -> {
            calls.incrementAndGet(); cancelled.set(true); return "자";
        }));
        assertEquals(1, calls.get());
        assertEquals("", WordChainGeneration.generate(snack(), () -> true, repair -> {
            fail("cancelled before first model call"); return "";
        }));
    }

    @Test public void incompleteOrFailedNativeGenerationIsNotRetriedOrReplaced() {
        AtomicInteger calls = new AtomicInteger();
        ReplyFailure failure = assertThrows(ReplyFailure.class, () ->
                WordChainGeneration.generate(snack(), () -> false, repair -> {
                    calls.incrementAndGet(); throw new ReplyFailure("INCOMPLETE_RESPONSE");
                }));
        assertEquals(1, calls.get());
        assertEquals("INCOMPLETE_RESPONSE", failure.code);
    }
}
