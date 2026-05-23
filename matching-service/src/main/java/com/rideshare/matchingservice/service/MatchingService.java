package com.rideshare.matchingservice.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.rideshare.matchingservice.client.LocationServiceClient;
import com.rideshare.matchingservice.dto.NearbyDriverResponse;
import com.rideshare.matchingservice.event.RideMatchedEvent;
import com.rideshare.matchingservice.event.RideRequestedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class MatchingService {
    
    private final LocationServiceClient locationServiceClient;
    private final KafkaTemplate<String, RideMatchedEvent> kafkaTemplate;

    private static final String RIDE_MATCHED_TOPIC = "ride.matched";
    private static final double DEFAULT_SEARCH_RADIUS = 5.0;

    /*
        Main matching alogorithm. Called when RideRequestedEvent is consumed from Kafka
        Steps:
        1. Ask location service for nearby drivers
        2. Score each driver and pickup the best one
        3. Publish RideMatchedEvent to Kafka
    */
    public void matchDriverForRide(RideRequestedEvent event) {
        
        List<NearbyDriverResponse> nearbyDrivers = locationServiceClient.getNearbyDrivers(
            event.getPickupLatitude(),
            event.getPickupLongitude(),
            DEFAULT_SEARCH_RADIUS
        );

        if (nearbyDrivers.isEmpty()) {
            log.warn("No drivers found near ride");
            return;
        }

        Optional<NearbyDriverResponse> bestDriver = findBestDriver(nearbyDrivers);
        
        if (bestDriver.isEmpty()) {
            log.warn("Could not find suitable driver for ride");
            return;
        }

        NearbyDriverResponse assignedDriver = bestDriver.get();

        RideMatchedEvent matchedEvent = RideMatchedEvent.builder()
                .rideId(event.getRideId())
                .riderId(event.getRiderId())
                .driverId(assignedDriver.getDriverId())
                .driverLatitude(assignedDriver.getLatitude())
                .driverLongitude(assignedDriver.getLongitude())
                .distanceToPickupKm(assignedDriver.getDistanceInKm())
                .build();

        kafkaTemplate.send(RIDE_MATCHED_TOPIC, event.getRideId(), matchedEvent);
        log.info("RideMatchedEvent published");
    }

    /*
        Driver scoring algorithm

        Distance: 70% weightage
        Rating: 30% weightage

        Score = (1 / distance) * distanceWeight + rating * ratingWeight
    */ 
    private Optional<NearbyDriverResponse> findBestDriver(List<NearbyDriverResponse> drivers) {

        double distanceWeight = 0.7;
        double ratingWeight = 0.3;

        return drivers.stream()
                .max(Comparator.comparingDouble(driver -> {
                    // Distance score: closer = higher score
                    // Add 0.1 to avoid division by 0
                    double distanceScore = 1.0 / (driver.getDistanceInKm() + 0.1);

                    // Simulated rating between 4.0 and 5.0 (in production, fetch from Driver Service)
                    double simulatedRating = 4.0 + Math.random();

                    // Final weighted score
                    return (distanceScore * distanceWeight) + (simulatedRating * ratingWeight);
                }));
    }
}
