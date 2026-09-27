# Frequent Subgraph Mining Service (Task B)

A **local frequent-subgraph mining and pattern-counting service** over a collection
of labeled graphs. Inputs are directed graphs with **node labels** and **edge
labels**; outputs are frequent connected patterns with their **support** (number of
graphs containing them) and **embedding mappings** (pattern node → host graph node).

The service supports:

1. Configurable **minimum support**, **maximum pattern edge count**, and **allowed
   node/edge label** whitelists.
2. **Incremental updates** (add / modify / delete a graph) that recompute only the
   affected graph's postings; results are *identical* to a full re-mining.
3. **Snapshot isolation** between concurrent mutations and mining queries.
4. **Crash recovery** via an fsynced append-only WAL plus atomic snapshots.
5. **Hard limits** on pattern size, result count, candidate enumeration and runtime.
6. **Deterministic**, reproducible result ordering for the same input + configuration.

## Build & run

```bash
mvn spring-boot:run          # starts on http://localhost:8080
mvn test                     # full automated test suite
```

Configuration (`src/main/resources/application.properties`, all optional):

| property | default | meaning |
| --- | --- | --- |
| `fsm.min-support` | `2` | default query minimum support |
| `fsm.max-edges` | `5` | default query maximum pattern edges |
| `fsm.snapshot-every` | `100` | write a durable snapshot every N committed mutations (`0` = snapshots disabled) |
| `fsm.data-dir` | `data/fsm` | directory for `wal.log` and `snapshot.json` |

## Matching semantics

* Graphs are **directed labeled multigraphs**. An edge `(s, t, label)` matches a
  pattern edge `(u, v, lp)` iff the direction, edge label, and the node labels at the
  mapped endpoints all match exactly. Self-loops and parallel edges are preserved.
* A **pattern** is a **connected** set of edges (plus the single-node, zero-edge
  patterns used as the growth seeds). Two embeddings define the same pattern iff they
  are isomorphic as directed labeled graphs; isomorphism is decided by an exact
  canonical label computed over all label-preserving node permutations.
* A canonical key looks like:
  `n:A,B|e:0->1:knows` — node labels in canonical order, then edges
  `from->to:label`, sorted lexicographically. The single-node (zero-edge) pattern
  has an empty edge section, e.g. `n:Person|e:`.
* **Support** is the number of **distinct host graphs** containing at least one
  embedding (graph-based, not embedding-based). Every embedding is returned too.
* **Label whitelists**: an empty/null set means “unrestricted”; otherwise every node
  (resp. edge) label of a pattern must be in the allowed set.
* Patterns are returned in ascending canonical-key order; embeddings are ordered by
  graph id then node mapping, making output stable across runs.

## HTTP API

### Add or replace a graph (UPSERT)
```bash
curl -X POST http://localhost:8080/api/graphs \
  -H 'Content-Type: application/json' \
  -d '{"id":"g1","nodeLabels":{"a":"Person","b":"Person"},
       "edges":[{"source":"a","target":"b","label":"knows"}]}'
```

### Delete a graph
```bash
curl -X DELETE http://localhost:8080/api/graphs/g1
```

### Mine
```bash
curl -X POST http://localhost:8080/api/mine \
  -H 'Content-Type: application/json' \
  -d '{"minSupport":2,"maxEdges":3,
       "allowedNodeLabels":["Person"],
       "allowedEdgeLabels":["knows"],
       "maxResultPatterns":1000,"maxCandidates":100000,"timeoutMillis":5000}'
```

Response fields: `version` (committed sequence the query observed), `totalGraphs`,
`patterns[]` with `canonicalKey`, `nodeCount`, `edgeCount`, `support`, `embeddings[]`
(`graphId`, `nodeMapping` canonical-index → host node), plus status fields
`truncated`, `limitReason`, `scannedCandidates`, `elapsedMillis`.

## Incremental maintenance and consistency guarantees

* Committed state is an **immutable snapshot** containing the graph set and an
  **unpruned inverted index** `pattern → graphId → embeddings`. A mutation takes an
  exclusive write lock, re-enumerates **only the changed graph**, and replaces its
  postings; readers never block and simply observe one immutable snapshot reference.
  A query therefore sees either the complete pre-mutation state or the complete
  post-mutation state — never a partially mixed count.
* Because query-time filtering (support / size / labels) runs over an index that is
  maintained without support pruning, incremental results are identical to mining
  the current collection from scratch. `IncrementalConsistencyTest` asserts this
  after every one of a sequence of upserts, modifications and deletes.

## Durability and crash recovery

* Each mutation is appended to `wal.log` and **fsynced before it is published** as
  committed. On startup the service loads the latest atomic `snapshot.json`, replays
  WAL events with higher sequence numbers, rebuilds the index, and continues with the
  next monotonic sequence — no committed change is lost or double-counted.
* A record is considered committed only after its terminating newline is flushed; a
  torn trailing line after a crash is detected and ignored, and its sequence is
  reused. Snapshots are written to a temp file, fsynced and atomically renamed; the
  WAL is truncated only afterward, so recovery always starts from a consistent point.

## Resource limits

* `maxEdges` bounds pattern size (enumeration grows connected edge sets up to it).
* `maxResultPatterns` stops output at a fixed number and sets `truncated=true`.
* `maxCandidates` bounds enumeration states scanned; `timeoutMillis` bounds wall time.
* The maintained per-graph index itself has a hard budget (50,000 candidate states /
  3 s); a graph exceeding it is flagged, and subsequent queries report
  `truncated` with the graph ids whose counts may be partial. This prevents a single
  pathological graph from hanging commits or exhausting memory.

## Determinism

Canonical keys, TreeMap/TreeSet ordering, graph-id-sorted postings and canonical
embedding sorting together guarantee that identical input + configuration produces
byte-identical result ordering regardless of insertion order or thread interleaving.

## Code layout

```
model/      Graph, Pattern, Embedding, MiningConfig, MiningBudget, MiningResult, mutations
mining/     PatternCodec (canonical labels), SingleGraphPatterns, PatternIndex,
            SubgraphMiner, PatternKeyFilters
durability/ DurabilityStore (WAL + atomic snapshot), GraphCodec (deterministic JSON)
service/    FrequentSubgraphService (snapshot isolation, incremental index, recovery)
web/        MiningController, DtoMapper
```
