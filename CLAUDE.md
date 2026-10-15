# blinds-service

The roller shutters of the house (epic HAS-242): moving the Eaton and Shelly blinds, knowing
where they really are and running them on schedules that take the weather into account. Since
HAS-243 a skeleton: it consumes the device events, has its clients and the notification
publisher, and acts on nothing yet.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.blinds` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection); calls
  `database-service` (device registry), `weather-service` and `amx-service` over k8s DNS
  (`internal.service.*`). No endpoints, not routed by `api-gateway-service`.
- Uses libraries: `cholewa-commons` 1.8.0 (pool, error contract, connection names,
  `NotificationPublisher`), `smart-home-sdk` 1.9.0, `shelly-client` 2.0.0 (its three beans are
  registered — nothing calls a device yet).
- Database: own database `home-automation-blinds`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 = 12 of 22), pinned by
  `BlindsServiceApplicationTest`. No migration yet.
- Integrates with hardware: the Eaton blinds through `amx-service`, the Shelly blinds over HTTP
  (commands) and MQTT (their status topics) — both still to come.

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
