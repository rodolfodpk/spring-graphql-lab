package rdpk.products;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class CatalogRepositoryTest {

    private final CatalogRepository repository = new CatalogRepository();

    @Test
    void preservesSeedOrderAndExactSubtypeData() {
        var items = repository.findAll();

        assertAll(
                () -> assertEquals(
                        java.util.List.of("p-100", "p-200", "p-300", "d-400"),
                        items.stream().map(CatalogItem::id).toList()),
                () -> assertEquals(
                        java.util.List.of(
                                CatalogCategory.PHYSICAL,
                                CatalogCategory.PHYSICAL,
                                CatalogCategory.PHYSICAL,
                                CatalogCategory.DIGITAL),
                        items.stream().map(CatalogItem::category).toList()),
                () -> assertEquals(950, assertInstanceOf(Product.class, items.get(0)).weightGrams()),
                () -> assertEquals(95, assertInstanceOf(Product.class, items.get(1)).weightGrams()),
                () -> assertEquals(210, assertInstanceOf(Product.class, items.get(2)).weightGrams()),
                () -> assertEquals(
                        "PDF", assertInstanceOf(DigitalProduct.class, items.get(3)).downloadFormat()));
    }
}
