package net.cumba.corej.rest.session;

/**
 * Thrown when a session cannot be deleted because one or more of its check runs are still
 * {@code PENDING} or {@code RUNNING}. Maps to HTTP 409.
 */
public final class SessionBusyException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public SessionBusyException(String sessionId)
    {
        super("Session has in-flight runs and cannot be deleted: " + sessionId);
    }
}
