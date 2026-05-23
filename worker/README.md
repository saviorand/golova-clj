# golova-sync Worker

Cloudflare Worker that proxies a narrow two-endpoint surface to the GitHub
Git Data API. The browser app talks only to this Worker; the GitHub PAT
never reaches the browser.

## Endpoints

```
GET  /snapshot   → 200 { head_sha, files: { <path>: <utf-8>, … } }
POST /commit     → 201 { head_sha }                     on fast-forward success
                  → 409 { current_head }                on stale base_sha
                  → 4xx { error, details? }             on auth/validation errors
                  → 5xx { error }                       on upstream / internal errors

OPTIONS *        → 204 with CORS headers (preflight)
```

Both endpoints require `Authorization: Bearer <BEARER_TOKEN>`.

Implementation: `src/worker.ts` (~156 LOC).

## Setup

### 1. GitHub side

Create the kb repo. The branch must already have at least one commit;
the Worker reads HEAD before every operation.

```bash
gh repo create yourname/golova-kb --private
cd /tmp && git clone https://github.com/yourname/golova-kb.git
cd golova-kb
echo "# golova-kb" > README.md
git add . && git commit -m "init" && git push
```

Create a **classic** PAT at https://github.com/settings/tokens/new with
scope `repo` — or a fine-grained token scoped to just `golova-kb` with
**Contents: read & write**.

### 2. Worker config

Edit `wrangler.toml`:

```toml
[vars]
OWNER = "yourname"
REPO  = "golova-kb"
BRANCH = "main"
ALLOWED_ORIGIN = "https://your-golova-frontend.example"
```

Set the secrets:

```bash
cd worker
pnpm install
wrangler secret put GITHUB_PAT       # paste the PAT
wrangler secret put BEARER_TOKEN     # any long random string
```

`BEARER_TOKEN` is the value you'll paste into the Golova app's Settings →
Sync → "Bearer token" field.

### 3. Run locally

```bash
wrangler dev    # http://localhost:8787
```

Or deploy:

```bash
wrangler deploy
```

## Integration test

`harness/run.mjs` and `harness/concurrent.mjs` exercise the Worker against
a real GitHub repo. The Worker contract is otherwise unobservable from
the CLJS unit tests, which mock at the `fetch` boundary — see
`src/golova/state/sync.cljs`.

```bash
cd worker/harness
pnpm install
WORKER_URL=http://localhost:8787 \
BEARER_TOKEN=<same as wrangler secret> \
node run.mjs

# Then verify the 409 path:
node concurrent.mjs
```

Expected `run.mjs` output (abbreviated):

```
[1/5] Auth: no token → expect 401          status: 401 OK
[1/5] Auth: wrong token → expect 401       status: 401 OK
[2/5] CORS preflight (OPTIONS)             status: 204
[3/5] GET /snapshot                        head_sha: abc…  file count: 1
[4/5] POST /commit                         status: 201
[5/5] GET /snapshot again                  new head_sha: …  MATCH
```

Expected `concurrent.mjs` output: one 201, one 409 with `current_head`
populated (proves the LWW conflict path).

## What this Worker does NOT do

- It does NOT enforce that paths look like `domains/<x>/...edn` — the
  client (`golova.state.sync/snapshot->files`) is responsible for the
  layout. The Worker is a thin commit/checkout proxy.
- It does NOT do real merge resolution. On 409, the client refetches
  remote, replaces local state, and surfaces the clobber via a toast.
- It does NOT support branch-protected repos. The configured branch must
  accept direct PATCH-ref updates.
- It does NOT include AI-attribution trailers in commit messages.
