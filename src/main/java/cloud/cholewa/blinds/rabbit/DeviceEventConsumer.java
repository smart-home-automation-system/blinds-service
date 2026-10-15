package cloud.cholewa.blinds.rabbit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

//Every event of every device arrives here - the queue is bound with "#", about 130 a minute: the
//status topics of the Shelly devices (routing key "<id>.status.<component>", the body as the
//device sent it) and the DeviceEvent of the Eaton devices from amx-service ("eaton.<gateway>.<point>").
//The skeleton only takes them off the queue; telling the blinds apart by the device registry comes
//with the first task that acts on them. Each one is logged at DEBUG only - at INFO the volume
//alone would drown the log
@Slf4j
@Service
public class DeviceEventConsumer {

    @RabbitListener(queues = "${rabbitmq.queue.name}")
    Mono<Void> consume(final Message message) {
        final String routingKey = message.getMessageProperties().getReceivedRoutingKey();

        return Mono.<Void>fromRunnable(() -> log.debug("Device event received with routing key: {}", routingKey))
            //an event that cannot be handled is logged and acknowledged: a Mono that errors is
            //redelivered at once, which for a message that fails the same way is a tight loop
            .onErrorResume(throwable -> {
                log.error("Error while consuming the device event {}", routingKey, throwable);
                return Mono.empty();
            })
            //the listener observation lives in a ThreadLocal, but the container subscribes in a way that
            //does not trigger the automatic capture, so the reactor context stays empty and traceId is
            //lost on the first thread switch; this captures it at subscribe, where it is still present
            .contextCapture();
    }
}
