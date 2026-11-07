# MODE: verify (the reviewee pushed new commits)

The reviewee pushed commits after a Claude review. Your job:

1. Decide for **every open finding** whether the new code fixes it.
2. Review **only the new changes** for new problems, including regressions the fix itself caused.
3. Approve, or request changes again.

Inputs come from the prompt: `REPO`, `PR_NUMBER`, `BASE_SHA`, `HEAD_SHA`, `SINCE_SHA`
(the last reviewed commit; empty after a force-push/rebase).

Read `.github/claude-review/policy.md` and `.github/claude-review/comment-format.md` first.

## 1. Load the previous state

```bash
# Review threads with their comments, resolution state and IDs
gh api graphql -F owner=${REPO%/*} -F name=${REPO#*/} -F pr=$PR_NUMBER -f query='
query($owner:String!,$name:String!,$pr:Int!){
  repository(owner:$owner,name:$name){
    pullRequest(number:$pr){
      reviewThreads(first:100){
        nodes{ id isResolved isOutdated path line
          comments(first:50){ nodes{ databaseId author{login} body createdAt } } } } } } }'

# Summary comment (overall findings + statuses)
gh api repos/$REPO/issues/$PR_NUMBER/comments --paginate \
  --jq '.[] | select(.body | contains("<!-- claude-review:summary")) | {id, body}'

# Review bodies (overall findings live here)
gh api repos/$REPO/pulls/$PR_NUMBER/reviews --paginate --jq '.[] | {id, body, commit_id}'
```

Build the list of findings from the `claude-review:finding` markers. A finding is **open** when its
latest verdict marker says `status=open`, or it has no verdict yet, and the summary does not mark it
Fixed, Accepted or Obsolete.

## 2. What changed

```bash
# If SINCE_SHA is set:
git log --oneline $SINCE_SHA..$HEAD_SHA
git diff $SINCE_SHA..$HEAD_SHA
# If SINCE_SHA is empty (force-push): compare the whole PR again
git diff $BASE_SHA...$HEAD_SHA
```

Also read the reviewee's replies on each thread. They may explain the fix or dispute the finding.

## 3. Verify each open finding

For each one, read the **current** code at `HEAD_SHA`, not only the diff, and decide:

| Result | When |
|---|---|
| ✅ Fixed | The root cause is gone on every path the finding named, and the fix adds no new issue. |
| ⚠️ Partial | Some paths are fixed, some are not. Still open. |
| ❌ Not fixed | Unchanged, or the change does not address the root cause. Still open. |
| ➖ Obsolete | The code in question was removed. |
| 💬 Accepted as-is | The reviewee's reply gives a valid reason (policy.md). |

For concurrency and memory findings, delegate the check to `concurrency-reviewer` /
`memory-leak-reviewer` with the finding text and ask: "is this fixed at HEAD_SHA, yes/no/partial, and why".
Run independent checks in parallel.

Then, for each **inline** finding thread:

- Reply on the thread (short format from comment-format.md, with the verdict marker):
  `gh api repos/$REPO/pulls/$PR_NUMBER/comments/<root databaseId>/replies -f body=@...` (`-F body=@file`)
- If Fixed, Obsolete or Accepted: resolve the thread:
  `gh api graphql -f query='mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{isResolved}}}' -F id=<thread node id>`
- Do not reply to a thread whose status has not changed since your last verdict on it. No noise.

**Overall** findings have no thread. Their verdicts go in the summary table and the review body.

## 4. Review the new changes

Run the five specialists from `review.md` step 2 **on the incremental diff only** (SINCE_SHA..HEAD_SHA),
in parallel, and tell them which findings are already known so they do not repeat them.
Filter and verify their results as in `review.md` step 3. New IDs continue after the highest existing
`CR-n`.

## 5. Post one review and update the summary

Post one review with `gh api repos/$REPO/pulls/$PR_NUMBER/reviews --method POST --input .review-tmp/review.json`:

- `commit_id`: HEAD_SHA
- `comments`: new findings only (inline)
- `body`:
  ```
  **Claude re-review** (<SINCE_SHA short>..<HEAD_SHA short>): ✅ 3 fixed · ❌ 1 open · 🆕 1 new
  ```
  then any new overall findings, then verdicts for open overall findings.
- `event`: from the approval rules in policy.md, counted over **all** open findings (old + new).
  When the result is APPROVE, end the body with `All blocking findings resolved. Approved ✅`.

Then PATCH the summary comment (same steps as `review.md` step 5): update every status, bump the run
counter, set `**Commit:**`, and set the marker to `<!-- claude-review:summary sha=<HEAD_SHA> -->`.

## Hard rules

- Never mark a finding Fixed without reading the current code that proves it.
- Never re-open a finding that a human resolved and that you previously Accepted.
- Do not edit, commit or push code.
