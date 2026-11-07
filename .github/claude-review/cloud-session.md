# Running the review from a Claude Code cloud session

Two options, both on your Claude subscription with no API key:

| | Option A: Scheduled (automatic) | Option B: Watch (on demand) |
|---|---|---|
| Starts | by itself, every hour | you ask in a Claude Code session: `Review PR #12 and watch it` |
| Which PRs | every open PR **without** the `claude-watch` label | only PRs you ask for (they get the `claude-watch` label) |
| Reacts to a push or reply | at the next hourly run (≤ 1 h) | within about a minute (GitHub events wake the session) |
| Stops | when you pause or delete the scheduled task | when the PR is merged or closed, or you say `stop watching #12` |

The review can run without GitHub Actions or any API key: a scheduled Claude Code cloud session
runs every hour on your Claude subscription. It uses the same `review.md`, `verify.md`, `discuss.md`,
`policy.md`, `comment-format.md` and the `.claude/agents/*` reviewers.

## Differences from the Actions workflow

Cloud sessions have **no `gh` CLI**. Wherever the mode files say `gh …`, use the GitHub MCP tools
(`mcp__github__*`; load them with ToolSearch first):

| Mode files say | Use instead |
|---|---|
| `gh pr view` / `gh pr diff` | `pull_request_read` (methods `get`, `get_diff`, `get_files`, `get_review_comments`, `get_reviews`, `get_comments`) |
| `gh issue view` | `issue_read` |
| list issue comments / find the summary | `pull_request_read` method `get_comments` |
| POST a review with inline comments | `pull_request_review_write` method `create` (pending) → `add_comment_to_pending_review` for each inline finding → `pull_request_review_write` method `submit_pending` with `event` and `body` |
| reply on a finding thread | `add_reply_to_pull_request_comment` |
| resolve a thread | `resolve_review_thread` |
| create / PATCH the summary comment | `add_issue_comment` / `update_issue_comment` |
| `gh pr review --approve` | `pull_request_review_write` method `create` with `event: APPROVE` |

Code is read locally: `git fetch origin pull/<n>/head:pr-<n>` and then `git diff`, `git show`, `Read`, `Grep`.

## Your own PRs

GitHub does not let an account approve **or** request changes on its own PR. Comments from the cloud
session are posted as the connected GitHub account (yours). So when the PR author is that same account:

- submit the review with `event: COMMENT` instead of `APPROVE` / `REQUEST_CHANGES`;
- put the verdict in the body and the summary: `**Verdict:** ✅ Would approve` or
  `**Verdict:** ❌ Changes requested`.

## Scheduled run (every hour, fresh session)

1. Make sure the review instructions are present. If `.github/claude-review/` is missing on the default
   branch, check it out from the setup branch:
   `git fetch origin ccr-f07a4a65-grvw2o && git checkout origin/ccr-f07a4a65-grvw2o -- .github/claude-review .claude`
2. List open PRs (`list_pull_requests`, state `open`). Skip drafts, PRs by bots, and PRs with the
   label `claude-watch` (those are handled live by Option B below).
3. For each PR decide what to do:

   | State | Action |
   |---|---|
   | no `claude-review:summary` comment | **review** (review.md) |
   | summary `sha=` ≠ current head SHA | **verify** (verify.md); `SINCE_SHA` = summary sha if it is an ancestor of head, else empty |
   | a human replied on a finding thread after Claude's last reply on it | **discuss** (discuss.md) for that thread |
   | comment `/claude-review` newer than the summary | **review** again (full) |
   | otherwise | nothing |

4. Just before posting, re-read the summary marker. If another run already reviewed this head SHA,
   stop (no duplicate reviews).
5. End with a 1–3 line report: which PRs were reviewed or verified, and the open finding counts.
   Do not commit, push or modify code.

## Option B: watch a PR live (on demand)

Trigger: a user in a Claude Code cloud session on this repo says e.g. `Review PR #12 and watch it`.

1. Add the label `claude-watch` to the PR (`issue_write`, method `update`, labels including the
   existing ones) so Option A skips it.
2. Run **review** mode now (or **verify** if a summary already exists), exactly as in the table above.
3. Call `subscribe_pr_activity` for the PR and end the turn. No polling.
4. On each GitHub event that wakes the session:
   - new commits (`synchronize` / push) → **verify** mode;
   - a human reply on a `claude-review:finding` thread → **discuss** mode;
   - a `/claude-review` comment → **review** mode;
   - anything else (CI, bot comments, Claude's own comments) → do nothing.
5. When the PR is merged or closed, or the user says stop: `unsubscribe_pr_activity` and remove the
   `claude-watch` label (if the PR is still open, Option A takes over again).
