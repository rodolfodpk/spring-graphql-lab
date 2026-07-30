#!/bin/sh
set -eu

deadline=90
elapsed=0
while [ "$elapsed" -lt "$deadline" ]; do
  if docker compose ps --format json | grep -q '"Health":"healthy"'; then
    unhealthy=$(docker compose ps --format json | grep -vc '"Health":"healthy"' || true)
    if [ "$unhealthy" -eq 0 ]; then
      exit 0
    fi
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done

docker compose ps
echo "Services did not become healthy within ${deadline}s" >&2
exit 1
