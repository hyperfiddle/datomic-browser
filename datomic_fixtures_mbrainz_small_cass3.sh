#!/usr/bin/env bash

# Downloads Datomic Pro + mbrainz-small, starts Cassandra (Docker), and loads mbrainz into Cassandra storage.
# After this, use ./run_datomic_cass3.sh to start the transactor.

set -uo pipefail

CASS_PORT=9042
CASS_CONTAINER=datomic-cassandra

function fail { echo "$@"; exit 1; }
function info { echo "[INFO] $(date +"%T.%3N") $*"; }
function downloadAsNeeded { curl "$1" -O -C - || fail "Failed to download $1"; }

# --- Prerequisites ---

docker info >/dev/null 2>&1 || fail "Docker is not running."

# --- Download Datomic Pro + mbrainz ---

mkdir -p state
pushd state

info "Downloading datomic-pro"
downloadAsNeeded https://datomic-pro-downloads.s3.amazonaws.com/1.0.7469/datomic-pro-1.0.7469.zip
info "Extracting datomic-pro"
if [ ! -d "datomic-pro" ]; then
    unzip -q datomic-pro-1.0.7469.zip || fail "Failed to unzip datomic-pro"
    mv datomic-pro-1.0.7469 datomic-pro
fi

info "Downloading mbrainz dataset"
mbrainz=https://s3.amazonaws.com/mbrainz/datomic-mbrainz-1968-1973-backup-2017-07-20.tar
mbrainz_backup_dir=datomic-mbrainz-1968-1973-backup-2017-07-20
downloadAsNeeded "$mbrainz"
info "Extracting mbrainz dataset"
if [ ! -d "$mbrainz_backup_dir" ]; then
    tar -xf "${mbrainz##*/}" || fail "failed to untar mbrainz dataset"
fi

popd

# --- Cassandra ---

if docker ps --format '{{.Names}}' | grep -q "^${CASS_CONTAINER}$"; then
    info "Cassandra already running"
else
    docker rm -f $CASS_CONTAINER 2>/dev/null || true
    info "Starting Cassandra on port $CASS_PORT (this may take a minute)"
    docker run -d --name $CASS_CONTAINER \
        -p ${CASS_PORT}:9042 \
        cassandra:4 >/dev/null
fi

info "Waiting for Cassandra to accept CQL connections..."
while ! docker exec $CASS_CONTAINER cqlsh -e "DESCRIBE CLUSTER" >/dev/null 2>&1; do sleep 2; done

# --- Create keyspace and table (idempotent) ---

docker exec $CASS_CONTAINER cqlsh -e "CREATE KEYSPACE IF NOT EXISTS datomic2 WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}; CREATE TABLE IF NOT EXISTS datomic2.datomic2 (id2 text PRIMARY KEY, rev bigint, map text, val blob, chunks int);"

# --- Import mbrainz into Cassandra storage via a short-lived transactor ---

info "Importing the mbrainz dataset into Cassandra storage"
while PORT=$((RANDOM % 60000 + 5000)); lsof -i:$PORT >/dev/null 2>&1; do :; done

cat > state/datomic-pro/config/cass3-transactor.properties <<EOF
protocol=cass3
host=localhost
port=$PORT

cassandra-host=localhost
cassandra-port=${CASS_PORT}
cassandra-table=datomic2.datomic2

memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
EOF

./state/datomic-pro/bin/transactor config/cass3-transactor.properties >>state/cass3-transactor.log 2>&1 &
datomic_transactor_pid=$!

info "Waiting for Datomic to start on port $PORT..."
while ! nc -z localhost $PORT 2>/dev/null; do sleep 0.5; done

state/datomic-pro/bin/datomic restore-db \
    "file:$(pwd)/state/mbrainz-1968-1973 datomic:cass3://localhost:${CASS_PORT}/datomic2.datomic2/mbrainz-1968-1973"
kill $datomic_transactor_pid

info "Cleaning up mbrainz backup dir"
rm -rf "state/${mbrainz_backup_dir}"

info "Sample dataset ready (Cassandra). Run ./run_datomic_cass3.sh to start the transactor."
