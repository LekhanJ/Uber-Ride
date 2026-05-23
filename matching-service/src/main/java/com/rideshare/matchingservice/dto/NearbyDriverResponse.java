package com.rideshare.matchingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Response received from Location Service when querying for nearby drivers

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NearbyDriverResponse {
    private String driverId;
    private double latitude;
    private double longitude;
    private double distanceInKm;
}
