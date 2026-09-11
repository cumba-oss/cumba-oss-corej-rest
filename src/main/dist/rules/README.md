# `rules/` — the data rule corpus

⭐ **This directory ships FULL.** The bundle carries a pinned, hash-verified rule corpus, so a
freshly unzipped service offers rule packages immediately. You do not have to install anything
here.

It holds `packages.json` and a set of `rules-*.json` package files, directly — no subdirectory.
The version that shipped is pinned in this repository's `pom.xml` as `dependency.corej-rules.tag`,
and it is the **same corpus `cumba-oss-corej-cli` and the cumba data browser ship**, so all three
answer identically for the same study.

⚠ Until 2026-09-11 this directory shipped **empty**, and until it was populated the service started
normally and `/api/meta/run-options` simply offered no rule packages — a service that looks healthy
and can validate nothing. If you are reading that older README in a bundle, it is that bundle's
version that is out of date, not this one.

## What is in it, and what is not

The corpus comes from [cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules),
the public corpus. Two things to know about its scope:

- **No CORE rule family.** Those rules are externally sourced and may not be redistributed as a
  set, so no cumba bundle ships them. Everything else is here. ⚠ A consequence worth stating:
  **the bundled corpus therefore has no TIG coverage** — the only TIG package outside the CORE
  family is `rules-draft-tig-1-0`, which is in the DRAFT family below.
- **The DRAFT family IS present, for now.** Release `v0.3.1` ships 47 packages, 15 of them
  `rules-draft-*`. These are **parked candidate rules**: fully generated, schema-validated and
  executed by their test scenarios upstream, but not promoted, so their content can change and
  they are not a basis for a regulatory submission. They are offered by `/api/meta/run-options`
  like any other package, and are never selected for you. A future corpus release drops them from
  the published archive, at which point a bundle built against that release will not have them.

## Replacing it with a different corpus version

Two ways. Neither requires rebuilding the bundle.

**Point at another directory** — nothing is copied, and the bundle stays as shipped:

```sh
COREJ_RULES_DIR=/srv/corej/rules-0.4.0 ./run.sh
```

`COREJ_RULES_DIR` outranks the `corej.rules.dir` system property set by
`cumba-oss-corej-rest.conf`, which outranks this directory.

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

⚠ **Restart the service** after replacing the corpus.

⚠ In the container image the entrypoint **seeds** this corpus onto the data volume only if the
target has no `packages.json` of its own, so a corpus you unpack there is never overwritten.

## Verifying what is loaded

```sh
curl -s http://localhost:8080/api/meta/run-options
```

The `packages` array is built from this directory's `packages.json`. An **empty** array means the
service is not reading a usable corpus here — that is the failure worth checking for, and it is
what `src/test/smoke.sh` asserts at build time.
