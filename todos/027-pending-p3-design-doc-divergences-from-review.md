---
status: pending
priority: p3
issue_id: "027"
tags: [code-review, documentation]
dependencies: ["004", "005", "008", "009", "010", "011", "016", "017"]
---

# Record the review's decisions and the code's divergences in the design doc

## Problem Statement

The review found several places where `docs/design.md` and the code disagree, or where the design is
silent on a rule the code now embodies. Each fix in the other todos changes a rule; if the doc is not
updated with it, the next reviewer re-derives the same findings.

## Findings

- `release-snapshot.yml` and `release.yml` are referenced but do not exist; only `ci.yml` and `auto-pr.yml`
  do.
- The `_scalardb` refusal (`driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:126-130`) is
  implemented but the doc names only `_data`; the `_tx_metadata` gap (010) is in neither.
- The identifier case rule and the current-schema rule (004) are not written down.
- "Cannot interpret" (011) has no definition in 4.1.
- The cache TTL and owner (009): the doc says "read once per driver instance" but one `Driver` serves many
  databases.
- Catalog reads on a separate connection (005), rewrite-at-prepare and the statement cache (008), the refusal
  error table (016) and the driver property list (017) all need a sentence once decided.
- The working tree holds an uncommitted draft of a new section 4.7 (vendor hints and unparsed vendor syntax)
  that this review did not produce. It overlaps 011 and should be reconciled with it before committing.

## Proposed Solutions

### Option 1: Update the doc as each dependent todo closes

**Approach:** Add a "doc" acceptance criterion to 004, 005, 008, 009, 010, 011, 016 and 017; this todo closes
when all of them have landed their paragraph. Fix the stale workflow names and the `_scalardb` refusal now.

**Pros:**
- The doc records the decision that was actually made, not a guess.
- No separate doc-only pass.

**Cons:**
- Stays open for as long as the slowest dependency.

**Effort:** 15 minutes per dependent todo

**Risk:** Low

---

### Option 2: One doc pass now with the review's recommendations

**Approach:** Write all the paragraphs now from the review's recommended directions, mark each "pending
implementation", and adjust when the code lands.

**Pros:**
- The doc is complete for the next reader immediately.

**Cons:**
- Some paragraphs will be rewritten when triage picks a different option.

**Effort:** 2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `docs/design.md` sections 1 (Repository and build, The catalog, Architecture), 4.1, 4.4, 7
- `.github/workflows/` (names referenced by the doc)

**Related components:**
- Every todo listed in `dependencies`.

**Database changes:** No

## Resources

- `docs/design.md` throughout.
- Found by: architecture-strategist (P3-9), plus cross-references from the other findings.

## Acceptance Criteria

- [ ] Workflow names in the doc match the files in `.github/workflows/`.
- [ ] All three catalog refusals (`_data`, `_scalardb`, `_tx_metadata`) are listed.
- [ ] The case rule, current-schema rule and "cannot interpret" rule are stated.
- [ ] Cache TTL/owner, catalog connection, rewrite-at-prepare, refusal error table and property list are stated.
- [ ] The uncommitted 4.7 draft is reconciled with 011 or removed before the next commit.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the architecture-strategist report (P3-9) of the seven-agent review of main @ 06c137f and
  from the cross-references the lead collected while deduplicating findings.
- Noted that `git status` shows `docs/design.md` modified with a new section 4.7 that no review agent wrote.

**Learnings:**
- The doc and the code drifted within one commit of each other; a doc line per rule change is cheaper than a
  review round.

### 2026-10-09 - More divergences to record

**By:** Claude Code

**Actions:**
- From the five-engine probe and the catalog rework, design.md should state: the per-engine identifier table (folding, column comparison, where an unqualified name resolves and the engine query used); that ScalarDB creates a real view for every decoupled table's virtual name on every engine; that the catalog is loaded whole per database on the driver's own connection with a TTL, and that the session schemas are captured on the user's connection at connect and after session-changing statements; that ScalarDB cannot import an Oracle INT column (NUMBER(38,0)), so NUMBER(10) or NUMBER(19) is needed; and that the driver's catalog queries quote ScalarDB's column names because Oracle stores them in lower case.

## Notes

- Do not describe internal or private projects in the doc.
