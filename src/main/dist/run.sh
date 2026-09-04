#!/bin/sh
# Convenience launcher for the coreJ REST distribution.
#
# Resolves its own directory so the bundle is fully relocatable: the sidecar
# cumba-oss-corej-rest.conf and every ${bootstrap.dir} reference inside it resolve
# against the bundle, not against the working directory of whatever supervises
# the service.
#
# JAVA_OPTS is passed to the JVM (heap, GC, -D overrides); it is deliberately
# unquoted so multiple options word-split, which is the conventional contract.
# A server usually wants at least a heap setting:
#
#     JAVA_OPTS="-Xmx8g" ./run.sh
#
# Arguments are passed through to Spring Boot, so the usual overrides work:
#
#     ./run.sh --server.port=9090
# ⚠ RESOLVE SYMLINKS FIRST. `ln -s /opt/corej-1.2.3/run.sh /usr/local/bin/corej`
# is the normal way to put this on PATH, and a bare dirname "$0" would then resolve
# to /usr/local/bin — where the launcher jar is not. Walk the link chain to the real
# script before taking its directory.
#
# ⚠ Every cd is checked. Without `|| exit`, a failed cd leaves DIR empty and the exec
# below becomes `java -jar /cumba-oss-corej-rest.jar`, whose error names a path that was never involved.
src=$0
while [ -L "$src" ]; do
    dir=$(CDPATH= cd -- "$(dirname -- "$src")" && pwd) || exit 1
    src=$(readlink -- "$src") || exit 1
    case $src in
        /*) ;;
        *)  src=$dir/$src ;;
    esac
done
DIR=$(CDPATH= cd -- "$(dirname -- "$src")" && pwd) || exit 1

exec java ${JAVA_OPTS} -jar "$DIR/cumba-oss-corej-rest.jar" "$@"
