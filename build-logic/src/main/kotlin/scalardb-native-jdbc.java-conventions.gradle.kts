import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    id("scalardb-native-jdbc.base-conventions")
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
    id("net.ltgt.errorprone")
    id("net.ltgt.nullaway")
}

val libs = the<LibrariesForLibs>()

base.archivesName = "scalardb-native-jdbc-${project.name}"

// The JDK 25 toolchain runs the compiler, Error Prone, NullAway and the tests, as in ScalarDB Saga.
// The published surface of a module is compiled with --release by java11-conventions.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Reproducible archives
// ---------------------------------------------------------------------------

// Build timestamps and filesystem ordering would otherwise make every jar byte-different across
// builds of the same source. Pinning both makes a published artifact verifiable against a rebuild.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).isNoTimestamp = true
    // javadoc stops printing after its default of 100 warnings; raise the cap so the log states the
    // whole number. Doclint keeps its full default, "missing" group included: the javadoc is read by
    // the developers who change this code as much as by users.
    (options as StandardJavadocDocletOptions).addStringOption("Xmaxwarns", "10000")
}

// ---------------------------------------------------------------------------
// Spotless: the Google Java Style Guide, https://google.github.io/styleguide/javaguide.html
// ---------------------------------------------------------------------------

spotless {
    java {
        target("src/*/java/**/*.java")
        importOrder()
        removeUnusedImports()
        forbidWildcardImports()
        googleJavaFormat(libs.versions.google.java.format.get())
        formatAnnotations()
    }
}

// ---------------------------------------------------------------------------
// SpotBugs + FindSecBugs
// ---------------------------------------------------------------------------

spotbugs {
    effort = Effort.MAX
    reportLevel = Confidence.MEDIUM
    showStackTraces = true
    maxHeapSize = "1g"
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
}

dependencies {
    spotbugsPlugins(libs.findsecbugs.plugin)
}

tasks.withType<SpotBugsTask>().configureEach {
    val task = name
    reports.create("html") {
        required = true
        outputLocation = layout.buildDirectory.file("reports/spotbugs/$task.html")
    }
}

// ---------------------------------------------------------------------------
// Error Prone + NullAway
// ---------------------------------------------------------------------------

nullaway {
    annotatedPackages.add("com.scalar.db.nativejdbc")
}

dependencies {
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
}

tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableWarningsInGeneratedCode = true
        enable(
            "MissingOverride",
            "UnusedVariable",
            "FieldCanBeFinal",
        )
        // An enum switch that silently gains a constant is a correctness bug, not a style point: the
        // new constant inherits whatever the default arm does. Escalated to an error because a warning
        // here is invisible in practice; there is no -Werror.
        error("MissingCasesInEnumSwitch")
    }
}

tasks.compileJava {
    options.errorprone {
        error("NullAway")
    }
}

// ---------------------------------------------------------------------------
// Testing
// ---------------------------------------------------------------------------

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit.jupiter.get())
        }

        // The integration tests are the interleaving matrix of docs/design.md: driver transactions
        // against real ScalarDB transactions on database containers.
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit.jupiter.get())

            dependencies {
                implementation(project())
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
    // Javadoc is not part of `check` by default, so an unresolvable {@link} or a malformed tag fails
    // nothing until something builds the javadoc jar, which only happens when publishing. The javadoc
    // is a published artifact, so hold it to the same bar as the code and find out on the pull request.
    dependsOn(tasks.named("javadoc"))
}

dependencies {
    compileOnly(libs.jspecify)
    compileOnly(libs.jcip.annotations)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.assertj.core)

    "integrationTestImplementation"(libs.assertj.core)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
