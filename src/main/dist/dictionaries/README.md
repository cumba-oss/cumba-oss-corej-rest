# `dictionaries/` — the external-dictionary store

This directory ships **empty** and is the bundle's dictionary store root, wired up in
`cumba-oss-corej-rest.conf` as `corej.dictionariesDir`.

External dictionaries (MED-RT, UNII, neoplasm, MedDRA, WHODrug, SNOMED, LOINC) are licensed
separately and are never redistributed here. This service has **no endpoint that installs
them** — populating the store is a deliberate server-side maintenance action. Use the
[coreJ CLI](https://github.com/cumba-oss/cumba-oss-corej-cli) bundle's
`--install-dictionaries` against this directory, or point `COREJ_DICTIONARIES_DIR` at a store
you maintain elsewhere.

⚠ **Do not delete this directory.** A *configured* dictionary store that does not exist is a
deliberate hard error in the engine, so removing it makes every validation run fail. ⚠ The store
is resolved when a run sets up, **not** at process startup: the service starts normally and
answers `/api/info`, and the first check run then dies. Do not read a healthy startup as evidence
that the store is optional.

An empty store is fine: dictionary-backed rules then report as un-answerable, which surfaces in a
run's `dictionaryBasis` note rather than failing the run.
