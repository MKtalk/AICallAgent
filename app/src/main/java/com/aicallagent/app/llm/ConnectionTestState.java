package com.aicallagent.app.llm;

/** Main-thread state for a single test. Holds no settings, key or response body. */
public final class ConnectionTestState {
    public static final long TIMEOUT_MS = 30_000;
    public enum Status { IDLE, RUNNING, SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }
    private Status status = Status.IDLE;
    private long token, deadline;
    public Status getStatus() { return status; }
    public long start(long now) {
        if (status == Status.RUNNING) return 0;
        status = Status.RUNNING;
        deadline = now + TIMEOUT_MS;
        return ++token;
    }
    public boolean finish(long ticket, long now, boolean success) {
        if (ticket != token || status != Status.RUNNING) return false;
        status = now >= deadline ? Status.TIMED_OUT : success ? Status.SUCCEEDED : Status.FAILED;
        return true;
    }
    public void cancel() { token++; status = Status.CANCELLED; }
}
