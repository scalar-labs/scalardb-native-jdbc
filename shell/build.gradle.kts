plugins {
    id("scalardb-native-jdbc.java-conventions")
    id("scalardb-native-jdbc.java11-conventions")
    application
}

description = "snsql: an interactive SQL shell over ScalarDB Native JDBC, in the manner of psql."

// The plan (docs/plans/shell.md) names the distribution after the product, not the module.
base.archivesName = "scalardb-native-shell"

application {
    mainClass = "com.scalar.db.nativejdbc.shell.Main"
    applicationName = "snsql"
    // JLine loads its native terminal library, which JDK 24 and later warn about on every start
    // unless native access is enabled; JDK 11 to 16 do not know the option, and
    // IgnoreUnrecognizedVMOptions makes them skip it rather than refuse to start. JLine's exec
    // terminal reaches into java.lang by reflection, which JDK 11 warns about without the opens.
    applicationDefaultJvmArgs =
        listOf(
            "-XX:+IgnoreUnrecognizedVMOptions",
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
        )
}

// The vendor drivers whose licences allow bundling (docs/plans/shell.md, decision 3), at the versions
// the driver's integration tests run. Not dependencies of the jar: they ship in the zip only.
// MySQL goes through MariaDB Connector/J; MySQL Connector/J is GPL and is not bundled.
val bundledDrivers = configurations.create("bundledDrivers")

dependencies {
    implementation(project(":driver"))
    implementation(libs.jline.reader)
    implementation(libs.jline.terminal)
    implementation(libs.picocli)
    // JLine's native terminal; without it JLine runs stty instead, and on Windows falls back to a
    // dumb terminal
    runtimeOnly(libs.jline.terminal.jni)
    // the driver logs through SLF4J; without a binding, slf4j-api warns on every start
    runtimeOnly(libs.slf4j.nop)

    bundledDrivers(libs.postgresql)
    bundledDrivers(libs.mariadb.java.client)
    bundledDrivers(libs.mssql.jdbc)
    bundledDrivers(libs.ojdbc11)

    testCompileOnly(libs.spotbugs.annotations)

    // the driver's engine containers, and ScalarDB to create the ScalarDB tables \dt marks
    "integrationTestImplementation"(testFixtures(project(":driver")))
    "integrationTestCompileOnly"(libs.spotbugs.annotations)
    // Testcontainers' MySQL module loads Connector/J to wait for the server; the test deregisters
    // it before snsql connects, so that MySQL goes through MariaDB Connector/J as in the zip
    "integrationTestRuntimeOnly"(libs.mysql.connector.j)
}

// the tests run the drivers the zip bundles
configurations["integrationTestRuntimeOnly"].extendsFrom(bundledDrivers)

distributions.main {
    distributionBaseName = "scalardb-native-shell"
    contents {
        into("lib") { from(bundledDrivers) }
    }
}

// --version prints it, with the commit for a development build (java-conventions)
tasks.jar {
    val commit = project.extra["buildCommit"] as String
    manifest {
        attributes(
            "Implementation-Version" to
                if (commit.isEmpty()) project.version else "${project.version} (commit $commit)"
        )
    }
}

// lib/* rather than a list of jars, so a user can drop a driver of their own, such as MySQL
// Connector/J, into lib/. The java launcher expands the wildcard.
tasks.startScripts {
    classpath = files("*")
}

tasks.named<JavaExec>("run") {
    classpath += bundledDrivers
    standardInput = System.`in`
}
