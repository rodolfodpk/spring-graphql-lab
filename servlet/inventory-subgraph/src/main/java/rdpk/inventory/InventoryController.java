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

@Controller
public final class InventoryController {

    static final String WAREHOUSE_LOADER = "warehouseById";
    static final String SUPPLIER_LOADER = "suppliersById";

    @QueryMapping
    public String inventoryHealth() {
        return "ok";
    }

    @EntityMapping(name = "CatalogItem")
    public List<CatalogItemRef> catalogItem(List<Map<String, Object>> representations) {
        return representations.stream().map(representation -> new CatalogItemRef(
                (String) representation.get("id"))).toList();
    }

    @SchemaMapping(typeName = "CatalogItem", field = "stockLevel")
    public CompletableFuture<Integer> stockLevel(CatalogItemRef item, DataFetchingEnvironment environment) {
        return warehouse(environment).load(item.id()).thenApply(Stock::stockLevel);
    }

    @SchemaMapping(typeName = "CatalogItem", field = "restockEta")
    public CompletableFuture<String> restockEta(CatalogItemRef item, DataFetchingEnvironment environment) {
        return warehouse(environment).load(item.id()).thenApply(Stock::restockEta);
    }

    @SchemaMapping(typeName = "CatalogItem", field = "supplier")
    public CompletableFuture<Supplier> supplier(CatalogItemRef item, DataFetchingEnvironment environment) {
        return suppliers(environment).load(item.id());
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
