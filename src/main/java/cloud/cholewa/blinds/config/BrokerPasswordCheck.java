package cloud.cholewa.blinds.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.util.StringUtils;

import java.util.List;

//A copy of BrokerPasswordCheck of heating-service and boiler-service (up to three copies stay, see
//the Conventions of the organization).
//The two passwords of the broker come from the environment and have no default, so that a pod
//without them does not start. Left to Spring Boot it would: the binder keeps a placeholder it
//cannot resolve as its text, the password becomes the string "${notification-rabbitmq-password}",
//passes @NotBlank, the pod turns Ready, and the broker refuses the first notification - which is
//sent when something is already wrong (HAS-217 in heating-service).
//The environment itself is strict about the same placeholder, so asking it is the whole check. It
//is made before the first bean is created: a start that is going to be refused has opened no
//connection and run no migration by then.
//Never put the exception of the environment, or the value, into the message: both quote what was
//resolved so far, which for a password holding "${" is the password - and such a password does not
//resolve here either, Spring would read a placeholder into it wherever it is bound. For the same
//reason this is a plain exception and not Boot's InvalidConfigurationPropertyValueException: its
//failure analysis prints the configured value of the property
@Configuration(proxyBeanMethods = false)
public class BrokerPasswordCheck {

    //the property, and the variable application.yaml reads it from
    static final List<Password> PASSWORDS = List.of(
        //the listener on /device, and the second connection the notifications are published over
        new Password("spring.rabbitmq.password", "rabbitmq-password"),
        new Password("notification.password", "notification-rabbitmq-password")
    );

    @Bean
    static BeanFactoryPostProcessor brokerPasswordCheckProcessor(final Environment environment) {
        return beanFactory -> check(environment);
    }

    private static void check(final Environment environment) {
        final List<Password> missing = PASSWORDS.stream()
            .filter(password -> !hasValue(environment, password.property()))
            .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "No password of the broker: " + String.join(", ", missing.stream().map(Password::describe).toList()));
        }
    }

    private static boolean hasValue(final Environment environment, final String property) {
        try {
            return StringUtils.hasText(environment.getProperty(property));
        } catch (PlaceholderResolutionException e) {
            return false;
        }
    }

    record Password(String property, String variable) {

        private String describe() {
            return property + " has no value (set " + variable + ")";
        }
    }
}
