package rdpk.pricing;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;

@SpringBootTest
@AutoConfigureGraphQlTester
class PricingGraphQlIT {

    @Autowired
    GraphQlTester graphQlTester;

    @Test
    void resolvesMixedInterfaceObjectEntities() {
        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { id price priceLabel }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100", "category", "PHYSICAL"),
                        Map.of("__typename", "CatalogItem", "id", "d-400", "category", "DIGITAL")))
                .execute()
                .path("_entities[0].price").entity(String.class).isEqualTo("99.90")
                .path("_entities[0].priceLabel").entity(String.class).isEqualTo("Physical price")
                .path("_entities[1].price").entity(String.class).isEqualTo("24.00")
                .path("_entities[1].priceLabel").entity(String.class).isEqualTo("Digital price");
    }

    @Test
    void bindsQuoteInput() {
        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem {
                      quote(input: { quantity: 2 }) { unitPrice quantity subtotal }
                    }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100")))
                .execute()
                .path("_entities[0].quote.unitPrice").entity(String.class).isEqualTo("99.90")
                .path("_entities[0].quote.quantity").entity(Integer.class).isEqualTo(2)
                .path("_entities[0].quote.subtotal").entity(String.class).isEqualTo("199.80");
    }

    @Test
    void rejectsZeroQuantityThroughInput() {
        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { quote(input: { quantity: 0 }) { subtotal } }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100")))
                .execute()
                .errors()
                .satisfy(errors -> org.junit.jupiter.api.Assertions.assertEquals(
                        "VALIDATION_ERROR", errors.getFirst().getExtensions().get("code")));
    }

    @Test
    void rejectsUnknownRepresentationCategory() {
        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { priceLabel }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100", "category", "UNKNOWN")))
                .execute()
                .errors()
                .satisfy(errors -> org.junit.jupiter.api.Assertions.assertEquals(
                        "VALIDATION_ERROR", errors.getFirst().getExtensions().get("code")));
    }

    @Test
    void rejectsMissingRepresentationCategoryWhenLabelRequiresIt() {
        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { priceLabel }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100")))
                .execute()
                .errors()
                .satisfy(errors -> org.junit.jupiter.api.Assertions.assertEquals(
                        "VALIDATION_ERROR", errors.getFirst().getExtensions().get("code")));
    }

    @Test
    void rejectsMissingRequiredQuoteInputAtValidation() {
        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { quote { subtotal } }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100")))
                .execute()
                .errors()
                .satisfy(errors -> org.junit.jupiter.api.Assertions.assertFalse(errors.isEmpty()));
    }

    @Test
    void rejectsMissingQuantityAtValidation() {
        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { quote(input: {}) { subtotal } }
                  }
                }
                """)
                .variable("representations", List.of(
                        Map.of("__typename", "CatalogItem", "id", "p-100")))
                .execute()
                .errors()
                .satisfy(errors -> org.junit.jupiter.api.Assertions.assertFalse(errors.isEmpty()));
    }

    @Test
    void exposesInterfaceObjectSdl() {
        graphQlTester.document("{ _service { sdl } }")
                .execute()
                .path("_service.sdl").entity(String.class)
                .satisfies(sdl -> {
                    org.junit.jupiter.api.Assertions.assertTrue(sdl.contains("type CatalogItem"));
                    org.junit.jupiter.api.Assertions.assertTrue(sdl.contains("@interfaceObject"));
                    org.junit.jupiter.api.Assertions.assertTrue(sdl.contains("@requires"));
                });
    }
}
