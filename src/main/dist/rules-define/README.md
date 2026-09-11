# `rules-define/` — the Define-XML conformance corpus

⭐ **This directory ships FULL**, like `rules/` beside it: the bundle carries a pinned,
hash-verified Define-XML corpus, from the same `cumba-oss-corej-rules` release. Nothing to install.

It holds `packages.json` and `rules-define-*.json` directly — the four packages are CDISC and PMDA,
each for Define-XML 2.0 and 2.1.

It is read only by Define-XML conformance runs; everything else ignores it.

⚠ Until 2026-09-11 this directory shipped **empty**, with instructions to unzip a release asset in
by hand.

## ⚠⚠ It must hold the PACKAGED corpus, never authored YAML

The engine resolves Define-XML rules through the manifest
(`DefineConformanceEngine.selectRules` → `DefineRulePackageManifest.load`), so a directory of
authored `*.yaml` makes every Define-XML check fail with *"the directory does not hold a generated
Define-XML rule corpus"*.

Loading a YAML tree wholesale is impossible in principle anyway: 313 of the 409 rules are published
in **both** of their family's packages and collide on `Rule_Id`. Only the engine — after its
pre-pass — knows which version the document is, and therefore which package to resolve against.

## Replacing it with a different corpus version

```sh
COREJ_DEFINE_RULES_DIR=/srv/corej/rules-define-0.4.0 ./run.sh
```

or, in place, from the release's **second** asset — `cumba-oss-corej-rules-define-<version>.zip` is
a separate archive from the data corpus:

```sh
unzip cumba-oss-corej-rules-define-<version>.zip
rm -f rules-define/rules-define-*.json rules-define/packages.json
mv cumba-oss-corej-rules-define-<version>/rules-define/* ./rules-define/
```

⚠ Take both assets from the **same** release. The two corpora are versioned together upstream even
though they are separate archives.

⚠ **Do not delete this directory.** The bundle's `.conf` sets `corej.define.rules.dir`
unconditionally, so this path counts as *configured*, and a configured Define-XML rules directory
that does not exist is a hard error — `DefineRuleSelectionException: Configured Define-XML rules
directory not found`. An empty directory is fine; a missing one is not. (`rules/` beside it is more
forgiving: there, missing and empty behave identically.)

`COREJ_DEFINE_RULES_DIR` overrides the bundle default configured in `cumba-oss-corej-rest.conf`.
