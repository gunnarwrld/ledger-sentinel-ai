package io.paymentslab.api

import io.paymentslab.platform.HealthService
import spock.lang.Requires
import spock.lang.Specification

/** Starts the real Spark/Jetty server without dependencies (no database either); no route is called. */
class HttpApiStartupSpec extends Specification {

    @Requires({ HttpApiStartupSpec.nonLoopbackIpv4() != null })
    def "listens only on the configured address"() {
        given:
        def api = new HttpApi("127.0.0.1", 0, unusedHealth(), null)
        api.start()

        expect: "loopback connects"
        canConnect(InetAddress.getLoopbackAddress(), api.port())

        and: "this machine's LAN address is refused"
        !canConnect(nonLoopbackIpv4(), api.port())

        cleanup:
        api.stop()
    }

    def "a port that is already taken makes start() throw instead of exiting the JVM"() {
        given:
        def occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        def api = new HttpApi("127.0.0.1", occupied.localPort, unusedHealth(), null)

        when:
        api.start()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("127.0.0.1:${occupied.localPort}")

        cleanup:
        occupied.close()
    }

    /** Never called: these specs only start and stop the server. */
    private static HealthService unusedHealth() {
        new HealthService(null, null)
    }

    private static boolean canConnect(InetAddress address, int port) {
        def socket = new Socket()
        try {
            socket.connect(new InetSocketAddress(address, port), 500)
            return true
        } catch (IOException ignored) {
            return false
        } finally {
            socket.close()
        }
    }

    static InetAddress nonLoopbackIpv4() {
        NetworkInterface.networkInterfaces()
                .filter { it.up && !it.loopback }
                .flatMap { it.inetAddresses() }
                .filter { it instanceof Inet4Address && !it.loopbackAddress && !it.linkLocalAddress }
                .findFirst()
                .orElse(null)
    }
}
