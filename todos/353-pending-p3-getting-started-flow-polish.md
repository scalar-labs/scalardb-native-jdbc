---
status: pending
priority: p3
issue_id: "353"
tags: [code-review, docs, getting-started]
dependencies: []
---

# Getting started: small flow and formatting issues

## Problem Statement

Layout choice mixes layout and creation method, and "stock already exists" while B and C create it; the numbers
in steps 5 and 6 (98+30, 49+20; then +30, +20 again) are not explained; the =*> prompt is not explained; the Java
excerpt elides code without naming the full file; links split across lines; code fences without a language; the
setup output shows a real date; the diagram labels stock but not orders as a ScalarDB table; the ScalarDB docs link
points at latest. First-time-user review items 8, 15, 21.

## Proposed Solutions

1. Fix each in place.

## Recommended Action

## Acceptance Criteria

- [ ] Each listed item fixed or deliberately kept.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
