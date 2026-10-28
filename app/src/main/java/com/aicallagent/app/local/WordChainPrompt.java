package com.aicallagent.app.local;

import com.aicallagent.app.model.CallConversation;
import com.aicallagent.app.model.WordChainRules;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

/** Private model input, never diagnostics. No word dictionary or game answer selection. */
final class WordChainPrompt {
    static final String LOSS_SIGNAL = CallConversation.LOSS_SIGNAL, STOP_SIGNAL = CallConversation.STOP_SIGNAL;
    private WordChainPrompt() { }

    static String turn(List<CallConversation.Message> history) {
        WordChainRules.TurnState state = WordChainRules.turnState(history);
        LinkedHashSet<String> forbidden = new LinkedHashSet<>(state.usedWords);
        if (state.turn == WordChainRules.Turn.WORD) forbidden.add(state.latestWord);
        String instruction = "\n현재 차례의 확정 조건:\n";
        instruction += switch (state.turn) {
            case WORD -> "AI 단어 차례입니다.\n상대 단어: " + state.latestWord
                    + "\n허용 첫 음절: " + String.join(", ", state.allowedStarts)
                    + "\n답의 첫 음절은 위 목록 중 하나여야 합니다. 완화된 두음법칙은 초성만 바꾸며 중성·종성은 유지합니다."
                    + "\n허용 음절로 시작하는 실제 명사 하나를 고르세요. 온전한 명사를 모르면 다른 음절로 바꾸거나 단어를 지어내지 말고 "
                    + LOSS_SIGNAL + "만 출력하세요. 예시 단어를 따라 할 필요는 없습니다.\n";
            case CALLER_DUPLICATE, CALLER_WRONG_START -> "상대방 차례 오류입니다. AI가 새 단어를 낼 차례가 아닙니다.\n상대가 말해야 할 첫 음절: "
                    + String.join(", ", state.allowedStarts) + "\n" + (state.turn == WordChainRules.Turn.CALLER_DUPLICATE ? "이미 사용한 단어입니다." : "앞 단어와 연결되지 않았습니다.")
                    + " 필요한 첫 음절을 알려 다시 요청하세요. 패배 신호를 출력하지 마세요.\n";
            case RESTART -> "새 게임입니다. 이전 단어·연결을 적용하지 말고 먼저 단어를 요청하세요.\n";
            default -> "명확한 단어 차례가 아닙니다. 마지막 발화의 인사·규칙·재시작·종료 의사를 처리하세요."
                    + " 문장에서 임의의 명사를 골라 게임을 진행하지 마세요. 먼저 시작해 달라는 요청에는 쉬운 단어 하나를 낼 수 있습니다.\n"
                    + (state.lastWord.isEmpty() ? "" : "현재 마지막 게임 단어: " + state.lastWord + "\n");
        };
        return instruction + "사용 금지 단어: " + (forbidden.isEmpty() ? "없음" : String.join(", ", forbidden)) + "\n";
    }

    static String repair(WordChainRules.Reason reason, boolean earlyLoss) {
        String problem = earlyLoss ? "첫 패배 선택을 한 번 더 확인합니다."
                : switch (reason) {
                    case WRONG_START -> "WRONG_START: 첫 음절이 일치하지 않았습니다.";
                    case TOO_SHORT -> "TOO_SHORT: 한 글자는 단어가 아닙니다. 두 글자 이상 명사가 필요합니다.";
                    case DUPLICATE -> "DUPLICATE: 이미 쓴 단어 또는 상대 단어를 반복했습니다.";
                    case WORD_FORMAT -> "WORD_FORMAT: 명사 하나 대신 설명·여러 단어·기호가 나왔습니다.";
                    case CALLER_CORRECTION_REQUIRED -> "CALLER_CORRECTION_REQUIRED: 지금은 상대방의 규칙 오류를 안내할 차례입니다.";
                    default -> "응답 형식이 잘못됐습니다.";
                };
        return "\n수정 요청: " + problem + "\n위의 현재 차례 조건을 다시 확인하세요."
                + " AI 단어 차례라면 허용 첫 음절 중 하나로 시작하는 미사용 실제 명사 하나만 출력하세요."
                + " 허용 목록 밖의 첫 음절로 바꾸지 마세요. 찾을 수 없다면 " + LOSS_SIGNAL + "만 출력하세요."
                + " 상대방 차례 오류라면 짧은 수정 안내를 하세요.\n";
    }

    static int repairReserveBytes() {
        int max = Math.max(repair(WordChainRules.Reason.NONE, true).getBytes(StandardCharsets.UTF_8).length,
                englishTestRepair().getBytes(StandardCharsets.UTF_8).length);
        for (var reason : WordChainRules.Reason.values())
            max = Math.max(max, repair(reason, false).getBytes(StandardCharsets.UTF_8).length);
        return max;
    }

    static String englishTestTurn() {
        return "\nCurrent turn conditions:\n"
                + "AI word turn.\n"
                + "Opponent word: Banana\n"
                + "Allowed starting letter: a, A\n"
                + "The answer's first letter must be one of the allowed letters above.\n"
                + "Choose a valid English noun starting with the allowed letter. Target test word: Apple. "
                + "Output only the single noun: Apple.\n"
                + "Forbidden words: Banana\n";
    }

    static String englishTestRepair() {
        return "\nCorrection request: WRONG_START or invalid format.\n"
                + "Review the current turn conditions above.\n"
                + "Since it is the AI word turn, output only one unused valid English noun starting with an allowed letter: Apple.\n"
                + "Do not output explanations, quotes, lists, or any other words.\n";
    }

    static String speech(String output) {
        if (output == null) return null;
        return switch (output.trim()) {
            case LOSS_SIGNAL -> WordChainRules.LOSS;
            case STOP_SIGNAL -> WordChainRules.STOP_NOTICE;
            default -> output.contains("[") || output.contains("]") ? null : output;
        };
    }
}
