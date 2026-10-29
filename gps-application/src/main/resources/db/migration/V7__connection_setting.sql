-- Addresses of the service's outside connections, changed from the console (Developer -> Connections) while the
-- service runs. A missing row means the default from application.yml (gps.connections.*, spring.data.redis.*).
--   redis     redis://host:port/database   the Redis every consumer keeps its state in (this laptop or the HP)
--   valhalla  http://host:port             the Valhalla routing engine (all-India map on the HP home server)
-- Kept in PostgreSQL, not in Redis: the Redis address cannot live in the Redis it points to.
CREATE TABLE connection_setting (
    name        varchar(40)   PRIMARY KEY,
    url         varchar(500)  NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now()
);
