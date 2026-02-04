package no.nav.helse.sparkel.aap

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
import no.nav.helse.sparkel.retry
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.channels.ClosedReceiveChannelException

private val retryableExceptions = arrayOf(
    IOException::class,
    ClosedReceiveChannelException::class,
    SSLHandshakeException::class,
    SocketTimeoutException::class,
    ServerResponseException::class,
)

class AapClient(
    private val baseUrl: String,
    private val tokenClient: AzureTokenProvider,
    private val httpClient: HttpClient,
    private val scope: String,
) {
    suspend fun hentMaksimum(personidentifikator: String, fom: LocalDate, tom: LocalDate, behovId: String): Result<AapResponse> {
        val callId = UUID.randomUUID()
        return retry("maksimum", legalExceptions = retryableExceptions) {
            val response = httpClient.preparePost("$baseUrl/maksimum") {
                expectSuccess = true
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                val bearerToken = tokenClient.bearerToken(scope).getOrThrow()
                bearerAuth(bearerToken.token)
                setBody(mapOf(
                    "personidentifikator" to personidentifikator,
                    "fraOgMedDato" to fom.toString(),
                    "tilOgMedDato" to tom.toString()
                ))
                header("nav-callid", "$callId")
                header("x-correlation-id", behovId)
            }.execute()

            Result.success(response.body())
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AapResponse(
        val vedtak: List<AapRettighet>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AapRettighet(
        val barnMedStonad: Long,
        val barnetillegg: Long,
        val beregningsgrunnlag: Long,
        val dagsats: Long,
        val dagsatsEtterUføreReduksjon: Long?,
        val kildesystem: String,
        val opphorsAarsak: String?,
        val periode: Periode,
        val rettighetsType: String,
        val saksnummer: String,
        val samordningsId: String?,
        val status: String,
        val utbetaling: List<Utbetaling>,
        val vedtakId: String,
        val vedtaksTypeKode: String?,
        val vedtaksTypeNavn: String?,
        val vedtaksdato: String,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Periode(
        val fraOgMedDato: String?,
        val tilOgMedDato: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Utbetaling(
        val barnetillegg: Long,
        val belop: Long,
        val dagsats: Long,
        val periode: Periode,
        val reduksjon: Reduksjon?,
        val utbetalingsgrad: Long?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Reduksjon(
        val annenReduksjon: Long,
        val timerArbeidet: Long,
    )
}
