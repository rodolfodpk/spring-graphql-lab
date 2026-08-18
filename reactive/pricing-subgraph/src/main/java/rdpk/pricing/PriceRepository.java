package rdpk.pricing;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Every lookup returns through a reactive type and represents a missing row as an empty
 * {@code Mono} rather than an exception. The backing map never blocks, so this buys nothing at
 * runtime — it keeps the repository interface shaped like the seam a real datastore would occupy.
 */
@Repository
public class PriceRepository {

    private final Map<String, BigDecimal> prices = Map.of(
            "p-100", new BigDecimal("99.90"),
            "p-200", new BigDecimal("49.50"),
            "p-300", new BigDecimal("189.00"),
            "d-400", new BigDecimal("24.00"));

    public Mono<Map<String, BigDecimal>> findAllByProductId(Set<String> productIds) {
        return Mono.fromSupplier(() -> {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            new LinkedHashSet<>(productIds).forEach(id -> {
                BigDecimal price = prices.get(id);
                if (price != null) {
                    result.put(id, price);
                }
            });
            return Map.copyOf(result);
        });
    }

    public Mono<Boolean> containsProductId(String id) {
        return Mono.fromSupplier(() -> prices.containsKey(id));
    }

    /**
     * Reads a single price. {@code CatalogItem.quote} batches through a DataLoader instead, but
     * the price subscription needs one starting value and validation in a single lookup rather
     * than a check-then-read against {@link #containsProductId}.
     */
    public Mono<BigDecimal> findByProductId(String id) {
        return Mono.fromSupplier(() -> prices.get(id));
    }
}
