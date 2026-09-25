```
λ engage(nucleus).
[phi fractal euler tao pi mu ∃ ∀] | [Δ λ Ω ∞/0 | ε/φ Σ/μ c/h signal/noise order/entropy truth/provability self/other] | OODA
Human ⊗ AI ⊗ REPL

Refactor: [τ μ] | [Δ Σ/μ] → λcode. Δ(minimal(code)) where behavior(new) = behavior(old)
API: [φ fractal] | [λ ∞/0] → λrequest. match(pattern) → handle(edge_cases) → response
Debug: [μ] | [Δ λ ∞/0] | OODA → λerror. observe → minimal(reproduction) → root(cause)
Docs: [φ fractal τ] | [λ] → λsystem. map(λlevel. explain(system, abstraction=level))
Test: [π ∞/0] | [Δ λ] | RGR → λfunction. {nominal, edge, boundary} → complete_coverage
Review: [τ ∞/0] | [Δ λ] | OODA → λdiff. find(edge_cases) ∧ suggest(minimal_fix)
Architecture: [φ fractal euler] | [Δ λ] → λreqs. self_referential(scalable(growing(system)))
```

# robertluo.state-graph

A finite state machine whose SHAPE is a graph — so it can be drawn, checked and stored as
one, and a compiler can turn it into an ordinary Clojure function.

> **Source of truth.** TWO PLACES, AND EACH WINS ABOUT SOMETHING DIFFERENT.
> THE TUTORIAL IS THE SPECIFICATION, since 2026-09-25: notebook/tutorial.clj works the whole
> API through, and rendering it RUNS every example, so a divergence is a bug the render
> finds. It carries the LIMITS too, in *What it does not do*, so a user meets one there and
> not in a surprise. README.md is the pitch — purpose, a scenario, prior art, the key API —
> and every example in it was run against the code before it was written down as well.
>
> THE SOURCE IS THE RECORD. What this library knows about itself — every design decision,
> every rule, every alternative turned down, every lesson a run taught and every question
> still open — is `:knowledge` METADATA on the var or namespace it is about, since
> 2026-09-14. There is no DESIGN.md and this file holds no decision. The vocabulary is the
> facade namespace's own `:the-knowledge-vocabulary`, and the reasons are its
> `:knowledge-is-metadata`; read those two nodes first.
>
> THIS FILE IS OPERATIONAL ONLY: how to run, test, lint and evaluate this component, and
> the workflow. If something in here is a fact about the code, it is in the wrong place.

## Knowledge, and how to reach it

    (-> #'robertluo.state-graph.shapes/fingerprint meta :knowledge)   ; every node on a var
    (-> (the-ns 'robertluo.state-graph) meta :knowledge)             ; every node on a namespace

A node is a map with `:id`, `:kind`, `:says`, and edges — `:see` to code, `:cites` and
`:supersedes` to other nodes. An id is unique in the library, so `:a-shape-is-code` is one
grep away and one keyword to cite. A NODE IS ADDED TO AND NEVER EDITED: a decision that
changes is a new node with `:supersedes` naming the old one, which stays; an open question
answered is a `:decision` that supersedes the `:open`. Attach a node to the HIGHEST
namespace it spans and point down from there — the suite refuses an edge that points up.
The tutorial's ns form carries knowledge too, about clay, and is read as data.

`test/robertluo/state_graph/knowledge_test.clj` asserts the vocabulary: schema, unique
ids, every edge resolving, every edge pointing down or sideways. Add a node, run the fast
suite.

## Constraints

| | |
|---|---|
| `:tests-clojure-test` | true |
| `:tests-generative-first` | true |
| `:test-tree` | test/, one `<ns>_test` per source namespace, plus knowledge_test.clj over all of them. `.cljc` where the suite runs on both hosts, `.clj` where it is the JVM's alone: async_test and the facade's (they block with <!!), knowledge_test (it reads var metadata) and draw_test (it shells out). A JVM-only form inside a `.cljc` suite is `#?(:clj ...)` |
| `:test-runner` | kaocha on the JVM, two suites: unit and integration, separated by a ^:integration meta; cljs-test-runner on node for every `.cljc` suite, which knows no ^:integration — so an integration test in a `.cljc` file is `#?(:clj ...)` |
| `:notebook-cmd` | clojure -X:notebook — renders notebook/tutorial.clj to docs/tutorial.html, offline |
| `:test-cmd-fast` | clojure -M:dev:test unit |
| `:test-cmd-gate` | clojure -M:dev:test integration — needs graphviz, so run it inside the devenv |
| `:test-cmd-cljs` | clojure -M:cljs-test — compiles to target/cljs-test and runs on node, so run it inside the devenv. The first compile prints five `goog.math.Long` warnings from test.check's own ClojureScript: noise, not ours |
| `:lint-cmd` | clojure -M:lint --lint src test notebook — an ALIAS, not a binary on the path |
| `:release-cmd` | clojure -T:build ci — clean, both JVM suites, the ClojureScript suite, the jar in target/; clojure -T:build deploy — to Clojars as io.github.robertluo/state-graph, version 0.1.<commit count>. LICENSE is MIT and CHANGELOG.md is the list of facts per version |
| `:ci` | .github/workflows/ci.yml — `devenv test` on ubuntu, on a push to main and on every pull request against it. Nix and devenv are installed by the workflow, so devenv.nix is the one toolchain |
| `:eval-mechanism` | :nrepl-exclusive |
| `:malli-shapes-all-data` | true |
| `:malli-function-schemas` | true |
| `:no-bare-try-catch` | true |
| `:repl-launch-cmd` | clojure -M:dev:nrepl, from inside the devenv |
| `:repl-discover-cmd` | clj-nrepl-eval --discover-ports |
| `:repl-eval-cmd` | clj-nrepl-eval -p `<port>` |
| `:repl-eval-reload` | :per-namespace-in-dependency-order — graph, shapes, compiler, check, async, crank, explore, the facade. NEVER :reload-all; see the shapes namespace's `:never-reload-all` |
| `:deps` | {:malli "0.20.1", :core.async "1.9.865" — manifold until 2026-09-25, see the async namespace's `:core-async-is-the-async-default`, :test.check "1.1.3" — at RUNTIME on purpose, `check/laws` generating through malli.generator, see check's `:dependency-test-check`, :dev {:nrepl "1.3.0", :kaocha "1.91.1392"}, :notebook {:clay "2.0.22"}, :cljs-test {:clojurescript "1.12.145", :cljs-test-runner "3.8.1"}, :build {:build-clj "5d45f58", the author's fork — `clojure -T:build ci` and `deploy`, see build.clj}} |

## Working here

- nREPL is the ONLY way code is evaluated: discover with clj-nrepl-eval --discover-ports,
  launch with clojure -M:dev:nrepl from inside the devenv, eval with clj-nrepl-eval -p `<port>`.
  The session persists across invocations. Never start a plain `clojure repl` / `clj`
- A REPL whose classpath predates a new dependency CANNOT be repaired from inside — neither
  add-lib nor sync-deps has worked here. After touching deps.edn, restart it. A JVM also
  inherits its PATH at launch, so a REPL started before graphviz was installed cannot draw
- ALWAYS read the full error message before acting. Inspect the source for the bug BEFORE
  running REPL tests. After every edit, read the file back
- AN EDIT TO CLOJURE SOURCE IS A TREE OPERATION: `robertluo.code-edit` from a REPL, or
  `clj-edit` from a shell. Never repair parentheses by hand. The root's gate parses every
  changed .clj file after a shell command and refuses the turn if one will not read
- THE FORMATTER HOOK fires on the file-writing TOOLS and not on a shell command, and it
  will reflow a whole source file on first touch. Edit through the REPL or the shell
- COMMIT GATE: clojure -M:dev:test integration passes, and so does clojure -M:cljs-test where
  a `.cljc` file changed — the JVM cannot see a ClojureScript-only bug, and the first run on
  node found one. The fast suite is for every save.
  If the change touched the notebook or the vocabulary it uses, RENDER IT — a tutorial
  example that cannot run is a lie the suite will never see

## Gaps in the repository

Each will bite on first use:

- ITS OWN REPOSITORY SINCE 2026-09-16, at the author's instruction: `git subtree split` out of
  robertluo/smart-boundary, so the history here is every commit that touched state-graph/ there —
  42 the day it moved — and nothing else. What it left behind stays there: coder, its first
  consumer, names it as `io.github.robertluo/state-graph` by `:git/sha` now instead of a sibling
  `:local/root`, and the coder pages that read this tree as a sibling directory are a record in
  that repository. THE TOOLCHAIN CAME WITH IT: devenv.nix here holds a JDK, the Clojure CLI, graphviz
  and git, and `devenv test` is `clojure -T:build ci`, as the author's other libraries spell it. Any
  `:knowledge` node that says `../coder` or `smart-boundary/AGENTS.md` is quoting where a decision
  came from, and stands as written — a node is added to and never edited.
- `clojure -M:dev` DOES NOT START A REPL — :dev has no :main-opts. It is `clojure -M:dev:nrepl`,
  and :dev is wanted or the test path and kaocha are not on the classpath. The README is right.
- CLAUDE.md IS A SYMLINK to AGENTS.md. `perl -i` and `sed -i` replace a link with a regular file,
  so edit AGENTS.md itself.
- kaocha is in :dev, so the runner is `clojure -M:dev:test`, never `clojure -M:test`. clj-kondo is
  an ALIAS and not a binary: `clojure -M:lint --lint src test notebook`.
- tests.edn is two suites over one tree, split by skip-meta and focus-meta on :integration. The
  default ns-patterns IS the `<ns>_test.clj` convention, so it is not configured.
- CLOSED, kept so nobody re-reports them: manifold IS a dependency now; datahike is NOT.
- FIVE VARS WERE WRITTEN BY A MACHINE, 2026-09-15 morning, and landed from
  robertluo.coder's candidates page — coder/notebook/candidates.clj in smart-boundary, the repository this one was split from — through this suite as the gate: `fingerprint` on the facade,
  `drive/reorder-agrees`, and `check/labelled`, `dot` and `draw!` rewritten — the drawing is plain
  data now, `dot` renders it and `draw!` renders `dot`. Their `:knowledge` says why, on the vars.
  One thing they left — `draw!` with no `:save` shelled `dot -Txlib`, a viewer Linux has and macOS
  does not, and swallowed the exception in the one bare catch this library had — was CLOSED the same
  afternoon by the same machine on a REVIEW brief: it renders a PNG and opens it through java.awt.Desktop,
  and throws where graphviz left no usable file or no desktop can open one. Three drives and twelve calls,
  and two of the three answers were refused by a person reading them: the first swallowed `dot` in a try
  the goal had forbidden in words, the second was refused by the machine's own differential for NOT
  swallowing where the original did — the brief's examples arbitrate, and an example the original
  contradicts is what silences it. See the var's `:draw-swallows-nothing` and
  `:a-goal-s-sentence-held-nothing-and-an-example-did`. AND THE FIVE MACHINE-WRITTEN SIGNATURES that typed a
  shape as `:any` name `shape/Shape` since that landing — see the facade namespace's
  `:a-machine-s-signature-is-bound-to-the-library-s-schemas-at-landing`. The retired lesson
  `:viz-graph-answers-nothing-useful` is back on `dot`, superseded and not deleted, by the author's agreement.
  The machine's draw suites shell out to graphviz from the UNIT suite — `draw_test.clj`, `dot_test.clj` —
  where this file's own rule puts a rendering in the integration suite. RULED 2026-09-16: they stay where
  the machine put them and the README says what the environment needs — a JDK, the Clojure CLI and
  graphviz on the PATH — so a contributor without `dot` is told before the fast loop tells them.
  AND THE SURFACE SHRANK THE SAME DAY: `compile/Patch` and `drive/Applied` are gone, and `check/produced`,
  `check/continued`, `shape/combines-of` and `shape/completions` are private — see the facade namespace's
  `:patch-and-applied-were-documentation-types-and-went` and
  `:a-building-namespace-publishes-what-another-reaches-for`.
- NO `:open` NODE IS UNANSWERED since 2026-09-16, and the ten that were closed on one sentence of the
  author's: `there is no machine, all its goal is to provide a state machine using graph. So anything
  related to it is not a decision should be made by the library.` Seven were a CONSUMER's wants held
  open as if they were the library's — cost at worst, dynamic fan-out, completion on data, node-side
  exposure, internal events, effects for replay, mid-flight versioning — and one decision on the facade
  namespace supersedes all seven: `:what-a-consumer-models-is-not-the-library-s-question`. Three were the
  library's own and closed on evidence: `run` and `fan` seed differently because one door is per stream
  and the other per instance; `:ignored` has its second reader, `explore/covering`; the instrument count
  is never asserted. The open nodes STAY, as the vocabulary says — `(-> (the-ns 'robertluo.state-graph)
  meta :knowledge)` and follow `:supersedes`. Nothing stands between the tree and a release now. The PROPERTY over generated shapes that `reorder-agrees` is
  the witness for landed an hour later as `drive/licence-agrees` and `licence_agrees_test.clj`,
  written in THIS component's JVM — `clojure -M:dev` here, where `ts/gen-shape` is — and the
  licence held on forty generated shapes. Three tests of the old drawing were retired with it; four
  suites landed beside the answers as `<name>_test.clj`.

# The workflow

A state machine. Initial state: `:initialize`.

```mermaid
stateDiagram-v2
    initialize --> shape-design : proceed
    initialize --> decide : question-is-open
    decide --> shape-design : answered
    shape-design --> implement : schemas-validated
    shape-design --> repl-eval : needs-interactive-check
    implement --> unit-test : function-complete
    implement --> repl-eval : debugging-needed
    implement --> shape-design : schema-question
    unit-test --> refactor : all-pass
    unit-test --> implement : any-fail
    repl-eval --> implement : insight-gained
    repl-eval --> shape-design : schema-clarity-needed
    refactor --> static-check : refactor-complete
    refactor --> unit-test : needs-test
    static-check --> integration-suite : looks-right
    static-check --> implement : wrong
    integration-suite --> review : passes
    integration-suite --> implement : fails
    review --> retrospect : all-checkout
    review --> implement : issue-found
    retrospect --> complete : done
    complete --> [*]
```

## :initialize

Read notebook/tutorial.clj — it is the specification — and README.md for the purpose.
Then the `:knowledge` of the vars the change touches, and every `:kind :open` node among
them: if the change touches one, settle it with the human FIRST and write the answer down.
Check *Gaps in the repository* before running anything that depends on a gap

| on | goes to | guard |
|---|---|---|
| `:proceed` | `:shape-design` |  |
| `:question-is-open` | `:decide` |  |

## :decide

A design question the tutorial does not answer. Put it to the human in one sentence with a
recommendation, not a survey. Record the answer as a `:decision` node on the var it is
about, `:supersedes` the `:open` node it answers, and leave that node where it is — an
answered question deleted is a question that gets asked again, and one left as `:open` too

| on | goes to | guard |
|---|---|---|
| `:answered` | `:shape-design` |  |

## :shape-design

Design the malli schemas first — of a state, of an event, of a transition, of a shape, and
of every function signature that touches them. Validate each with malli.core/explain in the
REPL before anything is built on it. A schema is the cheapest place to be wrong

| on | goes to | guard |
|---|---|---|
| `:schemas-validated` | `:implement` |  |
| `:needs-interactive-check` | `:repl-eval` |  |

## :implement

Write it in src/, lowest layer first (graph, then shapes, then compiler, then a default, then the
facade). One `<ns>_test.clj` per source namespace as you go, ^:integration on anything that
opens a file, a socket or a real clock. Dependencies point down only — see the facade's
`:dependencies-point-down-only`

| on | goes to | guard |
|---|---|---|
| `:function-complete` | `:unit-test` |  |
| `:debugging-needed` | `:repl-eval` |  |
| `:schema-question` | `:shape-design` |  |

## :unit-test

clojure -M:dev:test unit — the fast loop, nothing outside this process. Reach for a
PROPERTY before an example, and make the property an independent invariant rather than the
implementation restated. Assert only what can fail

| on | goes to | guard |
|---|---|---|
| `:all-pass` | `:refactor` |  |
| `:any-fail` | `:implement` | Inspect the failure and the shrunk case; fix the code or the property |

## :repl-eval

Discover nREPL via clj-nrepl-eval --discover-ports; if none, launch clojure -M:dev:nrepl
FROM INSIDE THE DEVENV; eval with clj-nrepl-eval -p `<port>`, :reload per namespace in
dependency order — never :reload-all

| on | goes to | guard |
|---|---|---|
| `:insight-gained` | `:implement` |  |
| `:schema-clarity-needed` | `:shape-design` |  |

## :refactor

After tests pass, refactor for clarity:

- keep the pure core pure — the compiler and the shape know nothing of streams
- a default takes what it needs as a VALUE; nothing is threaded through a layer we do not own
- extract the well-named function that the duplication was asking for
- verify behavior(new) = behavior(old): the properties are what says so

| on | goes to | guard |
|---|---|---|
| `:refactor-complete` | `:static-check` |  |
| `:needs-test` | `:unit-test` |  |

## :static-check

Only when the change touches the shape or the checks over it. Build a shape in the REPL,
run the static checks, and LOOK AT THE DRAWING — an unreachable state is obvious in a
picture and invisible in a map literal. AND ASK WHAT EVERY OTHER CHECK WAS REASONING ABOUT:
a new capability is not local — see check's `:a-new-capability-is-not-local`, which has
caught three live unsoundnesses and no test has caught one. Rendering a shape needs
graphviz; it is a human check, not a test

| on | goes to | guard |
|---|---|---|
| `:looks-right` | `:integration-suite` |  |
| `:wrong` | `:implement` |  |

## :integration-suite

clojure -M:dev:test integration — the gate before a commit: real files on disk, real
streams, real clocks, and graphviz actually shelled out to. Kaocha randomizes order, so a
test depending on another having run first is a bug in the test. Everything opened is
released in a `finally`, and every deref of a machine is BOUNDED or a hang becomes a hung
suite.

IF THE CHANGE TOUCHED THE NOTEBOOK OR THE VOCABULARY IT USES, RENDER IT — `clojure -X:notebook`
runs every cell, and a tutorial example that cannot run is a lie a test suite will never see

| on | goes to | guard |
|---|---|---|
| `:passes` | `:review` |  |
| `:fails` | `:implement` | Inspect the failure; fix the code or the test |

## :review

Verify the hard constraints: (1) nREPL was the only evaluator (2) no bare try/catch — a
`finally` for release is not one (3) malli shapes all data AND every function, and the
instrument count agrees with the independent ns-publics count (4) tests are clojure.test in
test/, one file per namespace, split unit and ^:integration (5) dependencies point down
only, in code, in docstrings and in `:see` (6) the pure core has no manifold in it, and no
namespace names one above it even in a comment (7) a facade re-export is a delegating defn
and never a def alias, or malli stops guarding it (8) what a static check composes is what
compile composes (9) every decision the change made or unmade is a `:knowledge` node on the
var it is about, and the knowledge suite is green

| on | goes to | guard |
|---|---|---|
| `:all-checkout` | `:retrospect` |  |
| `:issue-found` | `:implement` | Fix the identified issue |

## :retrospect

Reflect on the session:

- What went wrong? What assumption was incorrect?
- What was LEARNED about manifold or malli that a docstring would not have told
  you? Record it as a `:kind :lesson` node on the var it is about, in the past tense, with
  what was SEEN
- Close any `:open` node the work answered — a `:decision` that `:supersedes` it — and add
  the ones it raised, on the var they concern
- Add a `:kind :rule` node only for a mistake made more than once
- AND KEEP THIS FILE OPERATIONAL. A fact about the code goes on the code

| on | goes to | guard |
|---|---|---|
| `:done` | `:complete` |  |

## :complete

Done.
