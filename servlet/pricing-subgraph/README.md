# Pricing (servlet)

Spring Boot 4.1 / Spring GraphQL Pricing subgraph on **Spring MVC and Tomcat**.
Repositories and controllers return plain values, with `Optional` for absence.

Its twin is [`reactive/pricing-subgraph`](../../reactive/pricing-subgraph). The two publish
byte-identical SDL and pass the same tests; the domain types they share live in
[`model`](../../model).

- Java release: 21
- GraphQL Java: 25.0
- Federation mode: `ENTITY_INTERFACE_MODE=interface-object`
- Local endpoint: `http://localhost:8082/graphql` through Compose

Contributes `price`, `quote`, and `priceLabel` to the `CatalogItem` entity
interface. It also serves `Subscription.priceChanges` over WebSocket and SSE — a
local-only surface deliberately kept out of the composed supergraph, because
routing subscriptions through Apollo Router requires GraphOS credentials this lab
does not use.

The subscription returns a `Flux` even here: graphql-java's subscription contract
is `Publisher`-based on every transport. The WebSocket transport also needs
`spring-boot-starter-websocket`, which is the one dependency the reactive twin
does not require.

Built from the repository root — there is no wrapper in this directory:

```sh
./mvnw -pl servlet/pricing-subgraph -am verify
```
