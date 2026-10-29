plugins {
    id("scalardb-native-jdbc.base-conventions")
    id("com.vanniktech.maven.publish")
}

// Publishing for the consumable modules. The Vanniktech plugin auto-detects the platform from the
// applied plugins (`java-library` gets a sources + javadoc jar) and uploads through the Central
// Portal API. Everything specific to this project lives in this one file.

// Ship the license inside every published jar (META-INF/LICENSE), so the terms travel with the jar
// wherever it is copied. The base class rather than the `bundling` Jar an unqualified `Jar` resolves
// to: the javadoc jar comes from the publishing plugin and extends only the base class.
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    metaInf {
        from(rootProject.file("LICENSE"))
    }
}

mavenPublishing {
    // The artifactId must be set explicitly: `from(components["java"])` defaults it to the Gradle
    // project name, which would publish generically-named artifacts under our group. This mirrors
    // `base.archivesName` in java-conventions, so the POM coordinate and the jar file name stay in
    // step. A module whose published name is not derivable from its directory reassigns all three
    // from its own build file, where the later call wins; the driver module does.
    coordinates(
        groupId = project.group.toString(),
        artifactId = "scalardb-native-jdbc-${project.name}",
        version = project.version.toString(),
    )

    // Uploads a USER_MANAGED deployment to the Central Portal for a human to release. SNAPSHOT
    // versions bypass this and go to the Central snapshot repository.
    publishToMavenCentral()

    // Central rejects unsigned releases, but requiring a signature unconditionally would also break
    // `publishToMavenLocal` for anyone without a key. Sign only when a key is actually configured.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name = "scalardb-native-jdbc-${project.name}"
        description = provider {
            checkNotNull(project.description) {
                "Project '${project.path}' is published, so it must set `description`; Maven " +
                    "Central rejects a POM without one."
            }
        }
        url = "https://github.com/scalar-labs/scalardb-native-jdbc"
        inceptionYear = "2026"

        // Central rejects a POM without a license.
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "scalar-labs"
                name = "Scalar, Inc."
                url = "https://scalar-labs.com/"
            }
        }

        scm {
            url = "https://github.com/scalar-labs/scalardb-native-jdbc"
            connection = "scm:git:https://github.com/scalar-labs/scalardb-native-jdbc.git"
            developerConnection = "scm:git:ssh://git@github.com/scalar-labs/scalardb-native-jdbc.git"
        }

        issueManagement {
            system = "GitHub Issues"
            url = "https://github.com/scalar-labs/scalardb-native-jdbc/issues"
        }
    }
}
