package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.config.LocationProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

//The times of the sun in the zone of the house, checked against the published times for Krakow
//(timeanddate.com): within two minutes - the published ones are for the city, not this point
class SunCalendarTest {

    private static final LocationProperties KRAKOW = new LocationProperties(50.06, 19.94);
    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");

    private final SunCalendar sut = new SunCalendar(KRAKOW, Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), HOUSE));

    @Test
    void should_work_out_the_sunrise_and_the_sunset_in_local_time() {
        //Krakow, 16 October 2026 (CEST): sunrise 07:03, sunset 17:47
        final SunDay day = sut.of(LocalDate.of(2026, 10, 16));

        assertThat(day.sunrise()).isCloseTo(LocalTime.of(7, 3), within(2, ChronoUnit.MINUTES));
        assertThat(day.sunset()).isCloseTo(LocalTime.of(17, 47), within(2, ChronoUnit.MINUTES));
    }

    //the clocks go back on 25 October: the times are the winter time of that day
    @Test
    void should_follow_the_change_of_the_clocks() {
        //Krakow, 26 October 2026 (CET): sunrise 06:19, sunset 16:27
        final SunDay day = sut.of(LocalDate.of(2026, 10, 26));

        assertThat(day.sunrise()).isCloseTo(LocalTime.of(6, 19), within(2, ChronoUnit.MINUTES));
        assertThat(day.sunset()).isCloseTo(LocalTime.of(16, 27), within(2, ChronoUnit.MINUTES));
    }

    @Test
    void should_keep_the_times_of_a_day_and_work_out_the_next_one() {
        final SunDay first = sut.of(LocalDate.of(2026, 10, 16));

        assertThat(sut.of(LocalDate.of(2026, 10, 16))).isSameAs(first);
        assertThat(sut.of(LocalDate.of(2026, 10, 17)).date()).isEqualTo(LocalDate.of(2026, 10, 17));
    }

    @Test
    void should_round_to_the_minute() {
        final SunDay day = sut.of(LocalDate.of(2026, 6, 21));

        assertThat(day.sunrise().getSecond()).isZero();
        assertThat(day.sunset().getSecond()).isZero();
    }
}
