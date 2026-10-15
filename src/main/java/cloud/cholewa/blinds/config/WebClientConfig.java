package cloud.cholewa.blinds.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.netty.LogbookClientHandler;
import reactor.netty.http.client.HttpClient;

//One WebClient per service called, each with its own timeouts. Built from the injected builder -
//a prototype, so each gets a fresh one - or the calls would be untraced
@Configuration
public class WebClientConfig {

    @Bean
    WebClient databaseWebClient(
        final WebClient.Builder webClientBuilder,
        final Logbook logbook,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, logbook, properties.database());
    }

    @Bean
    WebClient weatherWebClient(
        final WebClient.Builder webClientBuilder,
        final Logbook logbook,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, logbook, properties.weather());
    }

    @Bean
    WebClient amxWebClient(
        final WebClient.Builder webClientBuilder,
        final Logbook logbook,
        final InternalServiceProperties properties
    ) {
        return webClient(webClientBuilder, logbook, properties.amx());
    }

    static WebClient webClient(
        final WebClient.Builder webClientBuilder,
        final Logbook logbook,
        final InternalServiceProperties.Endpoint endpoint
    ) {
        final HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) endpoint.connectTimeout().toMillis())
            .responseTimeout(endpoint.responseTimeout())
            .doOnConnected(connection -> connection.addHandlerLast(new LogbookClientHandler(logbook)));

        return webClientBuilder
            .baseUrl(endpoint.url())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
