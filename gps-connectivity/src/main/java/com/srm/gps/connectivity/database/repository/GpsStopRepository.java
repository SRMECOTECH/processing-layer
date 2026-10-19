package com.srm.gps.connectivity.database.repository;

import com.srm.gps.connectivity.database.entity.GpsStop;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface GpsStopRepository extends JpaRepository<GpsStop, Long> {

    /** Stops of one device that overlap the period from - to, oldest first. Uses the (imei, start_recorded_at) unique index. */
    @Query("""
            select stop from GpsStop stop
            where stop.imei = :imei and stop.startRecordedAt < :to and stop.endRecordedAt >= :from
            order by stop.startRecordedAt
            """)
    List<GpsStop> findOverlapping(String imei, Instant from, Instant to, Limit limit);

    /** Stops of every device that overlap the period from - to, newest first. */
    @Query("""
            select stop from GpsStop stop
            where stop.startRecordedAt < :to and stop.endRecordedAt >= :from
            order by stop.startRecordedAt desc
            """)
    List<GpsStop> findOverlappingAllDevices(Instant from, Instant to, Limit limit);

    long countByStatus(String status);
}
