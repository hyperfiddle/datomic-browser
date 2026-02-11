#!/usr/bin/env bash

# Starts PostgreSQL (Docker) + Datomic SQL transactor.
# Run ./datomic_fixtures_mbrainz_small_sql.sh first to load sample data.
#
# Then start datomic-browser with:
#   clj -X:dev dev/-main :datomic-uri '"datomic:sql://*?jdbc:postgresql://localhost:5433/datomic?user=datomic&password=datomic"'
#
# Cleanup:
#   docker rm -f datomic-postgres
#   lsof -ti:4336 | xargs kill  # kill the transactor process on port 4336

set -euo pipefail

PG_PORT=5433
TRANSACTOR_PORT=4336
PG_CONTAINER=datomic-postgres
PG_USER=postgres
PG_PASSWORD=postgres

function fail { echo "ERROR: $*" >&2; exit 1; }

# --- Preflight checks ---

[ -d "state/datomic-pro" ] || fail "state/datomic-pro not found. Run ./datomic_fixtures_mbrainz_small_sql.sh first."
docker info >/dev/null 2>&1 || fail "Docker is not running."
nc -z localhost $TRANSACTOR_PORT 2>/dev/null && fail "Port $TRANSACTOR_PORT already in use."

# --- PostgreSQL ---

if docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
    echo "PostgreSQL already running"
else
    docker rm -f $PG_CONTAINER 2>/dev/null || true
    echo "Starting PostgreSQL on port $PG_PORT"
    docker run -d --name $PG_CONTAINER \
        -p ${PG_PORT}:5432 \
        -e POSTGRES_PASSWORD=$PG_PASSWORD \
        postgres:16 >/dev/null
fi

echo "Waiting for PostgreSQL..."
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

# --- Transactor config ---

cat > state/datomic-pro/config/sql-transactor.properties <<EOF
protocol=sql
host=localhost
port=${TRANSACTOR_PORT}

sql-url=jdbc:postgresql://localhost:${PG_PORT}/datomic
sql-user=datomic
sql-password=datomic
sql-driver-class=org.postgresql.Driver

memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
EOF

# --- Start transactor ---

./state/datomic-pro/bin/transactor config/sql-transactor.properties >>state/sql-transactor.log 2>&1 &

sleep 1
echo "Datomic (sql) is starting in background. You can proceed."
