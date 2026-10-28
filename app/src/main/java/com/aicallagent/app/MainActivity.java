package com.aicallagent.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import com.aicallagent.app.local.LocalModelRuntime;
import com.aicallagent.app.local.LocalModelActivity;
import java.io.IOException;

import com.aicallagent.app.model.CallStateModel;
import com.aicallagent.app.model.AutoReplyFlow;

/**
 * AI 통화 비서의 기본 메인 화면.
 * 접근성 서비스의 실제 연결 상태와 마지막으로 관찰된 통화 상태를 안전하게 표시합니다.
 */
public class MainActivity extends Activity {

    private TextView textServiceStatus;
    private TextView textCallState;
    private Button btnOpenSettings;
    private Button btnAutoReply;
    private TextView textReplyStatus;
    private TextView textDiagnostics;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresher = new Runnable() {
        @Override
        public void run() {
            updateUi();
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textServiceStatus = findViewById(R.id.text_service_status);
        textCallState = findViewById(R.id.text_call_state);
        btnOpenSettings = findViewById(R.id.btn_open_accessibility_settings);
        btnAutoReply = findViewById(R.id.btn_auto_reply);
        textReplyStatus = findViewById(R.id.text_reply_status);
        textDiagnostics = findViewById(R.id.text_call_diagnostics);
        findViewById(R.id.btn_open_local_model).setOnClickListener(v ->
                startActivity(new Intent(this, com.aicallagent.app.local.LocalModelActivity.class)));
        btnAutoReply.setOnClickListener(v -> {
            if (CallAccessibilityService.isAutoReplyEnabled() || CallAccessibilityService.isModelPreparing()) {
                CallAccessibilityService.setAutoReplyEnabled(false);
                updateUi();
            } else if (!CallAccessibilityService.hasPhoneStatePermission(this)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.phone_state_permission_title)
                        .setMessage(R.string.phone_state_permission_notice)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.phone_state_permission_request, (dialog, which) ->
                                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 15))
                        .show();
            } else if (!hasLocalModel()) {
                startActivity(new Intent(this, LocalModelActivity.class));
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.auto_reply_confirm_title)
                        .setMessage(R.string.auto_reply_confirm_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.auto_reply_start, (dialog, which) -> {
                            CallAccessibilityService.setAutoReplyEnabled(true);
                            updateUi();
                        }).show();
            }
        });

        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 15) updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
        mainHandler.removeCallbacks(statusRefresher);
        mainHandler.postDelayed(statusRefresher, 1000);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(statusRefresher);
        super.onPause();
    }

    private void updateUi() {
        boolean connected = CallAccessibilityService.isServiceConnected();
        if (connected) {
            textServiceStatus.setText(R.string.status_service_connected);
        } else {
            textServiceStatus.setText(R.string.status_service_disconnected);
        }

        CallStateModel.CallState state = CallAccessibilityService.getLastObservedCallState();
        String stateText = state == CallStateModel.CallState.IDLE ? getString(R.string.call_not_observed)
                : getString(R.string.label_last_observed_state_format, getString(callStateLabel(state)));
        textCallState.setText(stateText);
        updateDiagnostics();
        boolean enabled = CallAccessibilityService.isAutoReplyEnabled();
        btnAutoReply.setText(CallAccessibilityService.isModelPreparing() ? R.string.local_cancel
                : enabled ? R.string.auto_reply_stop : R.string.auto_reply_start);
        btnAutoReply.setEnabled(connected);
        if (!connected) {
            textReplyStatus.setText(R.string.reply_needs_service);
        } else if (CallAccessibilityService.isModelPreparing()) {
            textReplyStatus.setText(R.string.local_loading);
        } else {
            textReplyStatus.setText(getString(R.string.reply_status_format, getString(replyStatus())));
        }
    }

    private void updateDiagnostics() {
        int window;
        switch (CallAccessibilityService.getWindowObservation()) {
            case NO_WINDOW: window = R.string.call_window_none; break;
            case OTHER_APP: window = R.string.call_window_other; break;
            case PHONE_NO_CONTROLS: window = R.string.call_window_no_controls; break;
            case CALL_VISIBLE: window = R.string.call_window_visible; break;
            case SYSTEM_IDLE: window = R.string.system_call_idle; break;
            default: window = R.string.call_not_observed; break;
        }
        String error = CallAccessibilityService.getLastFailure();
        String result = CallAccessibilityService.isGenerationRunning() ? getString(R.string.llm_call_running)
                : !error.isEmpty() ? LlmStatusText.error(this, error)
                : CallAccessibilityService.getGenerationResponseCount() > 0 ? getString(R.string.llm_call_success)
                : CallAccessibilityService.getGenerationRequestCount() > 0 ? getString(R.string.llm_call_no_response)
                : getString(R.string.llm_call_none);
        String model = getString(hasLocalModel() ? R.string.local_present : R.string.local_missing);
        Boolean inCall = CallAccessibilityService.getSystemCallActive();
        textDiagnostics.setText(getString(R.string.call_diagnostics_format, getString(window),
                CallAccessibilityService.getObservedSpeechCount(), CallAccessibilityService.getGenerationRequestCount(),
                CallAccessibilityService.getGenerationResponseCount(), CallAccessibilityService.getSentReplyCount(), result, model,
                getString(inCall == null
                        ? R.string.system_call_unknown : inCall
                        ? R.string.system_call_active : R.string.system_call_idle),
                getString(CallAccessibilityService.isDiagnosticInputReady() ? R.string.diagnostic_yes : R.string.diagnostic_no),
                getString(CallAccessibilityService.isDiagnosticInputMatches() ? R.string.diagnostic_yes : R.string.diagnostic_no),
                getString(CallAccessibilityService.isDiagnosticSubmitReady() ? R.string.diagnostic_yes : R.string.diagnostic_no)));
    }

    private boolean hasLocalModel() {
        try { return LocalModelRuntime.get(this).hasModel(); }
        catch (IOException failure) { return false; }
    }

    private int replyStatus() {
        if (!CallAccessibilityService.hasPhoneStatePermission(this)) return R.string.phone_state_permission_missing;
        switch (CallAccessibilityService.getReplyStage()) {
            case WAIT_INCOMING: return R.string.reply_waiting;
            case WAIT_CONNECTED: return R.string.reply_answering;
            case WAIT_ASSIST:
            case WAIT_TEXT:
            case WAIT_TEXT_ACTIVE: return R.string.reply_converting;
            case WAIT_REMOTE: return R.string.reply_listening;
            case WAIT_LLM: return R.string.reply_wait_llm;
            case WAIT_LLM_RETRY: return R.string.reply_retry_llm;
            case WAIT_INPUT:
            case VERIFY_DRAFT_CLEARED:
            case VERIFY_INPUT: return R.string.reply_preparing;
            case VERIFY_SENT: return R.string.reply_sending;
            case WAIT_SPEECH: return R.string.reply_wait_speech;
            case WAIT_END: return R.string.reply_wait_end;
            case FAILED:
                AutoReplyFlow.Failure failure = CallAccessibilityService.getReplyFailure();
                if (failure == AutoReplyFlow.Failure.EXISTING_INPUT) return R.string.reply_existing_input;
                if (failure == AutoReplyFlow.Failure.INPUT_CHANGED) return R.string.reply_input_changed;
                if (failure == AutoReplyFlow.Failure.LLM_FAILED) return R.string.reply_llm_failed;
                if (failure == AutoReplyFlow.Failure.CONTEXT_LIMIT) return R.string.reply_context_limit;
                if (failure == AutoReplyFlow.Failure.TIMEOUT) return R.string.reply_timeout;
                if (failure == AutoReplyFlow.Failure.SEND_REJECTED) return R.string.reply_send_rejected;
                if (failure == AutoReplyFlow.Failure.SEND_UNCONFIRMED) return R.string.reply_send_unconfirmed;
                if (failure == AutoReplyFlow.Failure.SPEECH_BUSY_TIMEOUT) return R.string.reply_speech_timeout;
                return R.string.reply_action_failed;
            case OFF:
            default: return R.string.reply_off;
        }
    }

    private static int callStateLabel(CallStateModel.CallState state) {
        if (state == null) return R.string.call_state_idle;
        switch (state) {
            case RINGING_INCOMING: return R.string.call_state_ringing;
            case CONNECTED_VOICE: return R.string.call_state_connected_voice;
            case AI_MENU_AVAILABLE: return R.string.call_state_ai_menu_available;
            case TEXT_CALL_ACTIVE: return R.string.call_state_text_call_active;
            case ENDED: return R.string.call_state_ended;
            default: return R.string.call_state_idle;
        }
    }
}
