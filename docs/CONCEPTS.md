# Concepts Demonstrated

SGL is a deliberately small Apollo Federation system. Products owns a
catalog, Pricing contributes commercial fields, and Apollo Router presents
both schemas to a client as one graph. Keeping the domain deterministic makes
the mechanics of federation visible without database or cloud infrastructure.

## Schema-first synchronous Spring GraphQL

Both subgraphs define their public contracts in GraphQL SDL and implement them
with Spring MVC-style annotated controllers. They use ordinary synchronous
Java methods—there is no WebFlux or Reactor layer.

Spring GraphQL loads each `schema.graphqls`, maps `@QueryMapping`,
`@SchemaMapping`, and `@BatchMapping` methods to fields, and exposes the
standard `/graphql` endpoint. GraphiQL is enabled locally for inspecting each
subgraph.

## Subgraphs, supergraph, Router, and Rover

A subgraph owns part of a larger graph:

- Products owns catalog identity and descriptive data.
- Pricing contributes fields that depend on catalog identity.

Each running subgraph exposes its Federation SDL through `_service { sdl }`.
The local scripts export those real schemas rather than maintaining handwritten
copies. Rover composes them into `router/supergraph.graphql`, and Apollo Router
uses that artifact to plan and execute client operations.

The client calls only `http://localhost:4000/`. For a query such as:

```graphql
query {
  products {
    id
    name
    price
  }
}
```

the Router fetches catalog data from Products, constructs entity
representations, asks Pricing for `price`, and merges the results.

Composition is tested for determinism: the same exported inputs are composed
twice and compared byte for byte with the checked-in supergraph.

## Entity identity with `@key`

Products defines a keyed entity interface:

```graphql
interface CatalogItem @key(fields: "id") {
  id: ID!
  name: String!
  category: CatalogCategory!
}
```

`@key(fields: "id")` tells Federation that an item can be identified across
subgraphs by `id`. The Router sends representations resembling:

```json
{
  "__typename": "CatalogItem",
  "id": "p-100",
  "category": "PHYSICAL"
}
```

Spring’s `@EntityMapping` resolves those representations into local Java
objects. Products uses a sealed `CatalogItem` hierarchy; Pricing uses a small
`CatalogItemRef` because it needs identity and required external data, not the
whole Products model.

## Entity interfaces and `@interfaceObject`

Products owns `CatalogItem` as an interface implemented by `Product` and
`DigitalProduct`. Pricing contributes the same fields to every implementation
without duplicating them:

```graphql
type CatalogItem @key(fields: "id") @interfaceObject {
  id: ID!
  category: CatalogCategory! @external
  price: Decimal!
  quote(input: QuoteInput!): Quote!
  priceLabel: String! @requires(fields: "category")
}
```

`@interfaceObject` lets Pricing treat the entity interface as one object while
the composed client schema retains real polymorphism.

## Polymorphism

The catalog query returns `[CatalogItem!]!`. Clients use `__typename` and inline
fragments to select subtype-specific fields:

```graphql
query {
  catalog {
    id
    name
    __typename
    ... on Product {
      weightGrams
    }
    ... on DigitalProduct {
      downloadFormat
    }
  }
}
```

The deterministic catalog has three physical products and one digital product.
The Java sealed interface and GraphQL type resolver guarantee that each object
maps to the correct GraphQL type.

## `@external` and `@requires`

Products owns `category`; Pricing does not. Pricing marks it `@external` and
declares that `priceLabel` requires it:

```graphql
category: CatalogCategory! @external
priceLabel: String! @requires(fields: "category")
```

When a client requests `priceLabel`, the Router fetches `category` from
Products even if the client did not request it, carries it in the entity
representation, and removes it from the response. Pricing maps the enum
exhaustively:

- `PHYSICAL` becomes `"Physical price"`.
- `DIGITAL` becomes `"Digital price"`.

Tests cover this Router-to-representation-to-Java-enum round trip.

## Enum and input object

The category is modeled as a closed GraphQL enum:

```graphql
enum CatalogCategory {
  PHYSICAL
  DIGITAL
}
```

This prevents arbitrary category strings at the schema boundary and gives Java
an exhaustive enum model. The two values are a teaching device, not a proposed
production taxonomy.

Quote arguments are grouped in an input object:

```graphql
input QuoteInput {
  quantity: Int!
}
```

A client uses it as follows:

```graphql
query {
  product(id: "p-100") {
    quote(input: { quantity: 2 }) {
      unitPrice
      quantity
      subtotal
    }
  }
}
```

Spring binds the input to a Java record. GraphQL validates missing input fields;
business validation rejects quantities below one with `VALIDATION_ERROR`.

## Custom `Decimal` scalar

GraphQL has no built-in arbitrary-precision decimal. Pricing registers a
custom `Decimal` scalar backed by `BigDecimal`. Prices are normalized to two
decimal places and serialized as JSON strings, preserving values such as
`"99.90"` without binary floating-point loss.

Scalar tests cover literals, variables, serialization, and malformed values.

## Field selection and over-fetching

A GraphQL response contains exactly the fields the client selected. That
guarantee describes the response payload, not the work the server performed to
produce it.

Selection reaches a data source only where a resolver boundary exists. The
clearest case is the subgraph boundary:

```graphql
query {
  catalog {
    id
    name
  }
}
```

No field in this query belongs to Pricing, so the Router never constructs
entity representations and never calls Pricing at all. An E2E test stops the
Pricing container, runs this query, and asserts that it still succeeds without
errors.

The same rule holds inside a subgraph. `price`, `quote`, and `priceLabel` are
backed by `@BatchMapping` and `@SchemaMapping` methods, so an unselected one is
never invoked. Fields such as `name` and `weightGrams` are read by the default
property fetcher from objects the repository already returned whole; selecting
fewer of them saves serialization, not repository work.

## Batching and the N+1 problem

Resolving `price` independently for every catalog item would produce one
repository call per item. Pricing instead uses:

```java
@BatchMapping(typeName = "CatalogItem", field = "price")
```

Spring collects all catalog references requested during one GraphQL execution
and invokes the resolver once with the full list. A dedicated test uses a
counting repository to prove that four items produce exactly one bulk lookup.
This asserts behavior, rather than merely asserting that the annotation exists.

## Incremental delivery with `@defer`

Apollo Router supports deferring a fragment:

```graphql
query {
  catalog {
    id
    name
    ... @defer(label: "commercial-data") {
      price
      priceLabel
    }
  }
}
```

The initial multipart response contains the core catalog. Subsequent patches
contain commercial data and their paths. The E2E test parses the dynamic MIME
boundary and verifies the initial result, labeled patches, and final
`hasNext: false`.

## Errors and local diagnostics

Domain failures use stable extension codes such as `PRODUCT_NOT_FOUND`,
`PRICE_NOT_FOUND`, and `VALIDATION_ERROR`. The local Router exposes subgraph
error details so tests can assert these codes; production systems should
normally retain Apollo’s error redaction.

Only the Actuator health endpoint is exposed. Apollo Sandbox and the two
GraphiQL pages are intentionally local diagnostic tools.

## Test pyramid

The example uses JUnit 6 at several levels:

- Unit tests cover seeded values, money rules, scalar coercion, label mapping,
  and batching call counts.
- Spring GraphQL integration tests load each real schema and exercise mappings,
  entity representations, validation, and Federation SDL.
- E2E tests call Apollo Router and cover cross-subgraph queries, polymorphism,
  `@requires`, quotes, errors, health exposure, outage recovery, and `@defer`.
- Schema checks export runtime SDL, compose twice, and detect stale artifacts.

`make verify` executes the complete path and removes the local containers when
finished.

## Deliberate boundaries

This milestone does not demonstrate persistence, authentication or
authorization, subscriptions, WebFlux, telemetry infrastructure, cloud
deployment, or production scaling. The in-memory repositories return whole
objects; deriving a storage projection from
`DataFetchingEnvironment.getSelectionSet()` is likewise out of scope. Those
concerns would obscure the central lesson: how a synchronous Spring GraphQL
application participates correctly in an Apollo Federation supergraph.
