package tn.sncft.trino.diffusion;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;

/**
 * Defers a fan-out to after the current transaction commits.
 *
 * <p>Publishing inline lets a subsequent rollback leave subscribers holding a
 * delta for state that was never persisted -- and a client reacting by
 * refetching the REST snapshot reads the pre-commit row and disagrees with the
 * delta it just applied. The failure is invisible in tests that never roll back.
 *
 * <p>Shared by the circulation and the incident publishers rather than written
 * twice: it is a correctness mechanism, and a second copy is a second thing to
 * forget when one of them changes. The payload and the channel list are built
 * eagerly by the caller, before entity state can move on; only the send waits.
 */
public final class PublicationApresCommit {

    private PublicationApresCommit() {
    }

    public static void publier(HubSse hubSse, Collection<String> canaux, String nomEvenement, Object donnees) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            hubSse.publier(canaux, nomEvenement, donnees);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                hubSse.publier(canaux, nomEvenement, donnees);
            }
        });
    }
}
