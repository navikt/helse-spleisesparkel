package no.nav.helse.sparkel.aap

import com.fasterxml.jackson.databind.JsonNode
import com.github.navikt.tbd_libs.rapids_and_rivers.JsonMessage
import com.github.navikt.tbd_libs.rapids_and_rivers.River
import com.github.navikt.tbd_libs.rapids_and_rivers.asLocalDate
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageContext
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageMetadata
import com.github.navikt.tbd_libs.rapids_and_rivers_api.MessageProblems
import com.github.navikt.tbd_libs.rapids_and_rivers_api.RapidsConnection
import io.micrometer.core.instrument.MeterRegistry
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.logstash.logback.argument.StructuredArguments.keyValue
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import org.slf4j.MDC

internal class AapRiver(
    rapidsConnection: RapidsConnection,
    private val aapClient: AapClient,
    private val behov: String
) :
    River.PacketListener {
    private companion object {
        private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")
        private val log = LoggerFactory.getLogger(AapRiver::class.java)
    }

    init {
        River(rapidsConnection).apply {
            precondition { it.requireAll("@behov", listOf(behov)) }
            precondition { it.forbid("@løsning") }
            validate { it.requireKey("@id") }
            validate { it.requireKey("fødselsnummer") }
            validate { it.requireKey("vedtaksperiodeId") }
            validate { it.require("$behov.periodeFom", JsonNode::asLocalDate) }
            validate { it.require("$behov.periodeTom", JsonNode::asLocalDate) }
        }.register(this)
    }

    override fun onError(problems: MessageProblems, context: MessageContext, metadata: MessageMetadata) {
        sikkerlogg.error("forstod ikke $behov:\n${problems.toExtendedReport()}")
    }

    override fun onPacket(packet: JsonMessage, context: MessageContext, metadata: MessageMetadata, meterRegistry: MeterRegistry) {
        val behovId = packet["@id"].asText()
        val vedtaksperiodeId = packet["vedtaksperiodeId"].asText()
        withMDC(
            mapOf(
                "behovId" to behovId,
                "vedtaksperiodeId" to vedtaksperiodeId
            )
        ) {
            try {
                info("løser behov {} for {}", keyValue("vedtaksperiodeId", vedtaksperiodeId))
                håndter(packet, context)
            } catch (err: Exception) {
                warn("feil ved behov {} for {}: ${err.message}", err)
            }
        }
    }

    private fun håndter(packet: JsonMessage, context: MessageContext) {
        val fødselsnummer = packet["fødselsnummer"].asText()
        val behovId = packet["@id"].asText()
        val fom = packet["$behov.periodeFom"].asLocalDate()
        val tom = packet["$behov.periodeTom"].asLocalDate()

        val json = runBlocking {
            aapClient.hentMaksimum(fødselsnummer, fom, tom, behovId)
        }

        json.fold(
            onSuccess = { aapResponse: AapClient.AapResponse ->
                sikkerlogg.info("Mottok svar fra AAP med følgende payload: $aapResponse")
                packet["@løsning"] = mapOf(
                    behov to mapOf(
                        "utbetalingsperioder" to
                            aapResponse.vedtak
                                .flatMap { aapRettighet ->
                                    aapRettighet.utbetaling.filter { it.periode.fraOgMedDato != null }
                                        .map { utbetaling ->
                                            val fom = LocalDate.parse(utbetaling.periode.fraOgMedDato!!)
                                            val tom = utbetaling.periode.tilOgMedDato?.let { LocalDate.parse(it) }?.takeIf { it >= fom } ?: LocalDate.now()
                                            mapOf(
                                                "fom" to fom,
                                                "tom" to tom
                                            )
                                        }
                                })
                )
                context.publish(packet.toJson())
                log.info("Besvarte behov {}", behovId)
                sikkerlogg.info(
                    "Besvarte behov {}:\n{}",
                    kv("id", behovId),
                    packet.toJson()
                )
            },
            onFailure = { t: Throwable ->
                "Fikk feil ved oppslag mot representasjon".also { message ->
                    log.error("$message, se sikkerlogg for detaljer")
                    sikkerlogg.error("$message: {}", t, t)
                }
                throw t
            }
        )
    }

    private fun withMDC(context: Map<String, String>, block: () -> Unit) {
        val contextMap = MDC.getCopyOfContextMap() ?: emptyMap()
        try {
            MDC.setContextMap(contextMap + context)
            block()
        } finally {
            MDC.setContextMap(contextMap)
        }
    }

    private fun info(format: String, vararg args: Any) {
        log.info(format, *args)
        sikkerlogg.info(format, *args)
    }

    private fun warn(format: String, vararg args: Any) {
        log.warn(format, *args)
        sikkerlogg.warn(format, *args)
    }
}
