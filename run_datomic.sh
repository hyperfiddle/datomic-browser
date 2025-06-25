#!/bin/sh
set -e
nc -z localhost 4334 2>/dev/null && { echo "Port 4334 already in use"; exit 1; } || true

set -eux -o pipefail

./state/datomic-pro/bin/transactor config/samples/dev-transactor-template.properties >>state/datomic.log 2>&1 &
