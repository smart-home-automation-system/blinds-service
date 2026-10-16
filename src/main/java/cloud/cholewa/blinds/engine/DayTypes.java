package cloud.cholewa.blinds.engine;

import cloud.cholewa.home.model.DayType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

//The type of today, asked of the calendar once a day and kept. When the calendar cannot be read the
//day is judged by the weekday alone - Saturday and Sunday off, a holiday or a day off of the
//household then counts as a workday - with a WARN, and the calendar is asked again after
//RETRY_AFTER: a database-service away for a moment must not cost the whole day
@Slf4j
@Component
@RequiredArgsConstructor
public class DayTypes {

    static final Duration RETRY_AFTER = Duration.ofMinutes(10);

    private final CalendarClient calendarClient;
    private final Clock clock;

    private final AtomicReference<TodayType> cached = new AtomicReference<>();

    /**
     * The type of a day and where it comes from: {@code fromCalendar} false when it is the weekday
     * alone. {@code askedAt} is when the calendar was last asked.
     */
    public record TodayType(LocalDate date, DayType type, boolean fromCalendar, Instant askedAt) {
    }

    //never fails
    public Mono<TodayType> of(final LocalDate date) {
        return Mono.defer(() -> {
            final TodayType current = cached.get();
            if (current != null && current.date().equals(date)
                && (current.fromCalendar() || clock.instant().isBefore(current.askedAt().plus(RETRY_AFTER)))) {
                return Mono.just(current);
            }

            return calendarClient.typeOf(date)
                .map(type -> new TodayType(date, type, true, clock.instant()))
                .doOnNext(today -> log.info("Day {} is a {} by the calendar of the house", date, today.type().getValue()))
                .onErrorResume(e -> {
                    final TodayType weekday = new TodayType(date, byWeekday(date), false, clock.instant());
                    log.warn("The calendar of {} could not be read ({}), the day counts as a {} by its weekday",
                        date, e.toString(), weekday.type().getValue());
                    return Mono.just(weekday);
                })
                .doOnNext(cached::set);
        });
    }

    static DayType byWeekday(final LocalDate date) {
        final DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? DayType.DAY_OFF : DayType.WORKDAY;
    }
}
