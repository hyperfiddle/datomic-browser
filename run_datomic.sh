#!/usr/bin/env bash

# * **Watch out:** the Datomic command works only from the datomic_browser directory precisely. The `transactor` java process will resolve the config file path relative to the java resource path, or something. This is a common gotcha!
# * **Nix users**: user reports, "The transactor and the datomic bash files begin with an invalid shebang that only matters to nix people, see https://www.reddit.com/r/NixOS/comments/k8ja54/nixos_running_scripts_problem/"


set -e
nc -z localhost 4334 2>/dev/null && { echo "Port 4334 already in use"; exit 1; } || true

set -eux -o pipefail

# Without explicit bindAddress h2 will bind to 0.0.0.0 on fly for large mbrainz, but localhost for small mbrainz. No idea why.
export JAVA_OPTS='-Dh2.bindAddress=localhost -XX:+UseG1GC -XX:MaxGCPauseMillis=50'
./state/datomic-pro/bin/transactor config/samples/dev-transactor-template.properties >>state/datomic.log 2>&1 &

set +x
sleep 1
echo "Datomic is starting in background. You can proceed."