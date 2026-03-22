# water-server

Maven-based Spring Boot backend for the personal asset management system.

## Current scope

- Spring Boot project skeleton
- Initial asset snapshot API placeholder at `/api/snapshots`
- CSV import preview at `/api/import/preview`
- Domain model derived from `water.csv`

## Run

After Maven is installed and available on `PATH`:

```bash
mvn spring-boot:run
```

Then preview the parsed CSV data:

```bash
curl "http://localhost:8080/api/import/preview"
```

## Next steps

1. Add persistence with Spring Data JPA + SQLite.
2. Save imported snapshot records into normalized tables.
3. Replace the placeholder snapshot controller with database-backed queries.
