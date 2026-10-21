# blinds-service

The roller shutters of the house (epic HAS-242): moving the Eaton and Shelly blinds, knowing
where they really are and running them on schedules that take the weather into account. Since
HAS-243 it consumes the device events; since HAS-244 it knows the blinds from the device registry
and moves them on command through three drivers; since HAS-245 it tracks the real state of every
blind from what the devices report and serves it (`GET /home/blinds`, `/rooms/{room}`, `/{id}`);
since HAS-246 it stores the schedules (`/home/blinds/schedules`, CRUD); since HAS-247 the schedule
engine runs them every minute and `GET /schedules/{id}/today` shows what it makes of one; since
HAS-290 it reads the window sensors of every blind, and a window may run past midnight; since HAS-250
it tells the household when a Shelly blind stops answering and when a blind leaves its commands
unconfirmed.

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
- Uses libraries: `cholewa-commons` 1.9.0 (pool, error contract, connection names,
  `NotificationPublisher`, `cholewa.required-properties`), `smart-home-sdk` 1.12.0 (`SmartDevice` of
  the registry, `CalendarDay` / `DayType` of the calendar), `shelly-client` 2.1.0
  (`ShellyGen1Client`, `ShellyRpcClient`); `commons-suncalc` for the times of the sun.
- Reads from other services for the engine: `GET /home/calendar/days` of `database-service`
  (once a day), `GET /home/weather/current` of `weather-service` (every minute) - the names
  `illuminance`, `temperature`, `gust`, `raining` there are wire contract (`WeatherClient.METRICS`).
- Database: own database `home-automation-blinds`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 = 12 of 22), pinned by
  `BlindsServiceApplicationTest`. Tables: `blinds_state` (`V1`, HAS-245); `blinds_schedule`,
  `blinds_schedule_target`, `blinds_schedule_condition` (`V2`, HAS-246); `blinds_schedule_run`
  (`V3`, HAS-247); `blinds_schedule_pause` (`V4`, HAS-232); `blinds_availability_alert` (`V5`,
  HAS-250). **The next migration is `V6`**.
- Integrates with hardware: the Eaton blinds through `amx-service`, the Shelly blinds over HTTP
  (commands, since HAS-244) and MQTT (their status topics — HAS-245). Registered for the
  verification of HAS-244: `Office window` (id 52, Gen1 SHSW-25) and `Office door` (id 53, Gen2
  Plus 2PM cover); the other Shelly blinds come with HAS-255.

## Build & run

- Build + tests: `mvn verify` **needs a running Docker** since HAS-245: `BlindStateRepositoryTest`
  runs the `DISTINCT ON` and the `touchLatest` of the state on a Testcontainers PostgreSQL
  (`@DataR2dbcTest`, migrations applied by hand), and so does `ScheduleServiceTest` (the schedules,
  their transaction and the CHECKs of `V2`), and `ScheduleRunRepositoryTest` (`V3`). Without
  Docker it fails, it is not skipped.
- Local run: `home,local` Spring profiles, port `6011` (Actuator `8011`); in-cluster port
  `6200`, Actuator `8200`. Locally the broker is `localhost`, the services are on 6005 / 6010 /
  6001, and the broker passwords default to a dummy.

## Specifics

- **The queue gets every event of every device** (bound with `#`, about 130 a minute): Shelly
  status topics (routing key `<id>.status.<component>`) and Eaton `DeviceEvent`s from
  `amx-service` (`eaton.<gateway>.<point>`). The service has to filter by the device registry
  before it acts. `DeviceEventConsumer` takes the raw `Message` (no converter: the two kinds of
  body differ) and hands it to `BlindEventRouter`, which decides by the routing key and
  `BlindRegistry.current()` (the list as last read, never a read of its own — 130 lookups a
  minute must not hit `database-service`) before a body is decoded. Manual ack, prefetch 3,
  `.contextCapture()` last, errors logged and completed — a `Mono` that errors is redelivered
  at once.
- **The state (HAS-245) is merged in `BlindStateTracker`**, in memory; `decide()` is pure and
  holds every rule, pinned by `BlindStateTrackerTest`:
  - a row of `blinds_state` only on a change; a confirming reading touches the latest row (the
    highest id) — the presence-service shape;
  - the writes of one blind go one after the other (a `Sinks.Empty` per write, only the last
    one kept), or a restart could read back an older state;
  - the restore runs once (cached), before the first reading; a failure is a WARN and is not
    repeated;
  - times are cut to microseconds, what the column keeps.
- **Attribution of a movement**: `BlindsCommandService` calls `commanded(blind, source, stop)`
  **before** it sends; the note is kept when the call fails, because the blind may have moved.
  - The first change within `COMMAND_WINDOW` (2 min — more than two Gen1 polls apart) takes the
    command's source.
  - The rest that ends a movement keeps the movement's source, unless a `stop` command ended
    it.
  - A rest without a position confirms a reached end only when its `last_direction` points
    there (an uncalibrated Gen1 roller).
- **Which channel says what**:
  - Gen2+: MQTT `status/cover:<channel>` + `online`, matched by `deviceId` case-insensitively.
    `Cover.GetStatus` every 5 min is a reconciliation that **never changes reachability** (MQTT
    owns it).
  - Gen1: `/status` every 60 s (a failure makes the roller unreachable) and the `roller_*_url`
    actions on `GET /events/gen1?device=<registry id>` (routed by HAS-251, configured on the devices
    in HAS-255). **`open` / `close` come when the roller starts, `stop` when it stops** - seen on a
    Shelly 2.5 on v1.14.0 (HAS-255): the first version took them for the end reached and showed a
    blind closed for the half minute it was still closing. After a stop the device gets its `204`
    at once and its `/status` is read a second later (`BlindsQueryService.readAfterStop`,
    `BlindStatePoll.readGen1`, a detached chain with `.contextCapture()` and an error consumer):
    the roller waits for the answer to its own call (found in review). A roller that does not answer
    keeps the stop (WARN) and is not made unreachable - it has just called. **An uncalibrated roller
    reads `stopped` after a move**: neither the action nor `/status` says whether it stopped at an
    end (accepted in review - every registered roller is calibrated).
  - Eaton: the `status` event of `amx-service` (`0` stop, `1` open, `2` close), no position.
    The group point 26 is deliberately not read: it is unregistered, and the cinema actuator
    (12) reports on it too.
- **An Eaton movement is confirmed, not assumed** (HAS-291): the `70` of a start may be lost
  (the kitchen, 2026-10-16: only the final `00` arrived), and the gateway's `C3 1C 04` proves
  nothing about the actuator.
  - `commanded(...)` returns the `IssuedCommand`; only an open or a close of an Eaton blind
    waits for a movement (`expectsMovement`). `MoveConfirmation` asks the tracker about **that
    object**, so a newer command is told apart; a replaced command is settled at once and never
    reported.
  - After `blinds.eaton.confirm-after` (`EatonProperties`, `PT5S`, 1–60 s) without a movement,
    one status request through `amx-service` (`EatonDriver.requestStatus`); its answer is the
    usual status event. A failed request is a WARN - never repeated.
  - `decide()`: a `00` without a position while the command still `expected` a direction is the
    end of a movement that way, with the command's source. A stop command keeps its meaning.
    A status answer `00` from an actuator that never got the command reads the same - accepted
    in HAS-291, not refined in HAS-250.
  - The wait is a `Mono.delay` chain subscribed in `start()` with `.contextCapture()` (the
    `traceId` of the command) and an error consumer; a restart forgets the waits.
- **Every command waits for its answer** (HAS-250, `MoveConfirmation`, which took over
  `EatonMoveConfirmation`): `commanded(blind, source, CommandTarget)` - the target is an open or a
  close, a stop or a position, and `CommandTarget.answeredBy` says which report answers it (moving
  its way or at its end, any rest, moving or at the position; pinned by `CommandTargetTest`).
  - The window is the binding's: a Gen2+ cover 60 s (`GEN2_CONFIRM_WINDOW`, it publishes a change
    at once), a Gen1 roller and an Eaton actuator `COMMAND_WINDOW` (two polls). Only a sent command
    waits - a failed one is the caller's 502 and the availability monitor's.
  - A command of a **Shelly** blind already where it asks has nothing to wait for
    (`awaitsConfirmation` false): a cover that does not move publishes nothing. Never for Eaton -
    the actuator knows no end, it runs and reports.
  - The answer is the change the command started (`commandUsed`) or a report that answers its target;
    a movement the state already had counts (the HAS-291 rule, now by direction).
  - Nothing in the window: `unconfirmed()` once, a WARN, the state untouched; the command stays
    in `commands`, so a late movement is still attributed while `COMMAND_WINDOW` lasts.
  - Per blind, in memory: `lastCommandConfirmed` (`true` / `false`, left out of the reads until a
    command was judged since the start) and the count in a row. A confirmed command or **any
    reported change of the state** (a button, a late movement) ends a run of unconfirmed ones.
  - The third unconfirmed command in a row is an **alert**, once a run (`unconfirmedAnnounced`
    after the broker took it - a failed publish is tried with the next one); none while the blind
    is unreachable - the availability monitor speaks for it. No info when it ends.
- **The availability monitor** (HAS-250), package `monitor`, the shape of light-service's
  (HAS-264): every minute (from 2 min after the start, only once the registry was read) the
  Shelly blinds by the reachability of `BlindStateTracker` - a failed Gen1 poll, the MQTT last will
  of a Gen2+ cover (the reconciliation never changes it). `AvailabilityEvaluator` is pure: out after
  `blinds.monitor.offline-after` (`MonitorProperties`, PT5M, 2 min - 2 h) counted from the first pass
  that saw it unreachable, a reminder every `reminder-every` (PT1H, 15 min - 24 h), an info on the
  return; one message a pass names every blind (ten, the rest counted). Open alerts in
  `blinds_availability_alert` (V5), written only after the broker took the message, so a restart
  neither repeats nor forgets one. A return needs the blind to answer: after a restart a blind
  still out is not "back" in the minutes before it is out again (found here; light-service
  announces such a false return). Eaton blinds are not watched.
- **The state reads leave out what is not known** (`BlindView`, `NON_NULL`): no state before
  the first report, no position from an Eaton blind, no `reachable` after a restart, no
  `lastCommandConfirmed` before a command was judged. `protection` comes with HAS-249.
- **`GET /events/gen1` is unauthenticated** like everything behind the gateway: anyone on the
  LAN can write a Gen1 state. HAS-248 and HAS-249 must not act on a Gen1 state the next poll has
  not confirmed.
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
- **The schedules (HAS-246)**: `ScheduleRules` holds every rule of a body and answers
  `INVALID_SCHEDULE` (400) with the reason, pinned rule by rule by `ScheduleRulesTest`; no
  `@Valid`, nothing in a body has a default. **A threshold must be crossable** (HAS-294): the engine
  compares strictly, so `lt` at the least of a range and `gt` at the most are refused
  (`checkReachable`) - `illuminance lt 0` kept a door open all night. A range moved in `rangeOf`
  moves those edges too; the dashboard copies both. `ScheduleService` writes the three tables in **one
  transaction** (`TransactionalOperator`, auto-configured on the pooled `ConnectionFactory`) -
  `should_leave_a_schedule_as_it_was_when_a_replace_fails` fails without it. Reads go one after
  the other (pool of 2). The models live here, not in the SDK (one consumer); the dashboard
  copies the values of the enums (`day off`, `outside temperature`, `lt`/`gt`/`is`), so a new or
  renamed value is a change on both sides, and a renamed constant a migration of the rows.
  - Times are `HH:mm` both ways (`@JsonFormat` on `WindowEnd`): Jackson 3 would write
    `17:00:00`. A threshold is answered as given (`200`, not `200.0` or `2E+2`).
  - A CHECK that comes out NULL lets the row in - `V2` says `IS NOT NULL` explicitly where it
    matters.
  - A target blind is not checked against the registry: the engine must skip one it does not know.
- **The schedule engine (HAS-247)**, package `engine`: `ScheduleEnginePass` every minute (first
  one a minute after the start), `ScheduleEngine` gathers the inputs and acts, **`ScheduleEvaluator`
  decides and holds every rule** - pure, pinned by `ScheduleEvaluatorTest` (window kinds and clamps,
  day types, truths, holds, the order of the verdicts, once a day).
  - **What is not known never holds**: a weather metric missing, `stale`, or measured more than
    10 min ago; a time of the sun the day does not have. The schedule that needs it does not fire,
    the others do. A failed calendar read means the weekday alone (WARN), asked again after 10 min.
  - **`window closed` is judged per blind** (HAS-290), never per room: `ScheduleEvaluator.truth`
    takes the `WindowState` of the blind, the engine evaluates a schedule with such a condition for
    every blind of its targets, and the hold of that condition is the blind's own
    (`HoldTracker.Key` with `blindId`); the weather conditions stay per schedule. A blind fires when
    it is pending and every one of its conditions is met (`ready`); `decide` takes the counts.
  - **A window may cross midnight** (HAS-290): `ScheduleEvaluator.occurrence` resolves the window
    that opens on a date (an end before the start is the next day's, a sunrise of the next day's
    sun), `current` picks yesterday's night while it lasts - on a day it runs on - else today's.
    The night belongs to the day it opened on: its day type, its sun, its `fired_on`. So the pass
    reads the runs of yesterday and today (`findByFiredOnIn`), and `DayTypes` / `SunCalendar`
    keep a few days around today (they kept one before - asking two days every minute would have
    called the calendar every minute).
  - **Once a window per schedule and blind**: the runs are read before anything fires (a
    failed read fires nothing), the blind is remembered in memory **before** its command is sent,
    the row after it (`UNIQUE (schedule_id, device_id, fired_on)`). A failed command is a run
    (`FAILED`) and is never repeated; a position for a blind without one is `UNSUPPORTED`; a blind
    the protection holds back is not a run and is tried again the next minute.
  - The holds live in memory (`HoldTracker`): a restart starts them again - later, never earlier.
  - **The pause of a blind** (HAS-232, owner 2026-10-17 - it supersedes the room overrides of
    HAS-248): `SchedulePauseService`, table `blinds_schedule_pause` (V4). It ends **after the next
    run it prevents**, not at an hour (the owner chose that over a daily reset at 12:00): the
    engine stores that run as `PAUSED` and deletes the row (`pausedRun`). Every schedule that
    would move the blind in the same pass is held back too; one whose window opens later is not
    (accepted in review). The pass reads the pauses strictly - none read, nothing fires; the reads
    of the blinds use `pausedAsLastKnown` (the last read with a WARN), because they were served
    from memory before and must not go blank for a database away (found in review).
    `GET /{id}/schedules` (`BlindSchedules`) is `ScheduleEngine.today` of every schedule whose
    targets take in the blind, cut down to it - the read of the room view of the dashboard.
  - A decision is logged at INFO when it changes, not every minute; a weather failure is one WARN
    and one INFO when it is back.
  - The sun is computed for `blinds.location.{latitude,longitude}` (`location-latitude` /
    `location-longitude` of the manifest; not in the public yaml, no default - a local run needs
    them too) in the zone of the JVM (`TZ=Europe/Warsaw` of the image).
  - `GET /schedules/{id}/today` changes nothing: the weather of the last pass, the holds as it
    left them; its `decision` and `state` values are the `@JsonValue`s of `Verdict` /
    `ConditionStatus` - the dashboard will copy them.
- **The window sensors (HAS-290)**, package `window`: `WindowSensors` reads the opening sensors
  (every one, a switched-off one is left out of its window) and the switched-on BTHome gateways from
  the registry every five minutes (`WindowSensorsRefresh`), then each gateway with
  `ShellyRpcClient.dynamicComponents` - the `bthomesensor` components of object `0x2D` (45) whose
  `addr` is a registered sensor, with their status - the way `SensorMapLoader` of weather-service
  maps its station. `BlindEventRouter` hands it a `bthomesensor` status topic only when `knows()`
  says the component is one of them, before the body is read.
  - **A BLU Door/Window has no heartbeat** (measured 2026-10-17: 82 min silent while untouched;
    beacon mode is off): the owner chose "the last state holds" - a sensor is unknown only when it
    never reported, or its gateway has neither published it nor answered a read for
    `blinds.window.gateway-silent-after` (`WindowProperties`, PT30M, 10 min - 24 h, minutes).
    Do not reintroduce a per-sensor silence bound.
  - `open` beats `unknown` beats `closed` across the sensors of one window; the newer
    `last_updated_ts` wins between a read and a publish. A value that is no boolean is ignored.
  - The state lives in memory: after a restart a window is unknown until the first read of its
    gateway (seconds - the refresh starts at once).
  - The Gen1 Door/Window 2 of the sauna entrance is out (owner, 2026-10-17): `/events/gen1` still
    takes Gen1 blinds only.
- **One instance, `Recreate`, never scaled**: the schedule engine lives in its memory (holds, the
  blinds sent today). `@EnableScheduling` is in `SchedulingConfig` under `@Profile("!test")`.
- **What has no default in `home`** - the two broker passwords and the place of the house - is
  listed in `cholewa.required-properties` (`cholewa-commons` 1.9.0), which refuses the start
  before the first bean; `RequiredPropertiesTest` ties the list to the placeholders. The own
  `BrokerPasswordCheck` copy is gone (HAS-247).
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
  with the real `database-*` values is the production database. The recipe of `V1`:
  `postgres:17` in Docker with `-c ssl=on` and the snakeoil certificate of the image
  (`MSYS_NO_PATHCONV=1` in Git Bash), then the jar with `home,local`, the five `--database-*`
  arguments and `--flyway-url=...?sslmode=require`. A local run polls the real Shelly devices
  (reads only); the registry can be a stub on 6005.
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`. Tests use `mockwebserver3`, and a publisher is verified as
  `publisher.as(StepVerifier::create)` only (converted everywhere with HAS-247).
