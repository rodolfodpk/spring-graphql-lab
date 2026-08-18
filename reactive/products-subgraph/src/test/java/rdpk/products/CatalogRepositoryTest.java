package rdpk.products;

import rdpk.model.CatalogCategory;
import rdpk.model.DigitalProduct;
import rdpk.model.Product;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Reactive twin of the servlet catalog test. Records give value equality, so asserting the seeded
 * items whole covers order, every field, the exact element count, and completion in one pass.
 */
class CatalogRepositoryTest {

    private final CatalogRepository repository = new CatalogRepository();

    @Test
    void preservesSeedOrderAndExactSubtypeData() {
        StepVerifier.create(repository.findAll())
                .expectNext(new Product("p-100", "Mechanical Keyboard",
                        "A deterministic mechanical keyboard", CatalogCategory.PHYSICAL, 950))
                .expectNext(new Product("p-200", "Wireless Mouse",
                        "A deterministic wireless mouse", CatalogCategory.PHYSICAL, 95))
                .expectNext(new Product("p-300", "USB-C Dock",
                        "A deterministic USB-C dock", CatalogCategory.PHYSICAL, 210))
                .expectNext(new DigitalProduct("d-400", "Spring GraphQL Field Guide",
                        "A digital field guide", CatalogCategory.DIGITAL, "PDF"))
                .verifyComplete();
    }
}
