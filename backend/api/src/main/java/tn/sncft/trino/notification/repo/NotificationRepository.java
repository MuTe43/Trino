package tn.sncft.trino.notification.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import tn.sncft.trino.notification.domaine.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * One passenger's notifications, scoped by the identity that owns the
     * subscription and by nothing else. There is deliberately no unscoped
     * listing: {@code GET /notifications} is a public endpoint, and a method
     * returning every row would be one refactor away from being called by it.
     */
    Page<Notification> findByAbonnementJetonAnonyme(String jetonAnonyme, Pageable pageable);

    Page<Notification> findByAbonnementUtilisateurId(Long utilisateurId, Pageable pageable);
}
