package example.logistics;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shipments")
public class ShipmentController {
    private final ShipmentFlow flow;
    public ShipmentController(ShipmentFlow flow) { this.flow = flow; }

    @PostMapping("/events")
    public Map<String, Object> event(@RequestBody ShipmentFlow.ShipmentEvent event) {
        flow.enqueue(event);
        return Map.of("queued", true, "eventId", event.eventId());
    }

    @PostMapping("/users/{userId}/connect")
    public JsonNode connect(@PathVariable String userId) { return flow.connectionToken(userId); }

    @PostMapping("/users/{userId}/deliver")
    public Map<String, Integer> deliver(@PathVariable String userId) {
        return Map.of("delivered", flow.deliverOnReconnect(userId));
    }

    @ExceptionHandler(InfraiClient.ApiError.class)
    public ResponseEntity<JsonNode> upstream(InfraiClient.ApiError error) {
        return ResponseEntity.status(error.status >= 400 && error.status < 500 ? error.status : 502).body(error.detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error.getMessage()));
    }
}
