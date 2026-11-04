package io.paymentslab.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Records every log event while open. Loggers named in the constructor are lowered to TRACE for
 * the duration, so debug lines are checked as well as the ones production would print.
 */
final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>()
    private final Map<Logger, Level> previousLevels = [:]

    LogCapture(String... traceLoggers) {
        traceLoggers.each { name ->
            def logger = (Logger) LoggerFactory.getLogger(name)
            previousLevels[logger] = logger.level // null means "inherited"
            logger.level = Level.TRACE
        }
        appender.start()
        root.addAppender(appender)
    }

    /** A snapshot; server threads may still be appending. */
    List<ILoggingEvent> events() {
        // AppenderBase.doAppend synchronizes on the appender, so this copy is consistent.
        synchronized (appender) {
            new ArrayList<>(appender.list)
        }
    }

    /** Every event as text: level, logger, message, and any exception with its stack trace. */
    String text() {
        events().collect { event ->
            def line = "${event.level} ${event.loggerName} ${event.formattedMessage}"
            event.throwableProxy ? line + "\n" + ThrowableProxyUtil.asString(event.throwableProxy) : line
        }.join("\n")
    }

    @Override
    void close() {
        root.detachAppender(appender)
        appender.stop()
        previousLevels.each { logger, level -> logger.level = level }
    }
}
