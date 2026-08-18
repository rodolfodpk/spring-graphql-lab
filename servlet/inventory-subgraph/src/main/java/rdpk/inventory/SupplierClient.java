package rdpk.inventory;

import rdpk.model.InventoryException;
import rdpk.model.Supplier;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SupplierClient {

    private static final String SUPPLIERS_QUERY = """
            query Suppliers($ids: [ID!]!) {
              suppliers(ids: $ids) { productId name rating }
            }
            """;

    private final HttpSyncGraphQlClient client;

    @Autowired
    public SupplierClient(@Value("${app.inventory.supplier-base-url}") String baseUrl,
            @Value("${app.inventory.timeout}") Duration timeout) {
        this(HttpSyncGraphQlClient.create(RestClient.builder().baseUrl(endpoint(baseUrl)).requestFactory(requestFactory(timeout)).build()));
    }

    SupplierClient(HttpSyncGraphQlClient client) {
        this.client = client;
    }

    public Map<String, Supplier> findAllByProductId(Set<String> productIds) {
        List<String> ids = List.copyOf(productIds);
        try {
            var response = client.document(SUPPLIERS_QUERY).variable("ids", ids).executeSync();
            if (!response.isValid()) {
                throw new InventoryException("INVENTORY_UNAVAILABLE", "Supplier GraphQL response contains errors");
            }
            List<SupplierRow> rows = response.field("suppliers").toEntityList(SupplierRow.class);
            Map<String, Supplier> result = new LinkedHashMap<>();
            rows.forEach(row -> result.put(row.productId(), new Supplier(row.name(), row.rating())));
            if (!result.keySet().containsAll(ids)) {
                throw new InventoryException("INVENTORY_UNAVAILABLE", "Supplier response omitted a requested product");
            }
            return Map.copyOf(result);
        }
        catch (InventoryException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new InventoryException("INVENTORY_UNAVAILABLE", "Supplier request failed", exception);
        }
    }

    private static JdkClientHttpRequestFactory requestFactory(Duration timeout) {
        // Pin HTTP/1.1. The JDK client negotiates HTTP/2 by default, and a stream cancelled by a
        // read timeout poisons the pooled connection -- the next request then fails with
        // RST_STREAM rather than the timeout the caller is trying to handle.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(timeout);
        return factory;
    }

    record SupplierRow(String productId, String name, double rating) {
    }

    private static String endpoint(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl + "graphql" : baseUrl + "/graphql";
    }
}
