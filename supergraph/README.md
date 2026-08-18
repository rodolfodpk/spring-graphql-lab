# Supergraph

Composes the two subgraphs into one supergraph, runs Apollo Router in front of
them, and owns the end-to-end tests:

- **Products** owns the catalog and runs on port `8081`.
- **Pricing** adds commercial fields and runs on port `8082`.
- **Apollo Router** exposes the federated client endpoint on port `4000`.

All data is deterministic and held in plain Java collections. There is no
database or cloud dependency.

## Repository layout

Each subgraph exists twice — once per stack — over one shared domain model:

```text
spring-graphql-lab/
├── pom.xml                     aggregator only; root mvnw is the sole entry point
├── Makefile                    delegates to supergraph/Makefile
├── model/                      rdpk:lab-model, pure Java 21
├── reactive/
│   ├── products-subgraph/      WebFlux + Reactor Netty
│   └── pricing-subgraph/
├── servlet/
│   ├── products-subgraph/      Spring MVC + Tomcat
│   └── pricing-subgraph/
└── supergraph/
```

Run all commands in this README from `supergraph`. The root `Makefile` forwards
every target here, so `make up STACK=servlet` works from the repository root too;
this file stays authoritative because the scripts, `compose.yaml`, and the Rover
volume mounts are all relative to this directory.

## Choosing a stack

Every target below takes an optional `STACK`, defaulting to `reactive`:

```sh
make up                 # reactive
make up STACK=servlet   # servlet
```

`STACK` selects which Dockerfile Compose builds and which Maven modules the test
targets run. Service names, host ports, Router config, the introspected SDL, and
the E2E suite are identical either way — only one pair runs at a time, and both
bind `8081`/`8082`. An unrecognised value fails immediately rather than silently
falling back.

Because both stacks share service names, ports, and image tags, run `make down`
before switching. `make verify-all` does this for you.

## Prerequisites

- Java 21 or newer
- Docker Desktop, or another Docker installation with Compose v2
- Make
- curl

Confirm the main tools are available:

```sh
java -version
docker version
docker compose version
make --version
curl --version
```

The Maven wrapper and all required Maven versions are included in the
repositories.

## Make targets

Every target accepts `STACK=reactive|servlet`, defaulting to `reactive`, plus two
runtime knobs: `THREADS=platform|virtual` and `DELAY=0ms`. All of them also work
from the repository root, which forwards here. See
[Comparing the three configurations](#comparing-the-three-configurations).

| Target | What it does |
| --- | --- |
| `help` | Lists these targets. |
| `test` | `lab-model` plus the selected stack's subgraph tests, then the supergraph module's own unit tests. |
| `build` | Builds the two subgraph images without starting anything. |
| `subgraphs` | Builds and starts Products and Pricing, then waits for both to report healthy. Does not start Router. |
| `export-schemas` | Introspects both running subgraphs and rewrites `schemas/*.graphql`. Strips the local subscription from Pricing's SDL. |
| `compose` | Runs Rover composition and publishes `schemas/*.graphql` and `router/supergraph.graphql`. Use after an intentional schema change. |
| `compose-check` | Re-introspects and composes twice, comparing against the checked-in artifacts. Fails on drift or non-determinism. Run by `up`. |
| `up` | `subgraphs` + `compose-check` + Router, all healthy. The normal way to start the lab. |
| `smoke` | One federated query through Router, asserting a known id and price. |
| `e2e` | The 10 federated end-to-end tests. Requires the stack to be already running. |
| `verify` | The full pipeline for one stack: test, up, smoke, e2e, then teardown. |
| `verify-all` | `verify` for both stacks, tearing down before each. The parity proof. |
| `down` | Stops and removes the containers, including the Rover tooling profile. |
| `clean` | `mvn clean` across the whole reactor. |

`build`, `subgraphs`, and `export-schemas` are the granular steps `up` and
`compose` are built from — useful when diagnosing a single stage, rarely needed
otherwise.

## Comparing the three configurations

`THREADS` and `DELAY` are deliberately *orthogonal* to `STACK`, because neither is
a code variant — no source differs between platform and virtual threads, and the
delay is a property. Both are passed to the containers as environment, so
switching them needs no rebuild:

```sh
make up STACK=reactive                          # Netty, event loop
make up STACK=servlet                           # Tomcat, platform threads
make up STACK=servlet THREADS=virtual           # Tomcat, virtual threads
```

Three runnable configurations out of two code trees. `THREADS` is meaningful only
on the servlet stack; the reactive stack has no thread-per-request model for
virtual threads to replace, and ignores it.

**`DELAY` is what makes the comparison mean anything.** With no I/O to overlap,
all three configurations are indistinguishable — that is the honest result this
lab reports elsewhere, and no amount of load will change it. Give the
repositories a simulated round trip and the models finally diverge:

```sh
make up STACK=servlet THREADS=virtual DELAY=50ms
```

The wait is the same 50ms in each case; what differs is what it costs. The
reactive stack defers a subscription and holds no thread. The servlet stack
blocks its request thread — a platform thread under `THREADS=platform`, a virtual
one under `THREADS=virtual`, which is precisely the tradeoff virtual threads
exist to change.

`DELAY` defaults to `0ms`, so every test, the composition check, and the E2E
suite run exactly as before and remain deterministic. Turn it on only for
comparison runs.

Measuring throughput needs a real load tool — `curl` in a shell loop measures
process startup, not the server.

## Start everything

```sh
cd supergraph
make up
```

`make up` performs the complete startup sequence:

1. Builds and starts both Spring Boot subgraphs.
2. Waits for their health checks.
3. Exports the real Federation SDL from each running subgraph.
4. Checks the exported SDL and composed supergraph for stale changes.
5. Starts a fresh Apollo Router with the verified supergraph.
6. Waits for every service to become healthy.

Inspect the running containers:

```sh
docker compose ps
```

The expected endpoints are:

| Component | URL | Purpose |
| --- | --- | --- |
| Apollo Router | <http://localhost:4000/> | Federated GraphQL API and Apollo Sandbox |
| Products GraphiQL | <http://localhost:8081/graphiql> | Interactive Products subgraph UI |
| Products | <http://localhost:8081/graphql> | Products subgraph GraphQL endpoint |
| Products health | <http://localhost:8081/actuator/health> | Container health |
| Pricing GraphiQL | <http://localhost:8082/graphiql> | Interactive Pricing subgraph UI |
| Pricing | <http://localhost:8082/graphql> | Pricing subgraph GraphQL endpoint |
| Pricing health | <http://localhost:8082/actuator/health> | Container health |

## Test with Apollo Sandbox

Open <http://localhost:4000/> in a browser. Apollo Sandbox should load with the
Router endpoint already available. If it asks for an endpoint, enter:

```text
http://localhost:4000/
```

Use this UI for normal manual testing because it executes queries against the
composed graph. Spring GraphiQL is also enabled at
<http://localhost:8081/graphiql> and <http://localhost:8082/graphiql> for
subgraph-level diagnostics. A subgraph UI exposes only that service's schema;
cross-subgraph queries such as `products { price }` must go through the Router.

### Federated product query

This query starts in Products and asks Pricing to resolve `price`:

```graphql
query ProductsWithPrices {
  products {
    id
    name
    price
  }
}
```

Expected products include `p-100`, `p-200`, and `p-300`. The price of `p-100`
is returned as the JSON string `"99.90"`.

### Polymorphic catalog

`CatalogItem` is an interface implemented by physical and digital products.
Pricing extends that interface with `@interfaceObject`:

```graphql
query PolymorphicCatalog {
  catalog {
    id
    name
    __typename
    price
    priceLabel

    ... on Product {
      weightGrams
    }

    ... on DigitalProduct {
      downloadFormat
    }
  }
}
```

The result contains three `Product` values and one `DigitalProduct`. The
digital product has `downloadFormat: "PDF"` and `priceLabel: "Digital price"`.
Physical entries use `"Physical price"`.

`priceLabel` also demonstrates `@external` and `@requires`: Pricing receives
the Products-owned `category` field internally even though the client did not
request it.

### Quote calculation

```graphql
query ProductQuote {
  product(id: "p-100") {
    id
    name
    quote(input: { quantity: 2 }) {
      unitPrice
      quantity
      subtotal
    }
  }
}
```

The expected subtotal is `"199.80"`.

### Incremental delivery with `@defer`

```graphql
query DeferredCommercialData {
  catalog {
    id
    name
    __typename

    ... @defer(label: "commercial-data") {
      price
      priceLabel
    }
  }
}
```

The Router sends the basic catalog first and the pricing fields in incremental
multipart patches.

## Subscriptions: pricing subgraph only

`pricing-subgraph` serves a live price stream:

```graphql
subscription {
  priceChanges(productId: "p-100") { productId price sequence }
}
```

**This is not available through Router on port 4000, by design.** Routing
subscriptions through Apollo Router requires connecting it to GraphOS with
credentials. Subscriptions are offered on every GraphOS plan including the free
one, so this is an account-and-credentials constraint rather than a paid-tier
one — but this lab's premise is that it runs with no Apollo account, and CI
composes unauthenticated. So `Subscription` and `PriceChange` are stripped from
the SDL handed to composition, and the supergraph has no subscription root.

Subscribe over SSE, which needs no client tooling:

```sh
curl --no-buffer --request POST \
  --header 'Content-Type: application/json' \
  --header 'Accept: text/event-stream' \
  --data '{"query":"subscription { priceChanges(productId: \"p-100\") { productId price sequence } }"}' \
  http://localhost:8082/graphql
```

Five `event:next` frames arrive 200 ms apart — `99.90`, `100.00`, `100.10`,
`100.20`, `100.30` — followed by `event:complete`. Sequence 1 is the unchanged
seeded price. An unknown `productId` returns `PRICE_NOT_FOUND` before the
stream starts.

The same subscription is served over WebSocket at `ws://localhost:8082/graphql`
using the `graphql-ws` protocol. `PricingSubscriptionWebSocketIT` in
`pricing-subgraph` exercises that path against a real server; whether the
bundled GraphiQL build at <http://localhost:8082/graphiql> negotiates the socket
automatically has not been verified, so prefer the test or a dedicated
`graphql-ws` client if you need certainty.

## Test from the command line

Run the built-in smoke query:

```sh
make smoke
```

Or send a query directly:

```sh
curl --silent \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ products { id name price } }"}' \
  http://localhost:4000/
```

To inspect a deferred multipart response:

```sh
curl --no-buffer \
  --header 'Content-Type: application/json' \
  --header 'Accept: multipart/mixed;deferSpec=20220824, application/json' \
  --data '{"query":"{ catalog { id name ... @defer(label: \"commercial-data\") { price priceLabel } } }"}' \
  http://localhost:4000/
```

## Test error behavior

Invalid quote quantities return the stable `VALIDATION_ERROR` extension:

```graphql
query InvalidQuantity {
  product(id: "p-100") {
    quote(input: { quantity: 0 }) {
      subtotal
    }
  }
}
```

An unknown product returns `PRODUCT_NOT_FOUND`:

```graphql
query MissingProduct {
  product(id: "missing") {
    id
    name
  }
}
```

Subgraph error details are enabled for this local tutorial. Production Router
configurations should normally retain Apollo's default error redaction.

## Run the automated tests

Run the model tests plus the selected stack's subgraph tests:

```sh
make test
make test STACK=servlet
```

These go through the root Maven wrapper — the module directories have no wrapper
of their own, because a standalone module build cannot resolve `lab-model`.

Run the end-to-end tests while the stack is running:

```sh
make e2e
```

The E2E suite covers federation, polymorphism, `@requires`, quotes, stable error
codes, restricted Actuator exposure, a Pricing outage and recovery, multipart
`@defer`, the SSE price stream against `pricing-subgraph` directly, and an
assertion that the composed supergraph exposes no subscription root.

Run the complete clean verification workflow:

```sh
make verify                # one stack
make verify STACK=servlet
make verify-all            # both, with a teardown before each
```

This builds and tests the model and the selected stack, starts the containers,
verifies deterministic schema composition, runs the smoke and E2E suites, and
shuts everything down automatically.

`make verify-all` is the parity proof. It runs the whole pipeline against both
stacks, and `compose-check` compares each stack's freshly introspected SDL
against the checked-in `schemas/*.graphql`. Passing twice means the two stacks
publish byte-identical SDL and compose to the identical supergraph — if either
had drifted, the second run would fail on the comparison rather than on a test.

## Schema workflow

Regenerate the checked-in subgraph schemas and supergraph after an SDL change:

```sh
make compose
```

Check that the live schemas and two independent Rover compositions exactly
match the checked-in artifacts:

```sh
make compose-check
```

Export additionally strips the pricing subgraph's local-only subscription
surface via `scripts/strip-local-subscription.sh`, asserting on both sides: the
live `_service { sdl }` must contain `Subscription`, `PriceChange`, and the
`subscription:` root, and the published schema must contain none of them.

The Apollo Router and Rover run locally from pinned container images. Neither
an Apollo account nor a GraphOS API key is required.

## Stop and clean

Stop and remove the containers and Compose network:

```sh
make down
```

Remove Maven build output from all three repositories:

```sh
make clean
```

Restarting a service restores the same four catalog items and prices because
the state is immutable and in memory.

## Troubleshooting

Check container status and logs:

```sh
docker compose ps
docker compose logs products
docker compose logs pricing
docker compose logs router
```

If a port is already occupied, stop the process using `4000`, `8081`, or
`8082`, then run `make up` again.

If Docker reuses stale local state, perform a normal shutdown and restart:

```sh
make down
make up
```

The Router image is a minimal derivative of the pinned Apollo Router image. It
adds only a static BusyBox executable for the container's HTTP health check.
