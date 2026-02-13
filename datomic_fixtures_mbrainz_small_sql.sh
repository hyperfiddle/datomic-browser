#!/usr/bin/env bash

# Downloads Datomic Pro + mbrainz-small, starts PostgreSQL (Docker), and loads mbrainz into SQL storage.
# After this, use ./run_datomic_sql.sh to start the transactor.

set -uo pipefail

PG_PORT=5433
PG_CONTAINER=datomic-postgres
PG_USER=postgres
PG_PASSWORD=postgres

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

# --- PostgreSQL ---

if docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
    info "PostgreSQL already running"
else
    docker rm -f $PG_CONTAINER 2>/dev/null || true
    info "Starting PostgreSQL on port $PG_PORT"
    docker run -d --name $PG_CONTAINER \
        -p ${PG_PORT}:5432 \
        -e POSTGRES_PASSWORD=$PG_PASSWORD \
        postgres:16 >/dev/null
fi

info "Waiting for PostgreSQL..."
while ! docker exec $PG_CONTAINER pg_isready -q 2>/dev/null; do sleep 0.5; done

# --- Create Datomic database/table/user (idempotent) ---

docker exec $PG_CONTAINER psql -U $PG_USER -tc "SELECT 1 FROM pg_database WHERE datname = 'datomic'" | grep -q 1 || \
    docker exec $PG_CONTAINER psql -U $PG_USER -c "CREATE DATABASE datomic;"

docker exec $PG_CONTAINER psql -U $PG_USER -d datomic -tc "SELECT 1 FROM pg_tables WHERE tablename = 'datomic_kvs'" | grep -q 1 || \
    docker exec $PG_CONTAINER psql -U $PG_USER -d datomic -c \
        "CREATE TABLE datomic_kvs (id text NOT NULL, rev integer, map text, val bytea, PRIMARY KEY (id));"

docker exec $PG_CONTAINER psql -U $PG_USER -tc "SELECT 1 FROM pg_roles WHERE rolname = 'datomic'" | grep -q 1 || \
    docker exec $PG_CONTAINER psql -U $PG_USER -d datomic -c \
        "CREATE USER datomic WITH PASSWORD 'datomic'; GRANT ALL ON TABLE datomic_kvs TO datomic;"

# --- Import mbrainz into SQL storage via a short-lived transactor ---

info "Importing the mbrainz dataset into SQL storage"
while PORT=$((RANDOM % 60000 + 5000)); lsof -i:$PORT >/dev/null 2>&1; do :; done

cat > state/datomic-pro/config/sql-transactor.properties <<EOF
protocol=sql
host=localhost
port=$PORT

sql-url=jdbc:postgresql://localhost:${PG_PORT}/datomic
sql-user=datomic
sql-password=datomic
sql-driver-class=org.postgresql.Driver

memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
EOF

./state/datomic-pro/bin/transactor config/sql-transactor.properties >>state/sql-transactor.log 2>&1 &
datomic_transactor_pid=$!

info "Waiting for Datomic to start on port $PORT..."
while ! nc -z localhost $PORT 2>/dev/null; do sleep 0.5; done

state/datomic-pro/bin/datomic restore-db \
    "file:$(pwd)/state/mbrainz-1968-1973 datomic:sql://mbrainz-1968-1973?jdbc:postgresql://localhost:${PG_PORT}/datomic?user=datomic&password=datomic"
kill $datomic_transactor_pid

info "Cleaning up mbrainz backup dir"
rm -rf "state/${mbrainz_backup_dir}"

info "Sample dataset ready (SQL). Run ./run_datomic_sql.sh to start the transactor."
