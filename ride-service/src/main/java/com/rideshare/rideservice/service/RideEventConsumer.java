package com.rideshare.rideservice.service;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.rideshare.rideservice.event.RideMatchedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class RideEventConsumer {
    
    private final RideService rideService;

    @KafkaListener(topics = "ride.matched", groupId = "ride-service-group")
    public void consumeRideMatchedEvent(RideMatchedEvent event) {
        rideService.updateRideWithDriver(event.getRideId(), event.getDriverId());
    }
}
