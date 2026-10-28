package com.aicallagent.app.model;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.aicallagent.app.model.AutoReplyFlow.Action.*;
import static com.aicallagent.app.model.AutoReplyFlow.Stage.*;

public class FragmentedTurnTest {
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private final AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();

    private void start() {
        screen.allowedWindow = true; flow.enable(screen, 0); screen.incoming = true;
        flow.step(screen, 0); flow.actionResult(true, 0); screen.incoming = false;
        screen.textCall = screen.inputReady = screen.inputFocused = true;
        flow.step(screen, 1);
    }

    private void visible(String text, String id) {
        screen.remoteCount = 1; screen.remoteText = text;
        screen.remoteMessages.clear(); screen.remoteMessages.add(text);
        screen.remoteIdentities.clear(); screen.remoteIdentities.add(id);
    }

    private void initialRequest() {
        start(); visible("욕실 공사", "row:1");
        flow.step(screen, 1000);
        assertEquals(REQUEST_REPLY, flow.step(screen, 1700));
    }

    @Test public void fragmentsDisappearingFromVisibleListRemainInOneRequest() {
        start(); visible("욕실 공사", "row:1"); flow.step(screen, 1000);
        visible("서울입니다", "row:2"); flow.step(screen, 1300);
        assertEquals(NONE, flow.step(screen, 1999));
        assertEquals(REQUEST_REPLY, flow.step(screen, 2000));
        assertEquals("욕실 공사\n서울입니다", flow.getRequestText());
    }

    @Test public void extraFragmentDuringApiInvalidatesOldReplyAndCombinesOneUserTurn() {
        initialRequest(); visible("서울입니다", "row:2");
        assertEquals(NONE, flow.step(screen, 1800));
        assertEquals(WAIT_REMOTE, flow.getStage());
        flow.replyResult("오래된 답변", 1850);
        assertEquals("", flow.getReplyText());
        visible("다음 달", "row:3"); flow.step(screen, 1900);
        assertEquals(NONE, flow.step(screen, 2599));
        assertEquals(REQUEST_REPLY, flow.step(screen, 2600));
        assertEquals("욕실 공사\n서울입니다\n다음 달", flow.getRequestText());
        assertEquals(1, flow.getConversation().size());
        flow.replyResult("최신 답변", 2600);
        ContinuousCallFlowTest.send(flow, screen, 2600);
        assertEquals(2, flow.getConversation().size());
        assertEquals(NONE, flow.step(screen, 5000));
    }

    @Test public void extraFragmentBeforeInputAlsoDiscardsReadyReply() {
        initialRequest(); flow.replyResult("이전 답변", 1750);
        visible("서울입니다", "row:2");
        assertEquals(NONE, flow.step(screen, 1800));
        assertEquals(WAIT_REMOTE, flow.getStage());
        assertEquals("", flow.getReplyText());
        assertEquals(REQUEST_REPLY, flow.step(screen, 2500));
        assertEquals("욕실 공사\n서울입니다", flow.getRequestText());
    }

    @Test public void fragmentsAfterInputStartRemainNextTurnAndDoNotEraseReply() {
        initialRequest(); flow.replyResult("현재 답변", 1700);
        assertEquals(SET_REPLY, flow.step(screen, 1700)); flow.actionResult(true, 1700);
        screen.inputEmpty = false; screen.inputMatchesReply = screen.submitReady = true;
        screen.submitBounds = "verified bounds";
        visible("서울입니다", "row:2"); flow.step(screen, 1950);
        visible("다음 달", "row:3");
        assertEquals(SEND_REPLY, flow.step(screen, 2250)); flow.actionResult(true, 2250);
        assertEquals("현재 답변", flow.getReplyText());
        screen.inputEmpty = true; screen.inputMatchesReply = false; screen.replyCount++;
        flow.step(screen, 2251); flow.step(screen, 2300);
        assertEquals(REQUEST_REPLY, flow.step(screen, 3000));
        assertEquals("서울입니다\n다음 달", flow.getRequestText());
    }

    @Test public void missingTranscriptDoesNotDropAccumulatedFragments() {
        start(); visible("욕실 공사", "row:1"); flow.step(screen, 1000);
        screen.remoteText = ""; screen.remoteMessages.clear(); screen.remoteIdentities.clear();
        flow.step(screen, 1200);
        assertEquals(REQUEST_REPLY, flow.step(screen, 1700));
        assertEquals("욕실 공사", flow.getRequestText());
    }

    @Test public void correctionsToPendingItemReplaceItWithoutDuplicatingWords() {
        start(); visible("욕실", "row:1"); flow.step(screen, 1000);
        visible("욕실 공사", "row:1"); flow.step(screen, 1200);
        visible("서울입니다", "row:2"); flow.step(screen, 1300);
        assertEquals(REQUEST_REPLY, flow.step(screen, 2000));
        assertEquals("욕실 공사\n서울입니다", flow.getRequestText());
    }

    @Test public void fallbackCountPreservesFragmentsWhenOnlyLatestTextIsAvailable() {
        start(); screen.remoteCount = 1; screen.remoteText = "욕실 공사"; flow.step(screen, 1000);
        screen.remoteCount = 2; screen.remoteText = "서울입니다"; flow.step(screen, 1300);
        assertEquals(REQUEST_REPLY, flow.step(screen, 2000));
        assertEquals("욕실 공사\n서울입니다", flow.getRequestText());
    }

    @Test public void callEndClearsOriginalAndAccumulatedContinuation() {
        initialRequest(); visible("서울입니다", "row:2"); flow.step(screen, 1800);
        flow.confirmCallEnded(); flow.replyResult("오래된 답변", 1900);
        assertTrue(flow.getConversation().isEmpty());
        assertEquals("", flow.getRequestText()); assertEquals("", flow.getReplyText());
        screen.textCall = false; screen.remoteCount = 0; screen.remoteText = "";
        screen.remoteMessages.clear(); screen.remoteIdentities.clear(); screen.incoming = true;
        flow.step(screen, 2000); flow.actionResult(true, 2000);
        screen.incoming = false; screen.textCall = true;
        visible("새 문의", "row:1"); flow.step(screen, 2100);
        assertEquals(REQUEST_REPLY, flow.step(screen, 2800));
        assertEquals("새 문의", flow.getRequestText());
    }

    @Test public void repeatedRevisionsKeepOneUserTurnAndEarlierConfirmedHistory() {
        initialRequest(); flow.replyResult("확인한 답변", 1700);
        ContinuousCallFlowTest.send(flow, screen, 1700);
        visible("서울입니다", "row:2"); flow.step(screen, 2300);
        assertEquals(REQUEST_REPLY, flow.step(screen, 3000));
        visible("다음 달", "row:3"); flow.step(screen, 3100);
        assertEquals(REQUEST_REPLY, flow.step(screen, 3800));
        visible("주방도요", "row:4"); flow.step(screen, 3900);
        assertEquals(REQUEST_REPLY, flow.step(screen, 4600));
        assertEquals("서울입니다\n다음 달\n주방도요", flow.getRequestText());
        assertEquals(3, flow.getConversation().size());
        assertEquals("확인한 답변", flow.getConversation().get(1).content);
        assertEquals("user", flow.getConversation().get(2).role);
    }

    @Test public void pendingPunctuationAliasDoesNotDuplicateOrExtendTurn() {
        start(); visible("욕실 공사", "row:1"); flow.step(screen, 1000);
        visible("욕실 공사.", "row:2"); flow.step(screen, 1200);
        assertEquals(REQUEST_REPLY, flow.step(screen, 1700));
        assertEquals("욕실 공사", flow.getRequestText());
    }

    @Test public void accumulatedTurnLimitFailsWithoutKeepingPrivateText() {
        start(); visible("가".repeat(3000), "row:1"); flow.step(screen, 1000);
        visible("나".repeat(2000), "row:2"); flow.step(screen, 1100);
        assertEquals(FAILED, flow.getStage());
        assertEquals(AutoReplyFlow.Failure.CONTEXT_LIMIT, flow.getFailure());
        assertTrue(flow.getConversation().isEmpty());
        assertEquals("", flow.getRequestText());
    }
}
