plugins {
    id("scalardb-native-jdbc.java-conventions")
}

description = "A sample application on Spring Data JDBC through ScalarDB Native JDBC, its acceptance test."

dependencies {
    implementation(project(":driver"))
    implementation(libs.hikaricp)
    compileOnly(libs.spotbugs.annotations)
    implementation(libs.spring.data.jdbc)

    "integrationTestImplementation"(testFixtures(project(":driver")))
    "integrationTestImplementation"(libs.hikaricp)
    "integrationTestImplementation"(libs.spring.data.jdbc)
    "integrationTestCompileOnly"(libs.spotbugs.annotations)
    "integrationTestRuntimeOnly"(libs.postgresql)
    "integrationTestRuntimeOnly"(libs.mysql.connector.j)
    "integrationTestRuntimeOnly"(libs.mariadb.java.client)
    "integrationTestRuntimeOnly"(libs.mssql.jdbc)
    "integrationTestRuntimeOnly"(libs.ojdbc11)
    "integrationTestRuntimeOnly"(libs.logback.classic)
}

// Spring Data maps rows to the entity's constructor by its parameter names, as a Spring Boot build
// compiles them in
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-parameters") }
