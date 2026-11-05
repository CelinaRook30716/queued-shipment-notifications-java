package example.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ShipmentFlowTest {
    @Test
    void enqueueAndReconnectUseTheSameDispatcherQueue() {
        InfraiClient client = mock(InfraiClient.class);
        when(client.post(eq("/v1/queue/consume"), anyMap(), isNull()))
            .thenReturn(new ObjectMapper().createObjectNode());
        ShipmentFlow flow = new ShipmentFlow(client);
        ShipmentFlow.ShipmentEvent event = new ShipmentFlow.ShipmentEvent(
            "e1", "s1", "u1", "delivered", "pod-42", null);

        flow.enqueue(event);
        assertEquals(0, flow.deliverOnReconnect("u1"));

        verify(client).post("/v1/queue/publish",
            Map.of("queue", "logistics-u1", "payload", event), "e1");
        verify(client).post("/v1/queue/consume",
            Map.of("queue", "logistics-u1", "max_messages", 10, "visibility_timeout", 30), null);
    }

    @Test
    void deliveryProofAndExceptionChooseDistinctNotificationTypes() {
        assertEquals("shipment.delivered", ShipmentFlow.eventType(
            new ShipmentFlow.ShipmentEvent("e1", "s1", "u1", "delivered", "pod-42", null)));
        assertEquals("shipment.exception", ShipmentFlow.eventType(
            new ShipmentFlow.ShipmentEvent("e2", "s1", "u1", "held", "pod-42", "address mismatch")));
        assertEquals("shipment.updated", ShipmentFlow.eventType(
            new ShipmentFlow.ShipmentEvent("e3", "s1", "u1", "in transit", null, null)));
    }
}
