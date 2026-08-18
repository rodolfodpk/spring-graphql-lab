package rdpk.products;

import rdpk.model.CatalogCategory;
import rdpk.model.CatalogItem;
import rdpk.model.DigitalProduct;
import rdpk.model.Product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Servlet twin of the reactive catalog test. The reactive version walks the same items with
 * StepVerifier; here the list is already materialized. Records give value equality either way, so
 * both assert order and every field rather than projecting a few of them.
 */
class CatalogRepositoryTest {

    private final CatalogRepository repository = new CatalogRepository();

    @Test
    void preservesSeedOrderAndExactSubtypeData() {
        List<CatalogItem> expected = List.of(
                new Product("p-100", "Mechanical Keyboard",
                        "A deterministic mechanical keyboard", CatalogCategory.PHYSICAL, 950),
                new Product("p-200", "Wireless Mouse",
                        "A deterministic wireless mouse", CatalogCategory.PHYSICAL, 95),
                new Product("p-300", "USB-C Dock",
                        "A deterministic USB-C dock", CatalogCategory.PHYSICAL, 210),
                new DigitalProduct("d-400", "Spring GraphQL Field Guide",
                        "A digital field guide", CatalogCategory.DIGITAL, "PDF"));

        assertEquals(expected, repository.findAll());
    }
}
