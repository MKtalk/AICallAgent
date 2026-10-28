package com.aicallagent.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.content.Intent;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.aicallagent.app.model.CallStateModel;
import com.aicallagent.app.model.AutoReplyFlow;
import com.aicallagent.app.model.CallLifecycle;
import com.aicallagent.app.model.ReplyDiagnostics;
import com.aicallagent.app.model.IncomingCallGate;
import com.aicallagent.app.model.ReplySendController;
import com.aicallagent.app.model.CallMenuController;
import com.aicallagent.app.model.SendEventDiagnostics;
import com.aicallagent.app.model.InputTrace;
import com.aicallagent.app.policy.CallPackagePolicy;
import com.aicallagent.app.policy.SpeechBusyNotice;

import com.aicallagent.app.local.LocalModelRuntime;
import com.aicallagent.app.local.ReplyEngine;
import com.aicallagent.app.local.ReplyRequest;
import com.aicallagent.app.local.CallReplyGate;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 삼성 통화 UI의 상태 변화를 안전하게 관찰하는 접근성 서비스.
 * 사용자가 자동 응답을 켠 동안만 수신·텍스트 통화 전환·LLM 응답을 실행합니다.
 * 개인정보(전화번호, 통화 텍스트 원문 등)는 로그나 영구 저장소에 기록하지 않습니다.
 */
public class CallAccessibilityService extends AccessibilityService {

    private static final String TAG = "CallAccessibility";
    public static final String PKG_INCALLUI = CallPackagePolicy.PKG_INCALLUI;
    public static final String PKG_TELEPHONYUI = CallPackagePolicy.PKG_TELEPHONYUI;

    private static volatile boolean serviceConnected = false;
    private static volatile CallStateModel.CallState lastObservedCallState = CallStateModel.CallState.IDLE;
    private static volatile CallAccessibilityService liveService;
    private static volatile boolean autoReplyEnabled;
    private static volatile AutoReplyFlow.Stage replyStage = AutoReplyFlow.Stage.OFF;
    private static volatile AutoReplyFlow.Failure replyFailure = AutoReplyFlow.Failure.NONE;
    public enum WindowObservation { UNOBSERVED, NO_WINDOW, OTHER_APP, PHONE_NO_CONTROLS, CALL_VISIBLE, SYSTEM_IDLE }
    private static volatile WindowObservation windowObservation = WindowObservation.UNOBSERVED;
    private static volatile int observedSpeechCount, generationRequestCount, generationResponseCount, sentReplyCount;
    private static volatile boolean generationRunning;
    private static volatile String lastFailure = "";
    private static volatile boolean modelPreparing;
    private static volatile Boolean systemCallActive;
    private static volatile boolean diagnosticInputReady, diagnosticInputMatches, diagnosticSubmitReady;
    public static boolean isDiagnosticInputReady() { return diagnosticInputReady; }
    public static boolean isDiagnosticInputMatches() { return diagnosticInputMatches; }
    public static boolean isDiagnosticSubmitReady() { return diagnosticSubmitReady; }
    public static Boolean getSystemCallActive() { return systemCallActive; }
    public static boolean hasPhoneStatePermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }
    public static WindowObservation getWindowObservation() { return windowObservation; }
    public static int getObservedSpeechCount() { return observedSpeechCount; }
    public static int getGenerationRequestCount() { return generationRequestCount; }
    public static int getGenerationResponseCount() { return generationResponseCount; }
    public static int getSentReplyCount() { return sentReplyCount; }
    public static boolean isGenerationRunning() { return generationRunning; }
    public static String getLastFailure() { return lastFailure; }
    public static boolean isModelPreparing() { return modelPreparing; }
    private static void clearDiagnostics() {
        observedSpeechCount = generationRequestCount = generationResponseCount = sentReplyCount = 0;
        generationRunning = false;
        lastFailure = "";
    }
    private final AutoReplyFlow flow = new AutoReplyFlow();
    private CallLifecycle callLifecycle = new CallLifecycle();
    private AutoReplyFlow.Snapshot diagnosticScreen = new AutoReplyFlow.Snapshot();
    private String lastDiagnostic = "";
    private String lastSpeechIdentityDiagnostic = "";
    private boolean diagnosticEventsExpanded;
    private final SendEventDiagnostics sendEventDiagnostics = new SendEventDiagnostics();
    private final IncomingCallGate incomingCallGate = new IncomingCallGate();
    private final InputTrace inputTrace = new InputTrace(SystemClock::uptimeMillis,
            line -> Log.i(TAG, "InputTrace " + line));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long generation;
    private boolean driving;
    private LocalModelRuntime localRuntime;
    private ReplyEngine replyEngine;
    private final CallReplyGate replyGate = new CallReplyGate();
    private CallReplyGate.Ticket activeReply;
    private Runnable pendingReplyStart;

    private void cancelLlm() {
        boolean pending = activeReply != null;
        replyGate.cancel(); activeReply = null;
        if (pendingReplyStart != null) handler.removeCallbacks(pendingReplyStart);
        pendingReplyStart = null;
        if (pending && replyEngine != null) replyEngine.cancel(this);
        generationRunning = false;
    }
    private void releaseModel() {
        modelPreparing = false;
        if (replyEngine != null) replyEngine.release(this);
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            drive();
            if (liveService == CallAccessibilityService.this) {
                handler.postDelayed(this, flow.isEnabled() ? 100 : 1000);
            }
        }
    };

    public static boolean isAutoReplyEnabled() { return autoReplyEnabled; }
    public static AutoReplyFlow.Stage getReplyStage() { return replyStage; }
    public static AutoReplyFlow.Failure getReplyFailure() { return replyFailure; }

    /** Called only from the Activity on the main thread. No persistent opt-in. */
    public static void setAutoReplyEnabled(boolean enabled) {
        CallAccessibilityService service = liveService;
        if (service == null || Looper.myLooper() != Looper.getMainLooper()) return;
        service.generation++;
        service.incomingCallGate.reset();
        service.cancelLlm();
        service.flow.disable();
        service.releaseModel();
        if (enabled && hasPhoneStatePermission(service)) {
            clearDiagnostics();
            try {
                service.localRuntime = LocalModelRuntime.get(service);
                service.replyEngine = service.localRuntime;
                final long preparation = service.generation;
                modelPreparing = true;
                boolean started = service.localRuntime.prepareForCalls(service, (unused, error) -> service.handler.post(() -> {
                    if (liveService != service || preparation != service.generation || !modelPreparing) return;
                    modelPreparing = false;
                    if (error.isEmpty() && service.replyEngine.isReady(service)) {
                        service.flow.enable(SamsungCallUi.read(service.getRootInActiveWindow(), false), SystemClock.uptimeMillis());
                    } else {
                        lastFailure = error.isEmpty() ? "LOAD_FAILED" : error;
                        service.releaseModel();
                    }
                    service.publish();
                    service.drive();
                }));
                if (!started) { modelPreparing = false; lastFailure = service.localRuntime.preparationError(); }
            } catch (IOException failure) { modelPreparing = false; lastFailure = "STORAGE"; }
        }
        service.publish();
        service.handler.removeCallbacks(service.tick);
        service.handler.post(service.tick);
    }

    public static boolean isServiceConnected() {
        return serviceConnected;
    }

    public static CallStateModel.CallState getLastObservedCallState() {
        return lastObservedCallState;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        reset();
        liveService = this;
        serviceConnected = true;
        handler.post(tick);
        Log.i(TAG, "AccessibilityService connected.");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        reset();
        Log.i(TAG, "AccessibilityService unbound.");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        reset();
        Log.i(TAG, "AccessibilityService destroyed.");
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        CharSequence eventPkg = event.getPackageName();
        if (diagnosticEventsExpanded && SendEventDiagnostics.shouldExpand(flow.isEnabled(), flow.getStage())) {
            boolean knownSource = CallPackagePolicy.isAllowedPackage(eventPkg)
                    || "com.android.systemui".contentEquals(eventPkg == null ? "" : eventPkg);
            boolean toast = knownSource && "android.widget.Toast".contentEquals(
                    event.getClassName() == null ? "" : event.getClassName());
            Log.i(TAG, "SendEventTrace " + sendEventDiagnostics.record(eventPkg, event.getEventType(), toast, () -> {
                for (CharSequence text : event.getText()) {
                    if (SpeechBusyNotice.matchesText(text)) return true;
                }
                return false;
            }));
        }
        if (eventPkg == null || !isAllowedPackage(eventPkg.toString())) {
            return;
        }

        boolean notificationEvent = event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        boolean windowStateEvent = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        boolean toastClass = "android.widget.Toast".contentEquals(
                event.getClassName() == null ? "" : event.getClassName());
        boolean notificationData = event.getParcelableData() != null;
        boolean transientNotice = SpeechBusyNotice.isTransientNotice(notificationEvent, windowStateEvent,
                event.getClassName(), notificationData);
        boolean matched = false;
        if (transientNotice && flow.isEnabled()) {
            for (CharSequence text : event.getText()) {
                if (SpeechBusyNotice.matches(eventPkg, true, text)) {
                    matched = true;
                    boolean accepted = Boolean.TRUE.equals(readSystemCallActive())
                            && flow.speechRejected(SamsungCallUi.read(getRootInActiveWindow(), true,
                            flow.getReplyText()), event.getEventTime(), SystemClock.uptimeMillis());
                    Log.i(TAG, "SpeechBusyNotice accepted=" + accepted);
                    publish();
                    break;
                }
            }
        }
        if (flow.isEnabled() && (notificationEvent || windowStateEvent)
                && (flow.getStage() == AutoReplyFlow.Stage.VERIFY_SENT
                || flow.getStage() == AutoReplyFlow.Stage.WAIT_SPEECH)) {
            long age = SystemClock.uptimeMillis() - event.getEventTime();
            Log.i(TAG, "SpeechNoticeEvent kind=" + (notificationEvent ? "NOTIFICATION" : "WINDOW_STATE")
                    + " toast_class=" + toastClass + " notification_data=" + notificationData
                    + " eligible=" + transientNotice + " matched=" + matched
                    + " age_ms=" + Math.min(60_001, Math.max(-1, age)));
        }
        drive();
    }

    @Override
    public void onInterrupt() {
        generation++;
        incomingCallGate.reset();
        cancelLlm();
        releaseModel();
        flow.disable();
        publish();
        Log.i(TAG, "AccessibilityService interrupted.");
    }

    private void reset() {
        generation++;
        incomingCallGate.reset();
        cancelLlm();
        releaseModel();
        flow.disable();
        handler.removeCallbacks(tick);
        if (liveService == this) liveService = null;
        serviceConnected = false;
        lastObservedCallState = CallStateModel.CallState.IDLE;
        systemCallActive = null;
        callLifecycle = new CallLifecycle();
        diagnosticScreen = new AutoReplyFlow.Snapshot();
        windowObservation = WindowObservation.UNOBSERVED;
        clearDiagnostics();
        publish();
    }

    private void publish() {
        updateDiagnosticEventScope();
        autoReplyEnabled = flow.isEnabled();
        replyStage = flow.getStage();
        replyFailure = flow.getFailure();
        diagnosticInputReady = diagnosticScreen.inputReady;
        diagnosticInputMatches = diagnosticScreen.inputMatchesReply;
        diagnosticSubmitReady = diagnosticScreen.submitReady;
        String diagnostic = ReplyDiagnostics.describe(replyStage, replyFailure, diagnosticScreen,
                generationRequestCount, generationResponseCount, sentReplyCount);
        if (!diagnostic.equals(lastDiagnostic)) {
            lastDiagnostic = diagnostic;
            Log.i(TAG, "FlowDiagnostic " + diagnostic);
        }
    }

    private void updateDiagnosticEventScope() {
        boolean expand = Boolean.TRUE.equals(systemCallActive)
                && SendEventDiagnostics.shouldExpand(flow.isEnabled(), flow.getStage());
        if (expand == diagnosticEventsExpanded) return;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            diagnosticEventsExpanded = false;
            sendEventDiagnostics.clear();
            return;
        }
        info.eventTypes = expand ? AccessibilityEvent.TYPES_ALL_MASK
                : AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED | AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.packageNames = expand ? null : new String[]{PKG_INCALLUI, PKG_TELEPHONYUI};
        try {
            setServiceInfo(info);
            diagnosticEventsExpanded = expand;
            sendEventDiagnostics.clear();
            Log.i(TAG, "SendEventScope expanded=" + expand);
        } catch (IllegalStateException | SecurityException exception) {
            Log.i(TAG, "SendEventScope update_failed=true");
            // On a failed scope change stop automation and let the next publish restore the filter.
            flow.disable();
        }
    }

    private void observeDiagnostic(AutoReplyFlow.Snapshot screen) {
        String identityDiagnostic = "available=" + AutoReplyFlow.hasRemoteIdentities(screen)
                + " observed=" + screen.remoteCount;
        if (!identityDiagnostic.equals(lastSpeechIdentityDiagnostic)) {
            lastSpeechIdentityDiagnostic = identityDiagnostic;
            Log.i(TAG, "SpeechIdentity " + identityDiagnostic);
        }
        // Retain only flags/counts. No node, bounds, greeting, caller or reply strings.
        diagnosticScreen = new AutoReplyFlow.Snapshot();
        diagnosticScreen.allowedWindow = screen.allowedWindow;
        diagnosticScreen.textCall = screen.textCall;
        diagnosticScreen.inputReady = screen.inputReady;
        diagnosticScreen.inputFocused = screen.inputFocused;
        diagnosticScreen.inputEmpty = screen.inputEmpty;
        diagnosticScreen.inputMatchesReply = screen.inputMatchesReply;
        diagnosticScreen.submitReady = screen.submitReady;
        diagnosticScreen.remoteCount = screen.remoteCount;
    }

    private Boolean readSystemCallActive() {
        if (!hasPhoneStatePermission(this)) return null;
        try {
            TelecomManager telecom = getSystemService(TelecomManager.class);
            return telecom == null ? null : telecom.isInCall();
        } catch (SecurityException | IllegalStateException exception) {
            return null;
        }
    }

    private Boolean readSystemRinging() {
        if (!hasPhoneStatePermission(this)) return null;
        try {
            TelephonyManager telephony = getSystemService(TelephonyManager.class);
            return telephony == null ? null
                    : telephony.getCallStateForSubscription() == TelephonyManager.CALL_STATE_RINGING;
        } catch (SecurityException | IllegalStateException | UnsupportedOperationException exception) {
            return null;
        }
    }

    private void drive() {
        if (driving || liveService != this) return;
        driving = true;
        try {
            systemCallActive = readSystemCallActive();
            boolean idle = callLifecycle.observe(systemCallActive, SystemClock.uptimeMillis());
            inputTrace.systemState(systemCallActive, idle);
            if (systemCallActive == null && flow.isEnabled()) {
                incomingCallGate.reset();
                generation++;
                cancelLlm();
                releaseModel();
                flow.disable();
            }
            if (idle) {
                incomingCallGate.reset();
                observeDiagnostic(new AutoReplyFlow.Snapshot());
                if (flow.confirmCallEnded()) generation++;
                cancelLlm();
                if (lastObservedCallState != CallStateModel.CallState.IDLE) {
                    lastObservedCallState = CallStateModel.CallState.ENDED;
                }
                // A retained text-call hierarchy cannot revive a call the system ended.
                windowObservation = WindowObservation.SYSTEM_IDLE;
                publish();
                return;
            }
            if (Boolean.FALSE.equals(systemCallActive)) {
                incomingCallGate.reset();
                // Await stable idle without acting on a hierarchy retained after hangup.
                publish();
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (SamsungCallUi.trusted(root)) {
                Set<String> ids = extractVisibleKnownViewIds(root);
                windowObservation = CallStateModel.determineState(ids) == CallStateModel.CallState.IDLE
                        ? WindowObservation.PHONE_NO_CONTROLS : WindowObservation.CALL_VISIBLE;
                lastObservedCallState = CallStateModel.lastObserved(lastObservedCallState, ids);
            } else {
                windowObservation = root == null ? WindowObservation.NO_WINDOW : WindowObservation.OTHER_APP;
            }
            AutoReplyFlow.Snapshot screen = SamsungCallUi.read(root, flow.isEnabled(), flow.getReplyText(),
                    flow.getOwnedDraft());
            inputTrace.observe(flow.isEnabled(), flow.getStage(), screen);
            if (flow.isEnabled() && flow.getStage() == AutoReplyFlow.Stage.WAIT_INCOMING) {
                // Hide the incoming trigger until fresh UI and system proof survive the delay.
                screen.incoming = incomingCallGate.observe(true, readSystemRinging(), screen,
                        SystemClock.uptimeMillis());
            } else {
                incomingCallGate.reset();
            }
            observeDiagnostic(screen);
            if (screen.allowedWindow && screen.textCall) observedSpeechCount = screen.remoteCount;
            AutoReplyFlow.Stage before = flow.getStage();
            AutoReplyFlow.Action action = flow.step(screen, SystemClock.uptimeMillis());
            if (before != AutoReplyFlow.Stage.WAIT_INCOMING
                    && flow.getStage() == AutoReplyFlow.Stage.WAIT_INCOMING) generation++;
            if ((before == AutoReplyFlow.Stage.VERIFY_SENT || before == AutoReplyFlow.Stage.WAIT_SPEECH)
                    && (flow.getStage() == AutoReplyFlow.Stage.WAIT_REMOTE
                    || flow.getStage() == AutoReplyFlow.Stage.WAIT_END)) {
                sentReplyCount++;
            }
            if (before == AutoReplyFlow.Stage.WAIT_LLM && flow.getStage() == AutoReplyFlow.Stage.FAILED
                    && flow.getFailure() == AutoReplyFlow.Failure.TIMEOUT) {
                lastFailure = "TIMEOUT";
                Log.w(TAG, "GenerationError " + ReplyDiagnostics.generationError(lastFailure, false));
                flow.disable();
                releaseModel();
            }
            if (flow.getStage() != AutoReplyFlow.Stage.WAIT_LLM) cancelLlm();
            publish();
            if (action != AutoReplyFlow.Action.NONE) execute(action, screen);
            publish();
        } finally {
            driving = false;
        }
    }

    private void execute(AutoReplyFlow.Action action, AutoReplyFlow.Snapshot observed) {
        if (!Boolean.TRUE.equals(readSystemCallActive())) {
            flow.actionResult(false, SystemClock.uptimeMillis());
            return;
        }
        if (action == AutoReplyFlow.Action.REQUEST_REPLY) {
            requestReply();
            return;
        }
        // Re-read immediately before an effect. Never act on a stored AccessibilityNodeInfo.
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AutoReplyFlow.Snapshot fresh = SamsungCallUi.read(root, true, flow.getReplyText(), flow.getOwnedDraft());
        if (!fresh.allowedWindow || fresh.ended || !flow.isEnabled()) {
            flow.actionResult(false, SystemClock.uptimeMillis());
            return;
        }
        boolean accepted = false;
        if (action == AutoReplyFlow.Action.ANSWER) {
            clearDiagnostics();
            accepted = incomingCallGate.dispatch(readSystemRinging(), fresh, SystemClock.uptimeMillis(),
                    () -> inputTrace.dispatch(action, () -> performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETHOOK)));
            Log.i(TAG, "AnswerAction method=HEADSETHOOK accepted=" + accepted);
        } else if (action == AutoReplyFlow.Action.CLEAR_DRAFT) {
            AccessibilityNodeInfo input = SamsungCallUi.unique(root, CallStateModel.ID_TEXT_MESSAGE_EDIT);
            if (input != null && flow.canClearDraft(fresh)) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                accepted = inputTrace.dispatch(action,
                        () -> input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
            }
        } else if (action == AutoReplyFlow.Action.SET_REPLY || action == AutoReplyFlow.Action.FOCUS_INPUT) {
            AccessibilityNodeInfo input = SamsungCallUi.unique(root, CallStateModel.ID_TEXT_MESSAGE_EDIT);
            if (fresh.textCall && fresh.inputReady && fresh.inputEmpty && input != null) {
                if (action == AutoReplyFlow.Action.FOCUS_INPUT) {
                    accepted = SamsungCallUi.supports(input, AccessibilityNodeInfo.ACTION_FOCUS)
                            && inputTrace.dispatch(action, () -> input.performAction(AccessibilityNodeInfo.ACTION_FOCUS));
                } else if (fresh.inputFocused) {
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            flow.getReplyText());
                    accepted = inputTrace.dispatch(action,
                            () -> input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
                }
            }
        } else if (action == AutoReplyFlow.Action.SEND_REPLY) {
            AccessibilityNodeInfo target = SamsungCallUi.buttonTarget(root, action);
            if (target != null) {
                try {
                    accepted = ReplySendController.dispatch(fresh, observed.replyCount,
                            () -> inputTrace.dispatch(action, () -> target.performAction(AccessibilityNodeInfo.ACTION_CLICK)));
                } catch (IllegalStateException exception) {
                    accepted = false;
                }
            }
            Log.i(TAG, "SendAction method=ACCESSIBILITY_CLICK accepted=" + accepted);
        } else if (action == AutoReplyFlow.Action.OPEN_ASSIST || action == AutoReplyFlow.Action.OPEN_TEXT) {
            dispatchMenuTouch(action, fresh, SamsungCallUi.buttonTarget(root, action));
            return;
        }
        flow.actionResult(accepted, SystemClock.uptimeMillis());
    }

    private void dispatchMenuTouch(AutoReplyFlow.Action action, AutoReplyFlow.Snapshot fresh,
                                   AccessibilityNodeInfo target) {
        final CallMenuController.GestureCompletion completion =
                new CallMenuController.GestureCompletion(action, generation);
        boolean scheduled = false;
        // buttonTarget already checks unique ID/label, visibility, clickability and root bounds.
        if (target != null) {
            Rect bounds = SamsungCallUi.bounds(target);
            scheduled = CallMenuController.dispatchTouch(action, fresh,
                    bounds.left, bounds.top, bounds.right, bounds.bottom, () -> {
                        Path path = new Path();
                        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
                        GestureDescription gesture = new GestureDescription.Builder()
                                .addStroke(new GestureDescription.StrokeDescription(path, 0, 80)).build();
                        return inputTrace.dispatch(action, () -> dispatchGesture(gesture,
                                new GestureResultCallback() {
                                    @Override public void onCompleted(GestureDescription description) {
                                        finishMenuTouch(action, completion, true);
                                    }
                                    @Override public void onCancelled(GestureDescription description) {
                                        finishMenuTouch(action, completion, false);
                                    }
                                }, handler));
                    });
        }
        Log.i(TAG, "MenuTouch action=" + action.name() + " phase=SCHEDULED accepted=" + scheduled);
        // A queued gesture is not completion. Keep the flow pending until its callback.
        if (!scheduled) flow.actionResult(false, SystemClock.uptimeMillis());
    }

    private void finishMenuTouch(AutoReplyFlow.Action action,
                                 CallMenuController.GestureCompletion completion, boolean completed) {
        boolean applied = completion.accept(generation, liveService == this && flow.isEnabled(),
                flow.getStage(), readSystemCallActive());
        Log.i(TAG, "MenuTouch action=" + action.name() + " phase=" + (completed ? "COMPLETED" : "CANCELLED")
                + " applied=" + applied);
        if (applied) {
            flow.actionResult(completed, SystemClock.uptimeMillis());
            publish();
            drive();
        }
    }

    private void requestReply() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AutoReplyFlow.Snapshot fresh = SamsungCallUi.read(root, false);
        if (!Boolean.TRUE.equals(readSystemCallActive()) || !flow.isEnabled()
                || !fresh.allowedWindow || fresh.ended || !fresh.textCall
                || !fresh.inputReady || !fresh.inputEmpty) {
            flow.replyResult(null, SystemClock.uptimeMillis());
            return;
        }
        final ReplyRequest request;
        try { request = ReplyRequest.forCall(flow.getConversation()); }
        catch (IllegalArgumentException failure) {
            lastFailure = "CONTEXT_LIMIT".equals(failure.getMessage()) ? "CONTEXT_LIMIT" : "INVALID_RESPONSE";
            Log.w(TAG, "GenerationError " + ReplyDiagnostics.generationError(lastFailure, false));
            flow.disable(); releaseModel(); return;
        }
        final CallReplyGate.Ticket ticket = replyGate.start(generation, SystemClock.uptimeMillis());
        activeReply = ticket;
        generationRunning = true;
        lastFailure = "";
        pendingReplyStart = new Runnable() {
            private boolean deferredLogged;
            @Override public void run() {
                if (!replyGate.accepts(ticket, generation, SystemClock.uptimeMillis()) || liveService != CallAccessibilityService.this
                        || flow.getStage() != AutoReplyFlow.Stage.WAIT_LLM) return;
                drive();
                if (!replyGate.accepts(ticket, generation, SystemClock.uptimeMillis()) || flow.getStage() != AutoReplyFlow.Stage.WAIT_LLM) return;
                boolean started = replyEngine != null && replyEngine.generate(CallAccessibilityService.this, request,
                        (result, diagnostic) -> handler.post(() -> {
                    if (!replyGate.accepts(ticket, generation, SystemClock.uptimeMillis()) || liveService != CallAccessibilityService.this) {
                        Log.i(TAG, "GenerationDiscard id=" + request.diagnosticId + " reason=STALE_TICKET");
                        return;
                    }
                    drive();
                    if (!replyGate.accepts(ticket, generation, SystemClock.uptimeMillis())
                            || flow.getStage() != AutoReplyFlow.Stage.WAIT_LLM || !Boolean.TRUE.equals(readSystemCallActive())) {
                        Log.i(TAG, "GenerationDiscard id=" + request.diagnosticId + " reason=STATE_CHANGED stage=" + flow.getStage().name());
                        return;
                    }
                    lastFailure = diagnostic;
                    if (diagnostic.isEmpty()) {
                        Log.i(TAG, "GenerationResult id=" + request.diagnosticId + " disposition=MODEL_REPLY");
                        generationResponseCount++;
                        flow.replyResult(result, SystemClock.uptimeMillis());
                    } else {
                        boolean recovered = replyEngine != null && replyEngine.isReady(CallAccessibilityService.this)
                                && flow.generationFailure(diagnostic, SystemClock.uptimeMillis());
                        Log.w(TAG, "GenerationError " + ReplyDiagnostics.generationError(diagnostic, recovered));
                        Log.w(TAG, "GenerationResult id=" + request.diagnosticId + " "
                                + ReplyDiagnostics.generationError(diagnostic, recovered));
                        if (!recovered) {
                            flow.disable();
                            releaseModel();
                        }
                    }
                    cancelLlm();
                    publish();
                    drive();
                }));
                if (started) {
                    generationRequestCount++;
                    Log.i(TAG, "GenerationRequest id=" + request.diagnosticId + " requests=" + generationRequestCount);
                    pendingReplyStart = null;
                } else {
                    // A cancelled native call may still be draining. The flow's 30s deadline still applies.
                    if (!deferredLogged) {
                        deferredLogged = true;
                        Log.i(TAG, "GenerationDeferred id=" + request.diagnosticId + " reason=ENGINE_NOT_READY");
                    }
                    handler.postDelayed(this, 100);
                }
            }
        };
        handler.post(pendingReplyStart);
    }

    public static boolean isAllowedPackage(String packageName) {
        return CallPackagePolicy.isAllowedPackage(packageName);
    }

    private Set<String> extractVisibleKnownViewIds(AccessibilityNodeInfo root) {
        Set<String> foundIds = new HashSet<>();
        checkAndAddVisible(root, CallStateModel.ID_ANSWER_CONTAINER, foundIds);
        checkAndAddVisible(root, CallStateModel.ID_CALL_TIME, foundIds);
        checkAndAddVisible(root, CallStateModel.ID_AI_BUTTON, foundIds);
        checkAndAddVisible(root, CallStateModel.ID_TEXT_CALL_BUTTON, foundIds);
        checkAndAddVisible(root, CallStateModel.ID_TEXT_MESSAGE_EDIT, foundIds);
        checkAndAddVisible(root, CallStateModel.ID_CALL_ENDED, foundIds);
        return foundIds;
    }

    private void checkAndAddVisible(AccessibilityNodeInfo root, String viewId, Set<String> target) {
        if (SamsungCallUi.visible(root, viewId)) target.add(viewId);
    }
}
