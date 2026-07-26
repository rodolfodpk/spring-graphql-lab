package rdpk.pricing;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.federation.EntityMapping;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public final class PricingController {

    private final PriceRepository repository;

    public PricingController(PriceRepository repository) {
        this.repository = repository;
    }

    @QueryMapping
    public String pricingHealth() {
        return "ok";
    }

    @EntityMapping(name = "CatalogItem")
    public List<CatalogItemRef> catalogItem(List<Map<String, Object>> representations) {
        return representations.stream()
                .map(representation -> reference(
                        (String) representation.get("id"),
                        representation.get("category")))
                .toList();
    }

    @BatchMapping(typeName = "CatalogItem", field = "price")
    public Map<CatalogItemRef, BigDecimal> price(List<CatalogItemRef> items) {
        Set<String> ids = new LinkedHashSet<>();
        items.forEach(item -> ids.add(item.id()));
        Map<String, BigDecimal> prices = repository.findAllByProductId(ids);
        return items.stream().collect(java.util.stream.Collectors.toMap(
                item -> item,
                item -> requiredPrice(item.id(), prices),
                (left, right) -> left,
                java.util.LinkedHashMap::new));
    }

    @SchemaMapping(typeName = "CatalogItem", field = "quote")
    public Quote quote(CatalogItemRef item, @Argument QuoteInput input) {
        return Money.quote(requiredPrice(
                item.id(), repository.findAllByProductId(Set.of(item.id()))), input.quantity());
    }

    @SchemaMapping(typeName = "CatalogItem", field = "priceLabel")
    public String priceLabel(CatalogItemRef item) {
        if (item.category() == null) {
            throw new PricingException("VALIDATION_ERROR", "Required category was not supplied");
        }
        return switch (item.category()) {
            case PHYSICAL -> "Physical price";
            case DIGITAL -> "Digital price";
        };
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(PricingException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment)
                .message(exception.getMessage())
                .extensions(Map.of("code", exception.code()))
                .build();
    }

    private CatalogItemRef reference(String id, @Nullable Object category) {
        if (!repository.containsProductId(id)) {
            throw new PricingException("PRICE_NOT_FOUND", "Price was not found");
        }
        return new CatalogItemRef(id, parseCategory(category));
    }

    private @Nullable CatalogCategory parseCategory(@Nullable Object category) {
        if (category == null) {
            return null;
        }
        if (category instanceof CatalogCategory catalogCategory) {
            return catalogCategory;
        }
        if (category instanceof String value) {
            try {
                return CatalogCategory.valueOf(value);
            }
            catch (IllegalArgumentException exception) {
                throw new PricingException("VALIDATION_ERROR", "Unknown catalog category");
            }
        }
        throw new PricingException("VALIDATION_ERROR", "Invalid catalog category");
    }

    private BigDecimal requiredPrice(String id, Map<String, BigDecimal> prices) {
        BigDecimal price = prices.get(id);
        if (price == null) {
            throw new PricingException("PRICE_NOT_FOUND", "Price was not found");
        }
        return Money.normalize(price);
    }
}
