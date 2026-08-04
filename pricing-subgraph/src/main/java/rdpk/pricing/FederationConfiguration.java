package rdpk.pricing;

import java.math.BigDecimal;

import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.data.federation.FederationSchemaFactory;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class FederationConfiguration {

    /**
     * Registers the price loader used by {@code CatalogItem.quote}. The field takes an
     * argument, which {@code @BatchMapping} does not support, so it batches through an
     * explicitly named DataLoader instead.
     */
    public FederationConfiguration(BatchLoaderRegistry registry, PriceRepository repository) {
        registry.forTypePair(String.class, BigDecimal.class)
                .withName(PricingController.PRICE_LOADER)
                .registerMappedBatchLoader((ids, environment) -> repository.findAllByProductId(ids));
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

    @Bean
    RuntimeWiringConfigurer decimalConfigurer() {
        return wiring -> wiring.scalar(DecimalScalar.INSTANCE);
    }
}
