# Pricing

Reactive Spring Boot 4.1 / Spring GraphQL Pricing subgraph, running on WebFlux
and Reactor Netty.

- Java release: 21
- GraphQL Java: 25.0
- Federation mode: `ENTITY_INTERFACE_MODE=interface-object`
- Local endpoint: `http://localhost:8082/graphql` through Compose

The subgraph contributes `price`, `quote`, and `priceLabel` to the
`CatalogItem` entity interface. It also serves `Subscription.priceChanges` over
WebSocket and SSE — a local-only surface that is deliberately kept out of the
composed supergraph, because routing subscriptions through Apollo Router
requires GraphOS credentials this lab does not use.

Run `./mvnw verify` to execute unit, batching, scalar, subscription, and
federation integration tests.
