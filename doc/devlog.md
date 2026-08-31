# Dev Log — August 31, 2026

This log starts today. The design day that produced target 1 (August 30) is recorded only in
`AGENTS.md`, under `:design`, which is where the decisions live anyway.

## Goal

Take `robertluo.state-graph` from a spine to a library with an async layer: settle every recorded
open question, then spend the answers — and let the answers change the code that was already
written, rather than leaving them as notes about what a version 2 might do.

## Earlier in the day

Three commits landed before the design work and are summarised here only so the day reads whole.

- **`a588c93`** — target 1, the spine: `shape` and `compile`, ending in a working
  `(reduce step initial events)`. A shape IS an Ubergraph; a state is a map with an `:id`; a handler
  never sees the state.
- **`fb26d48`** — target 2, what the graph buys: `check` — reachability as a traversal from the root,
  dead ends, a partial subsumption checker that never lies, and the drawing.
- **`faf4231`** — the monorepo refactor, after which `smart-boundary` is a component like this one
  and the root holds only what is genuinely repo-wide.

## Changes

### Four decisions the library could not be built without (`799e609`)

- **Parallel means ACROSS INSTANCES**, and the argument is a data dependency rather than anything
  about manifold. `compile` selected the handler with `(idx [(:id state) (:id event)])`, so the
  handler for event n+1 was unknowable until event n had produced its state. Orthogonal regions
  inside one machine are out — that is a statechart and not this.
- **What is persisted is HISTORY**, and it was never really open: the README says it in its own words
  and `:a-shape-is-code` forces it. Shape versioning is out of v1 and stays out.
- **An instance has a fixed field**, written by the constructors, on the EVENT as well as the state —
  the event being the load-bearing half, since routing happens before any state is in hand.
- **A handler may answer a DEFERRED**, and the mechanism keeps manifold out of the core: `compile` is
  parameterised by *how a value becomes available* — a `then` and a `pure`. The synchronous default
  is `(fn [v f] (f v))` and `identity`, needs no dependency, and reproduces the old step exactly.

### The handler belongs to the event (`7c1d26a`, `5a2274a`)

- The README's own reading, recovered: `(event id schema handler out)` and
  `(transition from event to)`. The target still comes from the graph, so `A -submit-> B` beside
  `C -submit-> D` stays expressible.
- **The reason is not parallelism**, which is what it looks like. It is that two edges can no longer
  *disagree* about a handler — one declaration where there were two, so a construction-time check is
  replaced by a shape in which the error cannot be written. And it finishes the older thought: the
  handler's function schema is `[:=> [:cat <event schema>] <out>]`, both halves off the event and
  nothing off the graph.
- **An ignored event is not an error and is not silent.** The handler does not run, the data is never
  computed, and the step says it happened so history can record it.
- **`:instance` names a run**, optional, written by `initial`, and a handler cannot reach it — both
  `:id` and `:instance` go on *after* the merge.

### A trap, which is the fault a cycle hides (`2dd8895`)

- `traps` answers the reachable states from which no ending can be reached. It is `reachable` run
  backwards: `finishable` traverses the transposed graph from every `:final`.
- **Both other structural checks walk straight past it.** `unreachable` cannot see it — a forward
  traversal gets there. `dead-ends` cannot — a trap has out-edges. A dead end is a trap of size one.
- The exception that had kept it unbuilt belonged inside `finishable` rather than bolted onto the
  check: with no `:final` declared, every state is finishable *vacuously*, so the check falls silent
  on its own.

### Chained events, deferred with the answer's shape written down (`b06c61c`)

- **An external event is the ultimate source of a transition**; an internal one is a convenience, and
  what it buys is *handler reuse*.
- If it is ever built, the raise belongs to the STATE. Two arguments reach that separately: the
  programming model is simpler, and an entry raise is *unconditional*, so a cycle in the raise
  relation is a **proof** rather than a warning.
- Not designed, and the handler's signature is unchanged. `:open-questions` went back to one.

### Two events in flight at once (`b322600`, `6e74e10`)

- An earlier claim was **over-argued and marked as such**: every argument for "one machine is
  sequential" was about events arriving *one at a time*, and none touched two events *pending in the
  same state*, which is exactly what an async handler creates.
- **`both admitted` is not the condition.** `idle -start-> running` beside `idle -cancel-> cancelled`
  has both legal, and if `start` lands first the cancel meets a state with no `cancel` edge and is
  silently discarded.
- `check/confluence` publishes a verdict per pending pair per state; `check/commuting` reduces it to
  the proven pairs as plain data. The **general diamond** got implemented, not the self-loop
  shortcut: same four lookups, and `:no` for "not both self-loops" would have been a lie.
- One of the three conditions was already paid for — the intermediate states need no check, because
  if `[ta b]` is an edge then `subsumption` has already asked whether `ta` admits what `b` produces.

### The async layer (`e046269`)

- manifold 0.4.3, which closes the oldest entry in `:gaps-in-the-repository`. The REPL had to be
  restarted, exactly as that entry warned.
- `context` is `{:then d/chain :pure d/success-deferred}` and is the whole of what manifold
  contributes to the core. `drive` is one machine, serialised. `fan` partitions on `:instance` and
  runs one machine per key, concurrently.
- Both answer `{:states :done}` — two different things under two names. `:states` closes on either
  path, so a consumer is never left waiting on a machine that has stopped; `:done` carries the final
  state or *errors* with what the step threw, a defect having nowhere honest to sit in a stream of
  states.

## Key insights

- **Denormalisation paid for itself.** Moving the handler from the edge to the event changed
  `shape.clj` and *nothing else* — `compile` and `check` needed not one edit, because `transitions`
  already flattens the catalogue onto every edge and both read a shape only through it. The suites
  went green on the first run. A reading layer between the graph and its consumers is what let a
  structural change stay local.
- **Measured, not guessed.** "Commute by default" was proposed and the numbers refused it: every
  pair of events that can be pending in one state, over both fixtures, fails confluence — 100% of
  them. In `counter`, `stop`-then-`set` lands in `:done` and loses the `:set` outright. The finding
  is structural: different events going to different places is what a state machine is *for*, so
  confluence is the exception.
- **`s/connect` is asynchronous, and it cost a lost state.** The first `fan` forwarded each machine's
  stream into one output with `s/connect`; closing that output once every machine reported done
  dropped whatever was still in a pipeline. Seen, not theorised — instance `a` ran three events, two
  states came out, and its `:done` carried the third. Machines now write straight to the shared sink.
- **A 2-arity delegating to a 3-arity breaks under its own instrumentation** when the extra argument
  refuses nil, and `[:maybe some?]` is not the fix — it admits every value there is. The private
  helper that solved it later became unnecessary for an unrelated reason: `fan` keys an unnamed
  event under `nil`, which forced the argument schema to loosen anyway. The invariant worth having
  was never on the argument; it is on the enter schema, checked on every entry.
- **The parse is necessary and not sufficient.** `AGENTS.md` is one EDN value, and its rule says to
  verify it by reading it "and nothing weaker". Seven delimiter mistakes in one day proved the rule
  half right. Four were caught by the reader — three unterminated strings and a dropped `}`. **Two
  parsed perfectly**: a `}` in the wrong place, which quietly filed two `:design` entries as keys of
  `:context`; and a literal `"` inside a prose string, which closed it early and left a bare
  `order-1` *symbol* as a `:project-knowledge` key. What caught both was asserting the key **counts**
  and that every key is a keyword. The seventh, a `#_` where a value belonged, was caught by reading
  it back before running anything.
  So the rule wants two additions: check the shape of the keys, not only that the file reads; and
  never put a literal `"` inside those strings, the file using backticks everywhere else. A checker
  doing the delimiter half — unterminated strings and unbalanced braces, with line numbers — is
  cheap, and it caught the seventh mistake before the reader ever saw the file.
- **Building it found the gap that thinking about it had not.** The licensed concurrency is *not*
  implemented: taking it needs the handler run apart from the application — two handlers in flight,
  patches applied in completion order — and `compile` answers one step that does both. Calling that
  step twice from one state gives two whole states derived from it, and combining those is correct
  only for self-loops with disjoint patches, which is less than `commuting` licenses. Closing it is
  a decision rather than a refactor: split `compile` into a **patch** phase and an **apply** phase —
  the same shape the chained-events lean already wants, so one decision may pay for both.

Suite: 43 tests, 156 assertions, 0 failures — 40 unit, 3 `^:integration`. clj-kondo clean. All 29
public fns carry a `:malli/schema`, which an `instrument!` count of exactly 29 confirms better than
a grep can.

Not built, and named so nobody assumes otherwise: the **facade** (`robertluo.state-graph`), so an
application still requires the namespaces directly; and the **store**, datahike remaining a
dependency nothing uses.
