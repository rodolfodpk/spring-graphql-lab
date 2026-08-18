package rdpk.model;

import java.math.BigDecimal;

public record Quote(BigDecimal unitPrice, int quantity, BigDecimal subtotal) {
}
