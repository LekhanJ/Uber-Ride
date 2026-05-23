# 🚗 Uber-Style Ride Sharing Platform

> A production-grade, event-driven microservices backend that replicates the core of Uber's ride dispatch system — ride requesting, geospatial driver matching, and real-time location tracking — built with Spring Boot 4, Apache Kafka, Redis GEO, and OpenFeign.

![Java](https://img.shields.io/badge/Java_26-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.0-6DB33F?style=for-the-badge&logo=spring&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-KRaft-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)
![Redis](https://img.shields.io/badge/Redis_GEO-Location-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Database-4169E1?style=for-the-badge&logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)

---

## 📌 Overview

This project implements the core backend pipeline of a ride-sharing platform. A rider requests a trip → the system finds the nearest available driver using Redis geospatial queries → a weighted scoring algorithm selects the best driver → the ride is confirmed, all via asynchronous Kafka events. No service calls another synchronously except for the one critical lookup: the Matching Service fetches nearby drivers from the Location Service via Feign.

**Key design decisions:**
- Services are **decoupled through Kafka** — Ride Service never calls Matching Service directly
- Driver locations are stored in **Redis GEO** (a geospatial sorted set) enabling O(log N) radius queries instead of scanning a database
- The **Haversine formula** computes fare estimates from raw GPS coordinates — no Maps API required
- Driver selection uses a **weighted scoring algorithm**: 70% proximity, 30% rating

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              RIDER / DRIVER                                 │
└──────────┬────────────────────────────────┬─────────────────────────────────┘
           │  POST /api/v1/rides/request    │  POST /api/v1/locations/drivers/update
           │  GET  /api/v1/rides/{id}       │  (every 3 seconds from driver app)
           ▼                                ▼
┌─────────────────────┐          ┌──────────────────────────┐
│    Ride Service     │          │    Location Service      │
│     port: 8083      │          │      port: 8082          │
│                     │          │                          │
│ PostgreSQL: rides   │          │ Redis GEO: GEOADD        │
│ Haversine fare calc │          │ GEORADIUS nearby search  │
│ Ride lifecycle mgmt │          │ ZREM driver removal      │
└────────┬────────────┘          └──────────────┬───────────┘
         │                                      ▲
         │ Kafka: ride.requested                │ HTTP (Feign)
         ▼                                      │
┌───────────────────────────────────────────────┴──┐
│                   Apache Kafka                   │
│           (KRaft — no ZooKeeper)                 │
│                                                  │
│  ride.requested  →  3 partitions, 1 replica      │
│  ride.matched    →  3 partitions, 1 replica      │
└──────────┬───────────────────────────────────────┘
           │ ride.requested consumed
           ▼
┌────────────────────────┐
│   Matching Service     │
│     port: 8084         │
│                        │
│ Consumes ride.requested│
│ Calls Location Service │──── GET /api/v1/locations/drivers/nearby
│ Scores & picks driver  │     (via OpenFeign)
│ Publishes ride.matched │
└────────────────────────┘
           │
           │ Kafka: ride.matched consumed
           ▼
    Ride Service updates:
    ride.driverId = assigned driver
    ride.status   = ACCEPTED
```

---

## 📂 Project Structure

```
Uber-Ride/
│
├── docker-compose.yml                   # Kafka (KRaft) container
│
├── ride-service/                        # Port 8083 — ride lifecycle
│   └── src/main/java/com/rideshare/rideservice/
│       ├── controller/RideController.java       # REST endpoints
│       ├── service/RideService.java             # Business logic + Haversine
│       ├── service/RideEventConsumer.java       # Kafka listener for ride.matched
│       ├── model/Ride.java                      # JPA entity
│       ├── model/RideStatus.java               # Status enum
│       ├── event/RideRequestedEvent.java        # Kafka event (published)
│       ├── event/RideMatchedEvent.java          # Kafka event (consumed)
│       ├── repository/RideRepository.java
│       ├── config/KafkaConfig.java             # Topic declarations (ride.requested, ride.matched)
│       └── exception/GlobalExceptionHandler.java
│
├── location-service/                    # Port 8082 — Redis GEO tracking
│   └── src/main/java/com/rideshare/locationservice/
│       ├── controller/LocationController.java   # Update / nearby / remove
│       ├── service/LocationService.java         # Redis GEO operations
│       ├── config/RedisConfig.java             # RedisTemplate<String, String> setup
│       └── dto/DriverLocationRequest.java
│
└── matching-service/                    # Port 8084 — driver assignment
    └── src/main/java/com/rideshare/matchingservice/
        ├── service/MatchingService.java         # Scoring algorithm
        ├── service/RideEventConsumer.java       # @KafkaListener on ride.requested
        ├── client/LocationServiceClient.java    # Feign client → Location Service
        ├── event/RideRequestedEvent.java        # Kafka event (consumed)
        └── event/RideMatchedEvent.java          # Kafka event (published)
```

---

## 🔄 Complete Ride Lifecycle

### Phase 1 — Rider requests a ride

```
POST /api/v1/rides/request
{
  "riderId": "rider-123",
  "pickupLatitude": 21.1458,  "pickupLongitude": 79.0882,
  "pickupAddress": "Nagpur Railway Station",
  "dropLatitude": 21.1767,    "dropLongitude": 79.0572,
  "dropAddress": "Nagpur Airport"
}

RideService.requestRide():
  1. Calculates estimated fare via Haversine formula
     distance ≈ 4.2 km → fare = ₹50 + (4.2 × ₹12) = ₹100.40
  2. Saves Ride to PostgreSQL  { status: REQUESTED }
  3. Publishes RideRequestedEvent → Kafka topic: ride.requested
  4. Updates ride status → MATCHING
  5. Returns RideResponse to rider immediately (non-blocking)
```

### Phase 2 — Matching Service finds the best driver

```
RideEventConsumer.consumeRideRequestedEvent():
  Receives RideRequestedEvent from Kafka

MatchingService.matchDriverForRide():
  1. Calls LocationService via Feign:
     GET /api/v1/locations/drivers/nearby?latitude=21.1458&longitude=79.0882&radius=5.0
     → Redis GEORADIUS returns up to 10 nearest drivers, sorted by distance

  2. Scores each driver:
     distanceScore = 1.0 / (distanceKm + 0.1)     ← closer = higher
     simulatedRating = random between 4.0 and 5.0  ← in prod: from Driver Service
     score = (distanceScore × 0.7) + (rating × 0.3)

  3. Picks driver with highest score

  4. Publishes RideMatchedEvent → Kafka topic: ride.matched
     { rideId, riderId, driverId, driverLat, driverLng, distanceToPickupKm }
```

### Phase 3 — Ride Service confirms the match

```
RideEventConsumer (ride-service) consumes ride.matched:
  ride.driverId = assignedDriver.driverId
  ride.status   = ACCEPTED
  → saved to PostgreSQL
```

### Phase 4 — Active ride management

```
Driver starts ride:
  PUT /api/v1/rides/{rideId}/start
  → validates status == ACCEPTED
  → status = RIDE_STARTED, startedAt = now()

Driver completes ride:
  PUT /api/v1/rides/{rideId}/complete
  → validates status == RIDE_STARTED
  → status = COMPLETED, completedAt = now(), actualFare = estimatedFare

Cancel at any stage:
  PUT /api/v1/rides/{rideId}/cancel
  → status = CANCELLED
```

---

## 📍 Redis GEO — How Driver Location Works

The Location Service uses Redis's native geospatial commands, not a database. All driver positions live in a single **Redis Sorted Set** called `drivers:locations`.

```
Redis key: drivers:locations     (type: ZSET — geospatial sorted set)

GEOADD drivers:locations  79.0882  21.1458  "driver-001"
GEOADD drivers:locations  79.0912  21.1423  "driver-002"
GEOADD drivers:locations  79.0751  21.1501  "driver-003"
```

Each driver's coordinates are encoded into a 52-bit geohash and stored as the sorted set score. This makes radius queries extremely fast.

**Finding nearby drivers (GEORADIUS):**

```
GEORADIUS drivers:locations 79.0882 21.1458 5 km
  ASC            ← sorted nearest-first
  WITHCOORD      ← include coordinates in response
  WITHDIST       ← include distance in km
  COUNT 10       ← limit to 10 results
```

This is O(N+log M) where N is the number of matches and M is the total set size — not O(total drivers). With millions of drivers, this still responds in milliseconds.

**Driver updates every 3 seconds:**
```
GEOADD drivers:locations <longitude> <latitude> <driverId>
```
`GEOADD` on an existing member updates its position in-place. No delete + insert needed.

**Driver goes offline:**
```
ZREM drivers:locations <driverId>
```

---

## 💰 Fare Calculation — Haversine Formula

Fare is computed without any external Maps API using the **Haversine formula** — the standard for calculating great-circle distance between two GPS coordinates.

```java
// Convert degrees to radians
lat1 = toRadians(pickupLatitude)
lat2 = toRadians(dropLatitude)
lon1 = toRadians(pickupLongitude)
lon2 = toRadians(dropLongitude)

// Haversine formula
a = sin²((lat2-lat1)/2) + cos(lat1) × cos(lat2) × sin²((lon2-lon1)/2)
c = 2 × arcsin(√a)

distanceKm = 6371 × c        // 6371 = Earth's radius in km

// Fare model
fare = ₹50 (base) + distanceKm × ₹12 (per km)
```

The Haversine formula gives the shortest distance between two points on a sphere (Earth), accounting for Earth's curvature. It's accurate to within ~0.3% for distances under 100 km.

---

## 🎯 Driver Scoring Algorithm

When multiple drivers are within the 5 km radius, the Matching Service picks the **best** one using a weighted score:

```
distanceScore = 1.0 / (distanceInKm + 0.1)
                          ↑
                   +0.1 prevents division by zero for drivers at exact pickup point

finalScore = (distanceScore × 0.7) + (driverRating × 0.3)
```

**Why these weights?**
- **70% distance** — proximity is the primary factor. A driver 0.5 km away is almost always better than one 4 km away, regardless of rating.
- **30% rating** — prevents penalising highly-rated drivers too harshly for being slightly further.

In production, `driverRating` would be fetched from a dedicated Driver Service. The current implementation simulates a rating between 4.0–5.0.

---

## 🔁 Ride Status Lifecycle

```
                    ┌─────────┐
                    │REQUESTED│  ← Ride saved to DB
                    └────┬────┘
                         │ Kafka event published
                    ┌────▼────┐
                    │MATCHING │  ← Driver search in progress
                    └────┬────┘
                         │ Kafka ride.matched consumed
                    ┌────▼────┐
                    │ACCEPTED │  ← Driver assigned
                    └────┬────┘
                         │ PUT /start
               ┌─────────▼──────────┐
               │   RIDE_STARTED     │  ← Trip in progress
               └─────────┬──────────┘
                         │ PUT /complete
               ┌─────────▼──────────┐
               │    COMPLETED       │  ← Fare finalised
               └────────────────────┘

  CANCELLED ← can occur from REQUESTED, MATCHING, or ACCEPTED
```

> The `DRIVER_ARRIVING` status is defined in the enum and reserved for a future extension (e.g. a WebSocket push when the driver is within 500 m of pickup).

---

## 📡 API Reference

### Ride Service (port 8083)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/rides/request` | Rider requests a new trip |
| `GET` | `/api/v1/rides/{rideId}` | Get ride details by ID |
| `GET` | `/api/v1/rides/rider/{riderId}` | Get all rides for a rider (latest first) |
| `PUT` | `/api/v1/rides/{rideId}/start` | Driver starts the trip |
| `PUT` | `/api/v1/rides/{rideId}/complete` | Driver marks trip as complete |
| `PUT` | `/api/v1/rides/{rideId}/cancel` | Cancel a ride |

### Location Service (port 8082)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/locations/drivers/update` | Update driver's GPS position (called every 3s) |
| `GET` | `/api/v1/locations/drivers/nearby?latitude=&longitude=&radius=` | Find drivers within radius (km) |
| `DELETE` | `/api/v1/locations/drivers/{driverId}` | Remove driver when offline |

### Matching Service (port 8084)

The Matching Service exposes no public REST endpoints. It operates entirely via Kafka — consuming `ride.requested` and publishing `ride.matched`.

---

## 🚀 Getting Started

### Prerequisites

- Java 26
- Maven 3.9+
- Docker and Docker Compose
- Redis (running locally on port 6379)
- PostgreSQL (running locally on port 5432)

### 1. Start Kafka

```bash
docker-compose up -d
# Starts Kafka in KRaft mode (no ZooKeeper) on port 9092
```

### 2. Create the PostgreSQL database

```bash
psql -U postgres -c "CREATE DATABASE uberride_db;"
```

### 3. Configure credentials

Update `ride-service/src/main/resources/application.yaml` with your PostgreSQL credentials:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/uberride_db
    username: your_username
    password: your_password
```

### 4. Start all services

```bash
# Terminal 1 — start Location Service first (Matching Service depends on it)
cd location-service && ./mvnw spring-boot:run

# Terminal 2
cd ride-service && ./mvnw spring-boot:run

# Terminal 3
cd matching-service && ./mvnw spring-boot:run
```

### 5. Test the full flow

```bash
# Step 1: Simulate a driver going online
curl -X POST http://localhost:8082/api/v1/locations/drivers/update \
  -H "Content-Type: application/json" \
  -d '{"driverId": "driver-001", "latitude": 21.1480, "longitude": 79.0900}'

# Step 2: Rider requests a ride
curl -X POST http://localhost:8083/api/v1/rides/request \
  -H "Content-Type: application/json" \
  -d '{
    "riderId": "rider-123",
    "pickupLatitude": 21.1458, "pickupLongitude": 79.0882,
    "pickupAddress": "Nagpur Railway Station",
    "dropLatitude": 21.1767, "dropLongitude": 79.0572,
    "dropAddress": "Nagpur Airport"
  }'
# Note the returned rideId

# Step 3: Check ride status (should become ACCEPTED after Kafka processing)
curl http://localhost:8083/api/v1/rides/{rideId}

# Step 4: Driver starts the ride
curl -X PUT http://localhost:8083/api/v1/rides/{rideId}/start

# Step 5: Driver completes the ride
curl -X PUT http://localhost:8083/api/v1/rides/{rideId}/complete

# Step 6: Driver goes offline
curl -X DELETE http://localhost:8082/api/v1/locations/drivers/driver-001
```

---

## ⚙️ Configuration

### Service Ports

| Service | Port |
|---|---|
| Location Service | 8082 |
| Ride Service | 8083 |
| Matching Service | 8084 |
| Kafka | 9092 |
| Redis | 6379 |
| PostgreSQL | 5432 |

### Kafka Topics

| Topic | Producer | Consumer | Partitions |
|---|---|---|---|
| `ride.requested` | Ride Service | Matching Service | 3 |
| `ride.matched` | Matching Service | Ride Service | 3 |

### Matching Service — Feign client URL

```yaml
# matching-service/application.yaml
location:
  service:
    url: http://localhost:8082
```

---

## 🛠️ Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Spring Boot | 4.0.6 | Microservice framework |
| Java | 26 | Runtime |
| Spring Kafka | (Boot-managed) | Kafka producer/consumer |
| Spring Data Redis | (Boot-managed) | Redis GEO operations (`GEOADD`, `GEORADIUS`, `ZREM`) |
| Spring Data JPA | (Boot-managed) | PostgreSQL ORM (ride persistence) |
| Spring Cloud OpenFeign | 5.0.1 | Declarative HTTP client (Matching → Location) |
| Spring Cloud | 2025.1.1 | BOM for Feign and related modules |
| Apache Kafka | KRaft mode | Async event streaming between services |
| Redis | — | Real-time driver location store (geospatial sorted set) |
| PostgreSQL | — | Persistent ride records |
| Lombok | (Boot-managed) | Boilerplate reduction |
| Docker Compose | — | Kafka container orchestration |

---

## 🔍 Technical Highlights

- **Redis GEO over database** — storing driver locations in a PostgreSQL table and querying with `ST_DWithin` would require either PostGIS or a full table scan. Redis `GEORADIUS` is a native O(log N) operation backed by a sorted set with 52-bit geohash precision — purpose-built for this exact use case.
- **Kafka for decoupling** — the Ride Service publishes `ride.requested` and continues immediately. It has no dependency on the Matching Service being available. If the Matching Service goes down and restarts, it processes the backlog from where it left off (`auto-offset-reset: earliest`). This is the key difference between event-driven and synchronous architectures.
- **Feign for the one synchronous call** — Matching Service calls Location Service synchronously via Feign because it genuinely needs the driver list before it can proceed. This is one of the correct uses of synchronous inter-service calls — when the response is required to continue the current operation.
- **Haversine without a Maps API** — fare estimation happens at request time with no external dependency. The Haversine formula is accurate enough for short urban distances (error < 0.3% under 100 km) and adds zero latency overhead.
- **Weighted scoring, not just proximity** — a pure nearest-driver algorithm is naive. The 70/30 distance/rating split prevents consistently penalising high-quality drivers who are slightly further. In production this extends naturally to add vehicle type, acceptance rate, and surge pricing factors.
- **Stateless location updates** — `GEOADD` on an existing Redis member is an upsert. Drivers can call the update endpoint every 3 seconds without any state management — the service just fires and forgets into the Redis sorted set.
- **KRaft — no ZooKeeper** — Kafka runs in KRaft mode (`KAFKA_PROCESS_ROLES: broker,controller`), eliminating the ZooKeeper dependency entirely. One container instead of two, faster startup, simpler local dev.

---

## 📈 Potential Extensions

- [ ] Add a dedicated **Driver Service** with real rating storage and availability status
- [ ] Implement **surge pricing** by querying demand/supply ratio from Redis at fare calculation time
- [ ] Add **Spring Cloud Gateway** as a single entry point with rate limiting per `riderId`
- [ ] Add **Spring Security + JWT** authentication for rider and driver endpoints
- [ ] Use **Spring Cloud Eureka** for service discovery so Feign resolves `location-service` by name instead of hardcoded URL
- [ ] Add a **dead letter topic** for failed `ride.requested` events that the Matching Service couldn't process
- [ ] Implement `DRIVER_ARRIVING` status (already in the enum) with a WebSocket push to the rider when the driver is within 500 m
- [ ] Add **distributed tracing** (Micrometer + Zipkin) to trace a ride request across all three services

---

## 📄 License

This project is open source and available under the [MIT License](LICENSE).

---

<p align="center">
  Built to demonstrate production-grade event-driven microservice architecture — the same patterns powering real-world ride-sharing platforms at scale.
</p>
