-- ordered
SELECT id FROM {c} ORDER BY id LIMIT 3;
-- ordered
SELECT id FROM {c} ORDER BY id LIMIT 2 OFFSET 2;
-- ordered
SELECT id FROM {c} ORDER BY id LIMIT 2, 2;
-- ordered
SELECT c.id, o.id FROM {c} c JOIN {o} o ON o.customer_id = c.id ORDER BY o.id DESC LIMIT 2;
-- ordered
SELECT id FROM {o} WHERE id < 15 ORDER BY id DESC LIMIT 2;
-- ordered
SELECT id FROM {o} ORDER BY id DESC OFFSET 1 ROWS FETCH FIRST 2 ROWS ONLY;
