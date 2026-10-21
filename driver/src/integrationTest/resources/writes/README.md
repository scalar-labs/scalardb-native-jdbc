# The write corpus (testing plan, milestone T3)

Statements in the read corpus's format (`../corpus/README.md`), run in file order, the shared file
first: each changes the tables the next ones see. The tables are `{c}`, `{o}`, `{k}` (ScalarDB
tables) and `{r}` (a plain table), created and filled by `../corpus/schema.sql`. A statement that
fails, or that the driver refuses, changes nothing on either side. Every statement outside the
driver's write scope is marked `-- refused`. A directory named after an
engine holds that engine's own forms: upserts, `MERGE`, joins in `UPDATE` and `DELETE`, `RETURNING`.
