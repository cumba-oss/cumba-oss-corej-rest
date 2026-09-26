package net.cumba.corej.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * The negative control of {@link HomeStaysCleanExtension} (review round 2): a test that writes into
 * the watched home MUST red, and the guard must name what changed. Run against a temp directory
 * standing in for the real home (the {@code realUserHome} property is repointed for the duration),
 * so the control itself never touches {@code ~/.cumbaDataBrowser}.
 */
class HomeStaysCleanExtensionSelfTest
{

    @Test
    void aWriteIntoTheWatchedHomeIsRedAndNamed(@TempDir Path aFakeHome) throws IOException
    {
        String saved = System.getProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY);
        System.setProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY, aFakeHome.toString());
        try
        {
            HomeStaysCleanExtension guard = new HomeStaysCleanExtension();
            guard.beforeEach(null);
            Path leak = Files.createDirectories(
                    aFakeHome.resolve(".cumbaDataBrowser").resolve("dictionaries"));
            AssertionFailedError red = assertThrows(AssertionFailedError.class,
                    () -> guard.afterEach(null), "a created directory must red the guard");
            assertTrue(red.getMessage().contains("created " + leak), red.getMessage());
            assertTrue(red.getMessage().contains(aFakeHome.toString()), red.getMessage());

            // A changed file reds too, and a clean interval passes.
            Path file = Files.writeString(leak.resolve("x.txt"), "1");
            guard.beforeEach(null);
            guard.afterEach(null);
            Files.writeString(file, "22");
            AssertionFailedError changed = assertThrows(AssertionFailedError.class,
                    () -> guard.afterEach(null));
            assertTrue(changed.getMessage().contains("changed " + file), changed.getMessage());
        }
        finally
        {
            if (saved == null)
            {
                System.clearProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY);
            }
            else
            {
                System.setProperty(HomeStaysCleanExtension.REAL_HOME_PROPERTY, saved);
            }
        }
    }


    @Test
    void theRealHomeIsTheMavenPassedOneNotTheRedirect()
    {
        Path real = HomeStaysCleanExtension.realHome();
        Path redirected = Path.of(System.getProperty("user.home")).toAbsolutePath();
        assertTrue(!real.equals(redirected),
                "-DrealUserHome must be the undirected home, but equals user.home: " + real);
        assertEquals("test-home", redirected.getFileName().toString());
    }
}
