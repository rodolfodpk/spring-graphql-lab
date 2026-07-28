package rdpk.local;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class FederationE2E {

    private final RestClient client = RestClient.create("http://127.0.0.1:4000/");

    @Test
    void resolvesPhysicalProductsAcrossSubgraphs() {
        String response = graphQl("{ products { id name price } }");
        assertAll(
                () -> assertTrue(response.contains("\"p-100\"")),
                () -> assertTrue(response.contains("\"99.90\"")),
                () -> assertTrue(response.contains("\"p-300\"")),
                () -> assertFalse(response.contains("\"d-400\"")));
    }

    @Test
    void resolvesPolymorphicCatalogAndRequiredExternalField() {
        String response = graphQl("""
                {
                  catalog {
                    id name __typename price priceLabel
                    ... on Product { weightGrams }
                    ... on DigitalProduct { downloadFormat }
                  }
                }
                """);
        assertAll(
                () -> assertTrue(response.contains("\"weightGrams\":950")),
                () -> assertTrue(response.contains("\"downloadFormat\":\"PDF\"")),
                () -> assertTrue(response.contains("\"Physical price\"")),
                () -> assertTrue(response.contains("\"Digital price\"")),
                () -> assertFalse(response.contains("\"category\"")));
    }

    @Test
    void calculatesQuote() {
        String response = graphQl("""
                { product(id: "p-100") {
                    quote(input: { quantity: 2 }) { unitPrice quantity subtotal }
                  }
                }
                """);
        assertAll(
                () -> assertTrue(response.contains("\"unitPrice\":\"99.90\"")),
                () -> assertTrue(response.contains("\"quantity\":2")),
                () -> assertTrue(response.contains("\"subtotal\":\"199.80\"")));
    }

    @Test
    void exposesStableValidationAndMissingProductCodes() {
        String invalid = graphQl("""
                { product(id: "p-100") { quote(input: { quantity: 0 }) { subtotal } } }
                """);
        String missing = graphQl("""
                { product(id: "missing") { id name } }
                """);
        assertAll(
                () -> assertTrue(invalid.contains("\"code\":\"VALIDATION_ERROR\"")),
                () -> assertTrue(missing.contains("\"code\":\"PRODUCT_NOT_FOUND\"")));
    }

    @Test
    void exposesOnlyHealthFromBothSubgraphs() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (int port : List.of(8081, 8082)) {
            assertEquals(200, status(http, port, "/actuator/health"));
            assertEquals(404, status(http, port, "/actuator/env"));
            assertEquals(404, status(http, port, "/actuator"));
        }
    }

    @Test
    void returnsGraphQlErrorsWhenPricingIsUnavailableAndRestoresIt() throws Exception {
        runScript("stop");
        try {
            String response = rawGraphQl("{ product(id: \"p-100\") { id price } }");
            assertAll(
                    () -> assertTrue(response.contains("\"errors\"")),
                    () -> assertFalse(response.contains("\"price\":\"99.90\"")));
        }
        finally {
            runScript("start");
            waitForPricingHealth();
        }
    }

    @Test
    void skipsPricingSubgraphWhenNoPricingFieldIsSelected() throws Exception {
        runScript("stop");
        try {
            String response = rawGraphQl("{ catalog { id name } }");
            assertAll(
                    () -> assertFalse(response.contains("\"errors\"")),
                    () -> assertTrue(response.contains("\"p-100\"")),
                    () -> assertTrue(response.contains("\"d-400\"")));
        }
        finally {
            runScript("start");
            waitForPricingHealth();
        }
    }

    @Test
    void deliversCommercialDataAsDeferredMultipartPatches() throws Exception {
        String query = """
                {
                  catalog {
                    id name __typename
                    ... @defer(label: "commercial-data") { price priceLabel }
                  }
                }
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:4000/"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "multipart/mixed;deferSpec=20220824, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"query\":" + quoteJson(query) + "}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        String contentType = response.headers().firstValue("Content-Type").orElseThrow();
        String boundary = contentType.replaceAll("(?i).*boundary=\"?([^\";]+)\"?.*", "$1");
        String body = response.body();
        String[] parts = body.split("--" + Pattern.quote(boundary));

        assertAll(
                () -> assertTrue(contentType.toLowerCase().startsWith("multipart/mixed")),
                () -> assertTrue(parts.length >= 3),
                () -> assertTrue(parts[1].contains("\"hasNext\":true")),
                () -> assertFalse(parts[1].contains("\"price\"")),
                () -> assertTrue(parts[2].contains("\"label\":\"commercial-data\"")),
                () -> assertTrue(parts[2].contains("\"path\":[\"catalog\",0]")),
                () -> assertTrue(parts[2].contains("\"Physical price\"")),
                () -> assertTrue(parts[2].contains("\"Digital price\"")),
                () -> assertTrue(parts[2].contains("\"hasNext\":false")));

        String ordinary = graphQl(query.replace(
                "... @defer(label: \"commercial-data\")", "..."));
        assertAll(
                () -> assertTrue(ordinary.contains("\"price\":\"99.90\"")),
                () -> assertTrue(ordinary.contains("\"price\":\"24.00\"")),
                () -> assertTrue(ordinary.contains("\"Digital price\"")));
    }

    private String graphQl(String query) {
        return client.post()
                .body(Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    private static String rawGraphQl(String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:4000/"))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"query\":" + quoteJson(query) + "}"))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static String quoteJson(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private static int status(HttpClient client, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void runScript(String action) throws Exception {
        Process process = new ProcessBuilder("./scripts/toggle-pricing.sh", action)
                .inheritIO()
                .start();
        assertEquals(0, process.waitFor());
    }

    private static void waitForPricingHealth() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                if (status(client, 8082, "/actuator/health") == 200) {
                    return;
                }
            }
            catch (Exception ignored) {
                // Service is still restarting.
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Pricing did not become healthy after restart");
    }
}
