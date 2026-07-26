#!/bin/sh
set -eu

mkdir -p .tmp
scratch=$(mktemp -d .tmp/check.XXXXXX)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM

scripts/export-subgraphs.sh "$scratch/schemas"
cmp schemas/products.graphql "$scratch/schemas/products.graphql"
cmp schemas/pricing.graphql "$scratch/schemas/pricing.graphql"

sed "s#/work/schemas/#/work/$scratch/schemas/#g" \
  router/supergraph.yaml > "$scratch/supergraph.yaml"

docker compose --profile tooling run --rm rover \
  supergraph compose --config "/work/$scratch/supergraph.yaml" \
  > "$scratch/supergraph-first.graphql"
docker compose --profile tooling run --rm rover \
  supergraph compose --config "/work/$scratch/supergraph.yaml" \
  > "$scratch/supergraph-second.graphql"

cmp "$scratch/supergraph-first.graphql" "$scratch/supergraph-second.graphql"
cmp router/supergraph.graphql "$scratch/supergraph-first.graphql"
