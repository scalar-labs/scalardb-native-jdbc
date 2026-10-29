# Unreleased versions

This page explains how to use a version of the driver that is not released yet: a snapshot from the
Maven Central snapshot repository, or a build of this repository. The released versions come from
Maven Central and the [GitHub releases](https://github.com/scalar-labs/scalardb-native-jdbc/releases)
([Getting it](../README.md#getting-it)).

Every commit on `main` and on each version branch publishes the driver as a snapshot: `main` the
next line's, such as `4.0.0-SNAPSHOT`, and a version branch its next patch's, such as
`3.20.1-SNAPSHOT` on `3.20`. A snapshot version stays the same from commit to commit, and each
commit replaces the build behind it. `snsql` and the tools are not published as snapshots: build
them ([Build this repository](#build-this-repository)).

## Use a snapshot of the driver

Add the Maven Central snapshot repository, and depend on the snapshot version. The POM brings the
driver's own dependencies, JSQLParser and SLF4J's API; add your database's JDBC driver as usual.

With Gradle:

```kotlin
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation("com.scalar-labs:scalardb-native-jdbc:3.20.1-SNAPSHOT")
}
```

With Maven:

```xml
<repositories>
  <repository>
    <id>central-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>

<dependency>
  <groupId>com.scalar-labs</groupId>
  <artifactId>scalardb-native-jdbc</artifactId>
  <version>3.20.1-SNAPSHOT</version>
</dependency>
```

Gradle checks for a newer snapshot once a day by default; `--refresh-dependencies` checks now. Maven
does the same, and `-U` checks now.

## Build this repository

To use `snsql` or the tools of an unreleased version, or the driver with changes of your own, build
them.

### What you need

- `git`.
- JDK 25 installed, to build. Gradle finds it among the installed JDKs; nothing needs to point at it.
  `java` on your `PATH`, or `JAVA_HOME`, may be any JDK 17 or later, which runs Gradle itself.
- What you build runs on Java 11 or later: the driver, `snsql` and the tools.

### Build

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

The version is the one in `gradle.properties`, such as `4.0.0-SNAPSHOT` on `main`.

`~/.m2` and Gradle's cache, `~/.gradle`, are shared by every checkout on your machine: building
another checkout of this repository replaces the driver in `~/.m2` with that checkout's.

Put the two `bin` directories on your `PATH`. From the repository's root:

```sh
export PATH="$(pwd)/shell/build/install/scalardb-native-shell/bin:$(pwd)/tools/build/install/scalardb-native-tools/bin:$PATH"
```

This lasts for the terminal it runs in. In a new terminal, run it again from the root, or add the two
directories to your shell's profile with their full paths.

`snsql` and the tools bundle the JDBC drivers of PostgreSQL, MariaDB (also used for MySQL), SQL
Server and Oracle, as the release zips do.

To use the driver you built in your own application, depend on the version in `gradle.properties`.
Gradle needs `mavenLocal()` in its repositories, before `mavenCentral()`; Maven always reads the
local repository.

To rebuild after `git pull`, run the same `./gradlew` command. The version stays the same from build
to build, so a rebuild replaces the driver in place.

## Follow Getting started with an unreleased version

Follow [Getting started](getting-started.md), with two differences:

- **`snsql` and the tools** are the ones you built: skip the GitHub release.
- **The example program** takes the released driver unless told otherwise. Give it the snapshot
  version, which it looks for in your local Maven repository and then in the snapshot repository:

  ```sh
  ./gradlew run -PdriverVersion=4.0.0-SNAPSHOT
  ```

## Reporting a problem

A snapshot version names no single build, so give the commit. Include:

- the output of `snsql --version`, which names the commit, such as
  `snsql 4.0.0-SNAPSHOT (commit 1a2b3c4)`. A commit ending in `-dirty` was built with
  uncommitted changes. The driver's jar names it too, as `Build-Commit` in its manifest;
- the database and its version, and the layout, inline or decoupled;
- the statement, and the full error with its SQLState ([Errors](errors.md)).
