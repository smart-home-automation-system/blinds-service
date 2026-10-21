package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.database.model.BlindStateEntity;
import cloud.cholewa.blinds.database.repository.BlindStateRepository;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindBinding;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.BlindStatus;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.CommandTarget;
import cloud.cholewa.blinds.model.Direction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

//The real state of every blind, whoever moved it: what the devices report (BlindEventRouter,
//BlindStatePoll, the Gen1 actions) is merged here into the state kept in memory, and a change is
//stored - a row only when the state changes, a reading that confirms it moves last_checked_at of
//the latest row (the presence-service shape). At the start the latest rows are read back once,
//before the first reading is applied; a database that cannot be read then is a WARN, and the state
//is rebuilt from the devices (the Gen1 poll within a minute, the Gen2 reconciliation within five).
//
//The memory is the truth while the pod runs; a write that fails leaves the database one change
//behind, which the next restart reads back and the next readings correct. Readings of one blind
//from two channels at once (a Gen1 action and the poll) are merged one after the other, and their
//rows are written in that order
@Slf4j
@Component
public class BlindStateTracker {

    //a change seen this long after a command of this service is that command's movement. An Eaton
    //actuator reports its start within a second or two; a Gen1 roller without actions only at the
    //next poll, and two polls lie more than their 60 s apart (fixedDelay counts from the end of a
    //pass, which may wait for devices that time out) - so twice that
    public static final Duration COMMAND_WINDOW = Duration.ofMinutes(2);
    //how long a command of a Gen2+ cover waits for the report that answers it (HAS-250): the cover
    //publishes a change at once. A Gen1 roller and an Eaton actuator get COMMAND_WINDOW - two polls
    public static final Duration GEN2_CONFIRM_WINDOW = Duration.ofSeconds(60);
    static final String EXTERNAL = "external";

    //the width of the column: the word of a device is not trusted to be short
    private static final int SOURCE_LENGTH = 50;

    private final BlindStateRepository repository;
    private final Clock clock;

    private final Map<Long, BlindStatus> statuses = new ConcurrentHashMap<>();
    private final Map<Long, IssuedCommand> commands = new ConcurrentHashMap<>();
    //the end of the last write of every blind: the next one waits for it, so the rows of one blind
    //are inserted in the order the states changed - the latest row (the highest id) is what a
    //restart reads back. Only the last one is held, never a chain of them
    private final Map<Long, Sinks.Empty<Void>> writes = new ConcurrentHashMap<>();
    //what the commands of every blind came to since the start (HAS-250), in memory only: a restart
    //knows of no command, and lastCommandConfirmed is left out of the reads until the next one
    private final Map<Long, Confirmations> confirmations = new ConcurrentHashMap<>();
    private final Mono<Void> restored;

    public BlindStateTracker(final BlindStateRepository repository, final Clock clock) {
        this.repository = repository;
        this.clock = clock;
        //cached: read once, by whatever needs the state first; never repeated, also after a failure
        this.restored = Flux.defer(repository::findLatestPerDevice)
            .doOnNext(row -> statuses.putIfAbsent(row.deviceId(), new BlindStatus(
                row.state(), row.position(), row.lastDirection(), row.startedAt(), row.source(), null)))
            .count()
            .doOnNext(count -> log.info("State of {} blinds restored from the database", count))
            .then()
            .onErrorResume(e -> {
                log.warn("The state of the blinds could not be restored, it is rebuilt from the devices: {}",
                    e.toString());
                return Mono.empty();
            })
            .cache();
    }

    /** The state of one blind; {@link BlindStatus#unknown()} for one nothing was heard of. */
    public Mono<BlindStatus> status(final long id) {
        return restored.then(Mono.fromSupplier(() -> statuses.getOrDefault(id, BlindStatus.unknown())));
    }

    /**
     * A command of this service is about to go out: the movement it starts is attributed to its
     * source. Noted before it is sent - a device may report the movement before it answers - and
     * kept when the call fails: a command that timed out may have moved the blind all the same.
     * <p>
     * A stop is the only command that explains the rest that ends a movement; any other leaves that
     * rest to whoever started the movement. The direction of an open or a close of an Eaton blind is
     * the movement the command waits for (HAS-291): its start may never be reported - see
     * {@link #decide}.
     * <p>
     * Every command waits for a report that answers it (HAS-250) - {@link #unconfirmed} once its
     * window has passed without one. A command of a Shelly blind already where it asks it to be has
     * nothing to wait for: a cover that does not move publishes nothing.
     *
     * @return the command, for {@link #awaitingMovement} and {@link #unconfirmed}
     */
    public IssuedCommand commanded(final Blind blind, final CommandSource source, final CommandTarget target) {
        final boolean eaton = blind.binding() instanceof BlindBinding.Eaton;
        final Direction expected = eaton ? target.direction() : null;
        final BlindStatus known = statuses.get(blind.id());
        final boolean alreadyThere = !eaton && known != null && target.answeredBy(known.state(), known.position());
        final Duration window = blind.binding() instanceof BlindBinding.ShellyGen2 ? GEN2_CONFIRM_WINDOW : COMMAND_WINDOW;
        final IssuedCommand command = new IssuedCommand(source.value(), target, expected, Instant.now(clock),
            !alreadyThere, window);
        final IssuedCommand replaced = commands.put(blind.id(), command);
        //the newer command takes the wait over: the older one is never reported unconfirmed
        if (replaced != null) {
            replaced.settle();
        }
        return command;
    }

    /**
     * Whether this command of an Eaton blind is still the blind's latest and no movement has been
     * reported since it was sent - the moment to ask the actuator for its status. Never for a stop,
     * a position or a blind of another kind.
     */
    public boolean awaitingMovement(final Blind blind, final IssuedCommand command) {
        return command.expected() != null && commands.get(blind.id()) == command && !command.settled();
    }

    /**
     * Ends the wait for this command once its window has passed. Reported when no report of the
     * device answered it and no newer command took its place - once; the state is left as it is,
     * and a movement reported later is still attributed to it while {@link #COMMAND_WINDOW} lasts.
     *
     * @return how many commands of the blind in a row went unconfirmed, this one included, and
     *         whether the household was told of that run; {@link Unconfirmed#NONE} when the command
     *         was answered, replaced or waits for nothing
     */
    public Unconfirmed unconfirmed(final Blind blind, final IssuedCommand command) {
        //settled here too, so a command is reported once
        if (!command.awaitsConfirmation() || !command.settle()) {
            return Unconfirmed.NONE;
        }
        final Confirmations after = confirmations.merge(blind.id(), new Confirmations(false, 1, false),
            (before, unused) -> new Confirmations(false, before.unconfirmedInARow() + 1, before.announced()));
        return new Unconfirmed(after.unconfirmedInARow(), after.announced());
    }

    /** The household was told the blind leaves its commands unconfirmed: not again until one is confirmed. */
    public void unconfirmedAnnounced(final Blind blind) {
        confirmations.computeIfPresent(blind.id(),
            (id, known) -> new Confirmations(known.lastConfirmed(), known.unconfirmedInARow(), true));
    }

    /**
     * Whether the last command of the blind was confirmed - {@code false} from the end of its window
     * until a command is confirmed or the device reports a movement; {@code null} when no command
     * was judged since the start.
     */
    public Boolean lastCommandConfirmed(final long id) {
        final Confirmations known = confirmations.get(id);
        return known == null ? null : known.lastConfirmed();
    }

    private void confirmed(final long id) {
        confirmations.put(id, new Confirmations(true, 0, false));
    }

    //a blind that moves is no longer one that leaves its commands unanswered, whoever moved it
    private void moved(final long id) {
        confirmations.computeIfPresent(id, (key, known) -> known.lastConfirmed() ? known : new Confirmations(true, 0, false));
    }

    public Mono<Void> observe(final Blind blind, final BlindReading reading) {
        return restored.then(Mono.defer(() -> {
            //cut to what the column keeps: a restart must read back the same time
            final LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
            final IssuedCommand command = pendingCommand(blind.id());
            final AtomicReference<BlindStatus> before = new AtomicReference<>();
            final AtomicReference<Decision> decision = new AtomicReference<>();
            final Sinks.Empty<Void> done = Sinks.empty();
            final AtomicReference<Sinks.Empty<Void>> last = new AtomicReference<>();

            //the merge and the place of its write in the queue of the blind are taken together
            statuses.compute(blind.id(), (id, previous) -> {
                final BlindStatus known = previous == null ? BlindStatus.unknown() : previous;
                before.set(known);
                decision.set(decide(known, reading, now, command == null ? null : command.pending()));
                last.set(writes.put(id, done));
                return decision.get().status();
            });

            //the change the command started, or a report of the blind where the command asks it to be -
            //a movement the state already had (a blind moving that way before the command) included:
            //nothing changed, and yet the command has its answer
            if (command != null && (decision.get().commandUsed() || command.target().answeredBy(reading.state(), reading.position()))
                && command.settle() && command.awaitsConfirmation()) {
                confirmed(blind.id());
            }
            if (decision.get().commandUsed()) {
                commands.remove(blind.id(), command);
            }
            if (decision.get().changed()) {
                moved(blind.id());
            }
            if (Boolean.FALSE.equals(before.get().reachable())) {
                log.info("Blind {} ({}) is reachable again", blind.id(), blind.name());
            }
            //every caller subscribes to what it gets; done is signalled however the write ends,
            //a cancelled one included, so one write that failed holds up none after it
            return (last.get() == null ? Mono.<Void>empty() : last.get().asMono())
                .then(Mono.defer(() -> write(blind, decision.get(), now)))
                .doFinally(signal -> done.tryEmitEmpty());
        }));
    }

    /** Reachability alone, without a state: the MQTT last will of a device, a poll that failed. */
    public Mono<Void> reachability(final Blind blind, final boolean reachable, final String reason) {
        return restored.then(Mono.fromRunnable(() -> {
            final AtomicReference<BlindStatus> before = new AtomicReference<>();
            statuses.compute(blind.id(), (id, previous) -> {
                final BlindStatus known = previous == null ? BlindStatus.unknown() : previous;
                before.set(known);
                return known.withReachable(reachable);
            });

            if (Boolean.valueOf(reachable).equals(before.get().reachable())) {
                return;
            }
            if (!reachable) {
                log.warn("Blind {} ({}) is not reachable: {}", blind.id(), blind.name(), reason);
            } else if (before.get().reachable() != null) {
                log.info("Blind {} ({}) is reachable again", blind.id(), blind.name());
            }
        }));
    }

    //Merges one reading into the state known before it. A state that differs is a change; so is a
    //position at rest that differs from the one known (the blind moved between two readings). The
    //rest confirms what is known and fills in what the reading adds - the position of a moving
    //blind, or one the last report did not have.
    //A report at rest without a position (a Gen1 roller that was not calibrated) cannot tell an end
    //from the middle: after a blind that reported reaching an end it confirms that end - when it
    //says the blind last moved towards it. A stop action says nothing of the direction, and one
    //that contradicts the end is a blind stopped on its way back: both are a stop, not the end.
    //An Eaton actuator does not always get the 70 status of its start through (HAS-291): a stop
    //without a position after an open or a close of this service, with no movement reported since,
    //is the end of a movement in the direction of the command - a stop command keeps its own meaning
    static Decision decide(
        final BlindStatus previous,
        final BlindReading reading,
        final LocalDateTime now,
        final PendingCommand command
    ) {
        if (command != null && command.expected() != null
            && reading.state() == BlindState.STOPPED && reading.position() == null) {
            return changed(BlindState.STOPPED, null, command.expected(), now, command.source(), true);
        }

        final boolean restAtTheEnd = reading.state() == BlindState.STOPPED && reading.position() == null
            && (previous.state() == BlindState.OPEN && reading.lastDirection() == Direction.OPEN
                || previous.state() == BlindState.CLOSED && reading.lastDirection() == Direction.CLOSE);
        final BlindState state = restAtTheEnd ? previous.state() : reading.state();
        final Direction direction = Stream.of(state.direction(), reading.lastDirection(), previous.lastDirection())
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        if (state != previous.state()) {
            //the rest that ends a movement belongs to whoever started it - unless a stop of this
            //service ended it; any other command is left for the movement it starts
            final boolean endOfMovement = previous.state() != null && previous.state().moving() && !state.moving();
            if (endOfMovement) {
                final boolean stopped = command != null && command.stop();
                return changed(state, reading.position(), direction, now,
                    stopped ? command.source() : previous.source(), stopped);
            }
            return changed(state, reading.position(), direction, now,
                command != null ? command.source() : deviceSource(reading), command != null);
        }

        final boolean movedUnseen = !state.moving() && previous.position() != null && reading.position() != null
            && !previous.position().equals(reading.position());
        if (movedUnseen) {
            return changed(state, reading.position(), direction, now,
                command != null ? command.source() : deviceSource(reading), command != null);
        }

        return new Decision(new BlindStatus(
            state,
            reading.position() != null ? reading.position() : previous.position(),
            direction,
            previous.movedAt(),
            previous.source(),
            true
        ), false, false);
    }

    private static Decision changed(
        final BlindState state,
        final Integer position,
        final Direction direction,
        final LocalDateTime now,
        final String source,
        final boolean commandUsed
    ) {
        return new Decision(new BlindStatus(state, position, direction, now, source, true), true, commandUsed);
    }

    private static String deviceSource(final BlindReading reading) {
        final String source = reading.deviceSource();
        if (source == null || source.isBlank()) {
            return EXTERNAL;
        }
        return source.length() > SOURCE_LENGTH ? source.substring(0, SOURCE_LENGTH) : source;
    }

    private IssuedCommand pendingCommand(final long id) {
        final IssuedCommand command = commands.get(id);
        if (command == null) {
            return null;
        }
        if (command.at().plus(COMMAND_WINDOW).isBefore(Instant.now(clock))) {
            commands.remove(id, command);
            return null;
        }
        return command;
    }

    private Mono<Void> write(final Blind blind, final Decision decision, final LocalDateTime now) {
        final BlindStatus status = decision.status();

        if (decision.changed()) {
            return repository.save(entity(blind, status, now))
                .doOnNext(saved -> log.info("Blind {} ({}) is {} since {}{}, moved by {}",
                    blind.id(), blind.name(), status.state().value(), now,
                    status.position() == null ? "" : " at " + status.position(), status.source()))
                .then();
        }

        return repository.touchLatest(blind.id(), now, status.position())
            .filter(updated -> updated == 0)
            //the memory knows a state the table has no row for (rows removed by hand)
            .flatMap(nothingUpdated -> {
                log.warn("No state row to confirm for blind {} ({}), opening a new one", blind.id(), blind.name());
                return repository.save(entity(blind, status, now));
            })
            .then();
    }

    private static BlindStateEntity entity(final Blind blind, final BlindStatus status, final LocalDateTime now) {
        return new BlindStateEntity(null, blind.id(), status.state(), status.position(), status.lastDirection(),
            status.source(), status.movedAt(), now);
    }

    record Decision(BlindStatus status, boolean changed, boolean commandUsed) {
    }

    /**
     * A command of this service that is still waiting for the change it starts, as the merge sees it.
     *
     * @param expected the direction of an open or a close of an Eaton blind as long as no movement
     *                 has been reported since the command; {@code null} otherwise
     */
    record PendingCommand(String source, boolean stop, Direction expected) {

        PendingCommand(final String source, final boolean stop) {
            this(source, stop, null);
        }
    }

    /**
     * What the unconfirmed command of a blind came to.
     *
     * @param inARow    how many commands of the blind in a row went unconfirmed, this one included;
     *                  0 when this one was not reported
     * @param announced whether the household was already told of this run
     */
    public record Unconfirmed(int inARow, boolean announced) {

        public static final Unconfirmed NONE = new Unconfirmed(0, false);

        public boolean reported() {
            return inARow > 0;
        }
    }

    private record Confirmations(boolean lastConfirmed, int unconfirmedInARow, boolean announced) {
    }

    /** A command as it was sent; the same object is asked about later, so a newer one is told apart. */
    public static final class IssuedCommand {

        private final String source;
        private final CommandTarget target;
        private final Direction expected;
        private final Instant at;
        private final boolean awaitsConfirmation;
        private final Duration window;
        private final AtomicBoolean settled = new AtomicBoolean();

        private IssuedCommand(
            final String source,
            final CommandTarget target,
            final Direction expected,
            final Instant at,
            final boolean awaitsConfirmation,
            final Duration window
        ) {
            this.source = source;
            this.target = target;
            this.expected = expected;
            this.at = at;
            this.awaitsConfirmation = awaitsConfirmation;
            this.window = window;
        }

        /** Whether the command waits for a movement of an Eaton blind, which may never be reported. */
        public boolean expectsMovement() {
            return expected != null;
        }

        /** Whether the command waits for a report that answers it; not when the blind already was where it asks. */
        public boolean awaitsConfirmation() {
            return awaitsConfirmation;
        }

        /** How long after it was sent the command waits for its answer. */
        public Duration window() {
            return window;
        }

        public String source() {
            return source;
        }

        public CommandTarget target() {
            return target;
        }

        Direction expected() {
            return expected;
        }

        Instant at() {
            return at;
        }

        boolean settled() {
            return settled.get();
        }

        //confirmed by a movement or a stop, or reported unconfirmed: either once. True the first time
        boolean settle() {
            return !settled.getAndSet(true);
        }

        PendingCommand pending() {
            return new PendingCommand(source, target.stop(), settled() ? null : expected);
        }
    }
}
