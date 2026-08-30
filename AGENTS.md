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

{:statechart/id :robertluo.state-graph.workflow
 :initial :initialize
 :context
 {:project "robertluo.state-graph"
  :one-line "A finite state machine whose SHAPE is a graph — so a graph library can draw it,
             check it and store it, and a compiler can turn it into an ordinary Clojure function."
  :source-of-truth
  "README.md is the specification and this file is its reading. Where the two disagree the README
   wins and this file is wrong — say so and fix it. Nothing is implemented yet: src/robertluo/
   state_graph and test/robertluo/state_graph exist and are EMPTY, so everything below marked
   PROPOSED is a design not yet paid for, and everything marked GAP is a thing the repository
   promises and does not have."

  :constraints
  {:tests-clojure-test true
   :tests-generative-first true
   :test-tree "test/, one <ns>_test.clj per source namespace"
   :test-runner "kaocha, two suites: unit and integration, separated by a ^:integration meta"
   :test-cmd-fast "clojure -M:dev:test unit"
   :test-cmd-gate "clojure -M:dev:test integration"
   :eval-mechanism :nrepl-exclusive
   :malli-shapes-all-data true
   :malli-function-schemas true
   :no-bare-try-catch true
   :paren-repair-tool "clj-paren-repair"
   :repl-launch-cmd "clojure -M:dev:nrepl"
   :repl-discover-cmd "clj-nrepl-eval --discover-ports"
   :repl-eval-cmd "clj-nrepl-eval -p <port>"
   :repl-eval-reload :per-namespace-in-dependency-order
   :deps {:datahike "0.8.1861" :ubergraph "0.9.0" :malli "0.20.1" :test.check "1.1.1"
          :dev {:nrepl "1.3.0" :kaocha "1.91.1392"}}
   :inherited-from "../AGENTS.md — the sibling project robertluo.smart-boundary. Its house rules
                    (dependencies point down, errors are data, only assert what can fail, a store
                    is closed in a `finally`) hold here too; its :project-knowledge is about
                    Anthropic, Datalevin and nREPL and is NOT about this project."}

  :global-rules
  ["Do NOT manually repair parenthesis errors — run clj-paren-repair"
   "NEVER start a plain `clojure repl` / `clj`. nREPL is the ONLY way code is evaluated:
    discover with clj-nrepl-eval --discover-ports, launch with clojure -M:dev:nrepl, eval with
    clj-nrepl-eval -p <port>. The session persists across invocations"
   "NEVER (require ... :reload-all). malli is a dependency here, and :reload-all reloads malli.core
    itself, redefining its protocols: every compiled schema already in malli's function-schema
    registry then satisfies neither m/schema? nor m/Schema, and every instrument! afterwards is a
    StackOverflowError, for every var, however trivial. Use :reload per namespace, in dependency
    order (see :layering). Recovery is (reset! @#'malli.core/-function-schemas* {}) and a :reload
    of each namespace — a plain :reload alone does NOT recover, the stale objects being in malli's
    registry rather than ours"
   "A REPL whose classpath predates a new dependency CANNOT be repaired from inside — neither
    clojure.repl.deps/add-lib nor sync-deps has worked here. After touching deps.edn, restart it"
   "ALWAYS read the full error message before acting — do not skip or guess"
   "After every edit, read the file back to verify the edit landed correctly"
   "Inspect the source code for the bug BEFORE running REPL tests — do not test blindly"
   "DEPENDENCIES POINT DOWN ONLY — a lower namespace never refers to a higher one, in code, in a
    docstring, or in a comment. A namespace NESTED under another is BELOW it, so
    robertluo.state-graph.shape may not require robertluo.state-graph. See :layering"
   "Do NOT thread options through layers we do not own — inject a function that closes over them.
    The async layer is handed a compiled step FUNCTION; it never learns what a shape is"
   "MALLI GUARDS EVERY CROSSING, data and functions both. A schema that is only written down is a
    comment: instrument in dev, and conform BY HAND at a seam that must hold in production"
   "Errors are DATA — a plain map (m/explain, or our own) over malli.error/humanize prose. Prose
    reads well to a person and matches badly to a program"
   "TESTS LIVE IN test/, NOT IN THE SOURCE. One <ns>_test.clj per source namespace, ordinary
    clojure.test, run by kaocha. A test that opens a database or a socket is marked ^:integration
    and is NOT in the fast loop"
   "ONLY ASSERT WHAT CAN FAIL. Do not re-test what a library promises — that ubergraph adds an
    edge, that malli validates, that datahike stores a datom — and do not re-test our own code
    through a second door"
   "A GENERATIVE TEST NEEDS AN INDEPENDENT INVARIANT. A property that recomputes the expected
    answer the way the implementation computes it agrees with every bug it contains: it catches a
    wrong implementation and never a wrong understanding. For an FSM the honest invariants are
    structural — a reduction over events lands only in states the graph admits, a state entered
    validates against its own schema, replaying a prefix and then the rest equals replaying the
    whole — and those hold whatever the handlers do"
   "EVERY STORE A TEST OPENS IS RELEASED IN A `finally`. `finally` is release and is NOT the
    forbidden try/catch. In the sibling project one un-closed Datalevin environment kept the JVM
    alive after the suite had finished, because its executor threads are not daemons; whether
    datahike does the same is UNVERIFIED — assume it does until it has been measured"
   "COMMIT GATE: clojure -M:dev:test integration passes. The fast suite is for every save"]

  :layering
  ["The bottom two are BUILT as described (2026-08-30). Everything above them is still PROPOSED,
    and the point of writing it down first is that the arrows are cheap to change today and
    expensive next month.

    robertluo.state-graph          — THE FACADE: the vocabulary a user needs, and the only require
                                     an application should have. Constructors for a shape, `compile`,
                                     and re-exports of the two defaults so that one require is enough
    robertluo.state-graph.async    — A DEFAULT, not the core: manifold streams. Takes a compiled step
                                     FUNCTION and a stream of events, answers a stream of states.
                                     Knows nothing of shapes, schemas or graphs
    robertluo.state-graph.store    — A DEFAULT, not the core: datahike. Takes states and events as
                                     DATA and answers history — audit and trace. Knows nothing of
                                     shapes; the schema is the caller's
    robertluo.state-graph.compile  — shape -> (fn [state event] state'). The only namespace that
                                     turns data into a function, and the only one both defaults are
                                     above in spirit and below in the arrow: they take its OUTPUT
                                     as a value, so neither requires it
    robertluo.state-graph.shape    — THE BOTTOM: the graph itself. Pure data plus constructors,
                                     ubergraph underneath, the malli schemas of a shape, and the
                                     static checks. Requires ubergraph and malli only

    The two defaults sit BELOW the facade rather than beside it because of the nesting rule — a
    child may not require its parent — and it costs nothing, since neither needs the facade's
    vocabulary: what they need is a function and some data, handed over as values."]

  :layering-rule
  "A namespace NESTED under another is BELOW it: robertluo.state-graph.shape requiring
   robertluo.state-graph would be a child reaching for its parent. If something genuinely must sit
   ABOVE the facade it is a SIBLING of it and named accordingly — robertluo.state-graph-x, never
   robertluo.state-graph.x. The name has to agree with the direction of the arrow."

  :design
  {:the-shape-is-a-graph
   "Three definitions and no more, straight from the README:
    - A STATE is a node, shaped by a malli schema, VALIDATED ON ENTER. Entering is the only moment
      a state's schema can be checked, and it is the moment a bad transition becomes visible.
    - An EVENT is shaped by a malli schema too. An event is a value, not a keyword with baggage.
    - A TRANSITION is an edge, keyed BY EVENT ONLY — not by (state, event) — carrying a function
      that handles the event and whose RETURN VALUE IS APPLIED TO the state. `Applied to`, not
      `is`: what a handler answers is a change, and the state is what the change lands on.
    Consequence, and it decides the ubergraph call: two different events may join the same pair of
    states, so the shape is a MULTI-digraph and an edge needs an attribute map. A plain digraph
    would silently keep one of the two.
    DECIDED 2026-08-30: :initial IS A NODE ATTRIBUTE, exactly one per shape. The reachability check
    needs a root and so does the drawing, so the shape has to know it — while the starting DATA
    stays an argument to the reduction. A node and its value are different things and only the
    first belongs in the graph."

   :what-the-graph-buys
   "The whole argument for not writing another FSM library. A shape that is a graph can be:
    - DRAWN, so a person can see the machine they described rather than read it;
    - CHECKED STATICALLY, which is the part that pays: a state with no in-edge is unreachable, a
      state with no out-edge and no :final is a dead end, an event no transition mentions is dead
      code, and a transition whose handler cannot produce a value the target's schema admits is a
      bug findable WITHOUT RUNNING ANYTHING. That last one is what having malli on the nodes is
      for, and it is the check worth building first because it is the one no other FSM library has;
    - STORED, so the machine's own history is queryable in the same shape as its definition."

   :compilation-and-lifecycle
   "(compile shape) -> a pure function of a state and an event answering the next state. The
    lifecycle of an instance is then (reduce step initial events), and that is the whole runtime:
    no object, no atom, no protocol. Everything else in this library is a way of getting events
    into that reduction or getting states out of it — which is exactly why a transducer is the
    async layer and a log is the persistence layer, and why neither of them is the core."

   :the-defaults-are-batteries
   "Async and persistence are DEFAULTS. Someone with their own stream library or their own database
    must be able to use the compiled function directly and lose nothing. So neither may be
    required by the facade's core path, and neither may take a shape as an argument."

   :the-first-target
   "DECIDED 2026-08-30. The first target is THE SPINE: robertluo.state-graph.shape and
    robertluo.state-graph.compile, ending in a working (reduce step initial events). No async, no
    datahike, no drawing, and deliberately NOT the static checks.
    - Not the checks first, though they are the differentiator, and this is the whole argument: a
      check written over a shape that nothing has ever run is a check over a shape that is probably
      wrong. `compile` is the cheapest thing that can say whether the shape is expressive enough,
      and it is small. The checks are target 2 and cost almost nothing once the shape stands."

   :a-shape-is-code
   "DECIDED 2026-08-30, by the author, and it decides more than it looks like it does: A STATE
    MACHINE SHAPE IS CODE. The README's `pure clojure data with convinient functions as
    constructors` means you WRITE it as data, not that it ROUND-TRIPS as data. It is built at
    namespace load, its handlers are real closures, its schemas are compiled once.
    - What that kills: every argument from EDN, from =, from storability. Those were the only
      objections to the shape BEING an Ubergraph, so the shape is an Ubergraph — nodes carrying
      their schema as attributes, the multi-digraph carrying two events between one pair of states,
      and the checks and the drawing reading it directly with no parallel map to keep in sync.
    - And it settles persistence without a separate argument: a shape that is code is not something
      datahike reloads a machine FROM, so what is stored is HISTORY. Shape versioning is out of v1.
      The README says as much in its own words — `a machines states, events, transitions become
      history`."

   :a-state-has-an-id
   "DECIDED 2026-08-30. A state is a MAP with an identity field, :id — one word, and the same word
    in both places it is needed: the NODE in the graph is the :id, and the runtime value carries
    :id saying which node it is in. FORCED as well as chosen: the compiled step is
    (fn [state event] state') and has to know whose out-edges to search, so the identity cannot
    live only in the graph. An event is a map with :id for the same reason, and the compiler
    matches an edge on it.
    - A node's own schema describes the REST of the map. What is validated on enter is the DERIVED
      (mu/merge [:map [:id [:= <node>]]] <node schema>), never written by hand — so `validate on
      enter` also checks that the machine landed where it thought it did.
    - THE EDGE ALWAYS WINS. A handler answers a map that is MERGED, so a handler could write :id
      and move the machine sideways past the edge meant to decide the target. The compiler assocs
      the target's :id AFTER the merge, so a handler's :id is simply overwritten. Identity is the
      shape's to say, not a handler's."

   :a-handler-never-sees-the-state
   "DECIDED 2026-08-30, by the author, and it is the sharpest decision here: a handler takes THE
    EVENT ALONE — (handler event) — and never the state it is about to change. The event is a map
    and may carry whatever data the change needs; what it may not do is reach into the state.
    - THE REASON IS DECOUPLING: one handler serves many events and many source states, and states
      and events then evolve independently. Reuse is the visible payoff.
    - THE BIGGER PAYOFF IS THE CHECK. A handler with no state in it is a COMPLETE malli function on
      its own — [:=> [:cat <the edge's event schema>] <the edge's :out>] — both halves coming off
      the edge and nothing from the graph. So a handler is instrumentable as an ordinary function,
      and :out stops being an annotation nobody can verify and becomes a claim that can be TESTED
      generatively: generate events from the event schema, run the handler, check the answer, with
      no state and no machine anywhere near it. (handler state event) would have needed the source
      node's schema in that signature, welding the handler to one node.
    - THE COST, ACCEPTED AND SAID OUT LOUD: NO STATE-DEPENDENT UPDATE. :total after :add-item
      cannot see the old total; nor can a counter. Two places that goes. The history layer has
      every event, so a derived total is a QUERY rather than a state field. And when it must live
      in the state, the move that keeps this decoupling is to let the NODE'S SCHEMA declare how a
      key COMBINES (:n by +), so the handler still answers {:n 1} and the accumulation is DATA ON
      THE NODE rather than a closure. Not built; it is the door and it is open."

   :a-handler-answers-a-map-and-declares-it
   "DECIDED 2026-08-30. A handler's return value is a MAP, MERGED into the state — and the edge
    also DECLARES the malli schema of that map. Both halves are chosen for one reason, the static
    check, and for no other.
    - An opaque (fn [state] state') can never be checked, whatever the graph holds it in, and a
      diff composes with a schema no better. A merge of two MAP SCHEMAS does:
      merge(<from schema>, <declared out>) ⊆ <to schema> is decidable WITHOUT RUNNING ANYTHING,
      which is the check :what-the-graph-buys promises and the only version of it that is real.
    - MALLI HAS NO SUBSUMPTION. m/validate answers about a VALUE; there is no `is schema A admitted
      by schema B`. We write a structural one over :map entries — required keys present, each child
      compatible — and it is deliberately PARTIAL: yes, no, or DON'T KNOW, and don't-know is not a
      failure. A partial checker that never lies is worth more than a total one that guesses.
    - The declaration is OPTIONAL per edge. Absent, the check degrades to a GENERATIVE one —
      generate a state from the source schema and an event from the event schema, run the handler,
      validate the answer against the target — which is this project's testing style anyway.
    - COST, accepted: a merge cannot REMOVE a key. A state that must drop a field is a state the
      v1 shape cannot express."

   :the-event-catalogue-is-denormalised
   "DECIDED 2026-08-30, and FORCED by ubergraph rather than chosen — see :ubergraph-0-9-0. An
    Ubergraph holds nodes and edges and NOTHING ELSE, so the event catalogue (event id -> malli
    schema) has nowhere on the graph to live. It is therefore an ARGUMENT to the constructor, which
    writes each event's schema onto EVERY EDGE that carries it and refuses a shape whose edges
    disagree about one event. The graph remains the whole shape.
    - What is lost, and where it goes instead: `an event no transition mentions is dead code`
      stops being a graph query, because a catalogue no longer exists to be dead relative to. It
      becomes a CONSTRUCTION-TIME check, which is the only moment the catalogue is in hand — and
      the disagreement check is a better one than the check it replaces.
    - WHAT WAS KILLED FIRST, so nobody proposes it again: making the graph bipartite,
      state --> event --> state, is not merely awkward, it is WRONG. Two transitions on :submit
      leaving different states would share one event node and FABRICATE paths the shape never
      said — A -> submit -> D, when all that was declared was A -> submit -> B and C -> submit -> D."

   :v1-is-deterministic
   "DECIDED 2026-08-30. NO GUARDS. A state and an event have exactly one target, which is what
    makes `compile` a LOOKUP rather than a search and what makes every static check answerable.
    - The consequence, said out loud: :submit -> :accepted | :rejected is INEXPRESSIBLE. Branching
      must be spelled as two different events, which pushes the decision onto whoever PRODUCES the
      event, and that is sometimes the wrong place. This is the first thing to revisit.
    - It costs nothing to defer: an edge already carries an attribute map, so a guard is a key in
      it and not a change of shape. What it will cost when it comes is the compiler (an ORDERED
      search over a node's out-edges) and the checker (a considerably harder question)."}

  :open-questions
  ["PARALLEL TRANSITIONS AND A REDUCTION ARE IN TENSION, and this is the first thing to settle
    because it decides the async layer's whole shape. A reduce over one state is sequential by
    definition — event n+1 sees what event n did. `Automatically parallel` can therefore only mean
    ACROSS INSTANCES: events partitioned by machine id, one reduction each, run at once. If it is
    meant to mean anything else — independent regions inside one machine, orthogonal statechart
    states — that is a different and much larger feature, and the shape needs to say which parts of
    a state a transition touches before any of it is safe. Decide, and write the answer here."
   "WHAT IS PERSISTED: the SHAPE, the HISTORY, or both? They are different needs. History is an
    append-only log of events and the states they produced, which datahike is exactly right for.
    A shape in the database is a different claim — it means the machine can be changed without a
    recompile, and it drags versioning in with it (an instance mid-flight belongs to the shape it
    started under). The README's rationale mentions storing the machine; the features list only
    mentions state persistence. Pick one for v1, and prefer history."
   "WHAT IDENTIFIES AN INSTANCE, as opposed to a state? :id is the STATE's — which node the machine
    is in. `Automatically parallel` can only mean across instances (see the first question), and a
    partition by machine needs a second identity that :id is not. It may be a key in the state, it
    may be outside the state entirely and belong to the async layer alone. Not needed by the first
    target; needed by the moment there are two machines."]

  :project-knowledge
  {:status
   "TARGET 1 IS BUILT, 2026-08-30: robertluo.state-graph.shape and .compile, with tests.edn and a
    test file each. 15 tests, 59 assertions, green; clj-kondo clean; every one of the 14 public
    fns carries a :malli/schema, which the instrument! count of exactly 14 confirms better than a
    grep can. The lifecycle runs — (reduce (compile shape) (initial shape data) events).
    NOT BUILT, and named so nobody assumes otherwise: the FACADE (robertluo.state-graph) does not
    exist, so an application requires the two namespaces directly; the structural checks
    (reachability, dead ends, schema subsumption) are target 2; async and store are untouched."

   :gaps-in-the-repository
   "Found by reading deps.edn against README.md, and every one of them will bite on first use:
    - MANIFOLD IS NOT A DEPENDENCY. The README's async feature names manifold streams and deps.edn
      has no manifold in it. Add it before writing that layer, and restart the REPL afterwards —
      a running classpath cannot be repaired from inside.
    - tests.edn: CLOSED 2026-08-30. Two suites over one tree, separated by
      :kaocha.filter/skip-meta [:integration] and :kaocha.filter/focus-meta [:integration],
      copied from the sibling project. VERIFIED with
      `clojure -M:dev:test unit --print-test-plan`: both testables build and `unit` marks
      integration :kaocha.testable/skip true. The default :kaocha/ns-patterns is [\"-test$\"],
      which IS the <ns>_test.clj convention, so it is not configured. An empty tree warns
      `No tests were found` and exits 0.
    - `clojure -M:dev` DOES NOT START A REPL. The :dev alias is :extra-paths and :extra-deps with
      no :main-opts; the alias with the main-opts is :nrepl. The README's development note says
      -M:dev and is wrong: it is `clojure -M:dev:nrepl` (and :dev is wanted, or the test path and
      kaocha are not on the classpath).
    - kaocha is in :dev and not in :test, so the runner is `clojure -M:dev:test`, never
      `clojure -M:test`."

   :ubergraph-0-9-0
   "READ FROM THE SOURCE of ubergraph 0.9.0 on 2026-08-30 (not yet run — anything below that gets
    exercised should be re-recorded with what was SEEN).
    - AN UBERGRAPH IS A MAP TYPE — (def-map-type Ubergraph [node-map allow-parallel? undirected?
      attrs cached-hash]) — but a CLOSED one, and it fails SILENTLY. (assoc g :anything v) hits a
      `case` with no default and returns `this` UNCHANGED; (dissoc g k) returns `this`; and
      (with-meta g m) returns `this` while (meta g) is hardcoded nil. So metadata is DISCARDED
      without a word, and there is no slot on a graph for anything that is not a node or an edge.
      That is what decided :the-event-catalogue-is-denormalised.
    - IT IS = AND IT IS EDN, contrary to what this file assumed before reading it: `equiv` is
      (and (instance? Ubergraph other) (equal-graphs? this other)), `hasheq` is hash-graph, and
      ubergraph->edn / edn->ubergraph both exist. The EDN round trip obviously cannot carry a
      handler fn or a compiled malli schema, which is a fact about OUR attributes and not about
      ubergraph.
    - Attributes are the la/AttrGraph and up/Attrs protocols — attr, attrs, add-attr, add-attrs,
      set-attrs, remove-attr(s) — over a node OR an edge, and add-attrs MERGES while set-attrs
      REPLACES. Weight is not special: it is the :weight attribute with a default of 1.
    - multidigraph is the constructor this project wants (allow-parallel? true, undirected? false):
      two events joining one pair of states are two edges, and a plain digraph would keep one.
    - node-with-attrs and edge-with-attrs answer values that build-graph accepts back, which is the
      supported way to copy or rebuild a graph."

   :what-target-1-taught
   "VERIFIED BY RUNNING on 2026-08-30, all of it in this project's own REPL:
    - THE UBERGRAPH TRAPS ARE REAL, not merely read: (= g (assoc g :junk 1)) is TRUE and
      (meta (with-meta g {:a 1})) is NIL. :ubergraph-0-9-0 was written from the source; this is
      the same facts seen happening.
    - MALLI NAMES THE WRONG SCHEMA FOR A MISSING KEY, and it cost a test. (:schema error) is the
      WHOLE ENCLOSING MAP when a key is absent, and the offending child only when a present value
      is wrong. (mu/get-in root (:path error)) is right in BOTH — verified over a missing key, a
      wrong-typed key and a nested one. The error also carries :type :malli.core/missing-key,
      which is the only thing telling a missing key from a key whose value is legitimately nil.
      That is what robertluo.state-graph.shape/explain does, and why it keeps the ROOT schema.
    - gen/let IN test.check 1.1.1 DOES NOT SUPPORT :let BINDINGS. The symbol simply does not
      resolve, and the failure arrives as `Unable to resolve symbol` from inside the generator
      rather than as anything about gen/let. Use gen/bind and gen/fmap explicitly.
    - KAOCHA IGNORES A FOCUS-META NOBODY CARRIES. With no ^:integration test in the tree,
      `-M:dev:test integration` prints `Ignoring --focus-meta :integration` and RUNS THE UNIT
      TESTS, and `-M:dev:test` runs all of them TWICE (28 tests, 76 assertions, being 14 twice).
      So the commit gate is not yet a gate — it becomes one the moment there is one ^:integration
      test. Target 1 has nothing honest to put there: it opens no database and no socket, and
      drawing a graph needs graphviz and belongs to target 2.
    - THE INSTRUMENTATION FIXTURE IS WORTH PROVING. (mi/instrument!) RETURNS the vars it
      instrumented, so a count is an assertion that the fixture is not a silent no-op — 14 here,
      which is also exactly the number of public fns, so it doubles as the check that none was
      forgotten. mi/clj-collect! takes {:ns [...]} as a VALUE, where mi/collect! is a macro that
      would collect the test namespace.
    - malli normalises [:map] to the FORM :map. Both have (m/type ...) = :map, so a bare :map is
      a legitimate `any map` state schema and MapSchema admits it.
    - MALLI HAS NO `IS THIS A SCHEMA` PREDICATE for the thing people actually write. m/schema? is
      true ONLY of a compiled schema and false for the form [:map [:n :int]]; nothing public
      answers `could malli make a schema of this` (the public surface has schema?, into-schema?,
      -function-schema?, -ref-schema?, -entry-schema? and no more). So `Schema` and `MapSchema`
      are written here as :fn predicates that simply CALL m/schema — which works because MALLI
      RUNS A :fn PREDICATE THROUGH ITS OWN -safe-pred: (m/validate [:fn odd?] \"not-a-number\")
      answers false rather than throwing. The throw m/schema makes on nonsense therefore comes
      back as `false`, and the try/catch belongs to malli rather than to us, which is what lets
      this honour :no-bare-try-catch. Verified false for a string, nil, a number and an unknown
      schema type; true for a form, a compiled schema and a bare keyword one. (m/schema x) on an
      already-compiled x is identical? to x, so the predicate costs nothing on the common path.
    - AN ARGUMENT THAT MUST ACCEPT RUBBISH KEEPS :any, and that is a decision rather than a gap.
      `problems` and `shape` take [:* :any] because they must ACCEPT a malformed part in order to
      REPORT it — a tighter schema would refuse it with ::m/invalid-input instead of the list of
      what is wrong, and would do so only under instrumentation, so the diagnosis would be both
      worse and different between dev and production."

   :dependency-notes
   "What each dependency is here FOR, so that nobody reaches for the wrong one:
    - ubergraph 0.9.0 — the shape. Multigraph and digraph in one library, attributes on nodes and
      edges, and viz-graph for drawing. Drawing needs graphviz (`dot`) INSTALLED, so any test that
      renders is ^:integration at best and probably not a test at all.
    - datahike 0.8.1861 — history. It is a datalog database, not a graph library; a graph goes in
      it as datoms perfectly well, and its being immutable and time-travelling is the actual reason
      it fits an audit trail. Its connection is a resource: opened in a fixture, released in a
      `finally`.
    - malli 0.20.1 — the shapes of states, events and every function signature. See the
      :reload-all rule; it is the one dependency that punishes a careless REPL.
    - test.check 1.1.1 — it is in :deps and not :dev on purpose: generative tests are the unit
      suite here, not an extra."

   :from-the-sibling-project
   "../AGENTS.md is the same author's larger project and its :project-knowledge is worth reading
    before repeating an experiment. What transfers is method, not fact: schemas at every crossing,
    seams checked in the code and not merely declared, a store that must be closed, `only assert
    what can fail`, and a knowledge section written in the past tense about things actually
    observed. What does NOT transfer is any of its content — it is about Anthropic's API,
    Datalevin, nREPL-as-a-map and LLM agents, none of which this library has."}}

 :states
 {:initialize
  {:entry {:action "Read README.md — it is the specification. Then this file's :open-questions:
    if the change touches one, settle it with the human FIRST and write the answer down. Check
    :gaps-in-the-repository before running anything that depends on a gap"}
   :on {:proceed          {:target :shape-design}
        :question-is-open {:target :decide}}}

  :decide
  {:entry {:action "A design question the README does not answer. Put it to the human in one
    sentence with a recommendation, not a survey. Record the answer in :design and delete it from
    :open-questions — an answered question left in the list is a question that gets asked again"}
   :on {:answered {:target :shape-design}}}

  :shape-design
  {:entry {:action "Design the malli schemas first — of a state, of an event, of a transition, of
    a shape, and of every function signature that touches them. Validate each with malli.core/
    explain in the REPL before anything is built on it. A schema is the cheapest place to be wrong"}
   :on {:schemas-validated       {:target :implement}
        :needs-interactive-check {:target :repl-eval}}}

  :implement
  {:entry {:action "Write it in src/, lowest layer first (shape, then compile, then a default,
    then the facade). One <ns>_test.clj per source namespace as you go, ^:integration on anything
    that opens a database or a socket. Dependencies point down only"}
   :on {:function-complete {:target :unit-test}
        :debugging-needed  {:target :repl-eval}
        :schema-question   {:target :shape-design}}}

  :unit-test
  {:entry {:action "clojure -M:dev:test unit — the fast loop, nothing outside this process. Reach
    for a PROPERTY before an example, and make the property an independent invariant rather than
    the implementation restated. Assert only what can fail"}
   :on {:all-pass {:target :refactor}
        :any-fail {:target :implement
                   :guard "Inspect the failure and the shrunk case; fix the code or the property"}}}

  :repl-eval
  {:entry {:action "Discover nREPL via clj-nrepl-eval --discover-ports; if none, launch
    clojure -M:dev:nrepl; eval with clj-nrepl-eval -p <port>, :reload per namespace in dependency
    order — never :reload-all"}
   :on {:insight-gained        {:target :implement}
        :schema-clarity-needed {:target :shape-design}}}

  :refactor
  {:entry {:action "After tests pass, refactor for clarity:
    - keep the pure core pure — the compiler and the shape know nothing of streams or databases
    - a default takes what it needs as a VALUE; nothing is threaded through a layer we do not own
    - extract the well-named function that the duplication was asking for
    - verify behavior(new) = behavior(old): the properties are what says so"}
   :on {:refactor-complete {:target :static-check}
        :needs-test        {:target :unit-test}}}

  :static-check
  {:entry {:action "Only when the change touches the shape or the checks over it. Build a shape in
    the REPL, run the static checks, and LOOK AT THE DRAWING — an unreachable state is obvious in a
    picture and invisible in a map literal. Needs graphviz; it is a human check, not a test"}
   :on {:looks-right {:target :integration-suite}
        :wrong       {:target :implement}}}

  :integration-suite
  {:entry {:action "clojure -M:dev:test integration — the gate before a commit: a real datahike
    store on disk, real streams, real clocks. Kaocha randomizes order, so a test depending on
    another having run first is a bug in the test. Every store opened is released in a `finally`,
    and a suite that passes and then hangs is a store left open"}
   :on {:passes {:target :review}
        :fails  {:target :implement
                 :guard "Inspect the failure; fix the code or the test"}}}

  :review
  {:entry {:action "Verify the hard constraints: (1) nREPL was the only evaluator (2) no bare
    try/catch — a `finally` for release is not one (3) malli shapes all data AND every function
    (4) tests are clojure.test in test/, one file per namespace, split unit and ^:integration
    (5) dependencies point down only (6) the pure core has no manifold and no datahike in it"}
   :on {:all-checkout {:target :retrospect}
        :issue-found  {:target :implement
                       :guard "Fix the identified issue"}}}

  :retrospect
  {:entry {:action "Reflect on the session:
    - What went wrong? What assumption was incorrect?
    - What was LEARNED about ubergraph, datahike, manifold or malli that a docstring would not
      have told you? Record it in :project-knowledge in the past tense, with what was seen
    - Close any :open-questions the work answered; add the ones it raised
    - Add a :global-rule only for a mistake made more than once"}
   :on {:done {:target :complete}}}

  :complete
  {:final true}}}
