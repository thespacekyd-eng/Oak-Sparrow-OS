val kotestVersion = "5.9.1"

dependencies {
    implementation(project(":core"))
    // Testing module provides property generators, so it needs kotest-property as implementation
    implementation("io.kotest:kotest-property:$kotestVersion")
}
