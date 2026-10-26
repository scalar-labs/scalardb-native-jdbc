# Plan: the benchmark environment

## Why

The read benchmark (`ReadOverheadBenchmark`) and T6 of `testing.md` run today against
Testcontainers on a laptop. That is fine for exploring plans, not for numbers we publish or decide
on:

1. **Docker Desktop on macOS.** The containers share one Linux VM with a fixed share of CPU and
   memory and virtualised disk I/O, beside whatever else the laptop runs. On 2026-10-20 the
   measurements ran beside another project's containers, a JVM at 200% CPU, and a Docker hang.
2. **Default settings.** PostgreSQL runs with `shared_buffers` 128 MB and `work_mem` 4 MB, MySQL and
   MariaDB with a 128 MB buffer pool, against 500k-row tables plus their metadata tables. Sorts spill
   (PostgreSQL's VIEW_CASE page sorted 500k rows on disk), and the engines choose the plans of a
   small server. Which form of the read wins depends on that.
3. **Oracle Free** is capped at 2 CPUs and 2 GB, a limit of the product, not of the container.

A container on a dedicated Linux host, with tuned settings and a local disk, runs on the host's
kernel and measures like a native install. The problem is the laptop and the defaults.

Laptop containers stay for smoke runs, the integration tests, and questions about the SQL itself:
whether a planner can estimate a filter does not depend on the machine (the filter without
`COALESCE`, `paging-route.md`, was found locally).

## Scope

**AWS, $500 at most.** Engines in priority order: **PostgreSQL and Oracle**, then MySQL and
MariaDB. SQL Server is left out (its licence on RDS is the most expensive, and it is not a priority);
it can be added to tier A later with the Developer edition.

## Two tiers

| tier | what | for |
|---|---|---|
| **A: self-managed** | one EC2 instance per engine running the engine's official image (the tests' images; Oracle's Enterprise Edition image from Oracle's registry) with host networking and the data on the instance's NVMe, settings below; a separate client instance in the same availability zone | plan questions, the form matrix, comparisons between driver versions |
| **B: managed** | RDS for PostgreSQL, Oracle (19c, Standard Edition 2, licence included), MySQL and MariaDB, default parameter groups plus the memory settings below; the same client | the numbers a customer would see: real defaults, networked storage, Oracle's nightly statistics job (todo 094) |

Tier A is controllable and repeatable; tier B is realistic and less precise. Both run the same
benchmarks with the same parameters, and a result names its tier.

**Oracle versions.** Tier A runs 23ai (the version the tests use, as Oracle Free 23). RDS for Oracle
offers 19c and 21c, not 23ai, as far as known; to be checked before provisioning. If so, tier B runs
19c, the most deployed version, which is the point of tier B. Oracle has renamed the 23ai line:
the tests' image reports "Oracle AI Database 26ai, Release 23.26" (2026-10-20). Tier A takes the
Enterprise Edition image of that line.

## Machines

| role | type | notes |
|---|---|---|
| tier A database | `m6id.2xlarge`: 8 vCPU, 32 GB, 474 GB local NVMe | the data on the NVMe; fixed performance, not a burstable `t` type |
| tier B database | `db.m5.2xlarge`: 8 vCPU, 32 GB, 100 GB gp3 (RDS offers Oracle SE2 licence included on no m6i class) | single-AZ, no read replicas, no backups beyond the minimum |
| client | `m6i.2xlarge`: 8 vCPU, 32 GB | Gradle and the benchmark JVM, one JVM per engine; one per tier if its CPU goes above 50% |

All x86, one availability zone. No CI runners: shared and too noisy for timing.

## Settings

Sized for 32 GB, so that the benchmark's data (about 1 GB per engine with indexes and metadata
tables) stays in memory. Everything else is the engine's default. The settings live in
`bench-env/`, and each report records the effective ones (harness change 2).

| engine | tier A version | settings |
|---|---|---|
| PostgreSQL | 16 | `shared_buffers` 8GB, `effective_cache_size` 24GB, `work_mem` 64MB, `maintenance_work_mem` 1GB, `random_page_cost` 1.1 |
| Oracle | 23ai, Enterprise Edition | SGA 16 GB, PGA 4 GB; the automatic statistics job left on |
| MySQL | 8.4 | `innodb_buffer_pool_size` 20G, `innodb_redo_log_capacity` 4G; `lower_case_table_names` as in the tests |
| MariaDB | 11.4 | `innodb_buffer_pool_size` 20G, `innodb_log_file_size` 4G |

On tier B, everything stays at RDS's defaults, which size the buffers from the instance's memory:
the default parameter groups are the customer's starting point that tier B measures.

## Harness changes

The harness targets an external database through `-Dnativejdbc.<engine>.url`, `.username` and
`.password` (`Engines.start`). Added for this plan, in `ReadOverheadBenchmark`:

1. **A clean start (done).** `-Dnativejdbc.bench.dropExisting=true` drops the schemas a run creates
   (`bn`, ScalarDB's `scalardb` and `coordinator`, which ScalarDB names in quoted lower case) before
   loading; Oracle's without `DROP USER IF EXISTS`, which 19c lacks. Off by default, so that a
   mistyped URL does not empty another database. Checked on 2026-10-20 against standalone
   PostgreSQL and Oracle containers: a second run on the first one's leftovers loads and measures.
2. **The environment in the report (done).** Each report starts with the tier
   (`-Dnativejdbc.bench.tier=A`), the server version, and the settings that change plans.
3. **A statement timeout (done):** `-Dnativejdbc.bench.timeoutSeconds`, 120 by default; 30 for the
   form matrix, where some forms never finish.
4. **Privileges on RDS.** The benchmark and Setup create users and schemas and, on Oracle, call
   `DBMS_STATS`. RDS's master user has neither SYSDBA nor superuser: check each call on the first
   run and use RDS's equivalent (`rdsadmin` procedures) where one is refused.
5. **Reuse of a loaded data set**, only if loading over the network turns out to dominate a run.

## Tooling (`bench-env/`)

- `terraform/`: the VPC, subnets and security groups (SSH to the client from the user's address
  only; the databases reachable only from the client), the client, and the instances of both tiers,
  chosen per run by the variables `tier_a` and `tier_b`. Tier A's Oracle is Enterprise Edition
  with `oracle_image` and a login to Oracle's registry, both from the user, and otherwise Oracle
  Free (`gvenzl/oracle-free:23-slim`, the tests' 23.26 without their fast start), with no licence
  to buy. The product caps Free at 2 CPUs, 2 GB and 12 GB of user data; the benchmark's data is
  216 MB at scale 1 and about 2.2 GB at scale 10. With `db_memory_mb` the other tier A databases are
  capped at that much memory, close to Free's 2 GB, but not at 2 CPUs: compare Free with them
  for the order of the forms, not for the times. It is a host for 23 against RDS's 19c, as for the
  predicate's check (`docs/benchmarks/inflight-check.md`). Enterprise Edition's image creates its
  database on first start, about 20 minutes, which `BenchBaseBenchmark` waits for; once a PDB is
  open the host turns off the logon's write of its time into `SYS.USER$`
  (`_disable_last_successful_login_time`), as the tests' container does, since a hard parse at
  SERIALIZABLE reads it and now and then fails with ORA-08177. On Oracle BenchBase logs on as
  `BBRUN` (DBA rights, a logon trigger setting `CURRENT_SCHEMA`); `serverStats` is PostgreSQL's
  only so far. `bench-ora.tfvars` (git-ignored) holds the Oracle run's variables, the registry
  token among them. Tier A also runs SQL Server
  (`sqlserver`): the tests' image, `mcr.microsoft.com/mssql/server:2022-latest`, in Developer
  edition, free for testing. The setup makes the database `appdb` as the tests make it, with
  `READ_COMMITTED_SNAPSHOT` and `ALLOW_SNAPSHOT_ISOLATION` on. It keeps to 80% of `db_memory_mb`.
  RDS has no SQL Server here, because its licence-included editions cost more per hour.
- **Data read from disk on tier A:** `db_memory_mb` in `bench.tfvars` limits each tier A database
  container to that much memory, the OS's file cache in it included (a container's memory limit
  counts its page cache), with the buffer pool shrunk to fit (PostgreSQL `shared_buffers` a
  quarter, MySQL and MariaDB's InnoDB pool three eighths), so `-Dnativejdbc.bench.scale` data larger than it
  is read from the NVMe. 0, the default, keeps the settings above. RDS has no such limit without
  custom parameter groups, which the role may not create: tier B stays in memory.
- `rank.py`: ranks the forms per engine, in-flight count and query from a directory of reports
  (rows checked against BASE, forms that failed left out), and flags where a form beats DRIVER by
  more than 1.5x.
- `run.sh plan` writes `terraform/bench.tfplan` and shows it for approval; `run.sh run` applies it,
  copies the repository at HEAD to the client once per tier (`git archive` over SSH; nothing is
  pushed), runs `matrix.sh` per tier in parallel, copies the reports to `bench-env/results/`, and
  destroys everything (see "Credentials").
- **Runs without the laptop:** with `BENCH_BUCKET=scalardb-bench-<account>` and `bench.tfvars`'
  `client_profile = "scalardb-bench-client"`, `run.sh run` applies, copies the tree, starts
  `detached.sh` on the client and returns. The client runs the tiers within `MAX_RUN_MINUTES`, then,
  also after a failure, uploads the reports and logs to the bucket, deletes the tagged RDS instances
  and terminates the tagged EC2 instances, itself last, with its instance role (`client-policy.json`:
  describe, terminate and delete only what carries the tag, upload to the bucket). `run.sh fetch RUN`
  later copies the reports to `bench-env/results/RUN/` and destroys what is left (VPC, subnets,
  security groups, key pair, subnet group: no cost). The bucket and role come from
  `setup-account.sh`, run once more with the user's own login. If the client dies before its
  teardown, its EC2 instances still end at the dead man's switch, but the RDS instances run until
  `run.sh fetch`; the budget alerts are the backstop.
- `test-run.sh`: checks `run.sh`'s teardown with stand-ins for `aws`, `terraform`, `ssh` and `scp`,
  a detached run, and `detached.sh`'s teardown after a failed tier.
- `iam-policy.json`: the benchmark user's permissions; `terraform/bench.tfvars.example`: the
  settings file the user fills in (`bench.tfvars`, ignored by git).
- `matrix.sh`: runs on the client; the read benchmark against one tier's databases, every engine in
  parallel, per in-flight count (`INFLIGHT`) and run (`REPS`), with the clean start on.

## Safety and cost control

- **An AWS Budget of $500** on the account, e-mail alerts at 50% and 80%, and at 100% a budget
  action that attaches a deny policy to the benchmark user (no new instances). A budget action can
  stop only instances chosen when the action is created, not ones Terraform creates later, and
  billing data lags by hours: the budget is a backstop, and the destroy trap and the dead man's
  switch are what stop running instances.
- **Every run ends in `terraform destroy`**, from a trap that also runs after a failure. RDS
  instances are deleted, not stopped (RDS restarts a stopped instance after 7 days).
- **A dead man's switch on EC2:** each instance shuts itself down 10 hours after boot, with
  `instance_initiated_shutdown_behavior = "terminate"`.
- **The agent's credentials** come from `aws login --profile scalardb-bench` (AWS CLI 2.32 or
  later), signed in through the browser as the benchmark user of `iam-policy.json` (EC2 and RDS in
  one region, read access to costs and prices), in an account used only for this. Never as root or
  an administrator, and never the user's own `default` profile. No access key is created.

## Secrets on the instances

The database password reaches tier A's instances through `user_data`, which anyone in the account
with `ec2:DescribeInstanceAttribute` can read: acceptable for a password generated per environment,
used only by these databases, which only the client reaches. The boot scripts run without `set -x`,
so it is not logged, and the instances take IMDSv2 tokens only. **Before tier A runs Oracle
Enterprise Edition, the registry token (the user's own credential) moves to Secrets Manager**, read
by the instance through its role, not `user_data`.

The role may tag RDS resources only with the benchmark's tag and only on the names Terraform
generates (`bench-b-*`, `bench-*` subnet groups): someone else's resource so named in the shared
account could still be tagged and then changed. Accepted as unlikely; the names include a timestamp.

## Credentials

`aws login` gives short-lived credentials, refreshed by the CLI until the login session ends. The
risk is the cleanup: a run that outlives the session cannot destroy what it created, and RDS, unlike
EC2, does not terminate itself (four RDS instances cost about $100 a day). So `run.sh`:

1. exports fresh credentials before every Terraform call (`aws configure export-credentials`);
2. refreshes them every 5 minutes, and on the first refresh that fails (the session ended) destroys
   everything at once, with the last credentials, which are still valid for some minutes;
3. destroys everything after `MAX_RUN_MINUTES` (480 by default, below a 12-hour session with room for
   the destroy), at the end, and after a failure or an interrupt;
4. if a destroy fails anyway, prints the commands to sign in again and destroy by hand.

Log in right before a run. A run longer than a session (T5's and T6's long runs) is split into
pieces that each fit in one; to check once the account exists is the session length `aws login`
allows there, and that the Terraform AWS provider takes the exported credentials (it reads the
standard environment variables, so it should).
- **Each `terraform apply` is shown first** (`terraform plan`, the instance list and the estimated
  hourly cost) and confirmed by the user before it runs.

## Running

1. `aws login --profile scalardb-bench`; `run.sh plan`, shown to the user with the instance list and
   the estimated hourly cost; on approval, `run.sh run`, which applies it.
2. Copy the repository at a commit, build, and run the same scripts as locally with the external
   URLs, `-Dnativejdbc.bench.dropExisting=true` and the tier.
3. Each point is three runs. Caches are warm (the benchmark warms each statement); cold caches are
   not measured.
4. Copy the reports to `docs/benchmarks/`, marked by tier, and destroy.

**First job: the form matrix.** All 33 forms of the read benchmark on all 41 queries, at 100 and
10,000 rows in flight, on tier A, PostgreSQL and Oracle first: one screening run with a 30 s timeout,
`rank.py`, then three runs of the two or three best forms of each flagged query. The result is a
table of query shape to form per engine and the gaps against what the driver emits, which become
the router's rules (as `paging-route.md` does for paging). Then the same on tier B, then MySQL and
MariaDB, then T5 and T6.

**The predicate's check beside ScalarDB writers.** `WRITERS=1 READS=0 run.sh run` runs only
`InFlightCheckBenchmark`'s writers mode (`docs/benchmarks/inflight-check.md`), `REPS` times per
tier, one engine at a time so that the readers and writers of one engine have the client's CPU to
themselves; each run drops what the one before left. It checks whether ScalarDB's own commits fall
with `scalardbnative.predicateChecks` on, as Oracle's did on the laptop (629 against 407 commits/s at
16 writers). About 2 minutes per engine and run, after the instances are up. Tier B's Oracle is 19c:
the predicate's check has not run there before.

**BenchBase (T6 of `testing.md`, todo 329).** `BENCHBASE=1 READS=0 run.sh run` runs
`BenchBaseBenchmark` on the tier's PostgreSQL: TPC-C (scale 4) and YCSB (weights 50,5,15,10,0,20),
4 terminals, runs (a), the vendor URL, and (c), every table on ScalarDB, `REPS` times each,
`BB_TIME` seconds a run (120 by default), assertions off. The client builds BenchBase once, at the
commit the harness was written against (`33c00473`). With `pg_stat_statements = true` in
`bench.tfvars`, tier A's PostgreSQL loads pg_stat_statements with planning tracked, for both runs
alike. Each run's report adds what the server counted per commit, read over JDBC: active and
idle-in-transaction time (pg_stat_database), WAL, and on tier A the container's CPU and the host's
packets (`pg_read_file`, a superuser's); each run's top statements are in its `server.txt`, in
`bb-benchbase-runs.tgz`. `BASELINE=<commit>` copies that commit's tree beside the working tree and
runs the same after it (reports prefixed `base-`), for what a change is worth with the network in
between. Each run also measures the round trip from the client before it executes (2,000 prepared
`SELECT 1`, median and p99), since the ratio to (a) depends on it: the driver adds a roughly fixed
time a transaction, and placements in one zone gave the vendor 528 to 1,786 TPC-C transactions a
second (round trips of 105 to 364 µs). `placement_strategy = "cluster"` in `bench.tfvars` puts the
client and tier A's hosts in one placement group, for a short round trip that stays the same from
run to run; the default, `""`, leaves the placement to EC2. The role needs `iam-policy.json`'s
placement-group actions (re-run `setup-account.sh` once). For a slower network on the same
placement, `NET_DELAYS="0 250" run.sh run ...` runs BenchBase's whole set once per delay on the same
instances: the client delays each packet it sends to the databases by that many microseconds
(`net-delay.sh`, Linux's netem; ssh and S3 are not delayed), so each round trip is that much longer
for the vendor's runs and the driver's alike. It checks the TCP connect time without the delay and
with it (`net-delay-<delay>.txt` with the reports); the reports are prefixed `-d<delay>` and state
the delay beside the round trip they measure. One tier at a time. `BB_ISOLATIONS="READ_COMMITTED
REPEATABLE_READ SERIALIZABLE"` runs each of the driver's levels (run (a) at the vendor's matching
one), the reports prefixed `-rc` and `-ser` beside REPEATABLE_READ's. With `profiling = true`, tier A's database hosts get perf, and `perf-tier-a.sh OUT RUN...`,
run from the laptop beside a run, records 45 s of each named BenchBase run on the host (through the
client, which alone reaches it) and fetches the backends' profiles, by function and with what each
calls. About 4 minutes a run with loading: TPC-C and YCSB, (a) and (c), 3 repetitions, about
50 minutes per tree.

## Cost

Rough, from memory of AWS on-demand prices in `us-east-1`, to be checked with the Pricing API before
the first apply:

| item | assumption | estimate |
|---|---|---|
| Tier A, all running | four `m6id.2xlarge` at about $0.47/h, one client at about $0.38/h | about $2.3/h |
| Tier B, all running | RDS PostgreSQL, MySQL, MariaDB at about $0.70-0.75/h each; Oracle SE2 licence included about $1.5-2.5/h; storage a few dollars a month each | about $3.7-4.7/h |
| Form matrix, tier A | screening about 2.5 h, confirmation about 1.5 h, all engines in parallel | about $10-15 |
| Read benchmark, tier B | three matrices of about 3 h | about $40 |
| Setup, debugging, failed runs | | about $30 |
| T5 and T6, tier A, four engines | about 64 hours per engine (`testing.md`) | about $200 |
| T6, tier B, PostgreSQL and Oracle | about 40 hours | about $110 |
| **Total** | | **about $400**, about $100 of margin |

The risk is an environment left running: everything of both tiers costs about $150 a day. The
budget action, the destroy trap and the dead man's switch exist for that.

## Publishing

The licences of Oracle restrict publishing benchmark results without the vendor's permission, and
the development licence of Oracle 23ai on tier A has its own terms for benchmarking. The Oracle
numbers stay internal until both are checked.

## Decisions (2026-10-20)

1. **AWS**, in an account used only for the benchmarks.
2. **$500 at most**, watched by a budget with alerts and a deny action.
3. **Oracle 23ai** on tier A; 19c on RDS if RDS has no 23ai.
4. **An agent provisions**, with the benchmark user's short-lived `aws login` credentials, each
   apply confirmed first.
5. **Engines: PostgreSQL and Oracle first, then MySQL and MariaDB**; SQL Server left out.

Open: the region (`us-east-1` unless the user prefers one nearer); the Oracle download for tier A,
which needs an Oracle account, so the user fetches the installer or a link to it.
