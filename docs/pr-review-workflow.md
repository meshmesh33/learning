# Claude PR review workflow

Automated review of every pull request across five aspects: **Code Quality**, **Requirements**,
**Concurrency**, **Performance** and **Memory Leak**. Claude posts short, severity-tagged comments
(inline or overall), waits for the author's fix commits, checks each fix, and then approves or
requests changes again.

## Flow

```mermaid
flowchart TD
    A[PR opened / ready for review] --> B[Lead reviewer collects diff + requirements]
    B --> C1[code-quality-reviewer]
    B --> C2[requirements-reviewer]
    B --> C3[concurrency-reviewer]
    B --> C4[performance-reviewer]
    B --> C5[memory-leak-reviewer]
    C1 & C2 & C3 & C4 & C5 --> D[Merge, dedupe, verify, cap at 15]
    D --> E[One GitHub review: inline + overall comments]
    E --> F[Sticky summary comment with findings table + requirements coverage]
    F --> G{Open High/Medium?}
    G -- no --> H[APPROVE ✅]
    G -- yes --> I[REQUEST_CHANGES]
    I --> J[Author pushes fix commit]
    I --> K[Author replies on a thread]
    K --> L[discuss: explain, or accept and resolve]
    L --> G
    J --> M[verify: check each open finding against new code]
    M --> N[Reply ✅ Fixed / ❌ Not fixed / ⚠️ Partial, resolve fixed threads]
    N --> O[Review only the new diff for new issues / regressions]
    O --> F
```

| Event | Job / mode | What happens |
|---|---|---|
| PR `opened`, `reopened`, `ready_for_review` | `review` / review | Full review of the whole PR diff |
| New commits (`synchronize`) after a review | `review` / verify | Checks fixes, reviews only `last-reviewed..HEAD` |
| New commits with no previous review | `review` / review | Full review |
| Force-push / rebase | `review` / verify | Checks fixes against the whole PR diff |
| Reply on a Claude finding thread | `discuss` | Answers once: accepts and resolves, or explains |
| Comment `/claude-review` (maintainers) | `review` / review | Fresh full review |
| Draft PRs, bot PRs | (skipped) | Nothing |

## Comment format

```
🔴 High · Concurrency · Check-then-act race on `versionCache`

Concern: Two requests can both miss the cache and publish different versions.
Violates: Atomicity of compound actions (check-then-act)

Fix: Make the lookup and insert one atomic step.
  [suggestion block]
CR-3 · claude-review
```

Severity: 🔴 High and 🟠 Medium block approval; 🟡 Low never blocks.
Full spec: [`.github/claude-review/comment-format.md`](../.github/claude-review/comment-format.md).

## Files

| Path | Purpose |
|---|---|
| `.github/workflows/claude-pr-review.yml` | Triggers, mode detection, runs Claude |
| `.github/claude-review/review.md` | Instructions for the first full review |
| `.github/claude-review/verify.md` | Instructions for re-reviewing after fix commits |
| `.github/claude-review/discuss.md` | Instructions for replies on finding threads |
| `.github/claude-review/policy.md` | Severity, confidence bar, approval rules, requirement sources |
| `.github/claude-review/comment-format.md` | Exact comment/summary formats and hidden markers |
| `.claude/agents/*-reviewer.md` | The five specialist subagents (Java/Spring/JPA/Kafka checklists) |
| `.github/claude-review/cloud-session.md` | How the hourly cloud session runs the review (default) |
| `.claude/commands/review-pr.md` | `/review-pr <n>` to run the same review locally (dry run by default) |

State is kept **in the PR itself** through hidden HTML markers, with no database:
`claude-review:finding` (each finding), `claude-review:verdict` (each fix check) and
`claude-review:summary sha=…` (last reviewed commit).

## How it runs

### Option A: scheduled cloud session, automatic (no API key, no secrets)

A scheduled task starts a fresh Claude Code cloud session **every hour**, on your Claude subscription.
Each run checks the open PRs and does review / verify / discuss as needed. See
[`.github/claude-review/cloud-session.md`](../.github/claude-review/cloud-session.md).

- Reaction time: up to ~1 hour after a PR is opened or a fix is pushed.
- Comments are posted as **your** GitHub account. On PRs you opened yourself, GitHub does not allow
  self-approval or self-"request changes", so the review is posted as a comment with
  `Verdict: ✅ Would approve` / `❌ Changes requested`.
- Manage the task (pause, change frequency, delete) under scheduled tasks at claude.ai/code.

### Option B: watch a PR live from a cloud session

In any Claude Code cloud session on this repo, say **`Review PR #12 and watch it`**. Claude reviews
it right away, labels it `claude-watch` (so Option A skips it), and reacts within about a minute to
every push and every reply until the PR is merged or closed, or you say `stop watching #12`.
Details: `.github/claude-review/cloud-session.md`.

### Optional: GitHub Actions

`.github/workflows/claude-pr-review.yml` reacts within minutes and posts as a bot (so it can
approve your own PRs). It stays idle until you add a secret:

1. Install the Claude GitHub App on the repo: <https://github.com/apps/claude>.
2. On a computer, run `claude setup-token` (Pro/Max plan, **not** an API key) and save the printed
   token as the repo secret `CLAUDE_CODE_OAUTH_TOKEN`.
3. Settings → Actions → General → Workflow permissions → tick
   **"Allow GitHub Actions to create and approve pull requests"**.
4. Pause the scheduled task so the two don't both review.

## Writing PRs that get good requirement checks

Link the issue (`Closes #12`) or add a checklist to the PR description:

```markdown
## Acceptance criteria
- [ ] Publishing a product set creates a new immutable version
- [ ] Previous versions remain readable by version number
- [ ] Publishing the same set twice concurrently yields two distinct versions
```

## Tuning

- **Noise:** raise or lower the confidence bar and the 15-finding cap in `policy.md`.
- **Stack-specific rules:** edit the checklists in `.claude/agents/*.md`.
- **Cost:** `--max-turns` in the workflow; verify runs are cheaper because they only look at the new diff.
- **Try locally first:** `claude` → `/review-pr 12` (dry run) → `/review-pr 12 --post`.
