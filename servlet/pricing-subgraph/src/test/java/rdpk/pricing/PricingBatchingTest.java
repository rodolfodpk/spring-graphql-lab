package rdpk.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Servlet twin of the reactive batching test. The counting double increments in the method body
 * rather than in {@code doOnSubscribe}, because here the call <em>is</em> the lookup: there is no
 * gap between assembling a result and performing the work, so there is no deferral to assert. The
 * values asserted are identical, which is the point.
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

        Map<CatalogItemRef, BigDecimal> prices = controller.price(ITEMS);

        assertEquals(EXPECTED, prices);
        assertEquals(1, repository.calls);
        assertEquals(Set.of("p-100", "p-200", "p-300", "d-400"), repository.lastIds);
    }

    private static final class CountingPriceRepository extends PriceRepository {
        int calls;
        Set<String> lastIds;

        @Override
        public Map<String, BigDecimal> findAllByProductId(Set<String> productIds) {
            calls++;
            lastIds = Set.copyOf(productIds);
            return super.findAllByProductId(productIds);
        }
    }
}
