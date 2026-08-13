package io.shrike.admin;

import io.shrike.clients.ClientConfig;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the broker this facade reads is listening and how long it gets to answer, bound from
 * {@code shrike.broker.*}.
 *
 * <p>It is the whole of this facade's configuration surface: there is no data directory here, and no
 * path of any kind, because the facade holds none of the broker's state and reads none of its files.
 *
 * @param host              the host the broker listens on
 * @param port              the port it listens on
 * @param readTimeoutMillis how long the broker gets to answer a describe. It is the client's read
 *                          timeout, sized for what this facade actually asks: every one of its
 *                          questions is a describe the broker answers out of memory, so the client
 *                          default of thirty seconds — a margin sized for a fetch that has just
 *                          finished waiting — would be sixteen threads spending half a minute each on
 *                          a broker that is not going to answer. It is set in
 *                          {@code application.properties}; a facade whose configuration leaves it out
 *                          altogether does not start, because {@link ClientConfig} refuses a timeout
 *                          of zero
 */
@ConfigurationProperties(prefix = "shrike.broker")
public record BrokerTarget(String host, int port, int readTimeoutMillis) {

    public BrokerTarget {
        Objects.requireNonNull(host, "host");
    }

    /**
     * @return the client configuration for this broker: this facade's own read timeout, and the client
     *         defaults for everything else. Building it is what checks the host, the port, and the
     *         timeout, so a configuration that cannot name a broker or bound an answer is refused
     *         where it is read rather than where it is used
     */
    public ClientConfig toClientConfig() {
        return new ClientConfig(host, port, ClientConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS, readTimeoutMillis,
                ClientConfig.DEFAULT_MAX_RESPONSE_BYTES);
    }
}
