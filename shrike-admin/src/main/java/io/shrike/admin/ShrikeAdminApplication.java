package io.shrike.admin;

import io.shrike.clients.ClientConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
}
