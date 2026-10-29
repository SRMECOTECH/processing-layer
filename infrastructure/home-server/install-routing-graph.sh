#!/bin/sh
# Installs a routing graph exported on the build laptop (export-routing-graph.ps1) into the home server's
# Docker volume, then restarts the engine on it. Also the way to update the map: the old graph keeps serving until
# the new one is unpacked, then a restart (about a minute) switches over.
#
#   sh infrastructure/home-server/install-routing-graph.sh ~/asia-india-graph.tar
#
# Needs the file's .sha256 next to it, and about the graph's size free twice (the tar and the unpacked graph);
# delete the tar afterwards.
set -eu

TAR=$(realpath "${1:?usage: sh $0 <region>-graph.tar}")
VOLUME="${2:-gps-home-server_gps-routing-data}"
DIR=$(dirname "$TAR")
FILE=$(basename "$TAR")
NAME=${FILE%-graph.tar}
COMPOSE="$(dirname "$(realpath "$0")")/docker-compose.yml"

echo "Checking $FILE ..."
(cd "$DIR" && sha256sum -c "$FILE.sha256")

docker volume create "$VOLUME" >/dev/null
docker run --rm -v "$VOLUME:/data" -v "$DIR:/in:ro" alpine:3.20 sh -euc "
    mkdir -p /data/graph-cache
    rm -rf /data/graph-cache/$NAME.new && mkdir /data/graph-cache/$NAME.new
    tar -C /data/graph-cache/$NAME.new --strip-components=1 -xf /in/$FILE
    rm -rf /data/graph-cache/$NAME.old
    if [ -d /data/graph-cache/$NAME ]; then mv /data/graph-cache/$NAME /data/graph-cache/$NAME.old; fi
    mv /data/graph-cache/$NAME.new /data/graph-cache/$NAME
    rm -rf /data/graph-cache/$NAME.old
    chown -R 1001 /data
    du -sh /data/graph-cache/$NAME"

echo "Installed $NAME. Restarting the engine on it ..."
docker compose -f "$COMPOSE" restart graphhopper 2>/dev/null || docker compose -f "$COMPOSE" up -d graphhopper
echo "Watch it load: docker compose -f $COMPOSE logs -f graphhopper   (ready when http://localhost:8989/health says OK)"
