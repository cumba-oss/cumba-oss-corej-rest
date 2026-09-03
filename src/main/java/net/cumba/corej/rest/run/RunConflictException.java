package net.cumba.corej.rest.run;

/**
 * Thrown when an operation conflicts with a run's current state: requesting findings while the run
 * is still in flight, or deleting a run that is still {@code PENDING}/{@code RUNNING}. Maps to HTTP
 * 409.
 */
public final class RunConflictException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public RunConflictException(String message)
    {
        super(message);
    }
}
