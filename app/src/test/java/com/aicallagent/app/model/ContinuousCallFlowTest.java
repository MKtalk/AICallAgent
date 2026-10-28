package com.aicallagent.app.model;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class ContinuousCallFlowTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    public static long send(AutoReplyFlow flow, AutoReplyFlow.Snapshot screen, long now) {
        assertEquals(SET_REPLY, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.inputEmpty = false;
        screen.inputMatchesReply = true;
        screen.submitReady = true;
        screen.submitBounds = "verified bounds";
        assertEquals(NONE, flow.step(screen, now + 250));
        assertEquals(SEND_REPLY, flow.step(screen, now + 550));
        flow.actionResult(true, now + 550);
        screen.inputEmpty = true;
        screen.inputMatchesReply = false;
        screen.replyCount++;
        assertEquals(NONE, flow.step(screen, now + 550));
        assertEquals(WAIT_REMOTE, flow.getStage());
        return now + 550;
    }

    private void start() {
        screen.allowedWindow = true;
        flow.enable(screen, now);
        screen.incoming = true;
        assertEquals(ANSWER, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.incoming = false;
        screen.connected = true;
        screen.textCall = true;
        screen.inputReady = true;
        screen.inputFocused = true;
        screen.initialGreeting = "인테리어 담당자를 대신해 전화를 받는 AI 에이전트입니다.";
        assertEquals(NONE, flow.step(screen, now));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals("", flow.getReplyText());
    }

    private void request(String text, int count) {
        screen.remoteText = text;
        screen.remoteCount = count;
        assertEquals(NONE, flow.step(screen, now));
        now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
    }

    private List<CallConversation.Message> history() { return flow.getConversation(); }

    @Test public void phoneGreetingIsNotRepeatedAndTwoRepliesContinue() {
        start();
        request("아파트 욕실 공사를 원해요.", 1);
        flow.replyResult("현장은 어느 지역인가요?", now);
        now = send(flow, screen, now);
        request("서울이고 다음 달에 하고 싶어요.", 2);
        flow.replyResult("서울 아파트 욕실 공사를 다음 달에 희망하시는군요. 담당자가 다시 전화드릴 예정입니다.", now);
        now = send(flow, screen, now);
        assertEquals(NONE, flow.step(screen, now + 700));
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void historyContainsGreetingUserAndConfirmedReply() throws Exception {
        start(); request("아파트 욕실 공사", 1);
        assertEquals(2, history().size());
        flow.replyResult("현장은 어느 지역인가요?", now);
        assertEquals(2, history().size());
        now = send(flow, screen, now);
        request("서울", 2);
        assertEquals(4, history().size());
    }

    @Test public void identicalNewUtteranceWithNewMessageCountIsProcessed() {
        start(); request("네", 1);
        flow.replyResult("지역을 알려주시겠어요?", now);
        now = send(flow, screen, now);
        request("네", 2);
    }

    @Test public void speechDuringApiRequestIsCombinedBeforeReply() {
        start(); request("욕실 공사", 1);
        screen.remoteText = "현장은 서울이에요.";
        screen.remoteCount = 2;
        assertEquals(NONE, flow.step(screen, now + 100));
        flow.replyResult("어떤 공간인가요?", now + 100);
        assertEquals("", flow.getReplyText());
        now += 100;
        request("현장은 서울이에요.", 2);
        assertEquals("욕실 공사\n현장은 서울이에요.", flow.getRequestText());
    }

    @Test public void continuousCallCanExceedThreeMinutes() {
        start(); now += 300_000;
        request("기존 고객인데 전달할 용건이 있어요.", 1);
    }

    @Test public void disablingClearsConversation() throws Exception {
        start(); request("욕실 공사", 1);
        flow.disable();
        assertTrue(history().isEmpty());
    }

    @Test public void endingClearsConversationBeforeNextCall() throws Exception {
        start(); request("욕실 공사", 1);
        screen.ended = true;
        flow.step(screen, now);
        assertTrue(history().isEmpty());
        assertEquals(WAIT_INCOMING, flow.getStage());
    }

    @Test public void confirmedReplyAndEarlierFactsReachNextRequest() {
        start(); request("서울 아파트 욕실 공사", 1);
        flow.replyResult("희망하시는 공사 시기는 언제인가요?", now);
        now = send(flow, screen, now);
        request("다음 달입니다.", 2);
        List<CallConversation.Message> history = history();
        assertEquals("assistant", history.get(0).role);
        assertEquals(screen.initialGreeting, history.get(0).content);
        assertEquals("user", history.get(1).role);
        assertEquals("서울 아파트 욕실 공사", history.get(1).content);
        assertEquals("assistant", history.get(2).role);
        assertEquals("희망하시는 공사 시기는 언제인가요?", history.get(2).content);
        assertEquals("다음 달입니다.", history.get(3).content);
    }

    @Test public void requestHistorySnapshotCannotBeMutatedOrChangedByNextReply() {
        start(); request("욕실 공사", 1);
        List<CallConversation.Message> snapshot = history();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        flow.replyResult("지역이 어디인가요?", now);
        now = send(flow, screen, now);
        assertEquals(2, snapshot.size());
        assertEquals(3, history().size());
    }

    @Test public void failedReplyClearsHistoryAndNeverRequestsAgain() {
        start(); request("욕실 공사", 1);
        flow.replyResult(null, now);
        assertTrue(history().isEmpty());
        screen.remoteText = "서울";
        screen.remoteCount = 2;
        assertEquals(NONE, flow.step(screen, now + 1000));
        assertEquals(FAILED, flow.getStage());
    }

    @Test public void bufferedSpeechSurvivesTemporaryMissingTranscript() {
        start(); request("욕실 공사", 1);
        screen.remoteText = "서울";
        screen.remoteCount = 2;
        flow.step(screen, now);
        flow.replyResult("현장은 어느 지역인가요?", now);
        screen.remoteText = "";
        assertEquals(NONE, flow.step(screen, now));
        now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
        assertEquals("욕실 공사\n서울", flow.getRequestText());
    }

    @Test public void multipleCallerMessagesDuringReplyAreCombinedWithoutLosingFacts() {
        start(); request("욕실 공사", 1);
        screen.remoteMessages.add("욕실 공사");
        screen.remoteMessages.add("서울");
        screen.remoteMessages.add("다음 달");
        screen.remoteText = "다음 달";
        screen.remoteCount = 3;
        flow.step(screen, now);
        flow.replyResult("현장은 어느 지역인가요?", now);
        request("다음 달", 3);
        assertEquals("욕실 공사\n서울\n다음 달", flow.getRequestText());
    }

    @Test public void sameUtteranceIsNotRequestedAgainAfterConfirmedReply() {
        start(); request("욕실 공사", 1);
        flow.replyResult("현장은 어디인가요?", now);
        now = send(flow, screen, now);
        assertEquals(NONE, flow.step(screen, now));
        assertEquals(NONE, flow.step(screen, now + 1000));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals(3, history().size());
    }

    @Test public void contextLimitStopsInsteadOfDroppingPreviouslyProvidedFacts() {
        start();
        for (int i = 1; i <= 10; i++) {
            screen.remoteText = "가".repeat(4096);
            screen.remoteCount = i;
            assertEquals(NONE, flow.step(screen, now));
            now += 700;
            AutoReplyFlow.Action action = flow.step(screen, now);
            if (flow.getStage() == FAILED) {
                assertEquals(NONE, action);
                assertEquals(AutoReplyFlow.Failure.CONTEXT_LIMIT, flow.getFailure());
                assertTrue(history().isEmpty());
                return;
            }
            assertEquals(REQUEST_REPLY, action);
            flow.replyResult("확인하겠습니다.", now);
            now = send(flow, screen, now);
        }
        fail("Conversation must remain bounded");
    }
}
