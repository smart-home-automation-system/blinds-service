package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.config.LocationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.shredzone.commons.suncalc.SunTimes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

//The times of the sun at the house, worked out once a day - the first time the day is asked for -
//and kept until the next. Only the rise and the set within the day itself count (oneDay): a sunset
//the calculation would find after midnight is none of this day's
@Slf4j
@Component
@RequiredArgsConstructor
public class SunCalendar {

    private final LocationProperties location;
    private final Clock clock;

    //a few days around today: a night window takes its end from the sun of the next day
    private final Map<LocalDate, SunDay> cached = new ConcurrentHashMap<>();

    public SunDay of(final LocalDate date) {
        final SunDay current = cached.get(date);
        if (current != null) {
            return current;
        }

        final SunDay computed = compute(date);
        cached.keySet().removeIf(kept -> kept.isBefore(date.minusDays(2)) || kept.isAfter(date.plusDays(2)));
        cached.put(date, computed);
        log.info("Sun on {} at the house: sunrise {}, sunset {}", date, computed.sunrise(), computed.sunset());
        return computed;
    }

    private SunDay compute(final LocalDate date) {
        final SunTimes times = SunTimes.compute()
            .timezone(clock.getZone())
            .on(date)
            .at(location.latitude(), location.longitude())
            .oneDay()
            .execute();

        return new SunDay(date, toMinute(times.getRise(), date), toMinute(times.getSet(), date));
    }

    //rounded to the nearest minute, as the windows are; a time of another day is none of this one
    private static LocalTime toMinute(final ZonedDateTime time, final LocalDate date) {
        if (time == null) {
            return null;
        }
        final ZonedDateTime rounded = time.plusSeconds(30).truncatedTo(ChronoUnit.MINUTES);
        return rounded.toLocalDate().equals(date) ? rounded.toLocalTime() : null;
    }
}
