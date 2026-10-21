-- The open alerts of the availability monitor (HAS-250): one row per Shelly blind the household was told
-- does not answer and not yet told is back - kept here, not in memory, so that a restart neither repeats
-- an alert nor forgets to announce a return (light_availability_alert of light-service, one kind only).
-- device_id is the id of the device registry of database-service (another database, no foreign key);
-- since when it is out, last_alert_at the alert or the last reminder
CREATE TABLE blinds_availability_alert
(
    device_id     BIGINT PRIMARY KEY NOT NULL,
    name          VARCHAR(200)       NOT NULL,
    room          VARCHAR(50)        NOT NULL,
    since         TIMESTAMP          NOT NULL,
    last_alert_at TIMESTAMP          NOT NULL
);
