package com.srm.gps.connectivity.database.repository;

import com.srm.gps.connectivity.database.entity.GpsTrip;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface GpsTripRepository extends JpaRepository<GpsTrip, Long> {

    /** Trips of one device that overlap the period from - to, oldest first. Uses the (imei, start_recorded_at) unique index. */
    @Query("""
            select trip from GpsTrip trip
            where trip.imei = :imei and trip.startRecordedAt < :to and trip.endRecordedAt >= :from
            order by trip.startRecordedAt
            """)
    List<GpsTrip> findOverlapping(String imei, Instant from, Instant to, Limit limit);

    /** Trips of every device that overlap the period from - to, newest first. */
    @Query("""
            select trip from GpsTrip trip
            where trip.startRecordedAt < :to and trip.endRecordedAt >= :from
            order by trip.startRecordedAt desc
            """)
    List<GpsTrip> findOverlappingAllDevices(Instant from, Instant to, Limit limit);

    long countByStatus(String status);
}
