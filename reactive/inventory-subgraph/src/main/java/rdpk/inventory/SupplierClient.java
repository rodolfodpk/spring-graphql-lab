package rdpk.inventory;

import rdpk.model.InventoryException;
import rdpk.model.Supplier;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class SupplierClient {

    private static final String SUPPLIERS_QUERY = """
            query Suppliers($ids: [ID!]!) {
              suppliers(ids: $ids) { productId name rating }
            }
            """;

    private final HttpGraphQlClient client;

    @Autowired
    public SupplierClient(@Value("${app.inventory.supplier-base-url}") String baseUrl,
            @Value("${app.inventory.timeout}") Duration timeout) {
        this(HttpGraphQlClient.create(WebClient.builder().baseUrl(endpoint(baseUrl)).clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(timeout))).build()));
    }

    SupplierClient(HttpGraphQlClient client) {
        this.client = client;
    }

    public Mono<Map<String, Supplier>> findAllByProductId(Set<String> productIds) {
        List<String> ids = List.copyOf(productIds);
        return client.document(SUPPLIERS_QUERY).variable("ids", ids).execute()
                .flatMap(response -> {
                    if (!response.isValid()) {
                        return Mono.error(new InventoryException(
                                "INVENTORY_UNAVAILABLE", "Supplier GraphQL response contains errors"));
                    }
                    List<SupplierRow> rows = response.field("suppliers").toEntityList(SupplierRow.class);
                    Map<String, Supplier> result = new LinkedHashMap<>();
                    rows.forEach(row -> result.put(row.productId(), new Supplier(row.name(), row.rating())));
                    if (!result.keySet().containsAll(ids)) {
                        return Mono.error(new InventoryException(
                                "INVENTORY_UNAVAILABLE", "Supplier response omitted a requested product"));
                    }
                    return Mono.just(Map.copyOf(result));
                })
                .onErrorMap(error -> error instanceof InventoryException ? error
                        : new InventoryException("INVENTORY_UNAVAILABLE", "Supplier request failed", error));
    }

    record SupplierRow(String productId, String name, double rating) {
    }

    private static String endpoint(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl + "graphql" : baseUrl + "/graphql";
    }
}
