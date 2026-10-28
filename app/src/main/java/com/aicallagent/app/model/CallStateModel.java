package com.aicallagent.app.model;

import java.util.Set;

/**
 * 삼성 통화 화면의 UI 요소를 기반으로 현재 통화 상태를 결정하는 모델.
 */
public final class CallStateModel {

    // 확인된 삼성 통화 UI 리소스 ID (com.samsung.android.incallui)
    public static final String ID_ANSWER_CONTAINER = "com.samsung.android.incallui:id/answer_fragment_container";
    public static final String ID_CALL_TIME = "com.samsung.android.incallui:id/call_time";
    public static final String ID_AI_BUTTON = "com.samsung.android.incallui:id/ai_call_assist_button";
    public static final String ID_TEXT_CALL_BUTTON = "com.samsung.android.incallui:id/text_call_button";
    public static final String ID_TEXT_MESSAGE_EDIT = "com.samsung.android.incallui:id/text_call_message_edit_text";
    public static final String ID_CALL_ENDED = "com.samsung.android.incallui:id/primary_voice_call_again_button";

    public enum CallState {
        IDLE("대기"),
        RINGING_INCOMING("수신 중"),
        CONNECTED_VOICE("음성 통화 연결됨"),
        AI_MENU_AVAILABLE("AI 통화 어시스트 가능"),
        TEXT_CALL_ACTIVE("텍스트 통화 진행 중"),
        ENDED("통화 종료");

        private final String displayName;

        CallState(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private CallStateModel() {
    }

    public static CallState lastObserved(CallState previous, Set<String> visibleViewIds) {
        CallState current = determineState(visibleViewIds);
        return current == CallState.IDLE ? previous : current;
    }

    /**
     * 화면상에 존재하는 뷰 ID 집합을 분석하여 통화 상태를 판정합니다.
     * 종료 화면 -> 텍스트 통화 -> AI 어시스트 -> 일반 음성 연결 -> 수신 순서로 우선순위를 적용합니다.
     */
    public static CallState determineState(Set<String> visibleViewIds) {
        if (visibleViewIds == null || visibleViewIds.isEmpty()) {
            return CallState.IDLE;
        }
        if (visibleViewIds.contains(ID_CALL_ENDED)) {
            return CallState.ENDED;
        }
        if (visibleViewIds.contains(ID_TEXT_MESSAGE_EDIT)) {
            return CallState.TEXT_CALL_ACTIVE;
        }
        if (visibleViewIds.contains(ID_AI_BUTTON)) {
            return CallState.AI_MENU_AVAILABLE;
        }
        if (visibleViewIds.contains(ID_CALL_TIME)) {
            return CallState.CONNECTED_VOICE;
        }
        if (visibleViewIds.contains(ID_ANSWER_CONTAINER)) {
            return CallState.RINGING_INCOMING;
        }
        return CallState.IDLE;
    }
}
