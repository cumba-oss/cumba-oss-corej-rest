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
for tool in cat env grep java ls mkdir mktemp rm seq sleep sort tail timeout wc; do
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

case "$body" in
    *corej-cdisc-rest*) ;;
    *) fail "unexpected /api/info payload: $body" ;;
esac

echo "smoke: OK — $BUNDLE started on the externally-configured port $port"
echo "smoke: /api/info -> $body"
