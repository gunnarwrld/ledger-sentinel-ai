package io.paymentslab.platform;

/**
 * Named points where specs can make the code fail on purpose, to prove what survives a failure
 * (DESIGN §10, crash points). Production wiring always uses {@link #NONE}.
 */
@FunctionalInterface
public interface FaultInjector {

    FaultInjector NONE = point -> {};

    /** Called when execution reaches {@code point}; may throw. */
    void at(String point);
}
