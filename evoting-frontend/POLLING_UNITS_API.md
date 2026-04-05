# POST /api/admin/polling-units — Authentication Guide

This endpoint requires a valid JWT token. It returns 401 if the token is
missing and 403 if the token is present but the role is insufficient.

## Required role
`ADMIN` or `SUPER_ADMIN`

## Step 1 — Get a token
```
POST https://localhost:8443/api/auth/login
Content-Type: application/json

{
  "username": "superadmin",
  "password": "your_password"
}
```
Response: `{ "token": "eyJ...", "role": "SUPER_ADMIN", "username": "superadmin", "email": "" }`

## Step 2 — Get a valid lgaId
LGA IDs come from your database. Query one with:
```
GET https://localhost:8443/api/locations/states/1/lgas
```
(No token needed — public endpoint.)

## Step 3 — Create the polling unit
```
POST https://localhost:8443/api/admin/polling-units
Content-Type: application/json
Authorization: Bearer eyJ...

{
  "name":     "Polling Unit 001 Kaduna North",
  "code":     "KD/KN/001",
  "lgaId":    12,
  "capacity": 500
}
```

## Using Postman
1. Send the login request first
2. Copy the `token` value from the response body
3. In the polling-units request, go to the Authorization tab
4. Select "Bearer Token" and paste the token
5. Send the request
