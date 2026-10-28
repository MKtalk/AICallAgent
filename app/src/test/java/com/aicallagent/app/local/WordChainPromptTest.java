package com.aicallagent.app.local;

import com.aicallagent.app.model.CallConversation;
import com.aicallagent.app.model.WordChainRules;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class WordChainPromptTest {
    private ReplyRequest user(String word) {
        return ReplyRequest.forCall(List.of(new CallConversation.Message("user", word)));
    }
    @Test public void wordTurnExplicitlyRequiresTheLastSyllableAndForbidsTheCallerWord() {
        String instruction = user("차례").turnInstruction;
        assertTrue(instruction.contains("허용 첫 음절: 례"));
        assertTrue(instruction.contains("상대 단어: 차례"));
        assertTrue(instruction.contains("사용 금지 단어: 차례"));
        assertFalse(instruction.contains("허용 첫 음절: 차"));
        assertTrue(user("과자").turnInstruction.contains("허용 첫 음절: 자"));
    }
    @Test public void stateAfterLossDoesNotCarryOldGameWordsIntoTheNewPrompt() {
        ReplyRequest request = ReplyRequest.forCall(List.of(
                new CallConversation.Message("user", "불꽃"),
                new CallConversation.Message("assistant", WordChainRules.LOSS),
                new CallConversation.Message("user", "사과")));
        assertTrue(request.turnInstruction.contains("허용 첫 음절: 과"));
        assertFalse(request.turnInstruction.contains("불꽃"));
    }
    @Test public void callerWrongStartRequestsCorrectionInsteadOfAnAiWord() {
        ReplyRequest request = ReplyRequest.forCall(List.of(new CallConversation.Message("assistant", "과자"),
                new CallConversation.Message("user", "나무")));
        assertTrue(request.turnInstruction.contains("상대방 차례 오류"));
        assertTrue(request.turnInstruction.contains("상대가 말해야 할 첫 음절: 자"));
        assertFalse(request.turnInstruction.contains("허용 첫 음절: 무"));
    }
    @Test public void wrongStartRepairNamesTheActualFailureAndKeepsSameRequiredSyllable() throws Exception {
        ReplyRequest request = user("과자");
        assertEquals("자장가", WordChainGeneration.generate(request, () -> false, (repair, instruction) -> {
            assertTrue(instruction.contains("허용 첫 음절: 자"));
            if (!repair) return "나무";
            assertTrue(instruction.contains("WRONG_START"));
            assertTrue(instruction.contains("첫 음절이 일치하지 않았습니다"));
            return "자장가";
        }, new GenerationTrace(request, ignored -> { })));
    }
    @Test public void modelConcessionSignalAfterBadConnectionBecomesSpokenLoss() throws Exception {
        ReplyRequest request = user("차례");
        assertEquals(WordChainRules.LOSS, WordChainGeneration.generate(request, () -> false, (repair, instruction) -> {
            assertTrue(instruction.contains("허용 첫 음절: 례"));
            return repair ? WordChainPrompt.LOSS_SIGNAL : "나무";
        }, new GenerationTrace(request, ignored -> { })));
    }
    @Test public void lossSignalIsReconsideredOnceAndCanStillBecomeARealGeneratedWord() throws Exception {
        ReplyRequest request = user("과자");
        assertEquals("자장가", WordChainGeneration.generate(request, () -> false, (repair, instruction) -> {
            if (!repair) return WordChainPrompt.LOSS_SIGNAL;
            assertTrue(instruction.contains("첫 패배 선택을 한 번 더 확인"));
            return "자장가";
        }, new GenerationTrace(request, ignored -> { })));
    }
    @Test public void shortStopSignalBecomesOnlyTheHangupRequest() throws Exception {
        ReplyRequest request = user("그만할게요");
        assertEquals(WordChainRules.STOP_NOTICE, WordChainGeneration.generate(request, () -> false,
                (repair, instruction) -> WordChainPrompt.STOP_SIGNAL, new GenerationTrace(request, ignored -> { })));
    }
    @Test public void callerMistakeCannotBeTurnedIntoAiConcessionByTheSignal() {
        ReplyRequest request = ReplyRequest.forCall(List.of(new CallConversation.Message("assistant", "과자"),
                new CallConversation.Message("user", "나무")));
        assertThrows(ReplyFailure.class, () -> WordChainGeneration.generate(request, () -> false,
                (repair, instruction) -> WordChainPrompt.LOSS_SIGNAL, new GenerationTrace(request, ignored -> { })));
    }
    @Test public void retryAfterTooShortOutputStatesTheSpecificFailure() throws Exception {
        ReplyRequest request = user("과자");
        assertEquals("자장가", WordChainGeneration.generate(request, () -> false, (repair, instruction) -> {
            if (!repair) return "자";
            assertTrue(instruction.contains("TOO_SHORT"));
            return "자장가";
        }, new GenerationTrace(request, ignored -> { })));
    }
    @Test public void reserveCoversEveryRepairAndInputBudgetIncludesDynamicState() {
        ReplyRequest request = user("과자");
        for (WordChainRules.Reason reason : WordChainRules.Reason.values())
            assertTrue(WordChainPrompt.repairReserveBytes() >= WordChainPrompt.repair(reason, false)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        int baseline = request.system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 512 + ReplyRequest.OUTPUT_TOKENS;
        assertTrue(request.inputBudget > baseline + request.turnInstruction.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    @Test public void partialOrAnnotatedSignalsAreNeverSpokenOrConvertedToConcessions() {
        assertNull(WordChainPrompt.speech("[[LOSS]] 감사합니다."));
        assertNull(WordChainPrompt.speech("[[STO]]"));
        assertEquals("과자", WordChainPrompt.speech("과자"));
        assertEquals(WordChainRules.LOSS, WordChainPrompt.speech("  [[LOSS]]  "));
    }
    @Test public void promptAndGuardShareRelaxedStartsIncludingTheUserReverseExamples() {
        assertTrue(user("차례").turnInstruction.contains("허용 첫 음절: 례, 녜, 예"));
        assertTrue(user("틀니").turnInstruction.contains("허용 첫 음절: 니, 리, 이"));
        assertTrue(user("폭우").turnInstruction.contains("허용 첫 음절: 우, 누, 루"));
        assertTrue(user("차례").system.contains("공평하게 번갈아"));
        assertTrue(user("차례").system.contains("어려운 단어를 내지 않습니다"));
        assertFalse(user("차례").system.contains("두음법칙·고유명사"));
    }
}
