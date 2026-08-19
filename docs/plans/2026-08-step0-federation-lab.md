# SGL Step 0 — Local Spring GraphQL Federation

> **Superseded in part.** This document is kept as a record of the original
> milestone. Its "no WebFlux, no subscriptions" constraints no longer hold: both
> subgraphs were later migrated to WebFlux and Reactor, and `pricing-subgraph`
> gained a subscription served directly to clients. See `docs/CONCEPTS.md` for
> the current architecture.

## Goal

Build a local Apollo Federation reference implementation with two synchronous Spring Boot subgraphs. A client sends GraphQL requests only to Apollo Router, which exposes one federated schema backed by Products and Pricing.

Delivery has two acceptance gates in this milestone:

1. **Core gate:** establish the dependable path first — concrete entities, runtime SDL export, Rover composition, Router execution, batching, Docker Compose, Make targets, and ordinary JSON E2E tests.
2. **Advanced gate:** after the core gate passes, add interface polymorphism, a Federation entity interface with `@interfaceObject`, `@external`/`@requires`, query-plan evidence, and Router-driven `@defer` with multipart E2E tests.

The gates are sequencing and diagnostic boundaries, not separate deployable products. The final committed schema includes the advanced features. A compatibility spike must prove Spring/Federation JVM entity-interface behavior before the core model is expanded. If that spike fails with the pinned versions, retain ordinary GraphQL interface polymorphism and concrete federated entities, record the incompatibility with a minimal failing test, and defer only `@interfaceObject`; the remaining advanced features stay in scope.

This step deliberately excludes AWS, CDK, FLOCI, Kubernetes, WebFlux, reactive application code and WebClient, external databases, persistence, observability stacks, and production deployment. (Spring GraphQL's own transitive `reactor-core` dependency is unavoidable and permitted; no application code is reactive.) The complete system runs locally with Docker Compose and is controlled through a Makefile.

Step 0 intentionally departs from `plan.md`'s Maven monorepo baseline. Its two application services and local orchestration project live in three independent, local-only Git repositories to demonstrate service ownership and release boundaries.

## Repository layout

Create three sibling directories and run `git init` in each:

```text
sgl/
├── sgl-local/              # Compose, Makefile, Router, E2E tests, docs
├── sgl-products/           # Products Spring Boot subgraph
└── sgl-pricing/            # Pricing Spring Boot subgraph
```

No remote origin or hosting provider is required in Step 0. Do not implement `make clone` or assume GitHub repository URLs.

Each Spring service has its own Maven Wrapper, `pom.xml`, Dockerfile, tests, README, and independent Git history. They are not modules in a shared Maven reactor.

The `sgl-local` repository is the developer entry point:

```text
sgl-local/
├── .env.example
├── Makefile
├── compose.yaml
├── pom.xml
├── mvnw
├── mvnw.cmd
├── .mvn/
│   └── wrapper/
├── router/
│   ├── router.yaml
│   ├── supergraph.yaml
│   └── supergraph.graphql
├── schemas/
│   ├── products.graphql          # generated from running Products
│   └── pricing.graphql           # generated from running Pricing
├── scripts/
│   ├── compose-supergraph.sh
│   ├── export-subgraphs.sh
│   ├── smoke-test.sh
│   ├── wait-for-healthy.sh
│   └── toggle-pricing.sh          # stop/start the pricing container, used by the E2E harness
├── src/test/java/                # JSON RestClient plus multipart JDK HttpClient E2E harness
└── docs/
```

Generated subgraph SDL files and the composed supergraph are committed so schema changes are reviewable. They must never be edited by hand.

## Pinned technology baseline

Use these stable versions:

| Component | Version |
|---|---:|
| Java | 21 |
| Spring Boot | 4.1.0 |
| JUnit | 6.1.2 |
| Apollo Federation JVM support | 6.1.0 |
| GraphQL Java | 25.0, managed by Spring Boot |
| Maven Surefire Plugin | 3.5.4 |
| Maven Failsafe Plugin | 3.5.4 |
| Apollo Router | 2.15.0 |
| Rover CLI | 0.40.0 |
| Federation composition | 2.8.0 |

Both services use:

- `spring-boot-starter-graphql`
- `spring-boot-starter-webmvc`
- `spring-boot-starter-actuator`
- `com.apollographql.federation:federation-graphql-java-support:6.1.0`
- Spring Boot's test starter and Spring GraphQL Test
- JUnit BOM 6.1.2

Pricing implements its Decimal scalar directly against Spring Boot's managed GraphQL Java 25.0 API. Do not add `graphql-java-extended-scalars`: its 24.0 release targets GraphQL Java 24.1 and is not part of this baseline.

Use Spring Boot's supported `<junit-jupiter.version>6.1.2</junit-jupiter.version>` override in each Maven project, rather than relying on BOM import order. Add Maven Enforcer dependency-convergence checks and confirm with `./mvnw dependency:tree` that the JUnit BOM, Platform, Jupiter API, Jupiter Params, and Jupiter Engine all resolve to 6.1.2. Any remaining JUnit 6.0.3 artifact fails the build.

Before implementing either service, build a minimal compatibility spike containing Spring Boot GraphQL, Federation JVM, the custom Decimal scalar, `FederationSchemaFactory`, and one `_service { sdl }` test. Extend the spike with Federation 2.8 entity-interface fixtures that prove `@key` on an interface, `_entities` resolution for that interface, concrete-type selection, `@interfaceObject` composition, and one mixed-concrete-type batched request. Run `./mvnw dependency:tree`, record GraphQL Java 25.0 in both READMEs, and fail the baseline spike if any other GraphQL Java version is resolved. Do not override Spring Boot's GraphQL Java version.

The entity-interface part of the spike is a separately reported compatibility gate. Its documented fallback applies only to entity-interface federation: keep `CatalogItem` as an ordinary, non-keyed GraphQL interface in Products; keep `@key` on `Product` and `DigitalProduct`; and duplicate the same commercial fields plus `category @external` on concrete `Product` and `DigitalProduct` entities in Pricing. Polymorphism, batching, `@external`/`@requires`, and `@defer` still run end to end. Record the selected mode as `ENTITY_INTERFACE_MODE=interface-object` or `ENTITY_INTERFACE_MODE=concrete-fallback` in both READMEs and `.env.example`; do not silently choose a mode.

Pin Surefire and Failsafe to 3.5.4. Preview, milestone, release-candidate, and snapshot dependencies are excluded.

Do not add WebFlux, direct Reactor dependencies or Reactor-based application code, R2DBC, JDBC, JPA, Hibernate, Flyway, Liquibase, a database driver, or a database container. Spring GraphQL's internal transitive Reactor dependency is allowed; both applications remain synchronous Spring MVC services.

## System architecture

```text
GraphQL client
      |
      v
Apollo Router :4000
      |
      +-----------------------+
      |                       |
      v                       v
Products :8080            Pricing :8080
```

Inside the Compose network, both subgraphs use container port `8080`. Host diagnostic ports are:

- Products: `8081`
- Pricing: `8082`
- Router: `4000`

Apollo Router is the normal client entry point. Direct subgraph ports exist only for local health checks, schema inspection, and tests.

## Service 1: Products subgraph

Repository: `sgl-products`

Products owns the `CatalogItem` entity interface and every concrete entity that implements it. `Product` remains the physical-product type used by the core tutorial query, while `DigitalProduct` makes polymorphism observable:

```graphql
extend schema
  @link(url: "https://specs.apollo.dev/federation/v2.8",
        import: ["@key"])

enum CatalogCategory {
  PHYSICAL
  DIGITAL
}

interface CatalogItem @key(fields: "id") {
  id: ID!
  name: String!
  description: String
  category: CatalogCategory!
}

type Product implements CatalogItem @key(fields: "id") {
  id: ID!
  name: String!
  description: String
  category: CatalogCategory!
  weightGrams: Int!
}

type DigitalProduct implements CatalogItem @key(fields: "id") {
  id: ID!
  name: String!
  description: String
  category: CatalogCategory!
  downloadFormat: String!
}

type Query {
  product(id: ID!): Product
  products: [Product!]!
  catalog: [CatalogItem!]!
}
```

Implement:

- `FederationSchemaFactory` as a bean.
- `GraphQlSourceBuilderCustomizer` using the federation schema factory.
- `@QueryMapping` for `product` and `products`.
- `@QueryMapping` for polymorphic `catalog`.
- A GraphQL Java `TypeResolver` registered through `RuntimeWiringConfigurer`, mapping the sealed Java `CatalogItem` implementations to `Product` and `DigitalProduct`.
- A sealed Java `CatalogItem` model implemented by immutable `Product` and `DigitalProduct` records.
- A batched `@EntityMapping` accepting the representation ID list and returning catalog items in the same order; interface keys are globally unique across all implementations.
- `@GraphQlExceptionHandler` mapping missing products to `extensions.code = PRODUCT_NOT_FOUND`.
- `/actuator/health` for readiness.

Expose only the Actuator health endpoint:

```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
management.endpoints.web.discovery.enabled=false
```

`discovery.enabled=false` is required, not cosmetic: Spring Boot Actuator serves a discovery page at `/actuator` listing every exposed endpoint's link regardless of the `exposure.include` allowlist, so without it "only health is exposed" would be false at `/actuator` even while `/actuator/env` and friends correctly 404.

Use an immutable, deterministic plain Java repository. Seed:

| ID | Name | `category` | `weightGrams` | `downloadFormat` |
|---|---|---|---:|---|
| `p-100` | Mechanical Keyboard | `PHYSICAL` | `950` | — |
| `p-200` | Wireless Mouse | `PHYSICAL` | `95` | — |
| `p-300` | USB-C Dock | `PHYSICAL` | `210` | — |
| `d-400` | Spring GraphQL Field Guide | `DIGITAL` | — | `PDF` |

The three `p-*` items are physical `Product` values; `d-400` is a `DigitalProduct`. No physical and digital item may share an ID because `CatalogItem.id` is the entity-interface key.

`category` is deliberately not a realistic product taxonomy for Step 0: its only two enum values are `PHYSICAL` and `DIGITAL`, one per concrete type. The Java seed repository uses `CatalogCategory.PHYSICAL` for all three `p-*` records and `CatalogCategory.DIGITAL` for `d-400`; it does not retain string literals. `weightGrams` is seeded only for physical items since `DigitalProduct` does not declare that field.

There are no mutations. State resets on process restart.

## Service 2: Pricing subgraph

Repository: `sgl-pricing`

Pricing contributes commercial fields to every `CatalogItem` implementation through `@interfaceObject` and owns quote calculation:

```graphql
extend schema
  @link(url: "https://specs.apollo.dev/federation/v2.8",
        import: ["@key", "@interfaceObject", "@external", "@requires"])

scalar Decimal

enum CatalogCategory {
  PHYSICAL
  DIGITAL
}

input QuoteInput {
  quantity: Int!
}

type CatalogItem @key(fields: "id") @interfaceObject {
  id: ID!
  category: CatalogCategory! @external
  price: Decimal!
  quote(input: QuoteInput!): Quote!
  priceLabel: String! @requires(fields: "category")
}

type Quote {
  unitPrice: Decimal!
  quantity: Int!
  subtotal: Decimal!
}
```

This is the preferred `interface-object` schema. If and only if the compatibility spike selects `concrete-fallback`, Pricing instead declares equivalent keyed `Product` and `DigitalProduct` types, repeating `price`, `quote`, `priceLabel`, and `category @external` on both. The resolvers share the same implementation and batching repository; the fallback must not fork business logic.

Implement:

- `FederationSchemaFactory` and its `GraphQlSourceBuilderCustomizer`.
- An immutable `CatalogItemRef(String id, @Nullable CatalogCategory category)` Java record. `category` is absent when no selected Pricing field requires it; entity resolution explicitly converts the Router representation's enum string to the local Java enum and rejects unknown values. The `priceLabel` resolver may rely on it being present because `@requires` controls that query plan.
- An immutable `QuoteInput(int quantity)` Java record bound from the required GraphQL input object.
- A batched `@EntityMapping` for `CatalogItem` representations.
- `@BatchMapping` for `CatalogItem.price`.
- A synchronous `@SchemaMapping` for `CatalogItem.quote`.
- A synchronous `priceLabel` resolver that consumes the externally owned `category` supplied by Router because of `@requires`; it must not call Products directly.
- Exception handlers producing `PRICE_NOT_FOUND` and `VALIDATION_ERROR`.
- `/actuator/health` with only health exposed, using the same `exposure.include=health`, `show-details=never`, and `discovery.enabled=false` properties as Products.

Seed prices for exactly the same catalog-item IDs (three physical, one digital):

- `p-100`: `99.90`
- `p-200`: `49.50`
- `p-300`: `189.00`
- `d-400`: `24.00`

Every seeded catalog item must have a seeded price. A missing price violates this invariant and is rejected during Pricing entity resolution, before GraphQL resolves the non-null `price` field. Entity resolution checks membership through an immutable known-ID set or a separate `containsProductId` operation; it must not invoke the counted `findAllByProductId` bulk method and thereby invalidate the batching assertion.

`priceLabel` is an exhaustive enum mapping: `PHYSICAL` becomes `"Physical price"` and `DIGITAL` becomes `"Digital price"`. Its purpose is to demonstrate a real cross-subgraph dependency: Router fetches `category` from Products even when the client did not request it, includes it in the Pricing representation, and removes it from the client response unless requested.

### Decimal scalar and money policy

Pricing defines one GraphQL Java 25-compatible `GraphQLScalarType` named exactly `Decimal`, backed by `Coercing<BigDecimal, String>`, and registers it through a `RuntimeWiringConfigurer`. Products does not declare or configure this scalar.

The coercing contract is:

- Internal Java value: `BigDecimal`.
- Result JSON representation: a quoted plain-decimal string such as `"99.90"`; never exponent notation.
- Variable input: a decimal string is the canonical fractional form. Numeric JSON variables are accepted only from types that convert to `BigDecimal` without loss — `BigDecimal`, `BigInteger`, `Byte`, `Short`, `Integer`, and `Long`. `Float` and `Double` are rejected outright: Jackson has already collapsed them into binary floating point before the scalar sees the value, and no downstream coercion recovers the original decimal digits.
- Literal input: accept GraphQL `StringValue` and `IntValue` directly. `FloatValue` literals are also accepted — unlike JSON numeric variables, graphql-java's parser keeps a `FloatValue`'s original arbitrary-precision `BigDecimal`, so a decimal literal never passes through a lossy `double`.
- Reject null where the SDL is non-null, non-finite numbers, booleans, objects, arrays, blank strings, malformed decimals, and values that cannot be represented as `BigDecimal`.
- Convert coercion failures into standard sanitized GraphQL scalar errors without echoing raw input.

Step 0's schema exposes `Decimal` as output only: `price`, `unitPrice`, and `subtotal` are the only `Decimal` fields, and no query or field argument in the federated client contract accepts a `Decimal` input. There is consequently no real GraphQL example exercising `parseValue`/`parseLiteral` end-to-end — a mutation is not added solely to manufacture one. Those methods are implemented for `Coercing` completeness and are exercised directly by Pricing's scalar unit tests instead.

Business code normalizes monetary results before returning them:

- Java type: `BigDecimal`
- Scale: 2
- Rounding: `RoundingMode.HALF_UP`
- Formula: `subtotal = normalized unitPrice × quantity`, followed by scale normalization
- Serialization: quoted plain-decimal string through the custom scalar

Reject quantities below 1 with `extensions.code = VALIDATION_ERROR`.

### Avoiding N+1 price lookups

The in-memory price repository exposes:

```java
Map<String, BigDecimal> findAllByProductId(Set<String> productIds);
```

The `@BatchMapping` method:

1. Receives all `CatalogItemRef` sources collected for the GraphQL request.
2. Deduplicates their IDs while preserving stable behavior.
3. Calls `findAllByProductId` exactly once.
4. Maps prices back to the original `CatalogItemRef` record keys.
5. Never calls a single-product repository lookup.

`CatalogItemRef` is a record so it has stable value-based `equals` and `hashCode`, which are required when parent objects are keys in a mapped batch result.

Spring GraphQL registers the batch method through its request-scoped DataLoader mechanism. Step 0's N+1 claim applies specifically to `CatalogItem.price`; it does not claim batched quote calculation.

`CatalogItem.quote` performs one explicit `findAllByProductId(Set.of(item.id()))` bulk lookup for the single item being quoted and then calculates synchronously. The repository has no single-item lookup method. A list query requesting `quote` may therefore perform one singleton bulk lookup per item and is outside Step 0's batching claim.

There are no downstream REST calls, retries, circuit breakers, bulkheads, asynchronous orchestration, or WireMock services.

## Federated client contract

All normal requests go to `http://localhost:4000/`.

Single product and quote:

```graphql
query ProductWithQuote($id: ID!, $quantity: Int!) {
  product(id: $id) {
    id
    name
    description
    price
    quote(input: { quantity: $quantity }) {
      unitPrice
      quantity
      subtotal
    }
  }
}
```

Example variables:

```json
{
  "id": "p-100",
  "quantity": 2
}
```

Catalog query exercising `@BatchMapping`:

```graphql
query ProductCatalog {
  products {
    id
    name
    price
  }
}
```

For the three seeded products, Pricing performs one bulk price lookup, not three individual lookups. The subgraphs never call each other directly; Router coordinates entity representations using `Product.id`.

Polymorphic catalog query exercising the entity interface, inline fragments, `@interfaceObject`, `@external`, and `@requires`:

```graphql
query PolymorphicCatalog {
  catalog {
    id
    name
    __typename
    price
    priceLabel
    ... on Product {
      weightGrams
    }
    ... on DigitalProduct {
      downloadFormat
    }
  }
}
```

The response contains both concrete `__typename` values. Although this operation does not request `category`, Router obtains it from Products and supplies it to Pricing for `priceLabel`. Pricing batches prices for the mixed list in one bulk lookup.

Incremental-delivery query:

```graphql
query DeferredCatalog {
  catalog {
    id
    name
    __typename
    ... @defer(label: "commercial-data") {
      price
      priceLabel
    }
  }
}
```

`@defer` is a client-operation directive interpreted by Apollo Router; it is not added to either subgraph SDL. The client sends:

```http
Accept: multipart/mixed;deferSpec=20220824, application/json
```

Router returns identity data in the initial multipart part and commercial fields in a later labeled patch. The synchronous Spring MVC subgraphs return ordinary GraphQL JSON to Router and require no WebFlux or subgraph-native incremental-delivery implementation.

### Advanced-feature evidence

Commit human-readable query-plan fixtures, or sanitized Router query-plan snapshots if the pinned Router exposes them through an explicitly enabled development configuration, for:

- `ProductWithQuote`: Products fetch followed by Pricing entity fetch.
- `PolymorphicCatalog`: concrete type resolution, external `category` transport, and mixed entity hydration.
- `DeferredCatalog`: an initial fetch followed by a deferred commercial-data branch.

Query-plan evidence is tutorial material, not a byte-stable API contract. Tests assert structural markers and subgraph participation rather than an entire Router-formatted plan whose presentation may change between patch releases.

## Runtime SDL export and composition

Composition uses the SDL exposed by the running applications, not hand-copied schema fragments.

`compose.yaml` includes these services:

- `products`
- `pricing`
- `router`
- `rover`, enabled only through a tooling profile

The Rover service uses:

```text
ghcr.io/apollographql/rover:0.40.0
APOLLO_ELV2_LICENSE=accept
```

`scripts/export-subgraphs.sh <output-dir>`:

1. Requires healthy Products and Pricing containers.
2. Runs pinned Rover in the Compose network.
3. Executes `rover subgraph introspect http://products:8080/graphql`.
4. Writes stdout to `<output-dir>/products.graphql`.
5. Repeats for `http://pricing:8080/graphql`, writing `<output-dir>/pricing.graphql`.
6. Validates that both outputs are non-empty and contain the pinned Federation `@link`. In `interface-object` mode, both declare `CatalogItem`, Products declares it as an interface, and Pricing declares it as an object with `@interfaceObject`. In `concrete-fallback` mode, Products declares the ordinary `CatalogItem` interface and both outputs declare `Product` and `DigitalProduct` entities. The selected mode comes from the documented environment value and a schema that matches neither mode fails validation.

The output directory is a required argument rather than a hardcoded path, so the same script can write into the committed `schemas/` directory or into a disposable scratch directory depending on the caller.

`make export-schemas` invokes it directly against `schemas/`, replacing each of `schemas/products.graphql` and `schemas/pricing.graphql` through a same-filesystem temporary-file rename. Replacement is atomic per file, not across the two-file set; interruption between renames can leave mixed schema versions. This is an explicit, SDL-only maintenance command: it never touches `router/supergraph.graphql` and is not part of `make compose`'s transaction below. Use `make compose` when schemas and supergraph must be published together with rollback.

`router/supergraph.yaml`:

- Pins `federation_version: =2.8.0`.
- Names the subgraphs `products` and `pricing`.
- Uses routing URLs `http://products:8080/graphql` and `http://pricing:8080/graphql`.
- Reads schema paths supplied by the caller — the committed `schemas/` files for a standalone recompose, or a scratch directory during `make compose`.

`make compose` is validate-then-publish with rollback, orchestrated by `scripts/compose-supergraph.sh` — not strictly filesystem-atomic, since replacing three separate files across `schemas/` and `router/` cannot be one atomic operation, and a process interruption between individual replacements could still leave a mixed set of artifacts on disk:

1. Create a scratch directory with `mktemp -d`; register cleanup for `EXIT`, `HUP`, `INT`, and `TERM`. Track a publication-success flag so signal or failure cleanup restores backups, while a completed publication only removes scratch state.
2. Export both subgraph SDLs into the scratch directory via `scripts/export-subgraphs.sh <scratch-dir>`. Committed `schemas/*.graphql` are not touched yet.
3. Write a scratch `supergraph.yaml`, identical to the committed configuration except its schema paths point at the scratch SDLs.
4. Run `rover supergraph compose --config <scratch-supergraph.yaml>`, writing to a scratch `supergraph.graphql`.
5. Validate all three scratch outputs are non-empty and well-formed.
6. Back up the three currently committed files (`schemas/products.graphql`, `schemas/pricing.graphql`, `router/supergraph.graphql`) into the scratch directory before replacing any of them.
7. Replace the three committed files with the validated scratch outputs.
8. Only after all three renames succeed, set the publication-success flag. Otherwise the registered cleanup restores the step-6 backups over the committed files — whether from composition failure, validation failure, or an interrupted publish.

If composition fails at step 4 or validation fails at step 5, no committed file is ever touched. If the process is interrupted mid-publish (step 7), the trap's restore leaves the repository back at its pre-`make compose` state on any normal exit path. This is rollback-on-failure, not crash-atomicity: a hard kill (`SIGKILL`, power loss) during step 7 skips the trap entirely and could still leave a mixed set of artifacts. True crash-atomic publication would require staging all generated artifacts under one directory and swapping that directory's path in a single operation (e.g. a symlink flip); Step 0 does not implement that. `router/supergraph.graphql` is committed but never edited manually.

### Determinism and staleness

`make compose-check`:

1. Exports both runtime schemas into a temporary directory.
2. Fails if the temporary Products SDL differs byte-for-byte from committed `schemas/products.graphql`.
3. Fails if the temporary Pricing SDL differs byte-for-byte from committed `schemas/pricing.graphql`.
4. Creates a temporary `supergraph.yaml` that is identical to the committed configuration except that its schema paths point to the temporary exports.
5. Composes twice from those same temporary inputs.
6. Fails if the two generated outputs differ byte-for-byte.
7. Fails if the deterministic output differs from committed `router/supergraph.graphql`.

Temporary files live under a `mktemp -d` directory mounted read-write into the Rover tooling container and are removed by a shell trap. This proves Rover output determinism before using byte comparison as a stale-artifact gate, and it catches composition-neutral subgraph SDL drift — a comment, directive reordering, or formatting change — that would otherwise leave a committed subgraph schema stale while still producing a byte-identical supergraph.

## Apollo Router

Run:

```text
ghcr.io/apollographql/router:v2.15.0
```

Router configuration:

- Binds to `0.0.0.0:4000`.
- Loads `router/router.yaml`.
- Loads the local `router/supergraph.graphql`.
- Uses Compose DNS routing URLs from the supergraph.
- Enables Apollo Sandbox with `sandbox.enabled: true`, `supergraph.introspection: true`, and `homepage.enabled: false`.
- Enables health at `0.0.0.0:8088/health`; Compose checks `http://127.0.0.1:8088/health` inside the Router container and waits for HTTP 200.
- Does not set `APOLLO_KEY` or `APOLLO_GRAPH_REF`.
- Does not enable licensed GraphOS features.
- Leaves Router's supported `@defer` execution enabled and accepts the documented multipart negotiation header.

The first run requires network access to download Maven artifacts, container images, and Rover's pinned Federation composition plugin. Later offline runs work only while these artifacts remain cached locally.

Router execution, curl examples, and automated tests work offline with cached artifacts. Apollo Sandbox is an optional browser convenience and is not part of the offline acceptance criterion because its browser assets may require internet access.

## Docker Compose

Products and Pricing use multi-stage Dockerfiles parameterized with build arguments, so Compose can pin an architecture-specific base image without editing the Dockerfile:

```dockerfile
ARG BUILD_IMAGE=eclipse-temurin:21.0.11_10-jdk-jammy
FROM ${BUILD_IMAGE} AS build
...
ARG RUNTIME_IMAGE=eclipse-temurin:21.0.11_10-jre-jammy
FROM ${RUNTIME_IMAGE}
```

1. Build with their Maven Wrapper on `${BUILD_IMAGE}` (default `eclipse-temurin:21.0.11_10-jdk-jammy`), packaging with `./mvnw -DskipTests package`. Maven tests already ran via `make test`/`make verify` on the host before the image build starts, so the build stage does not pay for a second in-container test run.
2. Run on `${RUNTIME_IMAGE}` (default `eclipse-temurin:21.0.11_10-jre-jammy`).
3. Run as a non-root user.
4. Publish container port `8080`.

Install `curl` in the runtime image before switching to the non-root user, remove package-manager caches in the same layer, and print `curl --version` during the build. Compose checks each subgraph with `curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health`.

Resolve and commit the amd64 and arm64 manifest digests for both Temurin tags in `.env.example` during scaffolding (e.g. `PRODUCTS_BUILD_IMAGE_AMD64`, `PRODUCTS_BUILD_IMAGE_ARM64`, and the matching runtime-image variables). Compose does not select among these variables on its own, so the Makefile resolves the host architecture explicitly before invoking Compose:

1. Read `docker info --format '{{.Architecture}}'`.
2. Map `x86_64`/`amd64` to the `_AMD64` digest variables.
3. Map `aarch64`/`arm64` to the `_ARM64` digest variables.
4. Fail with a diagnostic message for any other architecture.
5. Export the selected values as the generic `BUILD_IMAGE`/`RUNTIME_IMAGE` environment variables that `compose.yaml`'s `build.args` consumes.

The plain tags in the Dockerfile `ARG` defaults remain only the undigested fallback for a bare `docker build` outside this Makefile; every Makefile-driven build (`make build`, `make up`, `make verify`) passes the resolved digest-pinned values instead. Missing digest values fail `make verify`.

Compose health checks call `/actuator/health`. Router cannot start until:

- Products is healthy.
- Pricing is healthy.
- `router/supergraph.graphql` exists and passed composition.

The first two conditions are native Compose `depends_on: condition: service_healthy` checks. The third is not expressible as a Compose healthcheck. It is enforced by `make up` and `make verify`: `compose-check` validates the committed supergraph against temporary runtime exports before either target invokes `docker compose up router`. Compose itself does not know about the supergraph file's state.

No database, application-state volume, cloud emulator, or external SaaS service is included.

## Makefile interface

The `sgl-local/Makefile` is the supported command surface:

```text
make help            Show commands and prerequisites
make test            Run unit and service integration tests
make build           Build service images (tests already ran via `make test`; the Docker build stage does not re-run them)
make export-schemas  Start/wait for subgraphs and export runtime SDL
make compose         Export SDL and regenerate the committed supergraph (validate-then-publish, with rollback on failure)
make compose-check   Prove determinism and detect stale generated artifacts
make up              Build and start the complete healthy system
make smoke           Run the basic federated Router query
make e2e             Run the RestClient E2E suite
make logs            Follow Router and subgraph logs
make ps              Show container and health status
make down            Stop this Compose project
make clean           Remove only project-owned build/temp artifacts — never committed schemas or the supergraph; export-schemas/compose update schemas and only compose updates the supergraph
make verify          Run the complete verification workflow
```

`make up` is non-mutating with respect to committed schema artifacts and executes:

1. Run service tests.
2. Build service images.
3. Start Products and Pricing.
4. Wait for both health endpoints with a bounded timeout.
5. Run `make compose-check` with temporary runtime SDL, Rover configuration, and supergraph files.
6. Start Router with the committed supergraph that just passed verification.
7. Wait for Router with a bounded timeout.
8. Run the smoke query through Router.

Requirements:

- `make up` is idempotent.
- Every wait has a bounded timeout and diagnostic logs on failure.
- Scripts use strict shell settings.
- Ports and image tags can be overridden without editing committed files.
- Cleanup is restricted to this Compose project and its generated artifacts.
- `make verify` always invokes `make down` in a failure-safe cleanup phase.
- Neither `make up` nor `make verify` changes committed SDL or supergraph files.

`make verify` is non-mutating with respect to committed schema artifacts and executes:

1. Run unit and service integration tests.
2. Build service images.
3. Start Products and Pricing and wait for health.
4. Run `make compose-check` entirely with temporary schema, configuration, and supergraph files.
5. Start Router with the already committed and verified `router/supergraph.graphql`.
6. Wait for `http://127.0.0.1:8088/health` inside the Router container.
7. Run smoke and RestClient E2E tests.
8. Run `make down` from a shell trap whether verification succeeds or fails.

Only explicit `make export-schemas` or `make compose` may replace committed `schemas/*.graphql`; only `make compose` may replace `router/supergraph.graphql`. `make verify` invokes neither target, so it never mutates either.

## Testing strategy

### Maven lifecycle

- Surefire 3.5.4 runs unit tests named `*Test`.
- Failsafe 3.5.4 runs service integration tests named `*IT`.
- Failsafe 3.5.4 runs orchestration tests named `*E2E`.
- `@Tag("integration")` and `@Tag("e2e")` remain available for optional filtering.
- Tags are not the only lifecycle separation mechanism.

### JUnit style

Use JUnit Jupiter features when they improve coverage or clarity:

- `@ParameterizedTest` with `@CsvSource` for table-driven money cases.
- `@MethodSource` for structured values and GraphQL variables.
- `@NullSource`, `@EmptySource`, and `@ValueSource` for applicable validation boundaries.
- `@Nested` for success, validation, missing-data, batching, and federation scenarios.
- `assertAll` for related properties of one result.
- `@TempDir` for generated schema and supergraph tests.
- Timeouts only for operations that can hang.

Example quote table:

```java
@ParameterizedTest(name = "{index}: {0} x {1} = {2}")
@CsvSource({
    "99.90, 1, 99.90",
    "99.90, 2, 199.80",
    "10.01, 3, 30.03"
})
void calculatesSubtotal(String unitPrice, int quantity, String expectedSubtotal) {
    // Invoke production code and assert using BigDecimal.
}
```

Do not add dynamic tests, custom extensions, repetition, or parallel execution without a concrete need.

### Products tests

- Plain unit tests for repository lookup and stable seed ordering.
- GraphQL integration tests for `product`, `products`, and polymorphic `catalog`.
- Type-resolver tests for `Product` and `DigitalProduct`, including inline fragments and `__typename`, asserting the exact seeded `weightGrams` values (`950`, `95`, `210` for `p-100`/`p-200`/`p-300`) and `d-400.downloadFormat == "PDF"`.
- `_service { sdl }` test.
- Batched `_entities` resolution tests for concrete entities and the `CatalogItem` entity interface, including a mixed-type batch and an unknown ID.
- `PRODUCT_NOT_FOUND` error-contract test.
- Health exposure test proving `/actuator/health` is available, another Actuator endpoint (e.g. `/actuator/env`) is unavailable, and the Actuator discovery page at `/actuator` is also unavailable.

### Pricing tests

- Unit tests for bulk price lookup.
- Parameterized quote, scale, and rounding tests.
- Quantity boundary and validation tests.
- Decimal scalar tests covering: quoted plain-string output; accepted decimal-string and integral numeric variables (`BigDecimal`, `BigInteger`, `Byte`, `Short`, `Integer`, `Long`); rejected fractional `Float`/`Double` variables; accepted `StringValue`, `IntValue`, and `FloatValue` GraphQL literals exercised directly against `parseLiteral`; exponent normalization; malformed input; non-finite values; and rejected non-scalar input.
- `_service { sdl }` and `_entities` tests.
- `@interfaceObject` reference resolution and optional representation-category handling.
- A `@ParameterizedTest` over `priceLabel`'s total derivation, asserting both seeded cases exactly: `category = PHYSICAL` → `priceLabel = "Physical price"`, and `category = DIGITAL` → `priceLabel = "Digital price"`.
- GraphQL integration tests for valid `QuoteInput` binding, zero-quantity business validation, required input/quantity validation, and invalid representation categories.
- Positive and negative composition fixtures for matching interface keys, missing `@interfaceObject`, and invalid `@external`/`@requires` dependencies.
- `PRICE_NOT_FOUND` and `VALIDATION_ERROR` contracts.
- Health exposure test proving `/actuator/health` is available and both another Actuator endpoint and the `/actuator` discovery page are unavailable.

The core batching integration test uses a counting repository test double and requests prices for the three physical Product sources. The advanced variant requests the mixed four-item catalog. It asserts in both cases:

- Exactly one `findAllByProductId` call.
- The requested ID set is `{p-100, p-200, p-300}` for the core case and `{p-100, p-200, p-300, d-400}` for the advanced case.
- No single-item price lookup occurs.
- All requested prices are returned: three in the core case and four in the advanced case.

The repository invocation-count proof belongs to Pricing's integration suite. An external Router test cannot observe internal repository calls.

### Local orchestration E2E tests

`sgl-local` is a Maven/JUnit test project, not a Spring Boot application. It uses:

- `spring-boot-starter-restclient:4.1.0` and synchronous Spring `RestClient`
- Jackson for GraphQL request and response JSON
- JDK `HttpClient` only for the streaming multipart `@defer` test, because the ordinary `RestClient` harness is intentionally JSON-oriented
- JUnit 6.1.2
- Failsafe 3.5.4

All E2E application requests target `http://localhost:4000/`.

Test:

- Single product response containing fields from both subgraphs.
- `products { id name price }` returning all three physical-product seeds (`Query.products` cannot return `d-400`, a `DigitalProduct`); the mixed four-item catalog is covered separately by the polymorphic `catalog` query.
- Quote calculation.
- Polymorphic catalog results with `Product` and `DigitalProduct` inline fragments and correct `__typename`.
- `priceLabel` resolved through `@external`/`@requires`, asserting the exact labels `"Physical price"` (for `p-100`/`p-200`/`p-300`) and `"Digital price"` (for `d-400`) in the polymorphic catalog result, while an unrequested `category` is absent from the client response.
- Mixed physical and digital catalog pricing.
- Multipart `@defer` delivery through Router.
- Invalid quantity with `VALIDATION_ERROR`.
- Missing product with `PRODUCT_NOT_FOUND`.
- Router behavior when Pricing is stopped.

The unavailable-Pricing assertion requires HTTP 200, a non-empty GraphQL `errors` array, and no fabricated `price` or `quote` value. It does not assert Router-specific error messages or paths that may change between Router patches.

`RestClient` alone cannot stop or restart a container, so the unavailable-Pricing test needs an explicit control path: `scripts/toggle-pricing.sh {stop|start}` wraps `docker compose stop pricing` / `docker compose start pricing` for this specific Compose project, and the test invokes it via `ProcessBuilder`, checking the exit code. No Testcontainers dependency is introduced; the script is the only container-control surface the E2E harness uses.

The unavailable-Pricing test must restore Pricing in `finally`/`@AfterEach` by calling `toggle-pricing.sh start`, poll `scripts/wait-for-healthy.sh` (or equivalent bounded wait) until Pricing reports healthy again, and leave the system usable even when its assertions fail.

E2E verifies observable federation behavior. Pricing integration tests prove the internal batching call count.

The `@defer` E2E test uses `HttpClient.BodyHandlers.ofInputStream()` and a bounded timeout. It must:

- Send the documented multipart `Accept` header.
- Validate the response `Content-Type` and parse its declared boundary instead of hard-coding one.
- Assert the initial payload contains catalog identity fields and indicates more results.
- Assert the initial payload does not contain `price` or `priceLabel`.
- Assert a later incremental patch is labeled `commercial-data` and supplies the commercial fields at valid response paths.
- Reassemble the patches and compare the resulting data with the equivalent non-deferred query.
- Close the response stream on success and failure.

The test asserts multipart order and contents, not wall-clock speed. Artificial sleeps are not added to production resolvers merely to make incremental delivery visually dramatic.

## Documentation

Document:

- Prerequisites: Java 21, Docker with Compose v2, Git, and Make.
- How to create and initialize the three sibling repositories.
- Why Step 0 intentionally differs from the parent monorepo plan.
- How to run `make up`, query Router, inspect logs, and stop the system.
- Field ownership and Product entity representations.
- `CatalogItem` polymorphism, Java sealed types, inline fragments, globally unique interface keys, and concrete type resolution.
- Federation entity interfaces, `@interfaceObject`, and the compatibility fallback.
- `@external`/`@requires` data flow and the corresponding Router query plan.
- `@defer`, multipart transport, client requirements, and how to run the deferred example with `curl --no-buffer`.
- `@EntityMapping`, `@BatchMapping`, and request-scoped DataLoader behavior.
- How the counting test proves N+1 prevention.
- Decimal scalar wiring and money rules.
- Why in-memory state resets.
- Runtime SDL export and supergraph regeneration.
- ELv2 acceptance and first-run network/cache requirements.
- Common Docker, port, health, schema-export, and composition failures.

## Explicitly out of scope

- Remote Git hosting and automated cloning.
- AWS or any other cloud deployment.
- AWS CDK and FLOCI.
- Kubernetes.
- WebFlux, WebClient, direct Reactor APIs, or reactive repositories. Framework-internal transitive Reactor usage is allowed.
- External databases and persistence.
- REST orchestration and WireMock.
- Resilience4j.
- OpenTelemetry, Prometheus, Grafana, Jaeger, Splunk, or Honeycomb.
- Authentication, authorization, and public exposure.
- `@override`, `@shareable`, `@provides`, `@tag`, `@inaccessible`, contracts, persisted queries, authorization directives, subscriptions, `@stream`, demand control, coprocessors, and custom Router plugins. These are intentionally not bundled into the coherent Step 0 advanced gate.
- CI/CD publishing and production hardening.

## Delivery sequence

1. Create and initialize the three local Git repositories.
2. Scaffold both Maven services with pinned dependencies and plugin versions.
3. Complete the baseline federation-plus-Decimal compatibility spike; verify one GraphQL Java version and JUnit 6.1.2 alignment.
4. Run and record the entity-interface compatibility spike, including its narrowly scoped fallback decision.
5. Implement the core Products and Pricing path first with concrete `Product` federation, Decimal, and batching.
6. Add Actuator health configuration and Dockerfiles.
7. Start the subgraphs, export runtime Federation SDL, compose it deterministically, and start Router.
8. Add the Makefile workflow and ordinary JSON RestClient E2E harness; pass the core acceptance gate.
9. Expand Products to the sealed `CatalogItem` model and add `DigitalProduct` plus type resolution.
10. Replace Pricing's concrete contribution with `CatalogItem @interfaceObject`, or apply the explicitly selected concrete fallback; add `@external`/`@requires`, mixed-type batching tests, and negative composition fixtures.
11. Regenerate committed SDL and supergraph artifacts, then capture structural query-plan evidence.
12. Add the streaming multipart `@defer` E2E test and documentation.
13. Run failure-safe `make verify` from a clean local sibling workspace and pass the advanced acceptance gate.
14. Finalize executable documentation.

## Acceptance criteria

- Exactly two Spring Boot application services exist, each in its own local Git repository.
- `sgl-local` is a third local Git repository and contains orchestration plus tests, not an application service.
- Both services use Java 21, Spring Boot 4.1.0, JUnit 6.1.2, and synchronous servlet execution.
- All `com.graphql-java:graphql-java` paths resolve to 25.0, and all `org.junit.*` artifacts resolve to 6.1.2.
- Surefire and Failsafe 3.5.4 execute the documented test lifecycles.
- Neither service contains WebFlux, direct Reactor dependencies/application code, database, ORM, or migration dependencies; framework-transitive Reactor is allowed.
- Products, prices, and quotes use deterministic plain Java in-memory state.
- Products exposes a polymorphic `CatalogItem` interface backed by sealed Java types and returns both physical `Product` and `DigitalProduct` values with correct `__typename`.
- Pricing registers and tests the Decimal scalar.
- Only `/actuator/health` is exposed from the Spring services.
- Products owns the `CatalogItem` entity interface and all its implementations; Pricing contributes commercial fields through `@interfaceObject`, unless the documented compatibility spike activates the narrow concrete-entity fallback.
- In `interface-object` mode, interface entity keys are globally unique across concrete implementations; the same global-ID invariant is retained in fallback mode so switching modes does not change data identity.
- `@external`/`@requires` causes Router to transport the Products-owned `category` to Pricing without exposing it when the client did not request it.
- Runtime `_service` SDL is exported from both healthy subgraphs.
- Rover composes the runtime schemas with pinned Federation 2.8.0.
- Composition is byte-for-byte deterministic, and stale committed subgraph SDL or supergraph output is detected.
- Apollo Router exposes one federated endpoint on localhost without GraphOS credentials.
- A client retrieves Products and Pricing fields in one Router query.
- The subgraphs never call each other directly.
- Pricing resolves catalog-item prices with `@BatchMapping`.
- A Pricing integration test proves N mixed catalog items produce one bulk price lookup, and entity validation does not call the counted bulk method.
- Structural query-plan evidence documents entity hydration, required external-field transport, and the deferred branch.
- A multipart-aware E2E test proves Router-driven `@defer`, validates the dynamic boundary, observes the labeled incremental patch, and reconstructs the same data as the non-deferred operation.
- Docker Compose builds and runs the full local system without cloud services.
- Committed amd64 and arm64 Temurin manifest digests are present and enforced by `make verify`.
- `make up`, `make down`, and failure-safe `make verify` behave as documented.
- `make verify` detects stale schema artifacts without modifying tracked schema or supergraph files.
- Unit, GraphQL integration, federation, composition, JSON RestClient E2E, and multipart JDK HttpClient E2E tests pass.
- No external account, database, cloud emulator, remote Git host, or host-installed Rover binary is required.
- The original `plan.md` remains unchanged.
