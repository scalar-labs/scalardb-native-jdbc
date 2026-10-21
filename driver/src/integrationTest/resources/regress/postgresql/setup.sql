-- The tables of PostgreSQL's test_setup.sql that the query files read, created in each schema {S}:
-- the same definition on both sides. A table ScalarDB imports has a primary key on a unique column,
-- and text for the type name, which ScalarDB cannot import. -- load marks where the data files are
-- loaded (RegressIntegrationTest.DATA); the statements after it copy loaded tables.

CREATE TABLE {S}.char_tbl (f1 char(4));
INSERT INTO {S}.char_tbl (f1) VALUES ('a'), ('ab'), ('abcd'), ('abcd    ');

CREATE TABLE {S}.float8_tbl (f1 float8);
INSERT INTO {S}.float8_tbl (f1) VALUES
  ('0.0'), ('-34.84'), ('-1004.30'), ('-1.2345678901234e+200'), ('-1.2345678901234e-200');

CREATE TABLE {S}.int2_tbl (f1 int2 PRIMARY KEY);
INSERT INTO {S}.int2_tbl (f1) VALUES ('0   '), ('  1234 '), ('    -1234'), ('32767'), ('-32767');

CREATE TABLE {S}.int4_tbl (f1 int4 PRIMARY KEY);
INSERT INTO {S}.int4_tbl (f1) VALUES
  ('   0  '), ('123456     '), ('    -123456'), ('2147483647'), ('-2147483647');

CREATE TABLE {S}.int8_tbl (q1 int8, q2 int8, PRIMARY KEY (q1, q2));
INSERT INTO {S}.int8_tbl VALUES
  ('  123   ', '  456'),
  ('123   ', '4567890123456789'),
  ('4567890123456789', '123'),
  (+4567890123456789, '4567890123456789'),
  ('+4567890123456789', '-4567890123456789');

CREATE TABLE {S}.point_tbl (f1 point);
INSERT INTO {S}.point_tbl (f1) VALUES
  ('(0.0,0.0)'), ('(-10.0,0.0)'), ('(-3.0,4.0)'), ('(5.1, 34.5)'), ('(-5.0,-12.0)'),
  ('(1e-300,-1e-300)'), ('(1e+300,Inf)'), ('(Inf,1e+300)'), (' ( Nan , NaN ) '), ('10.0,10.0');

CREATE TABLE {S}.text_tbl (f1 text PRIMARY KEY);
INSERT INTO {S}.text_tbl VALUES ('doh!'), ('hi de ho neighbor');

CREATE TABLE {S}.varchar_tbl (f1 varchar(4));
INSERT INTO {S}.varchar_tbl (f1) VALUES ('a'), ('ab'), ('abcd'), ('abcd    ');

CREATE TABLE {S}.onek (
  unique1 int4 PRIMARY KEY,
  unique2 int4,
  two int4,
  four int4,
  ten int4,
  twenty int4,
  hundred int4,
  thousand int4,
  twothousand int4,
  fivethous int4,
  tenthous int4,
  odd int4,
  even int4,
  stringu1 text,
  stringu2 text,
  string4 text
);
CREATE TABLE {S}.onek2 (
  unique1 int4 PRIMARY KEY,
  unique2 int4,
  two int4,
  four int4,
  ten int4,
  twenty int4,
  hundred int4,
  thousand int4,
  twothousand int4,
  fivethous int4,
  tenthous int4,
  odd int4,
  even int4,
  stringu1 text,
  stringu2 text,
  string4 text
);
CREATE TABLE {S}.tenk1 (
  unique1 int4 PRIMARY KEY,
  unique2 int4,
  two int4,
  four int4,
  ten int4,
  twenty int4,
  hundred int4,
  thousand int4,
  twothousand int4,
  fivethous int4,
  tenthous int4,
  odd int4,
  even int4,
  stringu1 text,
  stringu2 text,
  string4 text
);
CREATE TABLE {S}.tenk2 (
  unique1 int4 PRIMARY KEY,
  unique2 int4,
  two int4,
  four int4,
  ten int4,
  twenty int4,
  hundred int4,
  thousand int4,
  twothousand int4,
  fivethous int4,
  tenthous int4,
  odd int4,
  even int4,
  stringu1 text,
  stringu2 text,
  string4 text
);

CREATE TABLE {S}.person (name text, age int4, location point);
CREATE TABLE {S}.emp (salary int4, manager name) INHERITS ({S}.person);
CREATE TABLE {S}.student (gpa float8) INHERITS ({S}.person);
CREATE TABLE {S}.stud_emp (percent int4) INHERITS ({S}.emp, {S}.student);
CREATE TABLE {S}.road (name text, thepath path);
CREATE TABLE {S}.ihighway () INHERITS ({S}.road);
CREATE TABLE {S}.shighway (surface text) INHERITS ({S}.road);

-- load

INSERT INTO {S}.onek2 SELECT * FROM {S}.onek;
INSERT INTO {S}.tenk2 SELECT * FROM {S}.tenk1;
INSERT INTO {S}.ihighway SELECT * FROM ONLY {S}.road WHERE name ~ 'I- .*';
INSERT INTO {S}.shighway SELECT *, 'asphalt' FROM ONLY {S}.road WHERE name ~ 'State Hwy.*';
ANALYZE;
