package com.aicallagent.app.local;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.aicallagent.app.CallAccessibilityService;
import com.aicallagent.app.R;
import com.aicallagent.app.LlmStatusText;
import java.io.IOException;

/** A synthetic, local-only experiment. Never passes results to the call service. */
public final class LocalModelActivity extends Activity {
    private static final int PICK_MODEL = 31;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocalModelRuntime runtime;
    private Uri selected;
    private long selectedSize = -1;
    private EditText hash;
    private TextView status, answer, modelInfo, selection;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 300); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_local_model);
        hash = findViewById(R.id.local_hash);
        status = findViewById(R.id.local_status);
        answer = findViewById(R.id.local_answer);
        modelInfo = findViewById(R.id.local_model_info);
        selection = findViewById(R.id.local_selection);
        try { runtime = LocalModelRuntime.get(this); }
        catch (IOException failure) { status.setText(R.string.local_storage_error); }
        if (runtime != null) ((RadioGroup) findViewById(R.id.local_backend)).check(runtime.selectedGpu() ? R.id.local_gpu : R.id.local_cpu);
        ((RadioGroup) findViewById(R.id.local_backend)).setOnCheckedChangeListener((group, checked) -> {
            if (runtime != null) { runtime.selectGpu(checked == R.id.local_gpu); runtime.release(); }
            render();
        });
        findViewById(R.id.local_pick).setOnClickListener(v -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(picker, PICK_MODEL);
        });
        findViewById(R.id.local_import).setOnClickListener(v -> {
            if (runtime == null || selected == null || blockedByCall()) return;
            String expected = hash.getText().toString().trim();
            if (!expected.matches("[0-9a-fA-F]{64}")) {
                hash.setError(getString(R.string.local_hash_error)); return;
            }
            hash.setError(null);
            ContentResolver resolver = getApplicationContext().getContentResolver();
            Uri uri = selected;
            runtime.importModel(() -> resolver.openInputStream(uri), selectedSize, expected);
            render();
        });
        findViewById(R.id.local_load).setOnClickListener(v -> {
            if (runtime == null || blockedByCall()) return;
            runtime.load(((RadioGroup) findViewById(R.id.local_backend)).getCheckedRadioButtonId() == R.id.local_gpu);
            render();
        });
        findViewById(R.id.local_generate).setOnClickListener(v -> {
            if (runtime != null && !blockedByCall()) {
                runtime.generate(isKoreanLocale());
                render();
            }
        });
        findViewById(R.id.local_cancel).setOnClickListener(v -> { if (runtime != null) runtime.cancel(); render(); });
        findViewById(R.id.local_release).setOnClickListener(v -> { if (runtime != null) runtime.release(); render(); });
        findViewById(R.id.local_back).setOnClickListener(v -> finish());
    }

    private boolean callBusy() {
        return (runtime != null && runtime.callOwned()) || CallAccessibilityService.isModelPreparing()
                || CallAccessibilityService.isAutoReplyEnabled()
                || Boolean.TRUE.equals(CallAccessibilityService.getSystemCallActive());
    }
    private boolean blockedByCall() {
        if (!callBusy()) return false;
        status.setText(R.string.local_call_blocked); return true;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_MODEL || result != RESULT_OK || data == null) return;
        selected = data.getData(); selectedSize = -1;
        if (selected == null || !ContentResolver.SCHEME_CONTENT.equals(selected.getScheme())) {
            selected = null; selection.setText(R.string.local_no_selection); return;
        }
        // The document provider may omit SIZE. The streaming copy enforces the actual upper limit.
        try (Cursor cursor = getContentResolver().query(selected, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) selectedSize = cursor.getLong(0);
        } catch (RuntimeException failure) { selectedSize = -1; }
        selection.setText(selectedSize < 0 ? getString(R.string.local_selected_unknown)
                : getString(R.string.local_selected, selectedSize / 1048576.0));
        render();
    }

    @Override protected void onResume() {
        super.onResume(); handler.post(refresh);
    }
    @Override protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        // Opening the document picker must not invalidate the URI result. Native work is still released.
        if (runtime != null) runtime.release();
        answer.setText("");
        super.onStop();
    }

    private void render() {
        if (runtime == null) {
            for (int id : new int[]{R.id.local_pick, R.id.local_import, R.id.local_load,
                    R.id.local_generate, R.id.local_cancel, R.id.local_release}) findViewById(id).setEnabled(false);
            return;
        }
        LocalModelRuntime.Snapshot state = runtime.snapshot();
        boolean call = callBusy();
        boolean idle = !state.busy && runtime.session.canStart();
        findViewById(R.id.local_pick).setEnabled(idle && !call);
        findViewById(R.id.local_import).setEnabled(idle && !call && selected != null);
        hash.setEnabled(idle && !call);
        findViewById(R.id.local_cpu).setEnabled(idle && !call);
        findViewById(R.id.local_gpu).setEnabled(idle && !call);
        findViewById(R.id.local_load).setEnabled(idle && !call && !state.hash.isEmpty());
        findViewById(R.id.local_generate).setEnabled(idle && !call && state.session.state == LocalModelSession.State.READY);
        findViewById(R.id.local_cancel).setEnabled(!call && state.busy && !state.cancelling && !state.operation.equals("RELEASE"));
        findViewById(R.id.local_release).setEnabled(!call && idle && state.session.state != LocalModelSession.State.EMPTY);
        modelInfo.setText(state.hash.isEmpty() ? getString(R.string.local_missing)
                : getString(R.string.local_model_info, state.bytes / 1048576.0, state.hash));
        int message;
        if (call) message = R.string.local_call_blocked;
        else if (state.busy) message = state.cancelling ? R.string.local_cancelling
                : state.operation.equals("IMPORT") ? R.string.local_importing
                : state.operation.equals("LOAD") ? R.string.local_loading
                : state.operation.equals("GENERATE") ? R.string.local_generating : R.string.local_releasing;
        else if (!state.error.isEmpty()) message = errorText(state.error);
        else message = state.session.state == LocalModelSession.State.READY ? R.string.local_ready : R.string.local_idle;
        status.setText(getString(R.string.local_status_format, getString(message), state.session.elapsedMs / 1000.0));
        answer.setText(state.busy || state.cancelling ? "" : state.session.answer);
        if (call && !runtime.callOwned() && (state.busy || state.session.state == LocalModelSession.State.READY) && !state.cancelling) runtime.release();
    }
    private static int errorText(String code) { return LlmStatusText.resource(code); }
    private boolean isKoreanLocale() {
        android.os.LocaleList locales = getResources().getConfiguration().getLocales();
        if (locales == null || locales.isEmpty()) {
            return "ko".equalsIgnoreCase(java.util.Locale.getDefault().getLanguage());
        }
        java.util.Locale locale = locales.get(0);
        return locale != null && "ko".equalsIgnoreCase(locale.getLanguage());
    }
}
