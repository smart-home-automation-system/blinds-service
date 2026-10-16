-- The runs of the schedules (HAS-247): a schedule moves each blind of its targets at most once a day,
-- and this is what makes it so across a restart - the engine reads the runs of the day before it
-- fires. A row is written once the command was sent, whatever came of it: a command is never
-- repeated, a blind that may have moved is not moved again.
--
-- device_id is the id of the device registry of database-service (another database, no foreign
-- key); fired_on the local date of the house, fired_at its local time. outcome is the name of a
-- RunOutcome constant: MOVED (the device took the command), FAILED (it did not, or did not answer),
-- UNSUPPORTED (a position for a blind that has none). Gone with its schedule
CREATE TABLE blinds_schedule_run
(
    id          BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    schedule_id BIGINT             NOT NULL REFERENCES blinds_schedule (id) ON DELETE CASCADE,
    device_id   BIGINT             NOT NULL,
    fired_on    DATE               NOT NULL,
    fired_at    TIMESTAMP          NOT NULL,
    outcome     VARCHAR(12)        NOT NULL
);

ALTER TABLE blinds_schedule_run
    ADD CONSTRAINT blinds_schedule_run_outcome_check CHECK (outcome IN ('MOVED', 'FAILED', 'UNSUPPORTED'));

-- once a day per schedule and blind, whatever the engine believes it remembers
ALTER TABLE blinds_schedule_run
    ADD CONSTRAINT blinds_schedule_run_once_a_day UNIQUE (schedule_id, device_id, fired_on);

CREATE INDEX blinds_schedule_run_fired_on_idx ON blinds_schedule_run (fired_on);
