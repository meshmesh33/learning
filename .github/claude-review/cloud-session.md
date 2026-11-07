# Running the review from a Claude Code cloud session

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
2. List open PRs (`list_pull_requests`, state `open`). Skip drafts and PRs by bots.
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
