package com.aicallagent.app.policy;

import java.util.Locale;

/** Only a known transient phone notice is evidence that this send was explicitly rejected. */
public final class SpeechBusyNotice {
    private SpeechBusyNotice() { }

    public static boolean isTransientNotice(boolean notificationEvent, boolean windowStateEvent,
                                            CharSequence className, boolean hasNotificationData) {
        return !hasNotificationData && (notificationEvent || windowStateEvent
                && "android.widget.Toast".contentEquals(className == null ? "" : className));
    }

    public static boolean matches(CharSequence packageName, boolean toastNotification, CharSequence text) {
        if (!CallPackagePolicy.isAllowedPackage(packageName) || !toastNotification || text == null) return false;
        return matchesText(text);
    }

    public static boolean matchesText(CharSequence text) {
        if (text == null || text.length() > 256) return false;
        String value = text.toString().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        value = value.replaceFirst("^wait for the current response", "wait for current response");
        return value.equals("wait for current response to be said before sending a new one")
                || value.equals("wait for current response to be said before seding aw new one")
                || value.equals("현재 응답이 다 끝난 후에 새로운 응답을 보낼 수 있어요");
    }
}
