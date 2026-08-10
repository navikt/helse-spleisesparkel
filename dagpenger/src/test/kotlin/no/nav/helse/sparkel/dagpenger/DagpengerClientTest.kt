package no.nav.helse.sparkel.dagpenger

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
import io.ktor.serialization.jackson3.jackson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

internal class DagpengerClientTest {
    private lateinit var wireMockServer: WireMockServer
    private lateinit var dagpengerClient: DagpengerClient

    private val azureTokenProvider =
        object : AzureTokenProvider {
            override fun bearerToken(scope: String) = Result.Ok(AzureToken("test-bearer-token", LocalDateTime.now().plusHours(1)))

            override fun onBehalfOfToken(
                scope: String,
                token: String,
            ) = bearerToken(scope)
        }

    private val endpoint = "/dagpenger/datadeling/v1/beregninger"

    @BeforeEach
    fun setup() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMockServer.start()
        configureFor(create().port(wireMockServer.port()).build())

        dagpengerClient =
            DagpengerClient(
                baseUrl = "http://localhost:${wireMockServer.port()}",
                tokenClient = azureTokenProvider,
                httpClient =
                    HttpClient(CIO) {
                        install(ContentNegotiation) {
                            jackson()
                        }
                    },
                scope = "test-scope",
            )
    }

    @AfterEach
    fun teardown() {
        wireMockServer.stop()
    }

    @Test
    fun `skal sende riktig request til beregninger endpoint`() {
        stubFor(
            post(urlEqualTo(endpoint)).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString()),
            ),
        )

        val personidentifikator = "12345678910"
        val fom = LocalDate.of(2025, 1, 1)
        val tom = LocalDate.of(2025, 1, 14)

        val respons =
            runBlocking {
                dagpengerClient.hentBeregninger(personidentifikator, fom, tom, UUID.randomUUID().toString())
            }

        assertTrue(respons.isSuccess)

        val expectedRequestBody = """
            {
                "personIdent": "$personidentifikator",
                "fraOgMedDato": "$fom",
                "tilOgMedDato": "$tom"
            }
        """

        verify(
            postRequestedFor(urlEqualTo(endpoint))
                .withRequestBody(equalToJson(expectedRequestBody)),
        )
    }

    @Test
    fun `skal parse response fra API korrekt`() {
        stubFor(
            post(urlEqualTo(endpoint)).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString()),
            ),
        )

        val respons =
            runBlocking {
                dagpengerClient.hentBeregninger("12345678910", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14), UUID.randomUUID().toString())
            }

        assertTrue(respons.isSuccess)
        val result = respons.getOrNull()
        assertEquals(1, result?.size)
        assertEquals("2025-01-01", result?.first()?.fraOgMed)
        assertEquals("2025-01-14", result?.first()?.tilOgMed)
        assertEquals(5000, result?.first()?.utbetaltBeløp)
    }

    @Test
    fun `skal håndtere retry ved feil og deretter suksess`() {
        val scenario = "Feiler først, så ok"
        stubFor(
            post(urlEqualTo(endpoint))
                .inScenario(scenario)
                .willReturn(
                    aResponse().withStatus(500).withBody("Internal Server Error"),
                ).willSetStateTo("har feilet"),
        )
        stubFor(
            post(urlEqualTo(endpoint)).inScenario(scenario).whenScenarioStateIs("har feilet").willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString()),
            ),
        )

        val respons =
            runBlocking {
                dagpengerClient.hentBeregninger("12345678910", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14), UUID.randomUUID().toString())
            }

        assertTrue(respons.isSuccess)
        verify(2, postRequestedFor(urlEqualTo(endpoint)))
    }

    @Test
    fun `skal sende med riktige headers`() {
        stubFor(
            post(urlEqualTo(endpoint)).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString()),
            ),
        )
        val behovId = UUID.randomUUID().toString()
        runBlocking {
            dagpengerClient.hentBeregninger("12345678910", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14), behovId)
        }

        verify(
            postRequestedFor(urlEqualTo(endpoint))
                .withHeader(
                    "Content-Type",
                    com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("application/json"),
                ).withHeader(
                    "Accept",
                    com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("application/json"),
                ).withHeader(
                    "Authorization",
                    com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("Bearer test-bearer-token"),
                ).withHeader(
                    "x-correlation-id",
                    com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo(behovId),
                ),
        )
    }

    @Test
    fun `skal håndtere forskjellige datoer korrekt`() {
        stubFor(
            post(urlEqualTo(endpoint)).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString()),
            ),
        )

        val personidentifikator = "98765432109"
        val fom = LocalDate.of(2024, 3, 1)
        val tom = LocalDate.of(2024, 3, 14)

        val respons =
            runBlocking {
                dagpengerClient.hentBeregninger(personidentifikator, fom, tom, UUID.randomUUID().toString())
            }

        assertTrue(respons.isSuccess)

        val expectedRequestBody = """
            {
                "personIdent": "$personidentifikator",
                "fraOgMedDato": "2024-03-01",
                "tilOgMedDato": "2024-03-14"
            }
        """

        verify(
            postRequestedFor(urlEqualTo(endpoint))
                .withRequestBody(equalToJson(expectedRequestBody)),
        )
    }

    @Test
    fun `skal ignorere beregninger med utbetalt beløp lik null`() {
        stubFor(
            post(urlEqualTo(endpoint)).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJsonMed0UtbetaltBeløp).toString()),
            ),
        )

        val respons =
            runBlocking {
                dagpengerClient.hentBeregninger("12345678910", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), UUID.randomUUID().toString())
            }

        assertTrue(respons.isSuccess)
        val result = respons.getOrNull()
        assertEquals(1, result?.size)
        assertEquals("2025-01-01", result?.first()?.fraOgMed)
        assertEquals("2025-01-14", result?.first()?.tilOgMed)
        assertEquals(5000, result?.first()?.utbetaltBeløp)
    }

    @Test
    fun parseResponse() {
        val objectmapper = jacksonObjectMapper()
        objectmapper.readValue<List<DagpengerClient.DagpengerBeregningResponse>>(responseJson)
    }
}

const val responseJson = """[{
    "fraOgMed": "2025-01-01",
    "tilOgMed": "2025-01-14",
    "sats": 1000,
    "utbetaltBeløp": 5000,
    "gjenstendeDager": 50,
    "kilde": "ARENA"
}]
"""

const val responseJsonMed0UtbetaltBeløp = """[
    {
        "fraOgMed": "2025-01-01",
        "tilOgMed": "2025-01-14",
        "sats": 1000,
        "utbetaltBeløp": 5000,
        "gjenstendeDager": 50,
        "kilde": "ARENA"
    },
    {
        "fraOgMed": "2025-01-15",
        "tilOgMed": "2025-01-31",
        "sats": 1000,
        "utbetaltBeløp": 0,
        "gjenstendeDager": 50,
        "kilde": "DP_SAK"
    }
]
"""
