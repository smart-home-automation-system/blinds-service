-- The pause of the schedules of one blind (HAS-232, owner 2026-10-17): a resident switches every
-- schedule of a blind off, and the pause ends by itself after the next run it prevents - the engine
-- stores that run as PAUSED (once a window, like any run) and deletes the row. No fixed hour: a pause
-- set in the evening keeps the blind as it is through the night window and ends with it.
-- device_id is the id of the device registry of database-service (another database, no foreign key)
CREATE TABLE blinds_schedule_pause
(
    device_id BIGINT PRIMARY KEY NOT NULL,
    paused_at TIMESTAMP          NOT NULL
);

-- a run the pause prevented: the window is used up, the blind was not moved
ALTER TABLE blinds_schedule_run
    DROP CONSTRAINT blinds_schedule_run_outcome_check;
ALTER TABLE blinds_schedule_run
    ADD CONSTRAINT blinds_schedule_run_outcome_check CHECK (outcome IN ('MOVED', 'FAILED', 'UNSUPPORTED', 'PAUSED'));
