# blinds-service

The roller shutters of the house (epic HAS-242): moving the Eaton and Shelly blinds, knowing
where they really are and running them on schedules that take the weather into account. Since
HAS-243 it consumes the device events (and does nothing with them yet); since HAS-244 it knows
the blinds from the device registry and moves them on command through three drivers.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.blinds` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection); calls
  `database-service` (device registry), `weather-service` and `amx-service` over k8s DNS
  (`internal.service.*`). The command endpoints (`/home/blinds/...`, README) are not routed by
  `api-gateway-service` yet (HAS-251); the dashboard will branch on their codes.
- Uses libraries: `cholewa-commons` 1.8.0 (pool, error contract, connection names,
  `NotificationPublisher`), `smart-home-sdk` 1.9.0 (`SmartDevice` of the registry),
  `shelly-client` 2.0.0 (`ShellyGen1Client`, `ShellyRpcClient`).
- Database: own database `home-automation-blinds`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 = 12 of 22), pinned by
  `BlindsServiceApplicationTest`. No migration yet.
- Integrates with hardware: the Eaton blinds through `amx-service`, the Shelly blinds over HTTP
  (commands, since HAS-244) and MQTT (their status topics — HAS-245). Registered for the
  verification of HAS-244: `Office window` (id 52, Gen1 SHSW-25) and `Office door` (id 53, Gen2
  Plus 2PM cover); the other Shelly blinds come with HAS-255.

## Build & run

- Build + tests: `mvn verify` — no Docker needed yet; with the first repository test of
  PostgreSQL-only SQL (Testcontainers, dependencies already in the pom) it will be.
- Local run: `home,local` Spring profiles, port `6011` (Actuator `8011`); in-cluster port
  `6200`, Actuator `8200`. Locally the broker is `localhost`, the services are on 6005 / 6010 /
  6001, and the broker passwords default to a dummy.

## Specifics

- **The queue gets every event of every device** (bound with `#`, about 130 a minute): Shelly
  status topics (routing key `<id>.status.<component>`) and Eaton `DeviceEvent`s from
  `amx-service` (`eaton.<gateway>.<point>`). The service has to filter by the device registry
  before it acts. `DeviceEventConsumer` takes the raw `Message` (no converter: the two kinds of
  body differ), logs at `DEBUG` only and acknowledges; manual ack, prefetch 3,
  `.contextCapture()` last, errors logged and completed — a `Mono` that errors is redelivered
  at once.
- **The blinds are the registry's, read every five minutes** (`BlindRegistry`,
  `BlindRegistryRefresh`): a failed read keeps the last list (WARN); until one read has
  succeeded a lookup reads the registry itself and answers `REGISTRY_UNAVAILABLE` (502), never
  `NOT_FOUND_BLINDS`. `BlindMapper` skips what it cannot drive, and an Eaton binding never gets
  `positioning`.
- **Commands** (`BlindsCommandService`): protection first (`ProtectionGuard`), then the driver
  chosen by the sealed `BlindBinding` (`BlindsDrivers`), sent once. Anything a driver fails with
  becomes `DEVICE_FAILED`, a 502 whose answer carries the code alone — what the device said goes
  to the ERROR line of `BlindsExceptionProcessor`. A room goes one blind after the other, each
  with its own error handling; a room where nothing moved fails.
- **`source` is the caller's word** — nothing behind the gateway is authenticated, so anyone on
  the LAN can send `source=protection`. HAS-249 must not let a command through the protection
  because of its `source`; the engine and the protection call the service in-process.
- **The codes of `BlindsError` are wire contract** (`BlindsErrorTest`); a new or renamed code
  needs a text in the dashboard.
- **One instance, `Recreate`, never scaled**: the schedule engine will live in its memory.
  `@EnableScheduling` is in `SchedulingConfig` under `@Profile("!test")`.
- **The broker passwords have no default in `home`**: `BrokerPasswordCheck` refuses the start
  without `rabbitmq-password` or `notification-rabbitmq-password`. It is the third copy of the
  class (heating-service, boiler-service) — up to three copies stay.
- **The notification connection is not a bean** (`NotificationRabbitConfig`, the same as in
  heating-service): a `ConnectionFactory` or `RabbitOperations` bean would make
  `RabbitAutoConfiguration` back off, and the listener would lose manual ack, prefetch and
  observation.
- **Timeouts of the clients**: `internal.service.<name>.{connect,response}-timeout`, a bare
  number is seconds, bounded 1–60 s (`InternalServicePropertiesTest`). Each client has its own
  named `ConnectionProvider` with 2 min idle / 30 min life (the gateway's HAS-212 limits): the
  global pool keeps a connection for ever, and a POST to `amx-service` written onto a dead socket
  is not repeated.
- **Shelly calls through `shelly-client` reach Logbook only via the `ShellyHttpClientCustomizer`
  bean** (`shellyCallsInTheLog`): the library's clients put their own connector on the builder.
- **The test document points everything outward at port 1** (broker, database, the three
  services), so a context test reaches nothing even where the names resolve.
- **A migration is rehearsed on a throwaway PostgreSQL, never by a local run** — `home,local`
  with the real `database-*` values is the production database.
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`. Tests use `mockwebserver3`.
