package example.logistics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ShipmentFlow {
    private final InfraiClient client;

    public ShipmentFlow(InfraiClient client) { this.client = client; }

    public record ShipmentEvent(String eventId, String shipmentId, String userId,
                                String status, String proofOfDeliveryId, String exceptionReason) {}

    public static String eventType(ShipmentEvent event) {
        if (event.exceptionReason() != null && !event.exceptionReason().isBlank()) return "shipment.exception";
        if (event.proofOfDeliveryId() != null && !event.proofOfDeliveryId().isBlank()) return "shipment.delivered";
        return "shipment.updated";
    }

    public JsonNode enqueue(ShipmentEvent event) {
        if (event.eventId() == null || event.eventId().isBlank() || event.shipmentId() == null
                || event.shipmentId().isBlank() || event.userId() == null || event.userId().isBlank())
            throw new IllegalArgumentException("eventId, shipmentId and userId are required");
        return client.post("/v1/queue/publish",
            Map.of("queue", "logistics-" + event.userId(), "payload", event), event.eventId());
    }

    public int deliverOnReconnect(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        JsonNode data = client.post("/v1/queue/consume",
            Map.of("queue", "logistics-" + userId, "max_messages", 10, "visibility_timeout", 30), null);
        int delivered = 0;
        for (JsonNode message : data.path("messages")) {
            JsonNode payload = message.path("payload");
            if (!userId.equals(payload.path("userId").asText())) continue;
            ShipmentEvent event = new ShipmentEvent(payload.path("eventId").asText(),
                payload.path("shipmentId").asText(), userId, payload.path("status").asText(),
                payload.path("proofOfDeliveryId").asText(null), payload.path("exceptionReason").asText(null));
            client.post("/v1/realtime/publish", Map.of("channel", "logistics-" + userId,
                "account_id", userId, "event", eventType(event), "data", payload),
                message.path("message_id").asText(event.eventId()));
            delivered++;
        }
        return delivered;
    }

    public JsonNode connectionToken(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is required");
        return client.post("/v1/realtime/token/issue", Map.of("client_id", userId,
            "channels", new String[] {"logistics-" + userId}, "capabilities", new String[] {"subscribe"},
            "ttl_seconds", 3600), "token-" + userId);
    }
}
