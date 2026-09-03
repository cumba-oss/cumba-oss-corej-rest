package net.cumba.corej.rest.session;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Persistent metadata for one upload session, written as a sibling file
 * {@code <baseDir>/<id>.session.json} next to the session's {@code <id>/} staging directory (kept
 * out of the staging directory itself, which is handed to the engine as a data library). Lets the
 * registry rebuild a session — its id, optional name, creation time, and each file's
 * size/hash/upload time — after a restart without re-hashing the (possibly large) staged files.
 *
 * <p>
 * {@code name} is {@code null} for an unnamed session; manifests written before the naming feature
 * (no {@code name} field) deserialise to {@code null}, so reload is backward-compatible.
 * </p>
 */
public record SessionManifest(String id, @Nullable String name, String createdAt,
        List<FileRecord> files)
{

    public SessionManifest
    {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** One staged file's persisted metadata. */
    public record FileRecord(String filename, long size, String uploadedAt, @Nullable String sha256)
    {
    }
}
