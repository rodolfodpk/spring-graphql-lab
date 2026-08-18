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

@Controller
public final class ProductController {

    private final CatalogRepository repository;

    public ProductController(CatalogRepository repository) {
        this.repository = repository;
    }

    @QueryMapping
    public Product product(@Argument String id) {
        return resolveRequired(id, Product.class);
    }

    @QueryMapping
    public List<Product> products() {
        return repository.findProducts();
    }

    @QueryMapping
    public List<CatalogItem> catalog() {
        return repository.findAll();
    }

    @EntityMapping(name = "CatalogItem")
    public List<CatalogItem> catalogItem(List<Map<String, Object>> representations) {
        return resolveAll(representations, CatalogItem.class);
    }

    @EntityMapping(name = "Product")
    public List<Product> productEntities(List<Map<String, Object>> representations) {
        return resolveAll(representations, Product.class);
    }

    @EntityMapping(name = "DigitalProduct")
    public List<DigitalProduct> digitalProductEntities(List<Map<String, Object>> representations) {
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
     * Federation requires the resolved entities to line up positionally with the representations
     * they were requested for, which an ordered stream preserves.
     */
    private <T extends CatalogItem> List<T> resolveAll(
            List<Map<String, Object>> representations, Class<T> type) {
        return representations.stream()
                .map(representation -> resolveRequired((String) representation.get("id"), type))
                .toList();
    }

    /**
     * Narrows by filtering, never by casting. A bare cast would surface an id that resolves to the
     * wrong subtype as a ClassCastException instead of the documented PRODUCT_NOT_FOUND code.
     */
    private <T extends CatalogItem> T resolveRequired(String id, Class<T> type) {
        return repository.findById(id)
                .filter(type::isInstance)
                .map(type::cast)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }
}
