# water-server

Maven-based Spring Boot backend for the personal asset management system.

## Current scope

- Snapshot query and CRUD APIs under `/api/snapshots`
- Account query and CRUD APIs under `/api/accounts`
- CSV import preview at `/api/import/preview`
- Domain model derived from `water.csv`

## Run

Use Java 17, then run from `water-server`. Windows PowerShell can set it for the current terminal:

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
```

```bash
mvn spring-boot:run
```

By default the app looks for `water.db` in the current working directory and then its parent directory. Override it when needed:

```powershell
$env:WATER_DB_PATH="..\water.db"
mvn spring-boot:run
```

Then preview the parsed CSV data:

```bash
curl "http://localhost:8080/api/import/preview"
```

## Test

```bash
mvn test
```
