dependencies {
    implementation(project(":core"))
    implementation(project(":testing"))

    // Tests need full kernel stack to construct GovernanceKernel instances
    testImplementation(project(":attestation"))
    testImplementation(project(":metrics"))
    testImplementation(project(":gate"))
    testImplementation(project(":audit"))
    testImplementation(project(":calibration"))
}
