---
status: complete
priority: p1
issue_id: "131"
tags: [code-review, correctness, jdbc, writes]
dependencies: []
---

# Stream parameters of a prepared write are written as empty values

## Problem Statement

`setBinaryStream`, `setCharacterStream` (and Blob/Clob/AsciiStream) on a prepared write store empty
values: the setter is forwarded to the vendor statement at set time, which consumes the stream, and the
recorded call replays the same, now empty, stream into the driver's statements, once per step and per key.
The write succeeds with wrong data.

## Findings

- Confirmed by two reviewers: `INSERT ... VALUES (?, ?, ?)` with a reader and a byte stream stores `''`
  and zero bytes; `UPDATE ... SET b = ?, x = ? WHERE ...` over 3 rows sets all to empty.
- `Proxies.java` write branch (~403) forwards and records; the binder (~546) replays.

## Proposed Solutions

### Option 1
Buffer the stream at set time into `byte[]`/`String` and record the setter as `setBytes`/`setString`
(with the length variants honoured); or refuse streams on writes as `bind()` does for split reads.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] Both probe scenarios store the full values.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).

### 2026-10-14 - Fixed

**By:** Claude Code
- Proxies.buffered reads a stream or reader once at set time and records setBytes/setString/setNString (setUnicodeStream refused); the vendor statement gets the buffered call too. Test "131" (INSERT, and an UPDATE of two keys with a length-limited reader).
