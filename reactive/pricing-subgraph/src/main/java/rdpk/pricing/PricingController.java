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
import reactor.core.publisher.Mono;

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
    public Mono<String> pricingHealth() {
        return Mono.just("ok");
    }

    /**
     * {@code concatMap} rather than {@code flatMap}: federation requires the resolved references
     * to line up positionally with the representations they were requested for.
     */
    @EntityMapping(name = "CatalogItem")
    public Flux<CatalogItemRef> catalogItem(List<Map<String, Object>> representations) {
        return Flux.fromIterable(representations)
                .concatMap(representation -> reference(
                        (String) representation.get("id"),
                        representation.get("category")));
    }

    @BatchMapping(typeName = "CatalogItem", field = "price")
    public Mono<Map<CatalogItemRef, BigDecimal>> price(List<CatalogItemRef> items) {
        Set<String> ids = new LinkedHashSet<>();
        items.forEach(item -> ids.add(item.id()));
        return repository.findAllByProductId(ids)
                .map(prices -> items.stream().collect(Collectors.toMap(
                        item -> item,
                        item -> requiredPrice(item.id(), prices),
                        (left, right) -> left,
                        LinkedHashMap::new)));
    }

    @SchemaMapping(typeName = "CatalogItem", field = "quote")
    public Mono<Quote> quote(CatalogItemRef item, @Argument QuoteInput input,
            DataFetchingEnvironment environment) {
        // Validate up front rather than inside the chain below. Reactor propagates an exception
        // thrown in an operator unwrapped, so the PricingException handler would match either
        // way -- this simply keeps the check where it reads.
        if (input.quantity() < 1) {
            throw new PricingException("VALIDATION_ERROR", "Quantity must be at least 1");
        }
        DataLoader<String, BigDecimal> loader = environment.getDataLoader(PRICE_LOADER);
        // Load eagerly. Deferring it into Mono.fromFuture(Supplier) would move the call out of
        // the DataLoader's dispatch window and silently unbatch the field.
        CompletableFuture<BigDecimal> future = loader.load(item.id());
        return Mono.fromFuture(future)
                .map(price -> Money.quote(price, input.quantity()))
                .switchIfEmpty(Mono.error(
                        () -> new PricingException("PRICE_NOT_FOUND", "Price was not found")));
    }

    @SchemaMapping(typeName = "CatalogItem", field = "priceLabel")
    public Mono<String> priceLabel(CatalogItemRef item) {
        if (item.category() == null) {
            throw new PricingException("VALIDATION_ERROR", "Required category was not supplied");
        }
        return Mono.just(switch (item.category()) {
            case PHYSICAL -> "Physical price";
            case DIGITAL -> "Digital price";
        });
    }

    @SubscriptionMapping
    public Flux<PriceChange> priceChanges(@Argument String productId) {
        return repository.findByProductId(productId)
                .switchIfEmpty(Mono.error(
                        () -> new PricingException("PRICE_NOT_FOUND", "Price was not found")))
                .flatMapMany(initialPrice -> Flux.interval(PRICE_CHANGE_INTERVAL)
                        .take(PRICE_CHANGE_COUNT)
                        .map(index -> priceChange(productId, initialPrice, index)));
    }

    @GraphQlExceptionHandler
    public GraphQLError handle(PricingException exception, DataFetchingEnvironment environment) {
        return GraphqlErrorBuilder.newError(environment)
                .message(exception.getMessage())
                .extensions(Map.of("code", exception.code()))
                .build();
    }

    private Mono<CatalogItemRef> reference(String id, @Nullable Object category) {
        CatalogCategory parsed = parseCategory(category);
        return repository.containsProductId(id)
                .filter(Boolean::booleanValue)
                .map(present -> new CatalogItemRef(id, parsed))
                .switchIfEmpty(Mono.error(
                        () -> new PricingException("PRICE_NOT_FOUND", "Price was not found")));
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
