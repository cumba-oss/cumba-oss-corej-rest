package net.cumba.corej.rest.session;

/** Thrown when a named file does not exist in the session. Maps to HTTP 404. */
public final class SessionFileNotFoundException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public SessionFileNotFoundException(String sessionId, String filename)
    {
        super("No such file in session " + sessionId + ": " + filename);
    }
}
