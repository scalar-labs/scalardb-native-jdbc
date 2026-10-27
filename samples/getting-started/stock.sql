CREATE SCHEMA warehouse;
CREATE TABLE warehouse.stock (
  item_id INT PRIMARY KEY,
  name VARCHAR(50),
  quantity INT
);
