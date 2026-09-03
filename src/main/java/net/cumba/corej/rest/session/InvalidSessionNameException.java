package net.cumba.corej.rest.session;

/**
 * Thrown when a session name is too long or carries control characters. A blank/null name is not an
 * error — it simply leaves the session unnamed. Maps to HTTP 400.
 */
public final class InvalidSessionNameException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public InvalidSessionNameException(String reason)
    {
        super("Invalid session name: " + reason);
    }
}
