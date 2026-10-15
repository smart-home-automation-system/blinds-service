-- The schedules of the blinds (HAS-246): data the administrator edits on the dashboard, so they live
-- here and not in the configuration of the service. A schedule moves its targets once a day, when
-- every condition holds inside its window.
--
-- Every enum is stored as the name of its constant (WORKDAY, SUNSET, ILLUMINANCE ...), the values
-- of the API are the service's to translate; renaming a constant needs a migration of these rows.
-- The CHECKs repeat what one row can say of a schedule - ScheduleRules says the rest, and says it
-- in words where these would answer with the name of a constraint.
--
-- The window is a from and a to, each a clock time (window_*_time) or the sunrise / sunset of the
-- day moved by window_*_offset_min and held between the optional window_*_earliest and
-- window_*_latest. Times are the local time of the house, to the minute.
CREATE TABLE blinds_schedule
(
    id                     BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    name                   VARCHAR(60)        NOT NULL,
    enabled                BOOLEAN            NOT NULL,
    day_type               VARCHAR(10)        NOT NULL,
    window_from_kind       VARCHAR(10)        NOT NULL,
    window_from_time       TIME,
    window_from_offset_min SMALLINT,
    window_from_earliest   TIME,
    window_from_latest     TIME,
    window_to_kind         VARCHAR(10)        NOT NULL,
    window_to_time         TIME,
    window_to_offset_min   SMALLINT,
    window_to_earliest     TIME,
    window_to_latest       TIME,
    action                 VARCHAR(10)        NOT NULL,
    position               SMALLINT,
    created_at             TIMESTAMP          NOT NULL,
    updated_at             TIMESTAMP          NOT NULL
);

ALTER TABLE blinds_schedule
    ADD CONSTRAINT blinds_schedule_day_type_check CHECK (day_type IN ('WORKDAY', 'DAY_OFF', 'ANY'));

ALTER TABLE blinds_schedule
    ADD CONSTRAINT blinds_schedule_window_from_kind_check CHECK (window_from_kind IN ('CLOCK', 'SUNRISE', 'SUNSET'));

ALTER TABLE blinds_schedule
    ADD CONSTRAINT blinds_schedule_window_to_kind_check CHECK (window_to_kind IN ('CLOCK', 'SUNRISE', 'SUNSET'));

-- a clock time has a time and nothing else, a time of the sun an offset and no time of its own
ALTER TABLE blinds_schedule
    ADD CONSTRAINT blinds_schedule_window_from_shape_check CHECK (
        CASE window_from_kind
            WHEN 'CLOCK' THEN window_from_time IS NOT NULL AND window_from_offset_min IS NULL
                AND window_from_earliest IS NULL AND window_from_latest IS NULL
            ELSE window_from_time IS NULL AND window_from_offset_min BETWEEN -180 AND 180
            END);

ALTER TABLE blinds_schedule
    ADD CONSTRAINT blinds_schedule_window_to_shape_check CHECK (
        CASE window_to_kind
            WHEN 'CLOCK' THEN window_to_time IS NOT NULL AND window_to_offset_min IS NULL
                AND window_to_earliest IS NULL AND window_to_latest IS NULL
            ELSE window_to_time IS NULL AND window_to_offset_min BETWEEN -180 AND 180
            END);

ALTER TABLE blinds_schedule
    ADD CONSTRAINT blinds_schedule_action_check CHECK (action IN ('OPEN', 'CLOSE', 'POSITION'));

-- a position belongs to the action POSITION, and that action has one
ALTER TABLE blinds_schedule
    ADD CONSTRAINT blinds_schedule_position_check CHECK (
        (action = 'POSITION' AND position BETWEEN 0 AND 100) OR (action <> 'POSITION' AND position IS NULL));

-- What a schedule moves: a whole room (room, the name of a RoomName constant of the SDK) or one
-- blind (device_id, the id of the device registry of database-service - another database, so no
-- foreign key; a target of a removed blind stays and moves nothing). The id keeps the order the
-- targets were given in. Gone with its schedule
CREATE TABLE blinds_schedule_target
(
    id          BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    schedule_id BIGINT             NOT NULL REFERENCES blinds_schedule (id) ON DELETE CASCADE,
    room        VARCHAR(50),
    device_id   BIGINT
);

ALTER TABLE blinds_schedule_target
    ADD CONSTRAINT blinds_schedule_target_one_check CHECK ((room IS NULL) <> (device_id IS NULL));

CREATE INDEX blinds_schedule_target_schedule_idx ON blinds_schedule_target (schedule_id);

-- What has to hold for a schedule to fire, every condition at once: a metric compared with a
-- threshold (LT, GT) or with a truth (IS - raining, the window of the room closed), and how long
-- it has to hold before it counts. Thresholds: lux, degrees Celsius, metres per second. Gone with
-- its schedule
CREATE TABLE blinds_schedule_condition
(
    id           BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    schedule_id  BIGINT             NOT NULL REFERENCES blinds_schedule (id) ON DELETE CASCADE,
    metric       VARCHAR(20)        NOT NULL,
    operator     VARCHAR(2)         NOT NULL,
    threshold    NUMERIC(7, 1),
    expected     BOOLEAN,
    hold_minutes SMALLINT           NOT NULL
);

ALTER TABLE blinds_schedule_condition
    ADD CONSTRAINT blinds_schedule_condition_metric_check CHECK (
        metric IN ('ILLUMINANCE', 'OUTSIDE_TEMPERATURE', 'WIND_GUST', 'RAINING', 'WINDOW_CLOSED'));

-- a threshold for a comparison, a truth for IS - one of the two, never both
ALTER TABLE blinds_schedule_condition
    ADD CONSTRAINT blinds_schedule_condition_operator_check CHECK (
        (operator IN ('LT', 'GT') AND threshold IS NOT NULL AND expected IS NULL)
            OR (operator = 'IS' AND threshold IS NULL AND expected IS NOT NULL));

ALTER TABLE blinds_schedule_condition
    ADD CONSTRAINT blinds_schedule_condition_hold_check CHECK (hold_minutes BETWEEN 0 AND 120);

CREATE INDEX blinds_schedule_condition_schedule_idx ON blinds_schedule_condition (schedule_id);
