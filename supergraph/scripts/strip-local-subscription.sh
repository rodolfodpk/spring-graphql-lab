#!/bin/sh
# Removes the pricing subgraph's locally-served subscription surface from an introspected SDL
# before it is handed to composition.
#
# Why: the subgraph serves Subscription.priceChanges over WebSocket and SSE, but routing
# subscriptions through Apollo Router requires connecting the Router to GraphOS with credentials.
# This lab is deliberately account-free and CI runs unauthenticated, so the subscription stays a
# direct-to-subgraph feature and never reaches the supergraph.
#
# Three constructs have to go, not two. Dropping only the type definitions would leave
# "subscription: Subscription" dangling in the schema root block and composition would fail.
#
#   1. the "subscription: Subscription" line inside the schema { ... } root block
#   2. type Subscription { ... }
#   3. type PriceChange { ... }
#
# Definitions are matched by name and consumed by brace depth rather than by blank-line paragraphs,
# so a change in how graphql-java lays out the SDL cannot silently defeat this.
set -eu

# Pricing-specific: the integrity guards at the bottom assert the pricing surface survived.
file=${1:?usage: strip-local-subscription.sh PRICING_SDL_FILE}
tmp="$file.stripped"
trap 'rm -f "$tmp"' EXIT

awk -v drop="Subscription,PriceChange" '
function flushHeld(   i) {
    for (i = 1; i <= heldCount; i++) {
        print held[i]
    }
    heldCount = 0
}

BEGIN {
    split(drop, names, ",")
    for (i in names) {
        dropped[names[i]] = 1
    }
    skipDepth = 0
    inSchema = 0
    inDescription = 0
    heldCount = 0
    swallowBlank = 0
}

# Consume the body of a definition being dropped, counting braces to find its end.
skipDepth > 0 {
    skipDepth += gsub(/\{/, "{") - gsub(/\}/, "}")
    if (skipDepth <= 0) {
        skipDepth = 0
        swallowBlank = 1
    }
    next
}

# A dropped definition leaves the blank line that separated it behind. Take one, so the result is
# byte-identical to an SDL that never declared the type.
swallowBlank == 1 {
    swallowBlank = 0
    if ($0 ~ /^[[:space:]]*$/) {
        next
    }
}

# Hold description blocks: they belong to whatever definition comes next, which may be dropped.
inDescription == 1 {
    held[++heldCount] = $0
    if ($0 ~ /"""[[:space:]]*$/) {
        inDescription = 0
    }
    next
}
/^[[:space:]]*"""/ {
    held[++heldCount] = $0
    if ($0 !~ /""".*"""[[:space:]]*$/) {
        inDescription = 1
    }
    next
}

# The schema root block: drop the subscription root, keep everything else.
/^schema[[:space:]@{]/ {
    inSchema = 1
    flushHeld()
    print
    next
}
inSchema == 1 && /^[[:space:]]*subscription:[[:space:]]/ {
    next
}
inSchema == 1 && /^\}/ {
    inSchema = 0
    print
    next
}

# Top-level type definitions.
/^type[[:space:]]+[A-Za-z_][A-Za-z0-9_]*/ {
    name = $2
    if (name in dropped) {
        heldCount = 0
        skipDepth = gsub(/\{/, "{") - gsub(/\}/, "}")
        if (skipDepth <= 0) {
            skipDepth = 0
            swallowBlank = 1
        }
        next
    }
}

{
    flushHeld()
    print
}

END {
    flushHeld()
}
' "$file" > "$tmp"

# Guard: the published SDL must carry none of the local-only surface.
if grep -qE '^[[:space:]]*subscription:|^type Subscription|^type PriceChange' "$tmp"; then
    echo "strip-local-subscription.sh: local-only surface survived the filter in $file" >&2
    exit 1
fi

# Guard: everything the supergraph does depend on must still be intact.
for required in '@interfaceObject' '^type CatalogItem' '^type Quote'; do
    if ! grep -qE "$required" "$tmp"; then
        echo "strip-local-subscription.sh: $file lost '$required' -- is this the pricing SDL?" >&2
        exit 1
    fi
done

mv "$tmp" "$file"
