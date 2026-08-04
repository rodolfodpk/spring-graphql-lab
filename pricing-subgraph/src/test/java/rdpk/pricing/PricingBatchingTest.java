package rdpk.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class PricingBatchingTest {

    @Test
    void mixedCatalogUsesOneBulkLookup() {
        CountingPriceRepository repository = new CountingPriceRepository();
        PricingController controller = new PricingController(repository);
        List<CatalogItemRef> items = List.of(
                new CatalogItemRef("p-100", null),
                new CatalogItemRef("p-200", null),
                new CatalogItemRef("p-300", null),
                new CatalogItemRef("d-400", null));

        Map<CatalogItemRef, BigDecimal> prices = controller.price(items).block();

        assertEquals(1, repository.calls);
        assertEquals(Set.of("p-100", "p-200", "p-300", "d-400"), repository.lastIds);
        assertEquals(4, prices.size());
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
