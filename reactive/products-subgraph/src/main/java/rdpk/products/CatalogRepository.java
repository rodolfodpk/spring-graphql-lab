package rdpk.products;

import rdpk.model.CatalogCategory;
import rdpk.model.CatalogItem;
import rdpk.model.DigitalProduct;
import rdpk.model.Product;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code app.repository.delay} simulates datastore latency and defaults to zero, so ordinary runs
 * and every test are unaffected. When set, the wait is a deferred subscription that holds no
 * thread — the servlet twin blocks its request thread for the same interval instead.
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

    public Mono<CatalogItem> findById(String id) {
        return withLatency(Mono.fromSupplier(() -> items.get(id)));
    }

    public Flux<CatalogItem> findAll() {
        return withLatency(Flux.fromIterable(CATALOG_ORDER).map(items::get));
    }

    public Flux<Product> findProducts() {
        return findAll().filter(Product.class::isInstance).cast(Product.class);
    }

    /**
     * One deferred round trip for the whole query, holding no thread. Applied only when
     * {@code app.repository.delay} is set, so the default assembly is untouched.
     */
    private <T> Mono<T> withLatency(Mono<T> source) {
        return delay.isZero() ? source : source.delaySubscription(delay);
    }

    private <T> Flux<T> withLatency(Flux<T> source) {
        return delay.isZero() ? source : source.delaySubscription(delay);
    }
}
