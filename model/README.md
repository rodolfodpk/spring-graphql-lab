# Model

`rdpk:lab-model` — the domain types both subgraphs share, as plain Java 21.

**No production dependencies.** No Spring, no GraphQL, no Reactor, no servlet
API, no persistence. The only entry in its dependency tree is test-scoped JUnit,
and CI asserts that the compile classpath stays empty. That constraint is the
point: it is what keeps the four service modules honest about which code is
domain logic and which is adapter.

Ten types:

| Group | Types |
| --- | --- |
| Catalog | `CatalogItem` (sealed), `Product`, `DigitalProduct`, `CatalogCategory`, `ProductNotFoundException` |
| Pricing | `Money`, `Quote`, `QuoteInput`, `PriceChange`, `PricingException` |

Two things live *outside* this module on purpose. `DecimalScalar` is graphql-java
coercion and `CatalogItemRef` is a federation representation stub — neither is
domain vocabulary, so both stay in the pricing subgraph.

Pricing shares only the catalog *category vocabulary*: it uses `CatalogCategory`
to coerce the `@external` category field and derive `priceLabel`, and never
touches the Products entity hierarchy.

Exceptions stay plain Java carrying a stable `code()`; converting them into
GraphQL errors is each controller's job, via `@GraphQlExceptionHandler`.

See [Concepts](../docs/CONCEPTS.md) for why a shared domain jar is the right call
for a teaching lab and the wrong one for production.

```sh
./mvnw -pl model verify
```
