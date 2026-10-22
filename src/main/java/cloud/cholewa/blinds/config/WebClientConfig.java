package cloud.cholewa.blinds.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

//One WebClient per service called, each with its own timeouts and its own pool. Built from the
//injected builder - a prototype, so each gets a fresh one - or the calls would be untraced; and the
//builder carries Logbook's exchange filter (cholewa-commons >= 1.10.0), which logs every call with its
//traceId - the clients of shelly-client are built from it too, so nothing here adds a Logbook handler
//of its own (it would log every call twice, HAS-301)
@Configuration
public class WebClientConfig {

    //Without a limit an idle connection stays in the pool for ever: after a restart of the target,
    //or once conntrack has forgotten it, the next call is written onto a dead socket - and a POST
    //is never repeated (the lesson of the gateway, HAS-212). The same limits as there
    static final Duration MAX_IDLE_TIME = Duration.ofMinutes(2);
    static final Duration MAX_LIFE_TIME = Duration.ofMinutes(30);

    //Spring infers only close() and shutdown() as destroy methods, so the pools are named explicitly
    @Bean(destroyMethod = "dispose")
    ConnectionProvider databaseConnectionProvider() {
        return connectionProvider("database-service");
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider weatherConnectionProvider() {
        return connectionProvider("weather-service");
    }

    @Bean(destroyMethod = "dispose")
    ConnectionProvider amxConnectionProvider() {
        return connectionProvider("amx-service");
    }

    @Bean
    WebClient databaseWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider databaseConnectionProvider,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, databaseConnectionProvider, properties.database());
    }

    @Bean
    WebClient weatherWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider weatherConnectionProvider,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, weatherConnectionProvider, properties.weather());
    }

    @Bean
    WebClient amxWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider amxConnectionProvider,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, amxConnectionProvider, properties.amx());
    }

    static ConnectionProvider connectionProvider(final String name) {
        return ConnectionProvider.builder(name)
            .maxIdleTime(MAX_IDLE_TIME)
            .maxLifeTime(MAX_LIFE_TIME)
            .build();
    }

    static WebClient webClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider connectionProvider,
        final InternalServiceProperties.Endpoint endpoint
    ) {
        final HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(endpoint.connectTimeout().toMillis()));

        return webClientBuilder
            .baseUrl(endpoint.url())
            .clientConnector(new ReactorClientHttpConnector(httpClient.responseTimeout(endpoint.responseTimeout())))
            .build();
    }
}
