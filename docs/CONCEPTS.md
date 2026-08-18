# Concepts Demonstrated

Spring GraphQL Lab is a deliberately small Apollo Federation system. Products
owns a catalog, Pricing contributes commercial fields, and Apollo Router
presents both schemas to a client as one graph. Keeping the domain
deterministic makes the mechanics of federation visible without database or
cloud infrastructure.

## Schema-first Spring GraphQL, on two stacks

Both subgraphs define their public contracts in GraphQL SDL and implement them
with annotated controllers. Spring GraphQL loads each `schema.graphqls`, maps
`@QueryMapping`, `@SchemaMapping`, `@BatchMapping`, and `@SubscriptionMapping`
methods to fields, and exposes the standard `/graphql` endpoint. GraphiQL is
enabled locally for inspecting each subgraph.

Each subgraph is built twice: once on Spring WebFlux and Reactor Netty, once on
Spring MVC and Tomcat. Both build against one shared `model` module, and both
are exercised by the same E2E suite.

## The same subgraph on two stacks

This is the payload of the whole layout, so it is worth being precise about what
is and is not different.

**Identical:** the published SDL, byte for byte. The composed supergraph. Every
one of the ten E2E assertions. All ten `model` classes. And five of the ten
classes in each stack group — both `*Application` classes, `DecimalScalar`,
`CatalogItemRef`, and products' `FederationConfiguration`.

**Different**, in exactly five files plus their build config:

| | reactive | servlet |
| --- | --- | --- |
| web starter | `spring-boot-starter-webflux` | `spring-boot-starter-webmvc` |
| server | Reactor Netty | Tomcat |
| repositories | `Mono` / `Flux` | plain values, `Optional` for absence |
| controller returns | `Mono<T>` / `Flux<T>` | `T` / `List<T>` |
| DataLoader bridge | `Mono.fromFuture(future)` | `future.thenApply(...)` |
| batch loader registration | passes its `Mono` through | must wrap in `Mono.just(...)` |
| WebSocket transport | included | needs `spring-boot-starter-websocket` |

Two of those rows are the ones that actually teach something.

`BatchLoaderRegistry.registerMappedBatchLoader` requires a `Mono<Map<K,V>>`. The
reactive repository already returns one, so it is passed straight through; the
servlet repository returns a bare `Map`, so the servlet side *must* write
`Mono.just(repository.findAllByProductId(ids))`. That wrapper is not an
anti-pattern there — with a synchronous repository it is the only legal form.

And `Subscription.priceChanges` returns a `Flux` **on both stacks**. graphql-java's
subscription contract is `Publisher`-based on every transport, so the servlet
controller returns a `Flux` too, over a blocking repository. That is a framework
contract, not reactive code leaking across.

### What the stack choice cannot touch

The entry point at `:4000` is Apollo Router, a Rust binary on Tokio and Hyper.
Router does all cross-subgraph fan-out, and that fan-out is async and
non-blocking regardless of what the subgraphs run on. There is no "reactive vs
blocking" decision available at that tier at all.

So query planning, cross-subgraph concurrency, and `@defer` incremental delivery
— which Router assembles, with subgraphs returning ordinary GraphQL JSON either
way — are unaffected by the stack. The choice is scoped entirely to what happens
inside a subgraph process *after* Router has already fanned out. This is exactly
why one unmodified E2E suite can serve as the parity proof, and it is worth
saying plainly: the reactive stack does not buy concurrency that Router was
providing all along.

### What reactive buys here, and what it does not

Nothing in this lab blocks by default. There is no database, and no subgraph
makes an outbound HTTP call. So the reactive stack is a demonstration of the
*programming model*, not a throughput improvement. Claiming otherwise would be
dishonest: with no I/O to overlap, Netty and Tomcat serve this workload equally
well — and now the repository proves it rather than asserting it, since both
stacks pass the same suite.

That is also why `app.repository.delay` exists. Set it and the repositories take
a simulated round trip, which is the only condition under which the models can
differ at all. The wait is identical; what differs is its cost. Reactive defers a
subscription and holds no thread. Servlet blocks its request thread — and
`spring.threads.virtual.enabled` decides whether that is a platform thread or a
virtual one, giving three runnable configurations from two code trees.

Worth stating plainly, because it reframes the whole comparison: **on Java 21,
virtual threads take most of the scalability argument away from reactive.**
Blocking code on virtual threads scales close to non-blocking code, without the
operator vocabulary. What reactive still uniquely offers is backpressure — a
consumer able to say *slow down* — and operator-level composition of concurrent
work. Neither is exercised by this lab's five fixed emissions, and neither is
something a subgraph in a federated system usually needs, since Router does the
fan-out.

The delay defaults to zero, so every test and the SDL parity proof are
unaffected.

What it does buy is a codebase shaped the way a reactive service is shaped. Two
conventions are worth naming, because they look inconsistent otherwise:

- **Every reactive repository method returns `Mono` or `Flux`, and reports a
  missing row as an empty `Mono` rather than an exception.**
  `PriceRepository.containsProductId` returning `Mono<Boolean>` is pure ceremony
  over a `HashMap`. It is written that way because the repository interface is
  the seam where a real datastore would sit, and a method returning a bare
  `boolean` today cannot become an R2DBC lookup tomorrow without changing every
  caller. The servlet twin makes the same point from the other side: it returns
  `Optional<BigDecimal>`, so absence is still the caller's decision to escalate.
- **Controllers may still throw synchronously**, through two different mechanisms
  worth telling apart. `parseCategory` and `requiredPrice` throw from *inside* an
  operator lambda, so Reactor converts them into `onError` signals on the returned
  publisher. `priceLabel`'s category check throws from the method body *before*
  any `Mono` is constructed, so it propagates out of the call itself and Spring
  GraphQL catches it at invocation. `@GraphQlExceptionHandler` matches either way,
  which is what lets the two stacks keep identical error semantics — but only the
  first kind is observable to `StepVerifier.expectError`, since the second escapes
  before a publisher exists to subscribe to.

For the same reason, `pricingHealth()` returning `Mono<String>` is uniformity,
not necessity. Do not read it as a rule that every trivial computation belongs
in a `Mono`.

### Order of validation is observable

`PricingController.reference` parses the category *before* checking that the
product exists. That order is part of the contract: an invalid category on an
unknown id must report `VALIDATION_ERROR`, not `PRICE_NOT_FOUND`. Both stacks
implement the same order, and both are checked against a running server.

It is called out because it is the kind of detail a rewrite silently inverts.

## The shared model, and what it costs

`model` holds the ten types both subgraphs need — the catalog hierarchy plus the
pricing value types — as `rdpk:lab-model`, with no Spring, GraphQL, Reactor, or
servlet dependency. Its compile classpath is empty; the only entry in its
dependency tree is test-scoped JUnit, and CI asserts that.

Two things stay *out* of it deliberately. `DecimalScalar` is graphql-java
coercion, and `CatalogItemRef` is a federation representation stub — neither is
domain vocabulary, so both live in the pricing subgraph.

Pricing shares only the catalog *category vocabulary*, not the Products entity
hierarchy: it uses `CatalogCategory` to coerce the `@external` category field and
derive `priceLabel`, and never touches `CatalogItem`, `Product`, or
`DigitalProduct`. That overlap is an enum the federation contract already forces
both subgraphs to agree on.

The honest cost: a domain jar shared across independently deployable subgraphs is
a distributed-monolith smell. Federation's premise is that each subgraph owns its
types and ships without coordinating, and a shared jar trades some of that away.
It earns its place here by shrinking the stack comparison from 21 duplicated
classes to five differing files. Federation ownership itself is unchanged —
`lab-model` is a compile-time library, never a runtime service, and each subgraph
still owns and publishes its own SDL.

## Outbound I/O: the inventory subgraph

Products and Pricing hold their data in memory, which is why the stack comparison elsewhere in this
document reports no difference. `inventory-subgraph` is the exception: it resolves `stockLevel`,
`restockEta`, and `supplier` from two real upstreams — a REST warehouse API and an upstream GraphQL
service — and so it is the first place where blocking and non-blocking code do genuinely different
things.

It is deliberately **not** wired into the federated graph. It has no Compose service, no entry in
`router/supergraph.yaml`, and contributes nothing to the composed supergraph, so the byte-identical
SDL parity proof is untouched. It is exercised by its own tests. Wiring it in is a follow-up.

### One response, two fields

Both upstreams are batched through **named DataLoaders**, not `@BatchMapping`. That is a deliberate
departure from Pricing, and the reason is `restockEta`: two `@BatchMapping` methods would each get
their own loader, so a query selecting `stockLevel` and `restockEta` would make two warehouse calls
for the same data. One loader serving both fields makes it one.

A query selecting all three fields for any number of items performs exactly one GET and one POST.
The integration test asserts that with WireMock's request counting — and unlike a hand-written
counting double, the assertion is over real HTTP:

```java
wireMock.verify(1, getRequestedFor(urlPathEqualTo("/warehouse")));
wireMock.verify(1, postRequestedFor(urlEqualTo("/graphql")));
```

This is where the N+1 material elsewhere in this document stops being theoretical. With in-memory
maps a missing batch is a few extra `HashMap` lookups; here it is a network round trip per item.

### Why WireMock rather than Spring's mock servers

The mock has to serve both stacks identically. `MockRestServiceServer` and `@RestClientTest` bind to
`RestClient`/`RestTemplate` internals and cannot mock `WebClient`; a stubbed `ClientHttpConnector`
is reactive-only. Either choice would mean two different mocking strategies for one behaviour.
WireMock is a real socket, so it is protocol-level and stack-agnostic — and an upstream GraphQL
service is just `POST /graphql` with a JSON body, which needs no GraphQL-aware mock.

The dependency is `wiremock-standalone`, not `wiremock`. Every subgraph module enforces
`dependencyConvergence`; plain `wiremock` brings 29 transitive dependencies that collide with
Boot-managed versions, while the shaded jar brings none.

### The error contract, and two traps worth naming

HTTP 5xx, a read timeout, a malformed warehouse payload, and a GraphQL response carrying a
non-empty `errors` array all become `InventoryException("INVENTORY_UNAVAILABLE", …)`. That last one
is the case a REST-shaped client forgets: the response is HTTP 200, so a status check alone treats
degraded data as success.

Two implementation details differ between the stacks and are easy to get wrong:

- **`mapNotNull`, not `map`.** `restockEta` is nullable. Reactor treats a `null` returned from `map`
  as an error, so the reactive resolver must use `mapNotNull`; the servlet twin's
  `CompletableFuture.thenApply` accepts null and needs no equivalent. A nullable field is the kind
  of thing that passes every unit test and fails on the one stubbed row that has no ETA.
- **HTTP/1.1 is pinned on the servlet client.** The JDK's `HttpClient` negotiates HTTP/2 by default,
  and a stream cancelled by a read timeout poisons the pooled connection — the *next* request then
  fails with `RST_STREAM` instead of the timeout the caller is handling.

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

`quote` cannot use the annotation, because `@BatchMapping` methods do not
accept field arguments and `quote` takes a `QuoteInput`. Its data dependency is
still only the item id — the quantity is applied afterwards as pure
computation — so it batches through a DataLoader registered by name:

```java
// reactive: the repository already returns Mono<Map<String, BigDecimal>>
registry.forTypePair(String.class, BigDecimal.class)
        .withName(PricingController.PRICE_LOADER)
        .registerMappedBatchLoader((ids, environment) ->
                repository.findAllByProductId(ids));

// servlet: the repository returns a bare Map, and registerMappedBatchLoader
// requires a Mono, so the wrapper is mandatory rather than incidental
        .registerMappedBatchLoader((ids, environment) ->
                Mono.just(repository.findAllByProductId(ids)));
```

The resolver returns a `CompletableFuture` from `loader.load(item.id())`, and
Spring dispatches the accumulated keys once per execution. A second counting
test drives this through a real GraphQL execution, since a DataLoader only
dispatches when the framework runs the query.

The two mechanisms are registered separately, so a query selecting both `price`
and `quote` performs two bulk lookups rather than one. That is a constant, not
a lookup per item, which is the property the N+1 problem is about.

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

## A subscription that stops at the subgraph

`pricing-subgraph` serves a subscription that streams a product's price:

```graphql
subscription {
  priceChanges(productId: "p-100") { productId price sequence }
}
```

The stream is finite and fully determined, so tests can assert it value by
value. Sequence 1 carries the unchanged seeded price and each later emission
adds `0.10`, five emissions in all, 200 ms apart:

| sequence | price |
| --- | --- |
| 1 | `99.90` |
| 2 | `100.00` |
| 3 | `100.10` |
| 4 | `100.20` |
| 5 | `100.30` |

An unknown `productId` fails with `PRICE_NOT_FOUND` before the stream starts,
because the controller reads the seeded price and validates in a single lookup
rather than checking existence and then reading.

Both transports work against `http://localhost:8082/graphql`. WebSocket needs
`spring.graphql.websocket.path`; SSE needs no configuration at all:

```sh
curl -N -X POST http://127.0.0.1:8082/graphql \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"query":"subscription { priceChanges(productId: \"p-100\") { price sequence } }"}'
```

**WebSocket subscriptions are not something WebFlux unlocks**, and this repo now
demonstrates that rather than asserting it: `servlet/pricing-subgraph` serves the
same subscription over a real graphql-ws handshake against Tomcat, delivering the
same five values. SSE rides the ordinary GraphQL HTTP endpoint on both stacks.

What the servlet stack needs and the reactive one does not is one dependency.
`GraphQlWebMvcAutoConfiguration$WebSocketConfiguration` is gated on
`@ConditionalOnClass({HttpMessageConverter, jakarta.websocket.server.ServerContainer,
org.springframework.web.socket.WebSocketHandler})` plus
`@ConditionalOnProperty("spring.graphql.websocket.path")`. The webmvc starter
satisfies neither class condition, so `spring-boot-starter-websocket` is required.
SSE needs nothing extra — `GraphQlSseHandler` ships in `spring-graphql` for both
transports.

And the handler returns a `Flux` on both stacks regardless, because graphql-java's
subscription contract is `Publisher`-based everywhere.

### Why it is stripped from the supergraph

Routing subscriptions through Apollo Router requires connecting the Router to
GraphOS with credentials. Subscriptions are available on every GraphOS plan
including the free one, so this is an account-and-credentials constraint rather
than a paid-tier one—but this lab's premise is that it runs with no Apollo
account, and CI composes unauthenticated.

So `Subscription` and `PriceChange` are removed from the SDL handed to
composition by `scripts/strip-local-subscription.sh`. Three constructs have to
go, not two: dropping only the two type definitions would leave
`subscription: Subscription` dangling in the schema root block, and composition
would fail. The script matches definitions by name and consumes them by brace
depth rather than by blank-line paragraphs, so a change in how graphql-java
lays out SDL cannot silently defeat it.

The guard runs from both sides. `export-subgraphs.sh` asserts the live
`_service { sdl }` *does* contain all three constructs before filtering, and
the filter asserts the published schema contains none of them afterwards.
Without the first assertion, a subscription that quietly stopped being
published would look exactly like a filter that worked.

## Errors and local diagnostics

Domain failures use stable extension codes such as `PRODUCT_NOT_FOUND`,
`PRICE_NOT_FOUND`, and `VALIDATION_ERROR`. The local Router exposes subgraph
error details so tests can assert these codes; production systems should
normally retain Apollo’s error redaction.

Only the Actuator health endpoint is exposed. Apollo Sandbox and the two
GraphiQL pages are intentionally local diagnostic tools.

## Test pyramid

The example uses JUnit 6 at several levels:

- `model` owns the pure-domain unit tests — money rules and quote arithmetic —
  with no Spring or Reactor on the classpath at all.
- Per-stack unit tests cover seeded values, scalar coercion, label mapping, and
  batching call counts. The reactive versions assert with `StepVerifier`; the
  servlet versions assert plain values and `Optional`, which is the same
  behaviour expressed in the other idiom.
- The reactive batching test additionally asserts *when* the lookup happens:
  building the returned `Mono` performs no repository subscription, and exactly
  one occurs on subscribe. That is the one behaviour the two stacks genuinely
  differ on — the servlet twin's counting double increments in the method body,
  because there the call *is* the lookup. Stated narrowly on purpose: `price`
  still does eager work, assembling its id set before any publisher exists.
- Spring GraphQL integration tests load each real schema and exercise mappings,
  entity representations, validation, Federation SDL, and the subscription
  through `ExecutionGraphQlServiceTester`.
- One WebSocket integration test per stack runs against a real server with
  `WebSocketGraphQlTester` — Reactor Netty on one side, Tomcat on the other —
  which is the authoritative proof that each transport works end to end rather
  than only in process. Each asserts its own application-context type, so a
  misconfigured classpath cannot let one stack quietly test the other. (The
  *client* is Reactor-based in both, because `WebSocketGraphQlTester` accepts
  only the reactive `WebSocketClient` interface; that is a property of the test
  harness, not of the server under test.)
- E2E tests call Apollo Router and cover cross-subgraph queries, polymorphism,
  `@requires`, quotes, errors, health exposure, outage recovery, and `@defer`;
  plus SSE against `pricing-subgraph` directly, and an assertion that the
  supergraph exposes no `Subscription`.
- Schema checks export runtime SDL, compose twice, and detect stale artifacts.

`make verify` executes the complete path for one stack and removes the local
containers when finished. `make verify-all` runs it for both, tearing down before
each — the parity proof, since passing twice means both stacks introspect to
byte-identical SDL and compose to the identical supergraph.

## Deliberate boundaries

This milestone does not demonstrate persistence, authentication or
authorization, telemetry infrastructure, cloud deployment, or production
scaling. The in-memory repositories return whole objects; deriving a storage
projection from `DataFetchingEnvironment.getSelectionSet()` is likewise out of
scope. Those concerns would obscure the central lesson: how a Spring GraphQL
application participates correctly in an Apollo Federation supergraph.

Subscriptions are demonstrated, but only up to the subgraph boundary. See
[A subscription that stops at the subgraph](#a-subscription-that-stops-at-the-subgraph).
