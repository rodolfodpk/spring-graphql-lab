# Products (servlet)

Spring Boot 4.1 / Spring GraphQL Products subgraph on **Spring MVC and Tomcat**.
Repositories and controllers return plain values, with `Optional` for absence.

Its twin is [`reactive/products-subgraph`](../../reactive/products-subgraph). The two publish
byte-identical SDL and pass the same tests; the domain types they share live in
[`model`](../../model).

- Java release: 21
- GraphQL Java: 25.0
- Federation mode: `ENTITY_INTERFACE_MODE=interface-object`
- Local endpoint: `http://localhost:8081/graphql` through Compose

Owns the catalog and the `CatalogItem` entity interface.

Built from the repository root — there is no wrapper in this directory:

```sh
./mvnw -pl servlet/products-subgraph -am verify
```
