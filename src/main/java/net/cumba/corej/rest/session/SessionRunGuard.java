package net.cumba.corej.rest.session;

/**
 * Seam letting the session layer veto a session deletion while that session still has check runs in
 * flight. Phase 2 ships no implementation (deletion is always permitted); the run registry
 * introduced in Phase 3 supplies one as a Spring bean and the {@link SessionRegistry} picks it up
 * automatically.
 */
@FunctionalInterface
public interface SessionRunGuard
{

    /**
     * Tells whether the given session still has runs in flight.
     *
     * @return {@code true} if the session has any {@code PENDING}/{@code RUNNING} run.
     */
    boolean hasInFlightRuns(String sessionId);
}
