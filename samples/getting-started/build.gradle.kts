// The warehouse application of docs/getting-started.md. A project of its own, not part of the
// driver's build: it takes the driver from Maven Central, as an application would.
plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.scalar-labs:scalardb-native-jdbc:3.20.0-beta.1")
    runtimeOnly("org.postgresql:postgresql:42.7.14")
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

application {
    // step 5: ./gradlew run
    // step 6: ./gradlew run -PmainClass=example.ReceiveShipmentWithReceipt
    mainClass = providers.gradleProperty("mainClass").orElse("example.ReceiveShipment")
}
