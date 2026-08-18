package rdpk.pricing;

import rdpk.model.PricingException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Reactive twin of the servlet batching test. Both assert one bulk lookup for four ids; only this
 * one can assert <em>when</em> that lookup happens, which is the behaviour the two stacks actually
 * differ on.
 */
class PricingBatchingTest {

    private static final List<CatalogItemRef> ITEMS = List.of(
            new CatalogItemRef("p-100", null),
            new CatalogItemRef("p-200", null),
            new CatalogItemRef("p-300", null),
            new CatalogItemRef("d-400", null));

    private static final Map<CatalogItemRef, BigDecimal> EXPECTED = Map.of(
            ITEMS.get(0), new BigDecimal("99.90"),
            ITEMS.get(1), new BigDecimal("49.50"),
            ITEMS.get(2), new BigDecimal("189.00"),
            ITEMS.get(3), new BigDecimal("24.00"));

    @Test
    void mixedCatalogUsesOneBulkLookup() {
        CountingPriceRepository repository = new CountingPriceRepository();
        PricingController controller = new PricingController(repository);

        Mono<Map<CatalogItemRef, BigDecimal>> prices = controller.price(ITEMS);

        // The claim is narrow and deliberate: building the returned Mono does not subscribe to the
        // repository. The method is not free of eager work -- it assembles the id set first.
        assertEquals(0, repository.calls);

        StepVerifier.create(prices)
                .expectNext(EXPECTED)
                .verifyComplete();

        assertEquals(1, repository.calls);
        assertEquals(Set.of("p-100", "p-200", "p-300", "d-400"), repository.lastIds);
    }

    /**
     * Reachable only from here. {@code reference()} rejects unknown ids through
     * {@code containsProductId} before any ref can arrive at the batch mapping, so this pins the
     * controller's internal defence rather than anything a client can provoke.
     */
    @Test
    void reportsAMissingPriceAsAnErrorSignal() {
        PricingController controller = new PricingController(new PriceRepository());

        StepVerifier.create(controller.price(List.of(new CatalogItemRef("nope", null))))
                .expectErrorSatisfies(error -> {
                    PricingException exception = assertInstanceOf(PricingException.class, error);
                    assertEquals("PRICE_NOT_FOUND", exception.code());
                })
                .verify();
    }

    private static final class CountingPriceRepository extends PriceRepository {
        int calls;
        Set<String> lastIds;

        /** Counts on subscribe so the tally reflects lookups performed, not Monos assembled. */
        @Override
        public Mono<Map<String, BigDecimal>> findAllByProductId(Set<String> productIds) {
            return super.findAllByProductId(productIds)
                    .doOnSubscribe(subscription -> {
                        calls++;
                        lastIds = Set.copyOf(productIds);
                    });
        }
    }
}
