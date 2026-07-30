package rdpk.products;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public final class CatalogRepository {

    private final Map<String, CatalogItem> items;

    public CatalogRepository() {
        Map<String, CatalogItem> seeded = new LinkedHashMap<>();
        seeded.put("p-100", new Product("p-100", "Mechanical Keyboard",
                "A deterministic mechanical keyboard", CatalogCategory.PHYSICAL, 950));
        seeded.put("p-200", new Product("p-200", "Wireless Mouse",
                "A deterministic wireless mouse", CatalogCategory.PHYSICAL, 95));
        seeded.put("p-300", new Product("p-300", "USB-C Dock",
                "A deterministic USB-C dock", CatalogCategory.PHYSICAL, 210));
        seeded.put("d-400", new DigitalProduct("d-400", "Spring GraphQL Field Guide",
                "A digital field guide", CatalogCategory.DIGITAL, "PDF"));
        this.items = Map.copyOf(seeded);
    }

    public Optional<CatalogItem> findById(String id) {
        return Optional.ofNullable(items.get(id));
    }

    public List<CatalogItem> findAll() {
        return List.of(
                items.get("p-100"),
                items.get("p-200"),
                items.get("p-300"),
                items.get("d-400"));
    }

    public List<Product> findProducts() {
        return findAll().stream().filter(Product.class::isInstance).map(Product.class::cast).toList();
    }
}
