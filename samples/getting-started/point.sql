CREATE SCHEMA point;
CREATE TABLE point.point_account (
  customer_id INT PRIMARY KEY,
  points INT NOT NULL
);
CREATE TABLE point.point_history (
  history_id VARCHAR(36) PRIMARY KEY,
  customer_id INT NOT NULL,
  delta INT NOT NULL,
  reason VARCHAR(100) NOT NULL
);
INSERT INTO point.point_account VALUES (1, 500);
