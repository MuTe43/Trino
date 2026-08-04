-- Phase 3: indexes only, no schema change. Sized against the queries this
-- phase actually introduced, not against every column someone might filter on
-- one day -- an unused index still costs a write on every ping.
--
-- What V4 already covers, and is therefore NOT repeated here:
--   course (date_service, statut)      -> /courses filters, DetecteurSilence
--   course (ligne_id, date_service)    -> /courses?ligneId=
--   passage_gare (course_id, ordre)    -> the unique constraint, which serves
--                                         the propagation walk and /passages
--   position_course (course_id, horodatage desc) -> /courses/{id}/positions

-- ---------------------------------------------------------------------
-- The station board: /gares/{id}/departs
-- ---------------------------------------------------------------------
-- Ordered by depart_estimee, not depart_theorique, so the index has to carry
-- the estimate as its second column or every board read degenerates into a
-- sort of the whole day's stops for that gare.
--
-- Partial, because a terminus row has no departure at all: that is roughly one
-- row per course excluded, and it keeps the index to the rows the board can
-- actually return.
create index idx_passage_gare_departs
    on passage_gare (gare_id, depart_estimee)
    where depart_estimee is not null;

-- Superseded by the index above, which has gare_id as its leading column and
-- so answers every lookup the single-column one did.
drop index if exists idx_passage_gare_id;

-- ---------------------------------------------------------------------
-- Deliberately not indexed
-- ---------------------------------------------------------------------
-- /recherche matches with ILIKE '%q%', which no btree can serve. The searched
-- tables are five lignes, twenty-five trains and forty gares; a sequential scan
-- over those is faster than maintaining pg_trgm GIN indexes, and adding the
-- extension would be infrastructure we cannot justify (decision 4).
--
-- DetecteurSilence additionally filters on derniere_position_at and
-- depart_theorique, but only after (date_service, statut) has already narrowed
-- the day to at most ~80 rows. Widening idx_course_date_statut to cover them
-- would buy nothing measurable and slow down every position ping, which writes
-- derniere_position_at.
