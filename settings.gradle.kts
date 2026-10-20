pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "scalardb-native-jdbc"

include("driver")
include("shell")
include("tools")
include("samples:crud-jdbc")
include("samples:crud-spring-data-jdbc")
