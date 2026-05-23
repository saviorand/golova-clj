// golova-sync Worker: proxies a narrow surface to the GitHub Git Data API.
// PAT never reaches the browser. Two endpoints + CORS + bearer auth.

interface Env {
  GITHUB_PAT: string;
  BEARER_TOKEN: string;
  OWNER: string;
  REPO: string;
  BRANCH: string;
  ALLOWED_ORIGIN: string;
}

const UA = "golova-sync-worker/0.1";

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

async function getHead(env: Env): Promise<{ sha: string; treeSha: string }> {
  const r = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/refs/heads/${env.BRANCH}`);
  if (!r.ok) throw Object.assign(new Error("ref fetch failed"), { status: r.status, body: await r.text() });
  const ref = await r.json() as any;
  const headSha = ref.object.sha as string;
  const c = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/commits/${headSha}`);
  if (!c.ok) throw Object.assign(new Error("commit fetch failed"), { status: c.status, body: await c.text() });
  const commit = await c.json() as any;
  return { sha: headSha, treeSha: commit.tree.sha };
}

async function snapshot(env: Env) {
  const head = await getHead(env);
  const t = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/trees/${head.treeSha}?recursive=1`);
  if (!t.ok) throw Object.assign(new Error("tree fetch failed"), { status: t.status, body: await t.text() });
  const tree = await t.json() as any;
  if (tree.truncated) throw Object.assign(new Error("tree truncated"), { status: 500 });
  const files: Record<string, string> = {};
  for (const entry of tree.tree as Array<{ path: string; type: string; sha: string }>) {
    if (entry.type !== "blob") continue;
    const b = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/blobs/${entry.sha}`);
    if (!b.ok) throw Object.assign(new Error("blob fetch failed"), { status: b.status, body: await b.text() });
    const blob = await b.json() as { content: string; encoding: string };
    files[entry.path] = blob.encoding === "base64"
      ? new TextDecoder("utf-8").decode(Uint8Array.from(atob(blob.content), c => c.charCodeAt(0)))
      : blob.content;
  }
  return { head_sha: head.sha, files };
}

async function commitMany(env: Env, baseSha: string, files: Record<string, string>, message: string) {
  // 1. Verify base_sha is current (cheap early-exit; race still possible at PATCH time).
  const head = await getHead(env);
  if (head.sha !== baseSha) return { conflict: true as const, current_head: head.sha };
  // 2. Create a blob per file.
  const blobs: Array<{ path: string; sha: string }> = [];
  for (const [path, content] of Object.entries(files)) {
    const b = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/blobs`, {
      method: "POST",
      body: JSON.stringify({ content, encoding: "utf-8" }),
    });
    if (!b.ok) throw Object.assign(new Error("blob create failed"), { status: b.status, body: await b.text() });
    const j = await b.json() as { sha: string };
    blobs.push({ path, sha: j.sha });
  }
  // 3. Create a tree on top of the current root tree.
  const t = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/trees`, {
    method: "POST",
    body: JSON.stringify({
      base_tree: head.treeSha,
      tree: blobs.map(({ path, sha }) => ({ path, mode: "100644", type: "blob", sha })),
    }),
  });
  if (!t.ok) throw Object.assign(new Error("tree create failed"), { status: t.status, body: await t.text() });
  const newTree = await t.json() as { sha: string };
  // 4. Create the commit.
  const c = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/commits`, {
    method: "POST",
    body: JSON.stringify({ message, tree: newTree.sha, parents: [baseSha] }),
  });
  if (!c.ok) throw Object.assign(new Error("commit create failed"), { status: c.status, body: await c.text() });
  const newCommit = await c.json() as { sha: string };
  // 5. Fast-forward the ref. force=false → GitHub refuses non-FF updates.
  const r = await gh(env, `/repos/${env.OWNER}/${env.REPO}/git/refs/heads/${env.BRANCH}`, {
    method: "PATCH",
    body: JSON.stringify({ sha: newCommit.sha, force: false }),
  });
  if (r.status === 422) {
    // Re-fetch HEAD so the client knows what to rebase against.
    const fresh = await getHead(env);
    return { conflict: true as const, current_head: fresh.sha };
  }
  if (!r.ok) throw Object.assign(new Error("ref update failed"), { status: r.status, body: await r.text() });
  return { conflict: false as const, head_sha: newCommit.sha };
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const origin = request.headers.get("Origin") ?? "";
    const allowed = origin === env.ALLOWED_ORIGIN ? origin : "";
    const cors = corsHeaders(allowed);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    // Auth
    const auth = request.headers.get("Authorization") ?? "";
    const expected = `Bearer ${env.BEARER_TOKEN}`;
    if (!ctEq(auth, expected)) return json({ error: "unauthorized" }, 401, cors);
    try {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/snapshot") {
        return json(await snapshot(env), 200, cors);
      }
      if (request.method === "POST" && url.pathname === "/commit") {
        const body = await request.json() as { base_sha: string; files: Record<string, string>; message?: string };
        if (!body.base_sha || !body.files) return json({ error: "missing base_sha or files" }, 400, cors);
        const res = await commitMany(env, body.base_sha, body.files, body.message ?? "golova sync");
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
