package cloud.cholewa.blinds.service;

import cloud.cholewa.blinds.model.Blind;
import cloud.cholewa.blinds.model.CommandSource;
import reactor.core.publisher.Mono;

/**
 * Whether the wind and frost protection lets a command through. Completes when it does, and errors
 * with {@code BlindsException.protectionActive} - a 409 {@code PROTECTION_ACTIVE} - when it does
 * not. The protection itself comes with HAS-249; until then {@link NoProtection} lets everything
 * through.
 */
public interface ProtectionGuard {

    Mono<Void> check(Blind blind, CommandSource source);
}
