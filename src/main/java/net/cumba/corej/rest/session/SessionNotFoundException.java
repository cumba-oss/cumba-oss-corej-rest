package net.cumba.corej.rest.session;

/** Thrown when a session id does not resolve to a live session. Maps to HTTP 404. */
public final class SessionNotFoundException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public SessionNotFoundException(String sessionId)
    {
        super("No such session: " + sessionId);
    }
}
