#!/bin/sh
set -eu

response=$(curl --fail --silent \
  -H 'Content-Type: application/json' \
  --data '{"query":"{ products { id name price } }"}' \
  http://127.0.0.1:4000/)

echo "$response" | grep -q '"p-100"'
echo "$response" | grep -q '"99.90"'
echo "$response"
