package com.rideshare.matchingservice.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.rideshare.matchingservice.dto.NearbyDriverResponse;

@FeignClient(name = "location-service", url = "${location.service.url}")
public interface LocationServiceClient {
    
    @GetMapping("/api/v1/locations/drivers/nearby")
    List<NearbyDriverResponse> getNearbyDrivers(
        @RequestParam double latitude,
        @RequestParam double longitude,
        @RequestParam double radius
    );
}
