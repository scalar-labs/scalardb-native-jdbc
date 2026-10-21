-- refused
INSERT INTO {c} (id, name) VALUES (1, 'up') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
-- refused
INSERT INTO {c} (id, name) VALUES (1, 'skip'), (30, 'new') ON CONFLICT (id) DO NOTHING;
-- refused
INSERT INTO {c} (id, name) VALUES (1, 'skip') ON CONFLICT DO NOTHING;
INSERT INTO {o} (id, customer_id, total) VALUES (300, 1, 5) RETURNING id, total;
-- refused
UPDATE {o} SET total = 0 WHERE id = 300 RETURNING id, total;
-- refused
UPDATE {o} o SET note = c.name FROM {c} c WHERE c.id = o.customer_id AND c.region = 1;
-- refused
DELETE FROM {o} o USING {c} c WHERE c.id = o.customer_id AND c.name IS NULL;
-- refused
DELETE FROM {o} WHERE id = 300 RETURNING *;
-- refused
WITH x AS (SELECT id FROM {c} WHERE name = 'ann') UPDATE {o} SET note = 'cte' WHERE customer_id IN (SELECT id FROM x);
