-- refused
INSERT INTO {c} (id, name) VALUES (1, 'up') ON DUPLICATE KEY UPDATE name = 'up';
-- refused
INSERT INTO {c} (id, name) VALUES (1, 'up2'), (30, 'new') ON DUPLICATE KEY UPDATE name = VALUES(name);
-- refused
INSERT IGNORE INTO {c} (id, name) VALUES (1, 'skip'), (31, 'new');
-- refused
REPLACE INTO {c} (id, name) VALUES (30, 'rep');
-- refused
UPDATE {o} o JOIN {c} c ON c.id = o.customer_id SET o.note = c.name WHERE c.region = 1;
-- refused
DELETE o FROM {o} o JOIN {c} c ON c.id = o.customer_id WHERE c.name IS NULL;
-- ordered
-- refused
UPDATE {o} SET total = 1 WHERE note IS NOT NULL ORDER BY id LIMIT 2;
-- ordered
-- refused
DELETE FROM {o} WHERE note IS NOT NULL ORDER BY id DESC LIMIT 1;
INSERT INTO {o} (id, customer_id, total) VALUES (300, 1, 5) RETURNING id, total;
-- refused
DELETE FROM {o} WHERE id = 300 RETURNING id, total;
