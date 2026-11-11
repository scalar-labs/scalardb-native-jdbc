// The EC and point applications of docs/getting-started.md. A project of its own, not part of the
// driver's build: it takes the driver from Maven Central, as an application would.
plugins {
    java
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
    runtimeOnly("org.slf4j:slf4j-simple:2.0.20")
}

// ./gradlew -q ec --args="topup 1 3000", the EC application
// ./gradlew -q point --args="earn 1 100 checkin", the point application
tasks.register<JavaExec>("ec") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "example.EcApp"
}
tasks.register<JavaExec>("point") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "example.PointApp"
}
