WITH t AS (SELECT customer_id, SUM(total) AS s FROM {o} GROUP BY customer_id) SELECT customer_id FROM t WHERE s > 100;
WITH big AS (SELECT id, customer_id FROM {o} WHERE total >= 100) SELECT c.id, b.id FROM {c} c JOIN big b ON b.customer_id = c.id;
SELECT id, ROW_NUMBER() OVER (PARTITION BY region ORDER BY id) AS n FROM {c};
SELECT id, SUM(total) OVER (PARTITION BY customer_id) AS s FROM {o};
SELECT id, RANK() OVER (ORDER BY total DESC) AS r FROM {o};
SELECT id, COUNT(*) OVER () AS n FROM {c};
SELECT c.id, LAG(c.id) OVER (ORDER BY c.id) AS prev FROM {c} c;
