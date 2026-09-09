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

Tagging `vX.Y.Z` builds, signs and attaches **four** files to the release, on both the GitHub and
the Gitea mirror:

```
cumba-oss-corej-rest-<version>.zip        ← the runnable distribution
cumba-oss-corej-rest-<version>.zip.asc
cumba-oss-corej-rest-<version>.jar        ← the plain artifact, also on Nexus
cumba-oss-corej-rest-<version>.jar.asc
```

⭐ **The zip is what you run** (§ Running). The jar is kept for consumers who assemble their own
classpath; on its own it is not runnable.

The two releases are cut independently from the same tag by `.github/workflows/ci.yml` and
`.gitea/workflows/main.yml`; the Gitea run additionally deploys to the internal Nexus. Nothing
reaches Maven Central.

⚠ **This repository has never cut a tag.** `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` are
**organisation-level** secrets on `cumba-oss`, so they are inherited here rather than set per
repository — but the signing path itself has never run in this repo, so the first tag is its
first real test. The release job fails by name on an empty key rather than publishing unsigned
assets.

### Verifying a release asset

**Every asset is signed** — the zip as well as the jar — with the same GPG key as the project's
Maven Central artifacts, one trust root rather than two. Nothing here reaches Central, so the
release asset is the **only** delivery, and a release asset **can be replaced in place** by anyone
with write access: unlike an immutable Central artifact, the signature is the only thing standing
between you and a swapped file.

⚠ Verify the asset you actually intend to run. For most people that is the **zip**:

```bash
curl -sLO <asset-url> && curl -sLO <asset-url>.asc
gpg --verify cumba-oss-corej-rest-<version>.zip.asc cumba-oss-corej-rest-<version>.zip
# and the same two lines with .jar, if you are consuming the jar
```

Key fingerprint: `AE5AA7685BED3FC5DF4AE8DD7727EF25F931AF6B`

⚠ **Release assets are mutable**, unlike Central artifacts. The signature proves an asset is
authentic; **your pinned hash proves *which* authentic asset you adopted.** Record both, and
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

### From a release — the distribution zip

Every release carries **`cumba-oss-corej-rest-<version>.zip`**, a self-contained runnable
bundle. Download it (and its `.asc`, see *Verifying a release asset*), unzip, run:

```bash
unzip cumba-oss-corej-rest-<version>.zip
cd cumba-oss-corej-rest-<version>
JAVA_OPTS=-Xmx8g ./run.sh          # run.bat on Windows
```

```
cumba-oss-corej-rest-<version>/
├── cumba-oss-corej-rest.jar     the cumba-oss-bootstrap launcher
├── cumba-oss-corej-rest.conf    launcher + JVM configuration
├── run.sh   run.bat
├── config/application.yaml      external Spring configuration (overrides)
├── rules/  rules-define/  dictionaries/    ship empty; each has a README
└── lib/                         the application jar and every dependency
```

The bundle is **relocatable** — put it anywhere and start `run.sh` by any path, from any working
directory. `JAVA_OPTS` goes to the JVM; arguments go to Spring Boot
(`./run.sh --server.port=9090`).

`cumba-oss-corej-rest.jar` is not the application: it is
[`cumba-oss-bootstrap`](https://github.com/cumba-oss/cumba-oss-commons), a dependency-free
launcher that reads the sidecar `.conf` beside it, applies its `[properties]` as system
properties, assembles the `lib/` classpath and invokes `CorejRestApplication`. ⚠ **The jar and
the `.conf` must keep the same basename** — the lookup is "strip `.jar`, append `.conf`, look
beside me". Rename one, rename both, or pass `-Dbootstrap.config=<path>`.

There are **two configuration files, and they are not interchangeable**:

| File | Read by | Holds |
|---|---|---|
| `cumba-oss-corej-rest.conf` | the launcher | the classpath, JVM system properties, and the engine's `corej.rules.dir` / `corej.define.rules.dir` / `corej.dictionariesDir` |
| `config/application.yaml` | Spring | `server.port`, `corej.*` yaml keys, springdoc, multipart limits — overriding the copy packaged in the jar |

⛔ Putting `corej.rules.dir` in `config/application.yaml` binds **nothing** and fails silently
(see below). It belongs in the `.conf`, where the bundle already sets it.

### From a checkout

```bash
mvn -B org.springframework.boot:spring-boot-maven-plugin:4.1.1:run
```

⚠ Use the **fully-qualified** coordinate. The short `mvn spring-boot:run` does *not* work here:
the plugin is declared only inside the `generate-openapi` profile, so prefix resolution fails with
`No plugin found for prefix 'spring-boot' in the current project` (measured). `4.1.1` is the
`dependency.spring-boot.version` pinned in the pom.

`mvn package` also builds the distribution zip at `target/cumba-oss-corej-rest-<version>.zip`,
and the same tree exploded at
`target/cumba-oss-corej-rest-<version>/cumba-oss-corej-rest-<version>/` for `src/test/smoke.sh`.

⚠ **Two levels, not one.** The assembly's `dir` format nests its `<baseDirectory>` inside the
execution's `<finalName>`, so the bundle root is the doubled path above — pointing the smoke
script at the outer directory reports `bundle is missing run.sh` on a perfectly good build.

⚠ **The plain jar is still not runnable, by design.** `spring-boot:repackage` is deliberately
not bound: `target/cumba-oss-corej-rest-<version>.jar` carries no `Main-Class` and no
dependencies, and `java -jar` on it fails. It remains the artifact published to Nexus and is
still attached to the release for consumers who manage their own classpath — the **zip** is what
you run.

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

⭐ **The distribution zip already handles this.** `cumba-oss-corej-rest.conf` sets each of the
three directories to `${env:COREJ_…:-${sys:<property>:-${bootstrap.dir}/<dir>}}`, giving

    COREJ_* environment  >  -D system property  >  the bundle's own directory

— the engine's documented precedence, but resolving against the bundle instead of the working
directory of whatever supervises the service. ⚠ The `${sys:…}` level is not cosmetic: the
launcher applies `[properties]` with an unconditional `System.setProperty`, so a two-level form
would *overwrite* a `-D` the operator passed. Drop the corpus into the bundle's `rules/` (contents, not the archive's
top-level directory) or point `COREJ_RULES_DIR` elsewhere, and restart.

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

## Containers

`Dockerfile`, `docker-compose.yml` and `docker-entrypoint.sh` build and run the service from this
repository alone:

```sh
docker compose up -d --build
docker compose logs -f                 # the startup notices are here
curl -fsS http://localhost:8080/api/info
```

The build stage runs one `mvn -B -DskipTests package` and unpacks the dist zip; the runtime stage
is a JRE over that bundle, running as uid 1000. Compose bind-mounts `./corej-data` at `/app/data`,
so sessions, reports, the rule corpora, the dictionary store and the CDISC Library API cache all
stay on the host — `mkdir -p corej-data && sudo chown -R 1000:1000 corej-data` once, first.
Copy `.env.example` to `.env` to change any of it.

Three things the image deliberately does **not** carry, each announced once at startup rather than
discovered per run:

- **No SPA — the image is API-only.** `bundle-web` needs a pre-built SPA handed to it via
  `-Dweb.dist.dir`, and there is nothing here for a container build to inline. `/api` and
  `/swagger-ui.html` are what it serves.
- **No rule corpus.** The corpora are released separately by `cumba-oss-corej-rules` and are not
  Maven dependencies. Unpack the two release assets into `corej-data/rules` and
  `corej-data/rules-define`.
- **No dictionary installer.** The installer is the CLI, a separate repository. Run the
  `cumba-oss-corej-cli` image against the same `./corej-data`, or mount a licensed distribution at
  `/licensed-dictionaries/<type>`.

## What is deliberately not here

- **The web SPA.** See `bundle-web` above.
- **The rule editor.** Not part of the open-source distribution.

## Licence

AGPL-3.0-only — see [LICENSE](LICENSE).
