package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.CommandSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//no wind and frost protection yet (HAS-249 replaces this bean): every command goes through
@Component
public class NoProtection implements ProtectionGuard {

    @Override
    public Mono<Void> check(final Blind blind, final CommandSource source) {
        return Mono.empty();
    }
}
