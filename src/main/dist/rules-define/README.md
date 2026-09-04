# `rules-define/` — the Define-XML conformance corpus goes here

This directory ships **empty**, for the same reason as `rules/` beside it: the corpus is
released separately.

It is needed only by Define-XML conformance runs; everything else ignores it.

Get it from the matching release of
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules) — asset
`cumba-oss-corej-rules-define-<version>.zip`, a **separate asset** from the data corpus — and
move its contents here:

```sh
unzip cumba-oss-corej-rules-define-<version>.zip
mv cumba-oss-corej-rules-define-<version>/rules-define/* ./rules-define/
```

The result should hold `packages.json` and `rules-define-*.json` directly.

⚠ **Do not delete this directory.** The bundle's `.conf` sets `corej.define.rules.dir`
unconditionally, so this path counts as *configured*, and a configured Define-XML rules
directory that does not exist is a hard error — `DefineRuleSelectionException: Configured
Define-XML rules directory not found`. An empty directory is fine; a missing one is not.
(`rules/` beside it is more forgiving: there, missing and empty behave identically.)

`COREJ_DEFINE_RULES_DIR` overrides the bundle default configured in
`cumba-oss-corej-rest.conf`.
