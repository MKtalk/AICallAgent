package com.aicallagent.app.local;

/** Main-thread gate: call generation alone cannot distinguish two requests in the same call. */
public final class CallReplyGate {
    public static final class Ticket {
        public final long generation, deadline;
        private Ticket(long generation, long now) { this.generation = generation; deadline = now + 30_000; }
    }
    private Ticket current;
    public Ticket start(long generation, long now) { return current = new Ticket(generation, now); }
    public boolean accepts(Ticket ticket, long generation, long now) {
        return ticket != null && ticket == current && ticket.generation == generation && now < ticket.deadline;
    }
    public void cancel() { current = null; }
}
