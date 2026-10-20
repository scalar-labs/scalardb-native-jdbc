plugins {
    id("scalardb-native-jdbc.java-conventions")
    id("scalardb-native-jdbc.java11-conventions")
    id("scalardb-native-jdbc.publishing-conventions")
    `java-test-fixtures`
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

// A bug report can name the build from the jar; the module name stays as the package's when the
// driver is on the module path.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "ScalarDB Native JDBC",
            "Implementation-Version" to project.version.toString(),
            "Automatic-Module-Name" to "com.scalar.db.nativejdbc",
        )
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

    // The engine containers (testing.Engines), shared with the shell's integration tests.
    // ScalarDB brings its own copies of the vendor drivers; ojdbc8 duplicates ojdbc11's classes
    testFixturesApi(libs.scalardb) {
        exclude(group = "com.oracle.database.jdbc", module = "ojdbc8")
    }
    testFixturesApi(platform(libs.testcontainers.bom))
    testFixturesApi(libs.testcontainers)
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.testcontainers.mysql)
    testFixturesApi(libs.testcontainers.mariadb)
    testFixturesApi(libs.testcontainers.mssqlserver)
    testFixturesApi(libs.testcontainers.oracle.free)
    testFixturesCompileOnly(libs.spotbugs.annotations)
    "integrationTestImplementation"(testFixtures(project))
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

// The test fixtures are for this build's own tests, not a published artifact.
// The sources variant exists only once the publishing plugin has added the sources jar.
afterEvaluate {
    val javaComponent = components["java"] as AdhocComponentWithVariants
    for (name in listOf("ApiElements", "RuntimeElements", "SourcesElements")) {
        configurations.findByName("testFixtures$name")?.let {
            javaComponent.withVariantsFromConfiguration(it) { skip() }
        }
    }
}

// -Dnativejdbc.bench=true runs the read-overhead benchmark (bench package), off by default; the
// -Dnativejdbc.* options reach every integration test task (java-conventions)
tasks.withType<Test>().matching { it.name.startsWith("integrationTest") }.configureEach {
    // measured as an application runs the driver, assertions off (in every jar, as in production)
    enableAssertions = providers.systemProperty("nativejdbc.bench").map { it != "true" }.getOrElse(true)
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
