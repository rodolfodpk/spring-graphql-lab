package rdpk.pricing;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Repository;

@Repository
public class PriceRepository {

    private final Map<String, BigDecimal> prices = Map.of(
            "p-100", new BigDecimal("99.90"),
            "p-200", new BigDecimal("49.50"),
            "p-300", new BigDecimal("189.00"),
            "d-400", new BigDecimal("24.00"));

    public Map<String, BigDecimal> findAllByProductId(Set<String> productIds) {
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
        return prices.containsKey(id);
    }
}
