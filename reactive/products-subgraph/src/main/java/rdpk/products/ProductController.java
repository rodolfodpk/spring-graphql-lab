package rdpk.products;

import rdpk.model.CatalogItem;
import rdpk.model.DigitalProduct;
import rdpk.model.Product;
import rdpk.model.ProductNotFoundException;

import java.util.List;
import java.util.Map;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.federation.EntityMapping;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Controller
public final class ProductController {

    private final CatalogRepository repository;

    public ProductController(CatalogRepository repository) {
        this.repository = repository;
    }

    @QueryMapping
    public Mono<Product> product(@Argument String id) {
        return resolveRequired(id, Product.class);
    }

    @QueryMapping
    public Flux<Product> products() {
        return repository.findProducts();
    }

    @QueryMapping
    public Flux<CatalogItem> catalog() {
        return repository.findAll();
    }

    @EntityMapping(name = "CatalogItem")
    public Flux<CatalogItem> catalogItem(List<Map<String, Object>> representations) {
        return resolveAll(representations, CatalogItem.class);
    }

    @EntityMapping(name = "Product")
    public Flux<Product> productEntities(List<Map<String, Object>> representations) {
        return resolveAll(representations, Product.class);
    }

    @EntityMapping(name = "DigitalProduct")
    public Flux<DigitalProduct> digitalProductEntities(List<Map<String, Object>> representations) {
        return resolveAll(representations, DigitalProduct.class);
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(ProductNotFoundException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment)
                .message("Catalog item was not found")
                .extensions(Map.of("code", "PRODUCT_NOT_FOUND"))
                .build();
    }

    /**
     * {@code concatMap} rather than {@code flatMap}: federation requires the resolved entities to
     * line up positionally with the representations they were requested for.
     */
    private <T extends CatalogItem> Flux<T> resolveAll(
            List<Map<String, Object>> representations, Class<T> type) {
        return Flux.fromIterable(representations)
                .concatMap(representation -> resolveRequired((String) representation.get("id"), type));
    }

    /**
     * Narrows by filtering, never by casting. A bare cast would surface an id that resolves to the
     * wrong subtype as a ClassCastException instead of the documented PRODUCT_NOT_FOUND code.
     */
    private <T extends CatalogItem> Mono<T> resolveRequired(String id, Class<T> type) {
        return repository.findById(id)
                .filter(type::isInstance)
                .map(type::cast)
                .switchIfEmpty(Mono.error(() -> new ProductNotFoundException(id)));
    }
}
