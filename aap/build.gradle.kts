plugins {
    id("no.nav.helse.sas.sas-deployable")
}

sasDeployable {
    mainClass = "no.nav.helse.sparkel.aap.AppKt"
    imageName = "helse-spleisesparkel-aap"
}

dependencies {
    implementation(libs.tbd.libs.azure)
    implementation(libs.tbd.libs.retry)
    implementation(libs.bundles.ktor.client)
    implementation(project(":felles"))

    testImplementation(libs.tbd.libs.rapids.and.rivers.test)
    testImplementation(libs.wiremock) {
        exclude(group = "junit")
    }
}
