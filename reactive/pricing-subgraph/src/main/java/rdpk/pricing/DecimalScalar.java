package rdpk.pricing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

public final class DecimalScalar {

    public static final GraphQLScalarType INSTANCE = GraphQLScalarType.newScalar()
            .name("Decimal")
            .description("Exact decimal serialized as a plain JSON string")
            .coercing(new DecimalCoercing())
            .build();

    private DecimalScalar() {
    }

    static final class DecimalCoercing implements Coercing<BigDecimal, String> {

        @Override
        public String serialize(Object input, GraphQLContext context, Locale locale) {
            try {
                return toBigDecimal(input, false).toPlainString();
            }
            catch (RuntimeException exception) {
                throw new CoercingSerializeException("Value cannot be serialized as Decimal");
            }
        }

        @Override
        public BigDecimal parseValue(Object input, GraphQLContext context, Locale locale) {
            try {
                return toBigDecimal(input, true);
            }
            catch (RuntimeException exception) {
                throw new CoercingParseValueException("Value is not a valid Decimal");
            }
        }

        @Override
        public BigDecimal parseLiteral(
                Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
            try {
                return switch (input) {
                    case StringValue value -> parseString(value.getValue());
                    case IntValue value -> new BigDecimal(value.getValue());
                    case FloatValue value -> value.getValue();
                    default -> throw new IllegalArgumentException();
                };
            }
            catch (RuntimeException exception) {
                throw new CoercingParseLiteralException("Literal is not a valid Decimal");
            }
        }

        private static BigDecimal toBigDecimal(Object input, boolean rejectFloatingPoint) {
            return switch (input) {
                case BigDecimal value -> value;
                case BigInteger value -> new BigDecimal(value);
                case Byte value -> BigDecimal.valueOf(value.longValue());
                case Short value -> BigDecimal.valueOf(value.longValue());
                case Integer value -> BigDecimal.valueOf(value.longValue());
                case Long value -> BigDecimal.valueOf(value);
                case String value -> parseString(value);
                case Float ignored when rejectFloatingPoint -> throw new IllegalArgumentException();
                case Double ignored when rejectFloatingPoint -> throw new IllegalArgumentException();
                default -> throw new IllegalArgumentException();
            };
        }

        private static BigDecimal parseString(String value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException();
            }
            return new BigDecimal(value);
        }
    }
}
