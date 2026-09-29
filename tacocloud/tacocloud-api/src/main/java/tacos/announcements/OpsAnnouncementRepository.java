package tacos.announcements;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;

public interface OpsAnnouncementRepository
    extends ReactiveCrudRepository<OpsAnnouncement, String> {

  Flux<OpsAnnouncement> findByActiveTrueOrderByCreatedAtDesc();
}
