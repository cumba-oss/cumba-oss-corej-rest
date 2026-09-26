package net.cumba.corej.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The negative control behind {@code HomeStaysCleanExtension} (PLAN-define-ct-evaluation review
 * round 1, H1): this module's test JVM resolves {@code user.home} to {@code target/test-home}, so
 * every app-default fallback ({@code ~/.cumbaDataBrowser/…}) lands under {@code target/}, never in
 * the developer's real home. Red on a pom that lost the Surefire {@code <user.home>} property, and
 * — because the pitest {@code jvmArgs} carry the same {@code -Duser.home} — red under
 * {@code -P Pitest} too if that one is lost.
 */
class TestHomeIsSandboxedTest
{

    @Test
    void userHomeIsRedirectedToTargetTestHome()
    {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        assertEquals("test-home", home.getFileName().toString(),
                "user.home must be <module>/target/test-home in a test JVM, but is " + home);
        assertTrue(
                home.getParent() != null
                        && "target".equals(home.getParent().getFileName().toString()),
                "user.home must sit directly under target/, but is " + home);
        assertTrue(!home.equals(HomeStaysCleanExtension.realHome()),
                "user.home must not be the real home " + HomeStaysCleanExtension.realHome());
    }
}
