package rdpk.inventory;

import rdpk.model.InventoryException;
import rdpk.model.Stock;
import rdpk.model.Supplier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.federation.EntityMapping;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller
public final class InventoryController {

    static final String WAREHOUSE_LOADER = "warehouseById";
    static final String SUPPLIER_LOADER = "suppliersById";

    @QueryMapping
    public Mono<String> inventoryHealth() {
        return Mono.just("ok");
    }

    @EntityMapping(name = "CatalogItem")
    public Flux<CatalogItemRef> catalogItem(List<Map<String, Object>> representations) {
        return Flux.fromIterable(representations)
                .concatMap(representation -> Mono.just(new CatalogItemRef((String) representation.get("id"))));
    }

    @SchemaMapping(typeName = "CatalogItem", field = "stockLevel")
    public Mono<Integer> stockLevel(CatalogItemRef item, DataFetchingEnvironment environment) {
        return Mono.fromFuture(warehouse(environment).load(item.id())).map(Stock::stockLevel);
    }

    @SchemaMapping(typeName = "CatalogItem", field = "restockEta")
    public Mono<String> restockEta(CatalogItemRef item, DataFetchingEnvironment environment) {
        // mapNotNull, not map: restockEta is a nullable GraphQL field and Reactor treats a
        // null from map as an error rather than an empty result.
        return Mono.fromFuture(warehouse(environment).load(item.id())).mapNotNull(Stock::restockEta);
    }

    @SchemaMapping(typeName = "CatalogItem", field = "supplier")
    public Mono<Supplier> supplier(CatalogItemRef item, DataFetchingEnvironment environment) {
        return Mono.fromFuture(suppliers(environment).load(item.id()));
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(InventoryException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment).message(exception.getMessage())
                .extensions(Map.of("code", exception.code())).build();
    }

    private DataLoader<String, Stock> warehouse(DataFetchingEnvironment environment) {
        return environment.getDataLoader(WAREHOUSE_LOADER);
    }

    private DataLoader<String, Supplier> suppliers(DataFetchingEnvironment environment) {
        return environment.getDataLoader(SUPPLIER_LOADER);
    }
}
