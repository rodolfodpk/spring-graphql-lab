package rdpk.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.CoercingParseValueException;
import org.junit.jupiter.api.Test;

class DecimalScalarTest {

    private final DecimalScalar.DecimalCoercing coercing = new DecimalScalar.DecimalCoercing();
    private final GraphQLContext context = GraphQLContext.newContext().build();

    @Test
    void serializesWithoutExponentNotation() {
        assertEquals("1000", coercing.serialize(new BigDecimal("1E+3"), context, Locale.ROOT));
    }

    @Test
    void acceptsExactVariables() {
        assertEquals(new BigDecimal("99.90"), coercing.parseValue("99.90", context, Locale.ROOT));
        assertEquals(new BigDecimal("12"), coercing.parseValue(BigInteger.valueOf(12), context, Locale.ROOT));
        assertEquals(new BigDecimal("7"), coercing.parseValue(7, context, Locale.ROOT));
    }

    @Test
    void rejectsBinaryFloatingPointVariables() {
        assertThrows(CoercingParseValueException.class,
                () -> coercing.parseValue(1.2d, context, Locale.ROOT));
    }

    @Test
    void acceptsExactGraphQlLiterals() {
        CoercedVariables variables = CoercedVariables.emptyVariables();
        assertEquals(new BigDecimal("1.20"), coercing.parseLiteral(
                new StringValue("1.20"), variables, context, Locale.ROOT));
        assertEquals(new BigDecimal("12"), coercing.parseLiteral(
                new IntValue(BigInteger.valueOf(12)), variables, context, Locale.ROOT));
        assertEquals(new BigDecimal("1.25"), coercing.parseLiteral(
                new FloatValue(new BigDecimal("1.25")), variables, context, Locale.ROOT));
    }
}
