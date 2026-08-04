-- Phase 0 seed: real SNCFT référentiel (gares, lignes, desserte, trains).
-- Coordinates are OpenStreetMap-derived; where the exact platform/site was
-- uncertain the town/city centre point was used instead (see phase writeup).
-- No schema changes in this file.
--
-- AMENDED IN PHASE 2 -- deliberate exception to the "applied migrations are
-- immutable" invariant, authorised by docs/phases/phase-2.md. This is
-- unreleased seed data on a rebuildable local database, so the fix was made
-- here and the database rebuilt with `docker compose down -v` rather than
-- adding a corrective migration that would clutter the history for good.
-- V1 was NOT touched. Do not treat this as precedent: it applies to seed data
-- in V2 only. What changed: train speeds capped to their ligne's
-- vitesse_max_kmh, L5 stop order corrected, all five traces regenerated from
-- the stops. See the comments at each site.

-- ---------------------------------------------------------------------
-- gares (39)
-- ---------------------------------------------------------------------
insert into gare (code, nom, region, latitude, longitude, nb_quais, actif) values
    ('TUN',  'Tunis Ville',      'Tunis',      36.797500, 10.183900, 8, true),
    ('RAD',  'Radès',            'Ben Arous',  36.766700, 10.283300, 2, true),
    ('EZZ',  'Ez Zahra',         'Ben Arous',  36.742200, 10.316700, 2, true),
    ('HLIF', 'Hammam Lif',       'Ben Arous',  36.733300, 10.333300, 2, true),
    ('HCHO', 'Hammam Chott',     'Ben Arous',  36.716700, 10.366700, 1, true),
    ('BCED', 'Borj Cedria',      'Ben Arous',  36.700000, 10.416700, 2, true),
    ('GRB',  'Grombalia',        'Nabeul',     36.616700, 10.500000, 2, true),
    ('BARG', 'Bou Argoub',       'Nabeul',     36.533300, 10.516700, 1, true),
    ('BBR',  'Bir Bouregba',     'Nabeul',     36.466700, 10.566700, 3, true),
    ('ENF',  'Enfidha',          'Sousse',     36.133300, 10.383300, 2, true),
    ('KKEB', 'Kalaâ Kebira',     'Sousse',     35.883300, 10.533300, 1, true),
    ('AKO',  'Akouda',           'Sousse',     35.866700, 10.566700, 1, true),
    ('SOU',  'Sousse Ville',     'Sousse',     35.825600, 10.608400, 6, true),
    ('MSK',  'Msaken',           'Sousse',     35.733300, 10.583300, 2, true),
    ('ELJ',  'El Jem',           'Mahdia',     35.297200, 10.713900, 2, true),
    ('SFX',  'Sfax Ville',       'Sfax',       34.740600, 10.760300, 6, true),
    ('MHR',  'Mahares',          'Sfax',       34.533300, 10.500000, 1, true),
    ('SKH',  'Skhira',           'Gabès',      34.293900, 10.086400, 1, true),
    ('GAB',  'Gabès Ville',      'Gabès',      33.881500, 10.098200, 4, true),
    ('MAN',  'Manouba',          'Manouba',    36.808300, 10.096900, 1, true),
    ('OUE',  'Oued Ellil',       'Manouba',    36.850000, 10.033300, 1, true),
    ('DJD',  'Djedeida',         'Manouba',    36.850000, 9.933300,  1, true),
    ('MAT',  'Mateur',           'Bizerte',    37.039700, 9.665600,  2, true),
    ('ELA',  'El Alia',          'Bizerte',    37.166700, 10.050000, 1, true),
    ('BIZ',  'Bizerte Ville',    'Bizerte',    37.274400, 9.873900,  3, true),
    ('TEB',  'Tebourba',         'Manouba',    36.833300, 9.833300,  1, true),
    ('MEJ',  'Mejez el Bab',     'Béja',       36.650000, 9.616700,  1, true),
    ('TES',  'Testour',          'Béja',       36.550000, 9.433300,  1, true),
    ('BEJ',  'Béja Ville',       'Béja',       36.725600, 9.181700,  2, true),
    ('KRB',  'Le Krib',          'Siliana',    36.483300, 9.150000,  1, true),
    ('SER',  'Sers',             'Le Kef',     36.116700, 9.216700,  1, true),
    ('KKH',  'Kalaâ Khasba',     'Kasserine',  35.566700, 8.800000,  1, true),
    ('HSOU', 'Hammam Sousse',    'Sousse',     35.859200, 10.596400, 2, true),
    ('MON',  'Monastir Ville',   'Monastir',   35.764300, 10.811300, 3, true),
    ('SKN',  'Skanès',           'Monastir',   35.750000, 10.783300, 1, true),
    ('BEK',  'Bekalta',          'Monastir',   35.616900, 11.034200, 1, true),
    ('TBL',  'Teboulba',         'Monastir',   35.667200, 11.034200, 1, true),
    ('KSE',  'Ksour Essef',      'Mahdia',     35.416700, 10.983300, 1, true),
    ('MAH',  'Mahdia Ville',     'Mahdia',     35.504700, 11.062200, 2, true);

-- ---------------------------------------------------------------------
-- lignes (5)
-- ---------------------------------------------------------------------
-- `trace` is generated so the polyline passes exactly through every stop of
-- the ligne's desserte, in `ordre`, with interpolated points between stops.
-- That is what lets GeometrieLigne anchor a stop's pk_km to a trace vertex:
-- a hand-drawn polyline drifts away from the chainage and puts trains between
-- the wrong stations. `distance_km` stays the real rail chainage (it is what
-- the timetable offsets were derived from), so it is NOT the polyline's own
-- length -- the two scales are mapped anchor to anchor, never assumed equal.
insert into ligne (code, nom, distance_km, vitesse_max_kmh, temps_theorique_min, trace, actif) values
    ('L1-TSFG', 'Tunis - Sousse - Sfax - Gabès', 370.00, 120, 330,
     '[[10.1839,36.7975],[10.2586,36.7654],[10.3333,36.7333],[10.41665,36.675],[10.5,36.6167],[10.50835,36.575],[10.5167,36.5333],[10.5417,36.5],[10.5667,36.4667],[10.52085,36.38335],[10.475,36.3],[10.42915,36.21665],[10.3833,36.1333],[10.4333,36.049967],[10.4833,35.966633],[10.5333,35.8833],[10.55,35.875],[10.5667,35.8667],[10.58755,35.84615],[10.6084,35.8256],[10.59585,35.77945],[10.5833,35.7333],[10.60942,35.64608],[10.63554,35.55886],[10.66166,35.47164],[10.68778,35.38442],[10.7139,35.2972],[10.72318,35.18588],[10.73246,35.07456],[10.74174,34.96324],[10.75102,34.85192],[10.7603,34.7406],[10.673533,34.6715],[10.586767,34.6024],[10.5,34.5333],[10.3966,34.47345],[10.2932,34.4136],[10.1898,34.35375],[10.0864,34.2939],[10.08935,34.1908],[10.0923,34.0877],[10.09525,33.9846],[10.0982,33.8815]]'::jsonb,
     true),
    ('L2-TBZ', 'Tunis - Bizerte', 90.00, 90, 105,
     '[[10.1839,36.7975],[10.1549,36.8011],[10.1259,36.8047],[10.0969,36.8083],[10.0757,36.8222],[10.0545,36.8361],[10.0333,36.85],[10.0083,36.85],[9.9833,36.85],[9.9583,36.85],[9.9333,36.85],[9.908964,36.867245],[9.884627,36.884491],[9.860291,36.901736],[9.835955,36.918982],[9.811618,36.936227],[9.787282,36.953473],[9.762945,36.970718],[9.738609,36.987964],[9.714273,37.005209],[9.689936,37.022455],[9.6656,37.0397],[9.697633,37.050283],[9.729667,37.060867],[9.7617,37.07145],[9.793733,37.082033],[9.825767,37.092617],[9.8578,37.1032],[9.889833,37.113783],[9.921867,37.124367],[9.9539,37.13495],[9.985933,37.145533],[10.017967,37.156117],[10.05,37.1667],[10.024843,37.182086],[9.999686,37.197471],[9.974529,37.212857],[9.949371,37.228243],[9.924214,37.243629],[9.899057,37.259014],[9.8739,37.2744]]'::jsonb,
     true),
    ('L3-TKK', 'Tunis - Kalaâ Khasba', 250.00, 80, 240,
     '[[10.1839,36.7975],[10.1404,36.8029],[10.0969,36.8083],[10.0651,36.82915],[10.0333,36.85],[9.966633,36.844433],[9.899967,36.838867],[9.8333,36.8333],[9.78998,36.79664],[9.74666,36.75998],[9.70334,36.72332],[9.66002,36.68666],[9.6167,36.65],[9.57085,36.625],[9.525,36.6],[9.47915,36.575],[9.4333,36.55],[9.38298,36.58512],[9.33266,36.62024],[9.28234,36.65536],[9.23202,36.69048],[9.1817,36.7256],[9.173775,36.665025],[9.16585,36.60445],[9.157925,36.543875],[9.15,36.4833],[9.161117,36.4222],[9.172233,36.3611],[9.18335,36.3],[9.194467,36.2389],[9.205583,36.1778],[9.2167,36.1167],[9.17503,36.0617],[9.13336,36.0067],[9.09169,35.9517],[9.05002,35.8967],[9.00835,35.8417],[8.96668,35.7867],[8.92501,35.7317],[8.88334,35.6767],[8.84167,35.6217],[8.8,35.5667]]'::jsonb,
     true),
    ('L4-BS', 'Banlieue Sud: Tunis - Borj Cedria', 25.00, 70, 35,
     '[[10.1839,36.7975],[10.190527,36.795447],[10.197153,36.793393],[10.20378,36.79134],[10.210407,36.789287],[10.217033,36.787233],[10.22366,36.78518],[10.230287,36.783127],[10.236913,36.781073],[10.24354,36.77902],[10.250167,36.776967],[10.256793,36.774913],[10.26342,36.77286],[10.270047,36.770807],[10.276673,36.768753],[10.2833,36.7667],[10.288071,36.7632],[10.292843,36.7597],[10.297614,36.7562],[10.302386,36.7527],[10.307157,36.7492],[10.311929,36.7457],[10.3167,36.7422],[10.32085,36.739975],[10.325,36.73775],[10.32915,36.735525],[10.3333,36.7333],[10.338867,36.730533],[10.344433,36.727767],[10.35,36.725],[10.355567,36.722233],[10.361133,36.719467],[10.3667,36.7167],[10.37295,36.714613],[10.3792,36.712525],[10.38545,36.710438],[10.3917,36.70835],[10.39795,36.706263],[10.4042,36.704175],[10.41045,36.702088],[10.4167,36.7]]'::jsonb,
     true),
    ('L5-MSA', 'Métro du Sahel: Sousse - Monastir - Mahdia', 65.00, 90, 75,
     '[[10.6084,35.8256],[10.6024,35.8424],[10.5964,35.8592],[10.617167,35.847067],[10.637933,35.834933],[10.6587,35.8228],[10.679467,35.810667],[10.700233,35.798533],[10.721,35.7864],[10.741767,35.774267],[10.762533,35.762133],[10.7833,35.75],[10.7973,35.75715],[10.8113,35.7643],[10.83359,35.75459],[10.85588,35.74488],[10.87817,35.73517],[10.90046,35.72546],[10.92275,35.71575],[10.94504,35.70604],[10.96733,35.69633],[10.98962,35.68662],[11.01191,35.67691],[11.0342,35.6672],[11.0342,35.650433],[11.0342,35.633667],[11.0342,35.6169],[11.02911,35.59688],[11.02402,35.57686],[11.01893,35.55684],[11.01384,35.53682],[11.00875,35.5168],[11.00366,35.49678],[10.99857,35.47676],[10.99348,35.45674],[10.98839,35.43672],[10.9833,35.4167],[10.99645,35.431367],[11.0096,35.446033],[11.02275,35.4607],[11.0359,35.475367],[11.04905,35.490033],[11.0622,35.5047]]'::jsonb,
     true);

-- ---------------------------------------------------------------------
-- desserte — ordered stop pattern per ligne
-- ---------------------------------------------------------------------

-- L1-TSFG: Tunis - Sousse - Sfax - Gabès
insert into desserte (ligne_id, gare_id, ordre, pk_km, offset_arrivee_min, offset_depart_min)
select l.id, g.id, v.ordre, v.pk_km, v.arr, v.dep
from ligne l, (values
    -- Offsets are paced so no segment demands more than 80 km/h, the slowest
    -- train rostered on this ligne (FR101). The original timings asked for
    -- 120 km/h between Sousse and Msaken and 90 between Kalaâ Kebira and
    -- Akouda: legal for the ligne, impossible for FR101 (80) and TN107 (110),
    -- so those two would have reported a permanent structural delay that no
    -- amount of engine tuning in phase 3 could explain away.
    (1,  'TUN',  0.00,   null::smallint, 0::smallint),
    (2,  'HLIF', 15.00,  13, 14),
    (3,  'GRB',  40.00,  36, 37),
    (4,  'BARG', 52.00,  47, 48),
    (5,  'BBR',  62.00,  57, 59),
    (6,  'ENF',  95.00,  85, 86),
    (7,  'KKEB', 130.00, 116, 117),
    (8,  'AKO',  133.00, 120, 121),
    (9,  'SOU',  140.00, 127, 132),
    (10, 'MSK',  152.00, 142, 143),
    (11, 'ELJ',  195.00, 176, 178),
    (12, 'SFX',  245.00, 221, 226),
    (13, 'MHR',  280.00, 253, 254),
    (14, 'SKH',  320.00, 288, 289),
    (15, 'GAB',  370.00, 330, null)
) as v(ordre, code, pk_km, arr, dep)
join gare g on g.code = v.code
where l.code = 'L1-TSFG';

-- L2-TBZ: Tunis - Bizerte
insert into desserte (ligne_id, gare_id, ordre, pk_km, offset_arrivee_min, offset_depart_min)
select l.id, g.id, v.ordre, v.pk_km, v.arr, v.dep
from ligne l, (values
    (1, 'TUN', 0.00,  null::smallint, 0::smallint),
    (2, 'MAN', 8.00,  9, 10),
    (3, 'OUE', 15.00, 17, 18),
    (4, 'DJD', 25.00, 29, 30),
    (5, 'MAT', 65.00, 76, 78),
    (6, 'ELA', 78.00, 91, 92),
    (7, 'BIZ', 90.00, 105, null)
) as v(ordre, code, pk_km, arr, dep)
join gare g on g.code = v.code
where l.code = 'L2-TBZ';

-- L3-TKK: Tunis - Kalaâ Khasba
insert into desserte (ligne_id, gare_id, ordre, pk_km, offset_arrivee_min, offset_depart_min)
select l.id, g.id, v.ordre, v.pk_km, v.arr, v.dep
from ligne l, (values
    -- Paced to 70 km/h maximum, the speed of FR301, the slowest train on this
    -- ligne. The original Manouba -> Oued Ellil timing asked for 84 km/h on a
    -- ligne limited to 80 -- unrunnable by anything at all.
    (1,  'TUN', 0.00,   null::smallint, 0::smallint),
    (2,  'MAN', 8.00,   8, 9),
    (3,  'OUE', 15.00,  16, 17),
    (4,  'TEB', 35.00,  35, 36),
    (5,  'MEJ', 60.00,  58, 59),
    (6,  'TES', 85.00,  82, 83),
    (7,  'BEJ', 105.00, 101, 103),
    (8,  'KRB', 140.00, 134, 135),
    (9,  'SER', 175.00, 168, 169),
    (10, 'KKH', 250.00, 240, null)
) as v(ordre, code, pk_km, arr, dep)
join gare g on g.code = v.code
where l.code = 'L3-TKK';

-- L4-BS: Banlieue Sud, Tunis - Borj Cedria
insert into desserte (ligne_id, gare_id, ordre, pk_km, offset_arrivee_min, offset_depart_min)
select l.id, g.id, v.ordre, v.pk_km, v.arr, v.dep
from ligne l, (values
    (1, 'TUN',  0.00,  null::smallint, 0::smallint),
    (2, 'RAD',  6.00,  8, 9),
    (3, 'EZZ',  11.00, 15, 16),
    (4, 'HLIF', 16.00, 22, 23),
    (5, 'HCHO', 20.00, 28, 29),
    (6, 'BCED', 25.00, 35, null)
) as v(ordre, code, pk_km, arr, dep)
join gare g on g.code = v.code
where l.code = 'L4-BS';

-- L5-MSA: Métro du Sahel, Sousse - Monastir - Mahdia
-- Stop order follows the corridor southward: Skanès sits between Sousse and
-- Monastir, and Teboulba before Bekalta. The earlier order sent the polyline
-- doubling back on itself twice, which inflated the traversed distance by more
-- than half and would have put trains nowhere near the stop they were at.
insert into desserte (ligne_id, gare_id, ordre, pk_km, offset_arrivee_min, offset_depart_min)
select l.id, g.id, v.ordre, v.pk_km, v.arr, v.dep
from ligne l, (values
    (1, 'SOU',  0.00,  null::smallint, 0::smallint),
    (2, 'HSOU', 6.00,  7, 8),
    (3, 'SKN',  14.00, 17, 18),
    (4, 'MON',  18.00, 22, 24),
    (5, 'TBL',  34.00, 43, 44),
    (6, 'BEK',  40.00, 50, 51),
    (7, 'KSE',  55.00, 64, 65),
    (8, 'MAH',  65.00, 75, null)
) as v(ordre, code, pk_km, arr, dep)
join gare g on g.code = v.code
where l.code = 'L5-MSA';

-- ---------------------------------------------------------------------
-- trains (26) — rolling stock only, no status/delay (see Course, phase 2+)
-- ---------------------------------------------------------------------

-- L1-TSFG
insert into train (numero, nom, type, ligne_id, capacite, vitesse_max_kmh, actif)
select v.numero, v.nom, v.type, l.id, v.capacite, v.vitesse_max_kmh, true
from ligne l, (values
    ('TN101', 'Rapide Tunis-Gabès',    'GRANDES_LIGNES', 450, 120),
    ('TN102', 'Etoile du Sud',         'GRANDES_LIGNES', 450, 120),
    ('TN103', 'Expresso Sfaxien',      'EXPRESS',        380, 120),
    ('TN104', 'Le Gabésien',           'GRANDES_LIGNES', 450, 120),
    ('TN105', 'Trans-Sahel',           'GRANDES_LIGNES', 420, 120),
    ('TN106', 'Le Sfaxien',            'GRANDES_LIGNES', 420, 120),
    ('TN107', 'Nuit du Sud',           'GRANDES_LIGNES', 400, 110),
    ('FR101', 'Fret Phosphates Gabès', 'FRET',           0,   80)
) as v(numero, nom, type, capacite, vitesse_max_kmh)
where l.code = 'L1-TSFG';

-- L2-TBZ
insert into train (numero, nom, type, ligne_id, capacite, vitesse_max_kmh, actif)
select v.numero, v.nom, v.type, l.id, v.capacite, v.vitesse_max_kmh, true
from ligne l, (values
    ('TN201', 'Le Bizertin',        'GRANDES_LIGNES', 300, 90),
    ('TN202', 'Corniche Express',   'GRANDES_LIGNES', 300, 90),
    ('TN203', 'Le Mateurois',       'GRANDES_LIGNES', 280, 90),
    ('FR201', 'Fret Bizerte Port',  'FRET',            0,  70)
) as v(numero, nom, type, capacite, vitesse_max_kmh)
where l.code = 'L2-TBZ';

-- L3-TKK
insert into train (numero, nom, type, ligne_id, capacite, vitesse_max_kmh, actif)
select v.numero, v.nom, v.type, l.id, v.capacite, v.vitesse_max_kmh, true
from ligne l, (values
    ('TN301', 'Le Béjaois',            'GRANDES_LIGNES', 300, 80),
    ('TN302', 'Le Kasserinois',        'GRANDES_LIGNES', 280, 80),
    ('TN303', 'Vallée de la Medjerda',  'GRANDES_LIGNES', 280, 80),
    ('FR301', 'Fret Céréales Béja',    'FRET',             0, 70)
) as v(numero, nom, type, capacite, vitesse_max_kmh)
where l.code = 'L3-TKK';

-- L4-BS
insert into train (numero, nom, type, ligne_id, capacite, vitesse_max_kmh, actif)
select v.numero, v.nom, v.type, l.id, v.capacite, v.vitesse_max_kmh, true
from ligne l, (values
    ('BS401', 'Banlieue Borj Cedria 1', 'BANLIEUE', 600, 70),
    ('BS402', 'Banlieue Borj Cedria 2', 'BANLIEUE', 600, 70),
    ('BS403', 'Banlieue Borj Cedria 3', 'BANLIEUE', 600, 70),
    ('BS404', 'Banlieue Borj Cedria 4', 'BANLIEUE', 600, 70),
    ('BS405', 'Banlieue Borj Cedria 5', 'BANLIEUE', 600, 70)
) as v(numero, nom, type, capacite, vitesse_max_kmh)
where l.code = 'L4-BS';

-- L5-MSA
insert into train (numero, nom, type, ligne_id, capacite, vitesse_max_kmh, actif)
select v.numero, v.nom, v.type, l.id, v.capacite, v.vitesse_max_kmh, true
from ligne l, (values
    ('MS501', 'Métro Sahel 1',       'BANLIEUE', 500, 85),
    ('MS502', 'Métro Sahel 2',       'BANLIEUE', 500, 85),
    ('MS503', 'Métro Sahel 3',       'BANLIEUE', 500, 85),
    ('MS504', 'Métro Sahel 4',       'BANLIEUE', 500, 85),
    ('MS505', 'Métro Sahel Express', 'EXPRESS',  450, 90)
) as v(numero, nom, type, capacite, vitesse_max_kmh)
where l.code = 'L5-MSA';
