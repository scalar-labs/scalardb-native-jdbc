-- refused
MERGE INTO {c} t USING (SELECT 1 AS id, 'up' AS name FROM dual UNION ALL SELECT 30, 'new' FROM dual) s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.name = s.name WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);
-- refused
UPDATE {o} o SET note = (SELECT c.name FROM {c} c WHERE c.id = o.customer_id) WHERE customer_id IN (SELECT id FROM {c} WHERE region = 1);
-- refused
UPDATE (SELECT o.note FROM {o} o JOIN {c} c ON c.id = o.customer_id WHERE c.name IS NULL) SET note = 'view';
