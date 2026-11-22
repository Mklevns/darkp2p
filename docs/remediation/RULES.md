# Ground rules

1. Before the first edit in any step, list the repo, excluding node_modules, and confirm these paths: `server.js`, `public/index.html`, `public/404.html`, `Dockerfile`, `docker-compose.yml`, `Caddyfile`, `setup-ufw.sh`, `README.md`, `package.json`, `package-lock.json`. If the layout differs, map the paths and tell me before editing.
2. Read each target file in full before editing it. Find symbols by name (grep), not by line number. Keep every diff scoped to the step's tasks, with no drive-by reformatting or renames.
3. Preserve existing HTTP/WS APIs and message fields unless a task says **MIGRATE**. Server→client messages may gain fields but must not lose or rename any.
4. Never log or persist IP addresses (raw or from `X-Forwarded-For`), room IDs, invoice IDs, tokens, TURN credentials, SDP/ICE payloads or file names. Use only the existing `logInfo` / `logWarn` / `logError`. When logging errors from `fetch()` or BTCPay, log only `err.name`, `err.code` or the HTTP status, never the message, URL or cause.
5. Add no new runtime dependencies. Tests use only Node 22 built-ins (`node:test`, `node:assert`, `node:child_process`, `ws` from the existing dependency). Do not add eslint, prettier or jest.
6. No `TODO`s or stubs on error, crypto, validation or serialization paths. Every new map, queue or counter needs an explicit cap and an eviction rule.
7. The client wire format changes in Steps 5, 6 and 6.5. It stabilizes once session-nonce binding lands (Step 6.5, or the start of Step 7), and must be final and consistent before deployment in Step 7.
8. **Start of every step:** read `RULES.md` and `PROGRESS.md`, then run `npm run check && npm test`. If either fails, STOP and report before editing anything.
9. **End of every step:**
   - Run the step's gate.
   - Append an entry to `PROGRESS.md` with the step number, task IDs, files changed, the gate commands with their results, anything SKIPPED, and any open questions.
   - Print a checkpoint: the files changed, a 1–3 line diff summary per task, and the gate output.
   - Then STOP. Never start the next step on your own.
10. **STOP and ask me** if:
    - a fix needs an architectural decision the step doesn't make;
    - existing code contradicts a stated root cause;
    - a pinned image rejects a coturn or Caddy flag listed in a step.
11. If `docker`, `caddy`, `openssl` or `shellcheck` isn't available for a gate, mark it `SKIPPED (tool unavailable)` and continue. Don't fake results.
12. Don't commit or push unless I ask.
