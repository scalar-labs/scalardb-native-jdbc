# The read corpus (testing plan, milestone T1)

Each `.sql` file holds statements ending in `;` at the end of a line. Tables are written `{c}`,
`{o}`, `{k}` (ScalarDB tables) and `{r}` (a plain table); the test qualifies them per side. A line
`-- ordered` before a statement compares its rows in order: its ORDER BY must fix a total order, and
a statement with a row limit must be ordered. Other statements compare as multisets. A line
`-- refused` marks a statement the driver must refuse; a refusal of any other fails. A directory
named after an engine (`postgresql/`, `oracle/`, ...) holds that engine's spellings; the shared files
are written in what every engine takes.
