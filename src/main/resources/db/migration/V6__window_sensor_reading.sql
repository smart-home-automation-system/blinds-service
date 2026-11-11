-- the last reading of every window or door sensor (HAS-420, the copy of heating-service HAS-419), so that a restart
-- keeps what the service knew: on 2026-11-10 a grid outage restarted the BTHome gateways with this service, the
-- gateways restored the "open" half of short open/close pairs with its old time, and nothing remembered the newer
-- "closed". One row per sensor, replaced on a change of the reading only, never on a confirmation. sensor_id - the
-- registry id; open - what it said; updated_at - when its gateway heard it (last_updated_ts, an instant, null when
-- the gateway gave none); stored_at - when the row was written, house time.
-- Read back at the start as not confirmed: the history a gateway value is compared with, never a state a schedule
-- acts on. A sensor gone from the registry loses its row once the registry lists another set of sensors
CREATE TABLE window_sensor_reading
(
    sensor_id  BIGINT PRIMARY KEY       NOT NULL,
    open       BOOLEAN                  NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,
    stored_at  TIMESTAMP                NOT NULL
);
