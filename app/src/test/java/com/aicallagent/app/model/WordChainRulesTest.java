package com.aicallagent.app.model;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class WordChainRulesTest {
    private List<CallConversation.Message> user(String word) {
        return List.of(new CallConversation.Message("user", word));
    }

    @Test public void relaxedInitialRuleAcceptsAllUserExamplesAndPreservesVowelAndFinal() {
        for (String[] pair : new String[][]{{"대로", "노력"}, {"유럽", "업보"}, {"틀니", "리본"}, {"폭우", "누룩"}})
            assertEquals(pair[1], WordChainRules.guardReply(user(pair[0]), pair[1]));
        assertEquals("예술", WordChainRules.guardReply(user("차례"), "예술"));
        assertNull(WordChainRules.guardReply(user("차례"), "여름"));
        assertNull(WordChainRules.guardReply(user("유럽"), "어부"));
        assertNull(WordChainRules.guardReply(user("사과"), "나무"));
    }
    @Test public void relaxedCallerTurnAndEarlierHistoryUseTheSameConnectionRule() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("assistant", "대로"),
                new CallConversation.Message("user", "노력"),
                new CallConversation.Message("assistant", "역도"),
                new CallConversation.Message("user", "도시"));
        assertEquals("시계", WordChainRules.guardReply(history, "시계"));
        assertNull(WordChainRules.guardReply(history, "도자기"));
        assertEquals(WordChainRules.Turn.WORD, WordChainRules.turnState(List.of(
                new CallConversation.Message("assistant", "대로"),
                new CallConversation.Message("user", "노력"))).turn);
    }

    @Test public void relaxedConnectionDoesNotBypassAlreadyUsedWords() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("user", "기러기"),
                new CallConversation.Message("assistant", "기력"),
                new CallConversation.Message("user", "역기"));
        assertEquals(WordChainRules.Reason.DUPLICATE, WordChainRules.checkReply(history, "기러기").reason);
        assertEquals("기차", WordChainRules.guardReply(history, "기차"));
    }

    @Test public void detailedReasonsComeFromTheActualGuardDecision() {
        assertEquals(WordChainRules.Reason.TOO_SHORT, WordChainRules.checkReply(user("과자"), "자").reason);
        assertEquals(WordChainRules.Reason.WRONG_START, WordChainRules.checkReply(user("과자"), "나무").reason);
        assertEquals(WordChainRules.Reason.WORD_FORMAT, WordChainRules.checkReply(user("과자"), "자장가로 할게요.").reason);
        assertEquals(WordChainRules.Reason.DUPLICATE, WordChainRules.checkReply(user("기러기"), "기러기").reason);
        assertEquals(WordChainRules.Reason.BASE_INVALID, WordChainRules.checkReply(user("과자"), "<think>비공개</think>").reason);
        assertEquals(WordChainRules.Reason.NONE, WordChainRules.checkReply(user("과자"), "자장가").reason);
    }

    @Test public void generatedWordsOutsideTheOldVocabularyArePreserved() {
        assertEquals("자장가.", WordChainRules.guardReply(user("과자"), "자장가."));
        assertEquals("꽃다발.", WordChainRules.guardReply(user("불꽃"), "꽃다발."));
        assertEquals("차량.", WordChainRules.guardReply(user("기차"), "차량."));
    }

    @Test public void invalidStructureRequestsRepairWithoutChoosingAnotherWord() {
        assertNull(WordChainRules.guardReply(user("차례"), "례"));
        assertNull(WordChainRules.guardReply(user("사과"), "과"));
        assertNull(WordChainRules.guardReply(user("사과"), "나무"));
        assertNull(WordChainRules.guardReply(user("사과"), "과자, 자동차"));
        assertNull(WordChainRules.guardReply(user("사과"), "과자로 할게요."));
        assertNull(WordChainRules.guardReply(user("사과"), "<think>추론</think>과자"));
        assertNull(WordChainRules.guardReply(user("사과"), null));
    }

    @Test public void concessionMustComeFromTheModelRatherThanMissingRegisteredCandidates() {
        assertEquals(WordChainRules.LOSS, WordChainRules.guardReply(user("차례"), WordChainRules.LOSS));
        assertEquals(WordChainRules.LOSS, WordChainRules.guardReply(user("과자"), WordChainRules.LOSS));
        assertNull(WordChainRules.guardReply(user("과자"), "자"));
    }

    @Test public void historyIncludesGeneratedWordsOutsideThePreviousList() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("user", "불꽃"),
                new CallConversation.Message("assistant", "꽃다발."),
                new CallConversation.Message("user", "발자국"));
        assertEquals("국자.", WordChainRules.guardReply(history, "국자."));
        assertNull(WordChainRules.guardReply(history, "다람쥐."));
    }

    @Test public void callerMistakeAllowsModelCorrectionAndDoesNotAuthorizeAConcession() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("user", "사과"),
                new CallConversation.Message("assistant", "과자."),
                new CallConversation.Message("user", "나무"));
        String correction = "자 글자로 시작하는 단어를 다시 말씀해 주세요.";
        assertEquals(correction, WordChainRules.guardReply(history, correction));
        assertNull(WordChainRules.guardReply(history, "무지개"));
        assertNull(WordChainRules.guardReply(history, WordChainRules.LOSS));
    }

    @Test public void repeatedOutputRequestsRepairWithoutSelectingFromACandidateTable() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("user", "사과"),
                new CallConversation.Message("assistant", "과자."),
                new CallConversation.Message("user", "자동차"),
                new CallConversation.Message("assistant", "차표."),
                new CallConversation.Message("user", "표범"),
                new CallConversation.Message("assistant", "범죄."),
                new CallConversation.Message("user", "죄수"),
                new CallConversation.Message("assistant", "수박."),
                new CallConversation.Message("user", "박수"));
        assertNull(WordChainRules.guardReply(history, "수박"));
        assertEquals("수첩.", WordChainRules.guardReply(history, "수첩."));
    }

    @Test public void retryNoticePreservesEarlierWordsButDoesNotConsumeFailedTurn() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("user", "사과"),
                new CallConversation.Message("assistant", "과자"),
                new CallConversation.Message("user", "자동차"),
                new CallConversation.Message("assistant", WordChainRules.RETRY_NOTICE),
                new CallConversation.Message("user", "자동차"));
        assertEquals("차표", WordChainRules.guardReply(history, "차표"));
        assertNull(WordChainRules.guardReply(history, "자장가"));
        assertNull(WordChainRules.guardReply(history, WordChainRules.RETRY_NOTICE));
    }

    @Test public void confirmedLossResetsUsedWordsAndConnectionForDirectNewWord() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("user", "사과"),
                new CallConversation.Message("assistant", "과자"),
                new CallConversation.Message("user", "자동차"),
                new CallConversation.Message("assistant", WordChainRules.LOSS),
                new CallConversation.Message("user", "사과"));
        assertEquals("과자", WordChainRules.guardReply(history, "과자"));
        assertNull(WordChainRules.guardReply(history, "자장가"));
    }

    @Test public void restartAndNonGameMessagesDoNotRestrictGenerationToOldWords() {
        List<CallConversation.Message> history = List.of(
                new CallConversation.Message("user", "사과"),
                new CallConversation.Message("assistant", "과자."),
                new CallConversation.Message("user", "다시 시작"));
        assertEquals("사과.", WordChainRules.guardReply(history, "사과."));
        assertEquals("먼저 단어를 말씀해 주세요.",
                WordChainRules.guardReply(user("안녕하세요"), "먼저 단어를 말씀해 주세요."));
        assertEquals("끝말잇기만 지원해요.",
                WordChainRules.guardReply(user("오늘 날씨는 어때요?"), "끝말잇기만 지원해요."));
        assertEquals("과일.", WordChainRules.guardReply(user("사과로 시작할게요"), "과일."));
    }
}
