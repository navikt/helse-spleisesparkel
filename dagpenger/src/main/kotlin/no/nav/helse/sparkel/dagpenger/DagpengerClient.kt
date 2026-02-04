package no.nav.helse.sparkel.dagpenger

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.getOrThrow
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import no.nav.helse.sparkel.retry

private val retryableExceptions = arrayOf(
    IOException::class,
    ClosedReceiveChannelException::class,
    SSLHandshakeException::class,
    SocketTimeoutException::class,
    ServerResponseException::class,
)

class DagpengerClient(
    private val baseUrl: String,
    private val tokenClient: AzureTokenProvider,
    private val httpClient: HttpClient,
    private val scope: String,
) {
    suspend fun hentMeldekort(personidentifikator: String, fom: LocalDate, tom: LocalDate, behovId: String): Result<List<DagpengerMeldekortResponse>> {
        val callId = UUID.randomUUID()
        return retry("meldekort", legalExceptions = retryableExceptions) {
            val response = httpClient.preparePost("$baseUrl/dagpenger/datadeling/v1/meldekort") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                val bearerToken = tokenClient.bearerToken(scope).getOrThrow()
                bearerAuth(bearerToken.token)
                setBody(
                    mapOf(
                        "personIdent" to personidentifikator,
                        "fraOgMedDato" to fom.toString(),
                        "tilOgMedDato" to tom.toString()
                    )
                )
                header("nav-callid", "$callId")
                header("x-correlation-id", behovId)
            }.execute()

            Result.success(response.body())
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DagpengerMeldekortResponse(
        val id: String,
        val ident: String,
        val status: MeldekortStatus,
        val type: MeldekortType,
        val periode: Periode,
        val dager: List<Dag>,
        val kanSendes: Boolean,
        val kanEndres: Boolean,
        val kanSendesFra: String,
        val sisteFristForTrekk: String,
        val opprettetAv: OpprettetAvType,
        val originalMeldekortId: String?,
        val begrunnelse: String?,
        val kilde: Kilde?,
        val innsendtTidspunkt: String?,
        val registrertArbeidssoker: Boolean?,
        val meldedato: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    enum class OpprettetAvType {
        Arena, Dagpenger
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Periode(
        val fraOgMed: String,
        val tilOgMed: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Dag(
        val dato: String,
        val aktiviteter: List<Aktivitet>,
        val dagIndex: Int
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Aktivitet(
        val id: UUID,
        val type: AktivitetType,
        val timer: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Kilde(
        val rolle: RolleType,
        val ident: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    enum class RolleType {
        Bruker,
        Saksbehandler
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    enum class MeldekortStatus {
        TilUtfylling,
        Innsendt,
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    enum class MeldekortType {
        Ordinaert,
        Korrigert,
        Etterregistrert
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    enum class AktivitetType {
        Arbeid, Syk, Utdanning, Fravaer
    }
}

