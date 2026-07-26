package rdpk.pricing;

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
