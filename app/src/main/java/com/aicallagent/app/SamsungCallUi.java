package com.aicallagent.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.aicallagent.app.model.AutoReplyFlow;
import com.aicallagent.app.model.CallStateModel;
import com.aicallagent.app.model.IncomingCallGate;
import com.aicallagent.app.policy.AiTargetPolicy;
import com.aicallagent.app.policy.CallPackagePolicy;

import java.util.List;

/** Reads only the known Samsung call controls. Never stores or logs call content. */
final class SamsungCallUi {
    static final String REMOTE_ID = "com.samsung.android.incallui:id/remote_message";
    static final String LOCAL_ID = "com.samsung.android.incallui:id/local_message";
    static final String SUBMIT_ID = "com.samsung.android.incallui:id/text_call_submit_button";
    private static final int MAX_TEXT = 4096;

    private SamsungCallUi() { }

    static boolean trusted(AccessibilityNodeInfo root) {
        return root != null && CallPackagePolicy.isAllowedPackage(root.getPackageName());
    }

    static boolean visible(AccessibilityNodeInfo root, String id) {
        if (!trusted(root)) return false;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id)) {
            if (node != null && node.isVisibleToUser()) return true;
        }
        return false;
    }

    static AccessibilityNodeInfo unique(AccessibilityNodeInfo root, String id) {
        if (!trusted(root)) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if (nodes.size() != 1) return null;
        AccessibilityNodeInfo node = nodes.get(0);
        return node != null && node.isVisibleToUser()
                && CallPackagePolicy.isAllowedPackage(node.getPackageName()) ? node : null;
    }

    static boolean supports(AccessibilityNodeInfo node, int action) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return false;
        for (AccessibilityNodeInfo.AccessibilityAction candidate : node.getActionList()) {
            if (candidate.getId() == action) return true;
        }
        return false;
    }

    private static boolean clickable(AccessibilityNodeInfo node) {
        return node != null && node.isClickable()
                && supports(node, AccessibilityNodeInfo.ACTION_CLICK) && !bounds(node).isEmpty();
    }

    static Rect bounds(AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        if (node != null) node.getBoundsInScreen(rect);
        return rect;
    }

    private static String label(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence value = node.getContentDescription();
        if (value == null) value = node.getText();
        return value == null ? "" : value.toString();
    }

    static AccessibilityNodeInfo buttonTarget(AccessibilityNodeInfo root, AutoReplyFlow.Action action) {
        AccessibilityNodeInfo node;
        if (action == AutoReplyFlow.Action.OPEN_ASSIST) {
            node = unique(root, AiTargetPolicy.VERIFIED_ID);
            if (node == null || !AiTargetPolicy.matches(AiTargetPolicy.VERIFIED_ID,
                    AiTargetPolicy.VERIFIED_LABEL, node.getViewIdResourceName(), label(node))) return null;
        } else if (action == AutoReplyFlow.Action.OPEN_TEXT) {
            node = unique(root, CallStateModel.ID_TEXT_CALL_BUTTON);
            if (node == null || !AiTargetPolicy.matchesTextCall(node.getViewIdResourceName(), label(node))) return null;
        } else if (action == AutoReplyFlow.Action.SEND_REPLY) {
            node = unique(root, SUBMIT_ID);
        } else {
            return null;
        }
        if (!clickable(node)) return null;
        Rect rootRect = bounds(root);
        Rect targetRect = bounds(node);
        return rootRect.contains(targetRect) ? node : null;
    }

    static boolean emptyInput(AccessibilityNodeInfo node) {
        return node != null && (node.isShowingHintText() || node.getText() == null
                || node.getText().length() == 0);
    }

    private static boolean hasIncomingLabel(AccessibilityNodeInfo root) {
        for (String query : new String[]{"Incoming", "수신"}) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(query)) {
                if (node != null && node.isVisibleToUser() && !node.isEditable()
                        && CallPackagePolicy.isAllowedPackage(node.getPackageName())
                        && (IncomingCallGate.isIncomingLabel(node.getText())
                        || IncomingCallGate.isIncomingLabel(node.getContentDescription()))) return true;
            }
        }
        return false;
    }

    static AutoReplyFlow.Snapshot read(AccessibilityNodeInfo root, boolean readSpeech) {
        return read(root, readSpeech, "");
    }

    static AutoReplyFlow.Snapshot read(AccessibilityNodeInfo root, boolean readSpeech, String reply) {
        return read(root, readSpeech, reply, "");
    }

    static AutoReplyFlow.Snapshot read(AccessibilityNodeInfo root, boolean readSpeech, String reply,
                                       String ownedDraft) {
        AutoReplyFlow.Snapshot screen = new AutoReplyFlow.Snapshot();
        if (!trusted(root)) return screen;
        screen.allowedWindow = true;
        screen.incoming = visible(root, CallStateModel.ID_ANSWER_CONTAINER) && hasIncomingLabel(root);
        screen.connected = visible(root, CallStateModel.ID_CALL_TIME);
        screen.textCall = visible(root, CallStateModel.ID_TEXT_MESSAGE_EDIT);
        screen.ended = visible(root, CallStateModel.ID_CALL_ENDED);
        screen.assistReady = buttonTarget(root, AutoReplyFlow.Action.OPEN_ASSIST) != null;
        screen.textOptionReady = buttonTarget(root, AutoReplyFlow.Action.OPEN_TEXT) != null;
        AccessibilityNodeInfo input = unique(root, CallStateModel.ID_TEXT_MESSAGE_EDIT);
        screen.inputReady = input != null && input.isEditable()
                && supports(input, AccessibilityNodeInfo.ACTION_SET_TEXT);
        if (input != null) {
            screen.inputFocused = input.isFocused();
            screen.inputEmpty = emptyInput(input);
            screen.inputMatchesReply = !screen.inputEmpty && !reply.isEmpty() && reply.contentEquals(input.getText());
            screen.inputMatchesOwnedDraft = !screen.inputEmpty && !ownedDraft.isEmpty()
                    && ownedDraft.contentEquals(input.getText());
        }
        AccessibilityNodeInfo submit = buttonTarget(root, AutoReplyFlow.Action.SEND_REPLY);
        screen.submitReady = submit != null;
        if (submit != null) screen.submitBounds = bounds(submit).flattenToString();
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(REMOTE_ID)) {
            if (node == null || !node.isVisibleToUser()) continue;
            screen.remoteCount++;
            if (readSpeech) {
                String text = text(node, 0).trim();
                screen.remoteMessages.add(text);
                screen.remoteIdentities.add(messageIdentity(node));
                if (!text.isEmpty()) screen.remoteText = text;
            }
        }
        if (readSpeech) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(LOCAL_ID)) {
                if (node != null && node.isVisibleToUser()) {
                    String local = text(node, 0).trim();
                    if (screen.initialGreeting.isEmpty() && !local.isEmpty()) screen.initialGreeting = local;
                    if (!reply.isEmpty() && reply.equals(local)) screen.replyCount++;
                }
            }
        }
        // Samsung retains answer_fragment_container after answering; classify the whole screen.
        screen.incoming = screen.isRinging();
        return screen;
    }

    private static String messageIdentity(AccessibilityNodeInfo node) {
        // Collection positions distinguish messages without using their changing transcript.
        for (int depth = 0; node != null && depth <= 12; depth++, node = node.getParent()) {
            if (!CallPackagePolicy.isAllowedPackage(node.getPackageName())) return "";
            AccessibilityNodeInfo.CollectionItemInfo item = node.getCollectionItemInfo();
            if (item != null && item.getRowIndex() >= 0 && item.getColumnIndex() >= 0) {
                return "row:" + item.getRowIndex() + ":column:" + item.getColumnIndex();
            }
            if (node.getCollectionInfo() != null) return "";
        }
        return "";
    }

    private static String text(AccessibilityNodeInfo node, int depth) {
        if (depth > 12 || !node.isVisibleToUser()) return "";
        CharSequence own = node.getText();
        if (own != null && own.length() > 0) {
            return own.subSequence(0, Math.min(own.length(), MAX_TEXT)).toString();
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < node.getChildCount() && result.length() < MAX_TEXT; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String value = text(child, depth + 1);
                if (!value.isEmpty()) result.append(value).append(' ');
            }
        }
        return result.substring(0, Math.min(result.length(), MAX_TEXT));
    }
}
