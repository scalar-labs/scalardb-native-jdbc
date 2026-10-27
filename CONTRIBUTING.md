# Contributing

## Building

Requires JDK 25 to build; the driver artifact is compiled for Java 11 with `--release`. To build the
driver, `snsql` and the tools for use rather than for development, see
[docs/development-builds.md](docs/development-builds.md).

```bash
./gradlew check          # unit and integration tests, javadoc, Spotless, SpotBugs with FindSecBugs, Error Prone, NullAway
./gradlew spotlessApply  # format to the Google Java Style Guide
```

The integration tests run database containers through Testcontainers and need Docker. `check` runs
them in one task per engine configuration (`integrationTestPostgresql`, `integrationTestOracle`,
`integrationTestMysql`, `integrationTestMysqlLowerCase` for MySQL with `lower_case_table_names=1`,
`integrationTestMariadb`, `integrationTestSqlserver`), three at a time, each with its own container;
`-Pnativejdbc.parallelEngines=2` lowers that where Docker has less memory.
`-Dnativejdbc.engines=postgresql,mysql` selects a subset (`none` for no integration tests at all),
and `./gradlew :driver:integrationTest --tests '*ReadIntegrationTest*' -Dnativejdbc.engines=oracle`
runs some tests on the selected engines in one task. `-Dnativejdbc.postgresql.url=...`, with
`.username` and `.password`, runs an engine against an existing database instead of a container (the
requirements are in `testing.Engines`). Oracle and SQL Server take a minute or more to start, and SQL
Server runs under emulation on Apple Silicon.

| Path | What |
| --- | --- |
| `driver/` | the driver, published as `com.scalar-labs:scalardb-native-jdbc` |
| `shell/` | `snsql`, the psql-like interactive shell, distributed as `scalardb-native-shell-<version>.zip` (`docs/plans/shell.md`) |
| `tools/` | `scalardb-native-tools`, the setup, its check and its teardown, distributed as `scalardb-native-tools-<version>.zip` |
| `samples/` | `crud-jdbc` and `crud-spring-data-jdbc`: small applications through the driver, each run as an acceptance test on every engine and both layouts beside ScalarDB's own transactions; `getting-started`, the files of `docs/getting-started.md`, a project of its own outside this build |
| `docs/design.md` | the design rules |
| `build-logic/`, `gradle/libs.versions.toml` | shared Gradle conventions and the dependency versions |

The build follows the conventions of ScalarDB Saga and the ScalarDB PostgreSQL frontend, and the code
follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
