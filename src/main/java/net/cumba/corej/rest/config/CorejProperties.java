package net.cumba.corej.rest.config;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound configuration under the {@code corej.*} prefix. Centralises the service-level knobs so the
 * registries and the (later) run queue read a single, validated source.
 */
@ConfigurationProperties("corej")
public class CorejProperties
{

    private final Sessions sessions = new Sessions();

    private final Runs runs = new Runs();

    private final Reports reports = new Reports();

    private final Persistence persistence = new Persistence();

    private final Engine engine = new Engine();

    private final CacheSeed cacheSeed = new CacheSeed();

    public Sessions getSessions()
    {
        return sessions;
    }


    public Runs getRuns()
    {
        return runs;
    }


    public Reports getReports()
    {
        return reports;
    }


    public Persistence getPersistence()
    {
        return persistence;
    }


    public Engine getEngine()
    {
        return engine;
    }


    public CacheSeed getCacheSeed()
    {
        return cacheSeed;
    }

    /** Validation-engine defaults applied to runs unless the request overrides them. */
    public static class Engine
    {

        /**
         * Service-wide default for the per-rule findings cap: the maximum number of violations a
         * rule materialises per dataset (additional ones are counted but not listed). {@code null}
         * (the default) falls back to {@code corej.maxErrorsPerRule} / {@code MAX_ERRORS_PER_RULE}
         * (default 1000); {@code <= 0} means unlimited. A check request's own
         * {@code maxErrorsPerRule}, when set, overrides this per run.
         */
        private @Nullable Integer maxErrorsPerRule;

        public @Nullable Integer getMaxErrorsPerRule()
        {
            return maxErrorsPerRule;
        }


        public void setMaxErrorsPerRule(@Nullable Integer maxErrorsPerRule)
        {
            this.maxErrorsPerRule = maxErrorsPerRule;
        }
    }


    /** Cross-restart rehydration of sessions and runs from their on-disk manifests. */
    public static class Persistence
    {

        /**
         * Whether to rebuild sessions and runs from their on-disk manifests at startup. Only takes
         * effect when {@code corej.sessions.dir} / {@code corej.reports.dir} are configured (a temp
         * dir is always fresh). Set to {@code false} to start with an empty index without deleting
         * the data directory.
         */
        private boolean rehydrateOnStartup = true;

        public boolean isRehydrateOnStartup()
        {
            return rehydrateOnStartup;
        }


        public void setRehydrateOnStartup(boolean rehydrateOnStartup)
        {
            this.rehydrateOnStartup = rehydrateOnStartup;
        }
    }


    /** Session/file-staging configuration. */
    public static class Sessions
    {

        /**
         * Base directory under which per-session upload directories are created. When blank, a
         * fresh OS temp directory is allocated at startup (and removed on shutdown).
         */
        private @Nullable String dir;

        /**
         * Maximum number of bytes accepted when ingesting a file from a URL. The download is
         * aborted (and the partial file removed) once this is exceeded. Default 500 MiB.
         */
        private long maxDownloadBytes = 500L * 1024 * 1024;

        /** Connect/read timeout for a URL ingest download. Default 60s. */
        private Duration downloadTimeout = Duration.ofSeconds(60);

        public @Nullable String getDir()
        {
            return dir;
        }


        public void setDir(@Nullable String dir)
        {
            this.dir = dir;
        }


        public long getMaxDownloadBytes()
        {
            return maxDownloadBytes;
        }


        public void setMaxDownloadBytes(long maxDownloadBytes)
        {
            this.maxDownloadBytes = maxDownloadBytes;
        }


        public Duration getDownloadTimeout()
        {
            return downloadTimeout;
        }


        public void setDownloadTimeout(Duration downloadTimeout)
        {
            this.downloadTimeout = downloadTimeout;
        }
    }


    /** Run-queue configuration (consumed by the run executor in a later phase). */
    public static class Runs
    {

        /**
         * Global cap on concurrently-executing check runs across all sessions. Runs beyond this sit
         * queued (status {@code PENDING}) until a worker frees up.
         */
        private int maxParallel = 2;

        public int getMaxParallel()
        {
            return maxParallel;
        }


        public void setMaxParallel(int maxParallel)
        {
            this.maxParallel = maxParallel;
        }
    }


    /** Report persistence + cache configuration. */
    public static class Reports
    {

        /**
         * Base directory for per-run report JSON files (the source of truth). When blank, a fresh
         * OS temp directory is allocated at startup (and removed on shutdown).
         */
        private @Nullable String dir;

        private final Cache cache = new Cache();

        public @Nullable String getDir()
        {
            return dir;
        }


        public void setDir(@Nullable String dir)
        {
            this.dir = dir;
        }


        public Cache getCache()
        {
            return cache;
        }

        /** In-memory report cache over the on-disk JSON. */
        public static class Cache
        {

            /** Time after which a cached run's report is dropped and reloaded on demand. */
            private Duration ttl = Duration.ofMinutes(30);

            /** Maximum number of runs whose report is cached (LRU eviction beyond this). */
            private int maxEntries = 64;

            public Duration getTtl()
            {
                return ttl;
            }


            public void setTtl(Duration ttl)
            {
                this.ttl = ttl;
            }


            public int getMaxEntries()
            {
                return maxEntries;
            }


            public void setMaxEntries(int maxEntries)
            {
                this.maxEntries = maxEntries;
            }
        }
    }


    /**
     * Startup seeding of the unified CDISC metadata store — from the Python engine's pickle
     * metadata for deployments without an API key, or from the live CDISC Library API with one
     * ({@code from-api}).
     *
     * <p>
     * Opt-in and initialization-only: there is deliberately no HTTP endpoint, because seeding
     * performs network I/O, writes to a server-side file and takes minutes. Everything it uses is
     * server-configured; nothing is caller-supplied.
     * </p>
     */
    public static class CacheSeed
    {

        /** Whether to seed at startup. Off by default. */
        private boolean enabled;

        /** Repository to fetch the pickles from; blank uses the public upstream. */
        private @Nullable String repoUri;

        /** Branch, tag or commit; blank discovers the repository's default branch. */
        private @Nullable String ref;

        /** Path inside the repository holding the {@code *.pkl} files; blank uses the default. */
        private @Nullable String repoPath;

        /** Archive URL template with {@code {repo}} / {@code {ref}}; blank uses the default. */
        private @Nullable String archiveUrlTemplate;

        /** Seed from this local pickle directory instead of downloading. */
        private @Nullable String fromDir;

        /**
         * Seed from the live CDISC Library API instead of pickles — needs an API key
         * ({@code CDISC_API_KEY} / {@code cdisc.library.api.key}). Mutually exclusive with
         * {@code from-dir}.
         */
        private boolean fromApi;

        /**
         * Target store file; blank uses {@code CDISC_METADATA_STORE} / {@code
         * cdisc.metadata.store}, then the application default
         * ({@code ~/.cumbaDataBrowser/metadata-cache.zip}).
         *
         * <p>
         * ⭐ This is also <b>the store every validation run reads</b>
         * ({@code StudyValidationCheckRunner.configuredRunStore}), and it outranks an ambient
         * {@code CDISC_METADATA_STORE} — seeding one file and validating another was review finding
         * F2. It applies whether or not {@code enabled} is set: naming a store file is meaningful
         * for a deployment that provisions the store out of band and only points at it.
         * </p>
         */
        private @Nullable String targetStore;

        /**
         * Re-acquire everything, ignoring what the existing store already holds (plan §5.2's
         * {@code --refresh}); without it, content the store holds is carried forward and a store
         * that already exists is not re-seeded at all.
         */
        private boolean refresh;

        public boolean isEnabled()
        {
            return enabled;
        }


        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }


        public @Nullable String getRepoUri()
        {
            return repoUri;
        }


        public void setRepoUri(@Nullable String repoUri)
        {
            this.repoUri = repoUri;
        }


        public @Nullable String getRef()
        {
            return ref;
        }


        public void setRef(@Nullable String ref)
        {
            this.ref = ref;
        }


        public @Nullable String getRepoPath()
        {
            return repoPath;
        }


        public void setRepoPath(@Nullable String repoPath)
        {
            this.repoPath = repoPath;
        }


        public @Nullable String getArchiveUrlTemplate()
        {
            return archiveUrlTemplate;
        }


        public void setArchiveUrlTemplate(@Nullable String archiveUrlTemplate)
        {
            this.archiveUrlTemplate = archiveUrlTemplate;
        }


        public @Nullable String getFromDir()
        {
            return fromDir;
        }


        public void setFromDir(@Nullable String fromDir)
        {
            this.fromDir = fromDir;
        }


        public boolean isFromApi()
        {
            return fromApi;
        }


        public void setFromApi(boolean fromApi)
        {
            this.fromApi = fromApi;
        }


        public @Nullable String getTargetStore()
        {
            return targetStore;
        }


        public void setTargetStore(@Nullable String targetStore)
        {
            this.targetStore = targetStore;
        }


        public boolean isRefresh()
        {
            return refresh;
        }


        public void setRefresh(boolean refresh)
        {
            this.refresh = refresh;
        }
    }
}
