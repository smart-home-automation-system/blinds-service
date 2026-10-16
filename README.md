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
the service itself. Since HAS-246 it **stores the schedules** the dashboard edits, and since
HAS-247 **runs them every minute** from the calendar of the house, the sun and the weather.
There are no overrides of a resident yet (HAS-248), and no wind and frost protection (HAS-249).

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
    (ALL) is not read. **An actuator does not always get the status of its start through**
    (HAS-291): when an open or a close of this service is followed by no movement within
    `blinds.eaton.confirm-after` (5 s), the actuator is asked for its status once
    (`POST /home/amx/eaton/blinds/{point}/status` of `amx-service`) and its answer is the
    movement. A stop that arrives within two minutes of such a command, with no movement
    reported in between, is the end of a movement in the commanded direction. When nothing at
    all arrives the command is unconfirmed: a WARN in the log, the state left as it was.
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
  scaled; scheduling is off under the `test` profile. How they run is under Schedules.

# Run locally

- Build: `mvn verify` (JDK 21) **needs a running Docker**: the repository tests run on a
  PostgreSQL of Testcontainers. Without a Docker they fail, they are not skipped.
- Ports: local profile `6011` (management `8011`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The probes hit
  `/actuator/health/{readiness,liveness}` and Prometheus scrapes `/actuator/prometheus`.
- Profiles `home,local`. The broker is `localhost:5672`; without `rabbitmq-password` and
  `notification-rabbitmq-password` a local run starts with a dummy password and neither
  consumes nor publishes. In the `home` profile both variables are mandatory — without one
  the service does not start.
- **The place of the house**, for the sunrise and the sunset: `location-latitude` and
  `location-longitude` (degrees, north and east positive). Mandatory in every profile but
  `test` — they are not in the public configuration, the manifest gives them.
- The other services are called on their local ports: `database-service` 6005,
  `weather-service` 6010, `amx-service` 6001.
- `blinds.eaton.confirm-after` (default `PT5S`, 1 to 60 s, a bare number is seconds): how long
  the start of an Eaton movement is waited for before the actuator is asked for its status.
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
| `GET` | `/home/blinds/schedules` | Every schedule, in the order they were created |
| `GET` | `/home/blinds/schedules/{id}` | One schedule |
| `GET` | `/home/blinds/schedules/{id}/today` | What the engine makes of a schedule today (below) |
| `POST` | `/home/blinds/schedules` | Create a schedule: `201` with the schedule as stored |
| `PUT` | `/home/blinds/schedules/{id}` | Replace a schedule whole, `enabled` included: `200` with the schedule |
| `POST` | `/home/blinds/schedules/{id}/enable` \| `/disable` | Switch a schedule on or off without sending the rest: `200` with the schedule |
| `DELETE` | `/home/blinds/schedules/{id}` | Remove a schedule with its targets and conditions: `204` |

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
| 400 | `INVALID_SCHEDULE` | a schedule that breaks a rule (below); `details` says which, in words |
| 404 | `NOT_FOUND_SCHEDULE` | no schedule with the `id` |
| 400 | — | a schedule body that cannot be read: a value the API does not have (`weekend`, `ANY`), a time that is not `HH:mm`, a fraction where a whole number belongs |

Timeouts: the Shelly devices 3 s to connect and 5 s to answer (the defaults of
`shelly-client`); `amx-service` 3 s / 10 s — it may read the registry and wait for the frame
server first.

# Schedules

A schedule moves its targets **once a day**, when **every condition holds inside its window**, on
the days of its type.

| Field | Values |
|---|---|
| `name` | 3–60 characters, no space at either end |
| `enabled` | `true` / `false` — a write sets it like every other field |
| `dayType` | `workday`, `day off` (a weekend, a holiday or a day off of the household, as the calendar of `database-service` says) or `any` |
| `window.from`, `window.to` | each `{"kind": "clock", "time": "HH:mm"}`, or `{"kind": "sunrise"\|"sunset", "offsetMinutes": -180..180, "earliest": "HH:mm", "latest": "HH:mm"}` — `earliest` and `latest` optional |
| `targets` | 1–30, each `{"room": "living room"}` (every blind of the room) or `{"deviceId": 53}` (one blind of the registry), each listed once |
| `conditions` | 0–10 (an empty list for none), each `{"metric", "operator", "threshold" \| "value", "holdMinutes": 0..120}` |
| `action` | `open`, `close` or `position` with `position` 0 (closed) to 100 (open) |
| `id`, `createdAt`, `updatedAt` | the service's, in a read only; a body that sends them does not set them |

- **Conditions**: `illuminance` (lx, 0–200 000), `outside temperature` (°C, −50–60) and
  `wind gust` (m/s, 0–100) are compared `lt` or `gt` with a `threshold` of at most one decimal;
  `raining` and `window closed` (the window of the room of the target) are compared `is` with a
  `value`, `true` or `false`. `holdMinutes` is how long the condition has to hold without a break
  before it counts.
- **The window**: two clock times run forward within one day — `to` after `from`, never across
  midnight. Where an end is a time of the sun, the order is known only on the day.
- **Nothing in a body has a default**: a field left out is refused with `INVALID_SCHEDULE`, never
  read as off or zero. A field that does not belong (`position` with `close`, `offsetMinutes` on a
  clock time, a `threshold` for `raining`) is refused as well. The first broken rule is the answer.
- **A target blind is not checked against the registry**: a write does not depend on
  `database-service`, and a target of a blind removed later moves nothing.
- **A write is all or nothing**: the schedule, its targets and its conditions are written in one
  transaction — a schedule stays as it was while a write fails.

Close the living room and the office door half an hour before sunset, not before 17:00 and not
after 21:30, once it has been darker than 200 lx for ten minutes:

```json
{
  "name": "Evening close",
  "enabled": true,
  "dayType": "any",
  "window": {
    "from": {"kind": "sunset", "offsetMinutes": -30, "earliest": "17:00", "latest": "21:30"},
    "to": {"kind": "clock", "time": "23:00"}
  },
  "targets": [{"room": "living room"}, {"deviceId": 53}],
  "conditions": [{"metric": "illuminance", "operator": "lt", "threshold": 200, "holdMinutes": 10}],
  "action": "close"
}
```

Summer shading: on a workday between 11:00 and 16:00, half-close the office once it has been
warmer than 25.5 °C for half an hour and brighter than 40 000 lx for a quarter, with its window
closed:

```json
{
  "name": "Summer shading",
  "enabled": true,
  "dayType": "workday",
  "window": {"from": {"kind": "clock", "time": "11:00"}, "to": {"kind": "clock", "time": "16:00"}},
  "targets": [{"room": "office"}],
  "conditions": [
    {"metric": "outside temperature", "operator": "gt", "threshold": 25.5, "holdMinutes": 30},
    {"metric": "illuminance", "operator": "gt", "threshold": 40000, "holdMinutes": 15},
    {"metric": "window closed", "operator": "is", "value": true, "holdMinutes": 0}
  ],
  "action": "position",
  "position": 30
}
```

## The engine

Every minute (the first time a minute after the start) every schedule is judged, and one that
fires moves the blinds of its targets it has not moved today, one after the other, with the
`source` `schedule`.

- **The day type** comes from `GET /home/calendar/days` of `database-service`, once a day. While
  the calendar cannot be read the weekday decides — Saturday and Sunday off — with a WARN, and the
  calendar is asked again after ten minutes.
- **The window** is resolved for the day: a clock time as it is; a sunrise or a sunset of the
  house, moved by its offset (but never past midnight) and then held between `earliest` and
  `latest`. `from` is in the window, `to` is not. A window that ends before it starts passes the
  day without firing.
- **The weather** comes from `GET /home/weather/current` of `weather-service` every minute. A
  metric the answer leaves out, holds `stale`, or measured more than ten minutes ago is
  **unknown**; so is everything when `weather-service` cannot be read (one WARN, one INFO when
  it is back). The comparisons are strict: 200 is not `lt` 200.
- **`window closed` is always unknown for now**: no window sensor exists in the house yet, so a
  schedule with that condition does not fire.
- **What is not known never holds**: a schedule that needs an unknown input does not fire; the
  others do.
- **A hold** counts from the first minute the condition was seen true, without a break. The holds
  live in memory: a restart, or a minute the engine could not judge, starts them again — a
  schedule then fires later, never earlier.
- **Once a day per blind**: every blind a schedule sent a command to is recorded with what came
  of it — `moved`, `failed` (it did not take the command, or did not answer — it may have moved
  all the same) or `unsupported` (a position for a blind without one). A recorded blind is not
  moved again that day, after a restart either. A blind the protection holds back is not recorded
  and is tried again the next minute. When the runs of the day cannot be read, nothing fires.
- A blind named on its own and through its room is moved once; a target the registry does not
  know moves nothing.
- Every decision is logged at INFO with its reason when it changes — `Schedule 3 (Evening close):
  waiting for the conditions, window 17:17-23:00: illuminance lt 200 for 10 min holding`.

`GET /home/blinds/schedules/{id}/today` answers what the engine makes of a schedule now, and
changes nothing (it reads the weather of the last minute):

```json
{
  "scheduleId": 3,
  "date": "2026-10-16",
  "dayType": "workday",
  "dayTypeFromCalendar": true,
  "runsToday": true,
  "window": {"from": "17:17", "to": "23:00"},
  "conditions": [
    {"metric": "illuminance", "operator": "lt", "threshold": 200, "holdMinutes": 10,
     "current": 150, "measuredAt": "2026-10-16T17:20:05", "trueSince": "2026-10-16T17:18:00", "state": "holding"}
  ],
  "targets": [
    {"deviceId": 53, "name": "Office door", "room": "office", "firedAt": "2026-10-16T17:28:00", "outcome": "moved"},
    {"deviceId": 52, "name": "Office window", "room": "office"}
  ],
  "decision": "waiting for the conditions"
}
```

- `dayTypeFromCalendar` is `false` when the weekday decided. `window` is left out when the day
  has no such time of the sun.
- `state` of a condition: `met`, `holding` (true, the hold not over), `not met`, `unknown`;
  `current`, `measuredAt` and `trueSince` are left out when not known.
- `targets`: the blinds of the targets, and a blind moved today that the registry no longer
  knows (without `name` and `room`); `firedAt` and `outcome` once it was moved today.
- `decision`, the first that applies: `disabled`, `not today`, `no window today`, `fired today`,
  `before the window`, `after the window`, `no blind of its targets is known`,
  `waiting for the conditions`, `fires`.
- `404` `NOT_FOUND_SCHEDULE` for a schedule that does not exist.

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
- **`blinds_schedule`, `blinds_schedule_target`, `blinds_schedule_condition`** (`V2`, HAS-246):
  a schedule with its window, action and position; its targets (a room by the name of its
  `RoomName` constant, or a `device_id` of the registry — another database, so no foreign key);
  its conditions (`threshold` for `lt` / `gt`, `expected` for `is`). The enums are stored by the
  names of their constants. The targets and conditions keep the order they were given (their
  ids) and go with their schedule (`ON DELETE CASCADE`). The CHECKs repeat what one row can say
  of a schedule.
- **`blinds_schedule_run`** (`V3`, HAS-247): a blind a schedule sent a command to — `schedule_id`,
  `device_id`, `fired_on` (the local date), `fired_at`, `outcome` (`MOVED`, `FAILED`,
  `UNSUPPORTED`). `UNIQUE (schedule_id, device_id, fired_on)`: once a day, whatever the engine
  remembers. Gone with its schedule; no retention yet - a few rows a day.
