# Spring GraphQL Lab

[![Java CI](https://github.com/rodolfodpk/spring-graphql-lab/actions/workflows/maven.yml/badge.svg)](https://github.com/rodolfodpk/spring-graphql-lab/actions/workflows/maven.yml)
[![codecov](https://codecov.io/gh/rodolfodpk/spring-graphql-lab/branch/main/graph/badge.svg)](https://codecov.io/gh/rodolfodpk/spring-graphql-lab)
[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)

Spring GraphQL Lab is a local reference implementation of Apollo Federation
with two synchronous Spring Boot GraphQL subgraphs. It demonstrates how
independently owned schemas compose into one client-facing graph without a
database, cloud account, or paid Apollo service.

## Modules

The single Git repository contains three modules:

| Module | Responsibility |
| --- | --- |
| `products-subgraph` | Owns the catalog, the `CatalogItem` entity interface, and its physical and digital implementations. |
| `pricing-subgraph` | Extends catalog items with prices, quotes, and category-derived labels. |
| `supergraph` | Builds the services, composes the supergraph with Rover, runs Apollo Router, and owns E2E tests. |

The services use Java 21, Spring Boot 4.1, Spring GraphQL, Maven, Docker
Compose, Apollo Router, and Rover. All business data is immutable and stored in
plain Java collections.

## Start

Prerequisites are Java 21+, Docker Compose v2, Make, and curl.

```sh
cd supergraph
make up
```

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

From `supergraph`:

```sh
make test
make verify
```

`make test` runs unit and subgraph integration tests. `make verify` additionally
builds the containers, validates deterministic schema composition, starts the
Router, runs smoke and federated E2E tests, and shuts everything down.

Stop a manually started stack with:

```sh
make down
```

## What this demonstrates

The implementation covers federation entities and representations,
`@interfaceObject`, `@external`, `@requires`, polymorphic interfaces,
field selection at the subgraph boundary, `@BatchMapping` against N+1 queries, a
custom decimal scalar, an enum and input object, incremental delivery with
`@defer`, stable GraphQL errors, and layered JUnit 6 tests.

Read [Concepts demonstrated](docs/CONCEPTS.md) for the architecture and the
purpose of each feature, or browse the same material as a
[rendered page](docs/index.html).

## Boundaries

This milestone is intentionally local and educational. It does not include a
database, authentication, WebFlux, an observability platform, AWS/CDK, or
production deployment hardening. Apollo Router and Rover run locally from
pinned free container images; no Apollo account or GraphOS key is required.
