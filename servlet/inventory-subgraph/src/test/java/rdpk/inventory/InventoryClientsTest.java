package rdpk.inventory;

import rdpk.model.InventoryException;
import rdpk.model.Stock;
import rdpk.model.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class InventoryClientsTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @Test
    void readsWarehouseAndSupplierBatchesOverHttp() {
        wireMock.stubFor(get(urlPathEqualTo("/warehouse"))
                .withQueryParam("ids", equalTo("p-100,d-400"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
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

        Set<String> ids = new LinkedHashSet<>(List.of("p-100", "d-400"));
        WarehouseClient warehouse = new WarehouseClient(wireMock.baseUrl(), Duration.ofMillis(500));
        SupplierClient supplier = new SupplierClient(wireMock.baseUrl(), Duration.ofMillis(500));

        assertEquals(Map.of("p-100", new Stock("p-100", 8, null),
                "d-400", new Stock("d-400", 0, "2026-09-01")), warehouse.findAllByProductId(ids));
        assertEquals(Map.of("p-100", new Supplier("Acme", 4.5),
                "d-400", new Supplier("Spring Press", 4.8)), supplier.findAllByProductId(ids));
    }

    @Test
    void mapsUpstreamFailuresToTheStableInventoryCode() {
        wireMock.stubFor(get(urlPathEqualTo("/warehouse"))
                .willReturn(aResponse().withStatus(500)));
        WarehouseClient warehouse = new WarehouseClient(wireMock.baseUrl(), Duration.ofMillis(500));

        InventoryException exception = assertThrows(InventoryException.class,
                () -> warehouse.findAllByProductId(Set.of("p-100")));
        assertEquals("INVENTORY_UNAVAILABLE", exception.code());
    }
    @Test
    void mapsATimeoutToTheStableInventoryCode() {
        wireMock.stubFor(get(urlPathEqualTo("/warehouse")).willReturn(aResponse()
                .withFixedDelay(1500)
                .withHeader("Content-Type", "application/json").withBody("[]")));
        WarehouseClient warehouse = new WarehouseClient(wireMock.baseUrl(), Duration.ofMillis(300));

        assertUnavailable(() -> warehouse.findAllByProductId(Set.of("p-100")));
    }

    @Test
    void mapsAMalformedWarehouseResponseToTheStableInventoryCode() {
        wireMock.stubFor(get(urlPathEqualTo("/warehouse")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("{\"not\":\"an array\"}")));
        WarehouseClient warehouse = new WarehouseClient(wireMock.baseUrl(), Duration.ofMillis(500));

        assertUnavailable(() -> warehouse.findAllByProductId(Set.of("p-100")));
    }

    /**
     * The case a REST-shaped client forgets: HTTP 200 with a populated {@code errors} array. A
     * status-code check alone would treat this as success and surface nulls downstream.
     */
    @Test
    void treatsAGraphQlErrorsArrayAsAFailureDespiteHttp200() {
        wireMock.stubFor(post(urlEqualTo("/graphql")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody("""
                        {"errors":[{"message":"supplier service degraded"}]}
                        """)));
        SupplierClient supplier = new SupplierClient(wireMock.baseUrl(), Duration.ofMillis(500));

        assertUnavailable(() -> supplier.findAllByProductId(Set.of("p-100")));
    }

    private static void assertUnavailable(org.junit.jupiter.api.function.Executable call) {
        InventoryException exception = assertThrows(InventoryException.class, call);
        assertEquals("INVENTORY_UNAVAILABLE", exception.code());
    }
}
