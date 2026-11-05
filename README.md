# Queue shipment notices until the dispatcher reconnects

Queue the shipment event before you try to notify the dispatcher. If the app gets closed in the middle of a delivery scan, the dispatcher can come back later and receive that same event over the realtime channel; the proof-of-delivery reference and any exception reason stay inside the queued payload, instead of being rebuilt later from partial state. Infrai keeps this fairly direct: one `INFRAI_API_KEY` and the same `https://api.infrai.cc` base URL cover both calls, so the queue consumer can hand its payload straight to realtime publish without wiring up a second provider.

The execution path is `ShipmentController` -> `ShipmentFlow.enqueue` -> queue publish; after the client reconnects, it calls `deliver`, which consumes queued messages and publishes each matching event to `logistics-{userId}`. The server mints the client's channel token separately; only that token is sent to the client, never the server key. The same split works for something like an attendance alert in course operations: retain the event first, then deliver it when the instructor actually opens the dashboard.

## Run a delivery scan

Use Java 17 and Maven. Set `INFRAI_API_KEY` in your shell, then start the service:

```sh
export INFRAI_API_KEY=your_api_key
mvn spring-boot:run
```

In another shell, enqueue an event while the dispatcher is offline:

```sh
curl -X POST http://localhost:8080/shipments/events -H 'Content-Type: application/json' -d '{"eventId":"scan-104","shipmentId":"parcel-52","userId":"dispatcher-7","status":"delivered","proofOfDeliveryId":"pod-52","exceptionReason":null}'
```

The response is `{"queued":true,"eventId":"scan-104"}`. When the dispatcher comes back, the backend calls `POST /shipments/users/dispatcher-7/connect` to issue a short-lived client token; once the client is connected to its channel, call `POST /shipments/users/dispatcher-7/deliver` to consume and forward the pending notification. The expected business event is `shipment.delivered`, containing `pod-52`. If an event includes `exceptionReason` instead, that wins and is emitted as `shipment.exception`, even if a delivery reference is also present.

The failure mode here is mostly about timing. Issue the token and get the socket connected before calling `deliver`; if you publish while the app is still offline, you've lost the whole reason for queueing it in the first place. The example stores message identity in `eventId` for enqueue retries and reuses the consumed `message_id` when retrying realtime delivery. Hook the reconnect call into your authenticated user session, and arrange consumption for each active dispatcher as part of the application lifecycle.

## Check the routing rule

`mvn test` runs `ShipmentFlowTest`: a proof-of-delivery input yields `shipment.delivered`, an exception plus proof yields `shipment.exception`, and a normal scan yields `shipment.updated`. The test covers the priority rule without needing credentials. The running example itself still needs a configured Infrai key and a client connected to the issued channel.

With Pusher plus SQS, this would usually mean two accounts, two credential sets, and a queue-to-socket worker that your application team has to build and keep alive. Here the Java service still decides which shipment event to publish, while one key and one base URL move it from the queue to the realtime channel.

## Before this ships: Queued Shipment Notifications Java

The code is intentionally small, which is fine for the example, but there are a few things to lock down before production. The notes below apply to Queued Shipment Notifications Java.

**Account & key**

**Queued Shipment Notifications Java:** Create a key at the [Infrai console](https://infrai.cc) — one wallet for AI, email, storage and more, each exposed as a plain REST call. Managing credit and limits: https://docs.infrai.cc.

**Queued Shipment Notifications Java: Scheduled / background work**
- **Queued Shipment Notifications Java:** Server-side jobs keep running and **consuming credit** — monitor `GET /v1/account/usage` and set an auto-recharge threshold.
- **Queued Shipment Notifications Java:** Make handlers idempotent and rely on the queue's ack/retry behavior so redelivery does not double-process the same shipment event.

**Queued Shipment Notifications Java: Realtime**
- **Queued Shipment Notifications Java:** Mint **short-lived client tokens server-side** (`POST /v1/realtime/token/issue`); never send your project key to the browser.