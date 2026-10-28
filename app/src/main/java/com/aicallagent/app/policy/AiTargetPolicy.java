package com.aicallagent.app.policy;

import java.util.Locale;
import com.aicallagent.app.model.CallStateModel;

/**
 * AI 활성화 버튼 및 텍스트 통화 UI 선택 대상의 안전성 검증 정책.
 * ID와 의미적 설명 라벨이 모두 일치해야 하며, 영상통화 및 통화 종료 관련 대상을 엄격히 거부합니다.
 */
public final class AiTargetPolicy {

    public static final String VERIFIED_ID = "com.samsung.android.incallui:id/ai_call_assist_button";
    public static final String VERIFIED_LABEL = "통화 어시스트";

    private AiTargetPolicy() {
    }

    public static boolean matchesTextCall(String id, String label) {
        return CallStateModel.ID_TEXT_CALL_BUTTON.equals(id)
                && localizedLabel(label, "텍스트 통화", "text call");
    }

    private static boolean localizedLabel(String label, String korean, String english) {
        if (label == null) return false;
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        return normalized.equals(korean) || normalized.equals(korean + ", 버튼")
                || normalized.equals(english) || normalized.equals(english + ", button");
    }

    public static boolean matches(String configuredId, String configuredLabel, String id, String label) {
        if (configuredId == null || configuredId.isEmpty() || configuredLabel == null || configuredLabel.isEmpty()) {
            return false;
        }
        if (id == null || label == null) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("upgrade") || lower.contains("video") || lower.contains("disconnect")) {
            return false;
        }
        if (label.contains("영상") || label.contains("화상")) {
            return false;
        }
        if (!configuredId.equals(id)) return false;
        if (VERIFIED_ID.equals(configuredId)
                && localizedLabel(configuredLabel, VERIFIED_LABEL, "call assist")) {
            return localizedLabel(label, VERIFIED_LABEL, "call assist");
        }
        // Custom targets retain exact matching; translation aliases apply only to verified IDs.
        return configuredLabel.equals(label);
    }
}
