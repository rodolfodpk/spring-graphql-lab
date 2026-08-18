package rdpk.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Servlet twin of the reactive repository test. The reactive version expresses "absent" as an
 * empty {@code Mono} verified with StepVerifier; here it is an empty {@link Optional}. The values
 * asserted are identical, which is the point.
 */
class PriceRepositoryTest {

    private final PriceRepository repository = new PriceRepository();

    @Test
    void readsASeededPrice() {
        assertEquals(Optional.of(new BigDecimal("99.90")), repository.findByProductId("p-100"));
    }

    @Test
    void reportsAnUnknownPriceAsAbsentRatherThanFailing() {
        assertEquals(Optional.empty(), repository.findByProductId("nope"));
    }

    @Test
    void bulkLookupSkipsUnknownIds() {
        assertEquals(
                Map.of("p-100", new BigDecimal("99.90")),
                repository.findAllByProductId(Set.of("p-100", "nope")));
    }

    @Test
    void reportsPresence() {
        assertTrue(repository.containsProductId("d-400"));
        assertFalse(repository.containsProductId("nope"));
    }
}
