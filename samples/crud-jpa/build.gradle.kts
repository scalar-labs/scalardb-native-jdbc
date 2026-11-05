plugins {
    id("scalardb-native-jdbc.java-conventions")
}

description = "A sample application on Spring Data JPA and Hibernate through ScalarDB Native JDBC, its acceptance test."

dependencies {
    implementation(project(":driver"))
    implementation(libs.hikaricp)
    compileOnly(libs.spotbugs.annotations)
    implementation(libs.spring.data.jpa)
    implementation(libs.hibernate.core)

    "integrationTestImplementation"(testFixtures(project(":driver")))
    "integrationTestImplementation"(libs.hikaricp)
    "integrationTestImplementation"(libs.spring.data.jpa)
    "integrationTestImplementation"(libs.hibernate.core)
    "integrationTestCompileOnly"(libs.spotbugs.annotations)
    "integrationTestRuntimeOnly"(libs.postgresql)
    "integrationTestRuntimeOnly"(libs.mysql.connector.j)
    "integrationTestRuntimeOnly"(libs.mariadb.java.client)
    "integrationTestRuntimeOnly"(libs.mssql.jdbc)
    "integrationTestRuntimeOnly"(libs.ojdbc11)
    "integrationTestRuntimeOnly"(libs.logback.classic)
}
