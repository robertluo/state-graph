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
  :why-it-exists
  "SAID BY THE AUTHOR 2026-09-01, and it is the frame everything else here sits in: THIS LIBRARY
   EXISTS FOR THE SIBLING COMPONENT'S AGENT WORKFLOWS. smart-boundary needs a different concrete
   workflow per scenario, and the two ways to write that in ordinary code are both bad — a lot of
   long, nearly identical code, or worse, a configuration format pretending to unify them at the
   surface. A state machine is the third way.
   - COMPLEX IS NOT DIFFICULT. What those workflows need is GLUE, and glue is what a machine
     replaces. The argument is about VOLUME rather than cleverness, which is why it is convincing.
   - MACHINES COMPOSE, and nesting made that easier still — see :a-machine-can-nest-in-a-node.
     Across scenarios most parts are the SAME and only the ASSEMBLY differs: a state, an event
     with its handler, and a whole shape are all plain values, so an assembly is
     (apply shape (concat parts wiring)) and one child nests into two parents with nothing to
     alias. MEASURED rather than assumed, with one edge worth knowing before anybody designs a
     parts library — see :what-the-parts-library-showed.
   - A WORKFLOW MOSTLY IN DATA IS STORABLE AND DRAWABLE, which is what the README claims for the
     graph and what the agent case actually needs: a person has to be able to SEE the workflow an
     agent is running.
   - AUDITABILITY AND STATIC CHECKING ARE THE POSITION. For an agent workflow the two questions
     that matter are `what happened` and `could this ever have worked`; the transition results
     answer the first and `problems` answers the second, before anything runs.
   - THE PARALLELISM CLAIM, SAID CAREFULLY. `The first FSM that supports parallelism` is not the
     claim to make in public: Harel statecharts have had orthogonal regions since 1987, every
     workflow engine runs steps at once, and :parallel-is-across-instances puts orthogonal regions
     deliberately OUT of scope — so it argues against this project's own design. What is
     defensible is narrower and stronger. ONE CALL RUNS THOUSANDS OF INSTANCES AT ONCE, each
     serialised, with real backpressure. A HANDLER MAY ANSWER A DEFERRED, so an instance waiting
     on a model call holds no thread, which is the property that decides whether an agent workflow
     scales at all. And CONCURRENCY CAN BE PROVEN: check/confluence answers statically which
     pending pairs may be applied in order of completion, and no other FSM library appears to
     answer that question at all. Raised in the same conversation and not disputed."

  :source-of-truth
  "README.md is the specification and this file is its reading. Where the two disagree the README
   wins and this file is wrong — say so and fix it.
   REWRITTEN 2026-09-01 TO THE FINISHED SHAPE, at the author's instruction, so the README now
   documents the library that EXISTS rather than the one to build: every example in it was run
   against the code before it was written down. What that changes for a reader of this file is that
   PROPOSED is no longer a category — everything is built — and a divergence is now a bug in one of
   the two files rather than a gap in the repository. The README still wins.
   THE README ALSO CARRIES THE LIMITS, deliberately: no guards, no state-dependent update, a merge
   cannot remove a key, no internal events, no persistence, and the licensed concurrency not taken.
   A user meeting one of those should meet it in the README and not in a surprise."

  :constraints
  {:tests-clojure-test true
   :tests-generative-first true
   :test-tree "test/, one <ns>_test.clj per source namespace"
   :test-runner "kaocha, two suites: unit and integration, separated by a ^:integration meta"
   :notebook-cmd "clojure -X:notebook"
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
   :deps {:ubergraph "0.9.0" :malli "0.20.1" :manifold "0.4.3" :test.check "1.1.1"
          :dev {:nrepl "1.3.0" :kaocha "1.91.1392"}}
   :inherited-from "GIT HISTORY, under smart-boundary/AGENTS.md — the sibling component the house
                    rules came from, removed 2026-09-02 once each component carried its own. The
                    rules hold here regardless (dependencies point down, errors are data, only
                    assert what can fail, a store is closed in a `finally`); its :project-knowledge
                    was about Anthropic, Datalevin and nREPL and was never about this project. Its
                    living descendants are ../coder/AGENTS.md and ../llm-function/AGENTS.md."}

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
    edge, that malli validates, that manifold delivers what was put on a stream — and do not
    re-test our own code through a second door. A FACADE RE-EXPORT IS THAT SECOND DOOR: the
    delegation is not worth a test, and what the facade adds is"
   "A GENERATIVE TEST NEEDS AN INDEPENDENT INVARIANT. A property that recomputes the expected
    answer the way the implementation computes it agrees with every bug it contains: it catches a
    wrong implementation and never a wrong understanding. For an FSM the honest invariants are
    structural — a reduction over events lands only in states the graph admits, a state entered
    validates against its own schema, replaying a prefix and then the rest equals replaying the
    whole — and those hold whatever the handlers do"
   "EVERYTHING A TEST OPENS IS RELEASED IN A `finally`. `finally` is release and is NOT the
    forbidden try/catch. In the sibling project one un-closed Datalevin environment kept the JVM
    alive after the suite had finished, because its executor threads are not daemons. NOTHING HERE
    OPENS A DATABASE ANY MORE (see :nothing-is-persisted-here), so what this now governs is files
    and streams — and a STREAM has a second obligation the rule does not cover: a deref of a
    machine that never resolves hangs the suite, so every deref in a stream test is BOUNDED"
   "COMMIT GATE: clojure -M:dev:test integration passes. The fast suite is for every save"
   "EDITING THIS FILE BY STRING REPLACEMENT? ANCHOR ON A KEY *AND ITS OPENING QUOTE*. A bare
    `:some-key` matches its own PROSE REFERENCES, of which every decision here has several, and
    an indented reference contains the same characters as a top-level key — so a replacement
    aimed at an entry lands INSIDE another entry's string, silently, and the file stops
    parsing. This cost two repairs on 2026-09-01 alone, both caught by the edn check below and
    neither visible to a bracket balance."
   "THIS FILE IS DATA, SO CHECK IT BY PARSING IT. An unterminated string is INVISIBLE to a bracket
    balance — it shifts which quotes pair with which and leaves every { and } matched — so a
    balance check passes a file that no reader can read. Two entries were added with no closing
    quote on 2026-08-31 and the balance check said fine each time; what caught it was
    clojure.edn/read-string, which answered `Invalid number: 2026-08-31.` because it was reading
    prose as data. Verify with (clojure.edn/read-string (subs s (index-of s \"{:statechart/id\")))
    and nothing weaker. (smart-boundary/AGENTS.md did NOT parse — `Duplicate key: a` — and it
    predated any of this; the file was removed 2026-09-02 with that component, so the finding is
    only a reminder that an unparsed AGENTS.md can live for weeks without anybody noticing.)"]

  :layering
  ["EVERY LAYER IS NOW BUILT, 2026-09-01, and the store that was in this list is GONE — see
    :nothing-is-persisted-here. What is left is four namespaces and one arrow through them.

    robertluo.state-graph          — BUILT 2026-09-01. THE FACADE: the vocabulary a user needs, and
                                     the only require an application should have. Ten functions —
                                     state, event, transition, shape; problems, draw!, dot; compile,
                                     initial; run — being the constructors, the checks, and the two
                                     doors. Requires everything below it, `check` included, which is
                                     what one require costs. See
                                     :the-facade-is-a-vocabulary-and-two-doors
    robertluo.state-graph.async    — BUILT 2026-08-31. A DEFAULT, not the core: manifold streams.
                                     Takes a compiled step FUNCTION, a way to make a first state and
                                     a way to make an OUTPUT VALUE, all as VALUES, and knows nothing
                                     of shapes, schemas or graphs. `drive` is one machine,
                                     serialised; `fan` partitions on :instance and runs one per
                                     machine, concurrently. Both answer {:states :done}, two
                                     different things under two names
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
                                     A node may carry a whole SHAPE as its :machine, so the type
                                     is recursive and every layer above recurses with it.
                                     Requires ubergraph and malli only

    The default sits BELOW the facade rather than beside it because of the nesting rule — a
    child may not require its parent — and it costs nothing, since it does not need the facade's
    vocabulary: what it needs is a function and some data, handed over as values."]

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
    into that reduction or getting RESULTS out of it — which is exactly why a stream is a layer
    above and not the core, and why `run` is a caller this library ships rather than a second kind
    of machine. See :the-caller-owns-the-lifecycle."

   :the-defaults-are-batteries
   "Async is a DEFAULT. Someone with their own stream library must be able to use the compiled
    function directly and lose nothing, so it may not take a shape as an argument — and it does not:
    it is handed a step, a way to make a first state and a way to make an output value, all as
    VALUES.
    - REVISED 2026-09-01, twice over. Persistence is no longer a default because it is no longer
      anything at all, see :nothing-is-persisted-here. And `neither may be required by the facade's
      core path` did NOT survive the facade being built: robertluo.state-graph requires .async, so
      requiring the facade loads manifold. What the rule was protecting survives one level down —
      robertluo.state-graph.compile requires no manifold and never will — and that is the honest
      statement of it. See :the-facade-is-a-vocabulary-and-two-doors, where `check` costs the same
      way for the same reason."

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
    EVENT ALONE — (handler event) — and never the state it is about to change.
    - WHAT IT DOES NOT FORBID, added 2026-09-01 when nesting was built and a reader will
      otherwise think it does: THE STEP may depend on the state as much as it likes, and
      already does — the edge lookup, the merge, :id and :instance written afterwards. So a
      NESTED machine, whose child step needs the child's current state, sits inside the
      compiler and not inside a handler. See :a-machine-can-nest-in-a-node.
    - AND IT IS NOW `NEVER THE STATE` RATHER THAN `NEVER ANYTHING`, 2026-09-01: an event may
      declare {:sees <a map schema>}, and its handler is then handed the state PROJECTED onto
      those keys and validated against them, as a second argument. The reason this entry gives
      survives intact, which is why it was allowed: the view is declared on the EVENT, so a
      handler still names what it needs BY SHAPE and stays reusable across every state that
      satisfies it, and its function schema is still complete off the event definition alone —
      [:=> [:cat <schema> <sees>] <out>]. What a handler may never do is read what was not
      declared. See :internal-visibility-is-declared-and-not-automatic. The event is a map
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
      THE NODE rather than a closure.
    - AND THAT COST IS LIFTED WHERE A VIEW IS DECLARED, 2026-09-01: :total after :add-item CAN see
      the old total, and a counter can count, by declaring {:sees [:map [:total :int]]} and
      answering {:total (+ ...)}. The dependence is then visible in the shape rather than hidden in
      a closure, which is the whole difference. What stays true is the DEFAULT — a handler with no
      view answers from the event alone and is reusable everywhere.
    - THAT DOOR IS SHUT, 2026-09-01, by the author who had named it here on 2026-08-30, and the
      reason is worth more than the door was: A COMBINE IS A MECHANISM WITH NO POLICY. It can only
      ever GROW. What a task wants is the last n, or a summary, or one field from three steps back,
      and `:messages by conj` expresses none of those — while in the domain this library was built
      for, THE PILE IS THE COST, context being metered. So the accumulation question was the wrong
      question, and the right one is who may SEE what: :internal-visibility-is-declared-and-not-automatic."

   :internal-visibility-is-declared-and-not-automatic
   "BUILT 2026-09-01, both halves, the same day it was designed — and the design below stands as
    written, with what the building taught recorded at the end of it.
    THE QUESTION, PUT BY THE AUTHOR 2026-09-01. From OUTSIDE, an
    observer sees every transition and can have the whole history — that is what the results
    stream is. From INSIDE, can an observer — a handler, or another state — get at information?
    And the constraint that decides the shape of any answer: IT IS THE CONSTRUCTOR OF THE MACHINE
    WHO DECIDES, never the library automatically, because too broad a data visibility from the
    inside brings security problems easily.
    - WHAT WAS WITHDRAWN FIRST, so the design is not read as answering it: `an agent handler must
      see the accumulated context` is too strong — plenty of steps are input to output and want
      nothing from the past — and the combining-key door is refused outright, see
      :a-handler-never-sees-the-state. Accumulation was the wrong axis.
    - THERE ARE TWO HALVES AND ONLY ONE MECHANISM WOULD SERVE BOTH. READING: may a handler see
      anything. HOLDING: what does a state keep, which is the same question because a state that
      holds everything makes every read a read of everything.
    - THE READ HALF: A VIEW DECLARED ON THE EVENT.
      (event id schema handler out {:sees <a map schema>}), and the step passes the projection as a
      SECOND ARGUMENT — (handler event seen) — where a view is declared and (handler event) where
      none is, so nothing existing changes.
      DECLARED ON THE EVENT AND NOT ON THE NODE, because that is what keeps the original reason
      intact: the handler names what it needs BY SHAPE rather than by node, so it stays reusable
      across every state that satisfies the view. That is STRONGER reuse than today's `sees
      nothing`, not weaker — a handler needing a goal cannot be written at all right now.
      AND THE FUNCTION SCHEMA STAYS COMPLETE: [:=> [:cat <the event's schema> <the view>] <out>],
      both halves still off the event definition and nothing off the graph.
    - THE CHECK COSTS NOTHING, WHICH IS THE STRONGEST ARGUMENT FOR THIS SHAPE. `admits` already
      answers it, with the view as TARGET and the source state's schema as PRODUCED. Measured
      2026-09-01, all four verdicts: a state carrying the key and more answers :yes; a state
      without it :no; a state whose key is the wrong type :no; and A STATE THAT ONLY OPTIONALLY HAS
      IT ALSO :no, which is right — a view that must be there cannot rest on a maybe. So `this
      handler asks to see what this state cannot provide` becomes a PROVEN fault, before anything
      runs.
    - THE HOLD HALF: PROJECT AT THE DOOR. A node holds exactly what it declares — the merged value
      is projected onto the keys of its enter-schema on entry. VISIBILITY IS THEN BOUNDED BY
      ABSENCE rather than by permission, which is stronger than any read rule, and it dissolves
      `a merge cannot remove a key` at the same time. It is one call in the step.
      IT IS ALSO THE ANSWER TO `OR ANOTHER STATE`: a state seeing another state's data IS the
      merge, today unconditional and undeclared, and under projection it is exactly what the
      node's schema says. One mechanism, both halves.
    - HOW BROAD IT IS TODAY, measured rather than argued: a node whose schema is [:map [:y :int]]
      declares (:id :instance :y) and RECEIVES {:x 7 :id :b :y 1}, the :x having come from the
      state before it, and it validates because malli maps are open. The tutorial shows the same
      thing as a review's :notes sitting in a published manuscript. So a handler needs no read
      capability to see stale or sensitive data — it is already in the state it is about to change.
    - THE EVENT SIDE ALONE DOES NOT GIVE THE SECURITY PROPERTY, and this is where the author's
      constraint bites hardest: a view declared by the event is least privilege BY THE HANDLER'S
      OWN WORD, and a careless or shared handler declares {:sees [:map [:token :string]]} and gets
      it. If a state holds a credential, the DATA OWNER needs the say — the node declares what it
      EXPOSES, the event declares what it NEEDS, and the check verifies need is within exposure.
      Two declarations, one handshake, and the default on both sides is DENY, which is exactly
      today's behaviour and costs nothing to keep.
    - WHAT MAKES PROJECTION A DECISION AND NOT A ONE-LINER, both said out loud: it is BREAKING,
      since every node must then declare every key it carries forward — cheap before release and
      expensive after — and A BARE [:map] STOPS MEANING `anything` AND STARTS MEANING `nothing`,
      which every generative fixture in this suite relies on. Whether projection is opt-in per node,
      shape-wide, or a `:keeps` declaration separate from the schema is the open question that goes
      with it.
    - PROJECTION WAS TAKEN, and the three-way open question that went with it is closed by
      MEASUREMENT rather than argument: projecting always cost exactly ONE test in the whole suite,
      an async fixture whose state schema was a bare [:map] while its handler set :mark. It was
      under-declared, and the fix made it honest; the `form` fixture wanted the same correction.
      Opt-in-per-node and a separate :keeps declaration existed only to avoid a cost that turned
      out not to be there, so neither was built.
    - AND THE ORDER WAS FORCED, which the design did not see: THE VIEW CHECK IS ONLY SOUND UNDER
      PROJECTION. While a node's schema was a LOWER BOUND on what it held, a key could arrive from
      three transitions back, so `admits` answering :no proved nothing and `problems` would have
      condemned shapes that run — against its own promise to report only PROVEN faults. Holding had
      to land before reading, and the two halves hold each other up.
    - WHAT IS STILL NOT BUILT is the node-side EXPOSURE, the half that carries the security property
      against a careless handler. It is additive, and it waits for a real shape to ask."

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
      v1 shape cannot express.
    - THAT COST IS PAID OFF, 2026-09-01, and from the other end than the one it was stated at: the
      merge is PROJECTED onto the target node's declared keys on entry, so dropping a field is
      declaring one fewer and a state is exactly what its schema says. What it costs instead is that
      carrying a key across several states is EXPLICIT, each of them declaring it. See
      :internal-visibility-is-declared-and-not-automatic."

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
   "SUPERSEDED 2026-09-01 by :nothing-is-persisted-here — NOTHING is persisted BY THIS LIBRARY, and
    the store namespace was never built. What survives below is the answer to a different and still
    live question: what a CALLER should keep, and why a shape is not part of it.

    DECIDED 2026-08-31: HISTORY, and not the shape. An append-only log of events and the states they
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

   :an-event-is-the-only-way-a-transition-happens
   "THE AUTHOR'S, 2026-09-03, and it makes a rule this library already intended into one it
    ENFORCES: `In a FSM, a state can only transit by an event, so inside a machine, the only way
    of doing transition is to emit an event. And this hidden transition has to be illegal.`
    - WHAT WAS ALREADY TRUE: a handler could not move the machine. `compile` projected the answer
      onto the target's keys and then put :id, :instance and :sub on AFTERWARDS, and this file and
      three docstrings said `identity is the shape's to say`.
    - WHAT WAS WRONG WITH IT: SILENCE. A handler answering :id was overwritten without a word, so
      the rule was a convention the code quietly repaired. That is the same shape of fault as a
      predicate the model cannot see, and this library's own line answers it — `what throws is a
      crossing that does not hold; those are defects, not facts about the run`.
    - AND THE AUTHOR BROADENED IT, which is what makes the fix worth having: `the event's returned
      data should match the state schema`. So the check is not about identity at all. A handler
      answers a PATCH, and it is conformed against `shape/patch-schema` — the target's own schema
      with EVERY KEY OPTIONAL and the map CLOSED.
        optional  a handler says what changed; what it does not mention the state already holds
        closed    a key the target does not declare never reached the state anyway — `mu/keys`
                  dropped it one line later — so a handler computing something that EVAPORATES is
                  a defect, and closing the patch turns a shrug into a refusal
    - IDENTITY THEN NEEDS NO SPECIAL CASE, and that is the part to keep. A state schema describes
      the map WITHOUT :id, :instance and :sub, so naming one is answering an undeclared key and is
      refused by exactly the rule that refuses a typo. One check, three guarantees, and nothing in
      it mentions identity.
    - WHERE IT SITS AND WHY: after :out and before :enter. :out is what a handler PROMISES and is
      optional, existing for the STATIC check; :answer is what the target ADMITS and is not
      optional. :enter keeps the one thing only a whole state can be wrong about — A REQUIRED KEY
      NOBODY SUPPLIED, which a patch is allowed not to mention. All three crossings are still
      distinct and each is asserted.
    - WHAT IT COST: four tests, every one of which had asserted the silence — a handler's :id
      overwritten, its :instance overruled, its :sub replaced by the child's first state, and a
      wrong-typed value caught at :enter rather than at :answer. Rewriting them is the change:
      each now asserts the refusal, and the suite went 74/205 to 76/217.
    - AND THE EMISSION HALF STAYS SHUT. The author reasoned to it independently on the same day:
      `An internal conditional should generate an event to the event queue. However, in our
      current design, the machine does not own the event queue.` Which is
      :a-handler-causes-nothing's own argument arrived at from the other end — see it below, and
      :one-ordered-stream-per-instance for why the queue is the caller's."

   :a-handler-causes-nothing
   "DECIDED 2026-08-31, and REAFFIRMED by the author 2026-09-03 on the reasoning that the machine
    does not own an event queue — see :an-event-is-the-only-way-a-transition-happens.
    In v1 A HANDLER MAY NOT CAUSE ANOTHER EVENT. It answers a data map and that is
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
    - THAT CLAIM NOW HAS AN EXCEPTION, 2026-09-01, and it is the one thing views cost: A HANDLER
      THAT READS CAN HAVE A STALE PATCH. If it computed from a key the other event changes, what it
      answers was right in S and is wrong in T. So the patch condition stopped being `disjoint :out
      key sets` and became BERNSTEIN'S — neither writes what the other writes, and neither READS
      what the other writes. MEASURED, and the old condition really did license a bad pair: writes
      of {:total} and {:n} are disjoint while :sum reads :n, and the two orders answer :total 2 and
      :total 18. check/commutes was fixed the same hour and the pair is now :unknown. An event with
      no view reads nothing, so no shape written before views is affected.
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
      decision may pay for both."

   :the-caller-owns-the-lifecycle
   "DECIDED 2026-09-01, and the question DISSOLVED rather than being answered. The author asked who
    owns an instance: the CALLER, handed a seq of events and reducing over it, or a REACTIVE machine
    taking an event stream and answering a stream of states.
    - THEY ARE THE SAME OWNERSHIP, and reading `pump` is what settles it: the state lives in a
      d/loop ACCUMULATOR exactly as it lives in reduce's. There is no cell holding it and no object;
      the atom `fan` keeps holds per-instance STREAMS and never a state. So the reactive machine is
      not a second design, it is the same reduction with the loop shipped — and `run` is A CALLER
      THIS LIBRARY SHIPS.
    - SO THE FACADE NAMES BOTH AND CHOOSES NEITHER, which is not a fence-sit. The reduction is the
      README's own headline sentence, the step is what a caller with core.async or a transducer or a
      plain fold needs, and :the-defaults-are-batteries requires that such a caller lose nothing.
    - WHAT REACTIVE-ONLY WOULD HAVE COST, said out loud because it was the tempting answer: manifold
      would then be on the ONLY path there is, and the one rule the batteries have is that it must
      not be.
    - IT IS ALSO A PROPERTY AND NOT A SPEECH. `the-two-doors-agree` generates a shape and a seq of
      events and asserts that (map :state) off the stream equals the states the reduction passes
      through. That is the only thing that can refute any of the above."

   :the-facade-is-a-vocabulary-and-two-doors
   "BUILT 2026-09-01. TEN FUNCTIONS: state, event, transition, shape to build a machine; problems,
    draw! and dot to look at it; compile and initial for the reduction; run for the stream. The
    author asked for the fewest, so each collapse below was argued for rather than assumed.
    - THE TENTH ARRIVED THE SAME DAY AND FROM A CONSUMER, which is the only good reason to widen an
      API: `dot` answers the drawing as DATA where `draw!` is the drawing as an effect, and the
      notebook could not be written without it — see :what-the-tutorial-taught. `draw!` alone cannot
      serve a renderer that is not graphviz, and every diagram in a page, a docs build or a web app
      is exactly that.
    - ONE STREAM DOOR AND NOT TWO. `fan` already subsumes `drive` — one partition IS one machine —
      so `run` builds the initial-of function out of the shape and a caller never spells :instance.
      `drive` stays public in .async for somebody who has already partitioned, one consumer per
      instance being the obvious case.
    - THE WART, ACCEPTED: fan's :done became a MAP keyed by instance, so a caller who named nothing
      finds their machine under nil. A vector would have said the same thing while making the caller
      guess whose entry was whose, and the instance is the one name `fan` has in hand.
    - `problems` IS OPT-IN AND `shape` DOES NOT RUN IT. Fewest-functions argued for a strict
      constructor and no `problems` at all, and it is wrong for one decisive reason: A SHAPE YOU
      CANNOT BUILD IS A SHAPE YOU CANNOT DRAW, and the whole argument for this library is that a
      half-finished machine is worth looking at. The `broken` and `trapped` fixtures would become
      unconstructible, which is the check on the idea.
    - RE-EXPORTS ARE DELEGATING defns AND NOT def ALIASES, measured rather than assumed — see
      :what-the-facade-taught, where an alias skipped its guard entirely. And they carry NO
      :malli/schema of their own: the contract belongs to the namespace that owns the function, one
      declaration and not two, and a copy at the facade could only drift. `run` is the one function
      the facade really adds, so it is the one that has a schema — which is also why the instrument
      count went to 31 and not to 39.
    - THE FACADE REQUIRES `check`, and that breaks the property :layering claimed for it: that an
      application shipping a working shape never loads a graph algorithm. Taken knowingly. It is a
      load-time cost paid by a require and never by a step, the checks and the drawing are the reason
      the library exists, and a caller who minds requires robertluo.state-graph.compile directly —
      the same symmetry the batteries have, where the facade is the convenience and the namespaces
      are the truth.
    - NESTING ADDED NOTHING HERE, 2026-09-01, and that is worth recording as a check on the
      surface: a machine inside a node is an OPTION ON `state`, so the facade is still ten
      functions. A feature that needs no new door is a feature that fitted.
    - WHAT WAS TURNED DOWN: a `fold` doing the whole reduction in one call. It gives strictly LESS
      than `compile` — a step goes in a transducer and a fold does not — while hiding the thing the
      README names as a feature."

   :the-output-is-a-transition-and-not-a-state
   "DECIDED 2026-09-01, by the author, and forced by :nothing-is-persisted-here. `run` puts a RESULT
    on :states and not a bare state: the :event, the :state it produced, whether it :fired, and
    :instance where there is one.
    - THE ARGUMENT IS THAT THE CALLER STORES NOW. A state does not say what caused it, and an event
      nobody handled produces a state EQUAL to the one before it — so from a stream of states alone
      no consumer can build the history this library has just declined to keep. A result reads back
      down with (map :state) whenever states are all somebody wants, and the other direction does
      not exist.
    - THE OBJECTION THAT KILLED A RICH RETURN FOR THE STEP DOES NOT APPLY TO A STREAM, which is why
      this is consistent with :how-the-step-says-a-thing-was-ignored rather than a reversal of it.
      That entry refused an outcome value because (reduce step init events) must answer STATES or the
      README's headline sentence dies. A STREAM IS NOT AN ACCUMULATOR: `pump` holds the state itself
      and what it PUTS is free to be richer. The reduction still answers states; only the stream
      carries results.
    - :fired IS THE HALF NOTHING ELSE CAN ANSWER, and it needs a lookup and not a comparison — an
      ignored event answers the state unchanged, and a fired self-loop whose handler answers {}
      answers a state `identical?` to the old one, already verified in
      :what-the-design-conversation-verified. Hence compile/admits?, the step's own lookup published,
      over ONE private `entry` that both it and the step call, so the two cannot drift.
    - HOW IT REACHES THE STREAM WITHOUT PUTTING A SHAPE UNDER async: a third injected function,
      `result`, of the state applied to, the event, and the state that came back, defaulting to
      (fn [_ _ state] state). That default is exactly what the layer put before, so every existing
      drive test is untouched — and this is the Context pattern and the global rule again, do not
      thread options through a layer we do not own, hand it a function that closes over them.
    - :instance IS DERIVED FROM THE STATE and not read off the event, so there is one source for it,
      and it is absent where a caller named nothing.
    - THE COST, said out loud: :states is no longer a stream of states, so a consumer who wants only
      states writes (map :state). That is the cheaper half of the trade, and it is paid by the
      consumer who needs less."

   :a-machine-can-nest-in-a-node
   "DECIDED AND BUILT 2026-09-01, at the author's asking, and it is the answer to `a state
    machine is for complex problems`: a node may carry {:machine <a shape>}, and while the
    parent sits there that child runs inside it. Nine states in one graph is about where one
    graph stops being readable; nesting keeps every machine the size a person can hold.
    - IT DOES NOT BREAK :a-handler-never-sees-the-state, and this is the whole reason it was
      cheap. That rule constrains HANDLERS. The step is state-dependent all over already —
      it looks the edge up by (:id state), it merges into the state, it writes :id and
      :instance afterwards — so A CHILD'S STEP BELONGS TO THE COMPILER, exactly as :id does.
      A handler still only ever sees the event.
    - INNER FIRST. The child gets every event before the node's own edges do, so the parent's
      edges are the ESCAPE. The consequence is the mechanism: THE CHILD'S OWN VOCABULARY
      DECIDES WHO HANDLES AN EVENT — :authorize is the payment's word and the order never
      sees it; :cancel is not, so it escapes at once. Nothing had to be declared for that.
    - A FINISHED CHILD STOPS COMPETING, and this is what makes nesting cost the design
      nothing. A final state admits nothing, so once the child is done every later event falls
      straight through to the parent. NO GUARDS, no done-event, no internal queue and no
      run-to-completion — v1's own constraints turned out to give correct hierarchical
      semantics rather than standing in their way.
    - :sub IS MACHINERY'S, like :id and :instance. Seeded when the node is entered, DROPPED
      when it is left — a merge keeps every key, so a child left behind would ride into a
      state that never declared it — and RESTARTED when the node is re-entered, entering being
      entering. A handler answering {:sub ...} is overwritten, and a state DECLARING :sub is a
      :reserved-declared fault.
    - A CHILD MUST BE ABLE TO START, checked at construction. Entering a node with a machine
      enters the child at its own initial with NO data, so a child whose first state insists on
      some could never begin: :machine-cannot-start, and it is REFERENTIAL, answerable from the
      parts, so a nesting that cannot begin is refused before it exists.
    - THE CHECKS RECURSE FOR FREE because a child is an ordinary shape and every structural
      check is about ONE graph. Faults are reported :within [<host node> ...], a PATH because
      nesting nests. And there is no cross-boundary subsumption question at all: the child's
      slice is written only by the child's step, so nothing a parent handler declares can
      touch it.
    - NESTING CANNOT BE CIRCULAR and needs no check to say so: a shape is an immutable value
      built out of already-built children, so none can contain itself.
    - THE COST, said out loud: THE ESCAPE IS UNCONDITIONAL. Nothing stops the parent leaving
      while the child is half done, because `only when the child has finished` is a GUARD and
      :v1-is-deterministic has none. Deciding when is the producer's job — the same answer v1
      gives to branching — and the child's state is on every result, so a producer can see what
      it needs. Turned down deliberately: making the parent's edges wait for a final child,
      which would have made ABORT inexpressible, and abort is the commoner need.
    - THE DOOR, NAMED AND NOT DESIGNED: a node could declare where to go when its child
      FINISHES — {:machine sh :done :shipped} — which is the statechart done-transition and
      needs no event queue here, being a deterministic continuation inside one step rather than
      an event. It is the same lean as :a-handler-causes-nothing (the state raises, not the
      handler). The bar is a real shape asking for it twice."

   :a-node-is-labelled-by-its-id
   "DECIDED 2026-09-02, at the author's asking — `should not each state just be represented by the
    :id?` — and the answer is yes, on this library's OWN argument for drawing at all.
    - THE ARGUMENT THAT SETTLES IT is in :what-the-graph-buys: `an unreachable state is obvious in
      a picture and INVISIBLE IN A MAP LITERAL`. That is entirely about STRUCTURE — and a schema is
      precisely the part of a shape a map literal DOES show. So the schema was the least useful
      thing in the label, and it was the only thing that did not scale.
    - MEASURED, on the first real consumer: ../coder's workflow builds its states by conj-ing a
      vocabulary forward, so agent/Brief is inlined into eight of them. Twelve labels, the longest
      1,183 CHARACTERS, and a dot source of 10,408. `dot -Tpng` printed `graph is too large for
      cairo-renderer bitmaps`, scaled, and then wrote a ZERO-BYTE FILE — a warning that looks
      survivable and is not. CHECK THE FILE AND NOT THE EXIT CODE. SVG rendered the same graph
      fine, which is what made the failure look like a graphviz quirk rather than a label problem.
    - AFTER: the same shape is 1,007 characters of dot and renders to a 120KB PNG. Labels are
      `fresh ▸`, `kept ◼` — the name and the markers, and nothing else.
    - WHAT WAS KEPT AND WHY: ▸ for initial, ◼ for final, ⊞ n states for a nesting node. All three
      are STRUCTURAL, which is the test this decision now applies to anything wanting into a label.
    - WHAT WAS NOT BUILT: an option to put the schema back. Nobody has asked for it, the shape is
      right there to read, and `problems` answers what the schemas IMPLY better than a picture of
      them ever did. An option is cheap to add the day somebody wants one."


   :nothing-is-persisted-here
   "DECIDED 2026-09-01, by the author. THIS LIBRARY STORES NOTHING: it outputs what happened, and
    what becomes of that is the caller's. It AMENDS THE README rather than merely contradicting it,
    because :source-of-truth would otherwise make this file the wrong one.
    - WHAT WENT: datahike left deps.edn, where it had been a dependency nothing used;
      robertluo.state-graph.store left :layering, never having been built; and the README's
      persistence feature now says what the library does instead. Its `audition` and `trace`
      sub-bullets STAYED, because those are still what the output is FOR.
    - WHAT IT COST, and it is the one thing this decision broke: an audit trail must know which event
      produced which state, and a stream of bare states cannot say. That is what forced
      :the-output-is-a-transition-and-not-a-state, which is this same decision seen from the output
      end.
    - AND WHAT IT DID NOT COST. :what-is-persisted argued that HISTORY and not the shape is what a
      store holds, and that argument is untouched — it is now advice for whoever writes the store,
      outside this library. Shape versioning stays out, along with the question it drags behind it.
    - THE `finally` RULE KEEPS ITS FORCE with no database in the tree; what it governs here is files
      and streams. A stream adds an obligation a store never had, though: a deref that never resolves
      hangs the suite rather than leaking a resource, so every deref in a stream test is BOUNDED."}

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
    all."

   "IS THE NODE-SIDE EXPOSURE NEEDED, OR IS THE EVENT-SIDE VIEW ENOUGH? What is left of
    :internal-visibility-is-declared-and-not-automatic after both halves were built on 2026-09-01.
    A view declared by the EVENT is least privilege by the handler's own word: a careless or shared
    handler declares {:sees [:map [:token :string]]} and is handed the token. The remedy is for the
    data owner to have the say — the node declares what it EXPOSES, the event what it NEEDS, and the
    check verifies the one is within the other — and it is ADDITIVE, default deny on both sides being
    exactly today's behaviour.
    WHY IT WAS NOT BUILT WITH THE REST: projection already bounds visibility by ABSENCE, which is the
    stronger guarantee and covers the case that matters most, a state that never held the secret
    being unable to leak it. Exposure only helps where a state MUST hold something a handler in the
    same machine must not read. Whether an agent workflow really has that shape is the question, and
    a real one asking for it is the bar.
    RETIRED, and kept here for one line only because the file's own rule is that an answered question
    left in the list gets asked again: `is projection taken before release` was answered by taking
    it, and `how is a bare [:map] handled` by measuring — it holds nothing but its :id, and that cost
    one under-declared fixture."

   "IS THE Context's :ignored STILL EARNING ITS PLACE? Raised 2026-09-01 by building the facade and
    deliberately not answered. Its stated job was that the store layer would replace it with one that
    records, and there is no store layer: the stream door reports a miss as :fired false, taken from
    compile/admits? and not from any callback. What is left for it is a caller who folds BY HAND and
    wants to hear about a miss — real, and possibly not worth a key in the Context. If it goes, that
    caller closes over admits? themselves, `index` and `admits?` both being public for exactly this.
    Nothing is blocked either way; it is three lines of surface, and the bar for removing it is a
    second reader asking what it is for."]

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
    AND THE FACADE LANDED 2026-09-01, which is the last layer: robertluo.state-graph, nine
    functions, one require. Two doors on one machine — (reduce (compile sh) (initial sh {}) events)
    and (run sh {} events) — and :states now carries a TRANSITION RESULT rather than a bare state,
    because the same day decided that this library stores nothing and the caller does. See
    :the-facade-is-a-vocabulary-and-two-doors, :the-caller-owns-the-lifecycle,
    :the-output-is-a-transition-and-not-a-state and :nothing-is-persisted-here.
    THE STORE IS NOT COMING. datahike is out of deps.edn and robertluo.state-graph.store is out of
    :layering; neither was ever built.
    AND A MACHINE MAY NEST IN A NODE, 2026-09-01, the last thing the author asked for and the
    answer to `a state machine is for complex problems`: {:machine <a shape>} on a state, the
    child taking every event first and the parent's edges being the escape. It needed no new
    rules — see :a-machine-can-nest-in-a-node — and no new facade function, being an option on
    `state`.
    AND INTERNAL VISIBILITY IS DECLARED, 2026-09-01, both halves in one go: A NODE HOLDS WHAT IT
    DECLARES — the merge is projected onto its keys on entry, which killed the `a merge cannot
    remove a key` limit — and AN EVENT MAY DECLARE {:sees <a map schema>}, whose handler is handed
    that projection and nothing else. check/views proves whether a state can provide what a handler
    asks to read, and it is `admits` again with no new machinery. See
    :internal-visibility-is-declared-and-not-automatic.
    73 tests, 202 assertions, green — 71 unit and 2 ^:integration, so THE COMMIT GATE IS A REAL
    GATE; clj-kondo clean; 35 public fns carry a :malli/schema and the instrument! count of exactly
    35 confirms it better than a grep can. The integration suite is DOWN to two, and that is the
    right direction: `check/dot` made the graphviz-source test need no file, so it moved into the
    fast loop, leaving only what needs a real clock and a real `dot`. The integration suite NEEDS GRAPHVIZ — see
    :graphviz-and-the-devenv.
    NOTHING IS UNBUILT. What is left is not a layer but a licence not taken: async/drive serialises
    always, and the concurrency `check/commuting` proves to be safe is a GAP with a reason, recorded
    in :two-events-in-flight-at-once.
    AND THERE IS A TUTORIAL, 2026-09-01, this component being a release candidate:
    notebook/tutorial.clj, a Clay notebook rendered by `clojure -X:notebook` to docs/tutorial.html,
    which is gitignored because it is derived. It works the facade through in order and ends with a
    NINE-STATE PUBLISHING PIPELINE — a review cycle, a retry self-loop, one event leaving three
    states — drawn and then run over a two-instance event log. Every diagram in it is the library's
    own drawing, rendered client-side, so reading the page needs no graphviz. See
    :what-the-tutorial-taught, which is where the first real CONSUMER of this API found things the
    suites could not."

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
      no :main-opts; the alias with the main-opts is :nrepl. It is `clojure -M:dev:nrepl` (and :dev
      is wanted, or the test path and kaocha are not on the classpath). THE README SAYS THIS
      CORRECTLY — checked 2026-09-01; this entry claimed otherwise and was itself the stale one.
    - DATAHIKE WAS A DEPENDENCY NOTHING USED, and it is now not a dependency: removed 2026-09-01
      with the store, see :nothing-is-persisted-here. Nothing in the tree requires it.
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
      supported way to copy or rebuild a graph.
    - viz-graph ANSWERS NOTHING USEFUL, added 2026-09-01 from its source and then from running it.
      It threads the dot string through a cond-> whose branches are (#(spit filename %)),
      dj/save! and dj/show! — so the value is spit's nil for :format :dot and a viewer's for the
      rest, and the SOURCE is only ever written out. The way to it as a value is to hand :filename
      a java.io.StringWriter, `spit` accepting any java.io.Writer; that is what check/dot does."

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

   :what-the-facade-taught
   "VERIFIED BY RUNNING on 2026-09-01, building robertluo.state-graph.
    - A def ALIAS BYPASSES malli INSTRUMENTATION, which is why every re-export is a delegating defn.
      mi/instrument! replaces the VAR's root binding, so a value captured by (def state shape/state)
      is the raw function for ever: handed a bad argument it answers happily where the var throws.
      Measured both ways, on the same call in the same session.
    - AND THE NEAR MISS THAT WOULD HAVE HIDDEN IT, which is the half worth keeping: the alias
      APPEARED guarded when called at its 2-arity, and only the 3-arity gave the game away. The
      reason is that a defn whose body calls ITSELF goes through the var, so the 2-arity's delegation
      landed in the instrumented wrapper. That is the same fact :what-the-handler-move-taught records
      from the other side, and testing only the 2-arity would have licensed aliases everywhere.
    - THE INSTRUMENT COUNT IS 31 — yesterday's 29 plus compile/admits? and the facade's `run`. It is
      also exactly the number of public fns carrying a :malli/schema, counted the independent way
      through ns-publics, so it still doubles as the check that none was forgotten. The facade's
      eight delegations carry none by design and are absent from both counts.
    - THE TWO DOORS AGREE, as a property rather than an example: for a generated shape and a
      generated event sequence, (map :state) off the stream results equals the states the reduction
      passes through. Worth more than any number of examples about the record's shape, and the only
      thing that can refute :the-caller-owns-the-lifecycle.
    - fan's :done RESOLVES {} WHERE NO EVENT EVER ARRIVED, which fell out of keying it by instance
      rather than being designed, and is right: there is no machine until an event names one, so
      there is nothing to report on. The initial state is not a transition and never appears on
      :states either.
    - THE FORMATTER AND THIS REPOSITORY DISAGREE about a prop/for-all body — a PostToolUse hook
      aligns it under the binding vector, where every existing suite indents it four spaces. The hook
      fires on the file-writing tools and not on a shell heredoc, which is how the existing style was
      restored."

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

   :what-the-tutorial-taught
   "VERIFIED BY RUNNING on 2026-09-01, writing notebook/tutorial.clj — the first real CONSUMER of
    this API rather than another test of it, which is why it found things the suites could not.
    - kind/graphviz TAKES A VECTOR AND RENDERS IN THE BROWSER. Read from clay 2.0.22's own source
      before being used: (kind/graphviz [dot-string]) — the value's FIRST element is the source —
      and clay's item/graphviz interpolates it into a JS template literal that viz.js renders
      client-side. So a page full of this library's drawings needs NO graphviz installed to read,
      which is a better answer than a PNG and was not obvious. The one hazard is the template
      literal: a backtick in a node label would break it, and ours are schema forms, so none.
    - THE DOT SOURCE WAS ONLY REACHABLE THROUGH A FILE, which is the GAP the tutorial found and
      the author closed the same day. ubergraph's viz-graph THREADS the dot string through a cond->,
      and the :dot branch is (#(spit filename %)) — whose value is nil. So check/draw! with
      :format :dot writes the source and ANSWERS NOTHING, and the notebook's first version wrote a
      temp file and slurped it back, with a finally to release it.
      CLOSED by check/dot, and the mechanism is worth knowing because it needs NO file at all:
      `spit` calls clojure.java.io/writer on what it is handed, and that ACCEPTS a java.io.Writer, so
      a StringWriter catches the source in memory. Verified before it was used. No finally either —
      spit closes the writer it made, and closing a StringWriter is a no-op that keeps the buffer.
      IT PAID TWICE: the notebook's helper went from eleven lines to four, and the graphviz-source
      test stopped needing a file, so it left the integration suite for the fast loop.
    - `run` GIVES EVERY MACHINE THE SAME STARTING DATA, which async/fan does not — fan takes a
      function of the instance. Found by trying to write a pipeline whose initial state carried a
      per-manuscript title, and worked around by moving the title onto the event that STARTS the
      machine, which is better modelling anyway: a draft is empty and the submission names it. Worth
      knowing before somebody meets it as a surprise; whether `run` should accept a function is the
      author's call.
    - A MERGE CANNOT REMOVE A KEY, AND THE TUTORIAL SHOWS IT RATHER THAN SAYING IT. The published
      manuscript still carries the :notes from a review round three transitions earlier, visibly, in
      the :done map. A limit is more convincing as an output than as a bullet.
    - THE CROSS-INSTANCE INTERLEAVING IS VISIBLE AND IS NOT DETERMINISTIC. In the two-instance log
      m-2's :withdraw overtook m-1's :confirm, which is the parallelism working — so the notebook
      says the ROW ORDER is not promised and shows the per-instance paths beside it, which are. A
      tutorial that asserted the interleaved order would flake.
    - CLAY'S DEFAULTS, read from clay-default.edn: :base-target-path docs, :format [:html],
      :show/:browse true. `:render true` implies show, serve, browse and live-reload all false,
      which is what makes `clojure -X:notebook` headless. :exec-fn scicloj.clay.v2.api/make! with
      :exec-args is why the alias needs no build namespace and no extra file."

   :what-the-parts-library-showed
   "MEASURED BY RUNNING on 2026-09-01, checking the author's `most parts are shared, only the
    assembly differs` against the code instead of agreeing with it.
    - SHARING IS FREE AND COMPLETE. A vector of states and a vector of events, handlers and all,
      assemble into two different machines by concat plus different transitions, and both check
      clean. ONE CHILD SHAPE NESTS INTO TWO UNRELATED PARENTS with nothing to alias — a shape is
      an immutable value — and a reduction through either lands the child correctly.
    - BUT A SHARED CATALOGUE MUST BE SELECTED FROM AND NOT SPLATTED IN, and this bites on the
      first assembly that uses fewer events than the catalogue holds: :unused-event REFUSES the
      shape, seen as [{:problem :unused-event :id :retry}]. The check is right — an event no
      transition fires IS dead code in that machine — so a parts library wants to be a MAP KEYED
      BY ID that each assembly selects from, and never a vector to concat wholesale. Whoever
      builds the agent workflows should know that on day one rather than day three."

   :what-visibility-taught
   "VERIFIED BY RUNNING on 2026-09-01, building both halves of
    :internal-visibility-is-declared-and-not-automatic.
    - THE BREAKING CHANGE COST ONE TEST, and measuring it before recommending it is what settled a
      three-way design question that argument had not. Projecting the merge onto a node's declared
      keys broke exactly one of 68 tests: an async fixture whose state was a bare [:map] while its
      handler set :mark. Two others wanted the same correction on inspection — the `form` fixture
      wrote :name and :email into a state that declared neither, so the runtime had been quietly
      undoing what the fixture existed to demonstrate. THE GENERATIVE FIXTURES NEEDED NOTHING, which
      was the surprise: gen-shape's handlers all answer {}, so there was never anything to drop.
    - `views` IS `admits` AGAIN, with the view as TARGET and the source node's schema as PRODUCED,
      and the four verdicts were checked before the check was written: a state with the key and more
      is :yes, without it :no, with it at the wrong type :no, and WITH IT ONLY OPTIONALLY also :no —
      which is the one that matters, a view a handler is handed being unable to rest on a maybe.
      Two structural checks now come free off one subsumption function, which is the argument for
      having written it at all.
    - AND THE SOUNDNESS DEPENDENCY RAN THE OTHER WAY FROM THE DESIGN, found by asking what the check
      would answer before building it: the READ half's check is only sound because the HOLD half
      exists. A node's schema used to be a lower bound on what it held, so a key could arrive from
      three transitions back and :no proved nothing. Reading was the interesting half and holding was
      the one that had to land first.
    - A VIEW COSTS NOTHING WHERE IT IS NOT DECLARED, which is what let this land in a release
      candidate: a handler with no :sees keeps its single argument, so the arity is decided by the
      event definition and nothing written before this learns that views exist.
    - AND IT BROKE A SOUNDNESS CLAIM TWO ENTRIES AWAY, which is the finding worth most here: adding
      a read made check/commutes UNSOUND, because it compared WRITE sets only. A pair whose writes
      are {:total} and {:n} is disjoint while one of them READS :n, and the two orders answer
      :total 2 and :total 18 — an order-dependent flake the check would have licensed. The
      condition is now Bernstein's, and the lesson is the one this project keeps relearning: a new
      capability is not local, and the place to look is whatever OTHER check reasoned about what
      handlers could touch. Found by asking `does this actually solve the problem it was built
      for`, not by a test that already existed."

   :what-nesting-taught
   "VERIFIED BY RUNNING on 2026-09-01, building {:machine <a shape>}.
    - THE SUBSUMPTION CHECK HAD TO LEARN ABOUT :sub, and until it did, nesting was broken in a
      way only the check could see: `produced` composes the source's schema, the declared :out
      and then the target's :id, and a nesting target's enter-schema REQUIRES :sub — which no
      handler may write and the step assocs on entry. So every edge into a nested node was
      condemned :target-refuses. Found by running the checks on the first nested shape ever
      built, one minute after it worked. THE LESSON IS THE ONE THE ENTRY ALREADY STATED: what
      the check composes must be what compile composes, and every key the MACHINERY writes has
      to appear in both places or the check condemns correct shapes.
    - GRAPHVIZ CLUSTERS ARE NOT REACHABLE THROUGH UBERGRAPH, so a child is not drawn inside its
      parent. viz-graph builds its own dorothy element list out of nodes and edges, with no hook
      for a subgraph; the alternatives were generating the dot ourselves — which means copying
      ubergraph's private dotid and sanitize-attrs — or rewriting the child's dot to prefix
      every node id, which is a small compiler and a fragile one. What was done instead: the
      parent MARKS the node (⊞ n states) and the child is asked for its own picture. Two
      pictures, and the parent says where to look.
    - THE ORDERING OF A NAMESPACE MATTERS MORE THAN IT LOOKS. `Shape` had to move ABOVE StateDef
      once a node could hold one — a [:ref #'Shape] would have worked and a plain reference is
      better — and the referential nesting check needs `enter-schema` and `initial-id`, which
      live in the reading section BELOW it. Declared rather than moved, and deliberately not
      reimplemented: what the check asks has to be what runs.
    - THE GENERATIVE PROPERTY EXTENDED WITHOUT AN ARGUMENT, which is a good sign for the design:
      nest one generated shape into a node of another and assert that the parent lands in one of
      ITS nodes and the child in one of the CHILD'S. The two share an event vocabulary — gen-shape
      names events :e0..:e3 — so the child shadows the parent constantly, which is the
      interesting half rather than an accident.
    - AND CLOJURE'S OWN DEREFABLES PROVED THE Context COMPOSES ACROSS THE BOUNDARY, with no
      manifold on the classpath of the test: a child handler answering a `delay` is dereferenced
      by the synchronous `then` through two levels of nesting. Same trick as
      :what-the-handler-move-taught, one layer deeper."

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
    - manifold 0.4.3 — the async default, and nothing below that layer requires it. Its Deferred
      is a clojure.lang.IDeref, which is what lets the pure core deref one without depending on
      manifold at all; d/chain takes a plain value as happily as a deferred; and s/connect is
      ASYNCHRONOUS, which cost a lost state once — see :what-the-async-layer-taught. It drags in
      slf4j-api with no binding, hence three NOP lines on stderr.
    - NO DATABASE. datahike was here for history and is gone: this library stores nothing and the
      caller stores what it outputs. See :nothing-is-persisted-here.
    - malli 0.20.1 — the shapes of states, events and every function signature. See the
      :reload-all rule; it is the one dependency that punishes a careless REPL.
    - test.check 1.1.1 — it is in :deps and not :dev on purpose: generative tests are the unit
      suite here, not an extra.
    - clay 2.0.22 — the tutorial, and it is in the :notebook ALIAS and not in :deps: a library does
      not depend on the thing that documents it. It drags kindly in, which is where kind/graphviz
      comes from."

   :from-the-sibling-project
   "smart-boundary/AGENTS.md, in GIT HISTORY, was the sibling component — the same author's larger
    project, removed 2026-09-02 — and its :project-knowledge is still worth reading
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
  {:entry {:action "clojure -M:dev:test integration — the gate before a commit: real files on
    disk, real streams, real clocks, and graphviz actually shelled out to. Kaocha randomizes order, so a test depending on
    another having run first is a bug in the test. Every store opened is released in a `finally`,
    and a suite that passes and then hangs is a store left open"}
   :on {:passes {:target :review}
        :fails  {:target :implement
                 :guard "Inspect the failure; fix the code or the test"}}}

  :review
  {:entry {:action "Verify the hard constraints: (1) nREPL was the only evaluator (2) no bare
    try/catch — a `finally` for release is not one (3) malli shapes all data AND every function
    (4) tests are clojure.test in test/, one file per namespace, split unit and ^:integration
    (5) dependencies point down only (6) the pure core has no manifold in it, and no namespace
    names one above it even in a comment (7) a facade re-export is a delegating defn and never a
    def alias, or malli stops guarding it"}
   :on {:all-checkout {:target :retrospect}
        :issue-found  {:target :implement
                       :guard "Fix the identified issue"}}}

  :retrospect
  {:entry {:action "Reflect on the session:
    - What went wrong? What assumption was incorrect?
    - What was LEARNED about ubergraph, manifold or malli that a docstring would not
      have told you? Record it in :project-knowledge in the past tense, with what was seen
    - Close any :open-questions the work answered; add the ones it raised
    - Add a :global-rule only for a mistake made more than once"}
   :on {:done {:target :complete}}}

  :complete
  {:final true}}}
