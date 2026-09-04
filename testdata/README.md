# Test data fixtures

⛔ **Nothing in this repository reads these files.** They came across unchanged when
`cumba-oss-clients` was split, but the test that used them —
`BlankCellFormatIndependenceTest`, which asserts that a blank cell resolves by column type
identically across every input format — lives in
[cumba-oss-corej-cli](https://github.com/cumba-oss/cumba-oss-corej-cli), not here. No source
or test file under `src/` mentions `testdata`, and no REST test resolves the `repoRoot`
system property that would anchor them.

| File | Format |
|---|---|
| `xpt/01_plain/adsl.xpt` | SAS v5 transport |
| `sas7bdat/01_plain/adsl.sas7bdat` | SAS7BDAT |

Both are ADSL from **CDISCPILOT01**, the public CDISC pilot study — not real study data. They
are byte-identical to the copies in `cumba-oss-corej-cli`, where they are still in use.

⚠ Deciding whether to delete this directory or to add a REST-side test that earns it is
open. Until then, treat it as dead weight, not as a fixture contract.
