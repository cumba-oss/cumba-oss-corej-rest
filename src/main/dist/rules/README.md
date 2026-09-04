# `rules/` — the data rule corpus goes here

This directory ships **empty**. The engine needs a rule corpus on disk at runtime; it is not a
Maven dependency and is not vendored in this bundle, because the rules are versioned and
released on their own cadence.

Until it is populated the service starts normally and `/api/meta/run-options` simply offers no
rule packages.

Get the corpus from the matching release of
[cumba-oss-corej-rules](https://github.com/cumba-oss/cumba-oss-corej-rules) — asset
`cumba-oss-corej-rules-<version>.zip` — and move its **contents** here, so this directory ends
up holding `packages.json` and `rules-*.json` directly:

```sh
unzip cumba-oss-corej-rules-<version>.zip
mv cumba-oss-corej-rules-<version>/rules/* ./rules/
```

⚠ The archive has its own top-level `cumba-oss-corej-rules-<version>/rules/` directory, so
unzipping it *into* this one buries the JSON two levels too deep and the engine finds nothing.
Move the contents, as above.

`COREJ_RULES_DIR` overrides the bundle default configured in `cumba-oss-corej-rest.conf`.

## Verifying

```sh
curl -s localhost:8080/api/meta/rules
```

⚠ Restart the service after changing the corpus.
