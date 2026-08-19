package tn.sncft.trino.notification.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.sncft.trino.notification.domaine.Notification;

import java.time.OffsetDateTime;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * One passenger's notifications, scoped by the identity that owns the
     * subscription and by nothing else. There is deliberately no unscoped
     * listing: {@code GET /notifications} is a public endpoint, and a method
     * returning every row would be one refactor away from being called by it.
     */
    Page<Notification> findByAbonnementJetonAnonyme(String jetonAnonyme, Pageable pageable);

    Page<Notification> findByAbonnementUtilisateurId(Long utilisateurId, Pageable pageable);

    /**
     * Moves notifications left at {@code EN_ATTENTE} before {@code limite} to
     * {@code ECHEC}, with a stated cause.
     *
     * <p>Bulk update rather than load-mutate-save. The one measured case left 344
     * rows behind, and hydrating that many entities to change two fields each
     * would put the recovery on the startup path of the very process that has to
     * come up.
     *
     * <p>{@code envoyeAt} is deliberately left alone. It means "when delivery was
     * attempted", and for these rows it either never was or the attempt did not
     * finish; stamping it here would put a delivery time on a notification that
     * was never delivered.
     */
    @Modifying
    @Query("""
            update Notification n
               set n.statut = tn.sncft.trino.notification.domaine.StatutNotification.ECHEC,
                   n.erreur = :cause
             where n.statut = tn.sncft.trino.notification.domaine.StatutNotification.EN_ATTENTE
               and n.creeAt < :limite
            """)
    int marquerEnAttenteEnEchec(@Param("cause") String cause, @Param("limite") OffsetDateTime limite);
}
