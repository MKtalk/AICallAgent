package com.aicallagent.app.local;

import com.aicallagent.app.model.AutoReplyFlow;
import com.aicallagent.app.model.CallConversation;
import com.aicallagent.app.model.WordChainRules;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class GenerationTraceTest {
    @Test public void rejectedAttemptsHaveSameRequestIdAndDistinctReasonsWithoutTheirWords() {
        ReplyRequest request = ReplyRequest.forCall(List.of(new CallConversation.Message("user", "과자")));
        List<String> lines = new ArrayList<>();
        GenerationTrace trace = new GenerationTrace(request, lines::add);
        trace.start(false);
        assertThrows(ReplyFailure.class, () -> WordChainGeneration.generate(request, () -> false,
                repair -> repair ? "나무" : "자", trace));
        assertTrue(lines.stream().allMatch(line -> line.startsWith("id=" + request.diagnosticId + " ")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("attempt=1 phase=RULES") && line.contains("reason=TOO_SHORT")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("attempt=2 phase=RULES") && line.contains("reason=WRONG_START")));
        assertTrue(lines.get(lines.size()-1).contains("detail=EXHAUSTED"));
        assertEquals(2, lines.stream().filter(line -> line.contains("phase=ATTEMPT") && line.contains("detail=START")).count());
        assertFalse(String.join("\n", lines).matches("(?s).*[가-힣].*"));
    }

    @Test public void contentsPathsAndExceptionMessagesNeverReachTheSink() throws Exception {
        String privateUser = "PRIVATE_USER_TEXT";
        String privateAnswer = "PRIVATE_MODEL_TEXT";
        ReplyRequest request = ReplyRequest.forCall(List.of(new CallConversation.Message("user", privateUser)));
        List<String> lines = new ArrayList<>();
        GenerationTrace trace = new GenerationTrace(request, lines::add);
        trace.start(true);
        assertEquals(privateAnswer, WordChainGeneration.generate(request, () -> false, repair -> privateAnswer, trace));
        trace.shape(1, privateAnswer);
        trace.failure(1, GenerationTrace.Phase.NATIVE, new IllegalStateException("PRIVATE_EXCEPTION /private/model.litertlm"));
        trace.output(1, ReplyRequest.OutputProblem.TOKEN_LIMIT, 100, 128);
        String log = String.join("\n", lines);
        for (String secret : new String[]{privateUser, privateAnswer, "PRIVATE_EXCEPTION", "/private/", "litertlm", request.system})
            assertFalse(log.contains(secret));
        assertTrue(log.contains("backend=GPU"));
        assertTrue(log.contains("detail=NATIVE_EXCEPTION"));
        assertTrue(log.contains("reason=TOKEN_LIMIT chars=100 decoded=128"));
        assertTrue(lines.stream().allMatch(line -> line.matches("[A-Za-z0-9_= .-]+")));
    }

    @Test public void cancellationAndNativeFailureDoNotLookLikeRuleRejectionOrStartExtraAttempts() throws Exception {
        ReplyRequest request = ReplyRequest.test();
        List<String> lines = new ArrayList<>();
        GenerationTrace trace = new GenerationTrace(request, lines::add);
        AtomicBoolean cancelled = new AtomicBoolean();
        assertEquals("", WordChainGeneration.generate(request, cancelled::get, repair -> {
            cancelled.set(true); return "과자";
        }, trace));
        assertTrue(lines.get(lines.size()-1).contains("detail=CANCELLED"));
        assertFalse(lines.stream().anyMatch(line -> line.contains("phase=RULES") || line.contains("attempt=2")));
        lines.clear();
        assertThrows(ReplyFailure.class, () -> WordChainGeneration.generate(request, () -> false,
                repair -> { throw new ReplyFailure("INCOMPLETE_RESPONSE"); }, trace));
        assertTrue(lines.get(lines.size()-1).contains("detail=INCOMPLETE_RESPONSE"));
        assertFalse(lines.stream().anyMatch(line -> line.contains("attempt=2")));
    }

    @Test public void callerRuleProblemIsSeparatedFromGeneratedWordProblem() {
        List<CallConversation.Message> history = List.of(new CallConversation.Message("user", "사과"),
                new CallConversation.Message("assistant", "과자"), new CallConversation.Message("user", "사과"));
        WordChainRules.Check check = WordChainRules.checkReply(history, "과일");
        assertEquals(WordChainRules.Turn.CALLER_DUPLICATE, check.turn);
        assertEquals(WordChainRules.Reason.CALLER_CORRECTION_REQUIRED, check.reason);
        assertNull(check.accepted);
    }

    @Test public void outputDiagnosticsPreserveEveryExistingEligibilityCondition() {
        for (String text : new String[]{null, "", " ", "과자", " 과자. ", "<think>private</think>", "가".repeat(1001)})
            for (int tokens : new int[]{-1, 0, 1, 127, 128, 129})
                assertEquals(tokens > 0 && tokens < ReplyRequest.OUTPUT_TOKENS && AutoReplyFlow.isValidReply(text),
                        ReplyRequest.validOutput(text, tokens));
    }
}
