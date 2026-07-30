package rdpk.products;

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
        CatalogItem item = resolveRequired(id);
        if (item instanceof Product product) {
            return product;
        }
        throw new ProductNotFoundException(id);
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
        return representations.stream()
                .map(representation -> resolveRequired((String) representation.get("id")))
                .toList();
    }

    @EntityMapping(name = "Product")
    public List<Product> productEntities(List<Map<String, Object>> representations) {
        return representations.stream()
                .map(representation -> resolveRequired((String) representation.get("id")))
                .map(Product.class::cast)
                .toList();
    }

    @EntityMapping(name = "DigitalProduct")
    public List<DigitalProduct> digitalProductEntities(List<Map<String, Object>> representations) {
        return representations.stream()
                .map(representation -> resolveRequired((String) representation.get("id")))
                .map(DigitalProduct.class::cast)
                .toList();
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(ProductNotFoundException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment)
                .message("Catalog item was not found")
                .extensions(Map.of("code", "PRODUCT_NOT_FOUND"))
                .build();
    }

    private CatalogItem resolveRequired(String id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }
}
