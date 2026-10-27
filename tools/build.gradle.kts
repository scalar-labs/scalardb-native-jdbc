plugins {
    id("scalardb-native-jdbc.java-conventions")
    id("scalardb-native-jdbc.java11-conventions")
    application
}

description = "The operator's command line of ScalarDB Native JDBC: setup, check and teardown."

application {
    mainClass = "com.scalar.db.nativejdbc.tools.Main"
    applicationName = "scalardb-native-tools"
}

// The vendor drivers whose licences allow bundling, as the shell bundles them (docs/plans/shell.md,
// decision 3): MySQL goes through MariaDB Connector/J.
val bundledDrivers = configurations.create("bundledDrivers")

dependencies {
    implementation(project(":driver"))
    implementation(libs.picocli)
    // the driver logs through SLF4J; without a binding, slf4j-api warns on every start
    runtimeOnly(libs.slf4j.nop)

    bundledDrivers(libs.postgresql)
    bundledDrivers(libs.mariadb.java.client)
    bundledDrivers(libs.mssql.jdbc)
    bundledDrivers(libs.ojdbc11)

    "integrationTestImplementation"(testFixtures(project(":driver")))
    "integrationTestCompileOnly"(libs.spotbugs.annotations)
    // Testcontainers' MySQL module loads Connector/J to wait for the server
    "integrationTestRuntimeOnly"(libs.mysql.connector.j)
}

configurations["integrationTestRuntimeOnly"].extendsFrom(bundledDrivers)

distributions.main {
    distributionBaseName = "scalardb-native-tools"
    contents {
        into("lib") { from(bundledDrivers) }
    }
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// lib/* rather than the runtime classpath's jars, which leave out the bundled drivers above: without
// them the tools find no vendor driver. A user can also drop a driver of their own into lib/, as in
// the shell.
tasks.startScripts {
    classpath = files("*")
}
