package com.aicallagent.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Pure state machine. Only the Android service executes returned actions. */
public final class AutoReplyFlow {
    public enum Action { NONE, ANSWER, OPEN_ASSIST, OPEN_TEXT, REQUEST_REPLY, FOCUS_INPUT, SET_REPLY, SEND_REPLY, CLEAR_DRAFT }
    public enum Stage {
        OFF, WAIT_INCOMING, WAIT_CONNECTED, WAIT_ASSIST, WAIT_TEXT, WAIT_TEXT_ACTIVE, WAIT_REMOTE,
        WAIT_LLM, WAIT_LLM_RETRY, WAIT_INPUT, VERIFY_INPUT, VERIFY_SENT, WAIT_SPEECH, VERIFY_DRAFT_CLEARED, FAILED, WAIT_END
    }
    public enum Failure { NONE, ACTION_REJECTED, TIMEOUT, EXISTING_INPUT, INPUT_CHANGED, LLM_FAILED,
        CONTEXT_LIMIT, SEND_REJECTED, SEND_UNCONFIRMED, SPEECH_BUSY_TIMEOUT }
    public static final class Snapshot {
        public boolean allowedWindow, incoming, connected, textCall, ended;
        public boolean assistReady, textOptionReady, inputReady, inputFocused;
        public boolean inputEmpty = true;
        public boolean inputMatchesReply, submitReady;
        public boolean inputMatchesOwnedDraft;
        public String remoteText = "", submitBounds = "", initialGreeting = "";
        public int remoteCount, replyCount;
        public final List<String> remoteMessages = new ArrayList<>();
        public final List<String> remoteIdentities = new ArrayList<>();

        public boolean isRinging() {
            return incoming && !connected && !textCall && remoteCount == 0;
        }
    }
    private static final long STEP_TIMEOUT = 15_000;
    private static final int MAX_HISTORY = 32_000;
    private Stage stage = Stage.OFF;
    private Failure failure = Failure.NONE;
    private boolean enabled, callClaimed, pending, focusRequested;
    private long deadline, inputAt, remoteChangedAt, boundsChangedAt;
    private long sendAttemptAt, speechDeadline, retryAt;
    private long llmRetryAt;
    private boolean networkRetried;
    private String remoteBaseline = "", remoteCandidate = "", stableBounds = "";
    private int remoteCandidateCount;
    private String requestText = "", replyText = "";
    // Memory only. Samsung can retain an app-written, unsent draft across hangup.
    // Keep ownership separate from conversation state until cleared or delivered.
    private String ownedDraft = "";
    public String getOwnedDraft() { return ownedDraft; }

    public boolean canClearDraft(Snapshot screen) {
        return enabled && callClaimed && stage == Stage.VERIFY_DRAFT_CLEARED
                && screen.allowedWindow && !screen.ended && screen.textCall
                && screen.inputReady && !screen.inputEmpty
                && !ownedDraft.isEmpty() && screen.inputMatchesOwnedDraft;
    }
    public String getRequestText() { return requestText; }
    public String getReplyText() { return replyText; }
    private int remoteBaselineCount, repliesBeforeSend;
    private int historyCharacters;
    private final List<CallConversation.Message> conversation = new ArrayList<>();
    private String queuedText = "", queuedLatest = "";
    private int queuedCount;
    private boolean awaitingIdentityBaseline;
    private String unfinishedTurn = "";
    private final Map<String, String> queuedMessages = new LinkedHashMap<>();
    private final Set<String> consumedRemoteIdentities = new HashSet<>();
    private final List<String> queuedIdentities = new ArrayList<>();
    private final Set<String> consumedRemoteTexts = new HashSet<>();
    private final List<String> queuedCanonicalTexts = new ArrayList<>();
    public List<CallConversation.Message> getConversation() {
        return Collections.unmodifiableList(new ArrayList<>(conversation));
    }

    private boolean remember(String role, String text) {
        if (historyCharacters + text.length() > MAX_HISTORY) { fail(Failure.CONTEXT_LIMIT); return false; }
        conversation.add(new CallConversation.Message(role, text));
        historyCharacters += text.length();
        return true;
    }

    public static boolean hasRemoteIdentities(Snapshot screen) {
        if (screen.remoteCount == 0 || screen.remoteMessages.size() != screen.remoteCount
                || screen.remoteIdentities.size() != screen.remoteCount) return false;
        Set<String> unique = new HashSet<>();
        for (String identity : screen.remoteIdentities) {
            if (identity == null || identity.isEmpty() || !unique.add(identity)) return false;
        }
        return true;
    }

    private static String canonicalTranscript(String text) {
        // Comparison only: preserve the original text sent to the LLM and internal punctuation.
        return text.trim().replaceAll("\\s+", " ").replaceAll("[.!?。！？…]+$", "").trim();
    }

    private void rememberVisibleTexts(Snapshot screen) {
        for (String message : screen.remoteMessages) {
            consumedRemoteTexts.add(canonicalTranscript(message));
        }
    }

    private void observeSpeech(Snapshot screen) {
        String latest = screen.remoteText.trim();
        if (latest.isEmpty()) return;
        if (hasRemoteIdentities(screen)) {
            if (awaitingIdentityBaseline) {
                // Late metadata cannot establish which visible rows were already consumed.
                consumedRemoteIdentities.addAll(screen.remoteIdentities);
                rememberVisibleTexts(screen);
                awaitingIdentityBaseline = false;
                return;
            }
            for (int i = 0; i < screen.remoteCount; i++) {
                String identity = screen.remoteIdentities.get(i);
                String message = screen.remoteMessages.get(i).trim();
                if (!consumedRemoteIdentities.contains(identity) && !message.isEmpty()) {
                    String canonical = canonicalTranscript(message);
                    if (screen.remoteCount <= remoteBaselineCount && consumedRemoteTexts.contains(canonical)) {
                        consumedRemoteIdentities.add(identity);
                        continue;
                    }
                    String key = "id:" + identity;
                    // A pending row may also move before its request starts.
                    if (!queuedMessages.containsKey(key) && screen.remoteCount <= queuedCount
                            && queuedCanonicalTexts.contains(canonical)) {
                        consumedRemoteIdentities.add(identity);
                        continue;
                    }
                    queuedMessages.put(key, message);
                }
            }
        } else {
            // A changed transcript in the same visible row is not evidence of a new utterance.
            if (screen.remoteCount <= remoteBaselineCount) return;
            if (screen.remoteMessages.size() == screen.remoteCount) {
                for (int i = remoteBaselineCount; i < screen.remoteCount; i++) {
                    String message = screen.remoteMessages.get(i).trim();
                    if (!message.isEmpty()) queuedMessages.put("count:" + (i + 1), message);
                }
            } else {
                queuedMessages.put("count:" + screen.remoteCount, latest);
            }
        }
        queuedIdentities.clear();
        queuedCanonicalTexts.clear();
        for (Map.Entry<String, String> entry : queuedMessages.entrySet()) {
            if (entry.getKey().startsWith("id:")) queuedIdentities.add(entry.getKey().substring(3));
            queuedCanonicalTexts.add(canonicalTranscript(entry.getValue()));
        }
        queuedText = String.join("\n", queuedMessages.values());
        if (!queuedText.isEmpty()) {
            queuedLatest = latest;
            queuedCount = screen.remoteCount;
        }
    }

    private void requeueUnansweredTurn(long now) {
        // No input effect has started. Replace the unanswered user entry on the next request.
        CallConversation.Message last = conversation.remove(conversation.size() - 1);
        historyCharacters -= last.content.length();
        unfinishedTurn = last.content;
        pending = false;
        requestText = replyText = remoteCandidate = "";
        focusRequested = false;
        enter(Stage.WAIT_REMOTE, now);
    }

    public Stage getStage() { return stage; }
    public Failure getFailure() { return failure; }
    public boolean isEnabled() { return enabled; }


    public void enable(Snapshot screen, long now) {
        enabled = true;
        clearTransient();
        // Keep the claim across an off/on toggle: another hook could hang up this call.
        if (callClaimed || (screen.allowedWindow && hasActiveCall(screen))) {
            callClaimed = true;
            stage = Stage.WAIT_END;
        } else {
            stage = Stage.WAIT_INCOMING;
        }
    }

    public void disable() {
        enabled = false;
        stage = Stage.OFF;
        clearTransient();
    }

    private boolean hasActiveCall(Snapshot screen) {
        return screen.connected || screen.textCall || screen.remoteCount > 0;
    }

    private boolean terminal() {
        return stage == Stage.FAILED || stage == Stage.WAIT_END;
    }

    private void clearTransient() {
        pending = false;
        focusRequested = false;
        failure = Failure.NONE;
        remoteBaseline = "";
        remoteCandidate = "";
        remoteCandidateCount = 0;
        stableBounds = "";
        remoteBaselineCount = 0;
        requestText = "";
        replyText = "";
        conversation.clear();
        historyCharacters = 0;
        queuedText = "";
        queuedLatest = "";
        queuedCount = 0;
        unfinishedTurn = "";
        queuedMessages.clear();
        awaitingIdentityBaseline = false;
        consumedRemoteIdentities.clear();
        queuedIdentities.clear();
        consumedRemoteTexts.clear();
        queuedCanonicalTexts.clear();
        sendAttemptAt = speechDeadline = retryAt = 0;
        llmRetryAt = 0;
        networkRetried = false;
    }

    private void releaseCall() {
        callClaimed = false;
        clearTransient();
        stage = enabled ? Stage.WAIT_INCOMING : Stage.OFF;
    }

    /** Only call with positive end evidence: an ended UI or confirmed system idle. */
    public boolean confirmCallEnded() {
        boolean claimed = callClaimed;
        releaseCall();
        return claimed;
    }

    private void enter(Stage next, long now) {
        stage = next;
        deadline = next == Stage.WAIT_REMOTE ? Long.MAX_VALUE
                : now + (next == Stage.WAIT_LLM ? 30_000 : STEP_TIMEOUT);
    }

    private Action issue(Action action, Stage next, long now) {
        enter(next, now);
        pending = true;
        return action;
    }

    private void fail(Failure reason) {
        if (reason == Failure.INPUT_CHANGED || reason == Failure.EXISTING_INPUT) ownedDraft = "";
        stage = Stage.FAILED;
        pending = false;
        remoteBaseline = "";
        remoteCandidate = "";
        requestText = "";
        replyText = "";
        conversation.clear();
        historyCharacters = 0;
        queuedText = "";
        queuedLatest = "";
        unfinishedTurn = "";
        queuedMessages.clear();
        consumedRemoteIdentities.clear();
        queuedIdentities.clear();
        consumedRemoteTexts.clear();
        queuedCanonicalTexts.clear();
        failure = reason;
    }

    public void replyResult(String value, long now) {
        if (!enabled || stage != Stage.WAIT_LLM) return;
        if (now > deadline) { fail(Failure.TIMEOUT); return; }
        pending = false;
        requestText = "";
        String text = value == null ? "" : value.trim();
        if (!isValidReply(text)) { fail(Failure.LLM_FAILED); return; }
        replyText = text;
        enter(Stage.WAIT_INPUT, now);
    }

    /** Bounded recovery: send one notice, then wait for a new utterance. Never invent a word. */
    public boolean generationFailure(String code, long now) {
        if (!enabled || stage != Stage.WAIT_LLM || now > deadline
                || !ReplyDiagnostics.isRecoverableGenerationError(code)) return false;
        replyResult(WordChainRules.RETRY_NOTICE, now);
        return stage == Stage.WAIT_INPUT;
    }

    public void networkFailure(long now) {
        if (!enabled || stage != Stage.WAIT_LLM) return;
        if (now > deadline || networkRetried) {
            replyResult(null, now);
            return;
        }
        pending = false;
        networkRetried = true;
        llmRetryAt = now + 2000;
        enter(Stage.WAIT_LLM_RETRY, now);
    }

    public static boolean isValidReply(String value) {
        return value != null && !value.trim().isEmpty() && value.trim().length() <= 1000
                && !value.contains("<think>") && !value.contains("</think>");
    }

    public void actionResult(boolean accepted, long now) {
        if (!enabled || !pending || terminal()) return;
        pending = false;
        if (accepted && stage == Stage.VERIFY_INPUT) ownedDraft = replyText;
        if (!accepted) fail(stage == Stage.VERIFY_SENT ? Failure.SEND_REJECTED : Failure.ACTION_REJECTED);
    }

    public boolean speechRejected(Snapshot screen, long eventAt, long now) {
        if (!enabled || stage != Stage.VERIFY_SENT || eventAt < sendAttemptAt
                || eventAt > now || now - eventAt > 3000 || now - sendAttemptAt > 3000
                || !screen.allowedWindow || screen.ended || !screen.textCall || !screen.inputReady
                || screen.inputEmpty || !screen.inputMatchesReply
                || screen.replyCount != repliesBeforeSend || (speechDeadline != 0 && now > speechDeadline)) {
            return false;
        }
        pending = false;
        if (speechDeadline == 0) speechDeadline = now + 60_000;
        retryAt = now + 2000;
        stage = Stage.WAIT_SPEECH;
        deadline = speechDeadline;
        return true;
    }

    public Action step(Snapshot screen, long now) {
        if (screen.allowedWindow && screen.ended) {
            releaseCall();
            return Action.NONE;
        }
        // A transient empty UI, lost root or app switch must never release the hook lock.
        // Resume subsequent calls only after observing the verified call-ended control.
        if (!enabled || terminal()) return Action.NONE;
        if (stage != Stage.WAIT_INCOMING && now > deadline) {
            fail(stage == Stage.WAIT_SPEECH ? Failure.SPEECH_BUSY_TIMEOUT
                    : stage == Stage.VERIFY_SENT ? Failure.SEND_UNCONFIRMED : Failure.TIMEOUT);
            return Action.NONE;
        }
        if (!screen.allowedWindow) return Action.NONE;
        if (screen.textCall && (stage == Stage.WAIT_REMOTE || stage == Stage.WAIT_LLM || stage == Stage.WAIT_LLM_RETRY
                || stage == Stage.WAIT_INPUT || stage == Stage.VERIFY_INPUT || stage == Stage.VERIFY_SENT
                || stage == Stage.WAIT_SPEECH || stage == Stage.VERIFY_DRAFT_CLEARED)) {
            observeSpeech(screen);
        }
        if (!queuedText.isEmpty() && (stage == Stage.WAIT_LLM || stage == Stage.WAIT_LLM_RETRY
                || stage == Stage.WAIT_INPUT) && screen.inputReady && screen.inputEmpty) {
            requeueUnansweredTurn(now);
        }
        if (unfinishedTurn.length() + queuedText.length() > 4096) {
            fail(Failure.CONTEXT_LIMIT);
            return Action.NONE;
        }
        if (pending) return Action.NONE;

        if (stage == Stage.WAIT_INCOMING) {
            if (hasActiveCall(screen)) {
                callClaimed = true;
                stage = Stage.WAIT_END;
            } else if (screen.incoming) {
                callClaimed = true;
                return issue(Action.ANSWER, Stage.WAIT_CONNECTED, now);
            }
            return Action.NONE;
        }
        if ((stage == Stage.WAIT_CONNECTED || stage == Stage.WAIT_ASSIST
                || stage == Stage.WAIT_TEXT || stage == Stage.WAIT_TEXT_ACTIVE) && screen.textCall) {
            if (!screen.initialGreeting.trim().isEmpty()
                    && !remember("assistant", screen.initialGreeting.trim())) return Action.NONE;
            enter(Stage.WAIT_REMOTE, now);
            observeSpeech(screen);
        }
        if (stage == Stage.WAIT_CONNECTED) {
            if (!screen.connected) return Action.NONE;
            enter(Stage.WAIT_ASSIST, now);
        }
        if (stage == Stage.WAIT_ASSIST && screen.assistReady) {
            return issue(Action.OPEN_ASSIST, Stage.WAIT_TEXT, now);
        }
        if (stage == Stage.WAIT_TEXT && screen.textOptionReady) {
            remoteBaseline = screen.remoteText;
            remoteBaselineCount = screen.remoteCount;
            if (hasRemoteIdentities(screen)) {
                consumedRemoteIdentities.addAll(screen.remoteIdentities);
                rememberVisibleTexts(screen);
            }
            return issue(Action.OPEN_TEXT, Stage.WAIT_TEXT_ACTIVE, now);
        }
        if (stage == Stage.VERIFY_DRAFT_CLEARED) {
            if (!screen.textCall || !screen.inputReady) return Action.NONE;
            if (!screen.inputEmpty) {
                if (!screen.inputMatchesOwnedDraft) fail(Failure.INPUT_CHANGED);
                return Action.NONE;
            }
            ownedDraft = "";
            enter(Stage.WAIT_REMOTE, now);
        }
        if (stage == Stage.WAIT_REMOTE) {
            if (!screen.textCall) return Action.NONE;
            if (screen.inputReady && screen.inputEmpty) ownedDraft = "";
            if (conversation.isEmpty() && !screen.initialGreeting.trim().isEmpty()
                    && !remember("assistant", screen.initialGreeting.trim())) return Action.NONE;
            if (screen.inputReady && !screen.inputEmpty) {
                if (!ownedDraft.isEmpty() && screen.inputMatchesOwnedDraft) {
                    return issue(Action.CLEAR_DRAFT, Stage.VERIFY_DRAFT_CLEARED, now);
                }
                fail(Failure.EXISTING_INPUT);
                return Action.NONE;
            }
            String remote = unfinishedTurn.isEmpty() ? queuedText : unfinishedTurn + "\n" + queuedText;
            if (remote.isEmpty()) {
                remoteCandidate = "";
                return Action.NONE;
            }
            if (!remote.equals(remoteCandidate) || remoteCandidateCount != queuedCount) {
                remoteCandidate = remote;
                remoteCandidateCount = queuedCount;
                remoteChangedAt = now;
            } else if (now - remoteChangedAt >= 700) {
                if (remote.length() > 4096 || !remember("user", remote)) {
                    if (stage != Stage.FAILED) fail(Failure.CONTEXT_LIMIT);
                    return Action.NONE;
                }
                remoteBaseline = queuedLatest;
                remoteBaselineCount = queuedCount;
                awaitingIdentityBaseline = queuedIdentities.isEmpty();
                consumedRemoteIdentities.addAll(queuedIdentities);
                queuedIdentities.clear();
                consumedRemoteTexts.addAll(queuedCanonicalTexts);
                queuedCanonicalTexts.clear();
                queuedMessages.clear();
                unfinishedTurn = "";
                queuedText = "";
                queuedLatest = "";
                remoteCandidate = "";
                requestText = remote;
                networkRetried = false;
                return issue(Action.REQUEST_REPLY, Stage.WAIT_LLM, now);
            }
            return Action.NONE;
        }
        if (stage == Stage.WAIT_LLM_RETRY) {
            if (!screen.textCall || !screen.inputReady) return Action.NONE;
            if (!screen.inputEmpty) {
                fail(Failure.EXISTING_INPUT);
                return Action.NONE;
            }
            if (now >= llmRetryAt) return issue(Action.REQUEST_REPLY, Stage.WAIT_LLM, now);
            return Action.NONE;
        }
        if (stage == Stage.WAIT_INPUT) {
            if (!screen.textCall || !screen.inputReady) return Action.NONE;
            if (!screen.inputEmpty) {
                fail(Failure.EXISTING_INPUT);
                return Action.NONE;
            }
            if (!screen.inputFocused) {
                if (focusRequested) return Action.NONE;
                focusRequested = true;
                return issue(Action.FOCUS_INPUT, Stage.WAIT_INPUT, now);
            }
            inputAt = now;
            return issue(Action.SET_REPLY, Stage.VERIFY_INPUT, now);
        }
        if (stage == Stage.VERIFY_INPUT) {
            if (!screen.textCall || !screen.inputReady) return Action.NONE;
            if (!screen.inputEmpty && !screen.inputMatchesReply) {
                fail(Failure.INPUT_CHANGED);
                return Action.NONE;
            }
            if (!screen.inputMatchesReply || now - inputAt < 250 || !screen.submitReady
                    || screen.submitBounds.isEmpty()) {
                stableBounds = "";
                return Action.NONE;
            }
            if (!stableBounds.equals(screen.submitBounds)) {
                stableBounds = screen.submitBounds;
                boundsChangedAt = now;
            } else if (now - boundsChangedAt >= 300) {
                repliesBeforeSend = screen.replyCount;
                sendAttemptAt = now;
                return issue(Action.SEND_REPLY, Stage.VERIFY_SENT, now);
            }
        }
        if ((stage == Stage.VERIFY_SENT || stage == Stage.WAIT_SPEECH) && screen.textCall && screen.inputReady
                && screen.inputEmpty && screen.replyCount > repliesBeforeSend) {
            ownedDraft = "";
            if (!remember("assistant", replyText)) return Action.NONE;
            enter(WordChainRules.STOP_NOTICE.equals(replyText) ? Stage.WAIT_END : Stage.WAIT_REMOTE, now);
            focusRequested = false;
            stableBounds = "";
            remoteCandidate = "";
            requestText = "";
            replyText = "";
            sendAttemptAt = speechDeadline = retryAt = 0;
        }
        if (stage == Stage.WAIT_SPEECH && screen.textCall && screen.inputReady) {
            if (screen.inputEmpty || !screen.inputMatchesReply) {
                fail(Failure.INPUT_CHANGED);
                return Action.NONE;
            }
            if (screen.replyCount != repliesBeforeSend) {
                fail(Failure.SEND_UNCONFIRMED);
                return Action.NONE;
            }
            if (now >= retryAt && screen.submitReady && !screen.submitBounds.isEmpty()) {
                sendAttemptAt = now;
                Action action = issue(Action.SEND_REPLY, Stage.VERIFY_SENT, now);
                deadline = Math.min(deadline, speechDeadline);
                return action;
            }
        }
        return Action.NONE;
    }
}
