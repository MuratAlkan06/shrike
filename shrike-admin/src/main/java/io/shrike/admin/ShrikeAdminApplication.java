package io.shrike.admin;

import io.shrike.clients.ClientConfig;
import org.apache.catalina.Valve;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * The read-only admin facade: a small HTTP server that answers three GET endpoints in JSON by asking a
 * running broker the same questions any other client could ask it.
 *
 * <p><strong>It never reads the broker's data directory.</strong> Every number it reports arrives over
 * the TCP protocol, through {@code shrike-clients}, from a broker that is still the only owner of its
 * files. DESIGN.md records that decision and the alternative — parsing the data directory read-only —
 * that was turned down.
 *
 * <p>This is also the only module in the repository that names Spring, which is why the framework's
 * two entry points into this package, the component scan and the properties binding, are both started
 * from here rather than found by scanning something wider.
 */
@SpringBootApplication
@EnableConfigurationProperties(BrokerTarget.class)
public class ShrikeAdminApplication {

    /**
     * @param args the arguments Spring Boot reads its configuration overrides from
     */
    public static void main(String[] args) {
        SpringApplication.run(ShrikeAdminApplication.class, args);
    }

    /**
     * The broker's address, validated once at startup instead of once per request. A port that no
     * socket could bind fails the start here, where an operator is watching, rather than inside the
     * first request that tries to use it.
     *
     * @param broker where the configuration says the broker listens
     * @return the configuration every endpoint opens its connections with
     */
    @Bean
    ClientConfig brokerClientConfig(BrokerTarget broker) {
        return broker.toClientConfig();
    }

    /**
     * The container's own error page, taken out rather than trimmed.
     *
     * <p>{@link ErrorEndpoint} owns every failure that reaches the servlet stack. Some never do: a
     * request target Tomcat refuses while it is still parsing bytes — a broken percent-escape, a
     * request line longer than the header buffer — is settled before there is a servlet to forward to,
     * and what answers one is Tomcat's {@code ErrorReportValve}, which writes an HTML page. A caller
     * that sent bytes this server would not parse is owed the status code and nothing else.
     *
     * <p>Turning that valve's {@code showReport} and {@code showServerInfo} off is not enough, and
     * Spring Boot has already done it: whenever {@code server.error.include-stacktrace} is
     * {@code never}, which is pinned in {@code application.properties}, Boot adds a valve with both
     * flags cleared. Those two only drop the report table and the version footer — the page around
     * them, a title and an {@code h1} naming the status, is written regardless. So the valve is
     * removed from the host's pipeline instead, and {@code errorReportValveClass} is emptied so that
     * {@code StandardHost} does not install a fresh one as it starts. With no valve to write a body,
     * the answer is the status line and nothing after it.
     *
     * <p>Nothing else depended on it: the error pages a servlet application registers are dispatched
     * by {@code StandardHostValve}, which is what carries {@code /WEB-INF} and every other refusal
     * from inside the container to {@code /error}, and therefore to {@link ErrorEndpoint}. This
     * customizer is ordered last on purpose — Boot's own runs at order 0 — because a valve cannot be
     * removed before it has been added. The cast is unguarded for the same reason the order is
     * declared: a host that is not a {@code StandardHost} is an embedded server this code has never
     * seen, and finding that out when the process starts is better than finding it out from a page a
     * caller was shown.
     *
     * @return the customizer Spring Boot applies to the embedded server before it starts
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerErrorReports() {
        return factory -> factory.addContextCustomizers(context -> {
            StandardHost host = (StandardHost) context.getParent();
            for (Valve valve : host.getPipeline().getValves()) {
                if (valve instanceof ErrorReportValve) {
                    host.getPipeline().removeValve(valve);
                }
            }
            host.setErrorReportValveClass("");
        });
    }
}
