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
 * The servlet counterpart of the reactive WebSocket test, and the sharpest evidence that
 * subscriptions are not something WebFlux unlocks: a real graphql-ws handshake against a real
 * Tomcat server, delivering byte-identical values.
 *
 * <p>The client is still a {@link ReactorNettyWebSocketClient}, because {@code
 * WebSocketGraphQlTester} accepts only the reactive {@code WebSocketClient} interface — there is no
 * servlet-side variant. That is a property of the test harness, not of what is being tested: the
 * server under test is Tomcat, which {@link #runsOnAServletWebServer()} pins directly. Boot still
 * selects the servlet stack because spring-webmvc is on the classpath, so the test-scoped webflux
 * client cannot quietly flip the application context.
 *
 * <p>The transport exists here only because {@code spring-boot-starter-websocket} is a dependency;
 * the webmvc starter alone does not satisfy the {@code ServerContainer} condition that activates
 * GraphQL over WebSocket.
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
    void runsOnAServletWebServer() {
        assertInstanceOf(
                org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext.class,
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
