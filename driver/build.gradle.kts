plugins {
    id("scalardb-native-jdbc.java-conventions")
    id("scalardb-native-jdbc.java11-conventions")
    id("scalardb-native-jdbc.publishing-conventions")
}

description =
    "ScalarDB Native JDBC: a JDBC driver for a database that holds both ordinary tables and " +
    "ScalarDB-managed tables. The application uses the database's own SQL and transactions across " +
    "both, and ScalarDB's transactions on the same tables keep their guarantees."

// Published under the bare name, not scalardb-native-jdbc-driver: the driver is the product and the
// module name is an implementation detail. The three names the conventions derive from the module
// name are overridden together.
base.archivesName = "scalardb-native-jdbc"
mavenPublishing {
    coordinates(
        groupId = project.group.toString(),
        artifactId = "scalardb-native-jdbc",
        version = project.version.toString(),
    )
    pom {
        name = "scalardb-native-jdbc"
    }
}

dependencies {
    implementation(libs.jsqlparser)
    implementation(libs.slf4j.api)
    // No ScalarDB dependency, and no vendor driver dependency either: the driver talks to the vendor
    // driver through java.sql only, and the application supplies the vendor driver (docs/design.md,
    // "Repository and build").

    // The tests run real ScalarDB transactions against the driver on database containers.
    testImplementation(libs.scalardb)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.logback.classic)

    "integrationTestImplementation"(libs.scalardb)
    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"(libs.testcontainers)
    "integrationTestImplementation"(libs.testcontainers.postgresql)
    "integrationTestRuntimeOnly"(libs.postgresql)
    "integrationTestRuntimeOnly"(libs.logback.classic)
}
