-- Reports (Reports section of the console): one row per vehicle per day, so a month for 150 vehicles is about 4,650
-- small rows instead of millions of positions. Rebuilt from gps_position, gps_stop, gps_trip and vehicle_event for
-- today and yesterday every few minutes (DaySummaryJob), for older days at start and on demand.
--
-- generation 0 is the live row, recomputed from those tables. "Clear database" first freezes the live rows into a new
-- generation (> 0) and then empties the tables, so the day's numbers survive the clear; a report adds the generations
-- of a day up.
CREATE TABLE vehicle_day_summary (
    day                      date              NOT NULL,
    imei                     varchar(50)       NOT NULL,
    generation               integer           NOT NULL DEFAULT 0,
    tenant_code              varchar(40),
    vehicle_registration_no  varchar(50),
    readings                 integer           NOT NULL,
    first_seen_at            timestamptz,
    last_seen_at             timestamptz,
    distance_m               double precision  NOT NULL,
    moving_s                 bigint            NOT NULL,
    stopped_s                bigint            NOT NULL,
    max_speed_kmph           integer           NOT NULL,
    -- kilometres driven above economical_speed_kmph (the cost assumption in force when the row was computed)
    fast_distance_m          double precision  NOT NULL,
    economical_speed_kmph    integer           NOT NULL,
    -- IDLING episodes inside the day: standing with the engine on longer than the idling limit
    idle_s                   bigint            NOT NULL,
    stops                    integer           NOT NULL,
    stop_s                   bigint            NOT NULL,
    engine_on_stops          integer           NOT NULL,
    trips                    integer           NOT NULL,
    overspeed_events         integer           NOT NULL,
    harsh_events             integer           NOT NULL,
    computed_at              timestamptz       NOT NULL DEFAULT now(),
    PRIMARY KEY (day, imei, generation)
);

CREATE INDEX vehicle_day_summary_vehicle ON vehicle_day_summary (imei, day);

-- Report assumptions (fuel price, mileage, idle burn ...) edited on Reports -> Cost assumptions. A missing row means the
-- default in FuelAssumptions.defaults(). Not touched by "Clear database".
CREATE TABLE report_setting (
    name        varchar(60)   PRIMARY KEY,
    value       varchar(100)  NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now()
);

-- The summaries read one day of positions for every vehicle at a time.
CREATE INDEX gps_position_recorded_at ON gps_position (recorded_at);
