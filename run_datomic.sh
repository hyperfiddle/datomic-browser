#!/bin/bash
set -e
nc -z localhost 4334 2>/dev/null && { echo "Port 4334 already in use"; exit 1; } || true

set -eux -o pipefail

# Without explicit bindAddress h2 will bind to 0.0.0.0 on fly for large mbrainz, but localhost for small mbrainz. No idea why.
export JAVA_OPTS='-Dh2.bindAddress=localhost -XX:+UseG1GC -XX:MaxGCPauseMillis=50'
./state/datomic-pro/bin/transactor config/samples/dev-transactor-template.properties >>state/datomic.log 2>&1 &
