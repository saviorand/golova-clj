# golova.bb — headless Golova API server

Loads a Golova snapshot, replays it into a Datahike file DB, materialises
rules, and exposes an HTTP API for agent (or CLI) use.

## Prerequisites

- [Babashka](https://babashka.org/) ≥ 1.0
- Internet access on first run (downloads the Datahike pod binary, ~60 MB, cached at `~/.babashka/pods/`)

No JVM required.

## Getting a snapshot

In the Golova browser app: **Settings → Export** → save the `.edn` file.

The script only accepts **v2 snapshots** (exported from Golova after the
2025 unification). If you have an older export, import it into Golova first
and re-export.

## Running

```bash
bb scripts/golova.bb --snapshot path/to/snapshot.edn
```

Options:

| Flag | Default | Description |
|---|---|---|
| `--snapshot` | `snapshot.edn` | Path to the Golova v2 snapshot |
| `--db` | `/tmp/golova-bb` | Directory for the Datahike file store |
| `--port` | `7734` | HTTP port |

The server deletes and rebuilds the DB from the snapshot on every start,
so startup is the single source of truth. Startup takes 2–5 s depending
on KB size.

## Endpoints

All responses are EDN (`Content-Type: application/edn`).

### `GET /status`

```
{:entities 42 :datoms 310}
```

### `GET /entities`

All named entities (`:db/ident` values) across all domains.

```
{:entities (:alice :bob :carol …)}
```

### `GET /domains`

Entities that have a `:domain-label` (i.e. are domain roots).

```
{:domains (:people :tasks :books)}
```

### `GET /pull?e=:alice`

All facts about one entity. Ref values are resolved to idents.
Derived (rule-materialised) facts are included.

```
{:db/ident :alice
 :parent   [:bob]
 :ancestor [:bob :carol :dave]
 :in-domain :people}
```

### `GET /query?q=<datalog>`

Run a Datalog query. Supports two forms:

**Shorthand — where clauses only** (free vars auto-detected):
```
GET /query?q=[?e :parent :alice]
GET /query?q=(ancestor :alice ?d)     ← rule-call sugar
```

**Full query** (`:find` … `:where` …):
```
GET /query?q=[:find ?n :where [?e :db/ident :alice] [?e :name ?n]]
```

Response:
```edn
{:vars ["e"] :rows [[:bob] [:carol]]}
```

Rule-call sugar `(ancestor ?a ?d)` rewrites to `[?a :ancestor ?d]`, which
matches the materialised datoms — the same rewrite Golova uses for saved
queries.

### `POST /transact`

Body is EDN: `{:op :assert/:retract :triple [entity attr value]}`.

```bash
curl -X POST http://localhost:7734/transact \
  -H 'Content-Type: application/edn' \
  --data '{:op :assert :triple [:alice :note "Met at conference"]}'
```

Response: `{:ok true}`.

**Note:** writes are applied to the in-memory DB but not saved back to the
snapshot file. They survive until the server is restarted. If you want the
writes to round-trip into Golova, copy them into the snapshot's `:events`
vector or use Golova's git sync to commit them directly.

**Note:** rule materialisation runs once at startup. Writes that would
produce new derived facts won't be reflected until restart.

## Using from an agent

The server speaks plain HTTP + EDN. A Claude Code agent (or any tool-calling
agent) can discover the KB structure and query it:

```
# what domains exist?
GET /domains

# what do I know about alice?
GET /pull?e=:alice

# who are alice's ancestors?
GET /query?q=(ancestor :alice ?d)

# record a new fact
POST /transact   body: {:op :assert :triple [:alice :note "Speaks French"]}
```

## Caveats

- The Datahike pod binary is pinned to `0.8.1691` — the latest release with
  a macOS aarch64 native binary. Newer versions (0.8.1692+) only ship a JAR.
  Update the version in `golova.bb` once native binaries resume.
- v1 snapshots (`:domains` key, no `:version`) are not supported. Import into
  Golova and re-export.
