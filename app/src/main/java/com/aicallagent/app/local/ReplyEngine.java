package com.aicallagent.app.local;

/** Native engine ownership stays with the service until automatic answering is disabled. */
public interface ReplyEngine {
    interface Callback { void complete(String answer, String error); }
    boolean isReady(Object owner);
    boolean generate(Object owner, ReplyRequest request, Callback callback);
    void cancel(Object owner);
    void release(Object owner);
}
