# Products (reactive)

Spring Boot 4.1 / Spring GraphQL Products subgraph on **Spring WebFlux and Reactor
Netty**. Repositories and controllers are written in terms of `Mono` and `Flux`.

Its twin is [`servlet/products-subgraph`](../../servlet/products-subgraph). The two publish
byte-identical SDL and pass the same tests; the domain types they share live in
[`model`](../../model).

- Java release: 21
- GraphQL Java: 25.0
- Federation mode: `ENTITY_INTERFACE_MODE=interface-object`
- Local endpoint: `http://localhost:8081/graphql` through Compose

Owns the catalog and the `CatalogItem` entity interface.

Built from the repository root — there is no wrapper in this directory:

```sh
./mvnw -pl reactive/products-subgraph -am verify
```
