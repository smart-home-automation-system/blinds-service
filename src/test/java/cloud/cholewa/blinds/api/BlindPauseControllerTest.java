package cloud.cholewa.blinds.api;

import cloud.cholewa.blinds.config.ExceptionHandlerConfig;
import cloud.cholewa.blinds.engine.BlindSchedules;
import cloud.cholewa.blinds.engine.RunOutcome;
import cloud.cholewa.blinds.engine.ScheduleEvaluator.Verdict;
import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.model.schedule.BlindScheduleToday;
import cloud.cholewa.blinds.model.schedule.ScheduleAction;
import cloud.cholewa.blinds.model.schedule.ScheduleToday;
import cloud.cholewa.blinds.service.schedule.SchedulePauseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//The schedules of one blind and their pause (HAS-232): what the room view of the dashboard reads and writes
@Import(ExceptionHandlerConfig.class)
@WebFluxTest(BlindPauseController.class)
class BlindPauseControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private SchedulePauseService schedulePauseService;
    @MockitoBean
    private BlindSchedules blindSchedules;

    @Test
    void should_pause_and_resume_the_schedules_of_a_blind() {
        when(schedulePauseService.pause(17)).thenReturn(Mono.empty());
        when(schedulePauseService.resume(17)).thenReturn(Mono.empty());

        webTestClient.put().uri("/17/schedule-pause").exchange().expectStatus().isNoContent().expectBody().isEmpty();
        webTestClient.delete().uri("/17/schedule-pause").exchange().expectStatus().isNoContent().expectBody().isEmpty();

        verify(schedulePauseService).pause(17);
        verify(schedulePauseService).resume(17);
    }

    @Test
    void should_answer_404_with_the_code_for_an_unknown_blind() {
        when(schedulePauseService.pause(99)).thenReturn(Mono.error(BlindsException.notFound("no blind with id 99")));

        webTestClient.put().uri("/99/schedule-pause")
            .exchange()
            .expectStatus().isNotFound()
            .expectBody().jsonPath("$.errors[0].code").isEqualTo("NOT_FOUND_BLINDS");
    }

    //the contract of the room view: a schedule that ran for the blind, and one that has not yet
    @Test
    void should_answer_the_schedules_of_a_blind_today() {
        when(blindSchedules.of(17)).thenReturn(Mono.just(List.of(
            new BlindScheduleToday(5, "Close at dusk", true, ScheduleAction.CLOSE, null,
                new ScheduleToday.Window(LocalTime.of(16, 0), LocalTime.of(18, 0), null), Verdict.FIRED,
                LocalDateTime.of(2026, 10, 17, 17, 2, 3), RunOutcome.PAUSED),
            new BlindScheduleToday(6, "Half in the heat", false, ScheduleAction.POSITION, 30, null, Verdict.DISABLED,
                null, null))));

        webTestClient.get().uri("/17/schedules")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("""
                [
                  {"scheduleId": 5, "name": "Close at dusk", "enabled": true, "action": "close",
                   "window": {"from": "16:00", "to": "18:00"}, "decision": "%s",
                   "firedAt": "2026-10-17T17:02:03", "outcome": "paused"},
                  {"scheduleId": 6, "name": "Half in the heat", "enabled": false, "action": "position", "position": 30,
                   "decision": "%s"}
                ]
                """.formatted(Verdict.FIRED.value(), Verdict.DISABLED.value()), JsonCompareMode.STRICT);
    }
}
