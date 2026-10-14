# Ledger Service

A double-entry ledger with multi-currency accounts, idempotent postings and month-end reconciliation.
The full specification is [docs/DESIGN.md](docs/DESIGN.md).

## Stack

**Spring Boot 4.1.1** (the 4.x line). It was the latest GA release when the project was scaffolded, and
the GA releases of springdoc-openapi (3.1.1, built against Boot 4.1) and Testcontainers (2.0.5, managed by
Boot 4.1.1) both work with it, so the 3.5.x fallback in DESIGN.md §2 was not needed.

Java 21, PostgreSQL 17, Flyway, `JdbcClient`, springdoc-openapi, JUnit 5 + Testcontainers.

## Running

Prerequisites:

- JDK 21 (not just a JRE)
- Docker

```sh
docker compose up -d          # PostgreSQL 17 on localhost:5432
./mvnw spring-boot:run        # API on http://localhost:8080, Swagger UI at /swagger-ui.html
./mvnw verify                 # all tests; integration tests start their own PostgreSQL container
```
