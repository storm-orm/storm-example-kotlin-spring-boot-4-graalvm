<!-- STORM:START -->
## Storm ORM

This project uses the [Storm ORM framework](https://orm.st) for database access.
Storm is a modern SQL Template and ORM for Kotlin 2.0+ and Java 21+, built around
immutable data classes and records instead of proxied entities.

### Storm Annotations and API

Storm can run on top of JPA, but when generating code, always use Storm's own annotations and JDBC-based API:
- Use `@PK`, not `@Id` or `@GeneratedValue`
- Use `@FK`, not `@ManyToOne` or `@JoinColumn`
- Use `@DbTable`, not `@Table` or `@Entity`
- Use `@DbColumn`, not `@Column`
- Use `@UK`, not `@UniqueConstraint`
- Use `@Version` from `st.orm`, not from `jakarta.persistence`
- Use `DataSource.orm` or `ORMTemplate.of(dataSource)`, not `EntityManager`
- Do not add `jakarta.persistence-api`, Hibernate, or any JPA implementation unless the project already uses them

Storm works directly with JDBC `DataSource`. There is no persistence context, no session, no lazy proxy objects.

### Framework Detection

Before suggesting dependencies, patterns, or configuration, detect which framework the project uses by examining the build file and existing dependencies:

- **Spring Boot**: build file contains `storm-kotlin-spring-boot-starter`, `storm-spring-boot-starter`, `spring-boot-starter`, or `@SpringBootApplication` in the codebase.
- **Ktor**: build file contains `storm-ktor`, `ktor-server-core`, or `io.ktor` dependencies.
- **Standalone**: neither Spring Boot nor Ktor detected. The project uses Storm directly with `ORMTemplate.of(dataSource)`.

Adapt your suggestions to the detected framework:
- **Spring Boot**: use Storm's programmatic transactions — suspend `transaction { }` at the service level (never in controllers), bridged with `runBlocking` only at non-suspend entry points such as MVC handlers (declarative `@Transactional` also works), constructor injection, `application.yml` for config.
- **Ktor**: use `install(Storm)` plugin, `transaction { }` blocks, `application.conf` (HOCON) for config, `call.orm` for route access.
- **Standalone**: use `DataSource.orm` or `ORMTemplate.of(dataSource)`, programmatic `transaction { }` blocks.

### Query and Template Rules

- **Always prefer QueryBuilder and metamodel-based methods** for joins, where clauses, ordering, etc. Only fall back to SQL template lambdas when QueryBuilder cannot express the query.
- **Joins**: use `.innerJoin(Entity::class).on(OtherEntity::class)` unless it cannot be expressed with entity classes.
- **Template lambdas**: when you must use a template expression, write it as a lambda (`{ "..." }`) — never use `TemplateString.raw()`.
- **Compiler plugin interpolation**: with the Storm compiler plugin (which Kotlin projects should always use), standard `${}` interpolation inside template lambdas is automatically processed. Do not call `t()` manually — it exists only as a fallback for projects without the compiler plugin.
- **Metamodel in templates**: even inside template lambdas, use metamodel references (`${User_.email}`) instead of hardcoded column names wherever possible.

If the project does not yet have Storm dependencies in its build file (pom.xml,
build.gradle.kts), use /storm-setup to help the user configure their project.
Detect the project's Kotlin or Java version from the build file to recommend the
correct dependencies and compiler plugin version.

Available Storm skills:
- /storm-setup - Help configure Maven/Gradle dependencies
- /storm-docs - Load full Storm documentation
- /storm-entity-kotlin or /storm-entity-java - Create entities
- /storm-repository-kotlin or /storm-repository-java - Write repositories
- /storm-query-kotlin or /storm-query-java - Write queries with the QueryBuilder
- /storm-sql-kotlin or /storm-sql-java - Write SQL Templates
- /storm-json-kotlin or /storm-json-java - JSON columns and JSON aggregation
- /storm-serialization-kotlin or /storm-serialization-java - Entity serialization for REST APIs
- /storm-migration - Write Flyway/Liquibase migration SQL

When the user asks about Storm topics, suggest the relevant skill if they need detailed guidance.

## Database Schema Access

This project has a Storm Schema MCP server configured. Use the following tools to access the live database schema:

- `list_tables` - List all tables in the database
- `describe_table(table)` - Describe a table's columns, types, nullability, primary key, foreign keys (with cascade rules), and unique constraints
- `select_data` - Query individual records from a table (only available when data access is enabled for this connection)


### select_data parameters

All parameters except `table` are optional. Pass arrays and objects as native JSON types — never as stringified JSON.

| Parameter | Type | Description |
|-----------|------|-------------|
| `table` | `string` | **Required.** Table name. |
| `columns` | `string[]` | Columns to return. Omit for all columns. Example: `["id", "name"]` |
| `where` | `object[]` | Filter conditions (AND). Each object: `{ "column": "name", "operator": "=", "value": "x" }`. Operators: `=`, `!=`, `<`, `>`, `<=`, `>=`, `LIKE`, `IN`, `IS NULL`, `IS NOT NULL`. |
| `orderBy` | `object[]` | Sort order. Each object: `{ "column": "name", "direction": "DESC" }`. Direction: `ASC` (default) or `DESC`. **Not** `sort`. |
| `offset` | `integer` | Rows to skip (default: 0). |
| `limit` | `integer` | Max rows (default: 50, max: 500). |

Example call:
```json
{
  "table": "USER",
  "columns": ["id", "name", "timestamp"],
  "orderBy": [{"column": "timestamp", "direction": "DESC"}],
  "limit": 10
}
```

Use these tools when:
- Asked about the database schema or data model
- Generating or updating Storm entity classes
- Validating that existing entities match the actual database schema
- Investigating foreign key relationships between tables

### Schema vs. data access

The `list_tables` and `describe_table` tools return structural metadata only — no data is exposed.

The `select_data` tool is only available when the developer has explicitly enabled data access for this connection. If the tool is not listed in `tools/list`, data access is disabled — do not attempt to call it. When available, `select_data` accepts a structured request (table, columns, where, orderBy, offset, limit) and returns individual rows formatted as a markdown table. It does not accept raw SQL. Results default to 50 rows (max 500), and cell values longer than 200 characters are truncated.

Use `select_data` when sample data would inform a decision — for example, to determine whether a `VARCHAR` column contains enum-like values, whether a `TEXT` column stores JSON, or what value ranges a numeric column holds. Do not query data speculatively or in bulk; use it when a specific question about the data would change the entity design.

When presenting `select_data` results to the user, always show the actual data rows as a table — column names as headers, one row per record. Do not summarize, describe, or narrate the data in prose. The user asked to see the data, so show it. The response already contains a markdown table — present it directly. Never transpose the data (columns as rows), and never replace the table with a written description of what the data contains.

Some tables may be excluded from data queries by the developer. If `select_data` returns an error about an excluded table, the table's schema is still available through `describe_table` — only data access is restricted.


<!-- STORM:END -->
