-- Phase 9. When a notification row was created, as opposed to when its dispatch
-- was attempted.
--
-- notification carried only envoye_at, which Dispatcheur stamps immediately
-- before handing the row to its channel. That makes it unusable for finding rows
-- that are stuck: a notification the executor never picked up has envoye_at
-- null, and a notification whose JVM died mid-dispatch has an envoye_at that
-- says when the attempt began, not how long the row has been waiting.
--
-- Measured in phase 8: a taskkill of the API left 344 rows at EN_ATTENTE with no
-- way for the next process to tell them apart from work in flight. Dispatch
-- state lives entirely in an in-memory executor, so nothing recovers them and
-- nothing ever will without a creation time to age them against.
--
-- BalayeurNotification reads this column and nothing else does.

alter table notification
    add column cree_at timestamptz not null default now();

-- Existing rows get now() from the default, which back-dates nothing but is the
-- only honest answer available: the information was never recorded. They are all
-- older than any process that will read them, so the startup sweep -- which
-- compares against the instant the application started, not against a fixed
-- age -- still classifies them correctly.

-- Partial, because it only ever serves one query: the sweep looking for rows
-- left at EN_ATTENTE. ENVOYE and ECHEC rows are the overwhelming majority and
-- indexing them would cost every insert for a scan nobody performs.
create index idx_notification_en_attente
    on notification (cree_at)
    where statut = 'EN_ATTENTE';
