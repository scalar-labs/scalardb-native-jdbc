CREATE TABLE warehouse.receipt (
  receipt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  item_id INT NOT NULL,
  quantity INT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
