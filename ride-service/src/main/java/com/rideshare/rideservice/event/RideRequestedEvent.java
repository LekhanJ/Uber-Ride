package com.rideshare.rideservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Event published to kafka when a ride is requested. Matching service consumes this event. Topic: ride.requested

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RideRequestedEvent {
    private String rideId;
    private String riderId;
    
    // Pickup
    private double pickupLatitude;
    private double pickupLongitude;
    private String pickupAddress;

    // Drop
    private double dropLatitude;
    private double dropLongitude;
    private String dropAddress;
}
