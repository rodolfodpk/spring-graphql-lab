package rdpk.products;

import rdpk.model.CatalogCategory;
import rdpk.model.CatalogItem;
import rdpk.model.DigitalProduct;
import rdpk.model.Product;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Servlet twin of the reactive repository. A missing row is an empty {@link Optional} here rather
 * than an empty {@code Mono}; the seeded data and the published catalog order are identical.
 *
 * <p>{@code app.repository.delay} simulates datastore latency and defaults to zero. When set, this
 * blocks the request thread where the reactive twin defers a subscription — the difference
 * {@code spring.threads.virtual.enabled} exists to soften.
 */
@Repository
public final class CatalogRepository {

    /**
     * Catalog order is part of the published contract, so it is stated explicitly rather than
     * read from the backing map. {@code Map.copyOf} makes no promise about iteration order.
     */
    private static final List<String> CATALOG_ORDER = List.of("p-100", "p-200", "p-300", "d-400");

    private final Map<String, CatalogItem> items;

    private final Duration delay;

    @Autowired
    public CatalogRepository(@Value("${app.repository.delay:0ms}") Duration delay) {
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
        this.delay = delay;
    }

    /** Unit tests construct directly and always want the zero-latency behaviour. */
    public CatalogRepository() {
        this(Duration.ZERO);
    }

    public Optional<CatalogItem> findById(String id) {
        simulateLatency();
        return Optional.ofNullable(items.get(id));
    }

    public List<CatalogItem> findAll() {
        simulateLatency();
        return CATALOG_ORDER.stream().map(items::get).toList();
    }

    public List<Product> findProducts() {
        return findAll().stream().filter(Product.class::isInstance).map(Product.class::cast).toList();
    }

    /** No-op unless configured, so the default path performs no sleep and no interrupt handling. */
    private void simulateLatency() {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating repository latency", exception);
        }
    }
}
