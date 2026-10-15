package cloud.cholewa.blinds.rabbit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

class DeviceEventConsumerTest {

    private final DeviceEventConsumer consumer = new DeviceEventConsumer();

    //an event that completes is acknowledged; one that errored would be redelivered at once
    @ParameterizedTest
    @ValueSource(strings = {"shellyplus1pm-a1b2c3.status.switch:0", "eaton.blinds.45", ""})
    void should_take_every_event_off_the_queue(final String routingKey) {
        final MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);

        StepVerifier.create(consumer.consume(new Message("{}".getBytes(StandardCharsets.UTF_8), properties)))
            .verifyComplete();
    }
}
