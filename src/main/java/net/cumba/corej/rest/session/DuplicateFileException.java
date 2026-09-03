package net.cumba.corej.rest.session;

/** Thrown when a file with the same name already exists in the session. Maps to HTTP 409. */
public final class DuplicateFileException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public DuplicateFileException(String sessionId, String filename)
    {
        super("File already present in session " + sessionId + ": " + filename);
    }
}
