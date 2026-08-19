# Dual-stack lab: stack groups over a shared domain model

## Context

The lab currently exists as one branch plus one unmerged commit. `main` is the servlet/Tomcat
stack; `webflux-reactive-stack` (`a19cec3`) is the reactive rewrite, and `main` is a strict
ancestor of it — there is no divergence yet. Keeping the two stacks as two long-lived branches
would mean writing every future federation lesson, doc fix, and Boot upgrade twice by
cherry-pick, forever, and CI only triggers on `main` so the reactive half is currently unguarded.

Both stacks are being kept, so they move onto one branch as two **stack groups** over one shared,
stack-neutral domain model. The intended outcome: **the two stacks are behaviourally
indistinguishable from outside.** They publish byte-identical SDL, compose into the identical
supergraph, and pass the identical E2E suite. The only observable differences are the ones
genuinely inherent to the stack — and those become the lesson.

The shared model is what makes that lesson legible. Without it the comparison is buried in 21
duplicated classes; with it, the entire delta between stacks is **five files**.

### One tension to record honestly

A shared domain jar across independently deployable subgraphs is, in a real federated system, a
distributed-monolith smell: federation's premise is that each subgraph owns its types and can ship
without coordinating. `lab-model` deliberately trades that away to make the stack comparison
readable. That is the right call for a teaching lab and the wrong call for production, and the
docs should say so in the same voice the repo already uses for "reactive buys nothing here."
Federation ownership is unchanged — `model` is a compile-time library, never a runtime service,
and the two subgraphs still own their own SDL.

## Runtime topology: what the stack choice does *not* touch

The entry point at `:4000` is **Apollo Router `v2.15.0`** (`supergraph/router/Dockerfile`) — a Rust
binary on Tokio/Hyper, not Spring and not the JVM. Router does all cross-subgraph fan-out, and that
fan-out is async and non-blocking *regardless of which stack the subgraphs run*. There is no
"reactive vs blocking" decision available at that tier.

The only Java in `supergraph/` is test code — no `src/main` — and it is blocking:
`RestClient.create("http://127.0.0.1:4000/")` plus `java.net.http.HttpClient`.

So the choice is scoped entirely to **what happens inside each subgraph process after Router has
already fanned out.** Query planning, cross-subgraph concurrency, and `@defer` incremental delivery
(assembled by Router; subgraphs return ordinary GraphQL JSON either way) are unaffected. This is
why one unmodified E2E suite can serve as the parity proof, and it must be the first thing the new
CONCEPTS section says — otherwise a reader assumes the reactive stack buys concurrency Router was
providing all along.

## Layout

```
pom.xml                              aggregator only (not a parent)
mvnw, .mvn/                          the sole build entry point
.dockerignore                        root, since Docker context becomes the repo root
model/                               rdpk:lab-model — pure Java 21
reactive/
  products-subgraph/                 artifactId products-subgraph
  pricing-subgraph/                  artifactId pricing-subgraph
servlet/
  products-subgraph/                 artifactId products-subgraph-servlet
  pricing-subgraph/                  artifactId pricing-subgraph-servlet
supergraph/                          composition + E2E, stack-swappable
```

Four independently deployable service artifacts. Directory paths are stack-grouped; artifactIds
keep the `-servlet` suffix so the two groups produce distinct jar names — which matters, because
each Dockerfile copies its jar by name.

Branch: rename `webflux-reactive-stack` to `dual-stack`, then merge to `main` as one PR.

## The model module

`rdpk:lab-model:0.0.1-SNAPSHOT`, package `rdpk.model`, Java 21, with **no *production*
dependencies** — no Spring, GraphQL, Reactor, servlet, Netty, or persistence. Test-scoped JUnit is
expected and does not violate the rule. Ten classes:

- **Catalog:** `CatalogItem` (sealed), `Product`, `DigitalProduct`, `CatalogCategory`,
  `ProductNotFoundException`
- **Pricing:** `Money`, `Quote`, `QuoteInput`, `PriceChange`, `PricingException`

`CatalogCategory` is currently duplicated in `rdpk.pricing` and `rdpk.products` — verified
identical apart from the package line — and collapses to one. That is the only class count that
changes: 21 classes today → 10 shared + 10 per stack group.

Keep the sealed `CatalogItem` hierarchy together in `rdpk.model`; `permits` requires it.

Exceptions stay plain Java carrying their stable `code()`; GraphQL error conversion
(`@GraphQlExceptionHandler`, `GraphqlErrorBuilder`) stays in each subgraph controller.

### `model/pom.xml` must stand on its own

The root pom is **aggregator-only** and is not a parent, so it supplies `model` with nothing. Unlike
the four service modules, `model` does not inherit `spring-boot-starter-parent` either — it has no
parent at all. Everything it needs must be declared explicitly, or `MoneyTest` will not compile,
run, or be measured:

- `maven.compiler.release=21` (and `project.build.sourceEncoding=UTF-8`)
- `dependencyManagement` importing `org.junit:junit-bom:6.1.2`, plus a test-scoped
  `org.junit.jupiter:junit-jupiter` — matching what `supergraph/pom.xml` already does
- `maven-surefire-plugin` 3.5.4, or `MoneyTest` is silently never executed
- `jacoco-maven-plugin` 0.8.13 with `prepare-agent` and a `report` execution bound to `verify` —
  required because CI uploads `model/target/site/jacoco/jacoco.xml`

Version numbers above match the ones already pinned across the existing modules; keep them aligned.

### What pricing actually shares

Pricing shares only the **catalog category vocabulary**, not the Products entity hierarchy. It uses
`CatalogCategory` directly — `CatalogItemRef` carries one, `parseCategory` coerces the federated
`category: CatalogCategory! @external` field, and `priceLabel` switches on it under
`@requires(fields: "category")`. It does not use `CatalogItem`, `Product`, `DigitalProduct`, or
`ProductNotFoundException`.

So one jar spanning two bounded contexts is still a simplification worth naming in the docs — but
the overlap is a shared enum the federation contract already forces both subgraphs to agree on,
not an unused dependency. That is a much easier tradeoff to defend than "pricing depends on types
it never touches," and it is the honest version.

## What stays per service module — and what actually differs

Ten classes per stack group:

| Module | Classes |
|---|---|
| `<stack>/pricing-subgraph` | `PricingApplication`, `PricingController`, `PriceRepository`, `FederationConfiguration`, `DecimalScalar`, `CatalogItemRef` |
| `<stack>/products-subgraph` | `ProductsApplication`, `ProductController`, `CatalogRepository`, `FederationConfiguration` |

Of those ten, **five are byte-identical across stacks** — `PricingApplication`,
`ProductsApplication`, `DecimalScalar`, `CatalogItemRef`, `products/FederationConfiguration` — and
**five genuinely differ**: `PricingController`, `PriceRepository`, `ProductController`,
`CatalogRepository`, and `pricing/FederationConfiguration`.

`DecimalScalar` and `CatalogItemRef` stay out of the model on purpose: the scalar is graphql-java
coercion, and `CatalogItemRef` is a federation representation stub, not domain vocabulary.

Both `schema.graphqls` files and `subscription.graphqls` copy verbatim between stacks. Each
subgraph keeps declaring its own `enum CatalogCategory` in SDL — one Java enum serving two schemas
in two separate JVMs is fine, and **the published SDL must not change at all.**

## Servlet module contents

### Source of truth: the reactive modules, never `main`

**The current reactive modules define required behaviour. `main` is a reference for *synchronous
shape* only, and is not the source of truth for any behaviour.** `main` predates several deliberate
changes; copying it wholesale reintroduces regressions the reactive branch fixed.

The concrete trap, verified in both sources: reactive `PricingController.reference()` calls
`parseCategory(category)` **before** `repository.containsProductId(id)`, so an invalid category on
an unknown id yields `VALIDATION_ERROR`. `main` checks product existence first and yields
`PRICE_NOT_FOUND`. Same inputs, different error code. Port the reactive precedence.

Also new on the reactive branch and absent from `main`: `PriceRepository.findByProductId`,
`PriceChange`, the whole subscription surface, and `ProductController`'s filter-before-cast
narrowing. Work file-by-file from the reactive twin; consult `main` only to recall the blocking
idiom (`git show main:pricing-subgraph/src/main/java/rdpk/pricing/PricingController.java`).

### `pricing/FederationConfiguration` is not a copy

`BatchLoaderRegistry.registerMappedBatchLoader` requires a `Mono<Map<K,V>>` return. The reactive
repository already returns `Mono`, so the reactive config passes it through:
`registerMappedBatchLoader((ids, env) -> repository.findAllByProductId(ids))`. The servlet
repository returns a bare `Map`, so that expression **will not compile** there; the servlet config
must wrap it — `Mono.just(repository.findAllByProductId(ids))`, exactly what `main` has. This is
the one place `main`'s version is the correct one to take, and it is not an anti-pattern in the
servlet context: with a synchronous repository it is the only legal form.

Verify with `PricingBatchingTest` and `PricingQuoteBatchingIT`, which assert exactly one bulk
lookup for all four ids — the assertion that catches a mis-wired loader.

Pre-existing docs defect to fix while here: `docs/CONCEPTS.md:271-275` still shows the
`Mono.just(...)` form, which is `main`'s blocking-era code documenting the reactive
implementation. Show the reactive form there with the servlet variant beside it.

### Three things `main` does not have at all

1. **Subscriptions.** `main` predates them, but `export-subgraphs.sh` hard-fails unless the pricing
   SDL contains `subscription: Subscription`, `type Subscription`, and `type PriceChange`. Copy
   `subscription.graphqls` unchanged and add a `@SubscriptionMapping` returning `Flux<PriceChange>`
   over the blocking repository: `Flux.defer(...)` around the seeded lookup, then
   `Flux.interval(...).take(5).map(...)`, reusing the same `PRICE_CHANGE_INTERVAL`/`COUNT`/`DELTA`
   constants so emitted values match exactly.

   **This Reactor usage is deliberate and must be documented as such.** graphql-java's subscription
   contract is `Publisher`-based on every transport, so returning a `Flux` here is a *framework
   contract*, not a reactive-stack leak — and `reactor-test` stays in the servlet pricing pom for
   the same reason. Otherwise a reader flags the `Flux` as copy-paste and tries to "fix" it.

2. **The servlet WebSocket starter.** Verified from bytecode:
   `GraphQlWebMvcAutoConfiguration$WebSocketConfiguration` is gated on
   `@ConditionalOnClass({HttpMessageConverter, jakarta.websocket.server.ServerContainer, org.springframework.web.socket.WebSocketHandler})`
   and `@ConditionalOnProperty("spring.graphql.websocket.path")`. So `servlet/pricing-subgraph`
   needs `spring-boot-starter-websocket` and must keep `spring.graphql.websocket.path=/graphql`.
   SSE needs nothing extra — `org.springframework.graphql.server.webmvc.GraphQlSseHandler` already
   ships in `spring-graphql`. This is the single dependency the reactive stack does not need, and
   it is exactly what `docs/CONCEPTS.md:349` predicts in prose — now demonstrated, not asserted.

3. **The filter-before-cast fix.** Reactive `ProductController` narrows with
   `.filter(type::isInstance).map(type::cast)`; `main` still uses bare `.map(Product.class::cast)`,
   which surfaces a wrong-subtype id as `ClassCastException` instead of `PRODUCT_NOT_FOUND`.

   **Add a direct test in both stacks, not just the implementation note.** New cases in
   `ProductsGraphQlIT` sending an `_entities` representation for `DigitalProduct` carrying `p-100`,
   and a `Product` representation carrying `d-400`, asserting the `PRODUCT_NOT_FOUND` extension
   code. Without it a future edit reinstates the bare cast and nothing fails.

Per servlet module also: `spring-boot-starter-webmvc` and `-webmvc-test` in place of the webflux
pair, `application.properties` (only `spring.application.name` changes), and `README.md`.

## Build

**Root aggregator `pom.xml`**, listing `model`, the four services, and `supergraph`. It is *not*
the parent of the Spring Boot modules — those keep `spring-boot-starter-parent:4.1.0` as parent
with `<relativePath/>`. All four service poms declare a `rdpk:lab-model` dependency.

**The root Maven wrapper is the sole supported entry point.** Remove the four nested `mvnw`/`.mvn`
directories: a standalone module build can no longer resolve `lab-model` and would fail
confusingly.

One consequence to document rather than discover: `supergraph` is in the aggregator, and its
failsafe binding runs `**/*E2E.java` against services that must already be up. **A bare
`./mvnw verify` at the repo root is therefore not a supported command** — every invocation is
scoped, e.g. `./mvnw -pl model,reactive/products-subgraph,reactive/pricing-subgraph -am verify`,
and the Makefile is the real interface. State this in the root README.

## Docker and runtime switching

`supergraph/compose.yaml` hardcodes service names `products`/`pricing`, and those names are baked
into `router/supergraph.yaml` routing URLs, the composed `router/supergraph.graphql`, and the Rover
introspect URLs in `scripts/export-subgraphs.sh`. **Do not add new services** — a second pair also
breaks `scripts/wait-for-healthy.sh`, which requires *every* listed service healthy.

Switch by build context and Dockerfile selection instead:

```yaml
  products:
    build:
      context: ..
      dockerfile: ${STACK:-reactive}/products-subgraph/Dockerfile
  pricing:
    build:
      context: ..
      dockerfile: ${STACK:-reactive}/pricing-subgraph/Dockerfile
```

Keep the `:-reactive` default so a bare `docker compose` invocation still works.

Service names, host ports (8081/8082), healthchecks, router config, `supergraph.yaml`,
`export-subgraphs.sh`, `strip-local-subscription.sh`, `wait-for-healthy.sh`, `smoke-test.sh`,
`toggle-pricing.sh`, and `FederationE2E.java` all stay **untouched**.

Each service Dockerfile (context = repo root) copies, in this order for layer caching:

1. root `mvnw` + `.mvn/`
2. **every** pom in the reactor — root, `model`, all four services, and `supergraph`. `-pl … -am`
   still parses all aggregator-listed modules, so a missing `supergraph/pom.xml` fails the build.
3. `./mvnw -pl <stack>/<service> -am dependency:go-offline`
4. `model/src`, then its own `src`
5. `./mvnw -pl <stack>/<service> -am package`, then copy
   `<stack>/<service>/target/<artifactId>-0.0.1-SNAPSHOT.jar`

Add one root `.dockerignore`; remove the two module-level ones, which no longer apply. Required
rules: `.git`, `**/target`, `supergraph/.tmp`, `docs`, `.idea`. **`**/target` is the load-bearing
one** — it is what keeps generated coverage and report output (`target/site/jacoco/`, surefire and
failsafe reports) out of the build context, since all of it lives under `target/`. No separate
report-directory rule is needed. Without this the context balloons and every build ships the whole
repo, including the other stack's build output.

Accept a known cost: with a root context, a change to either stack or to `docs/` invalidates more
layers than the old per-module contexts did. The pom-then-model-then-source ordering above is the
mitigation.

## Makefile

```make
STACK ?= reactive
ifeq ($(STACK),reactive)
else ifeq ($(STACK),servlet)
else
$(error STACK must be 'reactive' or 'servlet', got '$(STACK)')
endif
export STACK
```

The `$(error)` branch is required, not cosmetic: without it `make verify STACK=servlets` silently
runs the reactive stack and reports green for a stack it never built — the worst failure mode for a
parity claim.

**Every Maven invocation goes through the root wrapper.** The nested wrappers are being deleted, so
any surviving bare `./mvnw` inside `supergraph/` is a hard break — this includes the current `e2e`
and `clean` targets, not just `test`:

```make
test:
	cd .. && ./mvnw -pl model,$(STACK)/products-subgraph,$(STACK)/pricing-subgraph -am verify
	cd .. && ./mvnw -pl supergraph test

e2e:
	cd .. && ./mvnw -pl supergraph verify

clean:
	cd .. && ./mvnw clean
```

One thing this must not break: `FederationE2E` shells out to `./scripts/toggle-pricing.sh` by
**relative** path. Running it as `-pl supergraph` from the root still works, because surefire and
failsafe default their working directory to the module's `${basedir}` — so the script resolves
against `supergraph/`, as it does today. Worth an explicit check the first time the outage tests run
from the root reactor, since a silent cwd change would surface as a confusing "script not found"
inside two otherwise-unrelated tests.

Also: `subgraphs` → add `--force-recreate`.
- `verify-all` → run `down` **before each** stack, not merely between:

```make
verify-all:
	@set -e; \
	$(MAKE) down STACK=reactive; $(MAKE) verify STACK=reactive; \
	$(MAKE) down STACK=servlet;  $(MAKE) verify STACK=servlet
```

`--force-recreate` handles a changed image, but explicit teardown also clears orphans, a half-up
router from an aborted run, and containers whose build context changed without an image-digest
change. The two stacks share service names, host ports, and image tags, so stale Compose state is
the likeliest route to a false green.

## Tests

- **Move to `model`:** `MoneyTest` (pure money math and the `VALIDATION_ERROR` cases).
- **Stay per service module:** `DecimalScalarTest` (graphql-java coercion), `PriceRepositoryTest`,
  `CatalogRepositoryTest`, `PriceLabelTest`, `PricingBatchingTest`, `PricingGraphQlIT`,
  `PricingQuoteBatchingIT`, `PricingSubscriptionIT`, `PricingSubscriptionWebSocketIT`,
  `ProductsGraphQlIT`.
- **Port unchanged to servlet:** `DecimalScalarTest`, `PricingGraphQlIT` (8 tests),
  `ProductsGraphQlIT` — `@AutoConfigureGraphQlTester` behaves identically on both stacks.
- **Drop `.block()`:** `PriceLabelTest`, `CatalogRepositoryTest`, `PricingBatchingTest`.
- **`StepVerifier` → plain assertions:** `PriceRepositoryTest` — re-express "empty `Mono` means
  absent" as `Optional`/null.
- **Counting doubles:** currently count via `.doOnSubscribe`; on a blocking repo just increment in
  the override.
- **`PricingSubscriptionIT`:** ports nearly as-is — `executeSubscription().toFlux()` is Reactor-based
  on both stacks. Same five values `99.90 … 100.30`.
- **`PricingSubscriptionWebSocketIT`:** the one genuinely stack-specific test. The servlet twin
  swaps `ReactorNettyWebSocketClient` for `StandardWebSocketClient` and inverts the context
  assertion — `runsOnAReactiveWebServer` becomes an assertion that the context is a *servlet*
  `WebApplicationContext`. That inversion is the point of the test on this side.

## Docs

The docs assert the lab *is* reactive; they must assert it is *both*, over a shared model.

- `README.md`: intro (line 8), module table (12-25 — now model + two stack groups + supergraph),
  tech-stack paragraph (22-25), `## Start` (27-47, document `STACK=servlet`), `## Test` (49-66,
  root-reactor commands), closing boundary note (99-101).
- `docs/CONCEPTS.md`: retitle `## Schema-first reactive Spring GraphQL` (line 9); rewrite intro
  (11-13) and `### What reactive buys here, and what it does not` (20-51) into two-stack framing;
  promote lines 345-350 from aside to demonstrated result; fix the stale sample at 271-275; extend
  `## Test pyramid` (384-404).
- **New CONCEPTS section, "The same subgraph on two stacks."** The payload of the whole exercise.
  Identical: SDL, composed supergraph, all E2E results, all 10 model classes, and 5 of the 10
  service classes. Different: the web starter, `Mono`/`Flux` vs plain returns, the `Mono.just(...)`
  wrapper the servlet `BatchLoaderRegistry` call requires, `CompletableFuture` vs `Mono.fromFuture`
  for the DataLoader bridge, the extra `spring-boot-starter-websocket`, and the `Flux` that appears
  on *both* stacks because graphql-java requires a `Publisher`. Must also state the topology bound:
  the Router tier is Rust and async either way, so the swap is invisible from `:4000` by
  construction.
- **New CONCEPTS section on the model/adapters split**, including the distributed-monolith tension
  recorded above — honest about why a shared domain jar is right for a lab and wrong for production.
- `docs/index.html`: mirror at lines 25-26, 51-57, 228-231, 239-243.
- **Seven READMEs**, all on one branch: root, one per service module (4), one supergraph, one
  `model`. The root README is deliberately *not* duplicated per stack — the comparison is the thing
  being taught, so the module table and the `STACK` switch sit on one page.
- `supergraph/README.md`: `## Repository layout` (line 13) must show the new tree, and every `make`
  target section gains the `STACK` knob — `up` (46-53), `smoke` (223-228), `test`/`e2e`/`verify`
  (278-300), `compose`/`compose-check` (307-319), `down` (330-335), troubleshooting (347-365).
- `plan-step0.md` is already marked superseded; leave it.

## CI

```yaml
strategy:
  matrix:
    stack: [reactive, servlet]
env:
  STACK: ${{ matrix.stack }}
```

**Job-level `env:`, not per-step.** Every step shelling out to Docker Compose must see it —
including `make down` under `if: always()`. A teardown running without `STACK` falls back to
reactive and, because both stacks share service names and ports, tears down against the wrong
context or leaves the servlet stack running into the next job. A missed teardown usually still
exits zero, which is what makes the per-step form dangerous.

Extend `cache-dependency-path` to the root, `model`, all four service, and `supergraph` poms. Point
codecov `files:` at the matrix stack's two jacoco reports plus `model/target/site/jacoco/jacoco.xml`,
with `flags: ${{ matrix.stack }}`. Add `dual-stack` to the push triggers so the work is guarded
before it lands on `main`.

## Verification

1. Model purity: `./mvnw -pl model dependency:tree -Dscope=compile` lists `lab-model` itself and
   nothing else — no Spring, GraphQL, Reactor, servlet, Netty, or persistence. Scope the check to
   `compile`, or test-scoped JUnit makes it fail spuriously; equivalently, every entry in the
   unfiltered tree must be `(test)`. This is the load-bearing check for the whole model/adapters
   claim. Also confirm `MoneyTest` actually ran (surefire is wired) rather than being silently
   skipped, and that `model/target/site/jacoco/jacoco.xml` exists before CI tries to upload it.
2. Per-stack build through the root reactor:
   `./mvnw -pl model,<stack>/products-subgraph,<stack>/pricing-subgraph -am verify`, both stacks.
3. Reactive path unregressed: `cd supergraph && make verify` (34 module tests + 10 E2E).
4. Both paths from clean: `cd supergraph && make verify-all`.
5. Guard rejects a typo: `make verify STACK=servlets` fails with the `$(error)` message rather than
   quietly running reactive.
6. Error-precedence parity — the specific regression `main` would reintroduce. Against each stack,
   send an `_entities` representation with an invalid category *and* an unknown id; both must return
   `VALIDATION_ERROR`, not `PRICE_NOT_FOUND`. Confirm the new wrong-subtype cases return
   `PRODUCT_NOT_FOUND` on both.
7. Servlet path: `make verify STACK=servlet` — same 10 E2E assertions including `@defer` multipart,
   the pricing-outage tests, and the SSE stream test posting directly to `127.0.0.1:8082`. **The SSE
   test is the sharpest servlet risk**: it exercises `GraphQlSseHandler` on Tomcat; `event:next` × 5
   plus `event:complete` means the servlet subscription surface is correct end to end.
8. **The parity proof:** `make compose-check` under each stack. It re-introspects both subgraphs and
   `cmp`s against the checked-in `schemas/*.graphql`, then composes twice and compares. Passing under
   both stacks means they publish byte-identical SDL and compose to the identical supergraph — the
   entire claim this restructure exists to make. `schemas/*.graphql` and `router/supergraph.graphql`
   must not change at all in the final diff.
9. `docker compose config` under both `STACK` values to confirm the Dockerfile path resolves, and
   confirm the build context is not shipping `.git` or `target` (check reported context size).
10. No Tomcat in the reactive modules, no Netty in the servlet ones:
    `./mvnw -pl <stack>/<service> dependency:tree | grep -iE 'tomcat|netty'`.
