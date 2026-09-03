package net.cumba.corej.rest.run;

/** Lifecycle state of a check run: {@code PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED}. */
public enum RunStatus
{

    /** Queued, waiting for a worker thread to free up. */
    PENDING,

    /** Picked up by a worker and executing. */
    RUNNING,

    /** Completed normally; findings are available. */
    SUCCEEDED,

    /** Aborted by an error. */
    FAILED,

    /** Aborted in response to a cancel request. */
    CANCELLED;

    /** Whether this is a non-terminal state (counts against the session-delete guard). */
    public boolean isInFlight()
    {
        return this == PENDING || this == RUNNING;
    }


    /** Whether this is a terminal state (no further transitions). */
    public boolean isTerminal()
    {
        return !isInFlight();
    }
}
