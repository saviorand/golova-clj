// Exercises the Worker against a real GitHub repo via `wrangler dev` or a deployed Worker.
// Env vars required:
//   WORKER_URL      e.g. http://localhost:8787
//   BEARER_TOKEN    matches the Worker's `wrangler secret put BEARER_TOKEN`
// Optional: OWNER, REPO are read by the WORKER itself, not by this script.

const { WORKER_URL, BEARER_TOKEN } = process.env;
if (!WORKER_URL || !BEARER_TOKEN) {
  console.error("Set WORKER_URL and BEARER_TOKEN");
  process.exit(1);
}

const headers = {
  "Authorization": `Bearer ${BEARER_TOKEN}`,
  "Content-Type": "application/json",
};

async function snapshot() {
  const r = await fetch(`${WORKER_URL}/snapshot`, { headers });
  if (!r.ok) {
    console.error("snapshot failed:", r.status, await r.text());
    process.exit(1);
  }
  return r.json();
}

async function commit(base_sha, files, message) {
  const r = await fetch(`${WORKER_URL}/commit`, {
    method: "POST",
    headers,
    body: JSON.stringify({ base_sha, files, message }),
  });
  return { status: r.status, body: await r.json() };
}

function pretty(obj) { return JSON.stringify(obj, null, 2); }

console.log("Round-trip test against", WORKER_URL);

// 1. Auth checks
console.log("\n[1/5] Auth: no token → expect 401");
{
  const r = await fetch(`${WORKER_URL}/snapshot`);
  console.log("  status:", r.status, r.status === 401 ? "OK" : "FAIL");
}

console.log("[1/5] Auth: wrong token → expect 401");
{
  const r = await fetch(`${WORKER_URL}/snapshot`, {
    headers: { Authorization: "Bearer wrong" },
  });
  console.log("  status:", r.status, r.status === 401 ? "OK" : "FAIL");
}

// 2. CORS preflight
console.log("\n[2/5] CORS preflight (OPTIONS) → expect 204 with allow headers");
{
  const r = await fetch(`${WORKER_URL}/snapshot`, {
    method: "OPTIONS",
    headers: { "Origin": "http://localhost:3000", "Access-Control-Request-Method": "GET" },
  });
  console.log("  status:", r.status);
  console.log("  allow-origin:", r.headers.get("Access-Control-Allow-Origin"));
}

// 3. GET /snapshot
console.log("\n[3/5] GET /snapshot");
const snap = await snapshot();
console.log("  head_sha:", snap.head_sha);
console.log("  file count:", Object.keys(snap.files).length);
console.log("  paths:", Object.keys(snap.files).slice(0, 10));

// 4. POST /commit — modify one file, add another
console.log("\n[4/5] POST /commit — modify one file and add one");
const stamp = new Date().toISOString();
const newFiles = {
  // Modify (or create) a sentinel file. Repo doesn't need to exist beforehand —
  // the test repo's main branch must already have at least one commit; the
  // README documents bootstrapping.
  "poc-sentinel.edn": `;; updated at ${stamp}\n{:hello "world" :n ${Math.floor(Math.random() * 1000)}}\n`,
  "poc-trace.edn": `;; appended at ${stamp}\n[${Date.now()}]\n`,
};
const c = await commit(snap.head_sha, newFiles, `poc test ${stamp}`);
console.log("  status:", c.status);
console.log("  body:", pretty(c.body));
if (c.status !== 201) {
  console.error("commit failed — aborting");
  process.exit(1);
}

// 5. GET /snapshot again — verify the new file is there
console.log("\n[5/5] GET /snapshot again — verify");
const snap2 = await snapshot();
const newSentinel = snap2.files["poc-sentinel.edn"];
console.log("  new head_sha:", snap2.head_sha, snap2.head_sha === c.body.head_sha ? "MATCH" : "MISMATCH");
console.log("  sentinel present:", !!newSentinel);
if (newSentinel) console.log("  sentinel content:", newSentinel.trim());

console.log("\nAll checks complete.");
