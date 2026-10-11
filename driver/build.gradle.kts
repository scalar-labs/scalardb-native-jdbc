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

    // ScalarDB brings its own copies of the vendor drivers; ojdbc8 duplicates ojdbc11's classes
    "integrationTestImplementation"(libs.scalardb) {
        exclude(group = "com.oracle.database.jdbc", module = "ojdbc8")
    }
    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"(libs.testcontainers)
    "integrationTestImplementation"(libs.testcontainers.postgresql)
    "integrationTestImplementation"(libs.testcontainers.mysql)
    "integrationTestImplementation"(libs.testcontainers.mariadb)
    "integrationTestImplementation"(libs.testcontainers.mssqlserver)
    "integrationTestImplementation"(libs.testcontainers.oracle.free)
    // the vendor drivers an application would use, one per engine
    "integrationTestRuntimeOnly"(libs.postgresql)
    "integrationTestRuntimeOnly"(libs.mysql.connector.j)
    "integrationTestRuntimeOnly"(libs.mariadb.java.client)
    "integrationTestRuntimeOnly"(libs.mssql.jdbc)
    "integrationTestRuntimeOnly"(libs.ojdbc11)
    "integrationTestRuntimeOnly"(libs.logback.classic)
    // the pool most applications put in front of a driver; the version ScalarDB itself brings
    "integrationTestImplementation"(libs.hikaricp)
}

// Which engines the integration tests run against: -Dnativejdbc.engines=postgresql,oracle or "all".
tasks.named<Test>("integrationTest") {
    systemProperty(
        "nativejdbc.engines",
        providers.systemProperty("nativejdbc.engines").getOrElse("all"),
    )
    // -Dnativejdbc.bench=true runs the read-overhead benchmark (bench package), off by default;
    // -Dnativejdbc.bench.* options are passed on (see ReadOverheadBenchmark)
    systemProperties(providers.systemPropertiesPrefixedBy("nativejdbc.bench").get())
}

// The driver inspects statements through JSQLParser's public syntax tree, never through its private
// fields, so it must give the same answers with JSQLParser loaded as a named module, where
// reflection is refused. This runs the classifier that way once per build.
val modulePathCheck by tasks.registering(JavaExec::class) {
    description = "Runs the SQL classifier with JSQLParser on the module path."
    group = "verification"
    val sourceSets = project.the<JavaPluginExtension>().sourceSets
    val testRuntime = configurations.named("testRuntimeClasspath")
    classpath =
        files(
            sourceSets["test"].output,
            sourceSets["main"].output,
            testRuntime.map { it.filter { f -> !f.name.startsWith("jsqlparser-") } },
        )
    mainClass = "com.scalar.db.nativejdbc.sql.ModulePathCheck"
    val parserJar = testRuntime.map { it.filter { f -> f.name.startsWith("jsqlparser-") } }
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--module-path", parserJar.get().asPath, "--add-modules", "net.sf.jsqlparser")
        }
    )
}
tasks.named("check") { dependsOn(modulePathCheck) }
