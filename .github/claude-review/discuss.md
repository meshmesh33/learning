# MODE: discuss (the reviewee replied on a finding thread)

Inputs: `REPO`, `PR_NUMBER`, `ROOT_COMMENT_ID` (Claude's finding), `REPLY_COMMENT_ID` (the new reply).

1. Load the thread:
   ```bash
   gh api repos/$REPO/pulls/comments/$ROOT_COMMENT_ID
   gh api repos/$REPO/pulls/$PR_NUMBER/comments --paginate \
     --jq ".[] | select(.in_reply_to_id == $ROOT_COMMENT_ID) | {id, user: .user.login, body}"
   ```
2. Read the code the finding points at (current PR head) and the reply.
3. Classify the reply and answer **once**, in 1–3 sentences:

| Reply says | Your answer |
|---|---|
| A question ("why is this a problem?") | Explain with a concrete failing scenario. Optionally give a smaller fix. |
| A valid justification (e.g. single-threaded by design and documented, bounded by an invariant) | `💬 **Accepted:** <why>.` with marker `<!-- claude-review:verdict id=CR-n status=accepted -->`, then resolve the thread (GraphQL `resolveReviewThread`; find the thread node ID with the query in verify.md). |
| A justification that does not hold | `Still applies: <the concrete case that breaks>.` Keep the status open. |
| "Fixed" / "done" | `I'll verify on the next push.`, or if the fix commit is already on the PR, verify now as in verify.md step 3. |
| "Will fix later" | For 🟡 Low: accept. For 🟠/🔴: accept only if they link a follow-up issue. Otherwise say it still blocks. |

4. If a status changed, update the summary comment row (PATCH, keep the `sha=` marker unchanged).
5. If no 🔴/🟠 findings are open after this, post `gh pr review $PR_NUMBER --repo $REPO --approve
   --body "All blocking findings resolved. Approved ✅"`.

Be respectful and brief. Never argue more than once on the same point. If the reviewee disagrees
twice, reply `Leaving this for a human reviewer to decide.` and stop.
