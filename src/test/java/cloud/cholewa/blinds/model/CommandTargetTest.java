package cloud.cholewa.blinds.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

//which report of a device answers a command (HAS-250)
class CommandTargetTest {

    @Test
    void should_answer_an_open_by_the_opening_or_the_end() {
        final CommandTarget open = CommandTarget.move(Direction.OPEN);

        assertThat(open.answeredBy(BlindState.OPENING, 10)).isTrue();
        assertThat(open.answeredBy(BlindState.OPEN, 100)).isTrue();
        assertThat(open.answeredBy(BlindState.CLOSING, 90)).isFalse();
        assertThat(open.answeredBy(BlindState.CLOSED, 0)).isFalse();
        assertThat(open.answeredBy(BlindState.STOPPED, 50)).isFalse();
    }

    @Test
    void should_answer_a_close_by_the_closing_or_the_end() {
        final CommandTarget close = CommandTarget.move(Direction.CLOSE);

        assertThat(close.answeredBy(BlindState.CLOSING, null)).isTrue();
        assertThat(close.answeredBy(BlindState.CLOSED, 0)).isTrue();
        assertThat(close.answeredBy(BlindState.OPEN, 100)).isFalse();
    }

    @Test
    void should_answer_a_stop_by_any_rest() {
        final CommandTarget stop = CommandTarget.halt();

        assertThat(stop.answeredBy(BlindState.STOPPED, null)).isTrue();
        assertThat(stop.answeredBy(BlindState.OPEN, 100)).isTrue();
        assertThat(stop.answeredBy(BlindState.CLOSING, 40)).isFalse();
    }

    @Test
    void should_answer_a_position_by_a_movement_or_the_position() {
        assertThat(CommandTarget.position(40).answeredBy(BlindState.OPENING, 20)).isTrue();
        assertThat(CommandTarget.position(40).answeredBy(BlindState.STOPPED, 40)).isTrue();
        assertThat(CommandTarget.position(40).answeredBy(BlindState.STOPPED, 41)).isFalse();
        assertThat(CommandTarget.position(100).answeredBy(BlindState.OPEN, null)).isTrue();
        assertThat(CommandTarget.position(0).answeredBy(BlindState.CLOSED, null)).isTrue();
        assertThat(CommandTarget.position(0).answeredBy(BlindState.OPEN, null)).isFalse();
    }

    @Test
    void should_answer_nothing_by_a_state_not_known() {
        assertThat(CommandTarget.halt().answeredBy(null, null)).isFalse();
        assertThat(CommandTarget.position(40).answeredBy(null, 40)).isFalse();
    }
}
