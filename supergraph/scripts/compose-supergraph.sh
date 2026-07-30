#!/bin/sh
set -eu

mkdir -p .tmp
scratch=$(mktemp -d .tmp/compose.XXXXXX)
published=0
backed_up=0

cleanup() {
  if [ "$published" -eq 0 ] && [ "$backed_up" -eq 1 ]; then
    cp "$scratch/products.backup" schemas/products.graphql
    cp "$scratch/pricing.backup" schemas/pricing.graphql
    cp "$scratch/supergraph.backup" router/supergraph.graphql
  fi
  rm -rf "$scratch"
}
trap cleanup EXIT HUP INT TERM

scripts/export-subgraphs.sh "$scratch/schemas"
sed "s#/work/schemas/#/work/$scratch/schemas/#g" router/supergraph.yaml > "$scratch/supergraph.yaml"
docker compose --profile tooling run --rm rover \
  supergraph compose --config "/work/$scratch/supergraph.yaml" > "$scratch/supergraph.graphql"
test -s "$scratch/supergraph.graphql"

cp schemas/products.graphql "$scratch/products.backup"
cp schemas/pricing.graphql "$scratch/pricing.backup"
cp router/supergraph.graphql "$scratch/supergraph.backup"
backed_up=1

cp "$scratch/schemas/products.graphql" schemas/products.graphql
cp "$scratch/schemas/pricing.graphql" schemas/pricing.graphql
cp "$scratch/supergraph.graphql" router/supergraph.graphql
published=1
