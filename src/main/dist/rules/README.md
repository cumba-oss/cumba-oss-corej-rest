# `rules/` — the data rule corpus

⭐ **This directory ships FULL.** The bundle carries a pinned, hash-verified rule corpus, so a
freshly unzipped service offers rule packages immediately. You do not have to install anything
here.

It holds `packages.json` and a set of `rules-*.json` package files, directly — no subdirectory.
The version that shipped is pinned in this repository's `pom.xml` as `dependency.corej-rules.tag`,
and it is the **same corpus the CLI and the cumba data browser ship**, so all three answer
identically for the same study.

⚠ Until 2026-09-11 this directory shipped **empty**, and until it was populated the service started
normally and `/api/meta/run-options` simply offered no rule packages — a service that looks healthy
and can validate nothing. If you are reading that older README in a bundle, it is that bundle's
version that is out of date, not this one.

## What is in it, and what is not

The corpus is the pinned release asset of `cumba-oss-corej-rules`, which is the public corpus. It
is downloaded at build time from the host this repository's `pom.xml` names as
`corej.rules.server`, and verified against the `sha256` pinned beside it. Three things to know
about its scope:

- **No CORE rule family.** Those rules are externally sourced and may not be redistributed as a
  set, so no cumba corpus carries them and no cumba bundle ships them. Everything else is here.
- **No DRAFT family.** The published archive carries no `rules-draft-*` package. The DRAFT rules
  are parked candidate rules: fully generated, schema-validated and executed by their test
  scenarios upstream, but not promoted.
- **No TIG coverage.** `rules-draft-tig-1-0` is the only TIG package in the corpus, so TIG
  conformance travelled with the DRAFT family and is not offered. Accepted, not a defect.

## Replacing it with a different corpus version

Two ways. Neither requires rebuilding the bundle.

**Point at another directory** — nothing is copied, and the bundle stays as shipped:

```sh
COREJ_RULES_DIR=/srv/corej/rules-0.5.0 ./run.sh
```

`COREJ_RULES_DIR` outranks the `-Dcorej.rules.dir` system property, which outranks this directory —
the precedence the launcher's sidecar `.conf` configures.

**Or replace the contents in place**, from a `cumba-oss-corej-rules` release:

```sh
unzip cumba-oss-corej-rules-<version>.zip
rm -f rules/rules-*.json rules/packages.json
mv cumba-oss-corej-rules-<version>/rules/* ./rules/
```

⚠ The archive has a top-level `cumba-oss-corej-rules-<version>/rules/` directory, so unzipping it
*into* this one leaves the JSON two levels too deep and the engine finds nothing. Move the
contents, as above.

⚠ Delete the old `rules-*.json` first. The engine reads whatever packages are present, so leftovers
from the previous corpus stay selectable and silently mix two versions.

⚠ In the container image the entrypoint **seeds** the bundled corpus onto the configured rules
directory only if that directory holds no `packages.json` of its own, so a corpus you unpack there
is never overwritten.

⚠ **Restart the service** after replacing the corpus.

## Verifying what is loaded

```sh
curl -s http://localhost:8080/api/meta/run-options
```

The `packages` array is built from this directory's `packages.json`. An **empty** array means the
service is not reading a usable corpus here — that is the failure worth checking for, and it is
what `src/test/smoke.sh` asserts at build time.
