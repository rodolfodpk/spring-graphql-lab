package rdpk.pricing;

import rdpk.model.CatalogCategory;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.test.StepVerifier;

class PriceLabelTest {

    private final PricingController controller = new PricingController(new PriceRepository());

    @ParameterizedTest
    @CsvSource({
            "PHYSICAL, Physical price",
            "DIGITAL, Digital price"
    })
    void derivesTotalLabel(CatalogCategory category, String expected) {
        // No deferral to assert here: the happy path is Mono.just over an already-evaluated
        // switch, so there is nothing that could run later than assembly.
        StepVerifier.create(controller.priceLabel(new CatalogItemRef("p-100", category)))
                .expectNext(expected)
                .verifyComplete();
    }
}
