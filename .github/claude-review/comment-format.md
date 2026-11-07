# Comment format

Keep every comment short and simple. No praise, no filler, no restating the code.

## Severity labels

| Label | Meaning |
|---|---|
| 🔴 **High** | Bug, data loss/corruption, race condition, memory leak, security hole, or a requirement not implemented. Blocks approval. |
| 🟠 **Medium** | Likely problem under load, edge case or in maintenance; clear design violation. Blocks approval. |
| 🟡 **Low** | Readability, naming, small cleanup. Never blocks approval. |

## Categories

`Code Quality` · `Requirements` · `Concurrency` · `Performance` · `Memory Leak`

## Finding comment (inline, or in the overall review body)

~~~markdown
**🔴 High · Concurrency · Check-then-act race on `versionCache`**

**Concern:** Two requests can both miss the cache and publish different versions for the same set.
**Violates:** Atomicity of compound actions (check-then-act)

**Fix:** Make the lookup and insert one atomic step.
```suggestion
return versionCache.computeIfAbsent(setId, this::loadVersion);
```

<sub>CR-3 · claude-review</sub>
<!-- claude-review:finding id=CR-3 severity=high category=concurrency -->
~~~

Rules:

1. **Header**: `**<emoji> <Severity> · <Category> · <Title>**`. The title has at most 8 words.
2. **Concern**: 1–2 sentences on *what* goes wrong and *when*. Be concrete (input, load, sequence).
3. **Violates**: only if a named concept, principle or rule is broken, e.g. `SRP`, `DRY`,
   `Open/Closed`, `Liskov`, `Dependency Inversion`, `Hexagonal: domain must not depend on adapters`,
   `Java Memory Model: happens-before`, `Check-then-act`, `Lock ordering`, `N+1 queries`,
   `Transactional outbox`, `Idempotency`, `Resource ownership (try-with-resources)`,
   `Acceptance criterion #2`. Leave the line out when nothing named applies.
4. **Fix**: one sentence, then a GitHub ```` ```suggestion ```` block **only** when the fix fits in the
   commented lines. Otherwise use a plain ```` ```java ```` snippet of at most ~10 lines.
5. **Footer**: `<sub>CR-<n> · claude-review</sub>` plus the hidden marker. IDs are sequential per PR
   and never reused. The marker is how later runs find and track the finding. Never leave it out.

## Inline vs overall

- **Inline**: the problem lives in one place (one file, one hunk). Anchor it on the exact line on the
  RIGHT side of the diff.
- **Overall** (review body): the problem spans 3+ places or several files, or it is about something
  missing (e.g. an unimplemented requirement). Add a `**Where:**` line listing `path:line` references.

## Verification replies (on an existing finding thread)

```markdown
✅ **Fixed** in abc1234: lookup and insert now go through `computeIfAbsent`.
<!-- claude-review:verdict id=CR-3 status=fixed sha=abc1234 -->
```

```markdown
❌ **Not fixed** in abc1234: `put` still runs outside the lock in `refresh()` (line 88).
**Still needed:** move the `put` inside the `synchronized` block or use `compute`.
<!-- claude-review:verdict id=CR-3 status=open sha=abc1234 -->
```

```markdown
⚠️ **Partially fixed** in abc1234: the main path is fixed, the retry path at line 120 is not.
<!-- claude-review:verdict id=CR-3 status=open sha=abc1234 -->
```

## Summary comment (one per PR, edited in place on every run)

```markdown
## 🤖 Claude review: Changes requested

**Commit:** abc1234 · **Run:** 2 (verify)

| ID | Sev | Category | Title | Status |
|---|---|---|---|---|
| CR-1 | 🔴 | Concurrency | Check-then-act race on `versionCache` | ✅ Fixed |
| CR-2 | 🟠 | Performance | N+1 query loading set items | ❌ Open |
| CR-3 | 🟡 | Code Quality | Magic number for retry count | 💬 Accepted as-is |

### Requirements coverage
| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | Publishing a set creates a new immutable version | ✅ Implemented | `PublishProductSetService.java:42` |
| 2 | Old versions stay readable | ❌ Missing | no read path for version ≠ latest |

**Blocking:** 1 open (🟠 1)

<!-- claude-review:summary sha=<full 40-char head sha> -->
```

Status values: `❌ Open`, `⚠️ Partial`, `✅ Fixed`, `💬 Accepted as-is` (reviewee's justification accepted),
`➖ Obsolete` (code removed).
