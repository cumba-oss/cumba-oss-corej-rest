#!/bin/sh
# coreJ REST container entrypoint.
#
# "Custom" data — the rule corpora, the dictionary store and the unified CDISC
# metadata store — lives on the persistent volume mounted at /app/data, so it
# survives image rebuilds. Every block below guarantees its directory EXISTS,
# mirrors the bundle's own (empty) copy into it so the "put the corpus here"
# READMEs land where the operator will look, says once which stores are empty or
# absent, and then launches the service.
#
# ⚠⚠ The metadata store is the exception: it is a single zip FILE, only its PARENT
# DIRECTORY is created, and an ABSENT store is the good degradation. See its block
# below.
set -eu

# ------------------------------------------------------------------
# The rule corpora.
#
# ⚠⚠ THIS IMAGE SHIPS NONE. The corpora are released on their own cadence by
# cumba-oss-corej-rules and are not Maven dependencies, so there is nothing in this
# repository for the build to bake. The bundle's own rules/ and rules-define/ ship
# EMPTY, each with a README naming the release asset that belongs there.
#
# ⚠⚠ Empty and missing are NOT the same to the engine: a configured-but-missing
# Define-XML corpus throws on every conformance run, and a missing dictionary store
# throws when a run sets up. Only rules/ treats missing and empty alike. So each
# block falls back to the bundle's own empty copy rather than leaving a dangling
# path.
# ------------------------------------------------------------------
RULES_DIR="${COREJ_RULES_DIR:-/app/data/rules}"
if mkdir -p "$RULES_DIR" 2>/dev/null \
    && cp -a /app/dist/rules/. "$RULES_DIR"/ 2>/dev/null; then :; else
    echo "warning: could not prepare the rule corpus directory $RULES_DIR;" \
        "using the bundle's own (empty) /app/dist/rules" >&2
    RULES_DIR=/app/dist/rules
fi
export COREJ_RULES_DIR="$RULES_DIR"

DEFINE_RULES_DIR="${COREJ_DEFINE_RULES_DIR:-/app/data/rules-define}"
if mkdir -p "$DEFINE_RULES_DIR" 2>/dev/null \
    && cp -a /app/dist/rules-define/. "$DEFINE_RULES_DIR"/ 2>/dev/null; then
    export COREJ_DEFINE_RULES_DIR="$DEFINE_RULES_DIR"
else
    echo "warning: could not prepare the Define-XML corpus directory" \
        "$DEFINE_RULES_DIR; conformance runs will use the bundle's own (empty)" \
        "/app/dist/rules-define" >&2
    export COREJ_DEFINE_RULES_DIR=/app/dist/rules-define
fi

# ------------------------------------------------------------------
# The unified CDISC metadata store.
#
# A single zip FILE, read by the engine through CDISC_METADATA_STORE (env, the tier
# operators set) or cdisc.metadata.store (sysprop) — see
# StoreMetadataProviderFactory.resolveConfiguredFile. Left unset entirely the engine
# falls back to ~/.cumbaDataBrowser/metadata-cache.zip, and this image's runtime
# user is created with `useradd --home-dir /app`, so that resolves to
# /app/.cumbaDataBrowser/metadata-cache.zip — an IMAGE-LAYER path outside the
# /app/data bind mount. The store would then be discarded with every container
# replacement and cost a full re-seed. So default it into the mount and export it,
# which also covers a bare `docker run` that sets no environment.
#
# ⚠⚠ Create the store's PARENT DIRECTORY only — never the file. An empty file is a
# regular file, so resolveConfiguredFile ACCEPTS it and MetadataStore.open then
# fails on a malformed archive. That is strictly worse than an absent store, which
# degrades cleanly to "no store configured" and the loud per-rule SKIP.
#
# ⛔ Seeding is deliberately NOT done here. The service already has it, opt-in via
# corej.cache-seed.* (COREJ_CACHESEED_ENABLED=true), and since the F2 fix its target
# IS the store every run reads. A second seeding path in shell would re-open exactly
# the seed-B-validate-A split that fix closed.
# ------------------------------------------------------------------
STORE="${CDISC_METADATA_STORE:-/app/data/metadata-cache.zip}"
export CDISC_METADATA_STORE="$STORE"
if mkdir -p "$(dirname "$STORE")" 2>/dev/null; then :; else
    echo "warning: cannot create $(dirname "$STORE") for the CDISC metadata store;" \
        "seeding will fail there and every library-dependent rule will SKIP (loudly," \
        "by name)" >&2
fi

# Migration notice. Deployments predating the unified store configured the retired
# CDISC Library web-API cache (CDISC_API_CACHE, /app/data/api-cache); nothing reads
# it any more. Such a deployment keeps starting and keeps validating — it just skips
# every library-dependent rule, SILENTLY, because the operator believes the cache
# they populated is still doing its job. Say it once, naming the seed route.
# ⚠ CDISC_API_CACHE is read HERE ONLY to locate that legacy directory, so a stack
# that overrode it is detected too. It is never created, and nothing downstream
# reads it any more — the service has no web-API cache.
LEGACY_CACHE_DIR="${CDISC_API_CACHE:-/app/data/api-cache}"
if [ -d "$LEGACY_CACHE_DIR" ] && [ ! -f "$STORE" ]; then
    echo "warning: $LEGACY_CACHE_DIR is a retired CDISC Library web-API cache and is no" \
        "longer read by anything, and the unified metadata store $STORE does not exist" \
        "— every library-dependent rule will SKIP (loudly, by name). Seed the store once" \
        "by starting with COREJ_CACHESEED_ENABLED=true (add COREJ_CACHESEED_FROMAPI=true" \
        "plus CDISC_API_KEY to seed from the live CDISC Library instead of the published" \
        "pickle metadata), or provision $STORE out of band. Then delete" \
        "$LEGACY_CACHE_DIR." >&2
fi

# ------------------------------------------------------------------
# External dictionaries.
#
# coreJ ships NO dictionary data: the store starts empty on the persistent volume
# and is best-effort filled here — every step guarded (`set -eu` is active), warn
# on failure, NEVER abort the container. A failed install degrades to the engine's
# loud per-rule SKIP.
#
# ⚠⚠ NO INSTALLER IS BAKED INTO THIS IMAGE. The installer is the CLI, which is a
# separate repository, so the auto-convert and auto-download below degrade to a
# warning and dictionaries are installed out of band — run the cumba-oss-corej-cli
# image against the same volume, or unpack a converted store into
# COREJ_DICTIONARIES_DIR. The branch is kept rather than deleted: it costs
# nothing, it is what makes the absence self-describing, and it works again
# unchanged if a bundle is ever added.
#
# - AUTO-CONVERT (on by default): a licensed vendor distribution mounted at the
#   conventional path /licensed-dictionaries/<type> (types: meddra whodrug loinc
#   medrt unii snomed neoplasm) is converted into the store on start. Local files
#   only, no network, idempotent via --skip-installed.
# - AUTO-DOWNLOAD (OPT-IN, COREJ_DICTIONARY_AUTO_INSTALL=1, default off):
#   downloads and installs the credential-free trio (MED-RT, UNII, neoplasm).
#   Off by default because validation must be reproducible — same data + same
#   rules + same dictionary version must give the same findings, and a container
#   fetching "latest" on boot would report different findings from identical
#   inputs a month later. These deployments are also frequently air-gapped or
#   egress-restricted, and a large fetch plus conversion would delay first
#   request on every fresh volume.
# ------------------------------------------------------------------
DICT_DIR="${COREJ_DICTIONARIES_DIR:-/app/data/dictionaries}"
if mkdir -p "$DICT_DIR" 2>/dev/null; then
    export COREJ_DICTIONARIES_DIR="$DICT_DIR"
    if [ -x /app/cli/run.sh ]; then
        for type in meddra whodrug loinc medrt unii snomed neoplasm; do
            src="/licensed-dictionaries/$type"
            [ -d "$src" ] || continue
            if /app/cli/run.sh --install-dictionaries --skip-installed \
                --dictionaries-dir "$DICT_DIR" "--$type" "$src" >&2; then :; else
                echo "warning: auto-convert of $type from $src failed; its rules" \
                    "will SKIP" >&2
            fi
        done
        if [ "${COREJ_DICTIONARY_AUTO_INSTALL:-0}" = "1" ]; then
            if /app/cli/run.sh --install-dictionaries --skip-installed \
                --dictionaries-dir "$DICT_DIR" >&2; then :; else
                echo "warning: dictionary auto-download failed; the affected rules" \
                    "will SKIP" >&2
            fi
        fi
    else
        echo "warning: no dictionary installer at /app/cli/run.sh in this image;" \
            "auto-convert/auto-download are unavailable — install into" \
            "$DICT_DIR with the cumba-oss-corej-cli bundle instead" >&2
    fi
else
    # A configured-but-missing store is a deliberate hard error in the engine;
    # fall back to the bundle's own (empty) store so an image-supplied default
    # cannot trip it. Dictionary rules then SKIP, loudly and by name.
    echo "warning: cannot create the dictionary store at $DICT_DIR; using the" \
        "bundle's own read-only /app/dist/dictionaries — dictionary rules will SKIP" >&2
    export COREJ_DICTIONARIES_DIR=/app/dist/dictionaries
fi

# When no dictionary resolves, say so once at startup, naming the exact command —
# the report's Dictionary_Basis line and the per-rule SKIP reasons repeat it per
# run.
if ! find "$COREJ_DICTIONARIES_DIR" \( -name '*.json' -o -name '*.json.gz' \) \
    ! -name selected-versions.json -type f 2>/dev/null | grep -q .; then
    echo "notice: no external dictionaries are installed — all dictionary" \
        "conformance rules will SKIP (loudly, by name). Install the" \
        "credential-free set with:" >&2
    echo "    docker run --rm -v <this stack's corej-data>:/data cumba-oss-corej-cli:local" \
        "--install-dictionaries --dictionaries-dir /data/dictionaries" >&2
    echo "(this image bakes no installer — use the cumba-oss-corej-cli image against the" \
        "same volume, or mount a licensed distribution at /licensed-dictionaries/<type>" \
        "to have it converted on start once an installer is present.)" >&2
fi

# The rule corpora, same surface. A service that starts without them accepts every
# request and validates nothing, reporting only that it found no packages — per
# run, buried in a report. Say it once at startup, where `docker compose logs`
# shows it. packages.json is the manifest every generated corpus has.
if [ ! -f "$COREJ_RULES_DIR/packages.json" ]; then
    echo "notice: no rule corpus in $COREJ_RULES_DIR (no packages.json) — this image" \
        "ships none. Take the matching cumba-oss-corej-rules release asset and unpack" \
        "its CONTENTS there, so the directory holds packages.json and rules-*.json" \
        "directly. Under the compose stack that is ./corej-data/rules on the host." >&2
fi
if [ ! -f "$COREJ_DEFINE_RULES_DIR/packages.json" ]; then
    echo "notice: no Define-XML corpus in $COREJ_DEFINE_RULES_DIR (no packages.json)" \
        "— Define-XML conformance will find no rules. It is a SEPARATE release asset" \
        "from the data corpus above; under the compose stack unpack its contents into" \
        "./corej-data/rules-define on the host." >&2
fi

# The metadata store, same surface. ⚠ Suppressed when the migration notice above
# already fired, which says the same thing and more.
if [ ! -f "$STORE" ] && [ ! -d "$LEGACY_CACHE_DIR" ]; then
    echo "notice: no CDISC metadata store at $STORE — every library-dependent rule" \
        "will SKIP (loudly, by name). Seed it once by starting with" \
        "COREJ_CACHESEED_ENABLED=true (corej.cache-seed.enabled; add" \
        "COREJ_CACHESEED_FROMAPI=true plus CDISC_API_KEY to seed from the live CDISC" \
        "Library instead of the published pickle metadata), or provision the file out" \
        "of band and point CDISC_METADATA_STORE at it." >&2
fi

# JAVA_TOOL_OPTIONS (if set) is applied automatically by the JVM.
# run.sh execs the JVM, so java replaces this shell and stays PID 1.
exec /app/dist/run.sh "$@"
