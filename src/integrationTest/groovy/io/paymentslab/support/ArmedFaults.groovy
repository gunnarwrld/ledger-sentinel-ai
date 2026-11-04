package io.paymentslab.support

import io.paymentslab.platform.FaultInjector

/** A fault injector for specs: arm a named point, and the next time the code reaches it, it throws once. */
final class ArmedFaults implements FaultInjector {

    static final class InjectedFault extends RuntimeException {
        InjectedFault(String point) {
            super("injected fault at " + point)
        }
    }

    private final Set<String> armed = Collections.synchronizedSet(new HashSet<String>())
    /** Every point reached while armed, in order. */
    final List<String> fired = Collections.synchronizedList([])

    void arm(String point) {
        armed << point
    }

    @Override
    void at(String point) {
        if (armed.remove(point)) {
            fired << point
            throw new InjectedFault(point)
        }
    }
}
