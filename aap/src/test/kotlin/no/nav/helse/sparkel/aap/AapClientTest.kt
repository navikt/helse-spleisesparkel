package no.nav.helse.sparkel.aap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.navikt.tbd_libs.azure.AzureToken
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import com.github.navikt.tbd_libs.result_object.Result
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.create
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.verify
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.jackson.jackson
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AapClientTest {
    private lateinit var wireMockServer: WireMockServer
    private lateinit var aapClient: AapClient

    private val azureTokenProvider = object : AzureTokenProvider {
        override fun bearerToken(scope: String) =
            Result.Ok(AzureToken("test-bearer-token", LocalDateTime.now().plusHours(1)))

        override fun onBehalfOfToken(scope: String, token: String) = bearerToken(scope)
    }

    private val endpoint = "/maksimum"

    @BeforeEach
    fun setup() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMockServer.start()
        configureFor(create().port(wireMockServer.port()).build())

        aapClient = AapClient(
            baseUrl = "http://localhost:${wireMockServer.port()}",
            tokenClient = azureTokenProvider,
            httpClient = HttpClient(CIO) {
                install(ContentNegotiation) {
                    jackson()
                }
            },
            scope = "test-scope"
        )
    }

    @AfterEach
    fun teardown() {
        wireMockServer.stop()
    }

    @Test
    fun `skal sende riktig request til maksimumUtenUtbetaling endpoint`() {
        stubFor(
            post(endpoint).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString())
            )
        )

        val personidentifikator = "12345678910"
        val fom = LocalDate.of(2025, 1, 1)
        val tom = LocalDate.of(2025, 12, 31)

        val respons = runBlocking {
            aapClient.hentMaksimum(personidentifikator, fom, tom, UUID.randomUUID().toString())
        }

        assertTrue(respons.isSuccess)

        val expectedRequestBody = """
            {
                "personidentifikator": "$personidentifikator",
                "fraOgMedDato": "${fom}",
                "tilOgMedDato": "${tom}"
            }
        """

        verify(
            postRequestedFor(urlEqualTo(endpoint))
                .withRequestBody(equalToJson(expectedRequestBody))
        )
    }

    @Test
    fun `skal parse response fra API korrekt`() {
        stubFor(
            post(endpoint).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString())
            )
        )

        val respons = runBlocking {
            aapClient.hentMaksimum("12345678910", LocalDate.of(1990, 1, 1), LocalDate.of(2025, 1, 1), UUID.randomUUID().toString())
        }

        assertTrue(respons.isSuccess)
        val result = respons.getOrNull()
        assertEquals("2025-04-01", result?.vedtak?.first()?.periode?.fraOgMedDato)
        assertEquals("2025-06-01", result?.vedtak?.first()?.periode?.tilOgMedDato)
    }

    @Test
    fun `skal håndtere retry ved feil og deretter suksess`() {
        val scenario = "Feiler først, så ok"
        stubFor(
            post(endpoint).inScenario(scenario).willReturn(
                aResponse().withStatus(500).withBody("Internal Server Error")
            ).willSetStateTo("har feilet")
        )
        stubFor(
            post(endpoint).inScenario(scenario).whenScenarioStateIs("har feilet").willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString())
            )
        )

        val respons = runBlocking {
            aapClient.hentMaksimum("12345678910", LocalDate.of(1990, 1, 1), LocalDate.of(2025, 1, 1), UUID.randomUUID().toString())
        }

        assertTrue(respons.isSuccess)
        verify(2, postRequestedFor(urlEqualTo(endpoint)))
    }

    @Test
    fun `skal sende med riktige headers`() {
        stubFor(
            post(endpoint).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString())
            )
        )
        val behovId = UUID.randomUUID().toString()
        runBlocking {
            aapClient.hentMaksimum("12345678910", LocalDate.of(1990, 1, 1), LocalDate.of(2025, 1, 1), behovId)
        }

        verify(
            postRequestedFor(urlEqualTo(endpoint))
                .withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("application/json"))
                .withHeader("Accept", com.github.tomakehurst.wiremock.client.WireMock.equalTo("application/json"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer test-bearer-token"))
                .withHeader("x-correlation-id", com.github.tomakehurst.wiremock.client.WireMock.equalTo(behovId))
        )
    }

    @Test
    fun `skal håndtere forskjellige datoer korrekt`() {
        stubFor(
            post(endpoint).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString())
            )
        )

        val personidentifikator = "98765432109"
        val fom = LocalDate.of(2024, 3, 1)
        val tom = LocalDate.of(2024, 9, 30)

        val respons = runBlocking {
            aapClient.hentMaksimum(personidentifikator, fom, tom, UUID.randomUUID().toString())
        }

        assertTrue(respons.isSuccess)

        val expectedRequestBody = """
            {
                "personidentifikator": "$personidentifikator",
                "fraOgMedDato": "2024-03-01",
                "tilOgMedDato": "2024-09-30"
            }
        """

        verify(
            postRequestedFor(urlEqualTo(endpoint))
                .withRequestBody(equalToJson(expectedRequestBody))
        )
    }

    @Test
    fun parseResponse() {
        val objectmapper = jacksonObjectMapper()
        objectmapper.readValue<AapClient.AapResponse>(responseJson)
    }
}

const val responseJson = """{
            "vedtak": [{
                        "barnMedStonad": 1,
                        "barnetillegg": 2,
                        "beregningsgrunnlag": 3,
                        "dagsats": 4,
                        "dagsatsEtterUføreReduksjon": 5,
                        "kildesystem": "Arena",
                        "opphorsAarsak": null,
                        "periode": {
                            "fraOgMedDato": "2025-04-01",
                            "tilOgMedDato": "2025-06-01"
                        },
                        "rettighetsType": "Tolv",
                        "saksnummer": "Ett nummer",
                        "samordningsId": null,
                        "status": "Redo for action",
                        "utbetaling": [{
                            "barnetillegg" : 123,
                            "belop" : 123,
                            "dagsats" : 123,
                            "reduksjon" : {
                                "annenReduksjon" : 123.0,
                                "timerArbeidet" : 123.0
                            },
                            "utbetalingsgrad" : 123,
                            "periode": {
                                "fraOgMedDato": "2025-04-01",
                                "tilOgMedDato": "2025-06-01"
                            }
                        }],
                        "vedtakId": "en uuid",
                        "vedtaksTypeKode": null,
                        "vedtaksTypeNavn": null,
                        "vedtaksdato": "2025-04-01"
            }]
        }
        """

