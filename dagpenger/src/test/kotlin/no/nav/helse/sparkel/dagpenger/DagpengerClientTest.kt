package no.nav.helse.sparkel.dagpenger

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

internal class DagpengerClientTest {
    private lateinit var wireMockServer: WireMockServer
    private lateinit var dagpengerClient: DagpengerClient

    private val azureTokenProvider = object : AzureTokenProvider {
        override fun bearerToken(scope: String) =
            Result.Ok(AzureToken("test-bearer-token", LocalDateTime.now().plusHours(1)))

        override fun onBehalfOfToken(scope: String, token: String) = bearerToken(scope)
    }

    private val endpoint = "/dagpenger/datadeling/v1/meldekort"

    @BeforeEach
    fun setup() {
        wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        wireMockServer.start()
        configureFor(create().port(wireMockServer.port()).build())

        dagpengerClient = DagpengerClient(
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
    fun `skal sende riktig request til meldekort endpoint`() {
        stubFor(
            post(endpoint).willReturn(
                okJson(jacksonObjectMapper().readTree(responseJson).toString())
            )
        )

        val personidentifikator = "12345678910"
        val fom = LocalDate.of(2025, 1, 1)
        val tom = LocalDate.of(2025, 1, 14)

        val respons = runBlocking {
            dagpengerClient.hentMeldekort(personidentifikator, fom, tom, UUID.randomUUID().toString())
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
            dagpengerClient.hentMeldekort("12345678910", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14), UUID.randomUUID().toString())
        }

        assertTrue(respons.isSuccess)
        val result = respons.getOrNull()
        assertEquals(1, result?.size)
        assertEquals("2025-01-01", result?.first()?.periode?.fraOgMed)
        assertEquals("2025-01-14", result?.first()?.periode?.tilOgMed)
        assertEquals(DagpengerClient.MeldekortStatus.Innsendt, result?.first()?.status)
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
            dagpengerClient.hentMeldekort("12345678910", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14), UUID.randomUUID().toString())
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
            dagpengerClient.hentMeldekort("12345678910", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14), behovId)
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
        val tom = LocalDate.of(2024, 3, 14)

        val respons = runBlocking {
            dagpengerClient.hentMeldekort(personidentifikator, fom, tom, UUID.randomUUID().toString())
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
                .withRequestBody(equalToJson(expectedRequestBody))
        )
    }

    @Test
    fun parseResponse() {
        val objectmapper = jacksonObjectMapper()
        objectmapper.readValue<List<DagpengerClient.DagpengerMeldekortResponse>>(responseJson)
    }
}

const val responseJson = """[{
    "id": "01JKT9EFQQ1W2V3X4Y5Z6A7B8C",
    "ident": "12345678910",
    "status": "Innsendt",
    "type": "Ordinaert",
    "periode": {
        "fraOgMed": "2025-01-01",
        "tilOgMed": "2025-01-14"
    },
    "dager": [
        {
            "dato": "2025-01-01",
            "aktiviteter": [
                {
                    "id": "550e8400-e29b-41d4-a716-446655440000",
                    "type": "Arbeid",
                    "timer": "7.5"
                }
            ],
            "dagIndex": 0
        },
        {
            "dato": "2025-01-02",
            "aktiviteter": [
                {
                    "id": "550e8400-e29b-41d4-a716-446655440001",
                    "type": "Syk",
                    "timer": null
                }
            ],
            "dagIndex": 1
        }
    ],
    "kanSendes": false,
    "kanEndres": false,
    "kanSendesFra": "2025-01-15T00:00:00",
    "sisteFristForTrekk": "2025-01-28T00:00:00",
    "opprettetAv": "Dagpenger",
    "originalMeldekortId": null,
    "begrunnelse": null,
    "kilde": {
        "rolle": "Bruker",
        "ident": "12345678910"
    },
    "innsendtTidspunkt": "2025-01-15T10:30:00",
    "registrertArbeidssoker": true,
    "meldedato": "2025-01-15"
}]
"""
