package com.rideshare.matchingservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Event published to kafka topic: ride.matched and consumed by Ride Service to update ride with assigned driver

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RideMatchedEvent {
    private String rideId;
    private String riderId;
    private String driverId;
    private double driverLatitude;
    private double driverLongitude;
    private double distanceToPickupKm;
}
