package rdpk.inventory;

import rdpk.model.Stock;
import rdpk.model.Supplier;

import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.data.federation.FederationSchemaFactory;
import org.springframework.graphql.execution.BatchLoaderRegistry;

@Configuration
public class FederationConfiguration {

    public FederationConfiguration(BatchLoaderRegistry registry, WarehouseClient warehouseClient,
            SupplierClient supplierClient) {
        registry.forTypePair(String.class, Stock.class).withName(InventoryController.WAREHOUSE_LOADER)
                .registerMappedBatchLoader((ids, environment) -> warehouseClient.findAllByProductId(ids));
        registry.forTypePair(String.class, Supplier.class).withName(InventoryController.SUPPLIER_LOADER)
                .registerMappedBatchLoader((ids, environment) -> supplierClient.findAllByProductId(ids));
    }

    @Bean
    FederationSchemaFactory federationSchemaFactory() {
        FederationSchemaFactory factory = new FederationSchemaFactory();
        factory.setTypeResolver(environment -> environment.getSchema().getObjectType("CatalogItem"));
        return factory;
    }

    @Bean
    GraphQlSourceBuilderCustomizer federationCustomizer(FederationSchemaFactory factory) {
        return builder -> builder.schemaFactory(factory::createGraphQLSchema);
    }
}
