package com.aicallagent.app.local;

import com.aicallagent.app.model.WordChainRules;
import java.util.function.BooleanSupplier;

/** At most one model repair; never supplies or selects a game word itself. */
final class WordChainGeneration {
    interface Attempt { String generate(boolean repair) throws Exception; }
    interface PreparedAttempt { String generate(boolean repair, String instruction) throws Exception; }

    private WordChainGeneration() { }

    static String generate(ReplyRequest request, BooleanSupplier cancelled, Attempt attempt) throws Exception {
        return generate(request, cancelled, attempt, new GenerationTrace(request, ignored -> { }));
    }

    static String generate(ReplyRequest request, BooleanSupplier cancelled, Attempt attempt, GenerationTrace trace) throws Exception {
        return generate(request, cancelled, (repair, instruction) -> attempt.generate(repair), trace);
    }

    static String generate(ReplyRequest request, BooleanSupplier cancelled, PreparedAttempt attempt, GenerationTrace trace) throws Exception {
        WordChainRules.Reason previousReason = WordChainRules.Reason.NONE;
        boolean reconsiderLoss = false;
        for (int pass = 0; pass < 2; pass++) {
            int number = pass + 1;
            if (cancelled.getAsBoolean()) {
                trace.event(number, GenerationTrace.Phase.RESULT, GenerationTrace.Detail.CANCELLED, -1, -1);
                return "";
            }
            trace.event(number, GenerationTrace.Phase.ATTEMPT, GenerationTrace.Detail.START, -1, -1);
            String answer;
            String instruction = request.turnInstruction + (pass == 1
                    ? (request.englishTest ? WordChainPrompt.englishTestRepair() : WordChainPrompt.repair(previousReason, reconsiderLoss))
                    : "");
            try { answer = attempt.generate(pass == 1, instruction); }
            catch (Exception | LinkageError | OutOfMemoryError failure) {
                trace.failure(number, GenerationTrace.Phase.RESULT, failure);
                throw failure;
            }
            if (cancelled.getAsBoolean()) {
                trace.event(number, GenerationTrace.Phase.RESULT, GenerationTrace.Detail.CANCELLED, -1, -1);
                return "";
            }
            String spoken = WordChainPrompt.speech(answer);
            if (request.englishTest) {
                String plain = spoken != null ? spoken.replaceAll("[^a-zA-Z]", "").trim() : "";
                if ("apple".equalsIgnoreCase(plain)) {
                    trace.event(number, GenerationTrace.Phase.RESULT, GenerationTrace.Detail.ACCEPTED, answer.length(), -1);
                    return "Apple";
                }
                previousReason = WordChainRules.Reason.WORD_FORMAT;
                trace.event(number, GenerationTrace.Phase.ATTEMPT, GenerationTrace.Detail.REPAIR_REQUIRED, answer.length(), -1);
                continue;
            }
            WordChainRules.Check check = WordChainRules.checkReply(request.history, spoken);
            trace.rules(number, check);
            if (check.reason == WordChainRules.Reason.BASE_INVALID) throw new ReplyFailure("INVALID_RESPONSE");
            boolean earlyLoss = pass == 0 && WordChainRules.LOSS.equals(check.accepted);
            if (check.accepted != null && !earlyLoss) {
                trace.event(number, GenerationTrace.Phase.RESULT, GenerationTrace.Detail.ACCEPTED, answer.length(), -1);
                return check.accepted;
            }
            previousReason = check.reason;
            reconsiderLoss = earlyLoss;
            trace.event(number, GenerationTrace.Phase.ATTEMPT,
                    earlyLoss ? GenerationTrace.Detail.EARLY_LOSS : GenerationTrace.Detail.REPAIR_REQUIRED, answer.length(), -1);
        }
        trace.event(2, GenerationTrace.Phase.RESULT, GenerationTrace.Detail.EXHAUSTED, -1, -1);
        throw new ReplyFailure("INVALID_RESPONSE");
    }
}
