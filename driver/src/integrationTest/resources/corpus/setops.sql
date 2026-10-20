SELECT id FROM {c} UNION SELECT customer_id FROM {o};
SELECT id FROM {c} UNION ALL SELECT customer_id FROM {o};
SELECT id FROM {c} INTERSECT SELECT customer_id FROM {o};
SELECT id FROM {c} EXCEPT SELECT customer_id FROM {o};
SELECT region FROM {c} UNION SELECT id FROM {r};
SELECT name FROM {c} WHERE region = 1 UNION ALL SELECT name FROM {c} WHERE region = 2;
