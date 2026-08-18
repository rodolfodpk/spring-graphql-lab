package rdpk.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @ParameterizedTest(name = "{index}: {0} x {1} = {2}")
    @CsvSource({
            "99.90, 1, 99.90",
            "99.90, 2, 199.80",
            "10.01, 3, 30.03"
    })
    void calculatesSubtotal(String unitPrice, int quantity, String expectedSubtotal) {
        Quote quote = Money.quote(new BigDecimal(unitPrice), quantity);
        assertEquals(new BigDecimal(expectedSubtotal), quote.subtotal());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void rejectsInvalidQuantity(int quantity) {
        PricingException exception = assertThrows(
                PricingException.class, () -> Money.quote(BigDecimal.ONE, quantity));
        assertEquals("VALIDATION_ERROR", exception.code());
    }
}
