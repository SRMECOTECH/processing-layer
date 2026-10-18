package com.srm.gps.connectivity.smarttruck;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection details of the existing smart_truck MySQL database (properties starting with "smart-truck.database").
 * This database belongs to another system: we only ever READ from it.
 *
 * @param url       JDBC url, for example jdbc:mysql://localhost:3306/smart_truck
 * @param username  database user
 * @param password  database password (kept out of git, see local-secrets.properties)
 */
@ConfigurationProperties(prefix = "smart-truck.database")
public record SmartTruckDatabaseSettings(
        String url,
        String username,
        String password) {
}
