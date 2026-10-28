package com.aicallagent.app.local;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.LogSeverity;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.Role;
import com.google.ai.edge.litertlm.ThinkingConfig;
import com.google.ai.edge.litertlm.ExperimentalFlags;
import com.aicallagent.app.model.CallConversation;
import java.io.File;
import android.util.Log;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** No network operations. Each synthetic test gets a fresh conversation. */
final class LiteRtModelDriver implements LocalModelSession.Driver {
    private final File cache;
    private final Object conversationLock = new Object();
    private Engine engine;
    private boolean usingGpu;
    private Conversation active;

    LiteRtModelDriver(File cache) { this.cache = cache; }

    @Override public void load(File file, boolean gpu) throws Exception {
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IllegalStateException("CACHE");
        Engine.Companion.setNativeMinLogSeverity(LogSeverity.ERROR);
        ExperimentalFlags.INSTANCE.setEnableBenchmark(true);
        Engine candidate = new Engine(new EngineConfig(file.getAbsolutePath(),
                gpu ? new Backend.GPU() : new Backend.CPU(), null, null, ReplyRequest.CONTEXT_TOKENS, null, cache.getAbsolutePath()));
        candidate.initialize();
        engine = candidate;
        usingGpu = gpu;
    }

    @Override public String generate(ReplyRequest request, BooleanSupplier cancelled) throws Exception {
        GenerationTrace trace = new GenerationTrace(request, line -> Log.i("LocalGeneration", line));
        trace.start(usingGpu);
        return WordChainGeneration.generate(request, cancelled,
                (repair, instruction) -> generateOnce(request, cancelled, repair, instruction, trace), trace);
    }

    private String generateOnce(ReplyRequest request, BooleanSupplier cancelled, boolean repair, String instruction, GenerationTrace trace) throws Exception {
        List<Message> initial = new ArrayList<>();
        for (int i = 0; i < request.history.size()-1; i++) {
            CallConversation.Message message = request.history.get(i);
            initial.add("user".equals(message.role) ? Message.Companion.user(message.content) : Message.Companion.model(message.content));
        }
        int number = repair ? 2 : 1;
        trace.event(number, GenerationTrace.Phase.CREATE, GenerationTrace.Detail.START, -1, -1);
        Conversation conversation;
        try {
            conversation = engine.createConversation(new ConversationConfig(
                Contents.Companion.of(request.system + instruction),
                initial, Collections.emptyList(), null, false, null,
                Collections.emptyMap(), null, false, ReplyRequest.OUTPUT_TOKENS, new ThinkingConfig(false, 0)));
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            trace.failure(number, GenerationTrace.Phase.CREATE, failure);
            throw failure;
        }
        trace.event(number, GenerationTrace.Phase.CREATE, GenerationTrace.Detail.READY, -1, -1);
        int chars = -1, decoded = -1;
        try {
            synchronized (conversationLock) {
                active = conversation;
                if (cancelled.getAsBoolean()) return "";
            }
            trace.event(number, GenerationTrace.Phase.NATIVE, GenerationTrace.Detail.START, -1, -1);
            Message reply = conversation.sendMessage(request.latest().content);
            trace.event(number, GenerationTrace.Phase.NATIVE, GenerationTrace.Detail.RETURNED, -1, -1);
            if (reply.getRole() != Role.MODEL) {
                trace.event(number, GenerationTrace.Phase.OUTPUT, GenerationTrace.Detail.WRONG_ROLE, -1, -1);
                throw new ReplyFailure("INVALID_RESPONSE");
            }
            if (!reply.getToolCalls().isEmpty()) {
                trace.event(number, GenerationTrace.Phase.OUTPUT, GenerationTrace.Detail.TOOL_CALLS, -1, -1);
                throw new ReplyFailure("INVALID_RESPONSE");
            }
            StringBuilder text = new StringBuilder();
            for (Content content : reply.getContents().getContents()) {
                if (!(content instanceof Content.Text)) {
                    trace.event(number, GenerationTrace.Phase.OUTPUT, GenerationTrace.Detail.NON_TEXT_CONTENT, -1, -1);
                    throw new ReplyFailure("INVALID_RESPONSE");
                }
                text.append(((Content.Text) content).getText());
            }
            chars = text.length();
            trace.shape(number, text.toString());
            decoded = conversation.getBenchmarkInfo().getLastDecodeTokenCount();
            if (cancelled.getAsBoolean()) return "";
            ReplyRequest.OutputProblem problem = ReplyRequest.outputProblem(text.toString(), decoded);
            trace.output(number, problem, chars, decoded);
            if (problem != ReplyRequest.OutputProblem.NONE) throw new ReplyFailure(
                    decoded >= ReplyRequest.OUTPUT_TOKENS ? "INCOMPLETE_RESPONSE" : "INVALID_RESPONSE");
            // Only primary text is displayed. Thinking/tool channels are never concatenated.
            return cancelled.getAsBoolean() ? "" : text.toString();
        } catch (Exception | LinkageError | OutOfMemoryError failure) {
            trace.failure(number, GenerationTrace.Phase.NATIVE, failure);
            throw failure;
        } finally {
            synchronized (conversationLock) {
                active = null;
                trace.event(number, GenerationTrace.Phase.CLOSE, GenerationTrace.Detail.START, chars, decoded);
                try {
                    conversation.close();
                    trace.event(number, GenerationTrace.Phase.CLOSE, GenerationTrace.Detail.CLOSED, chars, decoded);
                } catch (RuntimeException | LinkageError failedRelease) {
                    trace.event(number, GenerationTrace.Phase.CLOSE, GenerationTrace.Detail.RELEASE_FAILED, chars, decoded);
                    throw new ReplyFailure("RELEASE_FAILED");
                }
            }
        }
    }

    @Override public void cancel() {
        synchronized (conversationLock) {
            if (active != null) active.cancelProcess();
        }
    }

    @Override public void close() {
        if (engine != null) {
            engine.close(); engine = null;
        }
    }
}
