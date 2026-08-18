package rdpk.inventory;

import rdpk.model.InventoryException;
import rdpk.model.Stock;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class WarehouseClient {

    private final WebClient client;

    @Autowired
    public WarehouseClient(@Value("${app.inventory.warehouse-base-url}") String baseUrl,
            @Value("${app.inventory.timeout}") Duration timeout) {
        this(WebClient.builder().baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(timeout))).build());
    }

    WarehouseClient(WebClient client) {
        this.client = client;
    }

    public Mono<Map<String, Stock>> findAllByProductId(Set<String> productIds) {
        List<String> ids = List.copyOf(productIds);
        return client.get()
                .uri(builder -> builder.path("/warehouse").queryParam("ids", String.join(",", ids)).build())
                .retrieve()
                .bodyToFlux(Stock.class)
                .collectList()
                .map(rows -> index(ids, rows))
                .onErrorMap(error -> unavailable("Warehouse request failed", error));
    }

    private Map<String, Stock> index(List<String> ids, List<Stock> rows) {
        Map<String, Stock> result = new LinkedHashMap<>();
        rows.forEach(stock -> result.put(stock.productId(), stock));
        if (!result.keySet().containsAll(ids)) {
            throw new InventoryException("INVENTORY_UNAVAILABLE", "Warehouse response omitted a requested product");
        }
        return Map.copyOf(result);
    }

    private Throwable unavailable(String message, Throwable error) {
        return error instanceof InventoryException ? error
                : new InventoryException("INVENTORY_UNAVAILABLE", message, error);
    }
}
