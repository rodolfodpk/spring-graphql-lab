# Spring GraphQL Lab

[![Java CI](https://github.com/rodolfodpk/spring-graphql-lab/actions/workflows/maven.yml/badge.svg)](https://github.com/rodolfodpk/spring-graphql-lab/actions/workflows/maven.yml)
[![codecov](https://codecov.io/gh/rodolfodpk/spring-graphql-lab/branch/main/graph/badge.svg)](https://codecov.io/gh/rodolfodpk/spring-graphql-lab)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)

Spring GraphQL Lab is a local reference implementation of Apollo Federation
with Spring Boot GraphQL subgraphs. It demonstrates how independently owned
schemas compose into one client-facing graph without a database, cloud account,
or paid Apollo service.

Every subgraph is built twice — once on Spring WebFlux and Reactor Netty, once
on Spring MVC and Tomcat — over one shared, stack-neutral domain model. Both
stacks publish byte-identical SDL, compose into the identical supergraph, and
pass the identical E2E suite, so the difference between them is exactly five
source files.

## Modules

```
model/                       shared domain, pure Java 21
reactive/products-subgraph/  WebFlux + Reactor Netty
reactive/pricing-subgraph/
reactive/inventory-subgraph/   outbound REST + GraphQL (not composed yet)
servlet/products-subgraph/   Spring MVC + Tomcat
servlet/pricing-subgraph/
servlet/inventory-subgraph/    outbound REST + GraphQL (not composed yet)
supergraph/                  composition, Router, E2E
```

| Module | Responsibility |
| --- | --- |
| `model` | `rdpk:lab-model` — the domain types the subgraphs share. No Spring, GraphQL, Reactor, or servlet dependency. |
| `<stack>/products-subgraph` | Owns the catalog and the `CatalogItem` entity interface. |
| `<stack>/pricing-subgraph` | Adds prices, quotes, category-derived labels, and the price subscription. |
| `<stack>/inventory-subgraph` | Adds stock and supplier fields, sourced from a REST API and an upstream GraphQL service. The only subgraph doing real outbound I/O. **Not part of the federated graph yet** — tested on its own, so the composed SDL is unchanged. |
| `supergraph` | Composes the supergraph, runs Apollo Router, owns the E2E tests. |

The services use Java 21, Spring Boot 4.1, Spring GraphQL, Maven, Docker
Compose, Apollo Router, and Rover. All business data is immutable and stored in
plain Java collections.

Each stack group holds ten classes. Five are byte-identical across stacks —
both `*Application` classes, `DecimalScalar`, `CatalogItemRef`, and products'
`FederationConfiguration`. The other five are where the stacks actually differ:
both controllers, both repositories, and pricing's `FederationConfiguration`.

The root `pom.xml` aggregates but does not parent, and the root Maven wrapper is
the only supported entry point — a standalone module build cannot resolve
`lab-model`. Scope every invocation, for example:

```sh
./mvnw -pl model,reactive/products-subgraph,reactive/pricing-subgraph -am verify
```

A bare `./mvnw verify` at the root is not supported: `supergraph`'s failsafe
binding expects services that are already running. Use the Makefile instead.

## Start

Prerequisites are Java 21+, Docker Compose v2, Make, and curl.

```sh
make up                                  # reactive: Netty, event loop
make up STACK=servlet                    # servlet: Tomcat, platform threads
make up STACK=servlet THREADS=virtual    # servlet: Tomcat, virtual threads
```

Three runnable configurations from two code trees. Add `DELAY=50ms` to give the
repositories a simulated round trip — without I/O to overlap the three are
indistinguishable, which is the honest result but not an instructive one.

The root `Makefile` delegates to `supergraph/Makefile`, so every target works
from either directory. The runbook below uses `supergraph`.

Both stacks bind the same ports and answer identically, so everything below
works the same either way. Only one pair runs at a time.

Interactive clients:

- Apollo Sandbox for the federated graph: <http://localhost:4000/>
- Products GraphiQL: <http://localhost:8081/graphiql>
- Pricing GraphiQL: <http://localhost:8082/graphiql>

Use Apollo Sandbox for queries that cross subgraph boundaries. The two
GraphiQL pages are useful for inspecting and diagnosing an individual
subgraph.

See the [supergraph runbook](supergraph/README.md) for manual queries,
troubleshooting, schema composition, and all available Make targets.

## Test

From the repository root, or from `supergraph`:

```sh
make test                  # model + the selected stack's subgraphs
make verify                # the full pipeline for one stack
make verify STACK=servlet
make verify-all            # both stacks, with a clean teardown before each
```

`make test` runs unit and subgraph integration tests. `make verify` additionally
builds the containers, validates deterministic schema composition, starts the
Router, runs smoke and federated E2E tests, and shuts everything down.

`make verify-all` is the parity proof: it runs the whole pipeline against both
stacks. Passing twice means the two publish byte-identical SDL and compose to
the identical supergraph.

Stop a manually started stack with:

```sh
make down
```

## What this demonstrates

The implementation covers federation entities and representations,
`@interfaceObject`, `@external`, `@requires`, polymorphic interfaces,
field selection at the subgraph boundary, `@BatchMapping` against N+1 queries, a
custom decimal scalar, an enum and input object, incremental delivery with
`@defer`, a subscription delivered over WebSocket and SSE, stable GraphQL
errors, and layered JUnit 6 tests.

The subscription is served by `pricing-subgraph` directly rather than through
Router, and is deliberately excluded from the composed supergraph — see
[Boundaries](#boundaries).

Read [Concepts demonstrated](docs/CONCEPTS.md) for the architecture and the
purpose of each feature, or browse the same material as a
[rendered page](docs/index.html).

## Boundaries

This milestone is intentionally local and educational. It does not include a
database, authentication, an observability platform, AWS/CDK, or production
deployment hardening. Apollo Router and Rover run locally from pinned free
container images; no Apollo account or GraphOS key is required.

That last point is why `Subscription.priceChanges` is served only by
`pricing-subgraph` on port 8082 and stripped from the SDL handed to composition:
routing subscriptions through Apollo Router requires connecting it to GraphOS
with credentials. Subscriptions are available on every GraphOS plan including
the free one, so this is an account-and-credentials constraint rather than a
paid-tier one — but it is still a constraint this lab declines to take on.

Neither stack is here to be faster. There is no database and no outbound HTTP,
so no thread is ever blocked and nothing in this lab is throughput-limited —
with no I/O to overlap, Netty and Tomcat serve this workload equally well. The
two stacks exist so the same federation code can be read in both idioms.

The shared `model` module is likewise a teaching decision, not an architectural
recommendation: a domain jar shared across independently deployable subgraphs is
a distributed-monolith smell in production. It is here because it shrinks the
stack comparison to five files. Federation ownership is unchanged — `lab-model`
is a compile-time library, never a runtime service, and each subgraph still owns
its own SDL.
