plugins {
    application
}

application {
    mainClass.set("dev.governance.metrics.MainKt")
}

dependencies {
    implementation(project(":plan-governance"))
    implementation(project(":attestation"))
    implementation(project(":core"))
}
