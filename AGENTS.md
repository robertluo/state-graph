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
   :inherited-from "../smart-boundary/AGENTS.md — the SIBLING COMPONENT robertluo.smart-boundary, in the same
                    monorepo. Its house rules
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
   "COMMIT GATE: clojure -M:dev:test integration passes. The fast suite is for every save"
   "THIS FILE IS DATA, SO CHECK IT BY PARSING IT. An unterminated string is INVISIBLE to a bracket
    balance — it shifts which quotes pair with which and leaves every { and } matched — so a
    balance check passes a file that no reader can read. Two entries were added with no closing
    quote on 2026-08-31 and the balance check said fine each time; what caught it was
    clojure.edn/read-string, which answered `Invalid number: 2026-08-31.` because it was reading
    prose as data. Verify with (clojure.edn/read-string (subs s (index-of s \"{:statechart/id\")))
    and nothing weaker. (../smart-boundary/AGENTS.md does NOT parse — `Duplicate key: a`, and it
    predates any of this; it is that component's to fix.)"]

  :layering
  ["The bottom two are BUILT as described (2026-08-30). Everything above them is still PROPOSED,
    and the point of writing it down first is that the arrows are cheap to change today and
    expensive next month.

    robertluo.state-graph          — THE FACADE: the vocabulary a user needs, and the only require
                                     an application should have. Constructors for a shape, `compile`,
                                     and re-exports of the two defaults so that one require is enough
    robertluo.state-graph.async    — BUILT 2026-08-31. A DEFAULT, not the core: manifold streams.
                                     Takes a compiled step FUNCTION and a way to make a first state,
                                     both as VALUES, and knows nothing of shapes, schemas or graphs.
                                     `drive` is one machine, serialised; `fan` partitions on
                                     :instance and runs one per machine, concurrently. Both answer
                                     {:states :done}, two different things under two names
    robertluo.state-graph.store    — A DEFAULT, not the core: datahike. Takes states and events as
                                     DATA and answers history — audit and trace. Knows nothing of
                                     shapes; the schema is the caller's
    robertluo.state-graph.compile  — shape -> (fn [state event] state'). The only namespace that
                                     turns data into a function, and the only one both defaults are
                                     above in spirit and below in the arrow: they take its OUTPUT
                                     as a value, so neither requires it
    robertluo.state-graph.check    — BUILT. What the graph BUYS: the static checks and the
                                     drawing, which answer the same question by different means.
                                     A SIBLING of compile, not a part of shape: nothing here is on
                                     the runtime path, and an application shipping a working shape
                                     never loads it. Requires shape, ubergraph and malli
    robertluo.state-graph.shape    — THE BOTTOM: the graph itself. Pure data plus constructors,
                                     ubergraph underneath, the malli schemas of a shape, and the
                                     REFERENTIAL checks — the ones answerable from the parts alone.
                                     Requires ubergraph and malli only

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

   :two-kinds-of-check-and-two-places-for-them
   "DECIDED 2026-08-31, when target 2 was built. shape/problems is REFERENTIAL — answerable from
    the PARTS alone, so it runs inside the constructor and a bad shape never exists. check/problems
    is STRUCTURAL — it needs the built graph, so it is a separate namespace and opt-in.
    - WHY A NAMESPACE AND NOT MORE OF shape, which :layering originally said: nothing in `check` is
      on the runtime path. compile does not require it, and an application shipping a working shape
      never loads a graph algorithm. The checks are for the person WRITING the machine.
    - THE DRAWING IS IN THERE TOO, and it belongs: an unreachable state is obvious in a picture and
      invisible in a map literal. Same question, different means.
    - REACHABILITY IS A TRAVERSAL FROM THE ROOT, not `has no in-edge`, and the difference is not
      academic: two states that reach only each other both have in-edges and are both unreachable.
      The suite has exactly that island in it, because the weaker check passes it."

   :a-partial-subsumption-checker
   "DECIDED 2026-08-31. `admits` answers :yes, :no or :unknown, and IT NEVER LIES. Malli has no
    subsumption — m/validate answers about a VALUE, and nothing asks whether schema A is admitted
    by schema B — so it is written here, structurally over :map entries.
    - WHAT IT CAN PROVE, and each is decidable rather than heuristic: a REQUIRED key the produced
      value may not have (the common bug by a distance, a handler that forgot to set something, and
      it covers `optional where the target insists` too); a value whose TYPE cannot be the wanted
      one, over seven primitives verified PAIRWISE disjoint rather than assumed — :int and :double
      included, malli rejecting each for the other; and a [:= v] or an [:enum ...], where the values
      are finite and can simply be tried.
    - :unknown IS AN ANSWER AND NOT A FAILURE, and problems reports only the PROVEN faults. A
      checker that cries about what it could not work out is a checker people turn off. `subsumption`
      publishes every verdict, :unknown and :undeclared included, so the check's own COVERAGE is
      readable — which is a better thing to have than a checker that pretends to be total.
    - WHAT IS CHECKED IS WHAT RUNS: `produced` composes the schema in the order compile composes the
      value — the source's own schema, the declared :out merged over it, the target's :id assoc'd
      last. If those two ever disagree the check is worthless, so they are written to be read side
      by side.
    - AN EDGE WITH NO :out IS :undeclared AND NOT A FAULT. That declaration is what the whole check
      is FOR; without it there is nothing to say about a closure.
    - SOUNDNESS IS TESTED BY GENERATION, which is a genuinely independent second opinion: where
      `admits` says :yes, values generated from the produced schema must all validate against the
      target. That direction is the one worth paying for — a checker saying :no where it should say
      :unknown merely nags, one saying :yes where it should say :no HIDES A BUG.
    - THE TRAP CHECK IT NAMED AS NOT BUILT IS NOW BUILT, 2026-08-31 — see
      :a-trap-is-what-a-cycle-hides. It cost five lines, as predicted, and the exception that made
      it wait turned out to belong inside `finishable` rather than bolted onto the check."

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
    - REVISED 2026-08-31: the declaration is PER EVENT and no longer per edge, so `optional per edge`
      now reads `optional per event`. See :a-handler-belongs-to-the-event.
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
      said — A -> submit -> D, when all that was declared was A -> submit -> B and C -> submit -> D.
    - EXTENDED 2026-08-31: the catalogue now carries the HANDLER and its :out as well as the event's
      schema, for the same reason and by the same mechanism. See :a-handler-belongs-to-the-event."

   :v1-is-deterministic
   "DECIDED 2026-08-30. NO GUARDS. A state and an event have exactly one target, which is what
    makes `compile` a LOOKUP rather than a search and what makes every static check answerable.
    - The consequence, said out loud: :submit -> :accepted | :rejected is INEXPRESSIBLE. Branching
      must be spelled as two different events, which pushes the decision onto whoever PRODUCES the
      event, and that is sometimes the wrong place. This is the first thing to revisit.
    - It costs nothing to defer: an edge already carries an attribute map, so a guard is a key in
      it and not a change of shape. What it will cost when it comes is the compiler (an ORDERED
      search over a node's out-edges) and the checker (a considerably harder question)."

   :parallel-is-across-instances
   "DECIDED 2026-08-31. `Automatically parallel` means ACROSS INSTANCES and nothing else: events
    partitioned by instance, one sequential reduction each, run at once. Orthogonal regions inside
    ONE machine are OUT — that is a statechart and not this, and the shape would have to declare
    which parts of a state a transition touches before any of it were safe.
    - WHY ONE MACHINE CANNOT PARALLELISE, and it is a DATA DEPENDENCY rather than anything about
      manifold: compile selects the handler with (idx [(:id state) (:id event)]), so the handler for
      event n+1 is unknowable until event n has produced its state. No scheduler breaks that chain.
      A stream library buys backpressure and non-blocking composition here, and not parallelism.
    - THE NEAR MISS THAT MAKES THE OPPOSITE SOUND TRUE: handler EXECUTION needs only the event, by
      :a-handler-never-sees-the-state. It is handler SELECTION that needs the state. So the
      expensive part is parallelisable in principle and unreachable in practice, because you cannot
      call what you have not yet chosen.
    - REVISED 2026-08-31, SAME DAY, and the conclusion survived a change to its reason. The handler
      is now the EVENT's and is known without the state — see :a-handler-belongs-to-the-event — so
      handler SELECTION is no longer what forces the sequence. ADMISSION is: a handler runs only
      where an edge admits it, and whether this state admits this event cannot be known until the
      previous step has landed. A parallel map over one machine's stream would have to run handlers
      SPECULATIVELY, and since deferreds exist so that handlers can do I/O, speculation means real
      effects for events the machine ignores. That was refused; see
      :an-ignored-event-is-not-an-error-but-is-not-silent. The price is the same price, charged at a
      different counter.
    - AND A THIRD REASON, the author's, which holds where both of the others fail. A handler that
      CAUSES another event makes parallel handlers interleave WRONGLY — not wastefully, wrongly — so
      even with pure handlers and free speculation the order would be wrong. That is why statecharts
      have run-to-completion. v1 forbids emission (:a-handler-causes-nothing), so the hazard is shut
      rather than survived; the argument is recorded because it is the one that would still stand if
      the other two were answered.
    - CORRECTED 2026-08-31, and the correction is the author's: EVERY ARGUMENT ABOVE IS ABOUT EVENTS
      ARRIVING ONE AT A TIME. None of them touches two events PENDING IN THE SAME STATE, which is
      what an async handler creates and which :one-ordered-stream-per-instance does nothing to
      prevent — that contract promises arrival ORDER, not one-at-a-time PROCESSING. Both handlers are
      then selected from the same state, so there is no speculation and no unknown state, and the
      selection argument simply does not apply. `Across instances` is still where the PARALLELISM is;
      a narrow CONCURRENCY inside one machine is licensed by the shape, and the whole of it is in
      :two-events-in-flight-at-once."

   :what-is-persisted
   "DECIDED 2026-08-31: HISTORY, and not the shape. An append-only log of events and the states they
    produced — audit and trace, which is what the features list names.
    - IT WAS NEVER REALLY OPEN, and both authorities already said so. The README says it in its own
      words — `a machines states, events, transitions become history` — and :a-shape-is-code forces
      it: a shape built at load time, whose handlers are closures and whose schemas are compiled, is
      not something a database reloads a machine FROM. What a store holds is what HAPPENED.
    - So SHAPE VERSIONING IS OUT OF v1 and stays out, with the question it drags behind it: which
      shape an instance mid-flight belongs to. Nothing in the store needs a shape identity."

   :an-instance-has-an-identity
   "DECIDED 2026-08-31. An instance is identified by a FIXED FIELD, :instance, and it is written by
    the CONSTRUCTORS and not by hand — a caller says which machine they mean and never spells the key.
    - THE NAME IS THE README'S OWN WORD. `A lifecycle of an instance of the FSM can be seen as a
      reduction on a seq of events` — the specification already calls this thing an instance, so any
      synonym would be this file overriding the README on a coin flip, which :source-of-truth forbids.
      It is a PLAIN keyword and not a namespaced one, because it is data a user reads and writes in
      their own maps, exactly as :id is.
    - IT IS ON THE EVENT AS WELL AS THE STATE, and the EVENT is the half that is load-bearing.
      Routing an incoming event to the right reduction is a decision made BEFORE any state is in
      hand, so the partition key cannot be read off a state. A state carries it so that a stored row
      says what it belongs to; an event carries it so that there is something to partition on.
    - IT IS A THIRD IDENTITY AND IT GETS A THIRD NAME. :id on a state is which NODE it is in and :id
      on an event is its TYPE; see :a-state-has-an-id. A word doing two jobs here would be the bug
      nobody sees.
    - nil NAMES NOTHING, refined 2026-08-31 when async/fan forced it. fan keys an event carrying no
      :instance under nil, so (fn [k] (initial sh k {})) is the call site whether a caller names
      machines or not, and initial's instance argument is [:maybe Instance] rather than Instance. It
      asserts nothing that way — Instance is `some?` — AND THAT COSTS NOTHING REAL: the invariant
      worth having is that no STATE ever carries a nil :instance, and that lives on the enter schema
      where it is checked on every entry rather than once at the door.
    - WHAT WAS TURNED DOWN: a key-fn handed to the async layer, leaving the core ignorant that
      instances exist at all. It is the more decoupled design and it is not the one chosen — a fixed
      field the constructors own is simpler to document, and it makes a state self-describing to the
      store with no second argument travelling beside it."

   :a-handler-may-answer-later
   "DECIDED 2026-08-31. A handler MAY answer a DEFERRED rather than a plain map, so an instance
    waiting on I/O does not hold a thread. That is what a stream library is actually for, and with
    parallelism living across instances it is what stops one slow handler starving the pool.
    - HOW, WITHOUT PUTTING MANIFOLD UNDER THE CORE. compile never learns what a deferred is. It is
      parameterised by HOW A VALUE BECOMES AVAILABLE — a `then`, of a value and a continuation, and
      a `pure`, of a value already available — and it composes the step out of those two and nothing
      else. The SYNCHRONOUS DEFAULT is (fn [v f] (f v)) and identity, needs no dependency at all, and
      reproduces today's step exactly, so (reduce (compile sh) init events) is unchanged and every
      existing test passes untouched. The async layer passes d/chain and d/success-deferred and
      requires manifold on its own account.
    - THIS IS THE GLOBAL RULE APPLIED and not a new idea: `do not thread options through layers we do
      not own — inject a function that closes over them`. The core requires no manifold, so :layering
      holds and .compile still knows nothing of streams.
    - THE COST, said out loud: the step's RETURN TYPE is now the caller's to know. Under the default
      it is a State and under the async layer it is a deferred State, so the :malli/schema on compile
      cannot say [:=> [:cat State Event] State] for both. Whether that becomes two schemas or one
      loosened one is an implementation question for the async target.
    - A DEFERRED UNDER THE SYNCHRONOUS DEFAULT IS DEREFERENCED, decided 2026-08-31 by the author, and
      it is better than the guard that was going to be recommended: synchronous is exactly what
      `block until it is available` means, so there is nothing to refuse. The default `then` derefs
      what it is given when that thing is derefable and passes it along otherwise.
    - AND IT COSTS NO DEPENDENCY, which is why it fits. clojure.lang.IDeref is CLOJURE'S and not
      manifold's, and a manifold deferred implements it — that is what makes @d work — so the core
      tests for IDeref and never learns that manifold exists. A handler's answer is a MAP, and a map
      is not IDeref, so the common path is untouched.
    - THE COST, said out loud: the synchronous path can now BLOCK, and with no timeout a handler whose
      deferred never resolves hangs the reduction forever. clojure.core/deref has a 3-arity taking a
      timeout, so a bounded wait is available without manifold if it is ever wanted; choosing a
      default timeout is policy and none is chosen.
    - VERIFIED 2026-08-31, the day manifold landed, exactly as this entry said to: a manifold
      Deferred IS a clojure.lang.IDeref and derefs to its value, d/success-deferred likewise, and
      d/chain takes a plain value as happily as a deferred. Nothing was read and reasoned any more."

   :a-handler-belongs-to-the-event
   "DECIDED 2026-08-31, by the author, and it is the README's own reading recovered: A HANDLER IS
    CHOSEN BY THE EVENT ALONE. `Each transitions (by event only, a function handle the event, return
    value will be applied to a state)` says it, and the first implementation had keyed the handler on
    [state, event] instead.
    - WHAT MOVES: the handler and its :out go from the TRANSITION to the EVENT.
      (event id schema handler) and (event id schema handler out); (transition from event to).
    - THE TARGET STILL COMES FROM THE GRAPH. Only the HANDLER is the event's; where the machine lands
      is [state, event] -> to as before, because A -submit-> B beside C -submit-> D is the thing
      :the-event-catalogue-is-denormalised exists to keep expressible.
    - WHY IT IS BETTER QUITE APART FROM ANY PARALLELISM, which is the reason to do it: two edges can
      no longer DISAGREE about a handler, because there is one declaration and not two — a
      construction-time check is replaced by a shape in which the error cannot be written. And it
      finishes :a-handler-answers-a-map-and-declares-it: the handler's function schema is
      [:=> [:cat <the event's schema>] <the event's :out>], so BOTH halves now come from the event
      definition and nothing at all from the graph.
    - THE COST, said out loud: A -submit-> B and C -submit-> D SHARE one handler and one :out, where
      before they could differ. The static check gets harder for it, and rightly — submit's :out must
      now satisfy B's schema AND D's. Where two edges genuinely need different data, that is two
      events, which is what :v1-is-deterministic already says about branching."

   :an-ignored-event-is-not-an-error-but-is-not-silent
   "DECIDED 2026-08-31. An event the current state has no transition for is NOT AN ERROR — the
    reduction stays total — but the step must SAY it happened, and history is where that is recorded.
    - WHY NOT AN ERROR: nothing controls the order events arrive in behind a stream, so a :cancel
      landing after :complete is ordinary traffic and not a defect. A machine that throws on it is a
      machine every caller needs a policy for.
    - WHY NOT SILENT EITHER, which is the change: an event that SHOULD have transitioned and did not
      looks exactly like one that was correctly ignored, and no static check can see a runtime fact.
      Persistence is history, so an audit trail is precisely the place this belongs.
    - THE HANDLER DOES NOT RUN. No edge means no :to, so there is no enter-schema to validate against
      and nothing to apply the data TO. The data is not merged — it is never computed.
    - AND THAT IS WHAT MAKES IT SAFE, because the alternative is worse than it looks. Malli maps are
      OPEN BY DEFAULT, so merging a handler's answer into a state with no edge for it would produce a
      state carrying keys that state never declared AND PASSING ITS OWN ENTER-VALIDATION. Verified;
      see :what-the-design-conversation-verified. `Able to apply, but wrong` was the author's phrase
      for it and it is the sharpest hazard this design had."

   :one-ordered-stream-per-instance
   "DECIDED 2026-08-31, by the author, and it is a REQUIREMENT THE LIBRARY STATES rather than an
    assumption it quietly makes. A machine is fed ONE TOTALLY ORDERED stream of events. A caller with
    several sources merges them into one order BEFORE the machine sees them, because the caller is
    the only one who can — the machine has no clock and no way to know two events were concurrent.
    - WHAT IT BUYS IS A WHOLE FEATURE. If external order is guaranteed then an event this state
      cannot handle is never EARLY: it is irrelevant, or it is a bug in whoever produced it. So
      DEFERRED EVENTS — the UML statechart mechanism where a state parks an event and the machine
      re-delivers it after moving on — are not needed, and are out of v1. That is a per-instance
      queue, a re-drive on every state change and a deadlock case, all avoided by writing an
      assumption down instead of leaving it unsaid.
    - WHERE IT BREAKS, so that nobody is surprised by it: two producers with no shared clock, an
      at-least-once transport that redelivers, a partitioned queue where one instance's events span
      partitions. Each is real, and each is the caller's to fix upstream."

   :a-handler-causes-nothing
   "DECIDED 2026-08-31. In v1 A HANDLER MAY NOT CAUSE ANOTHER EVENT. It answers a data map and that is
    all it does; a cascade is spelled as the caller feeding the next event.
    - WHY IT MATTERS: a handler that raises an event is the classic source of SELF-INFLICTED disorder,
      and it is the reason statecharts have RUN-TO-COMPLETION — one external event processed fully,
      internal events and all, before the next is accepted. With no emission there are no internal
      events, so there is no queue to drain and no RTC to implement, and the core stays the reduction
      the README promises.
    - IT IS A CONTRACT AND NOT A GUARANTEE, and the difference matters here. :a-handler-may-answer-later
      allows deferreds precisely so a handler can do I/O, and a handler doing I/O can publish to the
      very stream feeding this machine. No schema catches that. It is a rule people follow, and the
      failure mode when they do not is an ordering bug wearing the mask of a logic bug.
    - AN EXTERNAL EVENT IS THE ULTIMATE SOURCE OF A TRANSITION, said by the author on 2026-08-31 and
      worth keeping as the principle: the world moves the machine. An INTERNAL event is not a second
      kind of cause, it is a convenience, and the thing it buys is HANDLER REUSE — that is the whole
      motivation and it is smaller than `cascades` makes it sound.
    - THE DOOR, NARROWED. The raise belongs to the STATE — arriving somewhere is what has
      consequences — and not to the edge or the event. Two reasons that arrive there separately: the
      programming model is simpler, since a handler still answers a PATCH and the state applies it
      and only then raises; and an entry raise is UNCONDITIONAL, so the raise-driven relation is a
      plain graph and a cycle in it PROVES the machine can raise for ever, where a raise conditional
      on a handler could only ever be reported as possible. That second one is the difference between
      a fault `problems` may report and a warning it may not.
    - WHAT IS TURNED DOWN IS THE HANDLER KNOWING. A handler answering both a patch and a set of
      events to raise undoes :a-handler-answers-a-map-and-declares-it — the answer stops being a map
      merged into the state, so :out no longer describes it and the static check loses its subject.
      Reuse does not need it: the state can raise what the handler never mentioned.
    - AND IT IS NOT DESIGNED, deliberately, 2026-08-31. The handler's signature is UNCHANGED and
      nothing is owed. If internal events prove common enough in a shape written in anger, design it
      then, starting from the state-raises lean above. Whoever does should weigh one thing this
      conversation raised and did not settle: AN INTERNAL RAISE IS A SECOND EVENT SOURCE, and
      :one-ordered-stream-per-instance pushed source-merging onto the CALLER precisely because the
      machine has no clock. A queue inside the machine is the machine doing that merging, on an order
      somebody has to choose.
    - WHAT WAS TURNED DOWN: a handler answering both a delta and events to raise, {:data {...}
      :raise [...]}. Least ceremony to write, and it undoes what
      :a-handler-answers-a-map-and-declares-it bought — the answer stops being a map merged into the
      state, so :out no longer describes it and the static check loses its subject."

   :how-the-step-says-a-thing-was-ignored
   "DECIDED 2026-08-31, and only decidable once :one-ordered-stream-per-instance and
    :a-handler-causes-nothing had removed every reason to PARK an event. A THIRD INJECTED FUNCTION,
    beside the `then` and `pure` of :a-handler-may-answer-later: an `ignored` of a state and an event,
    defaulting to (fn [state _event] state), which the store layer replaces with one that records.
    - WHY THIS AND NOT A RICHER RETURN. An outcome value — {:state s :outcome :ignored} — is the
      STRUCTURAL answer, impossible for a caller to miss, and it was the better choice for as long as
      `ignored` might have had to grow into `deferred`. With deferral out the signal is two-valued and
      stays two-valued, and the outcome value's cost is real: (reduce step init events) would stop
      yielding states, and that reduction is the README's own headline sentence.
    - identical? IS NOT IT, and it was checked before being recommended rather than after — see
      :what-the-design-conversation-verified. Metadata on the state is worse still: merge and assoc
      PRESERVE metadata, so a stale flag would ride into every later state.
    - THE COST: the guarantee is OPT-IN. A layer that injects nothing gets today's silence. It is the
      store layer that wants the record and the store layer that injects, so the default is only ever
      taken by a caller recording nothing anyway."

   :a-trap-is-what-a-cycle-hides
   "BUILT 2026-08-31, and the cheapest thing that was left. `traps` answers the REACHABLE states
    from which no ending can be reached: the machine stays alive, goes on accepting events, and can
    never legitimately finish.
    - IT IS `reachable` RUN BACKWARDS, which is why it was cheap. `finishable` traverses the
      TRANSPOSED graph from every :final — uber/transpose and alg/pre-traverse, both already on
      hand and both checked before being used — and a trap is a reachable state not in it.
    - BOTH OTHER STRUCTURAL CHECKS WALK STRAIGHT PAST IT, and that is the whole argument for it.
      `unreachable` cannot see it, because a forward traversal gets there. `dead-ends` cannot,
      because a trap HAS out-edges: going nowhere and going nowhere USEFUL are different faults. A
      dead end is a trap of SIZE ONE; two states bouncing off each other are the smallest
      interesting one. The `trapped` fixture is exactly that, and check/problems answered [] on it
      before this existed — the suite records that fact rather than describing it.
    - THE EXCEPTION IS WHY IT WAITED, and the fix was to put it in `finishable` and not in `traps`:
      where a shape declares no :final at all, EVERY state is finishable, vacuously, so the check
      is silent of its own accord rather than by a special case. A machine never meant to terminate
      is not a broken one, and the `endless` fixture asserts that silence.
    - `traps` IS TOTAL AND `problems` IS WHAT FILTERS, the pattern `subsumption` already set. A
      dead end is in `traps` and is reported as :dead-end, the sharper of the two diagnoses, so
      every state is named once and named by the more specific fault.
    - AND IT IS VISIBLE IN THE PICTURE, which is what :what-the-graph-buys claims for the drawing:
      `oops` leads into a two-node pocket with no arrow reaching the double circle. Not as stark as
      an island, and still obvious."

   :two-events-in-flight-at-once
   "DECIDED 2026-08-31, and it is the async story. A handler may answer a deferred, so a second event
    can arrive while the first is still in flight, and a machine is in ONE state at a time. What may
    be done about the second is the whole question.
    - THE TWO CASES, the author's: where the state admits ONLY the first event, the second must WAIT
      and be applied to the updated state. Where the state admits BOTH, they may in principle be
      applied in order of COMPLETION.
    - BUT `BOTH ADMITTED` IS NOT THE CONDITION, and this is where the first analysis was wrong. Both
      admitted means each is INDIVIDUALLY legal there, not that they COMMUTE. idle -start-> running
      beside idle -cancel-> cancelled: both legal, and if start completes first the machine is in
      running, which has no cancel edge, so the cancel is SILENTLY DISCARDED and the caller believes
      they cancelled. Reverse the completion order and it lands. That is a flake, not a race anybody
      chose.
    - THE CONDITION IS CONFLUENCE — the diamond [S,A]->Ta, [S,B]->Tb, [Ta,B]->X, [Tb,A]->X with the
      same X — plus patches that commute, plus both intermediate states being enterable.
    - AND CONFLUENCE COLLAPSES TO SELF-LOOPS, which is what makes this cheap. If both events are
      self-loops then Ta = Tb = X = S and the diamond closes TRIVIALLY, with no graph query. If
      either event leaves the state, the other route has to exist AND rejoin, which MEASURED over
      this project's own fixtures never happens — see :confluence-was-measured-not-guessed. Divergence
      is the POINT of a state machine, so confluence is the exception and not the rule.
    - SO THE RULE IS SMALL. SERIALISE BY DEFAULT, always correct and needing no annotation. Take
      concurrency only where both pending events are SELF-LOOPS on the current state and their :out
      key sets are DISJOINT — mu/keys on each, and that declaration was paid for by the subsumption
      check already. That is the niche where it pays anyway: a form being filled in, a document
      edited, independent fields updated while the machine stays put.
    - NO DEPENDENCY AND NO INDEPENDENCE IS DECLARED. Dependency is the default and needs no saying.
      An author-asserted independence was considered and turned down twice over: the shape ALREADY
      says which events are self-loops, so nothing needs asserting; and independence is
      STATE-RELATIVE, so a global claim would be refuted somewhere in most real shapes and would
      rarely be usable.
    - THE PATCH IS NEVER STALE, ONLY THE ADMISSION IS, which is a payoff from
      :a-handler-belongs-to-the-event. A handler answers from the event alone, so what it computed
      while the machine was in S is still exactly right in T; it is only whether T admits the event
      that can have changed, and that is re-looked-up at application time as any other step is.
    - THE COST, ACCEPTED by the author: where concurrency is taken, HISTORY ORDER STOPS MATCHING
      ARRIVAL ORDER. Persistence is an audit trail, so the log has to represent that honestly rather
      than pretend to a sequence that did not happen.
    - THE CHECK IS BUILT, 2026-08-31: check/confluence publishes a verdict per pending pair per
      state, and check/commuting reduces it to {state #{#{a b}}} — the proven pairs, as PLAIN DATA
      the async layer is handed the way it is handed a compiled step, which is how that layer still
      knows nothing of shapes. THE GENERAL DIAMOND IS WHAT GOT IMPLEMENTED and not the self-loop
      shortcut, because it costs the same four lookups and answering :no for `not both self-loops`
      would have been a LIE — a general diamond can close. Self-loops remain where it pays; nothing
      is special-cased for them.
    - AND THE INTERMEDIATE STATES NEEDED NO CHECK, which fell out rather than being solved: if
      [ta b] is an edge at all then `subsumption` has already asked whether ta admits what b
      produces. One of the three conditions was already paid for.
    - THE RUNTIME DOES NOT TAKE THE LICENCE YET, and this is a GAP found by building the async layer
      rather than by thinking about it. Concurrency for a licensed pair needs the HANDLER run apart
      from the APPLICATION — two handlers in flight, their patches applied in order of completion —
      and `compile` answers ONE step that does both at once. Calling that step twice from the same
      state answers two whole states derived from it, and combining those is only correct where both
      events are self-loops with disjoint patches, which is LESS than `commuting` licenses. So
      async/drive serialises always, and says so rather than pretending.
    - WHAT WOULD CLOSE IT is a decision for the author, not a refactor: split `compile` into a PATCH
      phase (run the handler, check it against its own :out) and an APPLY phase (merge, write :id and
      :instance, validate on enter). Which is exactly the shape :a-handler-causes-nothing already
      leans towards for chained events — a handler answers a patch, a state applies it — so one
      decision may pay for both."}

  :open-questions
  ["ARE INTERNAL EVENTS WANTED AT ALL? Deliberately left open on 2026-08-31 rather than answered, and
    the handler's signature is unchanged in the meantime, so nothing is blocked by it. The motivation
    is HANDLER REUSE and not cascades for their own sake; the lean is that a state raises and a
    handler never does, for which the reasons are in :a-handler-causes-nothing. The bar for building
    it is a real shape asking twice. Three things to settle before any of it: whether the machine may
    drive itself at all or a caller triggers the next event by hand — the latter costs nothing and
    hides the flow from check, which is the whole trade; if it may, whether the queue drains
    breadth-first or depth-first, which is OBSERVABLE in the history and cannot be left to whatever
    `into` happens to do; and how an audit trail tells what the world did from what the machine did,
    because a log that conflates them is worse than one that does not have the internal events at
    all."]

  :project-knowledge
  {:status
   "TARGETS 1 AND 2 ARE BUILT. Target 1, 2026-08-30 (commit a588c93): robertluo.state-graph.shape
    and .compile — the lifecycle runs, (reduce (compile shape) (initial shape data) events).
    Target 2, 2026-08-31: robertluo.state-graph.check — reachability, dead ends, a partial
    subsumption checker and the drawing.
    THE DESIGN DECISIONS OF 2026-08-31 ARE IN THE CODE as of the same day: the handler is the
    EVENT's, an event nobody handled is heard through :ignored, compile is parameterised by a
    Context of :then/:pure/:ignored, a deferred under the synchronous default is dereferenced, and
    :instance names a run. Verified live afterwards: the counter drew correctly, `broken` still
    reports its two islands, three dead ends and one :target-refuses, and a reduction carrying an
    :instance ends {:id :done :instance order-1 :n 9}, that name having been given once to
    `initial` and never spelled again after.
    THE TRAP CHECK LANDED the same day too — see :a-trap-is-what-a-cycle-hides — so the structural
    checks are now reachability, dead ends, TRAPS and subsumption.
    THE CONFLUENCE CHECK LANDED 2026-08-31 as well — see :two-events-in-flight-at-once — so the
    static checks are reachability, dead ends, traps, subsumption AND confluence.
    AND SO DID THE ASYNC LAYER, the same day: robertluo.state-graph.async, manifold 0.4.3, `drive`
    and `fan`. It serialises always; the licensed concurrency is a GAP with a reason, recorded in
    :two-events-in-flight-at-once.
    43 tests, 156 assertions, green; clj-kondo clean; all 29 public fns carry a :malli/schema, which
    the instrument! count of exactly 29 confirms better than a grep can. THE COMMIT GATE IS A REAL
    GATE: 40 tests unit, 3 ^:integration.
    NOT BUILT still: the FACADE (robertluo.state-graph) and the STORE (datahike), which remains a
    dependency nothing uses. The integration suite NEEDS GRAPHVIZ — see
    :graphviz-and-the-devenv.
    NOT BUILT, and named so nobody assumes otherwise: the FACADE (robertluo.state-graph) does not
    exist, so an application requires the namespaces directly; async and store are untouched."

   :gaps-in-the-repository
   "Found by reading deps.edn against README.md, and every one of them will bite on first use:
    - MANIFOLD IS NOT A DEPENDENCY: CLOSED 2026-08-31. manifold 0.4.3 is in deps.edn and
      robertluo.state-graph.async is built. The REPL did have to be restarted, exactly as this entry
      warned — a running classpath cannot be repaired from inside.
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

   :what-target-2-taught
   "VERIFIED BY RUNNING on 2026-08-31:
    - viz-graph WITH :format :dot NEEDS NO GRAPHVIZ. Reading its source: :save with :format :dot is
      a `spit` of the dorothy string, every OTHER format calls dorothy.jvm/save! which shells out,
      and no :save at all calls show!. So the drawing is testable on a machine with no `dot`
      installed — which this one is — and that is what made ^:integration honest at last: the test
      writes a file, which is a real thing and nothing to mock.
    - ITS :auto-label IS USELESS HERE. It pprints the whole attribute map into the label, and ours
      holds a COMPILED MALLI SCHEMA and a CLOSURE. Hence `labelled`, which sets our own: the state
      id with a marker and its schema FORM, and the event name on the edge. Assert that no `$eval`
      reached the output — a closure in a picture is the failure mode.
    - alg/pre-traverse walks DIRECTED edges from a start node, which is what reachability wants.
    - THE SEVEN PRIMITIVE TYPES ARE PAIRWISE DISJOINT, checked and not assumed — every value of
      each was validated against the other six and nothing overlapped, :int against :double
      included. That check is what licenses `admits` to answer :no from a type difference alone.
    - mg/sample TAKES {:size n} AS THE COUNT, not as test.check's generator size: (mg/sample s)
      gives 10 and (mg/sample s {:size 30}) gives 30. Surprising, and it matters in a property that
      is looking for a counterexample.
    - THE INSTRUMENT COUNT CAUGHT A STALE REPL, exactly as the sibling project warns. It still said
      14 after `check` was written, because test-support's `namespaces` had been edited on disk and
      not reloaded in the REPL — so nine new fns were never collected. The number is a smoke alarm
      for the fixture AND for the REPL, which is most of why it is worth asserting.
    - KAOCHA'S FOCUS-META, from :what-target-1-taught, IS RESOLVED: with one ^:integration test in
      the tree the two suites finally differ — 24 tests unit, 1 integration — and `-M:dev:test` no
      longer runs everything twice."

   :what-the-design-conversation-verified
   "VERIFIED BY RUNNING on 2026-08-31, while settling the open questions and before anything was
    written down. Neither is about a target; both decided a design.
    - MALLI MAPS ARE OPEN BY DEFAULT. (m/validate [:map [:n :int]] {:n 1 :total 5}) is TRUE, and only
      {:closed true} refuses it. This is what makes `able to apply, but wrong` SILENT rather than
      loud: a handler's answer merged into a state that has no edge for that event would validate
      against that state's own schema while carrying keys it never declared. It is the reason the
      data is discarded on a miss rather than merged. See
      :an-ignored-event-is-not-an-error-but-is-not-silent.
    - A FIRED TRANSITION CAN RETURN AN IDENTICAL STATE, so identical? cannot signal `ignored`. For
      s = {:id :a :n 1}, all three of (merge s {}), (assoc s :id :a) and (assoc (merge s {}) :id :a)
      are identical? to s — Clojure's map assoc answers `this` when the value is already there. A
      self-loop whose handler answers {} is therefore indistinguishable from an event nobody handled.
      Checked because it was about to be recommended as a free signal."

   :what-the-handler-move-taught
   "VERIFIED BY RUNNING on 2026-08-31, building the decisions of that day.
    - DENORMALISATION PAID FOR ITSELF, and this is the finding worth keeping. Moving the handler and
      its :out from the transition to the event changed shape.clj AND NOTHING ELSE — compile.clj and
      check.clj needed not one edit, because `transitions` already flattens the catalogue onto every
      edge and both of them read a shape only through it. The suites went green on the first run.
      That vocabulary function is doing more work than its size suggests, and the lesson is that a
      reading layer between the graph and its consumers is what let a structural change stay local.
    - A 2-ARITY DELEGATING TO A 3-ARITY BREAKS UNDER ITS OWN INSTRUMENTATION when the extra argument
      refuses nil. (initial sh data) calling (initial sh nil data) goes through the INSTRUMENTED var,
      so nil is checked against Instance and throws. Loosening to [:maybe Instance] is NOT the fix:
      Instance is `some?`, and `some?` behind a :maybe admits every value there is, so the schema
      would assert nothing at all. The fix is a private helper both arities call, which is not
      instrumented and keeps the public schema strict.
    - CLOJURE'S OWN DEREFABLES TEST THE DEREF DECISION WITH NO MANIFOLD. A delay, a promise and a
      future are all clojure.lang.IDeref, so `a deferred under the synchronous default is
      dereferenced` is asserted today, on the classpath as it stands. What that does NOT prove, and
      what stays UNVERIFIED, is that manifold's Deferred implements IDeref.
    - A HANDLER CANNOT REACH :instance EITHER, by the same construction that stops it reaching :id:
      both are written AFTER the merge. Worth an assertion of its own, because a handler answering
      {:instance ...} is a plausible mistake and a silent one — it would move a row in the audit log
      to another machine."

   :what-the-async-layer-taught
   "VERIFIED BY RUNNING on 2026-08-31, building robertluo.state-graph.async.
    - s/connect IS ASYNCHRONOUS, AND IT COST A LOST STATE. The first `fan` gave each machine its own
      stream and s/connect-ed them into one output; closing that output once every machine reported
      done DROPPED whatever was still in a connect pipeline. Seen, not theorised: instance `a` ran
      three events and only two states came out, while its :done carried the third. The fix removes
      connect — every machine writes STRAIGHT to the shared sink, so a machine's :done cannot resolve
      until its last state has been ACCEPTED there. A private `pump` that does not close the sink is
      what makes one output shareable at all.
    - A BOUNDED DEREF IS THE ONLY HONEST ONE IN A STREAM TEST. Every deref in async-test carries a
      timeout, so a machine that hangs FAILS instead of hanging the suite. Streams are the one thing
      in this project that can wait for ever.
    - ONLY ONE TEST NEEDED A CLOCK. Serialisation is asserted with a handler that really is slower
      (d/future plus a sleep), and that one is ^:integration; everything else uses immediate
      deferreds and is deterministic. The assertion is about ORDER, not timing, so it does not care
      how slow the slow one is.
    - MANIFOLD DRAGS IN slf4j-api WITH NO BINDING, so the state-graph suite now prints three SLF4J
      NOP lines on stderr, as the sibling project already did. Noise, not a fault, and worth knowing
      before someone hunts it."

   :confluence-was-measured-not-guessed
   "MEASURED BY RUNNING on 2026-08-31, over this project's own fixtures, when the question was whether
    two events pending in one state may be applied in completion order. `Commute by default` was
    proposed and the numbers refused it.
    - For every state, every pair of distinct events admitted there was checked for a closing diamond:
      counter has ONE such pair, :set and :stop in :running, and it does NOT commute — set-then-stop
      lands :running then :done, while stop-then-set lands :done and then finds NO [done, set] edge,
      so the :set is silently discarded. trapped has one pair, :oops and :stop in :running, and it
      does not commute either.
    - So ONE HUNDRED PER CENT of the concurrent-candidate pairs that exist in this codebase fail
      confluence. Commute-by-default would have been wrong in every case there is, and wrong SILENTLY
      and ORDER-DEPENDENTLY, which is the worst way to be wrong.
    - The finding is structural rather than a fixture accident: different events take you to different
      places, and that is what a state machine is FOR. It is why :two-events-in-flight-at-once
      serialises by default and licenses only self-loops."

   :graphviz-and-the-devenv
   "ADDED 2026-08-31: pkgs.graphviz is in ../devenv.nix, because a drawing nobody can look at is
    not worth having. graphviz 15.1.0; `dot` was NOT on the path before, and the devenv is shared
    with the sibling project, which does not need it and is not harmed by it.
    - TWO TESTS, TWO REQUIREMENTS, and the split is deliberate. :format :dot is a spit and needs
      NOTHING, so the source is asserted about anywhere. :format :png shells out, and that test is
      the only thing proving the RENDERING path — asserted on the PNG MAGIC BYTES (0x89 P N G),
      because a file existing proves only that something wrote one. Verified BOTH ways: outside the
      devenv the render test errors and the source test passes; inside, both pass.
    - A JVM INHERITS ITS PATH AT LAUNCH, so a REPL started before graphviz was added CANNOT draw,
      however current the devenv is. That is the same class of mistake as a stale REPL and it looks
      just as puzzling — the shell has `dot` and the REPL does not. Start the REPL from inside the
      devenv: `devenv shell -- sh -c 'cd state-graph && clojure -M:dev:nrepl'`.
    - TO LOOK AT A SHAPE: (check/draw! sh) with no :save opens a viewer window; with
      {:save {:filename f :format :png}} it writes a file. The drawing marks the initial state ▸
      and gives a :final one a double circle, and labels every node with its schema FORM.
    - Verified live: the `broken` fixture rendered, and its island — two states reaching only each
      other — sits VISIBLY DETACHED from everything else. That picture is the argument for the
      library, and it is the thing a map literal cannot show."

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
   "../smart-boundary/AGENTS.md is the sibling component, the same author's larger project and its :project-knowledge is worth reading
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
  ;; NOTE 2026-08-31: (check/draw! shape {:save {:filename f :format :dot}}) writes the graphviz
  ;; SOURCE with no graphviz installed — only other formats shell out to `dot`. So the drawing can
  ;; be asserted about even where it cannot be rendered, and `dot` is needed only to LOOK at it.

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
