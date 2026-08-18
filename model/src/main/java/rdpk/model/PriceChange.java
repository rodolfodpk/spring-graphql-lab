package rdpk.model;

import java.math.BigDecimal;

/**
 * One snapshot from {@code Subscription.priceChanges}. Sequence 1 carries the unchanged seeded
 * price, so the first emission establishes the baseline rather than reporting a change.
 */
public record PriceChange(String productId, BigDecimal price, int sequence) {
}
