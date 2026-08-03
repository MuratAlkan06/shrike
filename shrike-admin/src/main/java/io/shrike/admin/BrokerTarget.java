package io.shrike.admin;

import io.shrike.clients.ClientConfig;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the broker this facade reads is listening, bound from {@code shrike.broker.*}.
 *
 * <p>It is the whole of this facade's configuration surface: there is no data directory here, and no
 * path of any kind, because the facade holds none of the broker's state and reads none of its files.
 *
 * @param host the host the broker listens on
 * @param port the port it listens on
 */
@ConfigurationProperties(prefix = "shrike.broker")
public record BrokerTarget(String host, int port) {

    public BrokerTarget {
        Objects.requireNonNull(host, "host");
    }

    /**
     * @return the client configuration for this broker, with every client default. Building it is what
     *         checks the host and the port, so a configuration that cannot name a broker is refused
     *         where it is read rather than where it is used
     */
    public ClientConfig toClientConfig() {
        return ClientConfig.defaults(host, port);
    }
}
