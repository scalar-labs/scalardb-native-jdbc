import java.util.Properties

// The warehouse application of docs/getting-started.md. A project of its own, not part of the
// driver's build: it takes the driver from Maven Central, as an application would.
plugins {
    application
}

// The driver's version is this repository's, from its gradle.properties. A -SNAPSHOT version is a
// development build, taken from the local Maven repository (docs/development-builds.md); a copy of
// this directory outside the repository takes the release below.
val repositoryProperties = file("../../gradle.properties")
val driverVersion =
    if (repositoryProperties.isFile) {
        Properties().apply { repositoryProperties.inputStream().use { load(it) } }.getProperty("version")
    } else {
        "3.20.0-beta.1"
    }

repositories {
    if (driverVersion.endsWith("-SNAPSHOT")) {
        mavenLocal()
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
