INSERT INTO {c} (id, name) VALUES (1, 'up') ON DUPLICATE KEY UPDATE name = 'up';
INSERT INTO {c} (id, name) VALUES (1, 'up2'), (30, 'new') ON DUPLICATE KEY UPDATE name = VALUES(name);
INSERT IGNORE INTO {c} (id, name) VALUES (1, 'skip'), (31, 'new');
REPLACE INTO {c} (id, name) VALUES (30, 'rep');
UPDATE {o} o JOIN {c} c ON c.id = o.customer_id SET o.note = c.name WHERE c.region = 1;
DELETE o FROM {o} o JOIN {c} c ON c.id = o.customer_id WHERE c.name IS NULL;
-- ordered
UPDATE {o} SET total = 1 WHERE note IS NOT NULL ORDER BY id LIMIT 2;
-- ordered
DELETE FROM {o} WHERE note IS NOT NULL ORDER BY id DESC LIMIT 1;
