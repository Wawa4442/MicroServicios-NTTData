package tacos.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.ReactiveTransactionManager;

/**
 * Enables reactive Mongo transactions (TC-29).
 *
 * <p>The order and its outbox row commit together only when Mongo runs as a
 * replica set (transactions are unavailable on a standalone server). Local
 * development must start Mongo with {@code --replSet}; the single-node
 * replica-set recipe is documented in {@code LABORATORIO_5} and the relay
 * test suite verifies the rollback shape without requiring a broker.
 */
@Configuration
@EnableScheduling
public class MongoTransactionConfig {

  @Bean
  public ReactiveTransactionManager reactiveTransactionManager(
      ReactiveMongoDatabaseFactory factory) {
    return new ReactiveMongoTransactionManager(factory);
  }
}
