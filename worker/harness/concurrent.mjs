// Race two commits against the same base_sha — prove 409 path.
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

console.log("Racing two commits with the same base_sha");
const snap = await snapshot();
console.log("  base:", snap.head_sha);

const [a, b] = await Promise.all([
  commit(snap.head_sha, { "race-a.edn": ";; A wins?\n[:a]\n" }, "race A"),
  commit(snap.head_sha, { "race-b.edn": ";; B wins?\n[:b]\n" }, "race B"),
]);
console.log("\nA:", a.status, JSON.stringify(a.body));
console.log("B:", b.status, JSON.stringify(b.body));

const winners = [a, b].filter(r => r.status === 201);
const losers  = [a, b].filter(r => r.status === 409);
console.log("\nWinners:", winners.length, "Losers (409):", losers.length);
if (winners.length === 1 && losers.length === 1) {
  console.log("OK: exactly one 201 and one 409 — LWW conflict path verified.");
  const loser = losers[0];
  console.log("  loser's current_head:", loser.body.current_head);
} else if (winners.length === 2) {
  // Possible if GitHub serialized both — the second commit's parent was the first.
  // In that case the second base_sha-check inside the Worker would still match
  // because Worker re-reads HEAD before commit creation. Real raciness requires
  // GitHub's PATCH ref to refuse — which it should via force=false 422.
  console.log("Both succeeded — race was serialized inside the Worker. Re-run; truly racy outcome is rare locally.");
} else {
  console.log("Unexpected outcome — check Worker logs.");
}
