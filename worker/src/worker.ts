// golova-sync Worker: proxies a narrow surface to the GitHub Git Data API.
// PAT never reaches the browser. Per-request target (owner/repo/branch)
// lets a single worker deployment serve any repo the PAT can see.

interface Env {
  GITHUB_PAT: string;
  BEARER_TOKEN: string;
  OWNER: string;
  REPO: string;
  BRANCH: string;
  ALLOWED_ORIGIN: string;
}

interface Target {
  owner: string;
  repo: string;
  branch: string;
}

const UA = "golova-sync-worker/0.2";

function corsHeaders(origin: string) {
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

function json(body: unknown, status: number, cors: Record<string, string>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...cors },
  });
}

// constant-time string equality
function ctEq(a: string, b: string) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// Per-request target. Query-string params win, env defaults are the
// fallback so existing deployments keep working without a client update.
function targetFromUrl(env: Env, url: URL): Target {
  return {
    owner:  url.searchParams.get("owner")  ?? env.OWNER,
    repo:   url.searchParams.get("repo")   ?? env.REPO,
    branch: url.searchParams.get("branch") ?? env.BRANCH,
  };
}

async function gh(env: Env, path: string, init: RequestInit = {}) {
  const url = `https://api.github.com${path}`;
  const headers = {
    "Authorization": `Bearer ${env.GITHUB_PAT}`,
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": UA,
    ...(init.headers || {}),
  };
  return fetch(url, { ...init, headers });
}

async function getHead(env: Env, t: Target): Promise<{ sha: string; treeSha: string }> {
  const r = await gh(env, `/repos/${t.owner}/${t.repo}/git/refs/heads/${t.branch}`);
  if (!r.ok) throw Object.assign(new Error("ref fetch failed"), { status: r.status, body: await r.text() });
  const ref = await r.json() as any;
  const headSha = ref.object.sha as string;
  const c = await gh(env, `/repos/${t.owner}/${t.repo}/git/commits/${headSha}`);
  if (!c.ok) throw Object.assign(new Error("commit fetch failed"), { status: c.status, body: await c.text() });
  const commit = await c.json() as any;
  return { sha: headSha, treeSha: commit.tree.sha };
}

async function snapshot(env: Env, t: Target) {
  const head = await getHead(env, t);
  const tr = await gh(env, `/repos/${t.owner}/${t.repo}/git/trees/${head.treeSha}?recursive=1`);
  if (!tr.ok) throw Object.assign(new Error("tree fetch failed"), { status: tr.status, body: await tr.text() });
  const tree = await tr.json() as any;
  if (tree.truncated) throw Object.assign(new Error("tree truncated"), { status: 500 });
  const files: Record<string, string> = {};
  for (const entry of tree.tree as Array<{ path: string; type: string; sha: string }>) {
    if (entry.type !== "blob") continue;
    const b = await gh(env, `/repos/${t.owner}/${t.repo}/git/blobs/${entry.sha}`);
    if (!b.ok) throw Object.assign(new Error("blob fetch failed"), { status: b.status, body: await b.text() });
    const blob = await b.json() as { content: string; encoding: string };
    files[entry.path] = blob.encoding === "base64"
      ? new TextDecoder("utf-8").decode(Uint8Array.from(atob(blob.content), c => c.charCodeAt(0)))
      : blob.content;
  }
  return { head_sha: head.sha, files };
}

async function commitMany(env: Env, t: Target, baseSha: string, files: Record<string, string>, message: string) {
  // 1. Verify base_sha is current (cheap early-exit; race still possible at PATCH time).
  const head = await getHead(env, t);
  if (head.sha !== baseSha) return { conflict: true as const, current_head: head.sha };
  // 2. Create a blob per file.
  const blobs: Array<{ path: string; sha: string }> = [];
  for (const [path, content] of Object.entries(files)) {
    const b = await gh(env, `/repos/${t.owner}/${t.repo}/git/blobs`, {
      method: "POST",
      body: JSON.stringify({ content, encoding: "utf-8" }),
    });
    if (!b.ok) throw Object.assign(new Error("blob create failed"), { status: b.status, body: await b.text() });
    const j = await b.json() as { sha: string };
    blobs.push({ path, sha: j.sha });
  }
  // 3. Create a tree on top of the current root tree.
  const tr = await gh(env, `/repos/${t.owner}/${t.repo}/git/trees`, {
    method: "POST",
    body: JSON.stringify({
      base_tree: head.treeSha,
      tree: blobs.map(({ path, sha }) => ({ path, mode: "100644", type: "blob", sha })),
    }),
  });
  if (!tr.ok) throw Object.assign(new Error("tree create failed"), { status: tr.status, body: await tr.text() });
  const newTree = await tr.json() as { sha: string };
  // 4. Create the commit.
  const c = await gh(env, `/repos/${t.owner}/${t.repo}/git/commits`, {
    method: "POST",
    body: JSON.stringify({ message, tree: newTree.sha, parents: [baseSha] }),
  });
  if (!c.ok) throw Object.assign(new Error("commit create failed"), { status: c.status, body: await c.text() });
  const newCommit = await c.json() as { sha: string };
  // 5. Fast-forward the ref. force=false → GitHub refuses non-FF updates.
  const r = await gh(env, `/repos/${t.owner}/${t.repo}/git/refs/heads/${t.branch}`, {
    method: "PATCH",
    body: JSON.stringify({ sha: newCommit.sha, force: false }),
  });
  if (r.status === 422) {
    // Re-fetch HEAD so the client knows what to rebase against.
    const fresh = await getHead(env, t);
    return { conflict: true as const, current_head: fresh.sha };
  }
  if (!r.ok) throw Object.assign(new Error("ref update failed"), { status: r.status, body: await r.text() });
  return { conflict: false as const, head_sha: newCommit.sha };
}

// List every repo the PAT can reach. Used by the Settings UI to render a
// dropdown of sync targets. Paginated under the hood; we cap at ~500 to
// keep payloads sensible (GitHub returns up to 100 per page).
async function listRepos(env: Env): Promise<Array<{ full_name: string; default_branch: string; private: boolean }>> {
  const out: Array<{ full_name: string; default_branch: string; private: boolean }> = [];
  for (let page = 1; page <= 5; page++) {
    const r = await gh(env, `/user/repos?per_page=100&page=${page}&sort=updated&affiliation=owner,collaborator,organization_member`);
    if (!r.ok) throw Object.assign(new Error("repo list failed"), { status: r.status, body: await r.text() });
    const items = await r.json() as Array<{ full_name: string; default_branch: string; private: boolean }>;
    for (const it of items) {
      out.push({ full_name: it.full_name, default_branch: it.default_branch, private: it.private });
    }
    if (items.length < 100) break;
  }
  return out;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const origin = request.headers.get("Origin") ?? "";
    const allowedOrigins = env.ALLOWED_ORIGIN.split(",").map(s => s.trim());
    const allowed = allowedOrigins.includes(origin) ? origin : "";
    const cors = corsHeaders(allowed);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    // Auth
    const auth = request.headers.get("Authorization") ?? "";
    const expected = `Bearer ${env.BEARER_TOKEN}`;
    if (!ctEq(auth, expected)) return json({ error: "unauthorized" }, 401, cors);
    try {
      const url = new URL(request.url);
      const target = targetFromUrl(env, url);
      if (request.method === "GET" && url.pathname === "/snapshot") {
        return json(await snapshot(env, target), 200, cors);
      }
      if (request.method === "GET" && url.pathname === "/repos") {
        return json({ repos: await listRepos(env) }, 200, cors);
      }
      if (request.method === "POST" && url.pathname === "/commit") {
        const body = await request.json() as { base_sha: string; files: Record<string, string>; message?: string };
        if (!body.base_sha || !body.files) return json({ error: "missing base_sha or files" }, 400, cors);
        const res = await commitMany(env, target, body.base_sha, body.files, body.message ?? "golova sync");
        if (res.conflict) return json({ error: "stale base", current_head: res.current_head }, 409, cors);
        return json({ head_sha: res.head_sha }, 201, cors);
      }
      return json({ error: "not found" }, 404, cors);
    } catch (e: any) {
      const status = e?.status ?? 500;
      return json({ error: e?.message ?? "internal error", details: e?.body }, status, cors);
    }
  },
};
