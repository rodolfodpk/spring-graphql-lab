#!/bin/sh
set -eu

case "${1:-}" in
  stop) docker compose stop pricing ;;
  start)
    docker compose start pricing
    ;;
  *) echo "usage: toggle-pricing.sh {stop|start}" >&2; exit 2 ;;
esac
