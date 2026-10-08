plugins {
    id("scalardb-native-jdbc.java-conventions")
}

// Thin override of java-conventions for the published surface. Keeps the JDK 25 toolchain, so Error
// Prone and NullAway still run, but compiles the MAIN source against the Java 11 platform API and
// language level with --release 11, backed by ct.sym. Java 11 is the widest floor that allows
// JSQLParser 5 and loads in every application stack that can use the driver (docs/design.md,
// "Java target"). This enforces a Java-11-clean published surface: no records, no switch expressions,
// no pattern matching, no text blocks. Tests are NOT constrained; they run at the JDK 25 toolchain.
tasks.named<JavaCompile>("compileJava") {
    options.release = 11
    // Keep all javac lint on the published surface, minus `options`: the benign "source/target value
    // 11 is obsolete" notice inherent to --release 11.
    options.compilerArgs.add("-Xlint:all,-options")
}
