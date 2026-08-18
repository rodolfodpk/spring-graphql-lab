package rdpk.pricing;

import rdpk.model.CatalogCategory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceLabelTest {

    private final PricingController controller = new PricingController(new PriceRepository());

    @ParameterizedTest
    @CsvSource({
            "PHYSICAL, Physical price",
            "DIGITAL, Digital price"
    })
    void derivesTotalLabel(CatalogCategory category, String expected) {
        assertEquals(expected, controller.priceLabel(new CatalogItemRef("p-100", category)));
    }
}
