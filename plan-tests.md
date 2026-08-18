# Make each stack's unit tests assert its own contract

## Context

The dual-stack refactor is done and committed (`807288c`, `9015cd8`, `d06fada`). One rough edge
survived it: three reactive unit tests assert through `.block()`, which makes them structurally
identical to their servlet twins — same shape, `.block()` standing in for the direct call.

That is not a correctness problem. Blocking a JUnit thread is fine, and for a single-value `Mono`
over an in-memory map it is the most readable assertion available. The problem is what it hides:
**the reactive tests verify values but never verify that anything is reactive.** In particular,
nothing anywhere asserts the behaviour that genuinely differs between the stacks — **the repository
lookup is not subscribed to until the returned publisher is subscribed**, where the servlet twin
performs the lookup during the call itself. `PricingBatchingTest` counts through `doOnSubscribe`
precisely because of that contract, and `.block()` makes the distinction invisible.

State the claim that narrowly and no wider. `price()` is *not* free of eager work: it builds the
`ids` `LinkedHashSet` at `PricingController.java:77-78`, before any publisher exists. The property
under test is about the repository subscription, not about the method doing nothing.

Intended outcome: each stack's unit tests assert its own contract in its own idiom, at the same
level of rigour. Reactive gains deferral and completion assertions; servlet gains full-value
assertions so it is not the weaker suite.

### Two findings that shape the work

**1. `priceLabel` throws at assembly, not as an onError signal.**
`reactive/.../PricingController.java:107-110` throws in the method body *before* the `return`, so
no `Mono` is ever constructed. `StepVerifier.create(controller.priceLabel(...))` would blow up
while evaluating the argument — `expectError` can never observe it. Only `assertThrows` around the
call can. Do not write an `expectError` test for this method; it will not work.

`price` is the opposite: `requiredPrice` throws from inside the `.map()` lambda
(`PricingController.java:82` → `:167-173`), so it *is* an onError signal and *is* observable.

**2. The servlet twins do not need "fixing", only strengthening.**
They already assert eagerness correctly — `assertEquals(1, repository.calls)` immediately after a
direct call proves the work happened at call time. What they lack is full-value assertions (they
check `prices.size() == 4`, not the values) and any statement of the contract they embody. Padding
them with `assertThrows` for codes the ITs already cover would be busywork; `PricingGraphQlIT`
lines 58, 75, 92 already assert `VALIDATION_ERROR` for every exception path in these classes.

## House idiom to follow

Established across `PriceRepositoryTest` and `PricingSubscriptionIT`, and the new code must match:

- `StepVerifier.create(publisher)` — never `withVirtualTime` (zero uses; deliberately rejected in
  `PricingSubscriptionIT`'s class Javadoc because `Flux.interval` is built inside the controller).
- `expectNext(fullyConstructedValue)` — the repo has **no** `expectNextMatches`, `assertNext`, or
  `consumeNextWith` anywhere. Build the expected object.
- Unit tests terminate with `.verifyComplete()` (no timeout); integration tests use
  `.expectComplete().verify(TIMEOUT)`.
- `import reactor.test.StepVerifier;` last in the third-party import group. No static imports.

`reactor-test` is already declared in all four subgraph poms. **No pom changes.**

## Reactive changes

### `reactive/pricing-subgraph/.../PricingBatchingTest.java` — the flagship

The one test where deferral is load-bearing. Split the call from the subscription so the gap is
assertable, and assert the full map rather than its size:

```java
Mono<Map<CatalogItemRef, BigDecimal>> prices = controller.price(items);
assertEquals(0, repository.calls);          // assembly performed no lookup

StepVerifier.create(prices)
        .expectNext(expectedPrices)          // all four ids -> normalized values
        .verifyComplete();

assertEquals(1, repository.calls);           // exactly one, and only on subscribe
assertEquals(Set.of("p-100", "p-200", "p-300", "d-400"), repository.lastIds);
```

Keep `CountingPriceRepository`'s `doOnSubscribe` counting and its Javadoc — the new pre-subscribe
assertion is what finally gives that comment teeth.

### `reactive/products-subgraph/.../CatalogRepositoryTest.java`

Replace `findAll().collectList().block()` + `assertAll` with four `expectNext` calls over fully
constructed records, then `verifyComplete()`. Records give value equality, so this asserts order,
every field, exact element count, and completion — strictly more than the current `assertAll`,
and shorter. The seed data appears in the test, which is the point of a seed test.

### `reactive/pricing-subgraph/.../PriceLabelTest.java`

Mechanical swap only:

```java
StepVerifier.create(controller.priceLabel(new CatalogItemRef("p-100", category)))
        .expectNext(expected)
        .verifyComplete();
```

There is deliberately no timing assertion here: the happy path is `Mono.just` over an
already-evaluated `switch`, so there is no deferral to observe. Adding one would assert nothing.

### One genuinely uncovered path (include unless you want the diff minimal)

`price` returns onError when an id has no seeded price (`requiredPrice`, `PricingController.java:169`).
Nothing reaches it: the existing test seeds all four ids, and `PricingGraphQlIT`'s error assertions
cover validation failures, not a missing bulk price. Add a test passing an unseeded
`CatalogItemRef` and assert the **error contract**, not merely its class:

```java
StepVerifier.create(controller.price(List.of(new CatalogItemRef("nope", null))))
        .expectErrorSatisfies(error -> {
            PricingException exception = assertInstanceOf(PricingException.class, error);
            assertEquals("PRICE_NOT_FOUND", exception.code());
        })
        .verify();
```

Two notes for the implementer. `expectErrorSatisfies` is a **deliberate deviation** from house
idiom — the repo's only existing error assertion is the no-arg `expectError()` at
`PricingSubscriptionIT:51`. It is worth the deviation here because the stable extension code is the
actual contract, and a bare class assertion would pass even if the code were wrong.
`assertInstanceOf` is already imported in `CatalogRepositoryTest` and both WebSocket ITs.

Be accurate about what this covers. The path is **unreachable through the GraphQL surface**:
`reference()` rejects unknown ids via `containsProductId` before any `CatalogItemRef` can reach the
batch mapping, so every ref arriving at `price` is already seeded. This test pins the controller's
internal defence-in-depth, not an externally reachable behaviour — worth having, but do not
describe it as closing a user-facing gap.

## Servlet changes

Mirror the value assertions so neither suite is weaker, and state the contract. No new
`assertThrows` — that would duplicate `PricingGraphQlIT`.

- `servlet/.../PricingBatchingTest.java`: assert the full expected map instead of `prices.size()`,
  matching the reactive twin. Add a class Javadoc naming the contrast: the counting double
  increments in the method body because the call *is* the lookup, where the reactive twin must
  count in `doOnSubscribe`. Follow the pattern already set by
  `servlet/.../PriceRepositoryTest.java:14-18`, the existing cross-stack comment.
- `servlet/.../CatalogRepositoryTest.java`: assert the full `List.of(...)` of records rather than
  projecting fields through `assertAll`, mirroring reactive.
- `servlet/.../PriceLabelTest.java`: unchanged. A direct call asserted with `assertEquals` is
  already the right shape.

## Leave alone

Both `PricingSubscriptionWebSocketIT` teardowns keep `tester.stop().block(TIMEOUT)`. It returns
`Mono<Void>`, there is nothing to assert, and `@AfterEach` must complete before the next test — a
StepVerifier there would be noise. These are the only two `.block()` calls that should survive, in
`reactive/` and `servlet/` alike.

## Docs

Two small accuracy fixes in `docs/CONCEPTS.md`:

- The test-pyramid bullet claims the reactive versions "assert with `StepVerifier`" — true only
  after this change. Extend it to say the reactive suite also asserts deferral, which is the
  behaviour the two stacks genuinely differ on.
- The "Controllers may still throw synchronously" bullet says Reactor "converts a throw inside an
  operator into an `onError` signal". That is right for `price`/`reference` but glosses
  `priceLabel`, which throws before any publisher exists and is caught by Spring GraphQL at
  invocation instead. One sentence distinguishing the two, since the plan above depends on it.

## Where this plan lives

Copy this file to `plan-tests.md` in the repository root as the first step, and commit it with the
work. `plan-dual-stack.md` stays untouched — it is the committed record of the refactor that
shipped in `807288c`, not a scratch file to overwrite. This follows the existing one-file-per-
milestone convention (`plan-step0.md`, `plan-dual-stack.md`).

## Verification

1. `make test` and `make test STACK=servlet` — both green. Expect the same test counts as today
   (35 per stack) plus one if the uncovered error path is included.
2. **Prove the new deferral assertion has teeth** — but mutate the right thing. Making
   `PriceRepository.findAllByProductId` eager (`Mono.just(...)` over a pre-built map) does **not**
   kill the test: `CountingPriceRepository` increments inside `doOnSubscribe`, which fires on
   subscription regardless of how the source publisher was constructed, so `calls` is still `0`
   before the test subscribes and the assertion passes vacuously.

   Mutate the subscription instead. Temporarily make `PricingController.price(...)` subscribe to
   the repository publisher before returning it:

   ```java
   Mono<Map<CatalogItemRef, BigDecimal>> result = repository.findAllByProductId(ids).map(...);
   result.subscribe();   // temporary: forces the lookup during the call
   return result;
   ```

   `assertEquals(0, repository.calls)` must now fail. Revert afterwards. An assertion that cannot
   fail is decoration, and this is the one new assertion whose entire value rests on being
   sensitive — which is exactly why the mutant has to target `doOnSubscribe`'s trigger rather than
   the source's laziness.
3. Confirm exactly two `.block(` remain repo-wide, both `@AfterEach` teardowns:
   `grep -rn "\.block(" reactive servlet --include="*.java"`.
4. `make verify-all` once at the end — the E2E and SDL parity results must be unchanged, since
   none of this touches `src/main` (except the temporary mutation in step 2, which is reverted).
