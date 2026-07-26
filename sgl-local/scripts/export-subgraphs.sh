#!/bin/sh
set -eu

output_dir=${1:?usage: export-subgraphs.sh OUTPUT_DIR}
mkdir -p "$output_dir"
products_tmp="$output_dir/products.graphql.tmp"
pricing_tmp="$output_dir/pricing.graphql.tmp"

docker compose --profile tooling run --rm rover \
  subgraph introspect http://products:8080/graphql > "$products_tmp"
docker compose --profile tooling run --rm rover \
  subgraph introspect http://pricing:8080/graphql > "$pricing_tmp"

test -s "$products_tmp"
test -s "$pricing_tmp"
grep -q 'interface CatalogItem' "$products_tmp"
grep -q '@interfaceObject' "$pricing_tmp"
grep -q 'federation/v2.8' "$products_tmp"
grep -q 'federation/v2.8' "$pricing_tmp"

mv "$products_tmp" "$output_dir/products.graphql"
mv "$pricing_tmp" "$output_dir/pricing.graphql"
