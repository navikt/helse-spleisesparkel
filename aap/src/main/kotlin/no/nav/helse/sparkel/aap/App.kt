package no.nav.helse.sparkel.aap

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

        val aapClient = AapClient(
            baseUrl = env.getValue("AAP_API_BASE_URL"),
            tokenClient = azureClient,
            httpClient = httpClient,
            scope = env.getValue("ACCESS_TOKEN_SCOPE")
        )

        AapRiver(
            rapidsConnection = this,
            aapClient = aapClient,
            behov = "ArbeidsavklaringspengerV2",
        )

    }.start()
}
