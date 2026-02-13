#!/usr/bin/env bash

# Starts Cassandra (Docker) + Datomic cass3 transactor.
# Run ./datomic_fixtures_mbrainz_small_cass3.sh first to load sample data.
#
# Then start datomic-browser with:
#   clj -X:dev dev/-main :datomic-uri '"datomic:cass3://localhost:9042/datomic2.datomic2/*"'
#
# Cleanup:
#   docker rm -f datomic-cassandra
#   lsof -ti:4337 | xargs kill  # kill the transactor process on port 4337

set -euo pipefail

CASS_PORT=9042
TRANSACTOR_PORT=4337
CASS_CONTAINER=datomic-cassandra

function fail { echo "ERROR: $*" >&2; exit 1; }

# --- Preflight checks ---

[ -d "state/datomic-pro" ] || fail "state/datomic-pro not found. Run ./datomic_fixtures_mbrainz_small_cass3.sh first."
docker info >/dev/null 2>&1 || fail "Docker is not running."
nc -z localhost $TRANSACTOR_PORT 2>/dev/null && fail "Port $TRANSACTOR_PORT already in use."

# --- Cassandra ---

if docker ps --format '{{.Names}}' | grep -q "^${CASS_CONTAINER}$"; then
    echo "Cassandra already running"
else
    docker rm -f $CASS_CONTAINER 2>/dev/null || true
    echo "Starting Cassandra on port $CASS_PORT (this may take a minute)"
    docker run -d --name $CASS_CONTAINER \
        -p ${CASS_PORT}:9042 \
        cassandra:4 >/dev/null
fi

echo "Waiting for Cassandra to accept CQL connections..."
while ! docker exec $CASS_CONTAINER cqlsh -e "DESCRIBE CLUSTER" >/dev/null 2>&1; do sleep 2; done

# --- Create keyspace and table (idempotent) ---

docker exec $CASS_CONTAINER cqlsh -e "CREATE KEYSPACE IF NOT EXISTS datomic2 WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}; CREATE TABLE IF NOT EXISTS datomic2.datomic2 (id2 text PRIMARY KEY, rev bigint, map text, val blob, chunks int);"

# --- Transactor config ---

cat > state/datomic-pro/config/cass3-transactor.properties <<EOF
protocol=cass3
host=localhost
port=${TRANSACTOR_PORT}

cassandra-host=localhost
cassandra-port=${CASS_PORT}
cassandra-table=datomic2.datomic2

memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
EOF

# --- Start transactor ---

./state/datomic-pro/bin/transactor config/cass3-transactor.properties >>state/cass3-transactor.log 2>&1 &

sleep 1
echo "Datomic (cass3) is starting in background. You can proceed."
