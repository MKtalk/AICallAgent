package com.aicallagent.app.local;

import com.aicallagent.app.model.WordChainRules;
import java.util.function.Consumer;

/** Fixed metadata only. Do not add content, content hashes, paths, or exception strings. */
final class GenerationTrace {
    enum Phase { REQUEST, ATTEMPT, CREATE, NATIVE, OUTPUT, SHAPE, RULES, CLOSE, RESULT }
    enum Detail { START, READY, RETURNED, WRONG_ROLE, TOOL_CALLS, NON_TEXT_CONTENT,
        ACCEPTED, EARLY_LOSS, REPAIR_REQUIRED, EXHAUSTED, CANCELLED, CLOSED, RELEASE_FAILED,
        INVALID_RESPONSE, INCOMPLETE_RESPONSE, MEMORY, LINKAGE_ERROR, NATIVE_EXCEPTION }
    private final ReplyRequest request;
    private final Consumer<String> sink;
    private final long started = System.nanoTime();
    GenerationTrace(ReplyRequest request, Consumer<String> sink) { this.request = request; this.sink = sink; }
    private String prefix(int attempt, Phase phase) {
        return "id=" + request.diagnosticId + " attempt=" + attempt + " phase=" + phase.name()
                + " elapsed_ms=" + ((System.nanoTime() - started) / 1_000_000);
    }
    void start(boolean gpu) {
        int chars = 0;
        for (var message : request.history) {
            chars += message.content.length();
        }
        Runtime runtime = Runtime.getRuntime();
        sink.accept(prefix(0, Phase.REQUEST) + " detail=START backend=" + (gpu ? "GPU" : "CPU")
                + " messages=" + request.history.size() + " history_chars=" + chars
                + " latest_chars=" + request.latest().content.length() + " input_budget=" + request.inputBudget
                + " context_limit=" + ReplyRequest.CONTEXT_TOKENS + " output_limit=" + ReplyRequest.OUTPUT_TOKENS
                + " java_heap_used_kb=" + ((runtime.totalMemory() - runtime.freeMemory()) / 1024)
                + " java_heap_max_kb=" + (runtime.maxMemory() / 1024));
    }
    void event(int attempt, Phase phase, Detail detail, int chars, int tokens) {
        sink.accept(prefix(attempt, phase) + " detail=" + detail.name() + " chars=" + chars + " decoded=" + tokens);
    }
    void output(int attempt, ReplyRequest.OutputProblem problem, int chars, int tokens) {
        sink.accept(prefix(attempt, Phase.OUTPUT) + " reason=" + problem.name() + " chars=" + chars + " decoded=" + tokens);
    }
    void shape(int attempt, String text) {
        int hangul = 0, ascii = 0, digits = 0, spaces = 0, lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '가' && c <= '힣') hangul++;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) ascii++;
            if (Character.isDigit(c)) digits++;
            if (Character.isWhitespace(c)) spaces++;
            if (c == '\n') lines++;
        }
        sink.accept(prefix(attempt, Phase.SHAPE) + " raw_chars=" + text.length()
                + " trimmed_chars=" + text.trim().length() + " lines=" + lines
                + " hangul_chars=" + hangul + " ascii_letters=" + ascii
                + " digits=" + digits + " whitespace=" + spaces);
    }

    void rules(int attempt, WordChainRules.Check check) {
        sink.accept(prefix(attempt, Phase.RULES) + " reason=" + check.reason.name()
                + " turn=" + check.turn.name() + " used_words=" + check.usedWords
                + " input_word_chars=" + check.inputWordLength + " output_word_chars=" + check.outputWordLength
                + " accepted=" + (check.accepted != null)
                + " loss=" + WordChainRules.LOSS.equals(check.accepted)
                + " stop=" + WordChainRules.STOP_NOTICE.equals(check.accepted));
    }
    void failure(int attempt, Phase phase, Throwable failure) {
        Detail detail = failure instanceof ReplyFailure ? switch (((ReplyFailure) failure).code) {
            case "INVALID_RESPONSE" -> Detail.INVALID_RESPONSE;
            case "INCOMPLETE_RESPONSE" -> Detail.INCOMPLETE_RESPONSE;
            case "RELEASE_FAILED" -> Detail.RELEASE_FAILED;
            default -> Detail.NATIVE_EXCEPTION;
        } : failure instanceof OutOfMemoryError ? Detail.MEMORY
                : failure instanceof LinkageError ? Detail.LINKAGE_ERROR : Detail.NATIVE_EXCEPTION;
        event(attempt, phase, detail, -1, -1);
    }
}
