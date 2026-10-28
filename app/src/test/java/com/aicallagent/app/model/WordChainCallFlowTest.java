package com.aicallagent.app.model;

import com.aicallagent.app.local.ReplyRequest;
import org.junit.Test;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;
import static org.junit.Assert.*;

/** Scripted replies verify the call/request plumbing, not actual Gemma word selection. */
public class WordChainCallFlowTest {
    private static final String LOSS = WordChainRules.LOSS;
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
    private long now = 1000;

    private void start() {
        screen.allowedWindow = true;
        flow.enable(screen, now);
        screen.incoming = true;
        assertEquals(ANSWER, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.incoming = false;
        screen.textCall = screen.inputReady = screen.inputFocused = true;
        screen.initialGreeting = "이 전화는 에이전트가 받은 끝말잇기 통화입니다.";
        assertEquals(NONE, flow.step(screen, now));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals(1, flow.getConversation().size());
    }

    private ReplyRequest request(String utterance) {
        now += 1000;
        screen.remoteText = utterance;
        screen.remoteCount++;
        assertEquals(NONE, flow.step(screen, now));
        now += 700;
        assertEquals(REQUEST_REPLY, flow.step(screen, now));
        ReplyRequest request = ReplyRequest.forCall(flow.getConversation());
        assertEquals(utterance, request.latest().content);
        return request;
    }

    private void deliverScripted(String reply) {
        flow.replyResult(reply, now);
        now = ContinuousCallFlowTest.send(flow, screen, now);
    }

    @Test public void agentStartsAppleAndCallerSnackUsesGeneratedWordOutsideOldVocabulary() {
        start(); request("먼저 시작해 주세요");
        deliverScripted("사과.");
        ReplyRequest reply = request("과자");
        String guarded = WordChainRules.guardReply(reply.history, "자장가.");
        assertEquals("자장가.", guarded);
        deliverScripted(guarded);
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals("자장가.", flow.getConversation().get(flow.getConversation().size() - 1).content);
    }

    @Test public void lossIsDeliveredBeforeSameCallAcceptsFreshGame() {
        start(); request("차례");
        flow.replyResult(LOSS, now);
        assertEquals(SET_REPLY, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.inputEmpty = false;
        screen.inputMatchesReply = screen.submitReady = true;
        screen.submitBounds = "verified bounds";
        assertEquals(NONE, flow.step(screen, now + 250));
        assertEquals(SEND_REPLY, flow.step(screen, now + 550));
        flow.actionResult(true, now + 550);
        assertEquals(VERIFY_SENT, flow.getStage());
        screen.inputEmpty = true;
        screen.replyCount++;
        assertEquals(NONE, flow.step(screen, now + 650));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertTrue(flow.isEnabled());
        now += 650;
        ReplyRequest fresh = request("사과");
        assertEquals("과자", WordChainRules.guardReply(fresh.history, "과자"));
        deliverScripted("과자");
        assertEquals(WAIT_REMOTE, flow.getStage());
        flow.confirmCallEnded();
        assertEquals(WAIT_INCOMING, flow.getStage());
        assertTrue(flow.getConversation().isEmpty());
        screen.textCall = false;
        screen.remoteCount = 0;
        screen.remoteText = "";
        start();
        request("사과");
    }

    @Test public void rejectedLossNoticeRetriesBeforeOfferingNewGame() {
        start(); request("차례");
        flow.replyResult(LOSS, now);
        assertEquals(SET_REPLY, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.inputEmpty = false;
        screen.inputMatchesReply = screen.submitReady = true;
        screen.submitBounds = "verified bounds";
        flow.step(screen, now + 250);
        assertEquals(SEND_REPLY, flow.step(screen, now + 550));
        flow.actionResult(true, now + 550);
        assertTrue(flow.speechRejected(screen, now + 650, now + 650));
        assertEquals(WAIT_SPEECH, flow.getStage());
        assertEquals(LOSS, flow.getReplyText());
        assertEquals(SEND_REPLY, flow.step(screen, now + 2650));
        flow.actionResult(true, now + 2650);
        screen.inputEmpty = true;
        screen.replyCount++;
        assertEquals(NONE, flow.step(screen, now + 2750));
        assertEquals(WAIT_REMOTE, flow.getStage());
    }

    @Test public void confirmedStopAsksCallerToHangUpAndWaitsWithoutMoreGameReplies() {
        start(); request("그만할게요");
        String stop = "알겠습니다. 게임을 마칠게요. 통화를 종료해 주세요.";
        assertEquals(stop, WordChainRules.guardReply(flow.getConversation(), stop));
        flow.replyResult(stop, now);
        assertEquals(SET_REPLY, flow.step(screen, now));
        flow.actionResult(true, now);
        screen.inputEmpty = false;
        screen.inputMatchesReply = screen.submitReady = true;
        screen.submitBounds = "verified bounds";
        assertEquals(NONE, flow.step(screen, now + 250));
        assertEquals(SEND_REPLY, flow.step(screen, now + 550));
        flow.actionResult(true, now + 550);
        assertEquals(VERIFY_SENT, flow.getStage());
        screen.inputEmpty = true; screen.replyCount++;
        assertEquals(NONE, flow.step(screen, now + 650));
        assertEquals(WAIT_END, flow.getStage());
        screen.remoteText = "사과"; screen.remoteCount++;
        assertEquals(NONE, flow.step(screen, now + 1000));
        assertEquals(WAIT_END, flow.getStage());
        assertTrue(flow.isEnabled());
    }

    @Test public void greetingThenGameAndOffTopicRetainOnlyConfirmedTurnHistory() {
        start();
        request("안녕하세요");
        deliverScripted("안녕하세요. 먼저 단어를 말씀해 주세요.");
        request("사과");
        deliverScripted("과자.");
        request("자동차");
        deliverScripted("차표.");
        request("오늘 날씨는 어때요?");
        deliverScripted("끝말잇기만 지원해요. 게임을 이어가 볼까요?");
        ReplyRequest request = request("표범");
        assertEquals(CallConversation.SYSTEM_PROMPT, request.system);
        assertEquals(10, request.history.size());
        assertEquals(screen.initialGreeting, request.history.get(0).content);
        assertEquals("차표.", request.history.get(6).content);
        assertEquals("표범", request.latest().content);
        // A new call must not inherit the previous game's words.
        flow.confirmCallEnded();
        assertTrue(flow.getConversation().isEmpty());
    }

    @Test public void immediateWordDoesNotRequireAGreetingOrAnExtraGeneratedIntroduction() {
        start();
        ReplyRequest request = request("사과로 시작할게요");
        assertEquals(2, request.history.size());
        assertEquals("assistant", request.history.get(0).role);
        deliverScripted("과자.");
        assertEquals(3, flow.getConversation().size());
        assertEquals("과자.", flow.getConversation().get(2).content);
    }
}
