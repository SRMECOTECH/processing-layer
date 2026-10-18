-- Master data needed to turn a device IMEI into a vehicle and a tenant.
-- This is the first slice of the GPS master-data design: tenant, vehicle, device, device_assignment.
-- The remaining tables (tenant_role, branch, app_user, trip, shipment, tracking_grant ...) follow in later migrations.

-- Needed for the "one vehicle per device at a time" exclusion constraint below.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE tenant (
    id          uuid         PRIMARY KEY,
    code        varchar(40)  NOT NULL UNIQUE,
    legal_name  varchar(255) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    -- code is stamped on every GPS position, so it is short, lower-case and never re-used
    CONSTRAINT tenant_code_format CHECK (code ~ '^[a-z0-9][a-z0-9_-]*$'),
    CONSTRAINT tenant_status_values CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE vehicle (
    id               uuid        PRIMARY KEY,
    -- one truck is one truck: the plate is unique across the whole platform
    registration_no  varchar(50) NOT NULL UNIQUE,
    -- null = market vehicle (not owned by a tenant on the platform)
    owner_tenant_id  uuid        REFERENCES tenant (id),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE device (
    id          uuid        PRIMARY KEY,
    imei        varchar(50) NOT NULL UNIQUE,
    kind        varchar(20) NOT NULL DEFAULT 'GPS',
    protocol    varchar(50),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT device_kind_values CHECK (kind IN ('GPS', 'FUEL', 'TEMPERATURE', 'DOOR', 'OBD'))
);

-- Stored as periods, never as "current vehicle": a reading delivered late must be matched
-- to the vehicle the device was in at the time of the reading.
CREATE TABLE device_assignment (
    id          uuid        PRIMARY KEY,
    device_id   uuid        NOT NULL REFERENCES device (id),
    vehicle_id  uuid        NOT NULL REFERENCES vehicle (id),
    valid_from  timestamptz NOT NULL,
    valid_to    timestamptz,
    CONSTRAINT device_assignment_period_is_valid CHECK (valid_to IS NULL OR valid_to > valid_from),
    -- A device can be in only one vehicle at a time, so every reading resolves to exactly one truck.
    CONSTRAINT device_assignment_one_vehicle_per_device_at_a_time
        EXCLUDE USING gist (device_id WITH =, tstzrange(valid_from, valid_to) WITH &&)
);

CREATE INDEX device_assignment_vehicle ON device_assignment (vehicle_id);
