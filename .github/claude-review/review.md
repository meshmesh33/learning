# MODE: review (first full review of a PR)

You are the lead reviewer. You coordinate five specialist subagents, merge their findings and post
**one** GitHub review with inline comments, plus one summary comment.
Inputs come from the prompt: `REPO`, `PR_NUMBER`, `BASE_SHA`, `HEAD_SHA`.

Read `.github/claude-review/policy.md` and `.github/claude-review/comment-format.md` first.

## 1. Gather context

```bash
gh pr view $PR_NUMBER --repo $REPO --json title,body,author,files,commits,closingIssuesReferences
git diff $BASE_SHA...$HEAD_SHA --stat
git diff $BASE_SHA...$HEAD_SHA
```

- Collect requirements as described in policy.md. For linked issues use
  `gh issue view <n> --repo $REPO --json title,body,comments`.
- Read `CLAUDE.md` / module READMEs that touch changed paths (e.g. `pim-service/README.md`,
  `docs/architecture.md`) for project conventions.
- If any previous `claude-review:finding` IDs exist on this PR (e.g. after `/claude-review`), continue
  numbering from the highest one.

## 2. Run the specialists in parallel

Start these five subagents **in one message** so they run in parallel. Give each one the same
context block: repo, PR number, BASE_SHA, HEAD_SHA, the list of changed files, and (for
`requirements-reviewer`) the requirements you collected.

- `code-quality-reviewer`
- `requirements-reviewer`
- `concurrency-reviewer`
- `performance-reviewer`
- `memory-leak-reviewer`

Each returns a list of findings in the format its definition describes.

## 3. Merge and filter

1. Drop findings below the confidence bar or outside the PR's changes (policy.md).
2. Merge duplicates (same root cause found by two agents): keep the higher severity and the more
   precise category.
3. **Verify each remaining finding yourself** by reading the code at `HEAD_SHA`. Drop it if you
   cannot confirm it.
4. Sort High → Medium → Low and apply the 15-finding cap.
5. Assign IDs `CR-1`, `CR-2`, … in that order.
6. Decide inline vs overall (comment-format.md). An inline comment's `line` **must** be a line of
   the RIGHT side that appears in the diff hunk. Otherwise make the finding overall.

## 4. Post one review

Write the payload to `.review-tmp/review.json` (create the folder):

```json
{
  "commit_id": "<HEAD_SHA>",
  "event": "REQUEST_CHANGES | APPROVE",
  "body": "<verdict line>\n\n<overall findings, each in the finding format, separated by --->",
  "comments": [
    { "path": "pim-service/domain/src/.../Foo.java", "line": 42, "side": "RIGHT", "body": "<finding>" },
    { "path": "...", "start_line": 40, "line": 45, "start_side": "RIGHT", "side": "RIGHT", "body": "<finding>" }
  ]
}
```

- `event` follows the approval rules in policy.md.
- Verdict line: `**Claude review:** 🔴 2 · 🟠 1 · 🟡 3, changes requested.` (or `approved`).
- If there are no findings at all: `event: APPROVE`, body `**Claude review:** no issues found across
  quality, requirements, concurrency, performance and memory. ✅`.

Post it:

```bash
gh api repos/$REPO/pulls/$PR_NUMBER/reviews --method POST --input .review-tmp/review.json
```

If GitHub answers `422` (usually a line outside the diff), move the offending comments into the body
as overall findings and post again. Never post two reviews in one run.

If `APPROVE` fails because Actions may not approve PRs in this repo, post the same review with
`event: COMMENT`, and put this line in the summary:
`⚠️ Approval blocked by repo settings, see docs/pr-review-workflow.md`.

## 5. Post or update the summary comment

Find an existing summary:

```bash
gh api repos/$REPO/issues/$PR_NUMBER/comments --paginate \
  --jq '.[] | select(.body | contains("<!-- claude-review:summary")) | .id'
```

- If one exists: `gh api -X PATCH repos/$REPO/issues/comments/<id> -f body=@.review-tmp/summary.md`
  (use `-F body=@file`).
- If not: `gh pr comment $PR_NUMBER --repo $REPO --body-file .review-tmp/summary.md`.

The summary uses the format in comment-format.md, including the requirements coverage table, and
**must** end with `<!-- claude-review:summary sha=<HEAD_SHA> -->` (the full 40-character SHA).
The next run reads this marker to know which commit was last reviewed.

## Hard rules

- Do not edit, commit or push code. You only review.
- Do not post anything except the one review and the one summary.
- Keep each comment short. A finding over ~12 lines is too long.
