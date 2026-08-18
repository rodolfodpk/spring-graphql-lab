package rdpk.pricing;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.test.tester.WebSocketGraphQlTester;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.test.StepVerifier;

/**
 * The authoritative proof that the reactive transport works end to end: a real graphql-ws
 * handshake over a real Reactor Netty server, rather than an in-process call into
 * {@code ExecutionGraphQlService}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PricingSubscriptionWebSocketIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext applicationContext;

    private WebSocketGraphQlTester tester;

    @BeforeEach
    void setUp() {
        tester = WebSocketGraphQlTester.builder(
                "ws://localhost:" + port + "/graphql", new ReactorNettyWebSocketClient()).build();
    }

    /** The tester holds one shared connection, so it has to be released between tests. */
    @AfterEach
    void tearDown() {
        tester.stop().block(TIMEOUT);
    }

    @Test
    void runsOnAReactiveWebServer() {
        assertInstanceOf(
                org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext.class,
                applicationContext);
    }

    @Test
    void deliversThePriceStreamOverGraphQlWebSocket() {
        StepVerifier.create(tester.document("""
                subscription {
                  priceChanges(productId: "p-100") {
                    productId
                    price
                    sequence
                  }
                }
                """)
                .executeSubscription()
                .toFlux("priceChanges.price", String.class))
                .expectNext("99.90", "100.00", "100.10", "100.20", "100.30")
                .expectComplete()
                .verify(TIMEOUT);
    }
}
