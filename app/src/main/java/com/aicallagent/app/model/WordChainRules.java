package com.aicallagent.app.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structural checks only. Word generation and lexical knowledge belong to the model. */
public final class WordChainRules {
    public static final String RETRY_NOTICE = "응답을 제대로 만들지 못했어요. 같은 단어를 다시 말씀해 주세요.";
    public static final String LOSS = "제가 이어갈 단어를 찾지 못했어요. 제가 졌습니다. 새 게임을 하려면 새로운 단어를 말씀해 주세요. 게임을 원하지 않으시면 통화를 종료해 주세요.";
    public static final String STOP_NOTICE = "알겠습니다. 게임을 마칠게요. 통화를 종료해 주세요.";
    private static final Set<String> CONTROLS = Set.of("안녕", "안녕하세요", "안녕하십니까", "여보세요",
            "감사합니다", "고마워요", "좋아요", "알겠어요", "시작", "다시시작", "다시할게요",
            "그만", "그만할게요", "종료", "끝낼게요", "반갑습니다", "알겠습니다", "죄송합니다", "네", "예");
    private static final Pattern START = Pattern.compile("^([가-힣]{2,})(?:으로|로)\\s*시작(?:할게요|하겠습니다|해요)$");

    private WordChainRules() { }

    private static String plain(String text) {
        return text.trim().replaceFirst("[.!?。！？]+$", "").trim();
    }

    private static boolean restart(String text) {
        String value = plain(text).replaceAll("\\s+", "");
        return value.equals("다시시작") || value.equals("다시시작할게요") || value.equals("다시할게요");
    }

    private static String word(String text) {
        String value = plain(text);
        if (CONTROLS.contains(value.replaceAll("\\s+", ""))) return "";
        value = value.replaceFirst("^(?:안녕하세요|안녕하십니까)[,，]?\\s+", "");
        Matcher start = START.matcher(value);
        if (start.matches()) return start.group(1);
        return value.matches("[가-힣]{2,}") && !value.endsWith("요") ? value : "";
    }

    private static final class Position {
        final Set<String> used = new LinkedHashSet<>();
        String last = "";
    }

    private static Position position(List<CallConversation.Message> history) {
        Position position = new Position();
        for (int i = 0; i < history.size() - 1; i++) {
            CallConversation.Message message = history.get(i);
            if (("user".equals(message.role) && restart(message.content))
                    || ("assistant".equals(message.role) && LOSS.equals(message.content))) {
                position.used.clear(); position.last = "";
                continue;
            }
            // A failed generation did not consume the caller's game turn. The notice is
            // committed only after confirmed delivery, so a new row can repeat that word.
            if ("user".equals(message.role) && i + 1 < history.size()
                    && "assistant".equals(history.get(i + 1).role)
                    && RETRY_NOTICE.equals(history.get(i + 1).content)) continue;
            String word = word(message.content);
            if (word.isEmpty() || position.used.contains(word)) continue;
            if (!position.last.isEmpty() && !connects(lastLetter(position.last), word.charAt(0))) continue;
            position.used.add(word); position.last = word;
        }
        return position;
    }

    private static char lastLetter(String word) { return word.charAt(word.length() - 1); }

    /** User's relaxed rule: interchange ㄴ/ㄹ/ㅇ while preserving vowel and final. */
    private static List<String> allowedStarts(char syllable) {
        Set<String> starts = new LinkedHashSet<>();
        starts.add(String.valueOf(syllable));
        if (syllable >= '가' && syllable <= '힣') {
            int offset = syllable - '가', initial = offset / 588;
            if (initial == 2 || initial == 5 || initial == 11)
                for (int replacement : new int[]{2, 5, 11})
                    starts.add(String.valueOf((char) ('가' + replacement * 588 + offset % 588)));
        }
        return List.copyOf(starts);
    }
    private static boolean connects(char last, char first) {
        return allowedStarts(last).contains(String.valueOf(first));
    }

    public enum Reason { NONE, BASE_INVALID, TOO_SHORT, WORD_FORMAT, WRONG_START, DUPLICATE,
        CALLER_CORRECTION_REQUIRED }
    public enum Turn { UNKNOWN, MESSAGE, RESTART, WORD, CALLER_DUPLICATE, CALLER_WRONG_START }
    /** The same turn snapshot drives validation and the private model instruction. */
    public static final class TurnState {
        public final Turn turn;
        public final String latestWord, lastWord, requiredStart;
        public final List<String> usedWords, allowedStarts;
        private TurnState(Turn turn, String latest, String last, String required, Set<String> used) {
            this.turn = turn; latestWord = latest; lastWord = last; requiredStart = required;
            usedWords = List.copyOf(used);
            allowedStarts = required.isEmpty() ? List.of() : WordChainRules.allowedStarts(required.charAt(0));
        }
    }
    public static TurnState turnState(List<CallConversation.Message> history) {
        String content = history.get(history.size() - 1).content;
        String latest = word(content);
        Position position = position(history);
        boolean restarting = restart(content);
        if (restarting) { position.used.clear(); position.last = ""; }
        Turn turn = restarting ? Turn.RESTART : latest.isEmpty() ? Turn.MESSAGE : Turn.WORD;
        if (!latest.isEmpty()) {
            if (position.used.contains(latest)) turn = Turn.CALLER_DUPLICATE;
            else if (!position.last.isEmpty() && !connects(lastLetter(position.last), latest.charAt(0))) turn = Turn.CALLER_WRONG_START;
        }
        String required = turn == Turn.WORD ? String.valueOf(lastLetter(latest))
                : position.last.isEmpty() ? "" : String.valueOf(lastLetter(position.last));
        return new TurnState(turn, latest, position.last, required, position.used);
    }

    public static final class Check {
        public final String accepted;
        public final Reason reason;
        public final Turn turn;
        public final int usedWords, inputWordLength, outputWordLength;
        private Check(String accepted, Reason reason, Turn turn, int usedWords, int inputLength, int outputLength) {
            this.accepted = accepted; this.reason = reason; this.turn = turn;
            this.usedWords = usedWords; this.inputWordLength = inputLength; this.outputWordLength = outputLength;
        }
    }

    /** Returns the model text unchanged apart from trim, or null to request regeneration. */
    public static String guardReply(List<CallConversation.Message> history, String answer) {
        return checkReply(history, answer).accepted;
    }

    /** The decision and diagnostic reason share the same checks; never log accepted text. */
    public static Check checkReply(List<CallConversation.Message> history, String answer) {
        if (!AutoReplyFlow.isValidReply(answer))
            return new Check(null, Reason.BASE_INVALID, Turn.UNKNOWN, 0, 0, 0);
        String text = answer.trim();
        String candidate = plain(text);
        TurnState state = turnState(history);
        String latest = state.latestWord;
        Turn turn = state.turn;
        Reason reason = Reason.NONE;
        if (latest.isEmpty()) {
            if (candidate.matches("[가-힣]+") && !CONTROLS.contains(candidate)) {
                if (candidate.length() < 2) reason = Reason.TOO_SHORT;
                else if (state.usedWords.contains(candidate)) reason = Reason.DUPLICATE;
            }
        } else {
            if (turn == Turn.CALLER_DUPLICATE || turn == Turn.CALLER_WRONG_START) {
                if (LOSS.equals(text) || STOP_NOTICE.equals(text) || candidate.matches("[가-힣]+"))
                    reason = Reason.CALLER_CORRECTION_REQUIRED;
            } else if (!LOSS.equals(text)) {
                if (!candidate.matches("[가-힣]+")) reason = Reason.WORD_FORMAT;
                else if (candidate.length() < 2) reason = Reason.TOO_SHORT;
                else if (!connects(lastLetter(latest), candidate.charAt(0))) reason = Reason.WRONG_START;
                else if (state.usedWords.contains(candidate) || candidate.equals(latest)) reason = Reason.DUPLICATE;
            }
        }
        return new Check(reason == Reason.NONE ? text : null, reason, turn,
                state.usedWords.size(), latest.length(), candidate.length());
    }
}
