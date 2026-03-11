# Spool — Publisher

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Maven](https://img.shields.io/badge/build-Maven-C71A36.svg)](https://maven.apache.org/)

**Publisher** is the **Spool** framework module responsible for **publishing raw data from the inbox to event channels**. It acts as the bridge between inbox storage and downstream consumer distribution, ensuring traceability, idempotency, and declarative error handling.

---

## Table of Contents

- [Architecture](#architecture)
- [Key Concepts](#key-concepts)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Examples](#examples)
  - [Minimal Example](#minimal-example)
  - [Publishing with InMemoryEventBus](#publishing-with-inmemoryeventbus)
  - [Channel Routing by Event Type](#channel-routing-by-event-type)
  - [Granular Error Handling](#granular-error-handling)
  - [Real-World: Kafka + JPA Integration](#real-world-kafka--jpa-integration)
  - [Real-World: Multi-Source Pipeline with Dead Letter Queue](#real-world-multi-source-pipeline-with-dead-letter-queue)
- [API Reference](#api-reference)
- [Event Model](#event-model)
- [License](#license)

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                        Publisher                         │
│                                                          │
│  RawDataWrittenToInbox ──► RawDataWrittenToInboxHandler   │
│                              │                           │
│                    ┌─────────┼──────────┐                │
│                    ▼         ▼          ▼                │
│             ChannelRouter  EventBus  InboxUpdater         │
│             (resolves      (emits    (marks entry         │
│              channel)       event)    as PUBLISHED)       │
│                    │                                     │
│                    ▼                                     │
│              ErrorRouter                                 │
│          (dispatches errors)                             │
└──────────────────────────────────────────────────────────┘
```

The flow is straightforward:

1. A `RawDataWrittenToInbox` event is received through the `EventBus`.
2. The `ChannelRouter` resolves which channel the data should be published to.
3. A `RawDataPublished` event is emitted with the payload to the resolved channel.
4. The `InboxUpdater` marks the inbox entry as `PUBLISHED`.
5. If anything fails, the `ErrorRouter` dispatches the exception to the appropriate handler.

---

## Key Concepts

| Concept | Description |
|---|---|
| **`Publisher`** | API entry point. Starts the fluent configuration via `Publisher.sender(name)`. |
| **`EventBus`** | Port (interface) to emit and subscribe to `SpoolEvent` instances. |
| **`InboxUpdater`** | Port (interface) to update the status of inbox entries. |
| **`ChannelRouter`** | Resolves the target channel based on event type. Default channel: `"spool"`. |
| **`ErrorRouter`** | Dispatches exceptions to typed handlers. Supports a generic fallback. |
| **`InMemoryEventBus`** | In-memory `EventBus` implementation, useful for testing and prototyping. |

---

## Installation

### Maven (GitHub Packages)

```xml
<dependency>
    <groupId>software.spool</groupId>
    <artifactId>feeder</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Configure the GitHub Packages repository in your `pom.xml` or `settings.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/Spool-FRAMEWORK/packages</url>
    </repository>
</repositories>
```

> Requires **Java 17+**.

---

## Quick Start

```java
Publisher.sender("my-service")
    .updater(myInboxUpdater)
    .on(myEventBus)
    .with(myChannelRouter)
    .with(myErrorRouter)
    .build();
```

That's it. After calling `.build()`, the Publisher automatically subscribes to the `EventBus` and begins processing `RawDataWrittenToInbox` events.

---

## Examples

### Minimal Example

The simplest case: a feeder using the in-memory bus, a default channel, and an updater that just prints to the console.

```java

import software.spool.feeder.api.ChannelRouter;
import software.spool.feeder.api.ErrorRouter;

var bus = new InMemoryEventBusEmitter();

Publisher.

sender("minimal-service")
    .

updater((id, status) ->System.out.

println(id +" → "+status))
        .

on(bus)
    .

with(new ChannelRouter())
        .

with(new ErrorRouter())
        .

build();

// Simulate data arriving in the inbox
var event = RawDataWrittenToInbox.from("source-a")
        .withPayload("{\"temp\": 22.5}")
        .withIdempotencyKey("key-001")
        .create();

bus.

emit(event, "spool");
// Output: InboxEntryId[id=key-001] → PUBLISHED
```

---

### Publishing with InMemoryEventBus

Register an additional subscriber to observe published events.

```java
var bus = new InMemoryEventBus();

// Subscribe to already-published events
bus.subscribe(RawDataPublished.class, published ->
    System.out.println("Published: " + published.payload())
);

Publisher.sender("sensor-gateway")
    .updater((id, status) -> System.out.println("Inbox " + id.id() + " = " + status))
    .on(bus)
    .with(new ChannelRouter())
    .with(new ErrorRouter().orElse(e -> e.printStackTrace()))
    .build();

var event = RawDataWrittenToInbox.from("sensor-gateway")
    .withPayload("{\"humidity\": 65, \"unit\": \"%\"}")
    .withIdempotencyKey("sensor-001-1709136000")
    .create();

bus.emit(event, "spool");
// Output:
//   Published: {"humidity": 65, "unit": "%"}
//   Inbox sensor-001-1709136000 = PUBLISHED
```

---

### Channel Routing by Event Type

Route different event types to different channels (topics, queues, etc.).

```java
var channelRouter = new ChannelRouter()
    .route(RawDataWrittenToInbox.class, "inbox-events")
    .route(RawDataPublished.class, "published-events");

var bus = new InMemoryEventBus();

Publisher.sender("order-service")
    .updater((id, status) -> { /* update database */ })
    .on(bus)
    .with(channelRouter)
    .with(new ErrorRouter())
    .build();

// The RawDataWrittenToInbox event will resolve to channel "inbox-events"
// The resulting RawDataPublished will be emitted on the bus internally
var event = RawDataWrittenToInbox.from("order-service")
    .withPayload("{\"orderId\": \"ORD-9921\", \"total\": 149.99}")
    .withIdempotencyKey("ORD-9921")
    .create();

bus.emit(event, "inbox-events");
```

---

### Granular Error Handling

Configure specific handlers per exception type and a generic fallback.

```java
import software.spool.model.exception.DuplicateEventException;
import software.spool.model.exception.SerializationException;

var errorRouter = new ErrorRouter()
    .on(DuplicateEventException.class, e ->
        System.err.println("Duplicate event ignored: " + e.idempotencyKey()))
    .on(SerializationException.class, e ->
        System.err.println("Serialization error: " + e.getMessage()))
    .orElse(e ->
        System.err.println("Unexpected error: " + e.getMessage()));

var bus = new InMemoryEventBus();

Publisher.sender("payment-processor")
    .updater((id, status) -> { /* ... */ })
    .on(bus)
    .with(new ChannelRouter())
    .with(errorRouter)
    .build();
```

The `ErrorRouter` tries each registered handler in order. If none match the exception type, the fallback defined with `.orElse(...)` is executed.

---

### Real-World: Kafka + JPA Integration

In a production environment, you would implement `EventBus` and `InboxUpdater` with real infrastructure.

```java
import software.spool.feeder.api.port.EventBusEmitter;
import software.spool.feeder.api.port.InboxUpdater;

// --- EventBus implementation with Kafka ---
public class KafkaEventBus implements EventBusEmitter {

  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper;
  private final Map<Class<? extends SpoolEvent>, List<Consumer<SpoolEvent>>> locals = new HashMap<>();

  public KafkaEventBus(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
    this.kafka = kafka;
    this.mapper = mapper;
  }

  @Override
  public void emit(SpoolEvent event, String channel) {
    try {
      String json = mapper.writeValueAsString(event);
      kafka.send(channel, event.eventId(), json);
    } catch (JsonProcessingException e) {
      throw new SerializationException("Failed to serialize event", e);
    }

    // Notify local subscribers
    locals.getOrDefault(event.getClass(), List.of())
            .forEach(c -> c.accept(event));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends SpoolEvent> void subscribe(Class<T> eventClass, Consumer<T> handler) {
    locals.computeIfAbsent(eventClass, k -> new ArrayList<>())
            .add(e -> handler.accept((T) e));
  }
}

// --- InboxUpdater implementation with JPA ---
public class JpaInboxUpdater implements InboxUpdater {

  private final InboxEntryRepository repository;

  public JpaInboxUpdater(InboxEntryRepository repository) {
    this.repository = repository;
  }

  @Override
  public void update(InboxEntryId id, InboxEventStatus status) {
    repository.findById(id.id()).ifPresent(entry -> {
      entry.setStatus(status.name());
      entry.setUpdatedAt(Instant.now());
      repository.save(entry);
    });
  }
}

// --- Publisher configuration ---
@Configuration
public class PublisherConfig {

  @Bean
  public void configurePublisher(KafkaTemplate<String, String> kafka,
                                 ObjectMapper mapper,
                                 InboxEntryRepository repository) {

    var bus = new KafkaEventBus(kafka, mapper);
    var updater = new JpaInboxUpdater(repository);

    var channels = new ChannelRouter()
            .route(RawDataWrittenToInbox.class, "raw-inbox")
            .route(RawDataPublished.class, "raw-published");

    var errors = new ErrorRouter()
            .on(DuplicateEventException.class, e ->
                    log.warn("Skipping duplicate: {}", e.idempotencyKey()))
            .on(SerializationException.class, e ->
                    log.error("Serialization failed", e))
            .orElse(e -> log.error("Unhandled feeder error", e));

    Publisher.sender("order-service")
            .updater(updater)
            .on(bus)
            .with(channels)
            .with(errors)
            .build();
  }
}
```

---

### Real-World: Multi-Source Pipeline with Dead Letter Queue

Configure multiple publishers for different data sources, with a shared dead letter queue for unrecoverable errors.

```java
public class PublisherPipeline {

    private final EventBus bus;
    private final InboxUpdater updater;
    private final Consumer<Exception> deadLetterSink;

    public PublisherPipeline(EventBus bus, InboxUpdater updater, Consumer<Exception> deadLetterSink) {
        this.bus = bus;
        this.updater = updater;
        this.deadLetterSink = deadLetterSink;
    }

    public void initialize() {
        // Publisher for IoT sensor data
        Publisher.sender("iot-sensors")
            .updater(updater)
            .on(bus)
            .with(new ChannelRouter()
                .route(RawDataWrittenToInbox.class, "iot.raw"))
            .with(new ErrorRouter()
                .on(SerializationException.class, e -> {
                    log.warn("Sensor data malformed, sending to DLQ: {}", e.getMessage());
                    deadLetterSink.accept(e);
                })
                .orElse(deadLetterSink))
            .build();

        // Publisher for external API data
        Publisher.sender("external-api")
            .updater(updater)
            .on(bus)
            .with(new ChannelRouter()
                .route(RawDataWrittenToInbox.class, "api.raw"))
            .with(new ErrorRouter()
                .on(DuplicateEventException.class, e ->
                    log.info("API duplicate ignored: {}", e.idempotencyKey()))
                .orElse(deadLetterSink))
            .build();

        // Publisher for batch CSV file data
        Publisher.sender("csv-importer")
            .updater(updater)
            .on(bus)
            .with(new ChannelRouter()
                .route(RawDataWrittenToInbox.class, "batch.csv.raw"))
            .with(new ErrorRouter()
                .on(SplitException.class, e ->
                    log.error("CSV split failed: {}", e.getMessage()))
                .orElse(deadLetterSink))
            .build();

        log.info("Publisher pipeline initialized for 3 sources");
    }
}

// --- Usage ---
var pipeline = new PublisherPipeline(
    kafkaEventBus,
    jpaInboxUpdater,
    e -> kafkaTemplate.send("dead-letter-queue", e.getMessage())
);

pipeline.initialize();

// Events are emitted to the bus from each source:
var sensorEvent = RawDataWrittenToInbox.from("iot-sensors")
    .withPayload("{\"deviceId\": \"DHT-22\", \"temp\": 23.1}")
    .withIdempotencyKey("DHT-22-1709136000")
    .create();

bus.emit(sensorEvent, "iot.raw");
```

---

## API Reference

### `Publisher`

| Method | Returns | Description |
|---|---|---|
| `Publisher.sender(String)` | `PublisherBuilder` | Creates a builder identified by the sender name. |

### `PublisherBuilder`

| Method | Returns | Description |
|---|---|---|
| `.updater(InboxUpdater)` | `PublisherBuilder` | Sets the inbox update strategy. |
| `.on(EventBus)` | `PublisherBuilder` | Sets the event bus to use. |
| `.with(ChannelRouter)` | `PublisherBuilder` | Sets the channel router. |
| `.with(ErrorRouter)` | `PublisherBuilder` | Sets the error router. |
| `.build()` | `void` | Builds and registers the feeder on the bus. Throws `NullPointerException` if any component is missing. |

### `ChannelRouter`

| Method | Returns | Description |
|---|---|---|
| `.route(Class<T>, String)` | `ChannelRouter` | Maps an event type to a channel. |
| `.resolve(SpoolEvent)` | `String` | Resolves the channel for an event. Defaults to `"spool"`. |

### `ErrorRouter`

| Method | Returns | Description |
|---|---|---|
| `.on(Class<E>, Consumer<E>)` | `ErrorRouter` | Registers a handler for a specific exception type. |
| `.orElse(Consumer<Exception>)` | `ErrorRouter` | Sets the fallback handler. |
| `.dispatch(Exception)` | `void` | Dispatches the exception to the matching handler. |

### Ports (Interfaces)

- **`EventBus`** — `emit(SpoolEvent, String)` / `subscribe(Class<T>, Consumer<T>)`
- **`InboxUpdater`** — `update(InboxEntryId, InboxEventStatus)`

---

## Event Model

| Event | Description |
|---|---|
| `RawDataWrittenToInbox` | Raw data written to the inbox. Triggers the Publisher. |
| `RawDataPublished` | Raw data successfully published to a channel. |
| `RawDataPublishFailed` | Failed to publish raw data. |
| `RawDataReadFromSource` | Raw data read from an external source. |
| `RawDataReadFromInbox` | Raw data read from the inbox. |
| `SourceFailed` | A data source failure. |

### Inbox States (`InboxEventStatus`)

```
UNPUBLISHED → PUBLISHED → VALIDATING → VALIDATED → PERSISTED
```

### Exceptions

| Exception | Description |
|---|---|
| `SpoolException` | Abstract base class for all Spool exceptions. |
| `DuplicateEventException` | Duplicate event detected by idempotency key. |
| `SerializationException` | Failed to serialize an event. |
| `DeserializationException` | Failed to deserialize an event. |
| `SplitException` | Failed to split input data. |

---

## License

```
Copyright 2024 Javier Castilla

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```