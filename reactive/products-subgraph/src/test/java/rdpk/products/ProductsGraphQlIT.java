package rdpk.products;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;

@SpringBootTest
@AutoConfigureGraphQlTester
class ProductsGraphQlIT {

    @Autowired
    GraphQlTester graphQlTester;

    @Test
    void resolvesPolymorphicCatalog() {
        graphQlTester.document("""
                {
                  catalog {
                    id
                    category
                    __typename
                    ... on Product { weightGrams }
                    ... on DigitalProduct { downloadFormat }
                  }
                }
                """)
                .execute()
                .path("catalog[*].category").entityList(String.class)
                    .containsExactly("PHYSICAL", "PHYSICAL", "PHYSICAL", "DIGITAL")
                .path("catalog[0].weightGrams").entity(Integer.class).isEqualTo(950)
                .path("catalog[1].weightGrams").entity(Integer.class).isEqualTo(95)
                .path("catalog[2].weightGrams").entity(Integer.class).isEqualTo(210)
                .path("catalog[3].__typename").entity(String.class).isEqualTo("DigitalProduct")
                .path("catalog[3].downloadFormat").entity(String.class).isEqualTo("PDF");
    }

    @Test
    void exposesFederationSdlWithEntityInterface() {
        graphQlTester.document("{ _service { sdl } }")
                .execute()
                .path("_service.sdl").entity(String.class)
                .satisfies(sdl -> {
                    org.junit.jupiter.api.Assertions.assertTrue(sdl.contains("interface CatalogItem"));
                    org.junit.jupiter.api.Assertions.assertTrue(sdl.contains("@key"));
                });
    }
    /**
     * A representation whose id resolves to the wrong subtype must report the documented
     * PRODUCT_NOT_FOUND code. Narrowing with a bare cast instead of a filter would surface a
     * ClassCastException here, and both stacks must agree.
     */
    @Test
    void rejectsARepresentationWhoseIdResolvesToTheWrongSubtype() {
        assertWrongSubtype("DigitalProduct", "p-100");
        assertWrongSubtype("Product", "d-400");
    }

    private void assertWrongSubtype(String typename, String id) {
        graphQlTester.document("""
                query Entities($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { id }
                  }
                }
                """)
                .variable("representations", java.util.List.of(
                        java.util.Map.of("__typename", typename, "id", id)))
                .execute()
                .errors()
                .satisfy(errors -> org.junit.jupiter.api.Assertions.assertEquals(
                        "PRODUCT_NOT_FOUND",
                        errors.get(0).getExtensions().get("code")));
    }
}
