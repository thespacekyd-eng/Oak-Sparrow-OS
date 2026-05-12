dependencies {
    implementation(project(":core"))
    implementation(project(":attestation"))
    testImplementation(project(":testing"))
    testImplementation(project(":metrics"))
    testImplementation(project(":calibration"))
    testImplementation(project(":audit"))
}
