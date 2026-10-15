# blinds-service

[![CI](https://github.com/smart-home-automation-system/blinds-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/blinds-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_blinds-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_blinds-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_blinds-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_blinds-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/blinds-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/blinds-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/blinds-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_blinds-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_blinds-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_blinds-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_blinds-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/blinds-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/blinds-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/blinds-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/blinds-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/blinds-service?style=plastic)

---


# Description

The roller shutters of the house: moving them, knowing where they really are and running them
on schedules that take the weather into account (epic HAS-242). The blinds are Eaton devices,
driven through `amx-service`, and Shelly devices.

Since HAS-244 the service **moves the blinds on command**, and since HAS-245 it **knows the
real state of every blind**, whoever moved it — the Shelly app, a wall button, an AMX panel or
the service itself. It has no schedules yet (HAS-246..248) and no wind and frost protection
(HAS-249).

Everything is non-blocking (Spring WebFlux / Reactor):

- **The blinds come from the device registry** of `database-service`
  (`GET /home/device/registry?kind=blinds&enabled=true`), read at the start and every five
  minutes and kept in memory. A failed read keeps the last list (a WARN). A blind just
  registered is driven within five minutes; a blind switched off or removed is still driven
  for up to five minutes. A device the registry holds too little about (a Shelly without its
  address or generation, an Eaton device without its point) is skipped with a WARN.
- **Three kinds of actuator behind one driver interface**:
  - a Shelly Gen1 roller (`generation` 1): `GET /roller/<channel>?go=open|close|stop|to_pos`;
  - a Shelly Gen2+ cover: `Cover.Open|Close|Stop|GoToPosition` with `id = channel`;
  - an Eaton actuator: `POST /home/amx/eaton/blinds/{point}?command=` of `amx-service`. It has
    no positions, whatever the registry says.
- **A command is sent once and never repeated.** It writes no state: where a blind is comes
  only from what the devices report. Every command is logged at INFO with who asked for it
  (`source`).
- **The real state** of every blind is kept in memory and merged from what the devices
  report:
  - a **Shelly Gen2+ cover** publishes `<deviceId>/status/cover:<channel>` and
    `<deviceId>/online` over MQTT (decoded by `shelly-client`, matched by the `deviceId` of
    the registry); `Cover.GetStatus` of every cover every five minutes reconciles what a lost
    message left behind. Its reachability is what MQTT says;
  - a **Shelly Gen1 roller** is not on MQTT: `/status` of every roller every 60 s, and its
    `roller_open_url` / `roller_close_url` / `roller_stop_url` actions call
    `GET /home/blinds/events/gen1` (below). A roller that does not answer the poll becomes
    unreachable (WARN on the change, INFO on the return);
  - an **Eaton actuator** reports its status through `amx-service` (`eaton.blinds.<point>`):
    `1` opening, `2` closing, `0` stopped. That is its last movement — it never says where it
    stopped, so an Eaton blind has no position, and no `open` or `closed`. The group point 26
    (ALL) is not read.
- **Who moved it** (`source`): a movement seen within two minutes of a command of this service
  takes the command's `source`; the rest that ends a movement keeps the source of that
  movement; otherwise it is the word of the Shelly device (`SHC` for the app, `button`,
  `http`, …) or `external`.
- **Device events** arrive from RabbitMQ, virtual host `/device` (user `device`), queue
  `device.prod.blinds` (`device.dev.blinds` in the `local` profile). The queue is bound to
  `amq.topic` with `#`, so it gets every event of every device — the status topics of the
  Shelly devices over MQTT and the `DeviceEvent` of the Eaton devices published by
  `amx-service` — about 130 a minute. Only those of a blind of the registry are read, by the
  routing key first. The listener acknowledges by hand with a prefetch of 3; an event that
  cannot be read is logged at ERROR and acknowledged.
- **Notifications** go to the `notification` exchange of the virtual host `/notification`
  over a second connection (`NotificationPublisher` of `cholewa-commons`), named
  `<pod>/notification`.
- **Clients** of `database-service` (the device registry), `weather-service` and
  `amx-service`, each a `WebClient` over k8s DNS with its own connect and response timeouts
  (`internal.service.*`).
- **The schedules run in the memory of one instance**: the deployment is `Recreate` and never
  scaled; scheduling is off under the `test` profile.

# Run locally

- Build: `mvn verify` (JDK 21) **needs a running Docker**: `BlindStateRepositoryTest` runs the
  PostgreSQL-only SQL of the state on a PostgreSQL of Testcontainers. Without a Docker it fails,
  it is not skipped.
- Ports: local profile `6011` (management `8011`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The probes hit
  `/actuator/health/{readiness,liveness}` and Prometheus scrapes `/actuator/prometheus`.
- Profiles `home,local`. The broker is `localhost:5672`; without `rabbitmq-password` and
  `notification-rabbitmq-password` a local run starts with a dummy password and neither
  consumes nor publishes. In the `home` profile both variables are mandatory — without one
  the service does not start.
- The other services are called on their local ports: `database-service` 6005,
  `weather-service` 6010, `amx-service` 6001.
- The database connection properties (`database-host`, `database-port`, `database-name`,
  `database-user`, `database-password`) are bound to the `database.*` prefix that
  `cholewa-commons` consumes; Flyway derives its JDBC URL from them (`--flyway-url=...`
  overrides it). Only `database.pool.max-size: 2` is pinned here, this service's share of the
  22 backend connections of the managed database.

# API

Base path `/home/blinds` (`spring.webflux.base-path`). Not routed by `api-gateway-service`
yet (HAS-251) — inside the cluster, or through a port-forward.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/blinds` | Every blind with its state, by room: `[{"room", "blinds": [...]}]`, the rooms in the order of the SDK |
| `GET` | `/home/blinds/rooms/{room}` | The blinds of one room: `{"room", "blinds": [...]}` |
| `GET` | `/home/blinds/{id}` | One blind (`id` of the device registry) |
| `GET` | `/home/blinds/events/gen1?device={id}&event=open\|close\|stop` | A Gen1 action: called by the roller itself, answers `204` |
| `POST` | `/home/blinds/{id}?command=open\|close\|stop&source=` | Move one blind (`id` of the device registry) |
| `POST` | `/home/blinds/{id}/position?value=0..100&source=` | Send a blind with `positioning` to a position, 0 closed, 100 open |
| `POST` | `/home/blinds/rooms/{room}?command=open\|close\|stop&source=` | Move every blind of a room, one after the other |

- **A blind in a read** is `{"id", "name", "room", "vendor", "placement", "positioning",
  "state", "position", "lastDirection", "movedAt", "source", "reachable"}`:
  - `state` is `open`, `closed`, `opening`, `closing` or `stopped`;
  - `position` is 0 (closed) to 100 (open), only from a blind that knows it;
  - `lastDirection` is `open` or `close`;
  - `movedAt` is when the state began, local time of the house;
  - `source` is who moved it (above);
  - `reachable` is whether the device answers.

  **A field the service does not know is left out**, never answered as closed, zero or
  unreachable: nothing before the first report, no position from an Eaton blind, no
  `reachable` right after a restart.
- **A Gen1 action** (`device` is the registry id of the blind, set in the `roller_*_url` of the
  device): `open` and `close` mean the roller reached that end, `stop` that it stopped. The
  same action twice is the same state, so a repeated call is harmless. A device that is not a
  Gen1 blind of the registry gets a `404` (logged at WARN).
- **A command answers `202`**: the device (or `amx-service`) took the command — never that the
  blind got there. A single blind answers with no body.
- **`command`, `room` and `source` are taken exactly as written**; anything else is a `400`.
  `room` is the value of the registry (`living room`, `office`, …). `source` is `dashboard`
  (the default), `schedule` or `protection`. It becomes the `source` of the movement the
  command starts; nothing decides by it yet.
- **A room** answers `{"room", "command", "moved": [{"id", "name"}], "failed": [{"id", "name", "code"}]}`.
  A blind that fails is skipped and named in `failed`, and the others still move. When none
  moved, the room fails like a single blind: `DEVICE_FAILED` when a device failed, otherwise
  the refusal of the first blind.

Failures come in the error contract of `cholewa-commons`, with a `code` to branch on:

| Status | `code` | When |
|---|---|---|
| 400 | — | an unknown `command`, `source` or `room`, a `value` outside 0–100, a missing parameter |
| 400 | `UNSUPPORTED_POSITION` | a position for a blind without `positioning` (every Eaton actuator) |
| 404 | `NOT_FOUND_BLINDS` | no switched-on blind with the `id`, no blind in the room, a Gen1 action of a device that is no Gen1 blind |
| 409 | `PROTECTION_ACTIVE` | the wind and frost protection keeps the blind where it is (HAS-249; nothing raises it yet) |
| 502 | `DEVICE_FAILED` | the device or `amx-service` did not take the command, or did not answer in time. The answer carries no more than that; what the device said is in the ERROR log. It **may have been carried out** — read the state, do not repeat it blindly |
| 502 | `REGISTRY_UNAVAILABLE` | the device registry could not be read since the start of the pod |

Timeouts: the Shelly devices 3 s to connect and 5 s to answer (the defaults of
`shelly-client`); `amx-service` 3 s / 10 s — it may read the registry and wait for the frame
server first.

# Database

- **Own database** `home-automation-blinds` on the managed PostgreSQL, reactive access via
  `r2dbc-postgresql`, migrations by Flyway from `src/main/resources/db/migration`.
- **`blinds_state`** (`V1`): one row per period of an unchanged state of a blind —
  `device_id` (the registry id), `state`, `position`, `last_direction`, `source`,
  `started_at`, `last_checked_at`. A row is inserted only when the state changes, or when a
  blind at rest reports another position. A reading that confirms the state moves
  `last_checked_at` of the latest row and fills in the position. The rows of one blind are
  written in the order of its changes.
- **The latest row of a blind is its highest id**, not its latest `started_at` (local time,
  which runs backwards on the night the clocks go back). The latest rows are read back once at
  the start, before the first report is applied. If the database cannot be read then, the
  state is rebuilt from the devices — within a minute for Gen1, five for Gen2.
- No retention yet: a row per change keeps the table small.
