# Pricing

Synchronous Spring Boot 4.1 / Spring GraphQL Pricing subgraph.

- Java release: 21
- GraphQL Java: 25.0
- Federation mode: `ENTITY_INTERFACE_MODE=interface-object`
- Local endpoint: `http://localhost:8082/graphql` through Compose

The subgraph contributes `price`, `quote`, and `priceLabel` to the
`CatalogItem` entity interface. Run `./mvnw verify` to execute unit,
batching, scalar, and federation integration tests.
