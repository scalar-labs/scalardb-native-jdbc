plugins {
    id("scalardb-native-jdbc.java-conventions")
}

description = "A sample application on plain JDBC through ScalarDB Native JDBC, its acceptance test."

dependencies {
    implementation(project(":driver"))
    implementation(libs.hikaricp)
    compileOnly(libs.spotbugs.annotations)

    "integrationTestImplementation"(testFixtures(project(":driver")))
    "integrationTestImplementation"(libs.hikaricp)
    "integrationTestCompileOnly"(libs.spotbugs.annotations)
    "integrationTestRuntimeOnly"(libs.postgresql)
    "integrationTestRuntimeOnly"(libs.mysql.connector.j)
    "integrationTestRuntimeOnly"(libs.mariadb.java.client)
    "integrationTestRuntimeOnly"(libs.mssql.jdbc)
    "integrationTestRuntimeOnly"(libs.ojdbc11)
    "integrationTestRuntimeOnly"(libs.logback.classic)
}
