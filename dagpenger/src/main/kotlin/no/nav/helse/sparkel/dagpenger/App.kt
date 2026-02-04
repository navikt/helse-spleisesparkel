package no.nav.helse.sparkel.dagpenger

import com.github.navikt.tbd_libs.azure.createAzureTokenClientFromEnvironment
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*
import no.nav.helse.rapids_rivers.RapidApplication

fun main() {
    val env = System.getenv()
    RapidApplication.create(env).apply {
        val azureClient = createAzureTokenClientFromEnvironment(env)

        val httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                jackson()
            }
        }
        val dagpengerClient = DagpengerClient(
            baseUrl = env.getValue("DAGPENGER_API_BASE_URL"),
            tokenClient = azureClient,
            httpClient = httpClient,
            scope = env.getValue("ACCESS_TOKEN_SCOPE")
        )

        DagpengerRiver(
            rapidsConnection = this,
            dagpengerClient = dagpengerClient,
            behov = "DagpengerV2",
        )
    }.start()
}
