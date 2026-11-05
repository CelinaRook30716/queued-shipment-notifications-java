package example.logistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InfraiClient {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String key;

    public InfraiClient(ObjectMapper json, @Value("${infrai.base-url}") String baseUrl,
                        @Value("${infrai.api-key}") String key) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.key = key;
        if (key.isBlank()) throw new IllegalArgumentException("Set INFRAI_API_KEY");
    }

    public JsonNode post(String path, Map<String, ?> body, String idempotencyKey) {
        try {
            String payload = json.writeValueAsString(body);
            for (int attempt = 0; attempt < 4; attempt++) {
                final long exponentialDelay = 1000L << attempt;
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
                if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode envelope = json.readTree(response.body());
                if (response.statusCode() == 429 && attempt < 3) {
                    long delay = response.headers().firstValue("Retry-After")
                        .map(value -> { try { return Long.parseLong(value) * 1000; }
                            catch (NumberFormatException ignored) { return exponentialDelay; } })
                        .orElse(exponentialDelay);
                    Thread.sleep(Math.min(delay, 30000));
                    continue;
                }
                if (!envelope.path("ok").asBoolean(false))
                    throw new ApiError(response.statusCode(), envelope.path("error"));
                if (response.statusCode() >= 500) throw new IllegalStateException("Upstream response: " + response.statusCode());
                return envelope.path("data");
            }
            throw new IllegalStateException("Retry limit reached");
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Notification request interrupted", e);
        }
    }

    public static class ApiError extends RuntimeException {
        public final int status;
        public final JsonNode detail;
        ApiError(int status, JsonNode detail) {
            super(detail.path("code").asText("Request rejected"));
            this.status = status;
            this.detail = detail;
        }
    }
}
