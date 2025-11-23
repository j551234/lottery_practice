# Lottery System API Documentation

## Overview

This document describes the RESTful API endpoints for the Lottery System. The system uses JWT-based authentication and supports both user and admin roles.

**Base URL**: `http://localhost:8080`

**Authentication**: JWT Bearer Token (except login endpoint)

---

## Table of Contents

1. [Authentication](#authentication)
2. [User Endpoints](#user-endpoints)
3. [Admin Endpoints](#admin-endpoints)
4. [Response Format](#response-format)
5. [Error Codes](#error-codes)

---

## Authentication

### Login

Authenticate user and receive JWT token.

**Endpoint**: `POST /auth/login`

**Access**: Public

**Request Body**:
```json
{
  "username": "string",
  "password": "string"
}
```

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Login successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "user_id": 1,
    "username": "user",
    "role": "USER"
  },
  "timestamp": "2025-11-23T10:30:00"
}
```

**Error Response** (401 Unauthorized):
```json
{
  "code": 401,
  "message": "Authentication failed",
  "error": {
    "type": "AUTHENTICATION_ERROR",
    "detail": "Invalid username or password"
  },
  "timestamp": "2025-11-23T10:30:00",
  "path": "/auth/login"
}
```

**Sample Request**:
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "user",
    "password": "123456"
  }'
```

---

## User Endpoints

All user endpoints require authentication with `ROLE_USER` or `ROLE_ADMIN`.

**Authorization Header**: `Authorization: Bearer {token}`

### Test Authentication

Verify user authentication status.

**Endpoint**: `GET /user/test`

**Access**: Authenticated Users

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Authentication verified",
  "data": "user",
  "timestamp": "2025-11-23T10:30:00"
}
```

**Sample Request**:
```bash
curl -X GET http://localhost:8080/user/test \
  -H "Authorization: Bearer {your_token}"
```

---

### Draw Lottery

Participate in a lottery draw.

**Endpoint**: `POST /user/event/{eventId}/draw`

**Access**: Authenticated Users

**Path Parameters**:
- `eventId` (Long) - ID of the lottery event

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Lottery drawn successfully",
  "data": {
    "prize": "small",
    "is_winner": true
  },
  "timestamp": "2025-11-23T10:30:00"
}
```

**Response** (200 OK - No Win):
```json
{
  "code": 200,
  "message": "Lottery drawn successfully",
  "data": {
    "prize": "Miss",
    "is_winner": false
  },
  "timestamp": "2025-11-23T10:30:00"
}
```

**Error Response** (400 Bad Request):
```json
{
  "code": 400,
  "message": "User has insufficient remaining draws",
  "error": {
    "type": "LOTTERY_ERROR",
    "detail": "User has insufficient remaining draws"
  },
  "timestamp": "2025-11-23T10:30:00",
  "path": "/user/event/1/draw"
}
```

**Sample Request**:
```bash
curl -X POST http://localhost:8080/user/event/1/draw \
  -H "Authorization: Bearer {your_token}"
```

---
### Multi Draw Lottery
Participate in a lottery draw multiple times in one request. Each draw will update the user's quota.

**Endpoint**: `POST /user/event/{eventId}/multi-draw`

**Access**: Authenticated Users

**Path Parameters**:
- `eventId` (Long) - ID of the lottery event

**Query Parameters**:
- `times` (Integer, required) - Number of draws. Must be greater than 0.

**Example Request**:
```bash
# Draw 5 times
curl -X POST "http://localhost:8080/user/event/1/multi-draw?times=5" \
  -H "Authorization: Bearer {your_token}"
  
Response (200 OK):  
 ```json 
  {
  "code": 200,
  "message": "Lottery drawn successfully",
  "data": [
    {
      "prize": "FirstPrize",
      "is_winner": true
    },
    {
      "prize": "Miss",
      "is_winner": false
    },
    {
      "prize": "ThirdPrize",
      "is_winner": true
    }
  ],
  "timestamp": "2025-11-23T10:31:00"
}
```
  
---
### Get My Win Records

Retrieve all win records for the authenticated user.

**Endpoint**: `GET /user/my-records`

**Access**: Authenticated Users

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Win records retrieved successfully",
  "data": [
    {
      "id": 1,
      "lottery_event_id": 1,
      "event_name": "test_event_1",
      "uid": 2,
      "draw_prize_id": 1,
      "prize_name": "small",
      "remain_prize_amount": 95,
      "created_time": "2025-11-23T10:30:00"
    },
    {
      "id": 2,
      "lottery_event_id": 1,
      "event_name": "test_event_1",
      "uid": 2,
      "draw_prize_id": 2,
      "prize_name": "medium",
      "remain_prize_amount": 78,
      "created_time": "2025-11-23T10:35:00"
    }
  ],
  "timestamp": "2025-11-23T10:40:00"
}
```

**Sample Request**:
```bash
curl -X GET http://localhost:8080/user/my-records \
  -H "Authorization: Bearer {your_token}"
```

---

## Admin Endpoints

All admin endpoints require authentication with `ROLE_ADMIN`.

**Authorization Header**: `Authorization: Bearer {token}`

### Get All Events

Retrieve a list of all lottery events.

**Endpoint**: `GET /admin/events`

**Access**: Admin Only

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Events retrieved successfully",
  "data": [
    {
      "id": 1,
      "name": "test_event_1",
      "setting_amount": 100,
      "remain_amount": 95,
      "is_active": true,
      "created_time": "2025-11-20T10:00:00",
      "updated_time": "2025-11-23T10:30:00"
    }
  ],
  "timestamp": "2025-11-23T10:40:00"
}
```

**Sample Request**:
```bash
curl -X GET http://localhost:8080/admin/events \
  -H "Authorization: Bearer {admin_token}"
```

---

### Get Lottery Status

Get detailed status of a lottery event including all prizes, rates, and stocks.

**Endpoint**: `GET /admin/event/{eventId}/status`

**Access**: Admin Only

**Path Parameters**:
- `eventId` (Long) - ID of the lottery event

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Lottery status retrieved successfully",
  "data": {
    "event_id": 1,
    "event_name": "test_event_1",
    "is_active": true,
    "remain_amount": 95,
    "total_rate": 0.50,
    "prizes": [
      {
        "prize_id": 1,
        "prize_name": "small",
        "rate": 0.20,
        "stock": 95
      },
      {
        "prize_id": 2,
        "prize_name": "medium",
        "rate": 0.20,
        "stock": 78
      },
      {
        "prize_id": 3,
        "prize_name": "big",
        "rate": 0.10,
        "stock": 70
      }
    ]
  },
  "timestamp": "2025-11-23T10:40:00"
}
```

**Sample Request**:
```bash
curl -X GET http://localhost:8080/admin/event/1/status \
  -H "Authorization: Bearer {admin_token}"
```

---

### Update Lottery Configuration

Batch update lottery event settings including active status, remain amount, and prize rates.

**Endpoint**: `PUT /admin/update`

**Access**: Admin Only

**Request Body**:
```json
{
  "event_id": 1,
  "is_active": true,
  "remain_amount": 100,
  "rate_update_list": [
    {
      "id": 1,
      "rate": 0.25
    },
    {
      "id": 2,
      "rate": 0.15
    }
  ]
}
```

**Field Constraints**:
- `event_id`: Required
- `is_active`: Optional, boolean
- `remain_amount`: Optional, must be >= 0
- `rate_update_list`: Optional, array of rate updates
  - `id`: Prize ID
  - `rate`: Must be between 0.0 and 1.0, max 2 decimal places

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Lottery updated successfully",
  "data": {
    "event_id": 1,
    "updated_count": 2,
    "is_active": true
  },
  "timestamp": "2025-11-23T10:40:00"
}
```

**Error Response** (400 Bad Request):
```json
{
  "code": 400,
  "message": "Validation failed",
  "error": {
    "type": "VALIDATION_ERROR",
    "detail": "Request validation failed",
    "field_errors": {
      "rate": "Rate must be between 0.0 and 1.0"
    }
  },
  "timestamp": "2025-11-23T10:40:00",
  "path": "/admin/update"
}
```

**Sample Request**:
```bash
curl -X PUT http://localhost:8080/admin/update \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": 1,
    "is_active": true,
    "remain_amount": 100,
    "rate_update_list": [
      {
        "id": 1,
        "rate": 0.25
      }
    ]
  }'
```

---

### Update Single Prize Rate

Update the win rate of a specific prize.

**Endpoint**: `PUT /admin/event/{eventId}/prize/{prizeId}/rate`

**Access**: Admin Only

**Path Parameters**:
- `eventId` (Long) - ID of the lottery event
- `prizeId` (Long) - ID of the prize

**Request Body**:
```json
{
  "rate": 0.30
}
```

**Field Constraints**:
- `rate`: Required, must be between 0.0 and 1.0, max 2 decimal places

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Prize rate updated successfully",
  "data": {
    "event_id": 1,
    "prize_id": 1,
    "new_rate": "0.30"
  },
  "timestamp": "2025-11-23T10:40:00"
}
```

**Sample Request**:
```bash
curl -X PUT http://localhost:8080/admin/event/1/prize/1/rate \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "rate": 0.30
  }'
```

---

### Refresh Lottery Cache

Reload all lottery data from database to Redis cache.

**Endpoint**: `POST /admin/event/{eventId}/refresh-cache`

**Access**: Admin Only

**Path Parameters**:
- `eventId` (Long) - ID of the lottery event

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Cache refreshed successfully",
  "data": {
    "event_id": 1,
    "cache_refreshed": true
  },
  "timestamp": "2025-11-23T10:40:00"
}
```

**Sample Request**:
```bash
curl -X POST http://localhost:8080/admin/event/1/refresh-cache \
  -H "Authorization: Bearer {admin_token}"
```

---

### Clear Lottery Cache

Clear all Redis cache for a lottery event.

**Endpoint**: `DELETE /admin/event/{eventId}/clear-cache`

**Access**: Admin Only

**Path Parameters**:
- `eventId` (Long) - ID of the lottery event

**Response** (200 OK):
```json
{
  "code": 200,
  "message": "Cache cleared successfully",
  "data": {
    "event_id": 1,
    "cache_cleared": true
  },
  "timestamp": "2025-11-23T10:40:00"
}
```

**Sample Request**:
```bash
curl -X DELETE http://localhost:8080/admin/event/1/clear-cache \
  -H "Authorization: Bearer {admin_token}"
```

---

## Response Format

All API responses follow a standard format:

### Success Response

```json
{
  "code": 200,
  "message": "Success message",
  "data": {
    // Response data object
  },
  "timestamp": "2025-11-23T10:40:00"
}
```

### Error Response

```json
{
  "code": 400,
  "message": "Error message",
  "error": {
    "type": "ERROR_TYPE",
    "detail": "Detailed error description",
    "field_errors": {
      "field_name": "Field error message"
    }
  },
  "timestamp": "2025-11-23T10:40:00",
  "path": "/api/endpoint"
}
```

---

## Error Codes

| HTTP Code | Error Type | Description |
|-----------|------------|-------------|
| 400 | VALIDATION_ERROR | Request validation failed |
| 400 | LOTTERY_ERROR | Lottery operation error (insufficient quota, inactive event, etc.) |
| 401 | AUTHENTICATION_ERROR | Authentication failed or token invalid |
| 403 | FORBIDDEN | Insufficient permissions |
| 404 | NOT_FOUND | Resource not found |
| 500 | INTERNAL_ERROR | Internal server error |

---

## Common Error Scenarios

### Lottery Errors

1. **Insufficient User Quota**
```json
{
  "code": 400,
  "message": "User has insufficient remaining draws",
  "error": {
    "type": "LOTTERY_ERROR",
    "detail": "User has insufficient remaining draws"
  }
}
```

2. **Event Ended**
```json
{
  "code": 400,
  "message": "Lottery event ended, insufficient remaining draws",
  "error": {
    "type": "LOTTERY_ERROR",
    "detail": "Lottery event ended, insufficient remaining draws"
  }
}
```

3. **Inactive Event**
```json
{
  "code": 400,
  "message": "Lottery event is not active",
  "error": {
    "type": "LOTTERY_ERROR",
    "detail": "Lottery event is not active"
  }
}
```

4. **Invalid Rate**
```json
{
  "code": 400,
  "message": "Total prize rate exceeds 1.0: 1.05",
  "error": {
    "type": "LOTTERY_ERROR",
    "detail": "Total prize rate exceeds 1.0: 1.05"
  }
}
```

### Authentication Errors

1. **Invalid Credentials**
```json
{
  "code": 401,
  "message": "Authentication failed",
  "error": {
    "type": "AUTHENTICATION_ERROR",
    "detail": "Invalid username or password"
  }
}
```

2. **Invalid Token**
```json
{
  "code": 401,
  "message": "JWT validation failed: token expired",
  "error": {
    "type": "Unauthorized",
    "detail": "JWT validation failed: token expired"
  }
}
```

---

## Testing Credentials

### Default Users

**Admin User**:
- Username: `admin`
- Password: `123456`
- Role: `ADMIN`

**Regular User**:
- Username: `user`
- Password: `123456`
- Role: `USER`

---

## Rate Limiting & Concurrency

- The system uses Redisson distributed locks for lottery draws
- Lock timeout: 5 seconds wait time, 10 seconds hold time
- Concurrent draws are queued and processed sequentially
- "System busy" error returned if lock cannot be acquired

---

## Data Synchronization

- User quota is synced asynchronously after each draw
- Win records are saved asynchronously to avoid blocking
- Emergency sync triggered on errors
- Admin can manually refresh cache from database

---

## Notes

1. All timestamps are in ISO 8601 format
2. All monetary amounts and rates use `BigDecimal` for precision
3. Prize rates must sum to <= 1.0
4. JWT tokens expire after 1 hour (configurable)
5. All API responses use snake_case naming convention
6. Redis cache is used for high-performance lottery operations