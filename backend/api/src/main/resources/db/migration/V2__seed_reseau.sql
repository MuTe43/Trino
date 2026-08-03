-- Phase 0 seed: real SNCFT référentiel (gares, lignes, desserte, trains).
-- Coordinates are OpenStreetMap-derived; where the exact platform/site was
-- uncertain the town/city centre point was used instead (see phase writeup).
-- No schema changes in this file.

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
insert into ligne (code, nom, distance_km, vitesse_max_kmh, temps_theorique_min, trace, actif) values
    ('L1-TSFG', 'Tunis - Sousse - Sfax - Gabès', 370.00, 120, 330,
     '[[10.1839,36.7975],[10.2586,36.7654],[10.3333,36.7333],[10.41665,36.675],[10.5,36.6167],[10.50835,36.575],[10.5167,36.5333],[10.5417,36.5],[10.5667,36.4667],[10.475,36.3],[10.3833,36.1333],[10.4583,36.0083],[10.5333,35.8833],[10.55,35.875],[10.5667,35.8667],[10.58755,35.84615],[10.6084,35.8256],[10.59585,35.77945],[10.5833,35.7333],[10.6486,35.51525],[10.7139,35.2972],[10.7371,35.0189],[10.7603,34.7406],[10.63015,34.63695],[10.5,34.5333],[10.2932,34.4136],[10.0864,34.2939],[10.0923,34.0877],[10.0982,33.8815]]'::jsonb,
     true),
    ('L2-TBZ', 'Tunis - Bizerte', 90.00, 90, 105,
     '[[10.1839,36.7975],[10.16215,36.8002],[10.1404,36.8029],[10.11865,36.8056],[10.0969,36.8083],[10.081,36.818725],[10.0651,36.82915],[10.0492,36.839575],[10.0333,36.85],[10.0083,36.85],[9.9833,36.85],[9.9583,36.85],[9.9333,36.85],[9.866375,36.897425],[9.79945,36.94485],[9.732525,36.992275],[9.6656,37.0397],[9.7617,37.07145],[9.8578,37.1032],[9.9539,37.13495],[10.05,37.1667],[10.005975,37.193625],[9.96195,37.22055],[9.917925,37.247475],[9.8739,37.2744]]'::jsonb,
     true),
    ('L3-TKK', 'Tunis - Kalaâ Khasba', 250.00, 80, 240,
     '[[10.1839,36.7975],[10.1549,36.8011],[10.1259,36.8047],[10.0969,36.8083],[10.0757,36.8222],[10.0545,36.8361],[10.0333,36.85],[9.966633,36.844433],[9.899967,36.838867],[9.8333,36.8333],[9.7611,36.7722],[9.6889,36.7111],[9.6167,36.65],[9.555567,36.616667],[9.494433,36.583333],[9.4333,36.55],[9.349433,36.608533],[9.265567,36.667067],[9.1817,36.7256],[9.171133,36.644833],[9.160567,36.564067],[9.15,36.4833],[9.172233,36.3611],[9.194467,36.2389],[9.2167,36.1167],[9.0778,35.933367],[8.9389,35.750033],[8.8,35.5667]]'::jsonb,
     true),
    ('L4-BS', 'Banlieue Sud: Tunis - Borj Cedria', 25.00, 70, 35,
     '[[10.1839,36.7975],[10.20875,36.7898],[10.2336,36.7821],[10.25845,36.7744],[10.2833,36.7667],[10.29165,36.760575],[10.3,36.75445],[10.30835,36.748325],[10.3167,36.7422],[10.32085,36.739975],[10.325,36.73775],[10.32915,36.735525],[10.3333,36.7333],[10.34165,36.72915],[10.35,36.725],[10.35835,36.72085],[10.3667,36.7167],[10.3792,36.712525],[10.3917,36.70835],[10.4042,36.704175],[10.4167,36.7]]'::jsonb,
     true),
    ('L5-MSA', 'Métro du Sahel: Sousse - Monastir - Mahdia', 65.00, 90, 75,
     '[[10.6084,35.8256],[10.6044,35.8368],[10.6004,35.848],[10.5964,35.8592],[10.668033,35.827567],[10.739667,35.795933],[10.8113,35.7643],[10.801967,35.759533],[10.792633,35.754767],[10.7833,35.75],[10.866933,35.705633],[10.950567,35.661267],[11.0342,35.6169],[11.0342,35.633667],[11.0342,35.650433],[11.0342,35.6672],[11.017233,35.5837],[11.000267,35.5002],[10.9833,35.4167],[11.0096,35.446033],[11.0359,35.475367],[11.0622,35.5047]]'::jsonb,
     true);

-- ---------------------------------------------------------------------
-- desserte — ordered stop pattern per ligne
-- ---------------------------------------------------------------------

-- L1-TSFG: Tunis - Sousse - Sfax - Gabès
insert into desserte (ligne_id, gare_id, ordre, pk_km, offset_arrivee_min, offset_depart_min)
select l.id, g.id, v.ordre, v.pk_km, v.arr, v.dep
from ligne l, (values
    (1,  'TUN',  0.00,   null::smallint, 0::smallint),
    (2,  'HLIF', 15.00,  13, 14),
    (3,  'GRB',  40.00,  36, 37),
    (4,  'BARG', 52.00,  46, 47),
    (5,  'BBR',  62.00,  55, 57),
    (6,  'ENF',  95.00,  85, 86),
    (7,  'KKEB', 130.00, 116, 117),
    (8,  'AKO',  133.00, 119, 120),
    (9,  'SOU',  140.00, 125, 130),
    (10, 'MSK',  152.00, 136, 137),
    (11, 'ELJ',  195.00, 174, 176),
    (12, 'SFX',  245.00, 219, 224),
    (13, 'MHR',  280.00, 250, 251),
    (14, 'SKH',  320.00, 285, 286),
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
    (1,  'TUN', 0.00,   null::smallint, 0::smallint),
    (2,  'MAN', 8.00,   8, 9),
    (3,  'OUE', 15.00,  14, 15),
    (4,  'TEB', 35.00,  34, 35),
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
insert into desserte (ligne_id, gare_id, ordre, pk_km, offset_arrivee_min, offset_depart_min)
select l.id, g.id, v.ordre, v.pk_km, v.arr, v.dep
from ligne l, (values
    (1, 'SOU',  0.00,  null::smallint, 0::smallint),
    (2, 'HSOU', 6.00,  7, 8),
    (3, 'MON',  18.00, 21, 23),
    (4, 'SKN',  23.00, 27, 28),
    (5, 'BEK',  40.00, 46, 47),
    (6, 'TBL',  46.00, 53, 54),
    (7, 'KSE',  55.00, 63, 64),
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
    ('TN103', 'Expresso Sfaxien',      'EXPRESS',        380, 130),
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
    ('BS401', 'Banlieue Borj Cedria 1', 'BANLIEUE', 600, 80),
    ('BS402', 'Banlieue Borj Cedria 2', 'BANLIEUE', 600, 80),
    ('BS403', 'Banlieue Borj Cedria 3', 'BANLIEUE', 600, 80),
    ('BS404', 'Banlieue Borj Cedria 4', 'BANLIEUE', 600, 80),
    ('BS405', 'Banlieue Borj Cedria 5', 'BANLIEUE', 600, 80)
) as v(numero, nom, type, capacite, vitesse_max_kmh)
where l.code = 'L4-BS';

-- L5-MSA
insert into train (numero, nom, type, ligne_id, capacite, vitesse_max_kmh, actif)
select v.numero, v.nom, v.type, l.id, v.capacite, v.vitesse_max_kmh, true
from ligne l, (values
    ('MS501', 'Métro Sahel 1',       'BANLIEUE', 500, 100),
    ('MS502', 'Métro Sahel 2',       'BANLIEUE', 500, 100),
    ('MS503', 'Métro Sahel 3',       'BANLIEUE', 500, 100),
    ('MS504', 'Métro Sahel 4',       'BANLIEUE', 500, 100),
    ('MS505', 'Métro Sahel Express', 'EXPRESS',  450, 110)
) as v(numero, nom, type, capacite, vitesse_max_kmh)
where l.code = 'L5-MSA';
