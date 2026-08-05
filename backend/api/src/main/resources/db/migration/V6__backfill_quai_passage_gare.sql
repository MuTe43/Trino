-- Phase 4 job 2: backfill `quai` on passage_gare rows generated before
-- GenerateurCourses started assigning it (phase-2 code, changed in phase 4).
-- GenerateurCourses.genererPour is idempotent read-then-insert, so it will
-- never revisit these rows -- this one-off data fix is the only way to reach
-- them.
--
-- Formula matches GenerateurCourses.quaiPour exactly: (train_id + gare_id)
-- mod nb_quais, 1-based. Deterministic, no randomness, same inputs always
-- give the same platform. Gares with nb_quais null or 0 are left untouched
-- (quai stays null, which the board already renders as "--").
update passage_gare pg
set quai = (((c.train_id + pg.gare_id) % g.nb_quais) + 1)::text
from course c, gare g
where pg.course_id = c.id
  and pg.gare_id = g.id
  and pg.quai is null
  and g.nb_quais is not null
  and g.nb_quais > 0;
