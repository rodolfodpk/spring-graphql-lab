package rdpk.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    private Money() {
    }

    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static Quote quote(BigDecimal unitPrice, int quantity) {
        if (quantity < 1) {
            throw new PricingException("VALIDATION_ERROR", "Quantity must be at least 1");
        }
        BigDecimal normalized = normalize(unitPrice);
        return new Quote(normalized, quantity, normalize(normalized.multiply(BigDecimal.valueOf(quantity))));
    }
}
