---
status: pending
priority: p2
issue_id: "111"
tags: [code-review, correctness, jdbc]
dependencies: ["100"]
---

# Transparent re-prepare: closed statements revive, replay order, stale objects

## Problem Statement

`Proxies.StatementHandler.prepareAgain` (785e126) replaces the vendor statement when the in-flight
count changes a split read's form. Several pieces of statement state are not carried over or are
wrong afterwards. The core check, that `countsOnly` is true only when the old text is still
correct, was confirmed sound.

## Findings

- **Closed statements revive, P2.** After `ps.close()`, or `closeOnCompletion`, an `executeQuery`
  more than 1 s later can re-prepare and succeed. JDBC requires an `SQLException`, and the new
  vendor statement leaks. `Proxies.java:373, 436-457`.
- **Replay order (verified), P3.** `settings.put` keeps the position of the first call.
  - `setLargeMaxRows(100); setMaxRows(5); setLargeMaxRows(7)` replays as 5.
  - Connector/J may also reject the order fetch size, then max rows.
- **unwrap (todo 100), worse in a new way.** A vendor statement the application unwrapped is closed
  silently, and settings made through it (MySQL `enableStreamingResults`, pgjdbc
  `setPrepareThreshold`) are lost. A streaming read then buffers everything.
- **Old metadata.** `getParameterMetaData` proxies keep the old, closed statement.
- **Visibility.** `vendor` / `target` are not volatile, so a `cancel()` from a watchdog thread can hit
  the old statement.
- **Masked error.** `fresh.close()` in the catch can hide the original exception.
- **Refused re-prepare, performance.** When it is refused (pending batch, `prepareCall`), the old
  route keeps an expired `recheckAt`, so every execute routes twice. For CallableStatement that is
  forever.
- **Recording everywhere, performance.** Parameters and settings are recorded on engines and
  statements that can never re-prepare.

## Proposed Solutions

### Option 1
- Check `isClosed()` before re-preparing.
- `remove` then `put` the settings.
- Refuse to re-prepare once `unwrap` has handed out the vendor statement, and keep the old text,
  as for a pending batch.
- Make the fields volatile, and use `addSuppressed`.
- Give the kept route a fresh `recheckAt`.
- Record only when the route's `recheckAt != 0`.
- **Effort:** Small to Medium.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] Tests for close-then-execute, replay order, unwrap-then-recount, and a refused re-prepare not
      re-routing every execute.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".
