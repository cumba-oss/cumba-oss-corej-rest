#!/usr/bin/env bash
#
# Smoke-test the assembled distribution bundle.
#
# ⛔ WHY THIS EXISTS.
#
# Before this bundle the release asset was the thin application jar: no Main-Class,
# no dependencies, `java -jar` on it simply fails. Nothing in `mvn verify` noticed,
# because nothing ever tried to start it. A zip can also assemble perfectly green
# and still be unrunnable — an empty lib/, an assembly declared before the jar
# plugin, an unresolvable ${...} in the sidecar .conf.
#
# So this actually starts the service and talks to it. Reaching a 200 from
# /api/info proves
#
#   * the bootstrap launcher found and parsed its sidecar .conf,
#   * every ${...} interpolation in it resolved,
#   * the whole lib/ classpath assembled and Spring Boot came up under the
#     launcher's URLClassLoader (auto-configuration, component scan, Tomcat), and
#   * external configuration via COREJ_CONFIG_DIR reached Spring — the run below
#     sets a port there that nothing else knows about, so a service answering on it
#     can only have read that file.
#
# ⚠ WHAT THIS STILL DOES NOT PROVE. A context refresh is a much wider net than the
# CLI's probe — it loads every Spring bean and so caught the jspecify regression —
# but it does not touch the REPORT WRITERS, which the engine resolves lazily when a
# run produces output. Deleting cumba-oss-corej-report-xlsx and its POI chain would
# still leave /api/info answering 200. The CLI bundle has a probe for that (an
# invalid --output-format makes the engine enumerate the writers it can see); this
# service exposes no equivalent endpoint, so the jar-count floor below is the only
# backstop here. Do not read `smoke: OK` as "every jar is present".
#
# ⚑ The twin is the same script with the other bundle names. The two are meant to
# stay in step: a fix to one belongs in the other.
#
# Usage: smoke.sh <path-to-exploded-bundle>

set -euo pipefail

# ⚠ PREFLIGHT. Two of the assertions below are `if grep -q … "$log"`, and grep
# exiting 127 (not installed) is indistinguishable from "no match" — the assertion
# silently becomes a no-op. Measured by accident while testing with a stripped PATH:
# the script printed `smoke: OK` with `grep: command not found` on stderr.
#
# ⚠ `cat` is the one that most needs to be here. It runs inside http_get, which is
# called as `if body="$(http_get …)"` — set -e is suspended for the whole function,
# so a missing cat yields an empty body, 120 retries and the verdict "no response
# from /api/info within 120s": a broken environment reported as a broken bundle.
#
# `env` and `wc` matter for the same reason and were missed once: `env` runs the
# service (missing -> "the service exited before it answered", diagnosable only from
# the log), and `wc` feeds the jar-count floor through a pipeline under `set -e`, so
# a missing `wc` exits 127 with NO message at all.
#
# curl is deliberately NOT in this list; it is genuinely optional (see http_get).
# ⚠ cut was added on 2026-09-17 with the corpus-load probe below, which reads the
# package short names out of the service's own JSON.
# ⚠ tr was added on 2026-10-03: the EXECUTION_ERROR message extraction flattens the
# report onto one line first, so a pretty-printed report yields the message too.
for tool in cat cut env find grep java ls mkdir mktemp rm seq sleep sort tail timeout tr wc; do
    command -v "$tool" >/dev/null 2>&1 \
        || { echo "smoke: required tool not found: $tool" >&2; exit 1; }
done

BUNDLE="${1:?usage: smoke.sh <path-to-exploded-bundle>}"
[ -d "$BUNDLE" ] || { echo "smoke: not a directory: $BUNDLE" >&2; exit 1; }
# ⚠ Absolute, because the run below deliberately happens from an unrelated CWD --
# a relative bundle path would resolve against that temp directory instead.
BUNDLE="$(cd "$BUNDLE" && pwd)"

# ⛔ The three STORE directories are the one bundle property the .conf marks
# DO-NOT-DELETE: for dictionaries/ a configured-but-missing directory is a hard error
# on every real run. They exist only because src/assembly/dist.xml ships them, and
# they are only in git because each holds a README -- so a dropped fileSet, or a
# deleted README, ships a bundle that passes everything else and fails on the
# operator's first run.
for d in rules rules-define dictionaries; do
    [ -d "$BUNDLE/$d" ] \
        || { echo "smoke: bundle is missing $d/ — see the DO-NOT-DELETE note in the .conf" >&2; exit 1; }
done

# ⛔ THE BUNDLED RULE CORPUS. rules/ and rules-define/ ship POPULATED since
# 2026-09-11; before that they were empty by design and only had to exist, and a
# service built from such a bundle started normally, accepted every request and
# validated nothing.
#
# The manifest is asserted separately from the count on purpose: the engine resolves
# rules THROUGH packages.json — /api/meta/run-options reads it directly — so a corpus
# that lost it degrades to an empty package list, and a count alone passes straight
# over that.
#
# ⚠ These floors are the distribution build's MIN_CORE_RULES (28) and
# MIN_DEFINE_PKGS (4), so the app and the clients move together. They are floors, not
# counts, and they are NOT the place to encode which families shipped. The exact
# DRAFT-package ratchet lives in pom.xml, where the pin it tracks lives; duplicating
# that number here would make it two places to bump.
for corpus in "rules:rules-*.json:28" "rules-define:rules-define-*.json:4"; do
    dir="${corpus%%:*}"; rest="${corpus#*:}"; glob="${rest%%:*}"; floor="${rest##*:}"
    [ -f "$BUNDLE/$dir/packages.json" ] \
        || { echo "smoke: $dir/packages.json is missing from the bundle — the engine resolves rules through that manifest, so this degrades to an empty package list rather than failing" >&2; exit 1; }
    n="$(find "$BUNDLE/$dir" -maxdepth 1 -name "$glob" -type f | wc -l)"
    [ "$n" -ge "$floor" ] \
        || { echo "smoke: only $n $glob in $BUNDLE/$dir — expected at least $floor; the assembly staged the wrong path (it points through a version-bearing directory)" >&2; exit 1; }
done

# ⚠ config/ is checked separately and NOT pointed at the DO-NOT-DELETE note: that note
# enumerates the three stores above, none of which is config/, and unlike them config/
# ships real content (application.yaml) rather than only a README. Sending an operator
# who deleted config/ to a note about rule corpora would waste their time.
#
# ⚠ AND IT MUST STAY ABOVE THE FILE LOOP BELOW, which asserts config/application.yaml —
# a file that cannot exist without config/. In the other order this check is
# unreachable and a missing config/ reports the less specific
# "bundle is missing config/application.yaml".
[ -d "$BUNDLE/config" ] \
    || { echo "smoke: bundle is missing config/ — the external Spring config directory; see config/README.md and the spring.config.additional-location line in the .conf" >&2; exit 1; }

for f in run.sh cumba-oss-corej-rest.jar cumba-oss-corej-rest.conf config/application.yaml; do
    [ -e "$BUNDLE/$f" ] || { echo "smoke: bundle is missing $f" >&2; exit 1; }
done
[ -x "$BUNDLE/run.sh" ] || { echo "smoke: run.sh is not executable" >&2; exit 1; }

# The application jar must be in lib/, not just its dependencies. An assembly
# declared before maven-jar-plugin silently produces a bundle without it.
ls "$BUNDLE"/lib/cumba-oss-corej-rest-*.jar >/dev/null 2>&1 \
    || { echo "smoke: no cumba-oss-corej-rest-*.jar in $BUNDLE/lib" >&2; exit 1; }

# A crude floor, not a manifest: it catches a dependencySet that resolved the wrong
# scope (or nothing), which is the realistic build-configuration failure. The bundle
# carries ~116 jars; anything under 90 means the graph, not a single artifact, went
# wrong.
libcount="$(ls "$BUNDLE"/lib/*.jar 2>/dev/null | wc -l)"
[ "$libcount" -ge 90 ] \
    || { echo "smoke: only $libcount jars in $BUNDLE/lib — expected ~116; the dependencySet resolved the wrong scope?" >&2; exit 1; }

work="$(mktemp -d)"
pid=""
cleanup() {
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        # Give Boot a moment to release the port, then insist.
        for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
        kill -9 "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT

# A free port, found by probing rather than assumed: CI runners are shared.
port=""
for candidate in $(seq 18730 18760); do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/$candidate") 2>/dev/null; then
        port="$candidate"; break
    fi
done
[ -n "$port" ] || { echo "smoke: no free port in 18730-18760" >&2; exit 1; }

# External configuration the packaged application.yaml does not contain. If the
# service answers on this port, Spring read this file.
mkdir -p "$work/config"
cat > "$work/config/application.yaml" <<EOF
server:
  port: $port
EOF

log="$work/service.log"
# ⚠ Run from an unrelated CWD. A bundle that only works when you stand inside it
# is the bug next door to the one this replaces.
#
# ⛔ THE `exec` IS LOAD-BEARING, not tidiness. run.sh itself ends in `exec java`, so
# with a plain `( cd … && run.sh ) &` the JVM is a GRANDCHILD of $! and killing $!
# leaves it running — measured: five orphaned JVMs after five local runs, each still
# holding its port. `exec` collapses the subshell into run.sh and run.sh into java,
# so $! is the JVM itself and the trap below can actually reach it.
( cd "$work" && exec env COREJ_CONFIG_DIR="$work/config" "$BUNDLE/run.sh" ) > "$log" 2>&1 &
pid=$!

# ⚠ Diagnose from the log BEFORE reporting the symptom. A missing runtime
# dependency makes the service die during context refresh, which otherwise
# surfaces only as the generic "exited before it answered" — the least useful
# description of the one failure this test exists to catch.
fail() {
    echo "smoke: $1" >&2
    if grep -qE 'NoClassDefFoundError|ClassNotFoundException' "$log" 2>/dev/null; then
        echo "smoke: cause — the bundle's classpath is incomplete:" >&2
        grep -oE '(NoClassDefFoundError|ClassNotFoundException): [^ ]+' "$log" | sort -u >&2
    elif grep -q '^bootstrap: ' "$log" 2>/dev/null; then
        echo "smoke: cause — the launcher rejected its configuration:" >&2
        grep '^bootstrap: ' "$log" >&2
    fi
    echo "--- service log (tail) ---" >&2
    tail -40 "$log" >&2 2>/dev/null || echo "(no log)" >&2
    exit 1
}

# ⚠ Do NOT hard-depend on curl. The Gitea job runs inside a custom Maven image and
# the GitHub job on a hosted runner; only one of those is guaranteed to carry it.
# Bash's /dev/tcp is always there (it is already used for the port probe above), so
# curl is an optimisation, not a requirement.
http_get() {
    local path="$1" raw
    if command -v curl >/dev/null 2>&1; then
        curl -fsS -m 3 "http://127.0.0.1:$port$path" 2>/dev/null
        return
    fi
    # ⚠ The suppression has to wrap the whole redirection, not sit on the command:
    #   `exec 3<>… 2>/dev/null` fails BEFORE the 2>/dev/null takes effect, so every
    #   pre-startup attempt printed a "Connection refused" pair — ~120 of them per
    #   curl-free run, burying the real output.
    #
    # ⛔ A BRACE GROUP, NOT A SUBSHELL, and the difference is load-bearing. The port
    #   probe above can use ( … ) because it throws the fd away; here fd 3 must
    #   survive into the caller for `timeout 3 cat <&3` below. `( exec 3<>… )` closes
    #   it on subshell exit, every request then reads nothing, and the verdict becomes
    #   "no response from /api/info within 120s" — on a curl-free runner only, which
    #   CI may never exercise. Do not "simplify" this to a subshell.
    { exec 3<>"/dev/tcp/127.0.0.1/$port"; } 2>/dev/null || return 1
    printf 'GET %s HTTP/1.0\r\nHost: 127.0.0.1:%s\r\nConnection: close\r\n\r\n' \
        "$path" "$port" >&3
    # ⛔ THE TIMEOUT IS NOT OPTIONAL. `cat <&3` blocks forever if something is
    # listening on the port but never answers, and the retry loop below would then
    # never advance — the step would hang until the JOB timeout (120 minutes on a
    # GitHub runner), reported as a hung build rather than a failed bundle. curl
    # gets -m 3 for the same reason; this is the fallback's equivalent.
    raw="$(timeout 3 cat <&3)"
    exec 3<&-
    # A non-2xx status is a failure, as with curl -f.
    case "$raw" in
        HTTP/1.[01]\ 2*) ;;
        *) return 1 ;;
    esac
    # Body is everything after the first blank line.
    printf '%s' "${raw#*$'\r\n\r\n'}"
}

body=""
for _ in $(seq 1 120); do
    if ! kill -0 "$pid" 2>/dev/null; then
        fail "the service exited before it answered"
    fi
    if body="$(http_get /api/info)" && [ -n "$body" ]; then
        break
    fi
    body=""
    sleep 1
done

if grep -qE 'NoClassDefFoundError|ClassNotFoundException' "$log"; then
    fail "the bundle's classpath is incomplete"
fi
if grep -q '^bootstrap: ' "$log"; then
    fail "the launcher rejected its configuration"
fi
[ -n "$body" ] || fail "no response from /api/info on port $port within 120s"

# ⚠ THE SERVICE NAME DIFFERS BETWEEN THE TWINS. InfoController.SERVICE is
# "corej-rest" in the internal service and "corej-cdisc-rest" in the published one,
# and it is deliberately NOT the artifactId (see the ⛔ note on ARTIFACT_ID in
# InfoController). A port that kept the other twin's spelling failed on a perfectly
# good bundle — measured 2026-09-11. Match the field, not a loose substring, so a
# payload that merely mentions the name elsewhere cannot satisfy it.
case "$body" in
    *'"service":"corej-cdisc-rest"'*) ;;
    *) fail "unexpected /api/info payload: $body" ;;
esac

# ⛔ THE CORPUS AS THE SERVICE SEES IT. The file checks above prove the corpus is in
# the zip; this proves the RUNNING service, reading the directory its own .conf
# resolved, offers those packages to a client. MetaController.packages() builds the
# list from packages.json in StudyValidationService.effectiveRulesDir(), so the two
# can genuinely disagree — a corpus staged into the bundle that the .conf does not
# point at satisfies the file checks and fails here, which is the case the CLI's
# equivalent probe could NOT construct.
#
# ⚠ An empty list is the failure mode to catch, and it is well-formed JSON: the
# endpoint answers 200 with "packages":[] on a service that can validate nothing,
# so requiring the KEY would pass on exactly that. Anchor on the opening of a first
# ELEMENT — "packages":[{ — which an empty array cannot produce.
#
# ⚠ Not a field name. The first version required "shortName" and failed on a
# perfectly good bundle: MetaController.PackageOption
# serialises that component as "name". A field name is a second thing to keep in
# step with the API; the array's own shape is not.
# ⚠ A SEPARATE VARIABLE, not $body — the final banner below prints the /api/info
# payload, and reusing $body here made it print the run-options payload instead.
opts=""
if ! opts="$(http_get /api/meta/run-options)" || [ -z "$opts" ]; then
    fail "no response from /api/meta/run-options on port $port"
fi
case "$opts" in
    *'"packages":[{'*) ;;
    *) fail "the service offers NO rule packages — rules/ reached the zip but the running service does not see a usable corpus there. /api/meta/run-options -> ${opts}" ;;
esac


# ---- THE ENGINE ACTUALLY LOADS AND RUNS THE CORPUS ---------------------------
#
# ⛔ WHY THIS EXISTS, 2026-09-17. Everything above — the find-based floors, the
# antrun verify-corej-rules-staged guard in pom.xml, /api/meta/run-options, even
# /api/meta/rules — reads the corpus as FILES or as a raw JSON tree. None of them
# binds a rule to net.cumba.corej.core.model.Rule, which is where a corpus the
# engine cannot parse is rejected. Most packages of the corpus pinned at the time
# still carried the retired `Operations:` block, the rejection is thrown from a
# Jackson setter, and ONE legacy rule kills the whole rules-<family>.json — with
# `smoke: OK` printed over it and /api/meta/run-options happily offering every one.
#
# So this stages a one-row dataset and starts a REAL check run for EVERY package the
# service offers, which is the only path that deserialises rules.
#
# ⭐ THE PACKAGE NAMES COME FROM THE SERVICE, never from a literal — the list this
# very bundle offers a client. A hard-coded name is a false alarm waiting for the
# corpus to legitimately stop shipping that package, and it leaves every other
# package unchecked.
#
# ⚠ A FAILED RUN IS NOT AUTOMATICALLY A LOAD FAILURE. A package declaring several
# TIG legs makes the run refuse with "name one with -mp" — a legitimate
# multi-standard refusal, after the package was loaded (the DRAFT family's
# draft-tig-1-0 was such a package; the published corpus carries no DRAFT family,
# so today every package runs). The discriminator is the
# Jackson binding signature in the message (the RulePackage[...] reference chain);
# anything else is recorded and reported, and execution is required of the corpus as
# a WHOLE: at least one package must reach SUCCEEDED with a dataset processed.
#
# ⭐⭐ THREE outcomes, not two (review round 2, R2-9). Until this fix the third —
# "FAILED, but not with the Jackson signature" — was written to a variable that was
# never read again, so a bundle whose corpus LOADS cleanly and whose runs then all
# fail at EXECUTION (an unreadable staged dm.csv, a missing metadata store, an NPE
# inside a rule, a broken report writer) printed `smoke: OK … ALL N of which the
# engine loaded; 1 ran to completion` with N-1 failures unread. That is the same
# defect probe 1 was written to end, one layer further in.
#
# The split mirrors the CLI twin. The CLI accepts exit 0 (clean run) or exit 2 and
# keys on the same two `Error:` messages this probe keys on, and FAILS on anything
# else; here the selection refusal is recognised by the two messages
# StudyValidationService raises for it, and it is reported in the banner rather than
# swallowed. Every other run failure is fatal.
#
# ⚠ Do NOT widen the refusal signature to "any FAILED that is not Jackson". That is
# precisely the swallow-everything behaviour being removed; an outcome this probe
# cannot name is an outcome it has not checked.
#
# ⚠⚠ And do NOT widen it back to the shared remedy sentence `-mp / --metadata-products`
# (review round 3, H-1) — a THIRD raise site prints that same remedy for a CORPUS
# fault. See the arm below.
#
# ⛔ A LOAD FAILURE IS NOT ALWAYS THROWN (2026-10-03). The engine files a rule that is
# definitionally invalid — an undeclared `&NAME&` token, a forward binding reference,
# an unknown key — onto the rule as a RECORDED load error and keeps going: the run
# SUCCEEDS, and the rule surfaces only in the report, as its Rules_Report row (one row
# per rule) with `"status":"EXECUTION_ERROR"`. Every outcome above is blind to that,
# so a corpus with dead rules printed `smoke: OK`. So each succeeded run's report
# (GET /api/checks/{id}/report, byte-identical to the CLI's report file) is read too,
# and ANY EXECUTION_ERROR row is fatal. That also catches a rule that fails at RUN
# time, which on this one-row dataset is just as much a defect — measured on the
# published corpus: zero EXECUTION_ERROR rows.
#
# ⛔ EVERY PACKAGE IS ACCOUNTED FOR (2026-10-03, review lane 4). A package ends in
# exactly one of two modelled states — SUCCEEDED, or REFUSED with one of the two named
# selection refusals — and every other outcome is fatal, so `succeeded + refused ==
# npkg` is asserted after the loop, and the refused packages are printed by name: a
# refusal is a package whose rules did NOT run, and the operator must see which ones.
# Today the ratchet cannot fail (every other branch exits first); it is here so that a
# future branch that forgets to exit or to count cannot pass silently. The CLI twin's
# ratchet is the one with a live arm (a run that exits 0 without completing).
#
# ⚠ Each run is ~30-90 ms in the already-warm service, so the whole sweep costs a
# couple of seconds — unlike the CLI's equivalent, which pays a JVM start per package.
#
# ⚑ The CLI has the matching probe in its own src/test/smoke.sh; the two are meant to
# stay in step.

# POST, sharing http_get's transport rules: curl when present, /dev/tcp otherwise.
# The body is always built here (multipart included), so both branches send the
# same bytes and only one of them can be wrong.
http_post() {
    local path="$1" ct="$2" body="$3" raw len
    if command -v curl >/dev/null 2>&1; then
        printf '%s' "$body" | curl -fsS -m 30 -X POST -H "Content-Type: $ct" \
            --data-binary @- "http://127.0.0.1:$port$path" 2>/dev/null
        return
    fi
    len="$(printf '%s' "$body" | wc -c)"
    # ⛔ A brace group, not a subshell — fd 3 must survive into `cat <&3` below.
    # See the same note on http_get.
    { exec 3<>"/dev/tcp/127.0.0.1/$port"; } 2>/dev/null || return 1
    printf 'POST %s HTTP/1.0\r\nHost: 127.0.0.1:%s\r\nContent-Type: %s\r\nContent-Length: %s\r\nConnection: close\r\n\r\n' \
        "$path" "$port" "$ct" "$len" >&3
    printf '%s' "$body" >&3
    raw="$(timeout 30 cat <&3)"
    exec 3<&-
    case "$raw" in
        HTTP/1.[01]\ 2*) ;;
        *) return 1 ;;
    esac
    printf '%s' "${raw#*$'\r\n\r\n'}"
}

# The short names the SERVICE offers, from the payload already fetched above.
# MetaController.PackageOption serialises the short name as "name" (see the ⚠ note
# on field names above — "shortName" was the first, wrong, guess).
packages=""
npkg=0
while IFS= read -r short; do
    [ -n "$short" ] || continue
    packages="$packages $short"
    npkg=$((npkg + 1))
done <<< "$(printf '%s' "$opts" | grep -o '"name":"[^"]*"' | cut -d'"' -f4)"

# ⚠ NON-VACUITY. An empty list would make the loop below run zero times and pass in
# silence — the "check that quietly stops checking" this probe was written to end.
# The "packages":[{ assertion above already proves the array is non-empty; this
# proves the extraction agrees with it.
[ "$npkg" -ge 1 ] \
    || fail "the service offers packages but no short names could be read from /api/meta/run-options — the payload shape changed and this probe would have checked nothing: ${opts}"

sid="$(http_post /api/sessions application/json '{"name":"smoke"}')" \
    || fail "could not create a session (POST /api/sessions)"
sid="$(printf '%s' "$sid" | grep -o '"sessionId":"[^"]*"' | cut -d'"' -f4)"
[ -n "$sid" ] || fail "POST /api/sessions returned no sessionId"

# A one-row DM, uploaded as multipart. Built by hand so the curl and /dev/tcp
# branches of http_post send identical bytes.
bd="corejsmoke$$boundary"
csv=$'STUDYID,DOMAIN,USUBJID\r\nS1,DM,U1\r\n'
part="--$bd"$'\r\n'"Content-Disposition: form-data; name=\"file\"; filename=\"dm.csv\""$'\r\n'"Content-Type: text/csv"$'\r\n\r\n'"$csv"$'\r\n'"--$bd--"$'\r\n'
http_post "/api/sessions/$sid/files?filename=dm.csv" "multipart/form-data; boundary=$bd" "$part" >/dev/null \
    || fail "could not stage dm.csv into session $sid (POST /api/sessions/{id}/files)"

# The escaped-newline cut keeps the operator-facing sentence and drops the Jackson
# source/location tail; the reference chain still yields the rule id.
run_message() {
    local s="$1" m
    case "$s" in *'"message":"'*) ;; *) printf 'no message in the run status'; return ;; esac
    m="${s#*\"message\":\"}"
    m="${m%%\\n*}"
    m="${m%%\",\"*}"
    printf '%s' "$m"
}
run_rule_id() {
    case "$1" in
        *'LinkedHashMap[\"'*)
            local r="${1#*LinkedHashMap[\\\"}"
            printf 'rule %s: ' "${r%%\\\"*}" ;;
    esac
}

bad=""
nbad=0
other=""
nother=0
refused=""
nrefused=0
recorded=""
nrecorded=0
nrows=0
succeeded=0
for p in $packages; do
    rid="$(http_post "/api/sessions/$sid/checks" application/json \
            "{\"rulesPackages\":[\"$p\"]}")" \
        || fail "could not start a check run for rule package '$p'"
    rid="$(printf '%s' "$rid" | grep -o '"checkRunId":"[^"]*"' | cut -d'"' -f4)"
    [ -n "$rid" ] || fail "starting a check run for '$p' returned no checkRunId"

    st=""
    for _ in $(seq 1 120); do
        st="$(http_get "/api/checks/$rid/status")" || st=""
        case "$st" in
            *'"status":"SUCCEEDED"'*|*'"status":"FAILED"'*|*'"status":"CANCELLED"'*) break ;;
        esac
        sleep 1
    done
    case "$st" in
        *'"status":"SUCCEEDED"'*)
            case "$st" in
                *'"processedDatasets":0'*)
                    fail "rule package '$p' succeeded without processing the staged dataset: $st" ;;
            esac
            succeeded=$((succeeded + 1))
            # ⛔ THE RECORDED LOAD ERROR. See the ⛔ note above: a rule the engine could
            # not load is reported, never thrown, so only the report shows it.
            report="$(http_get "/api/checks/$rid/report")" \
                || fail "rule package '$p' succeeded but GET /api/checks/$rid/report failed"
            # ⚠ NON-VACUITY, per report: the rows this arm reads must be there in the
            # shape it reads them, or a renamed key would turn it into a no-op.
            case "$report" in
                *'"Rules_Report"'*) ;;
                *) fail "the report for rule package '$p' has no Rules_Report — its shape changed, and the recorded-load-error check would have read nothing" ;;
            esac
            n="$(printf '%s' "$report" \
                    | grep -oE '"status"[[:space:]]*:[[:space:]]*"[A-Z_]+"' | wc -l)" || true
            nrows=$((nrows + n))
            n="$(printf '%s' "$report" \
                    | grep -oE '"status"[[:space:]]*:[[:space:]]*"EXECUTION_ERROR"' | wc -l)" || true
            if [ "$n" -gt 0 ]; then
                # BEST-EFFORT, for the operator only — the count above is the verdict.
                # The report is flattened onto one line first (tr), so a pretty-printed
                # report yields the message too; it still assumes the row's message
                # immediately precedes its status, as the report writer orders the keys.
                # A load error's message opens with the rule id in brackets, so it names
                # rule and cause.
                why="$(printf '%s' "$report" | tr '\n' ' ' \
                        | grep -oE '"message"[[:space:]]*:[[:space:]]*"([^"\\]|\\.)*"[[:space:]]*,[[:space:]]*"status"[[:space:]]*:[[:space:]]*"EXECUTION_ERROR"' \
                        | head -1 \
                        | sed -E 's/^"message"[[:space:]]*:[[:space:]]*"//; s/"[[:space:]]*,[[:space:]]*"status".*$//')" || true
                recorded="$recorded
smoke:   rules-$p.json: $n EXECUTION_ERROR row(s), first: ${why:-no message found next to the status (best-effort extraction)}"
                nrecorded=$((nrecorded + 1))
            fi ;;
        *'"status":"FAILED"'*)
            # The Jackson binding signature: the corpus, not the request, is at fault.
            case "$st" in
                *'net.cumba.corej.core.model.RulePackage'*|*RuleDefinitionException*)
                    bad="$bad
smoke:   rules-$p.json: $(run_rule_id "$st")$(run_message "$st")"
                    nbad=$((nbad + 1)) ;;
                # A run-level SELECTION refusal, not a corpus fault: the package loaded,
                # and the engine then declined to guess which standard to resolve
                # metadata against. StudyValidationService raises exactly this for a
                # multi-leg TIG package and for a package declaring no standard at all.
                #
                # ⛔ KEY ON THE TWO INTENDED MESSAGES, NOT on the remedy sentence they
                # share (review round 3, H-1). `-mp / --metadata-products` appears at a
                # THIRD raise site — effectiveMetadataProducts, review finding R-8:
                # "The selected rule package(s) declare a CDISC Library standard that
                # cannot be resolved." That is a CORPUS fault, deliberately fail-loud
                # under Q1, and three packages of the corpus pinned at the time hit it
                # (the sendig-dart-1-2 packages declared sendig/dart-1-2, which CDISC
                # never published). Matching the remedy sentence counted
                # those three corpus defects as legitimate refusals and exited 0 with
                # `succeeded >= 1` satisfied by a single package — the very swallow this
                # split was written to end, one message further in.
                *'declare more than one TIG leg'*|*'declare no CDISC Library standard'*)
                    refused="$refused
smoke:   rules-$p.json: $(run_message "$st")"
                    nrefused=$((nrefused + 1)) ;;
                *)
                    other="$other
smoke:   rules-$p.json: $(run_message "$st")"
                    nother=$((nother + 1)) ;;
            esac ;;
        *'"status":"CANCELLED"'*)
            fail "the check run for rule package '$p' was CANCELLED — nothing cancels it here, so the service aborted the run itself: $st" ;;
        *)
            fail "the check run for rule package '$p' never reached a terminal status within 120s: ${st:-no status}" ;;
    esac
done

if [ "$nbad" -gt 0 ]; then
    echo "smoke: the rule corpus staged in this bundle is UNLOADABLE by the engine in lib/." >&2
    echo "smoke: $nbad of $npkg packages offered by /api/meta/run-options failed to load:" >&2
    echo "${bad#$'\n'}" >&2
    echo "smoke:" >&2
    echo "smoke: This is a corpus/engine version mismatch, not a packaging fault: the corpus" >&2
    echo "smoke: pinned by dependency.corej-rules.tag in pom.xml predates the engine this" >&2
    echo "smoke: bundle links. Release cumba-oss-corej-rules FIRST, then bump the pin (its" >&2
    echo "smoke: tag, both sha256 properties and corej.rules.draft-packages) here and in" >&2
    echo "smoke: every other bundle that pins the corpus." >&2
    echo "smoke: ⛔ Do NOT weaken this probe to get a green build." >&2
    exit 1
fi

# ⛔ A run that failed for a reason this probe cannot name. The corpus parsed — so
# this is not the version-mismatch story above — and the run then broke somewhere
# the probe does not model. Fatal on purpose: `succeeded >= 1` below is satisfied by
# ONE package, so without this every other package could be failing and the script
# would still print OK.
if [ "$nother" -gt 0 ]; then
    echo "smoke: $nother of $npkg rule packages LOADED but their check run FAILED for a" >&2
    echo "smoke: reason this probe does not recognise — neither a corpus binding failure" >&2
    echo "smoke: nor a metadata-product selection refusal:" >&2
    echo "${other#$'\n'}" >&2
    echo "smoke:" >&2
    echo "smoke: The rules parsed, so this is NOT the corpus/engine version mismatch above." >&2
    echo "smoke: Look at the bundle's own runtime: the staged dataset, the metadata store," >&2
    echo "smoke: the report writer, the session's working directory." >&2
    echo "smoke: ⛔ Do NOT widen the refusal signature to make this go away." >&2
    exit 1
fi

# ⛔ RECORDED load errors: every package loaded and its run succeeded, and the engine
# still reported rules it could not use. Fatal for the same reason as the thrown kind
# above — a dead rule is a check the operator believes ran.
if [ "$nrecorded" -gt 0 ]; then
    echo "smoke: the rule corpus staged in this bundle LOADS, but the engine in lib/ RECORDED" >&2
    echo "smoke: rules it could not use: $nrecorded of $npkg packages report EXECUTION_ERROR rows" >&2
    echo "smoke: (a rule load error is filed on the rule and reported, never thrown):" >&2
    echo "${recorded#$'\n'}" >&2
    echo "smoke:" >&2
    echo "smoke: On this one-row dataset the published corpus has none. A load error means the" >&2
    echo "smoke: corpus and the engine disagree on the rule language, so the remedy is the same" >&2
    echo "smoke: as for an unloadable package above; a run-time error is an engine defect." >&2
    echo "smoke: ⛔ Do NOT weaken this probe to get a green build." >&2
    exit 1
fi

# ⛔ ACCOUNTING (see the ⛔ note above the loop): every offered package either succeeded
# or was refused with a named refusal.
if [ $((succeeded + nrefused)) -ne "$npkg" ]; then
    echo "smoke: $succeeded succeeded + $nrefused refused != $npkg packages offered by /api/meta/run-options — a run outcome this probe neither failed nor counted." >&2
    exit 1
fi

# ⚠ NON-VACUITY across the corpus: the recorded-load-error check read at least one
# rule row, so an empty Rules_Report everywhere cannot pass for a clean corpus.
[ "$nrows" -ge 1 ] \
    || fail "all $npkg packages ran, but their reports carry no rule rows at all — the recorded-load-error check read nothing"

# ⛔ EXECUTION, not merely loading. Every package parsed; at least one must have run
# to completion over the staged dataset, or the service never reached a rule.
[ "$succeeded" -ge 1 ] \
    || fail "all $npkg rule packages loaded, but NONE completed a run over the staged dataset — the service never reached rule execution"

# ⚠ `|| true`: under `set -euo pipefail` a grep that matches nothing exits 1, pipefail
# propagates it, and the assignment fails — the script would then exit 1 printing
# NOTHING, after every real assertion had passed. This count is a cosmetic banner
# number; it must never be able to fail the test.
pkgcount="$(printf '%s' "$opts" | grep -o '"ruleCount":' | wc -l)" || true

echo "smoke: OK — $BUNDLE started on the externally-configured port $port"
echo "smoke: /api/info -> $body"
echo "smoke:      and /api/meta/run-options offers $pkgcount rule packages,"
echo "smoke:      ALL $npkg of which the engine loaded; $succeeded ran to completion"
# The header promises the third outcome is "recorded AND reported". Reported here.
if [ "$nrefused" -gt 0 ]; then
    echo "smoke:      $nrefused refused with a named selection refusal (loaded, NOT run):"
    echo "${refused#$'\n'}"
fi
