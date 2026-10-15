package cloud.cholewa.blinds.state;

import cloud.cholewa.blinds.database.model.BlindStateEntity;
import cloud.cholewa.blinds.database.repository.BlindStateRepository;
import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.BlindReading;
import cloud.cholewa.blinds.model.BlindState;
import cloud.cholewa.blinds.model.BlindStatus;
import cloud.cholewa.blinds.model.CommandSource;
import cloud.cholewa.blinds.model.Direction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
//from two channels at once (a Gen1 action and the poll) are merged one after the other, but their
//rows may be written in the other order - accepted for the same reason
@Slf4j
@Component
public class BlindStateTracker {

    //a change seen this long after a command of this service is that command's movement; an Eaton
    //actuator reports its start within a second or two, a Gen1 roller at the next poll
    static final Duration COMMAND_WINDOW = Duration.ofSeconds(60);
    static final String EXTERNAL = "external";

    //the width of the column: the word of a device is not trusted to be short
    private static final int SOURCE_LENGTH = 50;

    private final BlindStateRepository repository;
    private final Clock clock;

    private final Map<Long, BlindStatus> statuses = new ConcurrentHashMap<>();
    private final Map<Long, Command> commands = new ConcurrentHashMap<>();
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
     */
    public void commanded(final Blind blind, final CommandSource source) {
        commands.put(blind.id(), new Command(source.value(), Instant.now(clock)));
    }

    public Mono<Void> observe(final Blind blind, final BlindReading reading) {
        return restored.then(Mono.defer(() -> {
            final LocalDateTime now = LocalDateTime.now(clock);
            final Command command = pendingCommand(blind.id());
            final AtomicReference<BlindStatus> before = new AtomicReference<>();
            final AtomicReference<Decision> decision = new AtomicReference<>();

            statuses.compute(blind.id(), (id, previous) -> {
                final BlindStatus known = previous == null ? BlindStatus.unknown() : previous;
                before.set(known);
                decision.set(decide(known, reading, now, command == null ? null : command.source()));
                return decision.get().status();
            });

            if (decision.get().commandUsed()) {
                commands.remove(blind.id(), command);
            }
            if (Boolean.FALSE.equals(before.get().reachable())) {
                log.info("Blind {} ({}) is reachable again", blind.id(), blind.name());
            }
            return write(blind, decision.get(), now);
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
    //from the middle: after a blind that reported reaching an end it confirms that end
    static Decision decide(
        final BlindStatus previous,
        final BlindReading reading,
        final LocalDateTime now,
        final String commandSource
    ) {
        final BlindState state = reading.state() == BlindState.STOPPED && reading.position() == null
            && (previous.state() == BlindState.OPEN || previous.state() == BlindState.CLOSED)
            ? previous.state()
            : reading.state();
        final Direction direction = Stream.of(state.direction(), reading.lastDirection(), previous.lastDirection())
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);

        if (state != previous.state()) {
            //the rest that ends a movement belongs to whoever started it
            final boolean endOfMovement = previous.state() != null && previous.state().moving() && !state.moving();
            final String source = commandSource != null ? commandSource
                : endOfMovement ? previous.source()
                : deviceSource(reading);
            return changed(state, reading.position(), direction, now, source, commandSource != null);
        }

        final boolean movedUnseen = !state.moving() && previous.position() != null && reading.position() != null
            && !previous.position().equals(reading.position());
        if (movedUnseen) {
            return changed(state, reading.position(), direction, now,
                commandSource != null ? commandSource : deviceSource(reading), commandSource != null);
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

    private Command pendingCommand(final long id) {
        final Command command = commands.get(id);
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

    private record Command(String source, Instant at) {
    }
}
