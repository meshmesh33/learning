---
description: Review a PR locally with the same 5-aspect process as the CI workflow (dry run by default)
argument-hint: <pr-number> [--post] [--verify]
allowed-tools: Read, Write, Glob, Grep, Agent, Task, TodoWrite, Bash(gh pr view:*), Bash(gh pr diff:*), Bash(gh pr review:*), Bash(gh pr comment:*), Bash(gh issue view:*), Bash(gh api:*), Bash(git diff:*), Bash(git log:*), Bash(git show:*), Bash(git fetch:*), Bash(git rev-parse:*)
---

Run the Claude PR review locally for PR `$ARGUMENTS`.

1. Parse the arguments: the first number is `PR_NUMBER`. `--post` means post to GitHub, otherwise this is
   a **dry run**. `--verify` selects MODE=verify, otherwise MODE=review.
2. `REPO` = `gh repo view --json nameWithOwner --jq .nameWithOwner`.
   Get `BASE_SHA`/`HEAD_SHA` with `gh pr view $PR_NUMBER --json baseRefOid,headRefOid`, then
   `git fetch origin pull/$PR_NUMBER/head`. For `--verify`, `SINCE_SHA` comes from the
   `claude-review:summary sha=` marker on the PR.
3. Follow `.github/claude-review/<MODE>.md`, `.github/claude-review/policy.md` and
   `.github/claude-review/comment-format.md` exactly as CI does.
4. **Dry run:** do not call any GitHub write endpoint (no `gh pr review`, `gh pr comment`, POST/PATCH
   `gh api`, or GraphQL mutations). Print instead:
   - the review event you would submit,
   - every inline comment as `path:line` followed by the rendered comment,
   - the overall body and the summary comment.
   Finish by asking whether to post it.
