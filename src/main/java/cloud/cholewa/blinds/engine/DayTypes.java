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
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

//The type of today, asked of the calendar once a day and kept. When the calendar cannot be read the
//day is judged by the weekday alone - Saturday and Sunday off, a holiday or a day off of the
//household then counts as a workday - with a WARN, and the calendar is asked again after
//RETRY_AFTER: a database-service away for a moment must not cost the whole day
@Slf4j
@Component
@RequiredArgsConstructor
public class DayTypes {

    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private final CalendarClient calendarClient;
    private final Clock clock;

    //a few days around today: a window that opened yesterday is judged by the type of yesterday
    private final Map<LocalDate, TodayType> cached = new ConcurrentHashMap<>();

    /**
     * The type of a day and where it comes from: {@code fromCalendar} false when it is the weekday
     * alone. {@code askedAt} is when the calendar was last asked.
     */
    public record TodayType(LocalDate date, DayType type, boolean fromCalendar, Instant askedAt) {
    }

    //never fails
    public Mono<TodayType> of(final LocalDate date) {
        return Mono.defer(() -> {
            final TodayType current = cached.get(date);
            if (current != null
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
                .doOnNext(day -> {
                    cached.keySet().removeIf(kept -> kept.isBefore(date.minusDays(2)) || kept.isAfter(date.plusDays(2)));
                    cached.put(date, day);
                });
        });
    }

    static DayType byWeekday(final LocalDate date) {
        return WEEKEND.contains(date.getDayOfWeek()) ? DayType.DAY_OFF : DayType.WORKDAY;
    }
}
