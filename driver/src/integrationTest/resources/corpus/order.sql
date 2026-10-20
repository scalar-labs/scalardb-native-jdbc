-- ordered
SELECT id, name FROM {c} ORDER BY id;
-- ordered
SELECT id, name FROM {c} ORDER BY id DESC;
-- ordered
SELECT id, total FROM {o} ORDER BY total DESC, id;
-- ordered
SELECT c.id, o.id FROM {c} c JOIN {o} o ON o.customer_id = c.id ORDER BY o.id DESC;
-- ordered
SELECT region, COUNT(*) AS n FROM {c} WHERE region IS NOT NULL GROUP BY region ORDER BY n DESC, region;
-- ordered
SELECT a, b FROM {k} ORDER BY a DESC, b;
