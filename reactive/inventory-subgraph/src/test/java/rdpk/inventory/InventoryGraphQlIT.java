package rdpk.inventory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest
@AutoConfigureGraphQlTester
class InventoryGraphQlIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void inventoryEndpoints(DynamicPropertyRegistry registry) {
        registry.add("app.inventory.warehouse-base-url", wireMock::baseUrl);
        registry.add("app.inventory.supplier-base-url", wireMock::baseUrl);
    }

    @Autowired
    GraphQlTester graphQlTester;

    @Test
    void batchesAllInventoryFieldsAndPublishesFederationSdl() {
        wireMock.stubFor(get(urlPathEqualTo("/warehouse")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        [{"productId":"p-100","stockLevel":8,"restockEta":null},
                         {"productId":"d-400","stockLevel":0,"restockEta":"2026-09-01"}]
                        """)));
        wireMock.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"data":{"suppliers":[
                          {"productId":"p-100","name":"Acme","rating":4.5},
                          {"productId":"d-400","name":"Spring Press","rating":4.8}
                        ]}}
                        """)));

        graphQlTester.document("""
                query($representations: [_Any!]!) {
                  _entities(representations: $representations) {
                    ... on CatalogItem { id stockLevel restockEta supplier { name rating } }
                  }
                }
                """)
                .variable("representations", java.util.List.of(
                        java.util.Map.of("__typename", "CatalogItem", "id", "p-100"),
                        java.util.Map.of("__typename", "CatalogItem", "id", "d-400")))
                .execute()
                .path("_entities[0].stockLevel").entity(Integer.class).isEqualTo(8)
                .path("_entities[1].restockEta").entity(String.class).isEqualTo("2026-09-01")
                .path("_entities[0].supplier.name").entity(String.class).isEqualTo("Acme");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/warehouse")));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/graphql")));

        graphQlTester.document("{ _service { sdl } }").execute().path("_service.sdl").entity(String.class)
                .satisfies(sdl -> assertEquals(true, sdl.contains("@interfaceObject")));
    }
}
