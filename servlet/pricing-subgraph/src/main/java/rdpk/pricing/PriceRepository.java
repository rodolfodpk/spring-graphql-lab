package rdpk.pricing;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Servlet twin of the reactive repository. Lookups return plain values and represent a missing row
 * as an empty {@link Optional} rather than an empty {@code Mono}. The seeded prices are identical,
 * so both stacks answer every query with the same numbers.
 *
 * <p>{@code app.repository.delay} simulates datastore latency, defaulting to zero so every test
 * and ordinary run is unaffected. The contrast with the reactive twin is the whole point: there
 * the wait is a deferred subscription holding no thread, here it is {@link Thread#sleep} holding
 * the request thread. Which thread that is — a platform thread or a virtual one — is what
 * {@code spring.threads.virtual.enabled} decides, and why this knob exists.
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

    public Map<String, BigDecimal> findAllByProductId(Set<String> productIds) {
        simulateLatency();
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        new LinkedHashSet<>(productIds).forEach(id -> {
            BigDecimal price = prices.get(id);
            if (price != null) {
                result.put(id, price);
            }
        });
        return Map.copyOf(result);
    }

    public boolean containsProductId(String id) {
        simulateLatency();
        return prices.containsKey(id);
    }

    /**
     * Reads a single price. {@code CatalogItem.quote} batches through a DataLoader instead, but
     * the price subscription needs one starting value and validation in a single lookup rather
     * than a check-then-read against {@link #containsProductId}.
     */
    public Optional<BigDecimal> findByProductId(String id) {
        simulateLatency();
        return Optional.ofNullable(prices.get(id));
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
