# PostgreSQL's regression tests (testing plan, milestone T4)

`RegressIntegrationTest` fetches the query files of `src/test/regress/sql/` and the data files of
`src/test/regress/data/` at a pinned tag into `build/regress/`, and runs each file statement by
statement in a plain schema directly and in a schema of the same tables imported into ScalarDB
through the driver. `setup.sql` holds the tables of PostgreSQL's `test_setup.sql` that the files
read, changed where ScalarDB cannot import them: a primary key on a unique column, `text` for
`name`. The expected outputs of the suite are not used: the two sides are compared.
