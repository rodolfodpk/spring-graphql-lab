package rdpk.pricing;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.GraphQlTester;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * The price stream is finite and fully determined, so it can be asserted value by value. Virtual
 * time is deliberately not used: the {@code Flux.interval} is created inside the controller, so a
 * test-supplied scheduler would never be substituted in.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
class PricingSubscriptionIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    GraphQlTester graphQlTester;

    @Test
    void streamsFiveDeterministicSnapshotsThenCompletes() {
        StepVerifier.create(priceChanges("p-100"))
                .expectNext(new Snapshot("p-100", "99.90", 1))
                .expectNext(new Snapshot("p-100", "100.00", 2))
                .expectNext(new Snapshot("p-100", "100.10", 3))
                .expectNext(new Snapshot("p-100", "100.20", 4))
                .expectNext(new Snapshot("p-100", "100.30", 5))
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void startsFromEachProductsOwnSeededPrice() {
        StepVerifier.create(priceChanges("d-400").take(2))
                .expectNext(new Snapshot("d-400", "24.00", 1))
                .expectNext(new Snapshot("d-400", "24.10", 2))
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void rejectsAnUnknownProductBeforeStreaming() {
        StepVerifier.create(priceChanges("nope"))
                .expectError()
                .verify(TIMEOUT);
    }

    private Flux<Snapshot> priceChanges(String productId) {
        return graphQlTester.document("""
                subscription($productId: ID!) {
                  priceChanges(productId: $productId) {
                    productId
                    price
                    sequence
                  }
                }
                """)
                .variable("productId", productId)
                .executeSubscription()
                .toFlux("priceChanges", Snapshot.class);
    }

    /** Mirrors the wire shape: the custom Decimal scalar serializes as a string. */
    record Snapshot(String productId, String price, int sequence) {
    }
}
