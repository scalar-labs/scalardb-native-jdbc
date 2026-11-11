CREATE SCHEMA ec;
CREATE TABLE ec.wallet (
  customer_id INT PRIMARY KEY,
  balance INT NOT NULL
);
CREATE TABLE ec.orders (
  order_id VARCHAR(36) PRIMARY KEY,
  customer_id INT NOT NULL,
  item VARCHAR(50) NOT NULL,
  price INT NOT NULL,
  paid_money INT NOT NULL,
  paid_points INT NOT NULL
);
CREATE TABLE ec.topup_history (
  topup_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  customer_id INT NOT NULL,
  amount INT NOT NULL,
  topped_up_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO ec.wallet VALUES (1, 10000);
