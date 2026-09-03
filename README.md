# Cumba OSS Clients

Runnable clients for the [coreJ](https://github.com/cumba-oss/cumba-oss-corej) CDISC
conformance engine: a validation command-line tool and a Spring Boot REST API.

| Module | What it is |
|---|---|
| [`clients/cumba-oss-corej-rest`](clients/cumba-oss-corej-rest) | REST API over the same engine, with OpenAPI / Swagger UI |

## ⛔ Nothing here is published to Maven Central

These are **applications, not libraries** — there is no coordinate to depend on. Every
module is excluded from Central via `excludeArtifacts` in the root pom (plugin
configuration, deliberately not a property: a property is a Maven *user* property that a
release job's `-D` silently overrides per module). Jars are distributed as GitHub release
assets.

The libraries these clients consume *are* on Central, released separately:
`cumba-oss-commons`, `cumba-oss-formats`, `cumba-oss-datatable` and `cumba-oss-corej`.

## Build

```bash
mvn -B clean install -Drevision=0.2.0-SNAPSHOT
```

The full CI gate, which is what must pass before a tag:

```bash
mvn -B clean install -Drevision=0.2.0-SNAPSHOT \
    -Dmaven.compiler.failOnWarning=true -Dspotless.check=true \
    -Dpmd.failOnViolation=true -Dspotbugs.failOnError=true
```

## Running

The CLI is a plain jar with a `Main-Class`; see its
application configured under the `corej:` key in `application.yaml`, with `COREJ_*`
environment variables for the common settings.

⚠ **Both need a rule corpus at runtime**, pointed at by `--rules-dir` /
`COREJ_RULES_DIR`. The corpus is **not** in this repository and is not a Maven
dependency — it is distributed separately as release content.

## What is deliberately not here

- **Containers.** The Dockerfiles, Compose files and the `start.sh` launcher were wired
  to the monorepo layout (`dist/` bundle modules, the web SPA, the rule corpus in a
  sibling module) and could not build from this repository. They are being reworked into
  their own repository.
- **The web SPA.** The REST module keeps an opt-in `bundle-web` profile that can inline a
  built SPA into the jar's `static/`, but the SPA lives elsewhere — point `web.dist.dir`
  at a built `dist/` to use it.
- **The rule editor.** Not part of the open-source distribution.

## Licence

AGPL-3.0 — see [LICENSE](LICENSE).
