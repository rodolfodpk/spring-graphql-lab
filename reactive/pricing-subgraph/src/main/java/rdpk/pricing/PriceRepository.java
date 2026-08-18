package rdpk.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Every lookup returns through a reactive type and represents a missing row as an empty
 * {@code Mono} rather than an exception. The backing map never blocks, so this buys nothing at
 * runtime — it keeps the repository interface shaped like the seam a real datastore would occupy.
 *
 * <p>{@code app.repository.delay} simulates that datastore's latency, and defaults to zero so
 * ordinary runs and every test behave exactly as before. Set it only when comparing stacks:
 * without I/O to overlap, a reactive server and a servlet server have nothing to distinguish
 * them. Here the wait is a {@code delaySubscription} that occupies no thread at all.
 */
@Repository
public class PriceRepository {

    private final Map<String, BigDecimal> prices = Map.of(
            "p-100", new BigDecimal("99.90"),
            "p-200", new BigDecimal("49.50"),
            "p-300", new BigDecimal("189.00"),
            "d-400", new BigDecimal("24.00"));

    private final Duration delay;

    @Autowired
    public PriceRepository(@Value("${app.repository.delay:0ms}") Duration delay) {
        this.delay = delay;
    }

    /** Unit tests construct directly and always want the zero-latency behaviour. */
    public PriceRepository() {
        this(Duration.ZERO);
    }

    public Mono<Map<String, BigDecimal>> findAllByProductId(Set<String> productIds) {
        return withLatency(Mono.fromSupplier(() -> {
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            new LinkedHashSet<>(productIds).forEach(id -> {
                BigDecimal price = prices.get(id);
                if (price != null) {
                    result.put(id, price);
                }
            });
            return Map.copyOf(result);
        }));
    }

    public Mono<Boolean> containsProductId(String id) {
        return withLatency(Mono.fromSupplier(() -> prices.containsKey(id)));
    }

    /**
     * Reads a single price. {@code CatalogItem.quote} batches through a DataLoader instead, but
     * the price subscription needs one starting value and validation in a single lookup rather
     * than a check-then-read against {@link #containsProductId}.
     */
    public Mono<BigDecimal> findByProductId(String id) {
        return withLatency(Mono.fromSupplier(() -> prices.get(id)));
    }

    /**
     * Applied only when configured, so the zero-latency default leaves the original assembly
     * untouched — no scheduler hop, and nothing for the deferral assertions to trip over.
     */
    private <T> Mono<T> withLatency(Mono<T> source) {
        return delay.isZero() ? source : source.delaySubscription(delay);
    }
}
