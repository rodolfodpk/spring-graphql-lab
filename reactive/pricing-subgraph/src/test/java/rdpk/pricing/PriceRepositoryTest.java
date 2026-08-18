package rdpk.pricing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class PriceRepositoryTest {

    private final PriceRepository repository = new PriceRepository();

    @Test
    void readsASeededPrice() {
        StepVerifier.create(repository.findByProductId("p-100"))
                .expectNext(new BigDecimal("99.90"))
                .verifyComplete();
    }

    @Test
    void reportsAnUnknownPriceAsAbsentRatherThanFailing() {
        StepVerifier.create(repository.findByProductId("nope"))
                .verifyComplete();
    }

    @Test
    void bulkLookupSkipsUnknownIds() {
        StepVerifier.create(repository.findAllByProductId(Set.of("p-100", "nope")))
                .expectNext(Map.of("p-100", new BigDecimal("99.90")))
                .verifyComplete();
    }

    @Test
    void reportsPresence() {
        StepVerifier.create(repository.containsProductId("d-400")).expectNext(true).verifyComplete();
        StepVerifier.create(repository.containsProductId("nope")).expectNext(false).verifyComplete();
    }
}
