# Stack contracts and inventory outbound-I/O subgraph

## Goal

Add a dual-stack `inventory-subgraph` that demonstrates the reactive/servlet contrast against real
HTTP I/O, mocked at the protocol boundary with WireMock.

The current two-subgraph Router/Compose pipeline remains unchanged. Inventory is built and tested
as a module in both stacks, but is not yet composed, routed, or exposed on a host port.

### Already shipped — do not redo

This milestone originally had a first commit making the existing Products and Pricing unit tests
express each stack's execution contract. **That shipped in `155d8d2`** and its section has been
removed from this plan so nobody re-does it. Delivered there: the `PricingBatchingTest` deferral
assertion (zero repository subscriptions before subscribe, one after), both `CatalogRepositoryTest`
files asserting the four seeded records whole, `PriceLabelTest` via `StepVerifier` with the
synchronous `priceLabel` throw left as `assertThrows`, the unseeded-ref `PRICE_NOT_FOUND` case, the
servlet full-map assertion with its contrast Javadoc, and the `docs/CONCEPTS.md` amendments
distinguishing an invocation-time throw from an in-operator `onError`.

The deferral assertion was validated with the early-subscription mutant in `PricingController.price`
and confirmed to fail; the `Mono.fromSupplier` → `Mono.just` mutant was tried and shown *not* to
exercise it, since the double counts subscriptions. Exactly two `.block(` calls remain under
`reactive/` and `servlet/`, both `PricingSubscriptionWebSocketIT` teardowns.

## The inventory subgraphs

Add `reactive/inventory-subgraph` and `servlet/inventory-subgraph`, both using artifact IDs
`inventory-subgraph` and `inventory-subgraph-servlet` respectively. Add both to the root Maven
reactor, the stack-aware `supergraph/Makefile test` target, CI Maven-cache paths, and Codecov's
per-stack report list. The services are not added to `compose.yaml`, Router configuration, schema
export/composition scripts, or federated E2E tests.

### Public GraphQL API

Both modules publish this identical schema:

```graphql
extend schema
  @link(url: "https://specs.apollo.dev/federation/v2.8",
        import: ["@key", "@interfaceObject"])

type CatalogItem @key(fields: "id") @interfaceObject {
  id: ID!
  stockLevel: Int!
  restockEta: String
  supplier: Supplier!
}

type Supplier { name: String! rating: Float! }
type Query { inventoryHealth: String! }
```

Use `@interfaceObject` federation wiring like Pricing: `FederationSchemaFactory` with a constant
`CatalogItem` type resolver, a `GraphQlSourceBuilderCustomizer`, and no scalar or interface wiring.
Entity resolution must preserve representation order: `concatMap` in reactive and an ordered stream
in servlet. The local `CatalogItemRef` record contains the ID and supplies value equality for loader
keys.

### Model and clients

Add these plain records/exceptions to `lab-model` (no production dependencies):

- `Stock(String productId, int stockLevel, String restockEta)`
- `Supplier(String name, double rating)`
- `InventoryException(String code, String message, Throwable cause)` with the existing two-argument
  convenience constructor and `code()` accessor.

Each inventory module has a non-final `WarehouseClient` and `SupplierClient`, with an injected
constructor and a direct-construction convenience constructor. Configure these properties in each
module:

```properties
app.inventory.warehouse-base-url=http://localhost:0
app.inventory.supplier-base-url=http://localhost:0
app.inventory.timeout=500ms
```

Tests override both URLs with one in-process WireMock server through `@DynamicPropertySource`.
Reactive clients use `WebClient` and `HttpGraphQlClient`; servlet clients use `RestClient` and
`HttpSyncGraphQlClient`. Configure the reactive connector with Reactor Netty `responseTimeout` and
the servlet `JdkClientHttpRequestFactory` with `setReadTimeout(Duration)`, both from
`app.inventory.timeout`.

The warehouse bulk endpoint is `GET /warehouse?ids=<comma-separated IDs in first-seen order>` and
returns a JSON array of `Stock` rows. The supplier endpoint is `POST /graphql`; its bulk query takes
the same ordered ID list and returns suppliers keyed by product ID. HTTP 5xx, malformed/missing
warehouse data, request timeout, transport failure, and any GraphQL response with a non-empty
`errors` array all become `InventoryException("INVENTORY_UNAVAILABLE", ...)`.

### Batching and controller behavior

Use **two named DataLoaders**, not `@BatchMapping`, because both `stockLevel` and `restockEta`
must share one warehouse response:

- `warehouseById` batches IDs into one `WarehouseClient` call and returns `Stock` by ID.
- `suppliersById` batches IDs into one `SupplierClient` GraphQL call and returns `Supplier` by ID.

Register both with `BatchLoaderRegistry`. Reactive clients return `Mono<Map<...>>`; servlet client
results are wrapped with `Mono.just(...)` at registration. `@SchemaMapping` methods for
`stockLevel`, `restockEta`, and `supplier` load the appropriate named DataLoader. Reactive mappings
return `Mono` from an eagerly-created DataLoader future; servlet mappings return its
`CompletableFuture`. Never defer `loader.load(...)`, because that misses the DataLoader dispatch
window.

Map `InventoryException` with `@GraphQlExceptionHandler` to a GraphQL error carrying extension code
`INVENTORY_UNAVAILABLE`. `inventoryHealth` returns `"ok"` in the appropriate stack idiom.

### Dependencies, images, and test fixtures

Start from each corresponding Pricing POM. Remove subscription-only dependencies from the inventory
POMs (`spring-boot-starter-websocket`, and servlet's test-only `spring-webflux` and
`reactor-netty-http`); retain `reactor-test` in servlet because the DataLoader API is reactive. Add
test-scoped `org.wiremock:wiremock-standalone:3.13.0`. Do not use plain `wiremock`, whose
transitives break the existing dependency-convergence rule.

Add inventory Dockerfiles under both stack directories and update the four existing service
Dockerfiles to copy both new inventory POMs before their reactor `dependency:go-offline` command.
Each inventory Dockerfile uses the root build context and builds only its module plus `lab-model`.
Because Inventory is absent from Compose, add direct `docker build` checks for both inventory
Dockerfiles; `make build` alone only builds Products and Pricing images.

Keep WireMock stubs as identical Java text blocks in each stack's test sources. They are deliberately
duplicated, not shared resources: no test-fixtures module and no production-model test dependency is
introduced.

### Tests

Use JUnit 6 `WireMockExtension` and identical REST/GraphQL fixture bodies in both stacks.

- Client tests: successful bulk response, HTTP 500, timeout beyond 500 ms, malformed warehouse
  response, and GraphQL response containing `errors`.
- Controller/GraphQL tests: `_entities` resolution requesting all three fields for four IDs;
  WireMock verifies exactly one warehouse GET and one supplier POST. Assert `_service { sdl }`
  contains `@interfaceObject`.
- Reactive unit tests use `StepVerifier`; servlet unit tests assert direct values. Both assert the
  same DTO values and `INVENTORY_UNAVAILABLE` error code.
- Do not claim an automated platform-versus-virtual-thread benchmark in this change. Inventory is
  deliberately not a Compose service, so `THREADS` is not part of its module-test runtime. Document
  instead that real blocking I/O now makes a future live-stack comparison meaningful; add that
  benchmark only with the follow-up that composes and runs Inventory.

## Verification and documentation

1. Run `make test` and `make test STACK=servlet`; both now include the inventory tests.
2. Prove the batching assertion has teeth rather than trusting a green run: temporarily resolve
   `stockLevel` and `restockEta` through *separate* loaders and confirm WireMock's
   `verify(1, getRequestedFor(...))` fails with two warehouse calls. Revert. An assertion that
   cannot fail is decoration.
3. Run each stack's scoped reactor build and `dependency:tree`; dependency convergence passes and
   `lab-model` retains no production dependencies. Confirm once, deliberately, that substituting
   plain `wiremock` for `wiremock-standalone` fails this step — that is the whole reason for the
   artifact choice.
4. Run both direct inventory Docker builds, then `make build` for the existing composed pair.
   Inventory is absent from Compose, so `make build` alone never exercises its Dockerfiles.
5. Run `make verify-all`; the two-service federated E2E and SDL composition checks stay unchanged.
   `git diff --exit-code -- supergraph/schemas supergraph/router/supergraph.graphql` must pass —
   this change adds modules and must not perturb the graph.
6. Update the root README and `docs/CONCEPTS.md` with the outbound REST and GraphQL batching design,
   the WireMock rationale, the timeout/error contract, and the limited non-composed inventory scope.
   State that a virtual-thread benchmark belongs to the later live-composition milestone.
