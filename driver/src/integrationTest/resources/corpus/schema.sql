-- The corpus's tables, created in every schema of the run: the plain one the vendor driver reads,
-- and the ScalarDB ones (decoupled and inline) the driver reads, where c, o and k are imported and r
-- stays a plain table. {S} is the schema; {INT}, {DBL}, {BOOL}, {TS} the engine's types.
-- The data is built for semantics: NULLs in every nullable column, duplicates and ties, a customer
-- without orders, orders without a customer, an empty group.
CREATE TABLE {S}.c (id {INT} PRIMARY KEY, name VARCHAR(20), region {INT}, rate {DBL}, vip {BOOL}, born DATE, seen {TS});
CREATE TABLE {S}.o (id {INT} PRIMARY KEY, customer_id {INT}, total {INT}, note VARCHAR(20));
CREATE TABLE {S}.k (a {INT} NOT NULL, b {INT} NOT NULL, v {INT}, PRIMARY KEY (a, b));
CREATE TABLE {S}.r (id {INT} PRIMARY KEY, label VARCHAR(20));
INSERT INTO {S}.c VALUES (1, 'ann', 1, 0.5, {TRUE}, DATE '1990-01-01', TIMESTAMP '2026-01-01 10:00:00');
INSERT INTO {S}.c VALUES (2, 'bob', 1, 1.25, {FALSE}, DATE '1985-06-15', TIMESTAMP '2026-01-02 11:30:00');
INSERT INTO {S}.c VALUES (3, 'cid', 2, NULL, {TRUE}, NULL, NULL);
INSERT INTO {S}.c VALUES (4, 'ann', 2, 0.5, NULL, DATE '1990-01-01', TIMESTAMP '2026-01-01 10:00:00');
INSERT INTO {S}.c VALUES (5, NULL, NULL, 3.75, {FALSE}, DATE '2000-02-29', TIMESTAMP '2026-03-01 00:00:00');
INSERT INTO {S}.c VALUES (6, 'eve', 3, 1.25, {TRUE}, DATE '1970-12-31', TIMESTAMP '2025-12-31 23:59:59');
INSERT INTO {S}.o VALUES (10, 1, 100, 'first');
INSERT INTO {S}.o VALUES (11, 1, 250, NULL);
INSERT INTO {S}.o VALUES (12, 2, 100, 'gift');
INSERT INTO {S}.o VALUES (13, 4, NULL, 'pending');
INSERT INTO {S}.o VALUES (14, NULL, 75, 'guest');
INSERT INTO {S}.o VALUES (15, 99, 30, 'orphan');
INSERT INTO {S}.o VALUES (16, 2, 100, 'gift');
INSERT INTO {S}.k VALUES (1, 1, 10);
INSERT INTO {S}.k VALUES (1, 2, NULL);
INSERT INTO {S}.k VALUES (2, 1, 10);
INSERT INTO {S}.k VALUES (2, 2, 30);
INSERT INTO {S}.k VALUES (3, 1, NULL);
INSERT INTO {S}.r VALUES (1, 'north');
INSERT INTO {S}.r VALUES (2, 'south');
INSERT INTO {S}.r VALUES (4, 'empty');
