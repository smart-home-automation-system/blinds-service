package cloud.cholewa.blinds.config;

import cloud.cholewa.shelly.http.ShellyHttpClientCustomizer;
import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

//One WebClient per service called, each with its own timeouts and its own pool. Built from the
//injected builder - a prototype, so each gets a fresh one - or the calls would be untraced
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
        final Logbook logbook,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, databaseConnectionProvider, logbook, properties.database());
    }

    @Bean
    WebClient weatherWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider weatherConnectionProvider,
        final Logbook logbook,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, weatherConnectionProvider, logbook, properties.weather());
    }

    @Bean
    WebClient amxWebClient(
        final WebClient.Builder webClientBuilder,
        final ConnectionProvider amxConnectionProvider,
        final Logbook logbook,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, amxConnectionProvider, logbook, properties.amx());
    }

    //the clients of shelly-client put their own connector on the builder, so the Logbook handler
    //of the clients above does not reach them; this does
    @Bean
    ShellyHttpClientCustomizer shellyCallsInTheLog(final Logbook logbook) {
        return http -> http.doOnConnected(connection -> connection.addHandlerLast(new LogbookClientHandler(logbook)));
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
        final Logbook logbook,
        final InternalServiceProperties.Endpoint endpoint
    ) {
        final HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(endpoint.connectTimeout().toMillis()))
            .responseTimeout(endpoint.responseTimeout())
            .doOnConnected(connection -> connection.addHandlerLast(new LogbookClientHandler(logbook)));

        return webClientBuilder
            .baseUrl(endpoint.url())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
