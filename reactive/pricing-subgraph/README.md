# Pricing (reactive)

Spring Boot 4.1 / Spring GraphQL Pricing subgraph on **Spring WebFlux and Reactor
Netty**. Repositories and controllers are written in terms of `Mono` and `Flux`.

Its twin is [`servlet/pricing-subgraph`](../../servlet/pricing-subgraph). The two publish
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

Built from the repository root — there is no wrapper in this directory:

```sh
./mvnw -pl reactive/pricing-subgraph -am verify
```
