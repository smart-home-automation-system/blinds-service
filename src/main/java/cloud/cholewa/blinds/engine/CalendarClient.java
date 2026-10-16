package cloud.cholewa.blinds.engine;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.home.model.CalendarDay;
import cloud.cholewa.home.model.DayType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

//The type of a day by the calendar of the house in database-service (HAS-224): its days off, the
//weekends and the Polish holidays. What a failing database-service says goes to the log; no body,
//or no entry for the day asked for, is a failed call - never a workday by default
@Slf4j
@Component
public class CalendarClient {

    private static final ParameterizedTypeReference<List<CalendarDay>> DAYS = new ParameterizedTypeReference<>() {
    };

    private final WebClient databaseWebClient;

    public CalendarClient(@Qualifier("databaseWebClient") final WebClient databaseWebClient) {
        this.databaseWebClient = databaseWebClient;
    }

    //the range is the day alone: from included, to not
    public Mono<DayType> typeOf(final LocalDate date) {
        return databaseWebClient
            .get()
            .uri(uriBuilder -> uriBuilder
                .path("/home/calendar/days")
                .queryParam("from", date)
                .queryParam("to", date.plusDays(1))
                .build()
            )
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
                .map(error -> {
                    log.warn("database-service answered {} to the read of the calendar: {}",
                        error.status().value(), error.errors());
                    return new InputCallException("database-service answered " + error.status().value());
                }))
            .bodyToMono(DAYS)
            .flatMap(days -> Mono.justOrEmpty(days.stream()
                .filter(Objects::nonNull)
                .filter(day -> date.equals(day.getDate()))
                .map(CalendarDay::getType)
                .filter(Objects::nonNull)
                .findFirst()))
            .switchIfEmpty(Mono.error(() -> new InputCallException("the calendar has no type for " + date)));
    }
}
