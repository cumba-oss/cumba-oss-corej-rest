# cumba-oss-corej-rest

The **Spring Boot REST API** over the
[coreJ](https://github.com/cumba-oss/cumba-oss-corej) CDISC conformance engine: create a session,
upload study files, start a check run, poll its status, and page through the findings. An OpenAPI
document and Swagger UI are generated from the controllers.

Part of the [cumba-oss](https://github.com/cumba-oss) set. The engine itself lives in
[cumba-oss-corej](https://github.com/cumba-oss/cumba-oss-corej), the rule corpus in
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules), the SPA that talks to
this API in [cumba-oss-corej-web](https://github.com/cumba-oss/cumba-oss-corej-web), and the
command-line client over the same engine in
[cumba-oss-corej-cli](https://github.com/cumba-oss/cumba-oss-corej-cli).

This repository is a **single Maven module** — the root pom *is* the artifact
(`net.cumba:cumba-oss-corej-rest`, `packaging=jar`). There is no `clients/` directory and no
`dist/` module.

## ⛔ Nothing here is published to Maven Central

This is an **application, not a library** — there is no coordinate to depend on. The artifact is
excluded from Central via `excludeArtifacts` in the pom's `deploy-central` profile.

⚠ That exclusion is **plugin configuration, deliberately not a property**: a property is a Maven
*user* property, which a release job's `-D` silently overrides per module. That precedence is
exactly how `net.cumba:coverage:0.0.4` became public and immutable.

The libraries this service consumes *are* on Central, released separately on their own cadence:

| Library | Version pinned here |
|---|---|
| `cumba-oss-commons` | `0.2.1` |
| `cumba-oss-datatable` | `0.2.0` |
| `cumba-oss-corej` | `0.3.0` |

(the `dependency.cumba-oss-*.version` properties in `pom.xml`). The web stack is Spring Boot
`4.1.1` plus springdoc `3.1.0`.

## Releases

Tagging `vX.Y.Z` builds, signs and attaches two files to the release, on both the GitHub and the
Gitea mirror:

```
cumba-oss-corej-rest-<version>.jar
cumba-oss-corej-rest-<version>.jar.asc
```

The two releases are cut independently from the same tag by `.github/workflows/ci.yml` and
`.gitea/workflows/main.yml`; the Gitea run additionally deploys to the internal Nexus. Nothing
reaches Maven Central.

⚠ **This repository has never cut a tag.** `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` are
**organisation-level** secrets on `cumba-oss`, so they are inherited here rather than set per
repository — but the signing path itself has never run in this repo, so the first tag is its
first real test. The release job fails by name on an empty key rather than publishing unsigned
assets.

### Verifying a release asset

Each jar is signed with the same GPG key as the project's Maven Central artifacts — one
trust root, not two. Nothing here reaches Central, so the release asset is the **only**
delivery, and a release asset **can be replaced in place** by anyone with write access:
unlike an immutable Central artifact, the signature is the only thing standing between you
and a swapped jar.

```bash
curl -sLO <asset-url> && curl -sLO <asset-url>.asc
gpg --verify cumba-oss-corej-rest-<version>.jar.asc cumba-oss-corej-rest-<version>.jar
```

Key fingerprint: `AE5AA7685BED3FC5DF4AE8DD7727EF25F931AF6B`

⚠ **Release assets are mutable**, unlike Central artifacts. The signature proves a jar is
authentic; **your pinned hash proves *which* authentic jar you adopted.** Record both, and
don't assume re-downloading a tag returns the same bytes.

## Build

Java 25, Maven 3.9.12+.

```bash
mvn -B clean install -Drevision=0.1.0-SNAPSHOT
```

`0.1.0-SNAPSHOT` is the current `<revision>` and also its fallback value in the pom, so
`-Drevision=…` may be omitted locally; CI always passes it explicitly.

### ⚠ A plain `mvn verify` is not the CI gate

Three of the four gate flags default to **permissive** so that day-to-day builds are not blocked
by a style finding, and `spotless` in its default mode *reformats your sources* instead of
failing on them:

| Property | Default here | CI |
|---|---|---|
| `maven.compiler.failOnWarning` | `true` | `true` |
| `pmd.failOnViolation` | `false` | `true` |
| `spotbugs.failOnError` | `false` | `true` |
| `spotless.check` | `false` (applies) | `true` (checks) |

To run what CI runs — the `MAVEN_CI_GATES` value from both workflow files:

```bash
mvn -B spotless:check -Drevision=0.1.0-SNAPSHOT
mvn -B clean install -Drevision=0.1.0-SNAPSHOT \
    -Dmaven.compiler.failOnWarning=true -Dspotless.check=true \
    -Dpmd.failOnViolation=true -Dspotbugs.failOnError=true
```

CI then runs a Pitest mutation pass (`-P Pitest`) on top, and — on Gitea only — Sonar, the Nexus
deploy and the release.

## Running

The Spring Boot entry point is `net.cumba.corej.rest.CorejRestApplication`. It serves on port
`8080` by default (`server.port` in `src/main/resources/application.yaml`).

⛔ **The build does not produce a runnable jar.** `spring-boot:repackage` is not bound in the
default build, the manifest carries no `Main-Class`, and no dependencies are staged beside the
jar — `target/cumba-oss-corej-rest-<version>.jar` is a **thin** jar, and `java -jar` on it (or on
the release asset) fails. In the monorepo the runnable bundle came from the `dist/corej-rest-dist`
assembly module, which did not come across the repository split, and nothing has replaced it yet.
Until it does, a deployment has to supply the classpath itself.

From a checkout, run it through the Maven plugin:

```bash
mvn -B org.springframework.boot:spring-boot-maven-plugin:4.1.1:run
```

⚠ Use the **fully-qualified** coordinate. The short `mvn spring-boot:run` does *not* work here:
the plugin is declared only inside the `generate-openapi` profile, so prefix resolution fails with
`No plugin found for prefix 'spring-boot' in the current project` (measured). `4.1.1` is the
`dependency.spring-boot.version` pinned in the pom.

### API surface

Swagger UI at `/swagger-ui.html`, the OpenAPI document at `/v3/api-docs`.

| Path | Verbs | What |
|---|---|---|
| `/api/info` | GET | service name and build version |
| `/api/meta/run-options`, `/api/meta/rules` | GET | valid values for the run form, derived from the rules directory |
| `/api/sessions` | GET, POST | list / create a session |
| `/api/sessions/{id}` | PATCH, DELETE | rename, delete |
| `/api/sessions/{id}/files` | POST, DELETE | upload (multipart), clear |
| `/api/sessions/{id}/files/from-url` | POST | fetch a file by URL server-side |
| `/api/sessions/{id}/files/{filename}` | DELETE | delete one file |
| `/api/sessions/{id}/files/{filename}/define-version` | GET | detected Define-XML version |
| `/api/sessions/{id}/checks` | POST | start a check run |
| `/api/checks` | GET | list runs |
| `/api/checks/{id}` | DELETE | delete a run |
| `/api/checks/{id}/status`, `/cancel` | GET, POST | poll, cancel |
| `/api/checks/{id}/findings` | GET | paged findings |
| `/api/checks/{id}/dataset-groups`, `/conformance`, `/rules`, `/artifacts` | GET | report projections |
| `/api/checks/{id}/report`, `/report-v2` | GET | the stored report verbatim (JSON, or XLSX by `Accept`) |
| `/api/checks/{id}/log`, `/log/file`, `/log/lines` | GET | run log |
| `/api/checks/{id}/rules/{coreId}/definition` | GET | one rule's definition |

Responses over 4 KB are gzipped (`server.compression`) — validation reports are large JSON, and a
big v1/v2 report can exceed 70 MiB. Multipart limits are lifted to 512 MB per file and per
request; tune both per deployment.

To dump the OpenAPI document to `target/openapi.json` (starts the app, dumps, stops it):

```bash
mvn -B -Pgenerate-openapi verify -Drevision=0.1.0-SNAPSHOT
```

⚠ Neither plugin in that profile is version-pinned, so the dump resolves whatever plugin release
Maven finds newest. It is opt-in and out of the CI gate, so this has not bitten yet — but it is
not reproducible.

## Configuration

Service settings live under the `corej:` key in `src/main/resources/application.yaml`:

| Key | Default | What |
|---|---|---|
| `corej.sessions.dir` | unset → fresh OS temp dir, removed on shutdown | per-session upload staging |
| `corej.reports.dir` | unset → fresh OS temp dir, removed on shutdown | per-run report JSON (the source of truth for every report endpoint) |
| `corej.reports.cache.ttl` / `.max-entries` | `30m` / `64` | in-memory cache of parsed reports |
| `corej.persistence.rehydrate-on-startup` | `true` | rebuild sessions and runs from their on-disk manifests at startup |
| `corej.runs.max-parallel` | `2` | concurrently executing check runs across all sessions; the rest queue as `PENDING` |
| `corej.engine.max-errors-per-rule` | unset | service-wide default per-rule findings cap |
| `corej.cache-seed.*` | `enabled: false` | opt-in, startup-only seeding of the CDISC Library cache from the Python engine's pickles, for deployments with no API key |

Set `sessions.dir` and `reports.dir` to distinct **persistent** directories to survive a restart;
a temp dir is always fresh, so `rehydrate-on-startup` has no effect there.

Spring's relaxed binding gives every key an environment-variable spelling: uppercase, `.` → `_`,
and **hyphens removed** — `corej.sessions.dir` is `COREJ_SESSIONS_DIR`, but
`corej.runs.max-parallel` is `COREJ_RUNS_MAXPARALLEL`, not `COREJ_RUNS_MAX_PARALLEL`.

`spring.application.name` is `corej-cdisc-rest` and the config prefix is `corej:`. Both are
**deliberately not renamed** — they are wire- and ops-visible identifiers, not namespace tokens.

## ⚠ The rule corpus is not in this repository

The engine needs a **rule corpus on disk at runtime**. It is not a Maven dependency and is not
vendored here — it is released separately, as signed archives, from
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules):
`cumba-oss-corej-rules-<version>.zip` (data rule packages) and
`cumba-oss-corej-rules-define-<version>.zip` (Define-XML packages).

⛔ **The rules directory is *not* a `corej:` yaml key.** It is resolved by the engine, not by
Spring, from `COREJ_RULES_DIR`, then the `corej.rules.dir` system property, then `./rules`.
Putting `corej.rules.dir` in `application.yaml` binds nothing and fails silently — the service
starts and `/api/meta/run-options` simply offers no rule packages.

## Serving the SPA from this jar (`bundle-web`)

The SPA in [cumba-oss-corej-web](https://github.com/cumba-oss/cumba-oss-corej-web) is **not** in
this repository; it is released as its own signed archive. The opt-in `bundle-web` profile inlines
a built SPA into this jar's `static/`, so the API serves the UI at `/` on the same origin and no
CORS configuration is needed:

```bash
unzip cumba-oss-corej-web-<version>.zip -d ./web-dist
mvn -B -Pbundle-web clean package -Drevision=0.1.0-SNAPSHOT -Dweb.dist.dir=$PWD/web-dist
```

⚠ `web.dist.dir` is a **property with no usable default** rather than a relative path. In the
monorepo it pointed at a sibling `../corej-web/dist`, which does not resolve across the
repository split; the default now names a directory that does not exist, so a run that forgets
`-Dweb.dist.dir` is skipped with a warning and yields an API-only jar instead of copying
something arbitrary. The profile does not change the default build.

For the cross-origin alternative — a separately hosted SPA — add a CORS `WebMvcConfigurer` bean
instead of bundling.

## What is deliberately not here

- **Containers.** The Dockerfiles, Compose files and the `start.sh` launcher were wired to the
  monorepo layout (`dist/` bundle modules, the web SPA, the rule corpus in a sibling module) and
  could not build from this repository. They are being reworked into their own repository.
- **The web SPA.** See `bundle-web` above.
- **The rule editor.** Not part of the open-source distribution.

## Licence

AGPL-3.0-only — see [LICENSE](LICENSE).
