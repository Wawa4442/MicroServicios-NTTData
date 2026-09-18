package tacos.payment;

import reactor.core.publisher.Mono;

/**
 * Hexagonal port for turning raw card data into an opaque token.
 * Implementations must never retain or return the PAN or the CVV: the only
 * thing that may be stored downstream is a {@link TokenizedCard}.
 */
public interface PaymentGateway {

  Mono<TokenizedCard> tokenize(String ccNumber, String ccCVV, String expiration);

}