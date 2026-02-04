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
    for (i in 0.until(depth)) {
        if (legalExceptions.any { it.isInstance(current) }) {
            return true
        }
        current = current.cause ?: break
    }
    return false
}
