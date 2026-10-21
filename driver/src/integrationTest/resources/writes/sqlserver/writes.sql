MERGE INTO {c} AS t USING (SELECT 1 AS id, 'up' AS name UNION ALL SELECT 30, 'new') AS s ON t.id = s.id WHEN MATCHED THEN UPDATE SET name = s.name WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);;
UPDATE o SET note = c.name FROM {o} o JOIN {c} c ON c.id = o.customer_id WHERE c.region = 1;
DELETE o FROM {o} o JOIN {c} c ON c.id = o.customer_id WHERE c.name IS NULL;
INSERT INTO {o} (id, customer_id, total) OUTPUT inserted.id, inserted.total VALUES (300, 1, 5);
UPDATE {o} SET total = 0 OUTPUT deleted.total, inserted.total WHERE id = 300;
DELETE FROM {o} OUTPUT deleted.id WHERE id = 300;
