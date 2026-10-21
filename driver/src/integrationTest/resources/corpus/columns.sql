-- every column of every type, by name and by *: the result's types as well as its values
SELECT * FROM {c};
SELECT * FROM {o};
SELECT * FROM {k};
SELECT id, vip, born, seen FROM {c};
SELECT c.*, o.total FROM {c} c JOIN {o} o ON o.customer_id = c.id;
