package cloud.cholewa.blinds.config;

import cloud.cholewa.blinds.error.BlindsException;
import cloud.cholewa.blinds.error.processor.BlindsExceptionProcessor;
import cloud.cholewa.commons.error.GlobalErrorExceptionHandler;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;

import java.util.Map;

//the error contract of cholewa-commons, with the processor of the commands that were not carried out
@Configuration
public class ExceptionHandlerConfig {

    @Bean
    @Order(-2)
    public GlobalErrorExceptionHandler globalErrorExceptionHandler(
        final ErrorAttributes errorAttributes,
        final WebProperties webProperties,
        final ApplicationContext applicationContext,
        final ServerCodecConfigurer serverCodecConfigurer
    ) {
        return new GlobalErrorExceptionHandler(
            errorAttributes, webProperties.getResources(), applicationContext, serverCodecConfigurer
        ).withCustomErrorProcessor(Map.of(BlindsException.class, new BlindsExceptionProcessor()));
    }
}
