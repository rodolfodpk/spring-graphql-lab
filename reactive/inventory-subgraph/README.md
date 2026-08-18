# Inventory (reactive)

Spring Boot 4.1 / Spring GraphQL Inventory subgraph on **Spring WebFlux and Reactor
Netty**. Clients are `WebClient` and `HttpGraphQlClient`, returning `Mono`.

Its twin is [`servlet/inventory-subgraph`](../../servlet/inventory-subgraph). Both publish the same
SDL and assert the same values against the same WireMock stubs; the domain types they share live in
[`model`](../../model).

- Java release: 21
- Local endpoint: not exposed — see below
- Upstreams: a REST warehouse API and a GraphQL supplier service, both mocked in tests

Contributes `stockLevel`, `restockEta`, and `supplier` to the `CatalogItem` entity interface via
`@interfaceObject`. This is the first subgraph in the lab that performs real outbound I/O.

Both upstreams are batched through named DataLoaders rather than `@BatchMapping`, so that
`stockLevel` and `restockEta` share a single warehouse response. A query selecting all three fields
for any number of items performs exactly one GET and one POST, which the integration test asserts
with WireMock's request counting.

**Not part of the federated graph yet.** This module is deliberately absent from `compose.yaml`,
the Router configuration, and the composed supergraph, so the existing SDL parity proof is
undisturbed. It is exercised by its own tests only. Wiring it into the live graph is a follow-up,
and `make build` does not build its image — build it directly:

```sh
docker build -f reactive/inventory-subgraph/Dockerfile .
```

Built from the repository root — there is no wrapper in this directory:

```sh
./mvnw -pl reactive/inventory-subgraph -am verify
```
