package rdpk.products;

import graphql.schema.idl.RuntimeWiring;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.data.federation.FederationSchemaFactory;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class FederationConfiguration {

    @Bean
    FederationSchemaFactory federationSchemaFactory() {
        FederationSchemaFactory factory = new FederationSchemaFactory();
        factory.setTypeResolver(environment -> {
            Object value = environment.getObject();
            String typeName = switch (value) {
                case Product ignored -> "Product";
                case DigitalProduct ignored -> "DigitalProduct";
                default -> throw new IllegalArgumentException("Unsupported federated entity: " + value);
            };
            return environment.getSchema().getObjectType(typeName);
        });
        return factory;
    }

    @Bean
    GraphQlSourceBuilderCustomizer federationCustomizer(FederationSchemaFactory factory) {
        return builder -> builder.schemaFactory(factory::createGraphQLSchema);
    }

    @Bean
    RuntimeWiringConfigurer catalogItemTypeResolver() {
        return wiring -> wiring.type("CatalogItem", type -> type.typeResolver(environment -> {
            Object value = environment.getObject();
            String typeName = switch (value) {
                case Product ignored -> "Product";
                case DigitalProduct ignored -> "DigitalProduct";
                default -> throw new IllegalArgumentException("Unsupported CatalogItem: " + value);
            };
            return environment.getSchema().getObjectType(typeName);
        }));
    }
}
