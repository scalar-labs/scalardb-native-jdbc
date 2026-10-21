-- single and multi-row VALUES, partial column lists
INSERT INTO {c} (id, name, region, rate, vip, born, seen) VALUES (7, 'fay', 3, 2.5, {TRUE}, DATE '1995-05-05', TIMESTAMP '2026-02-02 02:02:02');
INSERT INTO {c} (id, name) VALUES (8, 'gus');
INSERT INTO {o} (id, customer_id, total, note) VALUES (17, 7, 40, 'new'), (18, 8, NULL, NULL);
INSERT INTO {k} (a, b, v) VALUES (4, 1, 5);
-- a duplicate key fails on both sides, and changes nothing
INSERT INTO {c} (id, name) VALUES (1, 'dup');
INSERT INTO {k} (a, b, v) VALUES (5, 1, 1), (1, 1, 2);
-- INSERT ... SELECT from a ScalarDB table and from a plain one
-- refused
INSERT INTO {o} (id, customer_id, total, note) SELECT id + 100, id, region, name FROM {c} WHERE region IS NOT NULL;
-- refused
INSERT INTO {k} (a, b, v) SELECT id, 9, region FROM {c} WHERE id < 4;
-- refused
INSERT INTO {o} (id, customer_id, total, note) SELECT id + 200, NULL, id, label FROM {r};
-- UPDATE by key, by predicate, to NULL, of no row
UPDATE {c} SET region = 5 WHERE id = 1;
UPDATE {c} SET rate = rate * 2 WHERE rate > 1;
UPDATE {c} SET name = NULL, vip = NULL WHERE id = 2;
UPDATE {c} SET name = 'nobody' WHERE id = 999;
UPDATE {c} SET seen = TIMESTAMP '2026-05-05 05:05:05', born = DATE '2001-01-01' WHERE vip = {TRUE};
UPDATE {k} SET v = COALESCE(v, 0) + a WHERE b = 1;
-- subqueries: another ScalarDB table, the target itself, correlated
-- refused
UPDATE {o} SET total = total + 1 WHERE customer_id IN (SELECT id FROM {c} WHERE name = 'ann');
-- refused
UPDATE {o} SET note = 'big' WHERE total > (SELECT AVG(total) FROM {o} WHERE total IS NOT NULL);
-- refused
UPDATE {o} SET total = (SELECT MAX(k.v) FROM {k} k WHERE k.a = {o}.customer_id) WHERE id < 13;
-- refused
UPDATE {c} SET region = (SELECT COUNT(*) FROM {o} WHERE {o}.customer_id = {c}.id) WHERE id IN (1, 2, 3);
-- a key changes: a delete and an insert to ScalarDB
-- refused
UPDATE {c} SET id = 70 WHERE id = 7;
-- DELETE by key, by predicate, with subqueries, of no row
DELETE FROM {o} WHERE id = 14;
-- refused
DELETE FROM {o} WHERE customer_id NOT IN (SELECT id FROM {c});
-- refused
DELETE FROM {o} WHERE EXISTS (SELECT 1 FROM {r} r WHERE r.id = {o}.customer_id AND r.label = 'south');
DELETE FROM {k} WHERE v IS NULL;
DELETE FROM {c} WHERE id = 8;
DELETE FROM {c} WHERE id = 999;
-- a deleted key again
INSERT INTO {o} (id, customer_id, total, note) VALUES (14, 3, 1, 'again');
INSERT INTO {c} (id, name) VALUES (8, 'gus2');
UPDATE {o} SET customer_id = NULL WHERE note IS NULL;
DELETE FROM {o} WHERE total < 50 AND note <> 'gift';
