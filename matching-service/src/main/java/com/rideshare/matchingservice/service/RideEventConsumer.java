package com.rideshare.matchingservice.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.rideshare.matchingservice.event.RideRequestedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class RideEventConsumer {
    
    private final MatchingService matchingService;

    // Listens to ride.requested kafka topic. Trigger every time Ride Service publishes a new ride request
    @KafkaListener(topics = "ride.requested", groupId = "matching-service-group")
    public void consumeRideRequestedEvent(RideRequestedEvent event) {
        try {
            matchingService.matchDriverForRide(event);
        } catch (Exception e) {
            log.error("Error processing ride request: {} - {}", event.getRideId(), e.getMessage());
            // In production: send to dead letter queue for retry
        }
    }
}
