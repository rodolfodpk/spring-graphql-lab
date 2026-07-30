package rdpk.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.test.tester.GraphQlTester;

/**
 * {@code CatalogItem.quote} takes an argument, so it cannot use {@code @BatchMapping}.
 * It batches through a named DataLoader instead, which only dispatches under real
 * GraphQL execution — hence an integration test rather than a plain unit test.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class PricingQuoteBatchingIT {

    @Autowired
    GraphQlTester graphQlTester;

    @Autowired
    CountingPriceRepository repository;

    @Test
    void mixedCatalogQuotesUseOneBulkLookup() {
        repository.reset();

        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem {
                      quote(input: { quantity: 2 }) { subtotal }
                    }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100"),
                        Map.of("__typename", "CatalogItem", "id", "p-200"),
                        Map.of("__typename", "CatalogItem", "id", "p-300"),
                        Map.of("__typename", "CatalogItem", "id", "d-400")))
                .execute()
                .path("_entities[0].quote.subtotal").entity(String.class).isEqualTo("199.80")
                .path("_entities[3].quote.subtotal").entity(String.class).isEqualTo("48.00");

        assertEquals(1, repository.bulkCalls);
        assertEquals(Set.of("p-100", "p-200", "p-300", "d-400"), repository.lastIds);
    }

    @TestConfiguration
    static class CountingRepositoryConfiguration {

        @Bean
        @Primary
        CountingPriceRepository countingPriceRepository() {
            return new CountingPriceRepository();
        }
    }

    static final class CountingPriceRepository extends PriceRepository {

        int bulkCalls;
        Set<String> lastIds = Set.of();

        void reset() {
            bulkCalls = 0;
            lastIds = Set.of();
        }

        @Override
        public Map<String, BigDecimal> findAllByProductId(Set<String> productIds) {
            bulkCalls++;
            lastIds = Set.copyOf(productIds);
            return super.findAllByProductId(productIds);
        }
    }
}
