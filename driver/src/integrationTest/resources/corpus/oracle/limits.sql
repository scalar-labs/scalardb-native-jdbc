-- ordered
SELECT id FROM {c} ORDER BY id FETCH FIRST 3 ROWS ONLY;
-- ordered
SELECT id FROM {c} ORDER BY id OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY;
-- ordered
SELECT c.id, o.id FROM {c} c JOIN {o} o ON o.customer_id = c.id ORDER BY o.id DESC FETCH FIRST 2 ROWS ONLY;
-- ordered
SELECT id FROM {o} WHERE id < 15 ORDER BY id DESC FETCH FIRST 2 ROWS ONLY;
-- ordered
SELECT id FROM (SELECT id FROM {c} ORDER BY id) WHERE ROWNUM <= 3;
