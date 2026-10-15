-- One row per period of an unchanged state of a blind: a row is inserted only when the state
-- changes, a reading that confirms it moves last_checked_at (and fills in the position) of the
-- latest row of the blind. device_id is the id of the blind in the device registry of
-- database-service - another database, so there is no foreign key; the rows of a removed blind stay.
-- position and last_direction are NULL where the blind did not say (an Eaton actuator never does
-- for the position); source is who moved it: dashboard, schedule, protection (a command of this
-- service), the word of a Shelly device, or external
CREATE TABLE blinds_state
(
    id              BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    device_id       BIGINT             NOT NULL,
    state           VARCHAR(10)        NOT NULL,
    position        SMALLINT,
    last_direction  VARCHAR(5),
    source          VARCHAR(50)        NOT NULL,
    started_at      TIMESTAMP          NOT NULL,
    last_checked_at TIMESTAMP          NOT NULL
);

ALTER TABLE blinds_state
    ADD CONSTRAINT blinds_state_state_check CHECK (state IN ('OPEN', 'CLOSED', 'OPENING', 'CLOSING', 'STOPPED'));

ALTER TABLE blinds_state
    ADD CONSTRAINT blinds_state_last_direction_check CHECK (last_direction IN ('OPEN', 'CLOSE'));

ALTER TABLE blinds_state
    ADD CONSTRAINT blinds_state_position_check CHECK (position BETWEEN 0 AND 100);

-- the latest row of a blind is its highest id: read at the start, updated by every confirmation
CREATE INDEX blinds_state_device_latest_idx ON blinds_state (device_id, id DESC);
