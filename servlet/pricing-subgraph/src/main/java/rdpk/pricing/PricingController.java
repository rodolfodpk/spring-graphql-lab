package rdpk.pricing;

import rdpk.model.CatalogCategory;
import rdpk.model.Money;
import rdpk.model.PriceChange;
import rdpk.model.PricingException;
import rdpk.model.Quote;
import rdpk.model.QuoteInput;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.federation.EntityMapping;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

@Controller
public final class PricingController {

    /** Name of the DataLoader registered in {@link FederationConfiguration}. */
    static final String PRICE_LOADER = "pricesById";

    /**
     * The price stream is deliberately finite and fully determined: sequence 1 carries the
     * unchanged seeded price, and each later emission adds one delta. Tests assert these values.
     */
    static final Duration PRICE_CHANGE_INTERVAL = Duration.ofMillis(200);

    static final int PRICE_CHANGE_COUNT = 5;

    static final BigDecimal PRICE_CHANGE_DELTA = new BigDecimal("0.10");

    private final PriceRepository repository;

    public PricingController(PriceRepository repository) {
        this.repository = repository;
    }

    @QueryMapping
    public String pricingHealth() {
        return "ok";
    }

    /**
     * Federation requires the resolved references to line up positionally with the
     * representations they were requested for, which an ordered stream preserves.
     */
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
        return items.stream().collect(Collectors.toMap(
                item -> item,
                item -> requiredPrice(item.id(), prices),
                (left, right) -> left,
                LinkedHashMap::new));
    }

    @SchemaMapping(typeName = "CatalogItem", field = "quote")
    public CompletableFuture<Quote> quote(CatalogItemRef item, @Argument QuoteInput input,
            DataFetchingEnvironment environment) {
        // Validate before loading so the exception stays synchronous. Thrown inside the callback
        // below it would surface wrapped in a CompletionException, which the PricingException
        // handler would not match.
        if (input.quantity() < 1) {
            throw new PricingException("VALIDATION_ERROR", "Quantity must be at least 1");
        }
        DataLoader<String, BigDecimal> loader = environment.getDataLoader(PRICE_LOADER);
        return loader.load(item.id()).thenApply(price -> {
            if (price == null) {
                throw new PricingException("PRICE_NOT_FOUND", "Price was not found");
            }
            return Money.quote(price, input.quantity());
        });
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

    /**
     * Returns a {@code Flux} even though this is the servlet stack. graphql-java's subscription
     * contract is {@code Publisher}-based on every transport, so this is a framework contract
     * rather than reactive code leaking across: the repository call below is an ordinary blocking
     * lookup, deferred so the PRICE_NOT_FOUND error is raised on subscribe rather than at
     * assembly.
     */
    @SubscriptionMapping
    public Flux<PriceChange> priceChanges(@Argument String productId) {
        return Flux.defer(() -> {
            BigDecimal initialPrice = repository.findByProductId(productId)
                    .orElseThrow(() -> new PricingException("PRICE_NOT_FOUND", "Price was not found"));
            return Flux.interval(PRICE_CHANGE_INTERVAL)
                    .take(PRICE_CHANGE_COUNT)
                    .map(index -> priceChange(productId, initialPrice, index));
        });
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(PricingException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment)
                .message(exception.getMessage())
                .extensions(Map.of("code", exception.code()))
                .build();
    }

    /**
     * Parses the category before checking the product, matching the reactive stack. The order is
     * observable: an invalid category on an unknown id must report VALIDATION_ERROR, not
     * PRICE_NOT_FOUND.
     */
    private CatalogItemRef reference(String id, @Nullable Object category) {
        CatalogCategory parsed = parseCategory(category);
        if (!repository.containsProductId(id)) {
            throw new PricingException("PRICE_NOT_FOUND", "Price was not found");
        }
        return new CatalogItemRef(id, parsed);
    }

    private PriceChange priceChange(String productId, BigDecimal initialPrice, long index) {
        BigDecimal price = initialPrice.add(PRICE_CHANGE_DELTA.multiply(BigDecimal.valueOf(index)));
        return new PriceChange(productId, Money.normalize(price), Math.toIntExact(index + 1));
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
