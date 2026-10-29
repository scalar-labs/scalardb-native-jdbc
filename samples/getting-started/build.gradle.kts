// The warehouse application of docs/getting-started.md. A project of its own, not part of the
// driver's build: it takes the driver from Maven Central, as an application would.
plugins {
    application
}

// The released driver the guide names; -PdriverVersion=<version>-SNAPSHOT takes an unreleased one
// from the local Maven repository or the Maven Central snapshot repository
// (docs/development-builds.md). release.yml checks the default against the version it releases.
val driverVersion = providers.gradleProperty("driverVersion").getOrElse("3.20.0")

repositories {
    if (driverVersion.endsWith("-SNAPSHOT")) {
        mavenLocal()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
    mavenCentral()
}

dependencies {
    implementation("com.scalar-labs:scalardb-native-jdbc:$driverVersion")
    runtimeOnly("org.postgresql:postgresql:42.7.14")
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

application {
    // step 5: ./gradlew run
    // step 6: ./gradlew run -PmainClass=example.ReceiveShipmentWithReceipt
    mainClass = providers.gradleProperty("mainClass").orElse("example.ReceiveShipment")
}
