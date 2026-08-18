package rdpk.inventory;

import rdpk.model.InventoryException;
import rdpk.model.Stock;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WarehouseClient {

    private final RestClient client;

    @Autowired
    public WarehouseClient(@Value("${app.inventory.warehouse-base-url}") String baseUrl,
            @Value("${app.inventory.timeout}") Duration timeout) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory(timeout)).build());
    }

    WarehouseClient(RestClient client) {
        this.client = client;
    }

    public Map<String, Stock> findAllByProductId(Set<String> productIds) {
        List<String> ids = List.copyOf(productIds);
        try {
            Stock[] rows = client.get()
                    .uri(builder -> builder.path("/warehouse").queryParam("ids", String.join(",", ids)).build())
                    .retrieve()
                    .body(Stock[].class);
            if (rows == null) {
                throw new InventoryException("INVENTORY_UNAVAILABLE", "Warehouse response was empty");
            }
            Map<String, Stock> result = new LinkedHashMap<>();
            for (Stock stock : rows) {
                result.put(stock.productId(), stock);
            }
            if (!result.keySet().containsAll(ids)) {
                throw new InventoryException("INVENTORY_UNAVAILABLE", "Warehouse response omitted a requested product");
            }
            return Map.copyOf(result);
        }
        catch (InventoryException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new InventoryException("INVENTORY_UNAVAILABLE", "Warehouse request failed", exception);
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
}
