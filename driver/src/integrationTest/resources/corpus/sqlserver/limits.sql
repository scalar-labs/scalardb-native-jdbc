-- ordered
SELECT TOP 3 id FROM {c} ORDER BY id;
-- ordered
SELECT id FROM {c} ORDER BY id OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY;
-- ordered
SELECT TOP 2 c.id, o.id AS oid FROM {c} c JOIN {o} o ON o.customer_id = c.id ORDER BY o.id DESC;
-- ordered
SELECT TOP 2 id FROM {o} WHERE id < 15 ORDER BY id DESC;
