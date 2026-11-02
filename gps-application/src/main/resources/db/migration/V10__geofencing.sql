-- Geofencing module (consumer group gps-geofence-service): the place table becomes the geofence master, and every
-- visit of every vehicle to every fence is kept, for the live board and the day-wise geofence report.

-- 1. place = geofence: a circle or a polygon, more roles, its own dwell and speed limits, and where it came from.
ALTER TABLE place DROP CONSTRAINT place_role_values;
ALTER TABLE place DROP CONSTRAINT place_radius_sensible;
ALTER TABLE place
    ADD COLUMN shape             varchar(10) NOT NULL DEFAULT 'CIRCLE',
    -- POLYGON: [[latitude, longitude], ...] in order around the border; radius_m is then the covering radius
    ADD COLUMN polygon           jsonb,
    -- inside longer than this raises GEOFENCE_DWELL; null = the role's default (gps.modules.geofence.dwell-*), 0 = never
    ADD COLUMN dwell_alert_min   integer,
    -- the speed limit inside the fence (a plant's 20 km/h); null = the vehicle's own rule only
    ADD COLUMN speed_limit_kmph  integer,
    -- smart_truck's id (smart_truck:{i_fence_id}) for fences copied from it
    ADD COLUMN external_ref      varchar(60),
    -- how smart_truck derived it: plant (surveyed), anchor (medoid of pings at the facility), anchor_unverified,
    -- gazetteer (a town centre standing in for an unknown customer site: a "far" reading may still be a delivery)
    ADD COLUMN source_detail     varchar(30),
    ADD COLUMN trips_seen        integer,
    ADD COLUMN spread_m          integer,
    ADD CONSTRAINT place_role_values CHECK (role IN ('ORIGIN', 'DESTINATION', 'YARD', 'CHECKPOINT', 'ZONE')),
    ADD CONSTRAINT place_shape_values CHECK (shape IN ('CIRCLE', 'POLYGON')),
    ADD CONSTRAINT place_radius_sensible CHECK (shape = 'POLYGON' OR radius_m BETWEEN 50 AND 50000),
    ADD CONSTRAINT place_polygon_present CHECK (shape = 'CIRCLE' OR (jsonb_typeof(polygon) = 'array' AND jsonb_array_length(polygon) >= 3)),
    ADD CONSTRAINT place_dwell_sensible CHECK (dwell_alert_min IS NULL OR dwell_alert_min >= 0),
    ADD CONSTRAINT place_speed_sensible CHECK (speed_limit_kmph IS NULL OR speed_limit_kmph BETWEEN 5 AND 150);

-- 2. The geofence master data of smart_truck (table geofences, 87 fences, copied 2026-11-02). A fence edited here
--    (source MANUAL) is never overwritten; POST /api/geofences/import/smart-truck copies newer smart_truck data later.
INSERT INTO place (code, name, role, latitude, longitude, radius_m, source, active, external_ref, source_detail, trips_seen, spread_m)
SELECT g.code, g.name, g.role, g.lat, g.lon, g.radius, 'SMART_TRUCK', g.active, 'smart_truck:' || g.fence_id, g.source_detail,
       g.trips, g.spread
FROM (VALUES
    (1, 'ADITYAPUR', 'ADITYAPUR', 'DESTINATION', 22.79630800, 86.13767333, 1153, 34, 769, false, true, 'anchor'),
    (2, 'AGRA CITY', 'AGRA CITY', 'DESTINATION', 27.23498400, 77.85698900, 1000, 4, 74, false, true, 'anchor'),
    (3, 'AHMEDABAD', 'AHMEDABAD', 'DESTINATION', 22.90755600, 72.42872960, 1000, 3, 144, false, true, 'anchor'),
    (4, 'AMTA', 'AMTA', 'DESTINATION', 22.57101600, 88.03771200, 1000, 17, 337, false, true, 'anchor'),
    (5, 'ARRAH', 'ARRAH', 'DESTINATION', 25.58772000, 84.73854200, 1000, 4, 66, false, true, 'anchor_unverified'),
    (6, 'BALLABGARH', 'BALLABGARH', 'DESTINATION', 28.30633700, 77.30855100, 1000, 24, 625, false, true, 'anchor'),
    (7, 'BANGALORE CITY', 'BANGALORE CITY', 'DESTINATION', 13.00517000, 77.66321600, 1000, 23, 189, false, true, 'anchor'),
    (8, 'BEEKAY STEEL-JSR', 'BEEKAY STEEL-JSR', 'DESTINATION', 22.82696167, 86.07743500, 1000, 24, 61, false, true, 'anchor_unverified'),
    (9, 'BEGUNIA', 'BEGUNIA', 'DESTINATION', 20.19000000, 85.42000000, 20000, 2, 295294, false, true, 'gazetteer'),
    (10, 'BHARATPUR', 'BHARATPUR', 'DESTINATION', 27.22711100, 77.50654200, 1000, 2, 522, false, true, 'anchor'),
    (11, 'BHUBANESWAR', 'BHUBANESWAR', 'DESTINATION', 20.31364900, 85.85586600, 1918, 23, 1279, false, true, 'anchor'),
    (12, 'BIDADI', 'BIDADI', 'DESTINATION', 12.79750000, 77.38380000, 20000, 4, 3765, false, true, 'gazetteer'),
    (13, 'BMW-GAMHARIA', 'BMW-GAMHARIA', 'DESTINATION', 22.82618900, 86.07717300, 1000, 5, 77, false, true, 'anchor'),
    (14, 'BMW-MANIFIT-JAMSHPUR', 'BMW-MANIFIT-JAMSHPUR', 'DESTINATION', 22.77848700, 86.21704000, 1000, 2, 52, false, true, 'anchor_unverified'),
    (15, 'BOKARO', 'BOKARO', 'DESTINATION', 23.57552300, 86.20327200, 1303, 3, 869, false, true, 'anchor'),
    (16, 'BOKARO STEEL CY', 'BOKARO STEEL CY', 'DESTINATION', 23.64020200, 86.08768000, 1000, 2, 207, false, true, 'anchor'),
    (17, 'CALCUTTA', 'CALCUTTA', 'DESTINATION', 22.59887900, 88.22817700, 1000, 2, 201, false, true, 'anchor'),
    (18, 'CHANDAULI MAJH', 'CHANDAULI MAJH', 'DESTINATION', 25.24578900, 83.06272900, 1000, 7, 109, false, true, 'anchor'),
    (19, 'CHENNAI', 'CHENNAI', 'DESTINATION', 13.08270000, 80.27070000, 20000, 16, 599, false, true, 'gazetteer'),
    (20, 'CRM BARA', 'CRM BARA', 'DESTINATION', 22.80624200, 86.23228300, 1000, 64, 147, false, true, 'anchor_unverified'),
    (21, 'DANKUNI', 'DANKUNI', 'DESTINATION', 22.69511000, 88.29842100, 1000, 8, 140, false, true, 'anchor'),
    (22, 'DHANBAD', 'DHANBAD', 'DESTINATION', 23.76292000, 86.58327800, 1000, 5, 99, false, true, 'anchor'),
    (23, 'DHENKANAL', 'Tata Steel Meramandali', 'DESTINATION', 20.79605300, 85.26038200, 10000, 44, 204, true, true, 'plant'),
    (24, 'DUBURI', 'Tata Steel Kalinganagar', 'DESTINATION', 20.97041100, 86.01521100, 10000, 37, 486, true, true, 'plant'),
    (25, 'FARIDABAD', 'FARIDABAD', 'DESTINATION', 28.40890000, 77.31780000, 20000, 15, 3334, false, true, 'gazetteer'),
    (26, 'GAMHARIA', 'Tata Steel Gamharia', 'DESTINATION', 22.81289500, 86.08402200, 10000, 86, 1136, true, true, 'plant'),
    (27, 'GAMHARIA-SANFAB', 'GAMHARIA-SANFAB', 'DESTINATION', 22.81002700, 86.11185800, 1000, 6, 241, false, true, 'anchor'),
    (28, 'GAMHARIA-TSG', 'GAMHARIA-TSG', 'DESTINATION', 22.82371100, 86.08977800, 1000, 12, 104, false, true, 'anchor_unverified'),
    (29, 'GAUHATI', 'GAUHATI', 'DESTINATION', 26.11693700, 91.70520900, 1000, 3, 208, false, true, 'anchor'),
    (30, 'GIRIDIH', 'GIRIDIH', 'DESTINATION', 24.05041700, 86.42737800, 1000, 34, 553, false, true, 'anchor'),
    (31, 'GORAKHPUR CANTT', 'GORAKHPUR CANTT', 'DESTINATION', 26.76060000, 83.37320000, 20000, 3, 165, false, true, 'gazetteer'),
    (32, 'GOVINDPUR', 'GOVINDPUR', 'DESTINATION', 23.84132700, 86.51949700, 1000, 36, 256, false, true, 'anchor'),
    (33, 'GUMMIDIPUNDI', 'GUMMIDIPUNDI', 'DESTINATION', 13.40760000, 80.10870000, 20000, 4, 15031, false, true, 'gazetteer'),
    (34, 'HOSUR', 'HOSUR', 'DESTINATION', 12.74090000, 77.82530000, 20000, 2, 1437597, false, true, 'gazetteer'),
    (35, 'HOWRAH', 'HOWRAH', 'DESTINATION', 22.59824700, 88.22685300, 1000, 14, 492, false, true, 'anchor'),
    (36, 'HUBLI', 'HUBLI', 'DESTINATION', 15.36470000, 75.12400000, 20000, 2, 1420656, false, true, 'gazetteer'),
    (37, 'HYDERABAD', 'HYDERABAD', 'DESTINATION', 17.38500000, 78.48670000, 20000, 6, 79, false, true, 'gazetteer'),
    (38, 'INDORE', 'INDORE', 'DESTINATION', 22.76177600, 75.95973300, 5000, 3, 11950, false, true, 'anchor'),
    (39, 'ISWP-JAMSHEDPUR', 'ISWP-JAMSHEDPUR', 'DESTINATION', 22.77223800, 86.23353600, 1000, 10, 69, false, true, 'anchor_unverified'),
    (40, 'JABALPUR', 'JABALPUR', 'DESTINATION', 23.18150000, 79.98640000, 20000, 2, 625747, false, true, 'gazetteer'),
    (41, 'JAIPUR', 'JAIPUR', 'DESTINATION', 26.94254400, 75.75944000, 5000, 19, 6533, false, true, 'anchor'),
    (42, 'JAJPUR ROAD', 'Tata Steel Kalinganagar', 'DESTINATION', 20.97041100, 86.01521100, 10000, 23, 186, true, true, 'plant'),
    (43, 'JAMSHEDPUR', 'JAMSHEDPUR', 'DESTINATION', 22.77360700, 86.21681500, 1000, 128, 96, false, true, 'anchor'),
    (44, 'JAMSHEDPUR', 'Tata Steel Jamshedpur', 'ORIGIN', 22.78859800, 86.19960000, 10000, 1346, 1790, true, true, 'plant'),
    (45, 'JAMSHEDPUR (IBMD)', 'JAMSHEDPUR (IBMD)', 'DESTINATION', 22.82080300, 86.15939300, 1000, 26, 195, false, true, 'anchor'),
    (46, 'JCAPCPL_JSR WORKS', 'JCAPCPL_JSR Works', 'DESTINATION', 22.78300600, 86.20552100, 1161, 33, 774, false, true, 'anchor'),
    (47, 'JSR WORKS PLANTTRY', 'JSR WORKS PLANTTRY', 'DESTINATION', 22.78452900, 86.20697500, 1000, 20, 529, false, true, 'anchor'),
    (48, 'KANHAN', 'KANHAN', 'DESTINATION', 21.21852900, 79.24661700, 1000, 20, 502, false, true, 'anchor'),
    (49, 'KANPUR', 'KANPUR', 'DESTINATION', 26.39943600, 80.24158000, 5000, 11, 18926, false, true, 'anchor'),
    (50, 'KARANJIA', 'KARANJIA', 'DESTINATION', 21.75365600, 85.97498700, 1000, 2, 253, false, true, 'anchor'),
    (51, 'KARUR %SR#', 'KARUR %SR#', 'DESTINATION', 10.94527000, 78.12932400, 1000, 3, 270, false, true, 'anchor'),
    (52, 'KHOPOLI', 'KHOPOLI', 'DESTINATION', 18.80107700, 73.28833700, 1000, 3, 540, false, true, 'anchor'),
    (54, 'KOLABIRA', 'KOLABIRA', 'DESTINATION', 22.80395600, 86.03067600, 1000, 4, 99, false, true, 'anchor'),
    (55, 'LUDHIANA', 'LUDHIANA', 'DESTINATION', 30.87868000, 75.98849800, 5000, 2, 6000, false, true, 'anchor'),
    (56, 'MALDA TOWN', 'MALDA TOWN', 'DESTINATION', 25.02348200, 88.16378700, 1000, 8, 470, false, true, 'anchor'),
    (57, 'MALLAVALLI', 'MALLAVALLI', 'DESTINATION', 16.69823800, 80.89031600, 1000, 5, 506, false, true, 'anchor'),
    (58, 'MEDAK', 'MEDAK', 'DESTINATION', 17.66734300, 78.18116400, 1000, 3, 153, false, true, 'anchor'),
    (59, 'MEDCHAL', 'MEDCHAL', 'DESTINATION', 17.62970000, 78.48090000, 20000, 4, 139, false, true, 'gazetteer'),
    (60, 'NANDED', 'NANDED', 'DESTINATION', 19.13152700, 77.33448900, 1000, 2, 247, false, true, 'anchor'),
    (61, 'PALWAL', 'PALWAL', 'DESTINATION', 28.20680000, 77.30710000, 2124, 31, 1416, false, true, 'anchor'),
    (62, 'PATNA CITY', 'PATNA CITY', 'DESTINATION', 25.59410000, 85.13760000, 20000, 58, 454, false, true, 'gazetteer'),
    (63, 'PILKHUWA', 'PILKHUWA', 'DESTINATION', 28.69986100, 77.58792300, 1000, 9, 114, false, true, 'anchor'),
    (64, 'PITHAMPUR (M.P.)', 'PITHAMPUR (M.P.)', 'DESTINATION', 22.60760000, 75.67990000, 20000, 11, 10503, false, true, 'gazetteer'),
    (65, 'PURNEA JN.', 'PURNEA JN.', 'DESTINATION', 25.72052100, 87.51296500, 1000, 7, 251, false, true, 'anchor'),
    (66, 'RAJKOT', 'RAJKOT', 'DESTINATION', 22.37897100, 70.92816900, 1000, 7, 640, false, true, 'anchor'),
    (67, 'RAMGARH CANTT', 'RAMGARH CANTT', 'DESTINATION', 23.68904800, 85.50720900, 1000, 2, 82, false, true, 'anchor'),
    (68, 'RANCHI', 'RANCHI', 'DESTINATION', 23.26619600, 85.30533000, 1000, 3, 43, false, true, 'anchor'),
    (69, 'RANIGANJ', 'RANIGANJ', 'DESTINATION', 23.63251100, 87.12493200, 1000, 10, 132, false, true, 'anchor'),
    (70, 'RANIPPETTAI', 'RANIPPETTAI', 'DESTINATION', 13.00416800, 79.30485100, 1000, 3, 47, false, true, 'anchor'),
    (71, 'RANJANGAON', 'RANJANGAON', 'DESTINATION', 18.77260000, 74.24820000, 20000, 9, 2006, false, true, 'gazetteer'),
    (72, 'ROURKELA', 'ROURKELA', 'DESTINATION', 22.20408400, 84.74773400, 1000, 11, 149, false, true, 'anchor'),
    (73, 'RUDRAPUR', 'RUDRAPUR', 'DESTINATION', 28.98450000, 79.41410000, 20000, 27, 3020, false, true, 'gazetteer'),
    (74, 'SANAND', 'SANAND', 'DESTINATION', 22.99220000, 72.38190000, 20000, 32, 136, false, true, 'gazetteer'),
    (75, 'SANGAREDDY', 'SANGAREDDY', 'DESTINATION', 17.62480000, 78.08660000, 20000, 2, 1022501, false, true, 'gazetteer'),
    (76, 'SANSWADI', 'SANSWADI', 'DESTINATION', 18.68000000, 74.05000000, 20000, 22, 424, false, true, 'gazetteer'),
    (77, 'SARAIKELA KHARSAWAN', 'SARAIKELA KHARSAWAN', 'DESTINATION', 22.79090400, 86.15172400, 1000, 14, 435, false, true, 'anchor'),
    (78, 'SINGUR', 'SINGUR', 'DESTINATION', 22.81753300, 88.24571600, 1000, 2, 72, false, true, 'anchor'),
    (79, 'SURAT', 'SURAT', 'DESTINATION', 21.09586700, 72.88758700, 1000, 3, 260, false, true, 'anchor'),
    (80, 'TANGI', 'TANGI', 'DESTINATION', 20.58118300, 86.02957900, 1000, 13, 264, false, true, 'anchor'),
    (81, 'TBL- BARA AGRICO', 'TBL- BARA AGRICO', 'DESTINATION', 22.81376500, 86.23905800, 1000, 25, 36, false, true, 'anchor'),
    (82, 'TCIL-JAMSHEDPUR', 'TCIL-JAMSHEDPUR', 'DESTINATION', 22.78957600, 86.22433800, 1000, 6, 665, false, true, 'anchor_unverified'),
    (83, 'TIRUNINRAVUR', 'TIRUNINRAVUR', 'DESTINATION', 13.12533000, 80.01223000, 1000, 22, 409, false, true, 'anchor'),
    (84, 'TITAGARH', 'TITAGARH', 'DESTINATION', 22.74103900, 88.37270800, 1000, 4, 207, false, true, 'anchor_unverified'),
    (85, 'TRYL-BARA', 'TRYL-BARA', 'DESTINATION', 22.81165800, 86.23227600, 1000, 58, 126, false, true, 'anchor'),
    (86, 'UDHAM SINGH NAGAR', 'UDHAM SINGH NAGAR', 'DESTINATION', 28.97500000, 79.40000000, 20000, 2, 267, false, true, 'gazetteer'),
    (88, 'VADODARA', 'VADODARA', 'DESTINATION', 22.30720000, 73.18120000, 20000, 2, 363, false, true, 'gazetteer'),
    (89, 'VIRAMGAM', 'VIRAMGAM', 'DESTINATION', 23.08149760, 72.17208500, 1000, 7, 441, false, true, 'anchor')
) AS g (fence_id, code, name, role, lat, lon, radius, trips, spread, manual, active, source_detail)
ON CONFLICT (code, role) DO UPDATE SET
    name = EXCLUDED.name, latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, radius_m = EXCLUDED.radius_m,
    active = EXCLUDED.active, external_ref = EXCLUDED.external_ref, source_detail = EXCLUDED.source_detail,
    trips_seen = EXCLUDED.trips_seen, spread_m = EXCLUDED.spread_m, updated_at = now()
WHERE place.source = 'SMART_TRUCK';

-- 3. One row per visit (or pass) of one vehicle to one fence, written by the geofence module:
--      VISIT  inside long enough to be believed (15 min, the smart_truck rule); open (exited_at null) while inside
--      PASS   through without stopping long enough, or over the fence between two readings (inferred)
--    Stamps are readings the vehicle was seen at: entered_at = first reading inside, exited_at = last reading inside.
--    entry_gap_s / exit_gap_s are the gaps to the readings outside: the uncertainty of each stamp.
--    Identity: device + fence + kind + entry, so the same visit found twice (a redelivery, a replay) is one row.
--    No foreign key to place: a fence deleted later keeps its history, with its code and name as they were.
CREATE TABLE geofence_visit (
    id                       bigint           GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    imei                     varchar(50)      NOT NULL,
    vehicle_registration_no  varchar(50),
    tenant_code              varchar(40),
    place_id                 bigint           NOT NULL,
    place_code               varchar(255)     NOT NULL,
    place_name               varchar(255)     NOT NULL,
    place_role               varchar(12)      NOT NULL,
    kind                     varchar(6)       NOT NULL,
    entered_at               timestamptz      NOT NULL,
    exited_at                timestamptz,
    entry_gap_s              bigint,
    exit_gap_s               bigint,
    entry_latitude           double precision NOT NULL,
    entry_longitude          double precision NOT NULL,
    exit_latitude            double precision,
    exit_longitude           double precision,
    dwell_alert_at           timestamptz,
    dwell_limit_min          integer,
    inferred                 boolean          NOT NULL DEFAULT false,
    exit_reason              varchar(20),
    created_at               timestamptz      NOT NULL DEFAULT now(),
    updated_at               timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT geofence_visit_once UNIQUE (imei, place_id, kind, entered_at),
    CONSTRAINT geofence_visit_kind_values CHECK (kind IN ('VISIT', 'PASS')),
    CONSTRAINT geofence_visit_period_valid CHECK (exited_at IS NULL OR exited_at >= entered_at)
);

-- the live board: who is inside which fence now
CREATE INDEX geofence_visit_open ON geofence_visit (place_id) WHERE exited_at IS NULL;
-- the day report and the visit log: visits overlapping a period
CREATE INDEX geofence_visit_time ON geofence_visit (entered_at DESC);
CREATE INDEX geofence_visit_place_time ON geofence_visit (place_id, entered_at DESC);
CREATE INDEX geofence_visit_vehicle_time ON geofence_visit (vehicle_registration_no, entered_at DESC);
CREATE INDEX geofence_visit_imei_time ON geofence_visit (imei, entered_at DESC);
