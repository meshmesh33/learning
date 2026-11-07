# Review policy

## Aspects reviewed (each one is handled by a specialist subagent in `.claude/agents/`)

| Aspect | Agent | Focus |
|---|---|---|
| Code Quality | `code-quality-reviewer` | correctness, SOLID, hexagonal boundaries, error handling, tests, readability |
| Requirements | `requirements-reviewer` | every acceptance criterion from the PR/issue is implemented and tested |
| Concurrency | `concurrency-reviewer` | races, visibility, deadlocks, transactions, Kafka/async, idempotency |
| Performance | `performance-reviewer` | N+1, missing indexes, unbounded queries, hot-path allocations, blocking I/O |
| Memory Leak | `memory-leak-reviewer` | unclosed resources, unbounded caches/collections, ThreadLocals, listeners, executors |

## Signal over noise

- Report only findings with **confidence ≥ 80%** that a real problem exists. If unsure, leave it out.
- Report only problems **introduced or touched by this PR**. Pre-existing issues in lines the PR did not
  change are out of scope, unless the PR makes them worse.
- No style nits a formatter or linter would catch.
- At most **15 findings** per run, highest severity first. When there are more Low findings,
  collapse them into one overall 🟡 comment.
- Never post a finding that duplicates an existing open one (same root cause). Extend the existing
  thread instead.

## Approval rules

After every run, count findings whose status is **Open** or **Partial**:

| Open findings | Review event |
|---|---|
| any 🔴 High or 🟠 Medium | `REQUEST_CHANGES` |
| only 🟡 Low, or none | `APPROVE` (Low findings are listed as non-blocking) |

A finding only stops blocking when the code fixes it, the code is removed, or the reviewee gives a
justification that Claude accepts (`💬 Accepted as-is`). Claude never accepts "will fix later" for
High findings unless a linked follow-up issue exists.

## Requirement sources (in this order)

1. Issues linked from the PR body (`Closes #N`, `Fixes #N`, `Resolves #N`, or `#N` under a "Requirements" heading).
2. An "Acceptance criteria" / "Requirements" checklist in the PR body.
3. Docs the PR or issue points to (e.g. `docs/product-set-versioning.md`).

If none of these exist, post one 🟡 Low overall finding (`Requirements · No requirements linked`)
and skip requirements validation.
