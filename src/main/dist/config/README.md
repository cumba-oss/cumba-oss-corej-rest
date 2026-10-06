# `config/` — external Spring configuration

`application.yaml` here overrides the copy packaged inside the application jar. The launcher
wires it up in `cumba-oss-corej-rest.conf` as

```ini
spring.config.additional-location = ${env:COREJ_CONFIG_DIR:-${sys:corej.config.dir:-${bootstrap.dir}/config}}/
```

`additional-location` (not `location`) means the packaged defaults still apply and this file
only overrides the keys it sets — so you can change one value without restating the file.
`COREJ_CONFIG_DIR` points the whole mechanism somewhere else.

⚠ The trailing slash in that value is required, and it is appended by the `.conf` — Spring Boot
reads a value without one as a *file* path and refuses to start:

```
File extension is not known to any PropertySourceLoader. If the location is meant to reference
a directory, it must end in '/'
```

That is a loud failure at startup, not a silent misconfiguration. Set
`COREJ_CONFIG_DIR` (or `-Dcorej.config.dir`) to a directory **without** one — a value that
already ends in `/` produces a harmless `//`, which Spring tolerates.

⛔ `-Dspring.config.additional-location=…` on the command line does **not** work: the `.conf`
applies its `[properties]` with an unconditional `System.setProperty` and overwrites it. Use
`COREJ_CONFIG_DIR` or `-Dcorej.config.dir`, both of which the `.conf` reads.

⛔ **`corej.rules.dir`, `corej.define.rules.dir` and `corej.dictionariesDir` do not work here.**
They are read by the engine directly from the environment and system properties, never through
Spring's `Environment`. Put them in `cumba-oss-corej-rest.conf`, where the bundle already sets
them to its own `rules/`, `rules-define/` and `dictionaries/` directories.

**`brands/`** — with the SPA bundled into the jar (`-Pbundle-web`), a brand of your own is a folder
`brands/<name>/` here (a `brand.css` with the design tokens, an optional `brand.json` for a logo;
the contract is `brands/README.md` in the web repository), selected with `COREJ_BRAND_NAME=<name>`
(⚠ not `COREJ_BRAND`, which maps to the `corej.brand` object itself and refuses to start).
`neutral` (the default) and `p300` ship with the SPA. The `.conf` points `corej.brand.directory`
here (`COREJ_BRAND_DIRECTORY` overrides it); an unknown name fails start-up naming the available
brands. An API-only jar ignores it.

