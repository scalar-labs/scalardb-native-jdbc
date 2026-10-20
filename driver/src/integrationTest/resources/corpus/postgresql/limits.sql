-- ordered
SELECT id FROM {c} ORDER BY id LIMIT 3;
-- ordered
SELECT id FROM {c} ORDER BY id LIMIT 2 OFFSET 2;
-- ordered
SELECT id FROM {o} ORDER BY id DESC FETCH FIRST 2 ROWS ONLY;
-- ordered
SELECT c.id, o.id FROM {c} c JOIN {o} o ON o.customer_id = c.id ORDER BY o.id DESC LIMIT 2;
-- ordered
SELECT id FROM {o} WHERE id < 15 ORDER BY id DESC LIMIT 2;
SELECT DISTINCT ON (region) region, id FROM {c} ORDER BY region, id;
