package cloud.cholewa.blinds.config;

import cloud.cholewa.commons.notification.NotificationConnection;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.commons.rabbit.RabbitConnectionNameAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//no broker is needed and none is reached: Spring AMQP connects with the first use of a template,
//and nothing here uses one. The port is one nothing listens on all the same
class NotificationRabbitConfigTest {

    private static final String SERVICE = "blinds-service";

    //the properties are set here, above the environment of the machine, so the test reads the
    //same on a workstation, in CI and in a container
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            RabbitAutoConfiguration.class, RabbitConnectionNameAutoConfiguration.class))
        .withUserConfiguration(NotificationRabbitConfig.class, Properties.class)
        .withPropertyValues(
            "spring.application.name=" + SERVICE,
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.username=device",
            "spring.rabbitmq.password=listenerSecret",
            "spring.rabbitmq.virtual-host=/device",
            "spring.rabbitmq.connection-timeout=10s",
            "spring.rabbitmq.requested-heartbeat=10s",
            "notification.virtual-host=/notification",
            "notification.username=notification",
            "notification.password=publisherSecret",
            "notification.exchange=notification",
            "notification.env=dev",
            "HOSTNAME=" + SERVICE + "-69bccdf7f9-xzfxt"
        );

    //a ConnectionFactory or a RabbitOperations bean of the second connection would make
    //RabbitAutoConfiguration back off, and the listener would be left without its connection
    @Test
    void should_keep_the_second_connection_out_of_the_beans_the_auto_configuration_backs_off_from() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ConnectionFactory.class);
            assertThat(context).hasSingleBean(RabbitOperations.class);
            assertThat(context).hasSingleBean(NotificationConnection.class);
            assertThat(context.getBeanNamesForType(NotificationPublisher.class))
                .containsExactly("notificationPublisher");

            final CachingConnectionFactory listenerConnection = context.getBean(CachingConnectionFactory.class);
            assertThat(listenerConnection.getVirtualHost()).isEqualTo("/device");
            assertThat(listenerConnection.getUsername()).isEqualTo("device");
            assertThat(context.getBean(RabbitOperations.class).getConnectionFactory()).isSameAs(listenerConnection);
            assertThat(notificationFactory(context)).isNotSameAs(listenerConnection);
        });
    }

    @Test
    void should_connect_the_publisher_to_the_virtual_host_of_the_notifications() {
        contextRunner.run(context -> {
            final CachingConnectionFactory connectionFactory = notificationFactory(context);

            assertThat(connectionFactory.getHost()).isEqualTo("localhost");
            assertThat(connectionFactory.getPort()).isEqualTo(1);
            assertThat(connectionFactory.getVirtualHost()).isEqualTo("/notification");
            assertThat(connectionFactory.getUsername()).isEqualTo("notification");
            assertThat(connectionFactory.getRabbitConnectionFactory().getPassword()).isEqualTo("publisherSecret");
            assertThat(connectionFactory.getRabbitConnectionFactory().getConnectionTimeout()).isEqualTo(10_000);
            assertThat(connectionFactory.getRabbitConnectionFactory().getRequestedHeartbeat()).isEqualTo(10);
        });
    }

    //a notification counts as sent only once the broker took it, so the publisher has to learn whether the
    //message reached a queue: correlated confirms, returns and the mandatory flag - and the
    //observation, without which a notification would not carry the trace of the pass that raised it
    @Test
    void should_publish_with_confirms_returns_the_mandatory_flag_and_observation() {
        contextRunner.run(context -> {
            final RabbitTemplate template = notificationTemplate(context);

            assertThat(notificationFactory(context).isPublisherConfirms()).isTrue();
            assertThat(notificationFactory(context).isPublisherReturns()).isTrue();
            assertThat(template.isMandatoryFor(new Message(new byte[0]))).isTrue();
            assertThat(ReflectionTestUtils.getField(template, "observationEnabled")).isEqualTo(true);
        });
    }

    @ParameterizedTest(name = "HOSTNAME [{0}] names the connections {1} and {1}/notification")
    @CsvSource({
        //a pod of this service: its name, which tells the old pod from the new one in a rollout
        SERVICE + "-5968757896-bghz5, " + SERVICE + "-5968757896-bghz5",
        //a workstation, a container id, a pod of another service, the bare name, an empty value
        "DESKTOP-4F2K, " + SERVICE + "-local",
        "3f2a9c1b7d4e, " + SERVICE + "-local",
        "other-service-5968757896-bghz5, " + SERVICE + "-local",
        SERVICE + ", " + SERVICE + "-local",
        "'', " + SERVICE + "-local"
    })
    void should_name_both_connections_after_the_pod(final String hostname, final String expected) {
        contextRunner
            .withPropertyValues("HOSTNAME=" + hostname)
            .run(context -> {
                assertThat(context.getBeanNamesForType(ConnectionNameStrategy.class))
                    .containsExactly("cholewaPodConnectionNameStrategy");
                assertThat(connectionName(context.getBean(CachingConnectionFactory.class))).isEqualTo(expected);
                assertThat(connectionName(notificationFactory(context))).isEqualTo(expected + "/notification");
            });
    }

    //the connection is not a bean, so nobody but this configuration can close it - and closed is
    //final: a publish during the shutdown must not open a connection nothing closes any more
    @Test
    void should_close_the_second_connection_with_the_context() {
        final CachingConnectionFactory[] connectionFactory = new CachingConnectionFactory[1];

        contextRunner.run(context -> connectionFactory[0] = notificationFactory(context));

        assertThatThrownBy(connectionFactory[0]::createConnection)
            .isInstanceOf(AmqpApplicationContextClosedException.class);
    }

    private static RabbitTemplate notificationTemplate(final ApplicationContext context) {
        return (RabbitTemplate) context.getBean(NotificationConnection.class).rabbitOperations();
    }

    private static CachingConnectionFactory notificationFactory(final ApplicationContext context) {
        return (CachingConnectionFactory) notificationTemplate(context).getConnectionFactory();
    }

    //the strategy of a connection factory has no public getter; asking it does not open anything
    private static String connectionName(final CachingConnectionFactory connectionFactory) {
        final ConnectionNameStrategy strategy =
            ReflectionTestUtils.invokeMethod(connectionFactory, "getConnectionNameStrategy");

        assertThat(strategy).isNotNull();
        return strategy.obtainNewConnectionName(connectionFactory);
    }

    @Configuration
    @EnableConfigurationProperties(NotificationConnectionProperties.class)
    static class Properties {
    }
}
