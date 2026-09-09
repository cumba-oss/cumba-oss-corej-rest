# syntax=docker/dockerfile:1
#
# Build + run the coreJ CDISC validation service as a container. `docker compose
# up -d --build` builds it from source.
#
#   docker build -t cumba-oss-corej-rest:local .
#   docker run --rm -p 8080:8080 -v "$PWD/corej-data":/app/data cumba-oss-corej-rest:local
#
# ⚠⚠ THIS IMAGE IS API-ONLY. The React SPA is a separate project released as its
# own archive, and the `bundle-web` profile needs a pre-built SPA handed to it via
# -Dweb.dist.dir — there is nothing in this repository for a container build to
# inline. So the jar has no static/ and the container serves /api and
# /swagger-ui.html only. Host the SPA separately and give it a CORS
# WebMvcConfigurer.
#
# ⚠⚠ The build context is THIS REPOSITORY — `docker build .` from here, and
# docker-compose.yml sets `context: .`. The repository is flat and single-module,
# so it builds itself, and the .dockerignore beside this file governs the context.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre

# ---------- build stage ----------
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

# The whole repository. .dockerignore keeps target/, .git/ and the compose bind
# mount out, so this stays small and cache-friendly.
COPY . .

# One `package` produces the runnable bundle: this project is the application AND
# its own dist assembly (src/assembly/dist.xml, bound to the package phase), so
# there is no separate dist module and no `-pl`/`-am` reactor selection. The
# assembly ships a zip whose single top-level directory is the bundle; extract it
# with the JDK `jar` tool (present in this JDK image, absent from the JRE runtime
# image) into a fixed /build/out that the runtime stage can COPY. BuildKit's cache
# mount keeps ~/.m2 warm across rebuilds.
#
# ⚠ No `-P main` here. This repository's reactor is deliberately profile-less —
# CI strictness lives in the workflow's flags, not in a profile — so naming a
# profile that does not exist would fail the build.
#
# Tests are skipped: CI gates them, and an image build is not the place to
# discover a red suite.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests package \
 && mkdir -p /build/extract /build/out \
 && ( cd /build/extract && jar xf "$(ls /build/target/cumba-oss-corej-rest-*.zip)" ) \
 && cp -a /build/extract/cumba-oss-corej-rest-*/. /build/out/

# ---------- runtime stage ----------
FROM ${RUNTIME_IMAGE} AS runtime

# curl: used by the compose healthcheck against /api/info.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Non-root runtime user, pinned to uid/gid 1000. The compose stack bind-mounts a
# host directory (./corej-data) at /app/data; a bind mount keeps the host's
# ownership (unlike a named volume, which inherits the image's), so the in-
# container user must share the uid that owns the host dir. 1000 is the typical
# first host user, so `chown -R 1000:1000 corej-data` on the host makes it
# writable. Ubuntu-based bases (eclipse-temurin is Ubuntu) ship a default
# uid/gid-1000 user ('ubuntu'); free up 1000 first so the pin can't collide.
RUN set -eu; \
    existing_user="$(getent passwd 1000 | cut -d: -f1 || true)"; \
    [ -z "$existing_user" ] || userdel -r "$existing_user" 2>/dev/null || userdel "$existing_user" 2>/dev/null || true; \
    existing_group="$(getent group 1000 | cut -d: -f1 || true)"; \
    [ -z "$existing_group" ] || groupdel "$existing_group" 2>/dev/null || true; \
    groupadd --gid 1000 corej; \
    useradd --uid 1000 --gid 1000 --home-dir /app --shell /usr/sbin/nologin corej

WORKDIR /app

# The exploded dist bundle: cumba-oss-corej-rest.jar (the cumba-oss-bootstrap
# launcher) and its sidecar .conf, flat lib/, run.sh, config/ (the external
# application.yaml the .conf points spring.config.additional-location at), and the
# three EMPTY store directories the bundle ships (rules/ rules-define/
# dictionaries/, each holding a README that says what belongs there). Those
# empties are load-bearing — see below.
COPY --from=build /build/out /app/dist

COPY docker-entrypoint.sh /app/docker-entrypoint.sh

# ⚠⚠ NO DICTIONARY INSTALLER is baked into this image. The installer is the CLI,
# which is a separate repository, so the entrypoint's auto-convert and opt-in
# auto-download degrade to a warning and dictionaries are installed out of band —
# run the cumba-oss-corej-cli image against the same volume, or unpack a converted
# store into COREJ_DICTIONARIES_DIR.

# Writable area mounted at /app/data: per-session uploads and per-run report/log
# JSON (COREJ_SESSIONS_DIR / COREJ_REPORTS_DIR) plus everything that must survive
# a rebuild — the rule corpora, the dictionary store and the CDISC Library API
# cache. The compose stack bind-mounts ./corej-data here, which keeps the host's
# ownership, so the host dir must be owned by uid 1000 (this image's runtime
# user). These subdirs are created here so they exist in the image layer; the
# entrypoint re-creates them at startup against whatever is mounted.
#
# ⚠⚠ None of the rule directories ships with content. The corpora are released on
# their own cadence by cumba-oss-corej-rules and are not Maven dependencies, so
# this image cannot bake them — there is no module here to copy from. The operator
# supplies them; the entrypoint says so once.
#
# ⚠⚠ "Configured but MISSING" is a hard error for two of the three: the dictionary
# store throws when a validation run sets up, and the Define-XML corpus throws on
# every conformance run. Only rules/ treats missing and empty alike. That is why
# these directories are created here and why the entrypoint falls back to the
# bundle's own empty copies rather than leaving a dangling path. An EMPTY store
# degrades to a loud per-rule SKIP; a MISSING one throws.
RUN mkdir -p /app/data/sessions /app/data/reports /app/data/rules \
       /app/data/rules-define /app/data/dictionaries /app/data/api-cache \
 && chmod +x /app/dist/run.sh /app/docker-entrypoint.sh \
 && chown -R corej:corej /app

# Store locations, all on the persistent /app/data volume. Unlike the CLI image —
# whose /data is the caller's own working directory in the documented ad-hoc form —
# /app/data here is the service's own area, so everything belongs on it.
# Precedence comes from the bundle's .conf: COREJ_* environment > -D system
# property > the bundle's own directory.
ENV COREJ_RULES_DIR=/app/data/rules
ENV COREJ_DEFINE_RULES_DIR=/app/data/rules-define
ENV COREJ_DICTIONARIES_DIR=/app/data/dictionaries

# The CDISC Library web-API cache — a DIRECTORY that corej.cache-seed fills and
# every run reads.
# ⚠⚠ Left unset the client falls back to ~/.cdiscApiCache, and this image's
# runtime user is created with `useradd --home-dir /app`, so that resolves to
# /app/.cdiscApiCache — an IMAGE-LAYER path outside the /app/data bind mount. The
# cache would then be discarded with every container replacement and cost a full
# re-seed.
ENV CDISC_API_CACHE=/app/data/api-cache

USER corej
EXPOSE 8080

# The entrypoint prepares the stores and reports which are empty, then launches the
# JVM. JAVA_TOOL_OPTIONS (set via env) is picked up automatically by the JVM.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
