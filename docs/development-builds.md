# Development builds

Until the driver is on Maven Central and `snsql` and the tools are on the GitHub release, try them
by building this repository yourself. This page is for team members testing before a release.

## What you need

- Access to this repository, and `git`.
- JDK 25 installed, to build. Gradle finds it among the installed JDKs; nothing needs to point at it.
  `java` on your `PATH`, or `JAVA_HOME`, may be any JDK 17 or later, which runs Gradle itself.
- What you build runs on Java 11 or later: the driver, `snsql` and the tools.
- Docker, for the database in [Getting started](getting-started.md).

## Build

```sh
git clone https://github.com/scalar-labs/scalardb-native-jdbc.git
cd scalardb-native-jdbc
./gradlew :driver:publishToMavenLocal :shell:installDist :tools:installDist
```

This builds without running the tests, in a minute or two the first time. Javadoc warnings and
Gradle's notice about deprecated features are expected. It gives:

| What | Where |
| --- | --- |
| The driver, `com.scalar-labs:scalardb-native-jdbc`, with its POM | your local Maven repository, `~/.m2/repository` |
| `snsql` | `shell/build/install/scalardb-native-shell/bin/snsql` |
| `scalardb-native-tools` | `tools/build/install/scalardb-native-tools/bin/scalardb-native-tools` |

The version is the one in `gradle.properties`, such as `3.20.0-SNAPSHOT`.

`~/.m2` and Gradle's cache, `~/.gradle`, are shared by every checkout on your machine: building
another checkout of this repository replaces the driver in `~/.m2` with that checkout's.

Put the two `bin` directories on your `PATH`. From the repository's root:

```sh
export PATH="$(pwd)/shell/build/install/scalardb-native-shell/bin:$(pwd)/tools/build/install/scalardb-native-tools/bin:$PATH"
```

This lasts for the terminal it runs in. In a new terminal, run it again from the root, or add the two
directories to your shell's profile with their full paths.

`snsql` and the tools bundle the JDBC drivers of PostgreSQL, MariaDB (also used for MySQL), SQL
Server and Oracle, as the release zips will.

## Follow Getting started with a development build

Follow [Getting started](getting-started.md), with two differences:

- **`snsql` and the tools** are the ones you built: skip the GitHub release.
- **The Schema Loader.** The driver will be certified against ScalarDB 3.20, which is not released
  yet; until it is, the driver's tests run on 3.19. Take
  [`scalardb-schema-loader-3.19.1.jar`](https://github.com/scalar-labs/scalardb/releases/download/v3.19.1/scalardb-schema-loader-3.19.1.jar)
  and use `3.19.1` for `<SCALARDB_VERSION>`.

The example program needs no change: it uses the repository's version, and reads a `-SNAPSHOT`
version from your local Maven repository. Build first, so that the driver is there.

## Use the driver in your own application

Depend on the version in `gradle.properties`. The POM brings the driver's own dependencies,
JSQLParser and SLF4J's API; add your database's JDBC driver as usual.

With Gradle, add `mavenLocal()` to the repositories:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("com.scalar-labs:scalardb-native-jdbc:3.20.0-SNAPSHOT")
}
```

With Maven, the local repository is always read, so the dependency alone is enough:

```xml
<dependency>
  <groupId>com.scalar-labs</groupId>
  <artifactId>scalardb-native-jdbc</artifactId>
  <version>3.20.0-SNAPSHOT</version>
</dependency>
```

## Update

From the repository's root:

```sh
git pull
./gradlew :driver:publishToMavenLocal :shell:installDist :tools:installDist
```

The version stays the same from build to build, so a rebuild replaces the driver in place. Gradle
picks the new one up on the next build; an application that is running keeps the old one until it
restarts.

## Reporting a problem

Every build carries the same version, so give the commit you built. Include:

- the output of `snsql --version`, which names the commit, such as
  `snsql 3.20.0-SNAPSHOT (commit 1a2b3c4)`. A commit ending in `-dirty` was built with
  uncommitted changes. The driver's jar names it too, as `Build-Commit` in its manifest;
- the database and its version, and the layout, inline or decoupled;
- the statement, and the full error with its SQLState ([Errors](errors.md)).
