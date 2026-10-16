package cloud.cholewa.blinds.engine;

import cloud.cholewa.blinds.engine.DayTypes.TodayType;
import cloud.cholewa.home.model.DayType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The type of the day is asked of the calendar once a day; while the calendar cannot be read the
//weekday decides, and the calendar is asked again after ten minutes
class DayTypesTest {

    //11 November, Independence Day: a Wednesday the calendar knows is off
    private static final LocalDate HOLIDAY = LocalDate.of(2026, 11, 11);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T06:00:00Z"));
    private final CalendarClient calendarClient = mock(CalendarClient.class);
    private final DayTypes sut = new DayTypes(calendarClient, new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    @Test
    void should_ask_the_calendar_once_a_day() {
        when(calendarClient.typeOf(HOLIDAY)).thenReturn(Mono.just(DayType.DAY_OFF));

        sut.of(HOLIDAY).as(StepVerifier::create)
            .assertNext(today -> {
                assertThat(today.type()).isEqualTo(DayType.DAY_OFF);
                assertThat(today.fromCalendar()).isTrue();
            })
            .verifyComplete();
        now.set(now.get().plus(Duration.ofHours(5)));
        sut.of(HOLIDAY).map(TodayType::type).as(StepVerifier::create).expectNext(DayType.DAY_OFF).verifyComplete();

        verify(calendarClient, times(1)).typeOf(HOLIDAY);
    }

    @Test
    void should_take_the_weekday_while_the_calendar_cannot_be_read_and_ask_again_after_ten_minutes() {
        when(calendarClient.typeOf(HOLIDAY))
            .thenReturn(Mono.error(new InputCallException("database-service answered 503")))
            .thenReturn(Mono.just(DayType.DAY_OFF));

        sut.of(HOLIDAY).as(StepVerifier::create)
            .assertNext(today -> {
                assertThat(today.type()).isEqualTo(DayType.WORKDAY);
                assertThat(today.fromCalendar()).isFalse();
            })
            .verifyComplete();

        now.set(now.get().plus(Duration.ofMinutes(9)));
        sut.of(HOLIDAY).map(TodayType::fromCalendar).as(StepVerifier::create).expectNext(false).verifyComplete();
        verify(calendarClient, times(1)).typeOf(HOLIDAY);

        now.set(now.get().plus(Duration.ofMinutes(1)));
        sut.of(HOLIDAY).map(TodayType::type).as(StepVerifier::create).expectNext(DayType.DAY_OFF).verifyComplete();
        verify(calendarClient, times(2)).typeOf(HOLIDAY);
    }

    @Test
    void should_ask_again_on_the_next_day() {
        final LocalDate nextDay = HOLIDAY.plusDays(1);
        when(calendarClient.typeOf(HOLIDAY)).thenReturn(Mono.just(DayType.DAY_OFF));
        when(calendarClient.typeOf(nextDay)).thenReturn(Mono.just(DayType.WORKDAY));

        sut.of(HOLIDAY).map(TodayType::type).as(StepVerifier::create).expectNext(DayType.DAY_OFF).verifyComplete();
        sut.of(nextDay).map(TodayType::type).as(StepVerifier::create).expectNext(DayType.WORKDAY).verifyComplete();
    }
}
