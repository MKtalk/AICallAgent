package com.aicallagent.app.local;

import com.aicallagent.app.model.CallConversation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A fresh native conversation is reconstructed from confirmed flow history for every request. */
public final class ReplyRequest {
    public static final int CONTEXT_TOKENS = 8192, OUTPUT_TOKENS = 128;
    public static final String ENGLISH_TEST_SYSTEM_PROMPT = CallConversation.SYSTEM_PROMPT_EN;
    private static final AtomicLong IDS = new AtomicLong();
    public final long diagnosticId = IDS.incrementAndGet();
    public final String system, turnInstruction;
    public final int inputBudget;
    public final List<CallConversation.Message> history;
    public final boolean englishTest;
    private ReplyRequest(String system, List<CallConversation.Message> history) {
        this(system, history, false);
    }
    private ReplyRequest(String system, List<CallConversation.Message> history, boolean englishTest) {
        this.system = system;
        this.englishTest = englishTest;
        if (history.isEmpty() || history.size() > 32) throw new IllegalArgumentException("CONTEXT_LIMIT");
        boolean startsWithUser = history.get(0) != null && "user".equals(history.get(0).role);
        List<CallConversation.Message> copy = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            CallConversation.Message message = history.get(i);
            String role = (i % 2 == 0) == startsWithUser ? "user" : "assistant";
            if (message == null || !role.equals(message.role) || message.content == null || message.content.trim().isEmpty())
                throw new IllegalArgumentException("INVALID_RESPONSE");
            copy.add(new CallConversation.Message(message.role, message.content));
        }
        if (!"user".equals(copy.get(copy.size()-1).role)) throw new IllegalArgumentException("INVALID_RESPONSE");
        this.history = Collections.unmodifiableList(copy);
        turnInstruction = englishTest ? WordChainPrompt.englishTestTurn() : WordChainPrompt.turn(this.history);
        int bytes = (system + turnInstruction).getBytes(StandardCharsets.UTF_8).length
                + WordChainPrompt.repairReserveBytes() + 512 + OUTPUT_TOKENS;
        for (var message : copy) bytes += message.content.getBytes(StandardCharsets.UTF_8).length + 64;
        if (bytes > CONTEXT_TOKENS) throw new IllegalArgumentException("CONTEXT_LIMIT");
        inputBudget = bytes;
    }
    public static ReplyRequest forCall(List<CallConversation.Message> history) {
        return new ReplyRequest(CallConversation.SYSTEM_PROMPT, history, false);
    }
    public static ReplyRequest test() {
        return test(true);
    }
    public static ReplyRequest test(boolean isKorean) {
        if (isKorean) {
            return forCall(Collections.singletonList(new CallConversation.Message("user", "사과")));
        }
        return new ReplyRequest(CallConversation.SYSTEM_PROMPT_EN,
                Collections.singletonList(new CallConversation.Message("user", "Banana")), true);
    }
    public CallConversation.Message latest() { return history.get(history.size()-1); }
    public static boolean validOutput(String text, int decodedTokens) {
        return outputProblem(text, decodedTokens) == OutputProblem.NONE;
    }
    public enum OutputProblem { NONE, TOKEN_LIMIT, NO_DECODE_TOKENS, EMPTY, TOO_LONG, THINK_TAG }
    public static OutputProblem outputProblem(String text, int decodedTokens) {
        if (decodedTokens >= OUTPUT_TOKENS) return OutputProblem.TOKEN_LIMIT;
        if (decodedTokens <= 0) return OutputProblem.NO_DECODE_TOKENS;
        if (text == null || text.trim().isEmpty()) return OutputProblem.EMPTY;
        if (text.trim().length() > 1000) return OutputProblem.TOO_LONG;
        if (text.contains("<think>") || text.contains("</think>")) return OutputProblem.THINK_TAG;
        return OutputProblem.NONE;
    }
}
