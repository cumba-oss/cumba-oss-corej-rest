package net.cumba.corej.rest.session;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The single definition of what a staged file name may be: a bare, single path element.
 *
 * <p>
 * F-rest-04: this rule used to exist twice — in {@code SessionRegistry.validateFilename}, which
 * every uploaded name passes through, and in {@code StudyValidationCheckRunner.requireSessionFile},
 * which validates the names a check-run request references. The two had drifted: the run-side copy
 * checked only blank and the two path separators, and its safety against {@code ..} and a NUL byte
 * was a property of the <em>registry's</em> behaviour (an exact-match lookup over already-validated
 * names) rather than of its own code. One definition, two callers, so a change cannot land on only
 * one of them.
 * </p>
 */
public final class SessionFilenames
{

    private SessionFilenames()
    {
    }


    /**
     * Checks a name against the bare-file-name rule.
     *
     * @param filename
     *            the candidate name
     * @return {@code null} when the name is acceptable, otherwise the reason it is not, phrased to
     *         complete the sentence "&lt;name&gt; …"
     */
    public static @Nullable String violation(@Nullable String filename)
    {
        if (filename == null || filename.isBlank())
        {
            return "must not be blank";
        }
        if (filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0)
        {
            return "must not contain a path separator";
        }
        if (filename.indexOf('\0') >= 0)
        {
            return "must not contain a NUL character";
        }
        if (".".equals(filename) || "..".equals(filename))
        {
            return "must not be a relative path segment";
        }
        // Belt-and-suspenders: the name must resolve to a single path element.
        Path asPath = Path.of(filename);
        Path fileName = asPath.getFileName();
        if (asPath.getNameCount() != 1 || fileName == null || !fileName.toString().equals(filename))
        {
            return "must be a bare file name";
        }
        return null;
    }
}
