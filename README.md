# Storm Movies · Kotlin + Spring Boot 4 · GraalVM native image

The [Kotlin + Spring Boot 4 example](https://github.com/storm-orm/storm-example-kotlin-spring-boot-4)
compiled to a GraalVM native image: the same IMDB movie browser with search,
genres, people, statistics, and a watchlist, but the binary starts in about a
quarter of a second, connects to PostgreSQL, validates the schema against the
entity model, and serves pages. No JVM, no warmup.

The application code is identical to the non-native example. What this project
adds is the reachability metadata a Storm application needs under GraalVM's
closed-world analysis, all of it in two small classes described below.

## Why Storm fits a native image

GraalVM is hostile to classic ORMs: runtime bytecode generation, lazy-loading
entity proxies, and reflective entity construction all need extensive metadata,
or simply do not work. Storm avoids those mechanisms by design:

- **Entities are immutable data classes.** No runtime-generated entity proxies,
  no persistence context weaving.
- **Rows are constructed without reflection.** The metamodel processor (KSP)
  generates an `Instantiator` per data class at compile time, discovered via
  `ServiceLoader`. In the native image these are ordinary compiled classes.
- **Queries are built against the compile-time metamodel** (`Movie_`,
  `Principal_.person`, ...), so query construction needs no introspection
  either.

Since 1.13, Storm ships everything it needs itself: storm-core and
storm-foundation carry GraalVM reachability metadata for their internal
proxies and resources, and storm-spring contributes Spring AOT hints that
register every entity, repository, and repository proxy automatically, driven
by the compile-time type index. Repository scanning registers AOT-compatible
FactoryBean definitions, so scanned repositories work in a native image
without any configuration.

What is left is declared in
[`ApplicationRuntimeHints`](src/main/kotlin/st/orm/demo/imdb/ApplicationRuntimeHints.kt),
and all of it is application-level, not Storm-level:

| Hint | Why |
|---|---|
| Template-facing types | Thymeleaf evaluates expressions through SpEL, which reflectively invokes methods on whatever templates touch: `#numbers`-style utility objects and JDK value and collection types (even `String.isEmpty()`). |
| Jackson 3 `JsonMapper` | Spring resolves it reflectively when building the HTTP message converters. |
| View models | Query result shapes and API models, read reflectively by Thymeleaf/SpEL and the serialization infrastructure. |

## Stack

- Kotlin 2.2 / Java 21, Spring Boot 4.1 (WebMVC + Thymeleaf, virtual threads enabled)
- Storm ORM (`storm-kotlin-spring-boot-starter`) with the KSP metamodel
  generator and the Storm compiler plugin
- **Oracle GraalVM for JDK 25** (any GraalVM for JDK 23+ works; Spring Boot 4
  writes its AOT metadata in the consolidated `reachability-metadata.json`
  format, which older GraalVM releases do not read)
- PostgreSQL 17 (Docker Compose) with Flyway migrations
- kotlinx.serialization for JSON APIs and cache values; Jackson for parsing
  external APIs
- JUnit 5 + `storm-test` on H2 for repository tests, Playwright for interface tests

## Running the application

Prerequisites: JDK 21, Docker, and a GraalVM for JDK 23+ for the native build.

```bash
# 1. Start PostgreSQL. This is the same Compose stack as the non-native
#    example, so a database imported by either project is shared by both.
docker compose up -d

# 2. Compile the native image (2-3 minutes)
GRAALVM_HOME=/path/to/graalvm ./gradlew nativeCompile

# 3. Run the binary. On an empty database the first start streams the IMDB
#    dataset in natively (the ~1.2 GB of dataset files are downloaded once
#    and cached in ./data); afterwards the import is skipped.
./build/native/nativeCompile/storm-imdb-graalvm

# 4. Open the app
open http://localhost:8080
```

The application also still runs on the JVM with `./gradlew bootRun`.

Measured on an Apple M-series MacBook, same application and database:

| | Startup | Dataset import | Artifact |
|---|---|---|---|
| Native image | ~0.25 s | 87 s | 122 MB self-contained binary |
| JVM (`java -jar`) | ~2 s | 117 s | jar + JVM |

The native startup includes Flyway, the Hikari pool, and Storm validating all
entities against the live database schema. The import (1.5 million rows
through Storm's suspending batch inserts) is faster natively: a one-shot
batch job never gets the JIT warmup it would need on the JVM.

To start over with an empty database:

```bash
docker compose down -v
```

Movie posters, person photos, and plot summaries are fetched at runtime from
the IMDB suggestion API and the Wikipedia REST API, so the app looks best with
internet access.

## Project layout

```
src/main/kotlin/st/orm/demo/imdb/
├── ApplicationRuntimeHints.kt  The application's native-image hints (see above)
├── model/          Storm entities (@PK, @FK) and projections
├── repository/     EntityRepository interfaces with QueryBuilder queries
├── service/        Business logic in suspend `transaction { }` blocks,
│                   plus the streaming IMDB importer
├── web/            MVC controllers (pages) and REST controllers (/api/**)
└── serialization/  kotlinx.serialization support: custom serializers and
                    the JSON-serialized Spring cache
src/main/resources/
├── db/migration/   Flyway schema (V1__create_schema.sql)
├── templates/      Thymeleaf views
└── static/         CSS, JS, images
```

## What to look at

Each part of the app demonstrates a Storm feature:

- **Entities** (`model/`): immutable data classes with `@PK`, `@FK`, `@UK`,
  and composite keys (`MovieGenre`, `Principal`). `MovieView` is a
  database-view-backed projection; `MovieSummary` / `PersonSummary` select a
  subset of columns.
- **Repositories** (`repository/`): `EntityRepository` interfaces with default
  methods using the type-safe QueryBuilder and generated metamodel
  (`Movie_.startYear`, `Principal_.person`). Aggregations return plain data
  classes; computed expressions use SQL template lambdas with metamodel
  references.
- **Transactions** (`service/`): Storm's coroutine-native suspend
  `transaction { }` blocks at the service level, bridged with `runBlocking`
  only at MVC entry points. The Spring transaction integration is deliberately
  excluded in `application.yaml` because suspend mode manages transactions on
  the `DataSource` directly.
- **Streaming import** (`service/ImdbDataImporter.kt`): Flow-based pipeline
  that parses TSV rows into entities and hands them to Storm's suspending
  batch insert, one pass per file, without materializing entity lists. Runs
  in the native image too.
- **Schema validation**: on by default. The starter verifies every entity
  against the live database schema at startup;
  `EntitySchemaValidationTest` does the same in the test suite.
- **Serialization** (`serialization/`, `web/ApiModels.kt`): Storm entities
  serialized with kotlinx.serialization for the REST endpoints, and a Spring
  cache that stores values as serialized JSON to prove entities survive the
  round-trip (`CacheConfiguration.kt`).

## Testing

```bash
./gradlew test
```

Repository tests run on an in-memory H2 database via `@StormTest`, so no
Docker is required. Tests receive an `ORMTemplate` and a `SqlCapture` as parameters, so
they can assert on the SQL Storm generates.

The Playwright interface tests run against a live application, which can be
the native binary:

```bash
./gradlew installPlaywrightBrowsers                # once
./build/native/nativeCompile/storm-imdb-graalvm     # in one terminal
./gradlew e2eTest                                  # in another
```

## Configuration

Everything lives in `src/main/resources/application.yaml`. The defaults match
the Compose file (database `imdb`, user/password `storm` on `localhost:5432`).
Import behavior is tunable under `imdb.import` (cache directory, minimum vote
count, dataset base URL).
