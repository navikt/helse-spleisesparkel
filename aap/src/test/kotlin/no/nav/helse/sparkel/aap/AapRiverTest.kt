package no.nav.helse.sparkel.aap

import com.github.navikt.tbd_libs.rapids_and_rivers.test_support.TestRapid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

internal class AapRiverTest {
    private val testRapid = TestRapid()
    private val behov = "Maksimum"

    @BeforeEach
    fun setUp() {
        testRapid.reset()
    }

    private fun behovJson(fødselsnummer: String = "12345678910") =
        """
        {
            "@id": "${UUID.randomUUID()}",
            "@behov": ["$behov"],
            "fødselsnummer": "$fødselsnummer",
            "vedtaksperiodeId": "${UUID.randomUUID()}",
            "$behov": {
                "periodeFom": "2025-04-01",
                "periodeTom": "2025-06-01"
            }
        }
        """.trimIndent()

    private fun mockClient(response: AapClient.AapResponse): AapClient =
        object : AapClient {
            override suspend fun hentMaksimum(
                personidentifikator: String,
                fom: LocalDate,
                tom: LocalDate,
                behovId: String,
            ): Result<AapClient.AapResponse> = Result.success(response)
        }

    @Test
    fun `skal filtrere bort utbetalinger med belop=0 og kun sende utbetalinger med belop større enn 0 i løsning`() {
        val response =
            AapClient.AapResponse(
                vedtak =
                    listOf(
                        vedtak(
                            utbetaling =
                                listOf(
                                    utbetaling(belop = 0, fraOgMedDato = "2025-04-01", tilOgMedDato = "2025-05-01"),
                                    utbetaling(belop = 456, fraOgMedDato = "2025-05-01", tilOgMedDato = "2025-06-01"),
                                ),
                        ),
                    ),
            )

        AapRiver(testRapid, mockClient(response), behov)
        testRapid.sendTestMessage(behovJson())

        assertEquals(1, testRapid.inspektør.size)
        val løsning = testRapid.inspektør.message(0)["@løsning"][behov]
        val utbetalingsperioder = løsning["utbetalingsperioder"]

        assertEquals(1, utbetalingsperioder.size(), "Skal kun inneholde utbetalingen med belop=456")
        assertEquals("2025-05-01", utbetalingsperioder[0]["fom"].asText())
        assertEquals("2025-06-01", utbetalingsperioder[0]["tom"].asText())
    }

    @Test
    fun `skal gi tom liste når alle utbetalinger har belop=0`() {
        val response =
            AapClient.AapResponse(
                vedtak =
                    listOf(
                        vedtak(
                            utbetaling =
                                listOf(
                                    utbetaling(belop = 0, fraOgMedDato = "2025-04-01", tilOgMedDato = "2025-05-01"),
                                    utbetaling(belop = 0, fraOgMedDato = "2025-05-01", tilOgMedDato = "2025-06-01"),
                                ),
                        ),
                    ),
            )

        AapRiver(testRapid, mockClient(response), behov)
        testRapid.sendTestMessage(behovJson())

        assertEquals(1, testRapid.inspektør.size)
        val løsning = testRapid.inspektør.message(0)["@løsning"][behov]
        assertEquals(0, løsning["utbetalingsperioder"].size(), "Alle utbetalinger med belop=0 skal filtreres bort")
    }

    private fun vedtak(utbetaling: List<AapClient.Utbetaling>) =
        AapClient.AapRettighet(
            barnMedStonad = 1,
            barnetillegg = 2,
            beregningsgrunnlag = 3,
            dagsats = 4,
            dagsatsEtterUføreReduksjon = 5,
            kildesystem = "Arena",
            opphorsAarsak = null,
            periode = AapClient.Periode(fraOgMedDato = "2025-04-01", tilOgMedDato = "2025-06-01"),
            rettighetsType = "Tolv",
            saksnummer = "Ett nummer",
            samordningsId = null,
            status = "Aktiv",
            utbetaling = utbetaling,
            vedtakId = "en-uuid",
            vedtaksTypeKode = null,
            vedtaksTypeNavn = null,
            vedtaksdato = "2025-04-01",
        )

    private fun utbetaling(
        belop: Long,
        fraOgMedDato: String,
        tilOgMedDato: String,
    ) = AapClient.Utbetaling(
        barnetillegg = 0,
        belop = belop,
        dagsats = 0,
        periode = AapClient.Periode(fraOgMedDato = fraOgMedDato, tilOgMedDato = tilOgMedDato),
        reduksjon = null,
        utbetalingsgrad = 0,
    )
}
