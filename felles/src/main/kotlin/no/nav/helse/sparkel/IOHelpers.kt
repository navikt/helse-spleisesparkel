package no.nav.helse.sparkel

import kotlinx.coroutines.delay
import net.logstash.logback.argument.StructuredArguments.keyValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlin.reflect.KClass

private val log: Logger = LoggerFactory.getLogger("tjenestekall")

/**
 * Kjører [block] og forsøker på nytt dersom kallet feiler med en tillatt feiltype.
 *
 * Funksjonen prøver én gang per verdi i [retryIntervals]. Dersom et forsøk feiler med
 * en feil som matcher en av [legalExceptions], eller en av dens underliggende årsaker
 * innenfor [exceptionCausedByDepth], logges feilen og neste forsøk utsettes med det
 * angitte intervallet. Feil som ikke regnes som retrybare kastes umiddelbart videre.
 * Etter at alle ventetidene er brukt opp, gjøres ett siste forsøk uten ytterligere delay.
 *
 * @param callName navn på tjenestekallet som brukes i logging.
 * @param legalExceptions feiltyper som skal regnes som retrybare.
 * @param retryIntervals ventetid i millisekunder mellom hvert nytt forsøk.
 * @param exceptionCausedByDepth hvor dypt i årsakskjeden det skal sjekkes etter retrybare feil.
 * @param block suspenderende kode som skal kjøres.
 * @return resultatet fra [block] dersom et forsøk lykkes.
 * @throws Throwable dersom [block] feiler med en ikke-retrybar feil, eller alle forsøk feiler.
 */
suspend fun <T> retry(
    callName: String,
    vararg legalExceptions: KClass<out Throwable> = arrayOf(
        IOException::class,
        ClosedReceiveChannelException::class,
        SSLHandshakeException::class,
    ),
    retryIntervals: Array<Long> = arrayOf(500, 1000, 3000, 5000, 10000),
    exceptionCausedByDepth: Int = 3,
    block: suspend () -> T
): T {
    for (interval in retryIntervals) {
        try {
            return block()
        } catch (e: Throwable) {
            if (!isCausedBy(e, exceptionCausedByDepth, legalExceptions)) {
                throw e
            }
            log.warn("Failed to execute {}, retrying in $interval ms", keyValue("callName", callName), e)
        }
        delay(interval)
    }
    return block()
}

private fun isCausedBy(
    throwable: Throwable,
    depth: Int,
    legalExceptions: Array<out KClass<out Throwable>>
): Boolean {
    var current: Throwable = throwable
    repeat(depth) {
        if (legalExceptions.any { it.isInstance(current) }) {
            return true
        }
        current = current.cause ?: return@repeat
    }
    return false
}
