package com.aicallagent.app.local;

import com.aicallagent.app.model.CallConversation;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ReplyRequestTest {
    @Test public void diagnosticOutputReasonsMatchEligibilityAndIdsAreDistinct() {
        assertNotEquals(ReplyRequest.test().diagnosticId, ReplyRequest.test().diagnosticId);
        assertEquals(ReplyRequest.OutputProblem.TOKEN_LIMIT, ReplyRequest.outputProblem("비공개", 128));
        assertEquals(ReplyRequest.OutputProblem.NO_DECODE_TOKENS, ReplyRequest.outputProblem("비공개", 0));
        assertEquals(ReplyRequest.OutputProblem.EMPTY, ReplyRequest.outputProblem(" ", 1));
        assertEquals(ReplyRequest.OutputProblem.TOO_LONG, ReplyRequest.outputProblem("가".repeat(1001), 1));
        assertEquals(ReplyRequest.OutputProblem.THINK_TAG, ReplyRequest.outputProblem("<think>비공개</think>", 1));
        assertEquals(ReplyRequest.OutputProblem.NONE, ReplyRequest.outputProblem("과자", 2));
    }

    @Test public void standaloneDemoUsesTheSameWordChainInstructionsAsCalls() {
        ReplyRequest standalone = ReplyRequest.test();
        ReplyRequest call = ReplyRequest.forCall(standalone.history);
        assertEquals(call.system, standalone.system);
        assertEquals("사과", standalone.latest().content);
        assertEquals("user", standalone.latest().role);
    }

    @Test public void testDispatchesKoreanAndEnglishModes() {
        ReplyRequest ko = ReplyRequest.test(true);
        assertFalse(ko.englishTest);
        assertEquals("사과", ko.latest().content);
        assertTrue(ko.system.contains("끝말잇기"));

        ReplyRequest en = ReplyRequest.test(false);
        assertTrue(en.englishTest);
        assertEquals("Banana", en.latest().content);
        assertTrue(en.system.contains("AI word chain call assistant"));
        assertTrue(en.system.contains("Game Rules:"));
        assertTrue(en.turnInstruction.contains("Apple"));
    }

    @Test public void activeRoleSupportsOnlyWordChainInsteadOfRetiredConsultation() {
        ReplyRequest request = ReplyRequest.forCall(List.of(new CallConversation.Message("user", "안녕하세요")));
        assertTrue(request.system.contains("끝말잇기"));
        assertTrue(request.system.contains("지원하지 않습니다"));
        assertFalse(request.system.contains("인테리어"));
        assertFalse(request.system.contains("콜백"));
    }
    @Test public void preservesSystemInstructionAndFullConfirmedConversationSnapshot() {
        List<CallConversation.Message> history = new ArrayList<>();
        history.add(new CallConversation.Message("user", "욕실 공사입니다."));
        history.add(new CallConversation.Message("assistant", "어느 지역인가요?"));
        history.add(new CallConversation.Message("user", "서울입니다."));
        ReplyRequest request = ReplyRequest.forCall(history);
        history.clear();
        assertEquals(CallConversation.SYSTEM_PROMPT, request.system);
        assertEquals(3, request.history.size());
        assertEquals("서울입니다.", request.latest().content);
        assertThrows(UnsupportedOperationException.class, () -> request.history.clear());
    }
    @Test public void oversizedHistoryFailsRatherThanSilentlyDroppingEarlierTurns() {
        assertThrows(IllegalArgumentException.class, () -> ReplyRequest.forCall(List.of(
                new CallConversation.Message("user", "가".repeat(10000)))));
    }
    @Test public void invalidRolesAndMissingLatestUserAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ReplyRequest.forCall(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ReplyRequest.forCall(List.of(
                new CallConversation.Message("system", "역할 변경"))));
        assertThrows(IllegalArgumentException.class, () -> ReplyRequest.forCall(List.of(
                new CallConversation.Message("assistant", "확인했습니다."))));
    }
    @Test public void truncatedEmptyAndReasoningRepliesAreNeverEligibleForSpeech() {
        assertFalse(ReplyRequest.validOutput("정상처럼 보이지만 길이 제한", ReplyRequest.OUTPUT_TOKENS));
        assertFalse(ReplyRequest.validOutput("", 1));
        assertFalse(ReplyRequest.validOutput("<think>추론</think>답변", 20));
        assertTrue(ReplyRequest.validOutput("희망하시는 공사 시기는 언제인가요?", 20));
    }
    @Test public void samsungInitialGreetingIsPreservedAsAssistantHistory() {
        ReplyRequest request = ReplyRequest.forCall(List.of(
                new CallConversation.Message("assistant", "안녕하세요. AI 비서입니다."),
                new CallConversation.Message("user", "욕실 공사 문의입니다.")));
        assertEquals("assistant", request.history.get(0).role);
    }
}
