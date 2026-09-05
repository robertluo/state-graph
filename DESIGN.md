# robertluo.state-graph — the design record

THE ARGUMENTS, IN FULL. This file is the reasoning behind the library: why each decision was made,
what was turned down and why, and what was learned by running it. It is READ ON DEMAND and is not
loaded into a session automatically.

WHICH FILE WINS. README.md is the specification. AGENTS.md is the operational authority — the rules,
the layering, the constraints, the workflow, and one paragraph per decision. THIS FILE is the
argument behind each of those paragraphs, and it never contradicts them: where it appears to,
AGENTS.md is right about WHAT was decided and this file is right about WHY, and the disagreement is
a bug to fix in one of the two.

HOW TO USE IT. Every heading here matches a key in AGENTS.md's `:design` or `:project-knowledge`, so
a pointer like `see DESIGN.md :a-guard-is-a-schema-over-the-event` is a grep away. Read the entry
BEFORE reopening a question it settled, and before proposing anything it records as turned down.

Extracted from AGENTS.md 2026-09-04, when that file was trimmed. Every word below was in it.


## Why it exists

SAID BY THE AUTHOR 2026-09-01: THIS LIBRARY EXISTS FOR THE SIBLING COMPONENT'S AGENT WORKFLOWS.
smart-boundary needs a different concrete workflow per scenario, and the two ways to write that
in ordinary code are both bad — a lot of long, nearly identical code, or a configuration format
pretending to unify them at the surface. A state machine is the third way.
- COMPLEX IS NOT DIFFICULT. What those workflows need is GLUE, and glue is what a machine
  replaces. The argument is about VOLUME rather than cleverness, which is why it convinces.
- MACHINES COMPOSE, and nesting made that easier still. Across scenarios most parts are the SAME
  and only the ASSEMBLY differs: a state, an event with its handler, and a whole shape are all
  plain values, so an assembly is (apply shape (concat parts wiring)) and one child nests into
  two parents with nothing to alias. MEASURED — see :what-the-parts-library-showed for the one
  edge that bites a parts library on day one.
- A WORKFLOW MOSTLY IN DATA IS STORABLE AND DRAWABLE, which is what the agent case needs: a
  person has to be able to SEE the workflow an agent is running.
- AUDITABILITY AND STATIC CHECKING ARE THE POSITION. For an agent workflow the two questions
  that matter are `what happened` and `could this ever have worked`; the transition results
  answer the first and `problems` answers the second, before anything runs.
- THE PARALLELISM CLAIM, SAID CAREFULLY. `The first FSM that supports parallelism` is NOT the
  claim to make in public: Harel statecharts have had orthogonal regions since 1987, every
  workflow engine runs steps at once, and :parallel-is-across-instances puts orthogonal regions
  deliberately out of scope — so it argues against this project's own design. What is defensible
  is narrower and stronger. ONE CALL RUNS THOUSANDS OF INSTANCES AT ONCE, each serialised, with
  real backpressure. A HANDLER MAY ANSWER A DEFERRED, so an instance waiting on a model call
  holds no thread, which is the property that decides whether an agent workflow scales at all.
  And CONCURRENCY CAN BE PROVEN: check/confluence answers statically which pending pairs may be
  applied in order of completion, and no other FSM library appears to answer that at all.

## Source of truth

README.md is the specification and this file is its reading. WHERE THE TWO DISAGREE THE README
WINS and this file is wrong — say so and fix it. The README was rewritten 2026-09-01 to the
finished shape, every example in it run against the code before it was written down, so PROPOSED
is not a category here: everything is built, and a divergence is a bug in one of the two files.
THE README ALSO CARRIES THE LIMITS, deliberately — no state-dependent update without a view, no
internal events, no persistence, no orthogonal regions — so a user meets one of those in the
README and not in a surprise.


---

# Design decisions


## :the-shape-is-a-graph

Three definitions and no more, straight from the README:
- A STATE is a node, shaped by a malli schema, VALIDATED ON ENTER. Entering is the only moment
  a state's schema can be checked, and it is the moment a bad transition becomes visible.
- An EVENT is shaped by a malli schema too. An event is a value, not a keyword with baggage.
- A TRANSITION is an edge, keyed BY EVENT ONLY — not by (state, event) — carrying a function
  that handles the event and whose RETURN VALUE IS APPLIED TO the state. `Applied to`, not
  `is`: what a handler answers is a change, and the state is what the change lands on.
Consequence, and it decides the ubergraph call: two different events may join the same pair of
states, so the shape is a MULTI-digraph and an edge needs an attribute map. A plain digraph
would silently keep one of the two.
:initial IS A NODE ATTRIBUTE, exactly one per shape — the reachability check needs a root and
so does the drawing — while the starting DATA stays an argument to the reduction. A node and
its value are different things and only the first belongs in the graph.


## :what-the-graph-buys

The whole argument for not writing another FSM library. A shape that is a graph can be:
- DRAWN, so a person can SEE the machine they described rather than read it;
- CHECKED STATICALLY, which is the part that pays: a state with no in-edge is unreachable, a
  state with no out-edge and no :final is a dead end, an event no transition mentions is dead
  code, and a transition whose handler cannot produce a value the target's schema admits is a
  bug findable WITHOUT RUNNING ANYTHING. That last is what malli on the nodes is for, and it is
  the check worth building first because no other FSM library has it;
- STORED, so the machine's own history is queryable in the same shape as its definition.


## :compilation-and-lifecycle

(compile shape) -> a pure function of a state and an event answering the next state. The
lifecycle of an instance is then (reduce step initial events), and that is the whole runtime:
no object, no atom, no protocol. Everything else in this library is a way of getting events
into that reduction or getting RESULTS out of it — which is why a stream is a layer above and
not the core, and why `run` is a caller this library ships rather than a second kind of
machine. See :the-caller-owns-the-lifecycle.


## :the-defaults-are-batteries

Async is a DEFAULT. Someone with their own stream library must be able to use the compiled
function directly and lose nothing, so it may not take a shape as an argument — and it does
not: it is handed a step, a way to make a first state, a way to make an output value and a
licence, all as VALUES.
- THE HONEST STATEMENT OF THE RULE, revised 2026-09-01: robertluo.state-graph requires .async,
  so requiring the facade loads manifold. What the rule protects survives one level down —
  robertluo.state-graph.compile requires no manifold and never will. `check` costs the facade
  the same way for the same reason.


## :cover-the-graph-by-running-it

The author of ../coder, 2026-09-05, of a hand-written test that drove one branch of an agent
workflow with plain functions in place of the models: *"the ability of fully cover the graph
using this technique is very important and general, I think maybe it worth a seat in `src`:
useful not only for its own test, but also for users."* And then the question that decided the
shape of it: *"we can substitute not only LLM functions, but any functions in system, I wonder
if we can make it a thorough testing for a machine: our check does its work statically, while
this can check in the runtime"* — clarified: **runtime means by actual executing**.

### The technique is one sentence

A shape is a function of its env, so whatever a report reaches for — a model, a socket, a
clock, a budget — arrived as a **value**, and an ordinary function goes in its place. That is
not a testing trick bolted on; it is `:the-shape-is-the-only-integration-point` cashed. What was
missing was not the ability to do it but the **accounting** for having done it.

### What the accounting found

Some transitions cannot be taken by any driver, however you fake the world:

| why | meaning |
| --- | --- |
| `:no-report` | the event carries no `:report` — only the world supplies it |
| `:join-order` | the state is a proven `:join`, so the crank takes its events at once in ONE order, and the other orderings' halfway states are never entered |
| `:unvisited-state` | nothing entered the state this edge leaves from; the reason is upstream |

That middle row is the one worth the entry. **Exactly the property that makes a join safe is
what makes half its diamond undrivable.** `confluence` proves the order cannot be observed, so
the crank picks one — and a coverage report that did not know this would call a correct machine
half-tested, for ever, with no way to fix it. Worse, a report that *did* let you fix it would be
inviting you to depend on which order the crank picked, which is the one thing the library
promises you may not observe. The explore test asserts that one of the two orderings is skipped
and never which, for that reason.

Subtract the three and the residue is `:gaps` — an edge a driver **could** have taken and your
alternatives never produced a payload for. That is the only number that means you missed
something, and driving it to zero is the whole exercise.

### Measured, on ../coder's machine

Fourteen states, twenty-two transitions, three agents and a REPL replaced by constants:

| | |
| --- | --- |
| covered | 19 of 22 |
| runs | 24 — the *product* of the alternatives |
| gaps | none |
| uncovered | one `:no-report` (`:amend`), one `:join-order`, one `:unvisited-state` |

The first attempt covered 14 and reported three `:gaps`, all of them the way back from a
failure. The fix was to vary `:rounds` — **a plain number in the env, not a function** — which
is the author's "any functions in system" arriving at its strongest form: a budget that is an
edge is covered by substituting a constant, where before it would have taken nine real laps.

### One fixed env per run

A fake that answers differently on its third call reaches paths a fixed one cannot — a
*recovery*, tests failing and then passing. It was turned down as the default: it makes the run
count unpredictable and the failure hard to read, and **transition** coverage does not need it,
since to take an edge you need an env that produces its payload rather than a particular
history. Reach for a stateful fake when the thing under test is a sequence. `covering` is for a
graph.

### Where it sits, and what it is not

A sibling of `drive`, above it in the arrow, requiring it and `check` and `shape`. It is **not**
in `check`, which never touches the runtime path — an application shipping a working shape must
not load a graph algorithm, and this one *runs the machine*. It is **not** on the facade, for
the reason `coverage`, `confluence`, `commuting`, `subsumption` and `views` are not.

**A throw propagates and is not collected.** Driving already enforces the event's schema, the
guards, the target's schema and a loud miss, so anything that goes wrong is a defect in the
machine; stopping on it with the exception intact beats a tally. There is no `try` in this
library's src and this did not become the first one.

**One machine, the outermost**, for the reason `:a-published-check-answers-about-the-machine`
gives for `reachable`, `traps` and `dead-ends`: two machines may name a state `:done`, and a
report keyed by state id has nowhere to say which it meant. A child's transitions are counted
under `:nested` by `:within` and are not scored.

**A step cap, where `drive` has none.** `drive` needs no counter because the stopping rule is an
edge — but exploration is exactly where you find out that yours is not, and hanging the suite is
a poor way to report an infinite loop.

**A completion transition is scored too**, 2026-09-05, and it had to be once one could branch.
While a `:done` was a single unconditional target there was nothing to cover — the edge was taken
exactly when its state was entered — but a `:done` keyed by the child's final state is a FORK, and
a fork no run took is precisely what this exists to name. Leaving it out would have been the
quieter version of the mistake that prompted the whole change: a tool blind to a branch tempts you
to weaken the test until it matches the tool.

**It fires no event, so it is reconstructed.** `:a-state-may-say-where-it-goes-when-it-completes`
says an auditor holding the shape can do exactly this, the intermediate hops being a pure function
of the shape and the state. A row says where the whole machine ENDED UP, so a host it is no longer
sitting in has completed; WHICH branch is read off the target. Exact wherever the branches go
different places, which is what branching is for — two outcomes completing to ONE target are a
merge this counts both of, the shape having made them indistinguishable in a history.

**And an edge may land somewhere that carries on**, with no child in it at all. `:to` in a row is
where the machine ended up and not where the edge pointed, so `travelled` walks the declared
targets and takes the one whose entry-fired chain reaches it. That was a LATENT inaccuracy before
outcomes existed — an edge into a state that completes on entry scored as uncovered — and no
fixture had one.

**A completion is never `:no-report`**, and the ordering of `why` says so: that reason is about an
event only the WORLD can supply, and ARRIVING is not something anybody supplies. Asked as
membership in the completion set rather than by the source state, a nesting node's own edges being
its escape and perfectly ordinary events.


## :two-kinds-of-check-and-two-places-for-them

shape/problems is REFERENTIAL — answerable from the PARTS alone, so it runs inside the
constructor and a bad shape never exists. check/problems is STRUCTURAL — it needs the built
graph, so it is a separate namespace and opt-in.
- WHY A NAMESPACE AND NOT MORE OF shape: nothing in `check` is on the runtime path. compile
  does not require it, and an application shipping a working shape never loads a graph
  algorithm. The checks are for the person WRITING the machine.
- THE DRAWING IS IN THERE TOO, and it belongs: an unreachable state is obvious in a picture and
  invisible in a map literal. Same question, different means.
- REACHABILITY IS A TRAVERSAL FROM THE ROOT, not `has no in-edge`, and the difference is not
  academic: two states that reach only each other both have in-edges and are both unreachable.
  The suite has exactly that island in it, because the weaker check passes it.
- WHERE A CHECK LIVES IS DECIDED BY WHEN IT MUST ANSWER and not by what it resembles — which is
  why `disjoint` sits in shape beside `admits`'s sibling in check. See :what-guards-taught.
- THE WHOLE FAULT VOCABULARY, since it is otherwise spread over a dozen entries. A fault is a map
  carrying :problem, the id it is about, :within [<host node> ...] where it is nested, and a
  :witness where something could construct one.
  REFERENTIAL, refused by the constructor: :unused-event, :reserved-declared (a state declaring
  :id, :instance or :sub), :machine-cannot-start, :ambiguous (guards not provably disjoint),
  :done-cycle, :done-with-edges.
  STRUCTURAL, reported by check/problems: :unreachable, :dead-end, :trap, :target-refuses,
  :view-unavailable, :yield-unavailable.
  PUBLISHED AND NEVER FAULTED, being coverage rather than fault: `subsumption`, `views`,
  `coverage`, `confluence`, `commuting` and `laws`.


## :a-partial-subsumption-checker

`admits` answers :yes, :no or :unknown, and IT NEVER LIES. Malli has no subsumption — m/validate
answers about a VALUE, and nothing asks whether schema A is admitted by schema B — so it is
written here, structurally over :map entries.
- WHAT IT CAN PROVE, each decidable rather than heuristic: a REQUIRED key the produced value
  may not have (the common bug by a distance, and it covers `optional where the target insists`
  too); a value whose TYPE cannot be the wanted one, over seven primitives verified PAIRWISE
  disjoint rather than assumed, :int and :double included; and a [:= v] or an [:enum ...], where
  the values are finite and can simply be TRIED.
- :unknown IS AN ANSWER AND NOT A FAILURE, and `problems` reports only the PROVEN faults. A
  checker that cries about what it could not work out is a checker people turn off.
  `subsumption` publishes every verdict, :unknown and :undeclared included, so the check's own
  COVERAGE is readable — better than a checker that pretends to be total.
- WHAT IS CHECKED IS WHAT RUNS: `produced` composes the schema in the order compile composes the
  value — the source's own schema, the declared :out merged over it, the target's :id assoc'd
  last. If those two ever disagree the check is worthless, so they are written to be read side
  by side. `continued`, `accepted` and `yields` are the same discipline for the other crossings.
- AN EDGE WITH NO :out IS :undeclared AND NOT A FAULT. That declaration is what the whole check
  is FOR; without it there is nothing to say about a closure.
- SOUNDNESS IS TESTED BY GENERATION, a genuinely independent second opinion: where `admits`
  says :yes, values generated from the produced schema must all validate against the target.
  That direction is the one worth paying for — a checker saying :no where it should say :unknown
  merely nags, one saying :yes where it should say :no HIDES A BUG.


## :the-first-target

The first target was THE SPINE — shape and compile, ending in a working (reduce step initial
events) — and deliberately NOT the static checks, though they are the differentiator. The
argument is worth keeping for the next such decision: a check written over a shape that nothing
has ever run is a check over a shape that is probably wrong. `compile` is the cheapest thing
that can say whether the shape is expressive enough, and the checks cost almost nothing once
the shape stands.


## :a-shape-is-code

DECIDED 2026-08-30, by the author, and it decides more than it looks like it does: A STATE
MACHINE SHAPE IS CODE. The README's `pure clojure data with convinient functions as
constructors` means you WRITE it as data, not that it ROUND-TRIPS as data. It is built at
namespace load, its handlers are real closures, its schemas are compiled once.
- WHAT THAT KILLS: every argument from EDN, from =, from storability. Those were the only
  objections to the shape BEING an Ubergraph, so the shape is an Ubergraph — nodes carrying
  their schema as attributes, the multi-digraph carrying two events between one pair of states,
  and the checks and the drawing reading it directly with no parallel map to keep in sync.
- AND IT SETTLES PERSISTENCE without a separate argument: a shape that is code is not something
  a database reloads a machine FROM, so what is stored is HISTORY. Shape versioning is out of
  v1. It is also what licenses a COMBINE to be a closure — see :a-combine-is-how-a-patch-lands.


## :a-state-has-an-id

A state is a MAP with an identity field, :id — one word, and the same word in both places it is
needed: the NODE in the graph is the :id, and the runtime value carries :id saying which node it
is in. FORCED as well as chosen: the compiled step is (fn [state event] state') and has to know
whose out-edges to search, so the identity cannot live only in the graph. An event is a map with
:id for the same reason, and the compiler matches an edge on it.
- A node's own schema describes the REST of the map. What is validated on enter is the DERIVED
  (mu/merge [:map [:id [:= <node>]]] <node schema>), never written by hand — so `validate on
  enter` also checks that the machine landed where it thought it did.
- THE EDGE ALWAYS WINS. The compiler assocs the target's :id AFTER the merge, so a handler
  cannot move the machine sideways past the edge meant to decide the target. Identity is the
  shape's to say, and since 2026-09-03 a handler that TRIES is refused rather than silently
  overwritten — see :an-event-is-the-only-way-a-transition-happens.


## :a-handler-never-sees-the-state

DECIDED 2026-08-30, by the author, and it is the sharpest decision here: a handler takes THE
EVENT ALONE — (handler event) — or (handler event seen) where the event declares a {:sees} view.
Never the state itself, and never what was not declared.
- THE REASON IS DECOUPLING: one handler serves many events and many source states, and states
  and events then evolve independently. Reuse is the visible payoff.
- THE BIGGER PAYOFF IS THE CHECK. A handler with no state in it is a COMPLETE malli function on
  its own — [:=> [:cat <the event's schema> <the view>] <the event's :out>] — every half coming
  off the event definition and nothing from the graph. So a handler is instrumentable as an
  ordinary function, and :out stops being an annotation nobody can verify and becomes a claim
  TESTABLE generatively, with no state and no machine anywhere near it. (handler state event)
  would have needed the source node's schema in that signature, welding the handler to one node.
- WHAT IT DOES NOT FORBID: THE STEP may depend on the state as much as it likes, and does — the
  edge lookup, the merge, :id and :instance and :sub written afterwards. So a NESTED machine,
  whose child step needs the child's current state, sits inside the COMPILER and not inside a
  handler. See :a-machine-can-nest-in-a-node.
- THE COST, ACCEPTED AND SAID OUT LOUD: NO STATE-DEPENDENT UPDATE by default. A counter cannot
  count from the event alone. Two doors out, and the third was refused:
  A VIEW ({:sees}) lets :total after :add-item see the old total, the dependence then being
  visible in the shape rather than hidden in a closure. See
  :internal-visibility-is-declared-and-not-automatic.
  A COMBINE lets the NODE say how a key lands, so the handler still answers from the event
  alone. See :a-combine-is-how-a-patch-lands.
  WHAT STAYS REFUSED IS ACCUMULATION POLICY — a combining key `:messages by conj` — and the
  reason is worth more than the door was: A COMBINE THAT ONLY GROWS IS A MECHANISM WITH NO
  POLICY. What a task wants is the last n, or a summary, or one field from three steps back,
  and in the domain this library was built for THE PILE IS THE COST, context being metered. The
  accumulation question was the wrong question; who may SEE what is the right one.


## :internal-visibility-is-declared-and-not-automatic

THE QUESTION, PUT BY THE AUTHOR 2026-09-01: from OUTSIDE, an observer sees every transition. From
INSIDE, can a handler get at information? And the constraint that decides any answer: IT IS THE
CONSTRUCTOR OF THE MACHINE WHO DECIDES, never the library automatically, because too broad a
data visibility from the inside brings security problems easily. BOTH HALVES ARE BUILT.
- THE READ HALF: A VIEW DECLARED ON THE EVENT. (event id schema handler out {:sees <a map
  schema>}), and the step passes the projection as a SECOND argument — (handler event seen) —
  where a view is declared and (handler event) where none is, so nothing existing changed.
  DECLARED ON THE EVENT AND NOT ON THE NODE, because that keeps the original reason intact: the
  handler names what it needs BY SHAPE rather than by node, so it stays reusable across every
  state that satisfies the view. That is STRONGER reuse than `sees nothing`, not weaker.
- THE HOLD HALF: PROJECT AT THE DOOR. A node holds exactly what it declares — the merged value
  is projected onto the keys of its enter-schema on entry. VISIBILITY IS THEN BOUNDED BY ABSENCE
  rather than by permission, which is stronger than any read rule, and it dissolved the `a merge
  cannot remove a key` limit at the same time. It is also the answer to `or another state`: a
  state seeing another state's data IS the merge, and under projection it is exactly what the
  node's schema says. One mechanism, both halves.
- THE CHECK COSTS NOTHING, which is the strongest argument for this shape. `check/views` is
  `admits` with the view as TARGET and the source state's schema as PRODUCED. A state carrying
  the key and more is :yes; without it :no; wrong type :no; and A STATE THAT ONLY OPTIONALLY HAS
  IT ALSO :no, which is right — a view that must be there cannot rest on a maybe.
- AND THE ORDER WAS FORCED, which the design did not see: THE VIEW CHECK IS ONLY SOUND UNDER
  PROJECTION. While a node's schema was a LOWER BOUND on what it held, a key could arrive from
  three transitions back, so `admits` answering :no proved nothing and `problems` would have
  condemned shapes that run. Holding had to land before reading; the two halves hold each other
  up. See :what-visibility-taught, where the same dependency bit a third time.
- PROJECTION WAS BREAKING AND COST EXACTLY ONE TEST, measured rather than argued, which is what
  closed the three-way question of whether it should be opt-in per node, shape-wide, or a
  separate :keeps declaration. The two cheaper designs existed only to avoid a cost that turned
  out not to be there, so neither was built. A BARE [:map] now holds nothing but its :id.
- WHAT IS STILL NOT BUILT is the node-side EXPOSURE — the node declaring what it exposes, the
  event what it needs, the check verifying need is within exposure — which is the half carrying
  the security property against a careless handler that declares {:sees [:map [:token :string]]}
  and gets it. It is ADDITIVE, default deny on both sides being today's behaviour, and it waits
  for a real shape to ask. See :open-questions.


## :a-shape-has-a-derived-id

THE AUTHOR'S, 2026-09-04, and it came out of the transcript question rather than from anything
in this library: `to make sure the transcript log file correspond to a FSM, we may need a stable
id for the FSM.` A transcript row that cannot say which machine produced it is a row nobody can
audit, so yes — and the cheap answer is dead on arrival.

- `(hash shape)` IS NOT IT, MEASURED. Two structurally identical shapes, built separately in one
  process, are neither `=` nor equal-hashed: their handlers are distinct closures and their
  schemas distinct compiled objects. So it changes on every namespace load, and a transcript
  written yesterday would match nothing today. :ubergraph-0-9-0 records that an ubergraph IS `=`
  and IS EDN, and that is true — for a graph whose attributes are VALUES. :a-shape-is-code
  guarantees ours are not, and that is the whole of why this needed building.

- `canonical` IS THE ORDERED, READABLE FORM and `fingerprint` is SHA-256 over its printed
  representation, as 64 hex characters. Everything that is DATA goes in: node ids, the `m/form`
  of every schema, `:initial` and `:final`, every edge as `[from event to]` with its guard,
  `:out`, `:sees` and `:reads`, and every completion edge with its `:yield`. ORDERED BY PRINTED
  FORM, because ubergraph keeps nodes and out-edges in SETS and an id that depended on iteration
  order would not be one.

- A NESTED MACHINE IS ITS CHILD'S FINGERPRINT, which terminates the recursion and still moves
  the parent when something deep in a child changes. Asserted both ways.

- CLOSURES ARE ERASED AND NOT RENDERED, and this is the decision the rest rests on. `m/form`
  will happily render a closure — MEASURED: `[:fn {:error/message "nope"} (fn [v] ...)]` prints
  as `#object[user$f2$fn__44837 0x3442b587 ...]`, and two builds of it have forms that are NOT
  `=`. A hex address differs every process, so a fingerprint over the printed form would be
  worthless to a transcript. Everything that is not a value becomes one marker, `::opaque`.
  This is not academic: ../coder's `Signature` is exactly `[:and vector? [:fn {...} (fn [x] ...)]]`
  and it is embedded in eight of that task's state schemas.

- WHAT IT PROVES IS THE GRAPH AND NOT THE CODE, and it has to be said wherever it is used.
  Change what a handler returns without changing its `:out`, or change what an `:fn` predicate
  checks, and the fingerprint does not move. That is the same limit :a-shape-is-code imposes
  everywhere else, and it is the price of a shape whose behaviour is closures.

- AND THE ENV DOES NOT MOVE IT EITHER, measured on ../coder's own task shape once a shape became
  a function of its env: `(shape {})` and `(shape {:writer ... :repl ...})` fingerprint the same,
  the env being closed over in reports and reports being erased. That is RIGHT — it is the same
  machine — and it means the fingerprint does not tell you where it ran. Where it ran belongs in
  the log's context beside the name.

- IT IS DERIVED AND NOT DECLARED, which is the whole reason to have one rather than a version
  number: nobody can forget to bump it. This repository's own cross-component rule is that a rule
  living only in a declaration is a rule nothing checks.

- AND IT CARRIES NO NAME. What a machine is called is a fact about the JOB rather than about the
  graph, and belongs to whoever owns the job — so identity is two-part and only half of it is the
  library's. ../coder puts the name on the mulog context and the fingerprint rides on every row.

- WHAT IT REOPENS, PARTLY. :what-is-persisted put shape versioning out of v1 `with the question
  it drags behind it: which shape an instance mid-flight belongs to`. A fingerprint on every row
  answers that for a FINISHED run, which is the audit case and the one that was asked for. An
  instance in flight across a shape change is still open, and is left open deliberately.

## :an-event-may-say-how-it-is-reported

THE AUTHOR'S, 2026-09-04, and it arrived from a CONSUMER rather than from this library, which
is the first time that has happened. ../coder's driver carried a map of `acts` keyed by state —
`{:initial briefing :implementing implementing ...}` — and the objection was exact: it
`collects otherwise independent steps into a global map, which is an anti pattern — the
integration point should not be spread, the FSM shape already did it`.

- THE DIAGNOSIS IS THAT THE SHAPE HAD NO PLACE TO SAY IT. Measured on coder's own shape before
  anything was proposed: every non-final state there has EXACTLY ONE outgoing event id, so the
  state->event half of that map was already in the graph and carried no information. What was
  NOT in the graph was the other half — which events a driver may produce at all — so the whole
  map existed to carry one fact the shape could not hold, and dragged a redundant index along
  with it. Nothing compared the two, so a missing entry was `nil` called as a function, mid-run,
  after a model had been paid for the turn before.

- AND THIS FILE HAD ALREADY NAMED THE GAP AND DISMISSED IT.
  :a-join-is-the-product-and-the-licence says, of parking: `THE ONLY THING THE SHAPE CANNOT SAY
  is whether an event comes from the DRIVER or from the WORLD — a label and not a feature, and
  nobody has asked for it.` It is a feature. What says so is that every driver written against
  this library must write that knowledge down a second time, somewhere the checker cannot see.
  The dismissal was reasonable and wrong, and the thing that refuted it was a consumer.

- THE GRAMMAR: `{:report <fn> :reads <a map schema>}` on an event, and nothing else added.
  `:report` is the function that goes and finds the fact; `:reads` is the view of the state it
  needs to do so, projected onto its keys and validated, exactly as `:sees` is for a handler.
  AN EVENT WITH NO `:report` COMES FROM THE WORLD — which is what a park IS — so the presence
  of the declaration is the driver/world distinction, in data rather than in prose.

- IT IS SYMMETRIC WITH WHAT AN EVENT ALREADY HAD, and that symmetry is the argument for putting
  it on the event rather than on the state:

      :handler  :out  :sees      how an event LANDS      event -> patch
      :report   :reads           how an event is FOUND   state-view -> event payload

  Both halves off ONE declaration, which is what :a-handler-belongs-to-the-event bought for the
  first half and the reason two edges firing one event cannot disagree about it.

- IT IS NOT AN INTERNAL EVENT, and it does not reopen :a-handler-causes-nothing. The machine
  still does not move itself. This is the shape telling a CALLER how an event would be found,
  and a caller choosing to ask — so there is no queue, no run-to-completion, and the reduction
  is untouched. A driver that ignores every report still works.

- WHY THE WORK CANNOT SIMPLY GO IN THE HANDLER, which was proposed first and is the thing to
  understand before proposing it again. A GUARD READS THE INCOMING EVENT: `compile/entry`
  validates the guard against the event's payload and only THEN runs the handler — verified in
  the source, not assumed. So a fact a branch depends on must already be on the event when it
  ARRIVES. Move coder's REPL evaluation into the `:judged` handler and it computes `:verdict`
  after the edge has been chosen; the only way back is two events for one observation, which is
  precisely the hidden transition :a-guard-is-a-schema-over-the-event removed. The producer has
  to be outside the machine; the only question was where it is DECLARED.

- AND A SHAPE IS A FUNCTION OF ITS ENV, which is the author's other half and what makes a report
  able to do real work. A consumer writes `(defn shape [env] ...)` and the reports close over
  the writer and the REPL as the shape is built, so nothing downstream carries an environment —
  coder's `step` went from `(step shape acts env events)` to `(step shape events)`.
  :a-shape-is-code already licensed a closure in a shape; this is that, one level out. AND THE
  SHAPE CAN STILL BE BUILT WITH NO ENV AT ALL: measured, `(shape {})` in coder checks, draws and
  answers `stepped` for nothing, because none of those runs a report.

- `readings` IS `admits` FOR THE FOURTH TIME — `views` was the second and `yields` the third —
  with the read as TARGET and the source state's schema as PRODUCED. A driver runs a report in
  the state that AWAITS the event, so that state is what must provide the keys. `:undeclared`
  where there is no report, `:reads-unavailable` where it is PROVEN the state cannot supply it,
  and A KEY THAT IS MERELY OPTIONAL IS `:no` for the same reason a view cannot rest on a maybe.
  Sound for the same reason `views` is: a node holds exactly what it declares.

- A `:reads` WITH NO `:report` IS REFERENTIAL, being a view nothing will ever be handed — the
  same shape of mistake as an `:out` on an event nothing fires, and answerable from the parts
  alone, so the shape never exists.

- AND THE PURE LIFT KEPT ITS SHORT FORM, which mattered more than it looks. Declaring a report
  would otherwise have forced the 5-arity and written the event's schema out twice again,
  undoing :an-event-given-only-a-schema-is-a-pure-lift the day a second option existed. A
  handler is a `fn` and options are a `map`, so the 3-arity takes either and says which by type:
  `(event :again [:map [:round :int]] {:reads ... :report ...})`.

- WHAT IT COST: two public functions (`shape/reports`, `check/readings`), one arity that
  disambiguates by type, two faults, and three tests. 132/387 to 135/400, both suites green.
  In coder it DELETED a concept: no `acts`, no `env` argument, and `unacted` — the check that
  existed only to catch a missing entry in that map — went with it, absence of a report now
  meaning `the world supplies this` rather than `somebody forgot`.

## :a-handler-answers-a-map-and-declares-it

A handler's return value is a MAP, MERGED into the state — and the event also DECLARES the malli
schema of that map. Both halves are chosen for one reason, the static check, and no other.
- An opaque (fn [state] state') can never be checked, whatever the graph holds it in, and a diff
  composes with a schema no better. A merge of two MAP SCHEMAS does:
  merge(<from schema>, <declared out>) ⊆ <to schema> is decidable WITHOUT RUNNING ANYTHING.
- THE DECLARATION IS OPTIONAL PER EVENT. Absent, the check degrades to a GENERATIVE one —
  generate an event, run the handler, validate the answer against the target — which is this
  project's testing style anyway, and `subsumption` says :undeclared rather than faulting.
- THE COST AS FIRST STATED — a merge cannot REMOVE a key — IS PAID OFF, from the other end than
  it was stated at: the merge is PROJECTED onto the target's declared keys on entry, so dropping
  a field is declaring one fewer. What it costs instead is that carrying a key across several
  states is EXPLICIT, each of them declaring it — which for a join is not a cost but the whole
  mechanism, see :a-join-is-the-product-and-the-licence.


## :the-event-catalogue-is-denormalised

FORCED by ubergraph rather than chosen — an Ubergraph holds nodes and edges and NOTHING ELSE
(see :ubergraph-0-9-0), so the event catalogue has nowhere on the graph to live. It is therefore
an ARGUMENT to the constructor, which writes each event's schema, handler and :out onto EVERY
EDGE that carries it and refuses a shape whose edges disagree about one event. The graph remains
the whole shape.
- WHAT IS LOST AND WHERE IT GOES: `an event no transition mentions is dead code` stops being a
  graph query, because no catalogue exists to be dead relative to. It becomes a
  CONSTRUCTION-TIME check, which is the only moment the catalogue is in hand.
- WHAT WAS KILLED FIRST, so nobody proposes it again: making the graph bipartite,
  state --> event --> state, is not merely awkward, it is WRONG. Two transitions on :submit
  leaving different states would share one event node and FABRICATE paths the shape never said —
  A -> submit -> D, when all that was declared was A -> submit -> B and C -> submit -> D.


## :a-guard-is-a-schema-over-the-event

DESIGNED WITH THE AUTHOR AND BUILT 2026-09-03. How a conditional transition is spelled, and it
REPLACES :v1-is-deterministic — which said NO GUARDS, a state and an event having exactly one
target, at the cost that :submit -> :accepted | :rejected was INEXPRESSIBLE. DETERMINISM IS NOT
WHAT WAS GIVEN UP: it stays the contract, and the whole of the change is that it is now PROVEN
rather than had for free.
- WHAT STARTED IT, the author's: `a hidden transition is something we want to avoid`. The
  DISTINCTION IS EXTERNAL AGAINST HIDDEN — a person clicking approve is external and the machine
  never claimed to model them; a step of your own workflow that runs the code and then picks the
  edge with an `if` is HIDDEN, and the drawing shows both arrows with nothing saying which fires.
  With the target a function of [state, event-id] alone, a data-dependent branch cannot be in the
  shape AT ALL, so `no hidden transitions` and `no guards` could not both hold.
- THE GRAMMAR: {:when <a map schema>} on a transition, and nothing else added. :when IS TO A
  TRANSITION WHAT :sees IS TO AN EVENT — both an optional map schema, each declared where the
  thing it constrains lives. :sees projects the STATE for the handler; :when filters the EVENT
  for the edge.
    (transition :written :judged :implemented {:when [:map [:verdict [:= :green]]]})
    (transition :written :judged :fault       {:when [:map [:verdict [:= :red]]]})
  WHAT WAS TURNED DOWN was a map with three meanings in it — a dispatch key beside case->target
  pairs beside an :else — refused by the author on sight and rightly: the tiers are not grammar,
  they are HOW MUCH THE CHECKER CAN PROVE, which is the same three answers `admits` already gives.
- WHY A SCHEMA AND NOT A PREDICATE. A schema is DATA — drawable, storable, comparable, partially
  decidable by machinery already here. A predicate is a closure, which is the thing being
  escaped. And an `:fn` carries a :description, so ONE EXPRESSION IS BOTH THE CHECK AND THE
  LABEL: the repository's own cross-component rule, and it means a named guard needs no naming
  mechanism.
- WHAT THE OTHER LIBRARIES DO, surveyed the same day. THREE FAMILIES. (A) ORDERED CANDIDATES AND
  A PREDICATE — UML/Harel, SCXML `cond`, XState, clj-statecharts, python-transitions, Spring:
  [state, event] yields a LIST in DOCUMENT ORDER, first passing guard wins. The part worth
  stealing is not the guard but that XSTATE NAMES IT, so a visualizer can draw EVENT [isGreen] —
  which is Harel's own notation, unchanged since 1987. (B) PATTERN MATCHING IN HOST CODE —
  gen_statem, Akka, Rust statig: no graph to check, accepted deliberately since none promised a
  drawable one. (C) DETERMINIZE ON THE INPUT VALUE — Automat compiling an NFA to a DFA, and
  table-driven lexers. THIS PROPOSAL IS IN C.
- AND FAMILY A IS THE ONE THIS GRAPH CANNOT HAVE, measured rather than assumed:
  ubergraph/core.clj:284 stores node-info as {:out-edges {dest-id #{edge}} ...} — A SET. There is
  no edge order to recover, so DOCUMENT-ORDER FIRST-MATCH IS NOT REPRESENTABLE, and a priority
  number would be order smuggled back in as data. Determinism here has to be PROVEN rather than
  ordered — the same shape of constraint as :the-event-catalogue-is-denormalised.
- HENCE THE RULE, sharper for being forced: DECIDABLE GUARDS BRANCH, AND AN :fn GUARD MAY ONLY
  APPEAR ALONE on its [from event]. A lone :fn is a FILTER — `:again only while under budget` —
  and cannot threaten the lookup, having nothing to be ambiguous with.
- :ambiguous INVERTS, AND SHOULD. Everywhere else this checker reports only PROVEN faults; here
  it must demand PROVEN SAFETY — two edges on one [from event] whose guards are not provably
  disjoint are a fault. The asymmetry is principled: determinism is the CONTRACT, and a shape
  that cannot prove it is deterministic is not one.
- THERE IS NO :else, and it is the fallback concept the whole of family A needs and this library
  already had: no guard matching means no edge admits the event, which is `ignored` — the
  reduction stays total and the stream says :fired false.
- THE THREE DECIDABLE LEVERS. A SHARED KEY whose value schemas are disjoint. A CLOSED SCHEMA
  that does not name k is disjoint from one that REQUIRES k — {:closed true} being already this
  library's vocabulary. And NUMERIC BOUNDS, taken at the author's word: maxA < minB is a PROOF,
  over the :min/:max properties and malli's :> :>= :< :<= comparator schemas. Without that third
  lever `attempts > 3` would have been forced back into being a tag, refusing what malli can
  already decide.
  AND THE CLOSED LEVER REACHES LESS FAR THAN FIRST CLAIMED, corrected by trying it in ../coder:
  `green means no :fault key` DOES NOT WORK when the key is one the EVENT'S OWN SCHEMA declares,
  because `accepted` MERGES the guard over that schema and [:fault {:optional true}] survives as
  genuinely satisfiable — so :unknown is correct and the shape is rightly refused. MEASURED. The
  lever reaches a key the event schema does not declare AT ALL, and no further. So a tag was
  needed after all, and coder's :judged carries {:verdict [:enum :green :red]}.
- THE PAYLOAD CONVENTION, the author's: A GUARD DESCRIBES THE EVENT WITHOUT THE MACHINERY KEYS.
  An event map carries :id, and :instance where a run is named, so a guard is checked against
  (dissoc event :id :instance) — exactly as a state's schema describes the state without :id,
  :instance and :sub. One convention in a third place, and without it a closed guard would fail
  on :id every time. AND IT CANNOT STOP AT THE GUARD: the event's own :schema is conformed
  against the PAYLOAD too, or the exhaustiveness check would compare a guard over the payload
  against a schema over the whole map — two schemas about different values, which is the drift
  :a-partial-subsumption-checker exists to refuse. That also makes a CLOSED EVENT SCHEMA usable.
  What it breaks is small and is probably a correction: an event declaring :id or :instance in
  its own schema used to validate and now does not.
- THE CHECKERS ARE THREE, AND NONE IS NEW MACHINERY.
  `accepted` IS THE SIBLING OF `produced`: the schema of the events a transition FIRES ON, being
  the event's payload schema with the edge's :when merged over it. BOTH CHECKS BELOW RUN ON IT
  and never on the bare :when, or a guard the event schema already contradicts would look
  satisfiable. `produced` is what comes OUT of a transition and `accepted` is what goes IN.
  `disjoint` IS THE SIBLING OF `admits`: same three answers, same levers, ONE INVERSION — THE
  MAP COMBINATOR FLIPS. `sub-map` is an AND over keys, every one of which must be admitted;
  `dis-map` is an OR, ONE conflicting key being enough. A key OPTIONAL IN BOTH conflicts with
  nothing, a value being free to omit it.
  `coverage` IS THE SIBLING OF `subsumption` AND `views`: one verdict per [from event] group,
  being the other two run against a PROBE — the event schema with one key pinned to one value of
  a finite domain. Two structural checks off one subsumption function for the SECOND time.
- `problems` GAINS ONE FAULT AND NOT TWO. :ambiguous only. COVERAGE IS PUBLISHED AND NEVER
  FAULTED, because a gap means no edge admits the event, which is `ignored` — legal, first-class,
  and exactly what a lone :fn filter is FOR. Faulting it would make `problems` report a
  SUSPICION, which is the one thing it has never done.
- WHAT IT COSTS AT RUNTIME: compile's index maps [from event] to a small VECTOR of candidates,
  each tried with m/validate; disjointness is what makes set order irrelevant, so there is
  nothing to sort. One unguarded candidate is today's path at today's cost.
- THE DRAWING IS HAREL'S: judged [:verdict :green], or the :description where there is one.
  A guard is STRUCTURAL — it changes where you go — which is the test :a-node-is-labelled-by-its-id
  sets for anything wanting into a label.
- WHAT IS NOT TAKEN: A GUARD OVER THE STATE. A guard is over the CAUSE, and the cause is the
  event. THE DRIVER REPORTS A FACT AND THE SHAPE DECIDES WHAT THE FACT MEANS is the whole move;
  turning `a fault string exists` into `go to :fault` inside a driver is precisely the hidden
  transition. A {:sees}-style guard over the state is a DOOR, named and not designed.


## :a-state-may-say-where-it-goes-when-it-completes

BUILT 2026-09-03, out of a review of this architecture (see :what-the-review-scored) which named
one thing genuinely missing that this file had already named twice and not opened.
- THE GRAMMAR: {:done <id>} on a state, and {:yield <a map schema>} beside it where the node
  nests a machine. NO FACADE FUNCTION — a completion transition is an OPTION ON `state`, exactly
  as nesting is.
- ONE RULE, AND IT IS UML'S: A STATE COMPLETES WHEN IT HAS NOTHING LEFT TO DO. A state with no
  :machine has no activity to finish, so FINISHING IT IS ARRIVING and it is passed straight
  through. One WITH a machine completes when that child reaches a final state. That unification
  is the whole reason this is small: a simple state and a composite one are not two features.
- IT IS NOT A GUARD, and that is what it BUYS rather than what it concedes. One target,
  unconditional, so `compile` stays a lookup and nothing has to be proved disjoint — and a CYCLE
  among entry-completing states is then a PROVEN infinite loop rather than a suspicion, an
  unconditional relation being a plain functional graph. :done-cycle, and it is REFERENTIAL, so
  a machine that would spin for ever is never built. A cycle THROUGH a nesting node is legal —
  the events are what break it — and the check knows the exception: a child whose own first state
  is FINAL makes its parent complete on entry too.
  THIS IS :a-handler-causes-nothing'S OWN LEAN TAKEN, at the cheaper end. What did NOT happen is
  the event queue: a completion is a DETERMINISTIC CONTINUATION resolved inside one step, so
  there are still no internal events, no run-to-completion, and nothing to drain.
- :yield IS WHAT A FINISHED CHILD HANDS UP, HARVESTED AT COMPLETION ONLY. That restriction is
  not tidiness, it is what makes the check SOUND: completing is the only moment the child is
  guaranteed to be in a final state, so the yield schema is a GUARANTEE rather than a hope. Taken
  on an ordinary escape the child could be in any state and `problems` would condemn shapes that
  run. AN ESCAPE IS STILL AN ABORT AND STILL YIELDS NOTHING — aborting is the commoner need and
  stays what an event does; :done is how a parent WAITS instead.
- IT IS A REAL EDGE AND NOT A NODE ATTRIBUTE, and this is the decision the rest rests on.
  `reachable`, `dead-ends`, `finishable` and `traps` all WALK THE GRAPH, so as an edge all four
  see it and NONE was told anything; as an attribute, four traversals would each have had to
  learn about it or condemn correct shapes. It carries no :event, and that absence is the whole
  distinction: `shape/transitions` reads it to leave these out, so `index`, `coverage`,
  `commutes` and subsumption-over-events are untouched, and `shape/continuations` is where the
  other kind is read. NOTHING IS LEFT ON THE NODE — two places saying one thing is how a shape
  drifts from itself.
- `subsumption` COVERS IT AND IS NEVER :undeclared, the one way a completion is checked HARDER
  than an event edge: a completion carries NO CLOSURE, so what arrives is the state itself and
  its schema is known exactly. `continued` is the sibling of `produced`. And `yields` IS `admits`
  FOR THE THIRD TIME, with the yield as TARGET and the child's own final state as PRODUCED —
  EVERY final state is asked, a child being free to finish in any of them, and a yield resting on
  only some is a yield that is sometimes not there. :yield-unavailable.
- THE LICENCE HAD TO LEARN ABOUT IT: if ta CONTINUES, the second patch of a licensed pair is
  applied where ta continued TO, so (tgt [ta b]) is not the lookup that runs. `commutes` refuses
  s, ta and tb. THE JOIN NODE x IS EXEMPT and that matters rather than being a nicety — both
  orders were proved to arrive at the SAME x and a continuation is a pure function of the state,
  so a join's own :complete is exactly where a :done belongs.
- WHAT IT COSTS AT RUNTIME: one map lookup per transition for a shape declaring none. And
  `arrive` is now ONE definition of what entering a node means, called by all three places that
  do it — the first state of a run, the far end of a transition, the far end of a continuation.
- ONE RESULT ROW PER EVENT, carrying the state the chain ended in; the intermediate hops are not
  published. They are a PURE FUNCTION of the shape and the state, so an auditor holding the shape
  can reconstruct them; an EVENT is the thing a row could not be reconstructed without. It also
  keeps `the-two-doors-agree` intact, a continuation resolved inside the STEP being passed
  through by the reduction too.
- THE DRAWING IS UML'S: dashed and UNLABELLED. There is no event to name, and a :yield is about
  the DATA rather than about where the machine goes.
- :done MAY SAY WHERE EACH OUTCOME GOES, ADDED 2026-09-05: given an id it is one target for every
  way the child can finish, and given a MAP FROM THE CHILD'S FINAL STATE it is one target per
  outcome, each carrying a :yield of its own. The bare form is unchanged and means what it always
  meant, so no shape written before this moves — including its fingerprint, `canonical` emitting
  :outcome only where there is one.
  IT IS NOT THE CONDITIONAL COMPLETION THE OPEN QUESTION REFUSES, and the distinction is the whole
  argument. That question is about a fact over DATA — `all n reports are in`, `k branches have
  arrived` — and refuses it on DECIDABILITY: each is a relation between keys that no schema
  expresses, so it could only be a CLOSURE, and a closure may decide a VALUE but never where the
  machine goes. This reads the STRUCTURAL fact :done already reads — is the child final — one notch
  finer, over a set that is FINITE AND KNOWN AT CONSTRUCTION, dispatched by a map lookup on an id.
  No schema, no predicate, nothing to prove disjoint, and `compile` is still a lookup. The line
  drawn was `a structural fact may decide COMPLETION, never WHICH WAY`, and it was drawn against
  data conditions; the structural branch is a case that question never contemplated.
  IT MAKES `yields` SHARPER RATHER THAN LOOSER, which is the tell that it is the right shape. An
  unconditional completion's yield must hold at EVERY final state, a child being free to finish in
  any of them; a per-outcome one is asked about its OWN final state and no other, because that
  branch is taken only when the child stopped there. Naming the outcome is what buys the precision.
  WHAT IT UNBLOCKS: a parent that can tell `it worked` from `it gave up`. Before it, both landed in
  one state and a `:yield` could name only keys both final states hold — so ../coder's polish
  machine had to read a `:fault`'s presence as a tea leaf and copy the good code aside under another
  key to stop the failed attempt overwriting it. With it, the branch that gave up yields NOTHING and
  the parent keeps what it had by never being written to. A state holding what it declares does the
  rest.
  ONE EDGE PER OUTCOME, so the four traversals were told nothing a second time — the same property
  that made a completion an edge rather than a node attribute. `:done-cycle` follows the entry-fired
  target, which stays decided: an entry-completing state has no machine, or its child's first state
  is final and NAMES the outcome. Two new referential faults, :unknown-outcome and
  :outcome-without-machine, plus :yield-with-outcomes for the second spelling of one thing.
  AND THE DRAWING HAD TO LEARN. An unconditional completion stays dashed and UNLABELLED — there is
  no event to name — but two dashed arrows leaving one node are two different STRUCTURAL facts, and
  a picture that cannot tell them apart shows a machine that does not exist. A per-outcome one is
  labelled `[<the child's final state>]`, written the way a guard is.
- WHAT IS NOT TAKEN. A COMPLETION ON A CONDITION OVER DATA is a guard over the STATE and stays
  refused, see :open-questions. A NODE STILL HOLDS ONE CHILD, so a parent waiting on SEVERAL
  independent children is still orthogonal regions and still out. AND FAN-OUT IS STILL STATIC in the
  shape: the width is a runtime value, and a graph shows structure while a count is data.


## :parallel-is-across-instances

`Automatically parallel` means ACROSS INSTANCES and nothing else: events partitioned by
instance, one sequential reduction each, run at once. Orthogonal regions inside ONE machine are
OUT — that is a statechart and not this, and the shape would have to declare which parts of a
state a transition touches before any of it were safe.
- WHY ONE MACHINE CANNOT PARALLELISE, and it is a DATA DEPENDENCY rather than anything about
  manifold: ADMISSION. A handler runs only where an edge admits it, and whether this state
  admits this event cannot be known until the previous step has landed. A parallel map over one
  machine's stream would have to run handlers SPECULATIVELY, and since deferreds exist so that
  handlers can do I/O, speculation means real effects for events the machine ignores.
  (The FIRST version of this argument was that handler SELECTION needs the state. That stopped
  being true when the handler became the EVENT's — see :a-handler-belongs-to-the-event — and the
  conclusion survived the change to its reason, which is why both are recorded.)
- AND A THIRD REASON, the author's, which holds where both others fail: a handler that CAUSES
  another event makes parallel handlers interleave WRONGLY — not wastefully, wrongly — so even
  with pure handlers and free speculation the order would be wrong. That is why statecharts have
  run-to-completion. v1 forbids emission, so the hazard is shut rather than survived.
- CORRECTED 2026-08-31, the author's: EVERY ARGUMENT ABOVE IS ABOUT EVENTS ARRIVING ONE AT A
  TIME. None touches two events PENDING IN THE SAME STATE, which is what an async handler
  creates. Both are then selected from the same state, so there is no speculation and no unknown
  state. `Across instances` is still where the PARALLELISM is; a narrow CONCURRENCY inside one
  machine is licensed by the shape, and the whole of it is in :two-events-in-flight-at-once.


## :what-is-persisted

SUPERSEDED by :nothing-is-persisted-here — nothing is persisted BY THIS LIBRARY. What survives is
the answer to a still-live question, now advice for whoever writes a store OUTSIDE this library:
what a caller should keep is HISTORY and not the shape. Both authorities already said so — the
README's `a machines states, events, transitions become history`, and :a-shape-is-code, which
forces it: a shape built at load time out of closures and compiled schemas is not something a
database reloads a machine FROM. So SHAPE VERSIONING IS OUT OF v1 and stays out, with the
question it drags behind it — which shape an instance mid-flight belongs to.


## :an-instance-has-an-identity

An instance is identified by a FIXED FIELD, :instance, written by the CONSTRUCTORS and not by
hand — a caller says which machine they mean and never spells the key.
- THE NAME IS THE README'S OWN WORD, so any synonym would be this file overriding the README on
  a coin flip. It is a PLAIN keyword and not a namespaced one, because it is data a user reads
  and writes in their own maps, exactly as :id is.
- IT IS ON THE EVENT AS WELL AS THE STATE, and the EVENT is the half that is load-bearing:
  routing an incoming event to the right reduction is a decision made BEFORE any state is in
  hand, so the partition key cannot be read off a state.
- IT IS A THIRD IDENTITY AND GETS A THIRD NAME. :id on a state is which NODE it is in and :id on
  an event is its TYPE. A word doing two jobs here would be the bug nobody sees.
- nil NAMES NOTHING. `fan` keys an event carrying no :instance under nil, so initial's instance
  argument is [:maybe Instance] and asserts nothing — which costs nothing real: the invariant
  worth having is that no STATE ever carries a nil :instance, and that lives on the enter schema
  where it is checked on every entry rather than once at the door.
- WHAT WAS TURNED DOWN: a key-fn handed to the async layer, leaving the core ignorant that
  instances exist. More decoupled, and not chosen — a fixed field the constructors own is
  simpler to document, and it makes a state self-describing with no second argument travelling
  beside it.


## :a-handler-may-answer-later

A handler MAY answer a DEFERRED rather than a plain map, so an instance waiting on I/O does not
hold a thread. That is what a stream library is actually for, and with parallelism living across
instances it is what stops one slow handler starving the pool.
- HOW, WITHOUT PUTTING MANIFOLD UNDER THE CORE. compile never learns what a deferred is. It is
  parameterised by HOW A VALUE BECOMES AVAILABLE — a `then` and a `pure` — and composes the step
  out of those two and nothing else. The SYNCHRONOUS DEFAULT needs no dependency and reproduces
  the plain step exactly. The async layer passes d/chain and d/success-deferred and requires
  manifold on its own account. This is the global rule applied, not a new idea: inject a function
  that closes over the options.
- `then` IS A BIND AND NOT AN fmap, which only became visible when `phases` used it twice per
  call — see :what-the-phase-split-taught.
- A DEFERRED UNDER THE SYNCHRONOUS DEFAULT IS DEREFERENCED, the author's, and better than the
  guard that was going to be recommended: synchronous is exactly what `block until it is
  available` means, so there is nothing to refuse. AND IT COSTS NO DEPENDENCY —
  clojure.lang.IDeref is CLOJURE'S, a manifold deferred implements it, and a map does not, so
  the common path is untouched.
- THE COST, said out loud: the step's RETURN TYPE is the caller's to know — a State under the
  default, a deferred State under the async layer. And the synchronous path can now BLOCK: a
  handler whose deferred never resolves hangs the reduction, and choosing a default timeout is
  policy, so none is chosen.


## :a-handler-belongs-to-the-event

DECIDED 2026-08-31, by the author, and it is the README's own reading recovered: A HANDLER IS
CHOSEN BY THE EVENT ALONE. `Each transitions (by event only, a function handle the event, return
value will be applied to a state)` says it, and the first implementation had keyed the handler on
[state, event] instead. So (event id schema handler out) and (transition from event to).
- THE TARGET STILL COMES FROM THE GRAPH. Only the HANDLER is the event's; where the machine
  lands is [state, event] -> to as before, because A -submit-> B beside C -submit-> D is the
  thing :the-event-catalogue-is-denormalised exists to keep expressible.
- WHY IT IS BETTER QUITE APART FROM PARALLELISM: two edges can no longer DISAGREE about a
  handler, so a construction-time check is replaced by a shape in which the error cannot be
  written. And BOTH halves of the handler's function schema now come from the event definition
  and nothing at all from the graph.
- THE COST, said out loud: A -submit-> B and C -submit-> D SHARE one handler and one :out, so
  submit's :out must satisfy B's schema AND D's. Where two edges genuinely need different data,
  that is two events — or, since guards, no :out at all and the runtime crossings enforcing it.
  A GUARD AND A PER-TARGET PAYLOAD PULL AGAINST EACH OTHER; see
  :what-the-first-consumer-migration-taught, which is the first outside evidence of that.


## :an-ignored-event-is-not-an-error-but-is-not-silent

An event the current state has no transition for is NOT AN ERROR — the reduction stays total —
but the step must SAY it happened.
- WHY NOT AN ERROR: nothing controls the order events arrive in behind a stream, so a :cancel
  landing after :complete is ordinary traffic and not a defect. A machine that throws on it is a
  machine every caller needs a policy for.
- WHY NOT SILENT: an event that SHOULD have transitioned and did not looks exactly like one
  correctly ignored, and no static check can see a runtime fact.
- THE HANDLER DOES NOT RUN. No edge means no :to, so there is no enter-schema to validate
  against and nothing to apply the data TO. The data is not merged — it is never computed.
- AND THAT IS WHAT MAKES IT SAFE, because the alternative is worse than it looks. Malli maps are
  OPEN BY DEFAULT, so merging a handler's answer into a state with no edge for it would produce
  a state carrying keys it never declared AND PASSING ITS OWN ENTER-VALIDATION. Verified.
  `Able to apply, but wrong` was the author's phrase for it and it is the sharpest hazard this
  design had.
- A MALFORMED EVENT IS NOT AN IGNORED ONE, and conflating them was a real hole found by running
  guards: selection happens before conform!, so an event failing every guard looked like an
  ordinary miss. A GUARD IS A REFINEMENT OF A SCHEMA THE EVENT MUST ALREADY SATISFY, so where
  nothing matches the event is conformed against the group's schema — a bad event throws and a
  well-formed one no guard wanted is still ignored.


## :one-ordered-stream-per-instance

A machine is fed ONE TOTALLY ORDERED stream of events. A caller with several sources merges them
into one order BEFORE the machine sees them, because the caller is the only one who can — the
machine has no clock and no way to know two events were concurrent. IT IS A REQUIREMENT THE
LIBRARY STATES rather than an assumption it quietly makes.
- WHAT IT BUYS IS A WHOLE FEATURE. If external order is guaranteed then an event this state
  cannot handle is never EARLY: it is irrelevant, or it is a bug in whoever produced it. So
  DEFERRED EVENTS — the UML mechanism where a state parks an event and the machine re-delivers
  it — are not needed, and are out of v1. That is a per-instance queue, a re-drive on every state
  change and a deadlock case, all avoided by writing an assumption down instead of leaving it
  unsaid.
- WHERE IT BREAKS, so nobody is surprised: two producers with no shared clock, an at-least-once
  transport that redelivers, a partitioned queue where one instance's events span partitions.
  Each is real, and each is the caller's to fix upstream.


## :an-event-given-only-a-schema-is-a-pure-lift

THE AUTHOR'S, 2026-09-03: `the event's 4-arg constructor looks very redandunt.` It is, and the
redundancy is exact rather than a matter of taste — for a handler that only lifts, which is
nearly all of them, the event's SCHEMA, a (fn [e] {:k (:k e)}) per key, and an :out that is the
schema AGAIN are one fact written three times.
- SO `(event :brief [:map [:brief Brief]])` is the whole declaration: `lifting` makes the
  handler out of `mu/keys`, and the :out is the schema. The 3-, 4- and 5-arities stay for a
  handler that does something a select-keys does not.
- :id AND :instance ARE NOT LIFTABLE, and it falls out rather than being arranged: they are not
  in the declared schema, so `mu/keys` does not name them. AN OPTIONAL KEY ABSENT FROM THE EVENT
  IS ABSENT FROM THE PATCH, which is exactly what a patch schema permits.
- WHAT IT MEASURED OUT AT: the README's example lost four lines; coder's task shape went from 13
  lines of events to 5.
- AND IT DOES NOT COMPOSE WITH A TAG. A discriminating key is ROUTING INFORMATION the target
  does not hold, so a pure-lift handler answers it and the CLOSED patch schema refuses it —
  correctly. A guarded event therefore usually spells its handler out. Neither rule is wrong;
  they simply meet here.
- AND A CONSUMER'S LINT CACHE HAS TO BE REFRESHED. clj-kondo remembers the old arities of a
  :local/root dependency and reports errors for correct code: `rm -rf .clj-kondo/.cache` in the
  consumer. Seen twice.


## :an-event-is-the-only-way-a-transition-happens

THE AUTHOR'S, 2026-09-03: `In a FSM, a state can only transit by an event, so inside a machine,
the only way of doing transition is to emit an event. And this hidden transition has to be
illegal.` It makes a rule this library already intended into one it ENFORCES.
- WHAT WAS ALREADY TRUE: a handler could not move the machine, the step writing :id, :instance
  and :sub AFTER the merge. WHAT WAS WRONG WITH IT: SILENCE. A handler answering :id was
  overwritten without a word, so the rule was a convention the code quietly repaired — the same
  shape of fault as a predicate the model cannot see.
- AND THE AUTHOR BROADENED IT: `the event's returned data should match the state schema`. So the
  check is not about identity at all. A handler answers a PATCH, conformed against
  `shape/patch-schema` — the target's own schema with EVERY KEY OPTIONAL and the map CLOSED.
    optional  a handler says what changed; what it does not mention the state already holds
    closed    a key the target does not declare never reached the state anyway, so a handler
              computing something that EVAPORATES is a defect, and closing turns a shrug into a
              refusal
- IDENTITY THEN NEEDS NO SPECIAL CASE, and that is the part to keep: a state schema describes the
  map WITHOUT :id, :instance and :sub, so naming one is answering an undeclared key and is
  refused by exactly the rule that refuses a typo. One check, three guarantees, and nothing in it
  mentions identity.
- WHERE IT SITS AND WHY: after :out and before :enter. :out is what a handler PROMISES and is
  optional, existing for the STATIC check; :answer is what the target ADMITS and is not optional;
  :enter keeps the one thing only a whole state can be wrong about — A REQUIRED KEY NOBODY
  SUPPLIED, which a patch is allowed not to mention.
- WHAT IT COST: four tests, every one of which had asserted the silence. Each now asserts the
  refusal.
- AND THE EMISSION HALF STAYS SHUT, the author reasoning to it independently the same day: `An
  internal conditional should generate an event to the event queue. However, in our current
  design, the machine does not own the event queue.`


## :a-handler-causes-nothing

In v1 A HANDLER MAY NOT CAUSE ANOTHER EVENT. It answers a data map and that is all it does; a
cascade is spelled as the caller feeding the next event. Reaffirmed by the author 2026-09-03.
- WHY IT MATTERS: a handler that raises an event is the classic source of SELF-INFLICTED
  disorder, and it is why statecharts have RUN-TO-COMPLETION. With no emission there are no
  internal events, so there is no queue to drain and no RTC to implement, and the core stays the
  reduction the README promises.
- IT IS A CONTRACT AND NOT A GUARANTEE. Deferreds exist precisely so a handler can do I/O, and a
  handler doing I/O can publish to the very stream feeding this machine. No schema catches that.
  The failure mode when the rule is broken is an ordering bug wearing the mask of a logic bug.
- AN EXTERNAL EVENT IS THE ULTIMATE SOURCE OF A TRANSITION, the author's, and worth keeping as
  the principle: the world moves the machine. An INTERNAL event is not a second kind of cause,
  it is a convenience, and the thing it buys is HANDLER REUSE — smaller than `cascades` sounds.
- THE DOOR, NARROWED AND THEN PARTLY TAKEN. The raise belongs to the STATE — arriving somewhere
  is what has consequences — and not to the edge or the event. Two reasons arriving separately:
  a handler still answers a PATCH and the state applies it and only then raises; and an entry
  raise is UNCONDITIONAL, so the raise-driven relation is a plain graph and a cycle in it PROVES
  the machine can raise for ever, where a raise conditional on a handler could only be reported
  as possible. THAT LEAN WAS TAKEN 2026-09-03 as a deterministic CONTINUATION rather than as an
  event — see :a-state-may-say-where-it-goes-when-it-completes — so the cycle check exists and
  the queue still does not.
- WHAT IS TURNED DOWN IS THE HANDLER KNOWING: an answer of {:data {...} :raise [...]} undoes
  :a-handler-answers-a-map-and-declares-it, the answer ceasing to be a map merged into the state,
  so :out no longer describes it and the static check loses its subject. Reuse does not need it:
  the state can raise what the handler never mentioned.
- AND WHOEVER BUILDS THE REST SHOULD WEIGH ONE THING NOT SETTLED: an internal raise is a SECOND
  EVENT SOURCE, and :one-ordered-stream-per-instance pushed source-merging onto the CALLER
  precisely because the machine has no clock. A queue inside the machine is the machine doing
  that merging, on an order somebody has to choose.


## :how-the-step-says-a-thing-was-ignored

A THIRD INJECTED FUNCTION beside the `then` and `pure`: an `ignored` of a state and an event,
defaulting to (fn [state _event] state).
- WHY THIS AND NOT A RICHER RETURN. An outcome value — {:state s :outcome :ignored} — is the
  STRUCTURAL answer, impossible for a caller to miss, and was the better choice for as long as
  `ignored` might have grown into `deferred`. With deferral out the signal is two-valued and
  stays two-valued, and the outcome value's cost is real: (reduce step init events) would stop
  yielding states, and that reduction is the README's own headline sentence.
- identical? IS NOT IT, checked before being recommended rather than after — a fired self-loop
  whose handler answers {} returns a state identical? to the old one. Metadata on the state is
  worse still: merge and assoc PRESERVE metadata, so a stale flag would ride into every later
  state.
- THE COST: the guarantee is OPT-IN, and the layer that was to inject it was the store layer,
  which does not exist. See :open-questions.


## :a-trap-is-what-a-cycle-hides

`traps` answers the REACHABLE states from which no ending can be reached: the machine stays
alive, goes on accepting events, and can never legitimately finish.
- IT IS `reachable` RUN BACKWARDS, which is why it was cheap — uber/transpose and alg/pre-traverse
  from every :final, and a trap is a reachable state not in it.
- BOTH OTHER STRUCTURAL CHECKS WALK STRAIGHT PAST IT, which is the whole argument for it.
  `unreachable` cannot see it, because a forward traversal gets there. `dead-ends` cannot,
  because a trap HAS out-edges: going nowhere and going nowhere USEFUL are different faults. A
  dead end is a trap of SIZE ONE; two states bouncing off each other are the smallest interesting
  one, and check/problems answered [] on exactly that fixture before this existed.
- THE EXCEPTION IS WHY IT WAITED, and the fix was to put it in `finishable` and not in `traps`:
  where a shape declares no :final at all, EVERY state is finishable, vacuously, so the check is
  silent of its own accord rather than by a special case. A machine never meant to terminate is
  not a broken one.
- `traps` IS TOTAL AND `problems` IS WHAT FILTERS, the pattern `subsumption` already set: a dead
  end is reported as :dead-end, the sharper of the two diagnoses, so every state is named once
  and named by the more specific fault.


## :two-events-in-flight-at-once

A handler may answer a deferred, so a second event can arrive while the first is still in flight,
and a machine is in ONE state at a time. What may be done about the second is the whole question.
- THE TWO CASES, the author's: where the state admits ONLY the first event, the second must WAIT
  and be applied to the updated state. Where the state admits BOTH, they may in principle be
  applied in order of COMPLETION.
- BUT `BOTH ADMITTED` IS NOT THE CONDITION, and this is where the first analysis was wrong. Both
  admitted means each is INDIVIDUALLY legal there, not that they COMMUTE. idle -start-> running
  beside idle -cancel-> cancelled: if start completes first the machine is in running, which has
  no cancel edge, so the cancel is SILENTLY DISCARDED and the caller believes they cancelled.
  Reverse the completion order and it lands. That is a flake, not a race anybody chose.
- THE CONDITION IS CONFLUENCE — the diamond [S,A]->Ta, [S,B]->Tb, [Ta,B]->X, [Tb,A]->X with the
  same X — plus patches that commute, plus both intermediate states being enterable. AND THE
  INTERMEDIATE STATES NEEDED NO CHECK, which fell out rather than being solved: if [ta b] is an
  edge at all then `subsumption` has already asked whether ta admits what b produces.
- THE PATCH CONDITION IS BERNSTEIN'S: neither writes what the other writes, and neither READS
  what the other writes. The read half is what VIEWS cost — a handler that computed from a key
  the other event changes answered rightly in S and wrongly in T. MEASURED: writes of {:total}
  and {:n} are disjoint while :sum reads :n, and the two orders answer :total 2 and :total 18.
  An event with no view reads nothing, so no shape written before views was affected.
- THE WRITE-WRITE HALF IS NO LONGER ABSOLUTE, 2026-09-03. It was never really about Bernstein and
  always about `merge`: last-write-wins is the only non-commutative thing in the apply phase, so
  two patches touching one key were refused because of the OPERATION and not because of the data.
  A key that declares a COMMUTATIVE COMBINE is licensed. See :a-combine-is-how-a-patch-lands.
  The READ half is untouched and cannot be helped by one.
- THE PATCH IS NEVER STALE, ONLY THE ADMISSION IS, which is a payoff from
  :a-handler-belongs-to-the-event: a handler answers from the event alone, so what it computed
  while the machine was in S is still exactly right in T; it is only whether T admits the event
  that can have changed, and that is re-looked-up at application time. The exception is a handler
  that READS, above.
- NO DEPENDENCE AND NO INDEPENDENCE IS DECLARED. Dependency is the default and needs no saying.
  An author-asserted independence was turned down twice over: the shape ALREADY says which events
  are self-loops, so nothing needs asserting; and independence is STATE-RELATIVE, so a global
  claim would be refuted somewhere in most real shapes.
- THE CHECK IS BUILT: check/confluence publishes a verdict per pending pair per state, and
  check/commuting reduces it to {state #{#{a b}}} — the proven pairs, as PLAIN DATA the async
  layer is handed the way it is handed a compiled step. THE GENERAL DIAMOND GOT IMPLEMENTED and
  not the self-loop shortcut, because it costs the same four lookups and answering :no for `not
  both self-loops` would have been a LIE.
- THE RUNTIME TAKES THE LICENCE, 2026-09-03: `compile/phases` answers {:patch :apply :step}, the
  step being BUILT from the other two so the two doors cannot drift; async/drive and async/fan
  take a `Licence`; and `sg/run` computes it and hands it down, that being the only layer that
  knows the shape. MEASURED: two 400ms handlers on the licensed pair of a join went 843ms to
  418ms. See :what-the-phase-split-taught.
- AND THE LICENCE WAS UNSOUND UNDER NESTING, found by asking whether `commuting` could be trusted
  before resting a runtime on it. INNER FIRST means a child sees an event before the parent's own
  edges do, so a nesting node's self-loops describe a diamond that NEVER RUNS: measured, a node
  whose child admitted both events had its self-loops licensed :yes while the CHILD's `confluence`
  proved that same pair :no. `commutes` now answers :unknown wherever a nested machine could take
  either event — the state the pair is pending in, or either state it would leave it in; the state
  a pair ENDS in may nest freely. IT WAS DECORATIVE FOR TWO DAYS AND NOTHING NOTICED, because
  `drive` serialised whatever it said.
- AND A THIRD REFUSAL, from asking the same question of a new feature: a COMPLETION TRANSITION
  leaving ta means the second patch is applied where ta CONTINUED TO, so `commutes` refuses s, ta
  and tb. THE JOIN NODE x IS EXEMPT.
- AND THE PAIR MAY BE TWO OF ONE EVENT, 2026-09-03. `confluence` had enumerated pairs with
  (neg? (compare a b)), so THE DIAGONAL WAS NEVER ASKED ABOUT — yet two of one event ARE a
  concurrent candidate, an async handler making two of them pending exactly as it does two ids.
  Licensed only where every key the :out writes declares a commutative combine, which is the
  FAN-OUT. See :what-the-fan-out-licence-taught.
- WHAT IS STILL NOT TAKEN: only ever TWO events in flight. `commuting` is a PAIRWISE relation on
  ONE state; a third would need the licence re-established at each intermediate state, and
  inventing that in the async layer would be taking more than was proven. The speculative take is
  ONE event deep for the same reason.
- THE COST, ACCEPTED by the author: where concurrency is taken, HISTORY ORDER STOPS MATCHING
  ARRIVAL ORDER, and an audit trail has to represent that honestly rather than pretend to a
  sequence that did not happen.


## :a-join-is-the-product-and-the-licence

ASKED BY THE AUTHOR 2026-09-03 as two features — park on a state waiting for an external signal,
and transit only after two independent events — and NEITHER NEEDED NEW GRAMMAR. What it needed
was for the runtime to take a licence it had been computing for two days.
- PARKING NEEDS NOTHING AND ALREADY WORKED, measured before anything was proposed. A state
  waiting for :approve is a state with an :approve edge: `problems` and `traps` both silent, a
  park having out-edges that reach a final, and an event arriving that the state does not admit
  comes back :fired false, which is already the `I am not accepting that` signal. THE ONLY THING
  THE SHAPE CANNOT SAY is whether an event comes from the DRIVER or from the WORLD — a label and
  not a feature, and nobody has asked for it.
- A JOIN IS THE PRODUCT CONSTRUCTION, expressible on the day it was asked about: :complete
  declares [:map [:eval R] [:test R]], both REQUIRED, and the two events reach it by two routes
  through intermediate states that declare what has arrived so far. Which is family C of the
  guard survey, and the cost is the DFA's own: 2^n states, being 4 at n=2 and 8 at n=3.
- PROJECTION IS WHY IT WORKS rather than a thing it fights. Each intermediate state declares
  exactly what it carries, so THE STATE NAME IS THE JOIN'S PROGRESS AND THE SCHEMA SAYS SO. What
  :a-handler-answers-a-map-and-declares-it calls a cost is here the whole mechanism.
- AND `confluence` PROVES IT, which was already built. MEASURED: the n=2 join answers
  {:in :verifying :pair [:eval :test] :verdict :yes} and both orders land in one IDENTICAL map.
  At n=3 the generated lattice is 8 states, 12 edges, `problems` [], confluence {:yes 6} — every
  candidate pair at every level — with all SIX permutations landing in one identical state by six
  distinct paths. THAT IS THE FIRST :yes ANY SHAPE HERE HAS PRODUCED, and the reason
  :confluence-was-measured-not-guessed found none is that those fixtures had no join in them. A
  JOIN IS WHAT A COMMUTING PAIR IS.
- SO THE FEATURE WAS THE RUNTIME AND NOT THE SHAPE. Correctness was complete and only wall-clock
  was lost.
- A JOIN IS A PARTS ASSEMBLY AND NOT A CONSTRUCT, which is why nothing was added to build one:
  the n=3 lattice was GENERATED by a twenty-line function over the powerset, which is
  :what-the-parts-library-showed's own advice. If a `join` helper is ever wanted it belongs in
  whoever writes the workflows, not here.
- WHAT WAS TURNED DOWN. A {:join <schema>} + {:done <target>} continuation — accumulate on
  self-loops and move on when the state satisfies a schema — which is a GUARD OVER THE STATE
  wearing a different hat. AND ORTHOGONAL REGIONS, {:machines {...} :done :x}, the textbook
  answer, blocked on a question nesting has never had to answer: MEASURED, a child that finishes
  with {:id :c2 :result 42} in :sub is left behind entirely when the parent escapes. Escape means
  ABORT and discarding is correct; a join must COLLECT. THAT :yield NOW EXISTS FOR ONE CHILD,
  later the same day, and it did NOT bring regions with it — {:done :yield} waits for THE machine
  a node nests, and a node nests one. What is left is genuinely the REGIONS question.


## :a-combine-is-how-a-patch-lands

THE AUTHOR'S, 2026-09-03, in one line that reopened a door they had shut two days earlier:
`in real life, merging is a domain/task related job.` It is the answer to why the concurrency
licence was so narrow, and the diagnosis is exact.
- A NAIVE MERGE WAS THE WHOLE LIMIT, and BOTH HALVES OF BERNSTEIN TRACED BACK TO IT. Measured:
  every road to touching one key was closed — a relative change needs {:sees} and is refused
  read-write, an absolute set is refused write-write — so a concurrently incremented counter was
  INEXPRESSIBLE. The reason is one operation: `merge` is last-write-wins, and it is the only
  non-commutative thing in the apply phase, everything after it being a pure function of the
  value it produces. Two increments under a merge give :n 1 where the serial answer is 2; under
  `+` both orders give 2.
- SO A KEY MAY SAY HOW A PATCH LANDS ON IT, as properties on its own map entry:
  {:combine f :combine/commutes true}. A KEY WITH NO COMBINE REPLACES, which is what a merge
  always did, so nothing written before this behaves differently.
- IT IS A CLOSURE, AND THE FIXED VOCABULARY WAS REFUSED BY THE AUTHOR ON EXACTLY THE RIGHT
  GROUND. The first proposal was a small proven set — :+ :max :min :union — whose algebra the
  library would know. It does not survive contact: `:max` does not express `keep the
  highest-scoring implementation with its provenance`, and a review-comment merge deduplicating
  by line is nobody's `:union`. A vocabulary that covers no real merge buys a checker nothing.
- WHY A COMBINE MAY BE A CLOSURE WHERE A GUARD MAY NOT, since it reads as a reversal of
  :a-guard-is-a-schema-over-the-event and is not one. A GUARD DECIDES WHERE THE MACHINE GOES and
  a COMBINE DECIDES WHAT A VALUE IS. The first is structural — it changes the graph, which is the
  thing this library exists to make visible and checkable — so it must be DECIDED. The second
  lives inside a state's value, exactly as a handler's body always has, and :a-shape-is-code
  already licenses that.
- WHAT MAY NOT BE A CLOSURE IS THE PROMISE. No function yields its own algebra, so the law is
  declared beside it as DATA, and that declaration is the only part `commutes` reads — which
  makes it the class of claim the repository's own rule is about, so it is CHECKED AT TWO
  STRENGTHS, and it needs both:
    check/laws   REFUTES it by generation from the key's own schema. It never answers :yes,
                 generation being able to refute a law and not to prove one, so the verdicts are
                 :no with a witness or :unknown. Seeded, because a check that answers differently
                 each call is not a check.
    compile      VERIFIES it on the CONCRETE VALUES whenever the licence is actually taken, both
                 patches being in hand, and BEFORE either lands. A false promise is then a defect
                 that stops the machine rather than an order-dependent flake.
- AND THE LAW IS NOT THE ONE FIRST NAMED. What the licence needs is LEFT-COMMUTATIVITY over
  (state, patch, patch) triples — f(f(s,a),b) = f(f(s,b),a) — which is the shape the FOLD has,
  and not commutativity of the binary operation. The binary law was implemented first and is the
  wrong test.
- THE SECOND LAW IS :closed AND IS NOT OPTIONAL: f of two values of the key's schema must answer
  a value of that schema. It has to hold or the STATIC check is wrong — `produced` composes the
  declared :out over the source's schema and knows nothing of a combine, so a combine that
  changed the type would make every edge into that state a lie.
- THREE NODES AND NOT ONE decide whether a shared key may be written by both events: ta, tb and
  the join x, being every node a patch of the pair ever lands on. Each must declare the SAME
  combine and each must declare it commutative, because the fold applies the first patch at ta or
  tb and the second at x. For a SELF-LOOP, which is where combines pay, all three are one node.
- IT IS DECLARED ON THE NODE and never on an event: the same key must combine the same way
  however it arrives, or the algebra is per-edge and proves nothing. Which is also the say the
  data owner should have.
- AND IT LICENSES TWO OF ONE EVENT, which is the FAN-OUT case and was refused outright until
  then. n workers each reporting a result send n events of a SINGLE id into one accumulating
  state; the old refusal reasoned that two events of one id write one set of keys and so conflict
  by construction — TRUE UNDER A MERGE and untrue of a commutative key. `commutes` needed NO
  CHANGE to say so, which is what says the condition was right all along. The licence publishes
  as a SINGLETON — #{:found} beside #{:eval :test} — so one lookup serves both kinds. MEASURED:
  two 300ms reports went 613ms to 305ms.
- WHAT IT DOES NOT FIX, said out loud: the READ half of Bernstein, no combine repairing a stale
  patch. The way to a CONCURRENT accumulation is therefore a combine INSTEAD of a view — answer
  from the event alone and let the node say how it lands. And it does not lift the `only ever two
  in flight` limit, which is the diamond's and not the merge's.
- THE HONEST CAUTION: MOST DOMAIN MERGES ARE NOT COMMUTATIVE, AND THE AUTHOR WILL NOT NOTICE.
  Ties, timestamps, last-writer and provenance all break the law invisibly, and both of the first
  two combines written here were refuted by generation — see :what-the-combine-taught and
  :what-the-fan-out-licence-taught. So the licence widens less than it sounds.


## :the-caller-owns-the-lifecycle

The author asked who owns an instance: the CALLER, reducing over a seq of events, or a REACTIVE
machine taking an event stream and answering a stream of states. THE QUESTION DISSOLVED.
- THEY ARE THE SAME OWNERSHIP, and reading `pump` is what settles it: the state lives in a d/loop
  ACCUMULATOR exactly as it lives in reduce's. There is no cell holding it and no object; the
  atom `fan` keeps holds per-instance STREAMS and never a state. So the reactive machine is not a
  second design, it is the same reduction with the loop shipped — and `run` is A CALLER THIS
  LIBRARY SHIPS.
- SO THE FACADE NAMES BOTH AND CHOOSES NEITHER, which is not a fence-sit: the reduction is the
  README's own headline sentence, and the step is what a caller with core.async or a transducer
  or a plain fold needs.
- WHAT REACTIVE-ONLY WOULD HAVE COST, said out loud because it was the tempting answer: manifold
  would then be on the ONLY path there is, and the one rule the batteries have is that it must
  not be.
- IT IS A PROPERTY AND NOT A SPEECH. `the-two-doors-agree` generates a shape and a seq of events
  and asserts that (map :state) off the stream equals the states the reduction passes through.
  That is the only thing that can refute any of the above.


## :the-facade-is-a-vocabulary-and-two-doors

TEN FUNCTIONS: state, event, transition, shape to build a machine; problems, draw! and dot to look
at it; compile and initial for the reduction; run for the stream. The author asked for the fewest,
so each collapse was argued for rather than assumed.
- `dot` ARRIVED FROM A CONSUMER, which is the only good reason to widen an API: it answers the
  drawing as DATA where `draw!` is the drawing as an effect, and the notebook could not be
  written without it. `draw!` alone cannot serve a renderer that is not graphviz.
- ONE STREAM DOOR AND NOT TWO. `fan` already subsumes `drive` — one partition IS one machine — so
  `run` builds the initial-of function out of the shape and a caller never spells :instance.
  `drive` stays public in .async for somebody who has already partitioned.
  THE WART, ACCEPTED: fan's :done is a MAP keyed by instance, so a caller who named nothing finds
  their machine under nil. A vector would have made the caller guess whose entry was whose.
- `problems` IS OPT-IN AND `shape` DOES NOT RUN IT. Fewest-functions argued for a strict
  constructor and no `problems` at all, and it is wrong for one decisive reason: A SHAPE YOU
  CANNOT BUILD IS A SHAPE YOU CANNOT DRAW, and the whole argument for this library is that a
  half-finished machine is worth looking at.
- RE-EXPORTS ARE DELEGATING defns AND NOT def ALIASES, measured rather than assumed — an alias
  skipped its guard entirely, see :what-the-facade-taught. And they carry NO :malli/schema of
  their own: the contract belongs to the namespace that owns the function, one declaration and
  not two. `run` is the one function the facade really adds, so it is the one that has a schema.
- THE FACADE REQUIRES `check`, breaking the property :layering claimed for it. Taken knowingly:
  it is a load-time cost paid by a require and never by a step, the checks and the drawing are
  the reason the library exists, and a caller who minds requires .compile directly.
- TWO FEATURES ADDED NOTHING HERE, which is worth recording as a check on the surface: NESTING
  and the COMPLETION TRANSITION are both OPTIONS ON `state`. A feature that needs no new door is
  a feature that fitted. The licence did not either — `commuting` stays in `check` beside
  `subsumption`, `views`, `coverage` and `confluence`, none of which is on the facade.
- WHAT WAS TURNED DOWN: a `fold` doing the whole reduction in one call. It gives strictly LESS
  than `compile` — a step goes in a transducer and a fold does not — while hiding the thing the
  README names as a feature.


## :the-output-is-a-transition-and-not-a-state

`run` puts a RESULT on :states and not a bare state: the :event, the :state it produced, whether
it :fired, and :instance where there is one.
- THE ARGUMENT IS THAT THE CALLER STORES NOW. A state does not say what caused it, and an event
  nobody handled produces a state EQUAL to the one before it — so from a stream of states alone
  no consumer can build the history this library has declined to keep. A result reads back down
  with (map :state); the other direction does not exist.
- THE OBJECTION THAT KILLED A RICH RETURN FOR THE STEP DOES NOT APPLY TO A STREAM, which is why
  this is consistent with :how-the-step-says-a-thing-was-ignored rather than a reversal of it.
  That entry refused an outcome value because (reduce step init events) must answer STATES. A
  STREAM IS NOT AN ACCUMULATOR: `pump` holds the state itself and what it PUTS is free to be
  richer.
- :fired NEEDS A LOOKUP AND NOT A COMPARISON — an ignored event answers the state unchanged, and
  a fired self-loop whose handler answers {} answers a state identical? to the old one. Hence
  compile/admits?, the step's own lookup published, over ONE private `entry` that both it and the
  step call, so the two cannot drift.
- HOW IT REACHES THE STREAM WITHOUT PUTTING A SHAPE UNDER async: a third injected function,
  `result`, defaulting to (fn [_ _ state] state) — exactly what the layer put before, so every
  existing drive test was untouched.
- :instance IS DERIVED FROM THE STATE and not read off the event, so there is one source for it.
- THE COST: :states is no longer a stream of states, so a consumer who wants only states writes
  (map :state). That is the cheaper half of the trade, paid by the consumer who needs less.


## :a-machine-can-nest-in-a-node

A node may carry {:machine <a shape>}, and while the parent sits there that child runs inside it.
Nine states in one graph is about where one graph stops being readable; nesting keeps every
machine the size a person can hold.
- IT DOES NOT BREAK :a-handler-never-sees-the-state, and this is the whole reason it was cheap.
  That rule constrains HANDLERS. The step is state-dependent all over already, so A CHILD'S STEP
  BELONGS TO THE COMPILER, exactly as :id does.
- INNER FIRST. The child gets every event before the node's own edges do, so the parent's edges
  are the ESCAPE. The consequence is the mechanism: THE CHILD'S OWN VOCABULARY DECIDES WHO
  HANDLES AN EVENT — :authorize is the payment's word and the order never sees it; :cancel is
  not, so it escapes at once. Nothing had to be declared for that.
- A FINISHED CHILD STOPS COMPETING, and this is what makes nesting cost the design nothing. A
  final state admits nothing, so once the child is done every later event falls straight through
  to the parent. NO GUARDS, no done-event, no internal queue and no run-to-completion — v1's own
  constraints turned out to give correct hierarchical semantics rather than standing in their way.
- :sub IS MACHINERY'S, like :id and :instance. Seeded when the node is entered, DROPPED when it is
  left — a merge keeps every key, so a child left behind would ride into a state that never
  declared it — and RESTARTED when the node is re-entered, entering being entering.
- A CHILD MUST BE ABLE TO START, checked at construction: entering a node with a machine enters
  the child at whatever the node SOWS, so a child whose first state insists on data a seedless node
  cannot give it could never begin. :machine-cannot-start, and it is REFERENTIAL.
- A NODE MAY SOW ITS CHILD, {:seed <a map schema>}, ADDED 2026-09-05 and it is :yield'S MIRROR —
  one carries parent -> child at ENTRY, the other child -> parent at COMPLETION. IT WAS NOT A
  DECISION THAT DATA MUST NOT FLOW IN; there was simply nothing to carry it, `:yield` itself having
  arrived late as `the {:yield} that dropping :sub had cost`. The consequence had been written down
  as a check and read since as a principle, which is the mistake worth naming: `the library does not
  do this` and `this must not be done` are different sentences.
  WHAT IT UNBLOCKS IS RE-ENTRY WITH A DIFFERENT JOB. A shape is a function of its env, so a machine
  told what to do by the closure it was built from is told once, for the life of the shape — and a
  host that cannot tell its child what job to do cannot LOOP over it. That is the whole of what
  ../coder's polish machine needed, and it could not be got any other way.
  SOWN OFF THE PROJECTED VALUE and not off the merge in flight, which is what keeps the check local
  and SOUND: a node holds exactly what it declares, so `seeds` asks whether THIS node's schema
  guarantees the seed and gets a proof. Sowing out of the pre-projection value would have let a key
  three transitions back reach a child no state on the way admitted holding — the same unsoundness
  :internal-visibility-is-declared-and-not-automatic removed from views.
  `seeds` IS `admits` FOR THE FIFTH AND SIXTH TIME, and it is TWO verdicts because a seam has two
  sides: :provides (can this node give it) and :accepts (will the child take it), reported as
  :seed-unavailable and :seed-refused. The referential :machine-cannot-start stays for the seedless
  case, subsumption not being answerable from parts alone.
  AND `:first` LEFT THE COMPILED PHASES. A child's initial state used to be computed once per
  machine; it is now a function of the RUN, so `arrive` makes it per entry through `sown`. Nothing
  was being discovered there that construction does not discover earlier.
- THE CHECKS RECURSE FOR FREE because a child is an ordinary shape and every structural check is
  about ONE graph. Faults are reported :within [<host node> ...], a PATH because nesting nests.
  And there is no cross-boundary subsumption question at all: the child's slice is written only by
  the child's step.
- NESTING CANNOT BE CIRCULAR and needs no check to say so: a shape is an immutable value built out
  of already-built children, so none can contain itself.
- AN ESCAPE IS UNCONDITIONAL, and the reasoning is still exactly right about EVENTS: nothing stops
  the parent leaving while the child is half done, and a guard would not change it, `the child has
  finished` being a fact about the STATE. Making the parent's EDGES wait for a final child was
  turned down deliberately, since it would have made ABORT inexpressible, and abort is the
  commoner need. THE DOOR IS NOW OPEN FROM THE OTHER SIDE: {:machine sh :done :shipped} is a
  SECOND way out that fires when the child finishes, so abort stayed expressible and a parent can
  now WAIT. See :a-state-may-say-where-it-goes-when-it-completes, which also brought the {:yield}
  that dropping :sub had cost.


## :a-node-is-labelled-by-its-id

DECIDED 2026-09-02, at the author's asking — `should not each state just be represented by the
:id?` — and the answer is yes, on this library's OWN argument for drawing at all.
- THE ARGUMENT THAT SETTLES IT is in :what-the-graph-buys: an unreachable state is obvious in a
  picture and INVISIBLE IN A MAP LITERAL. That is entirely about STRUCTURE — and a schema is
  precisely the part of a shape a map literal DOES show. So the schema was the least useful thing
  in the label, and the only thing that did not scale.
- MEASURED, on the first real consumer: ../coder's workflow inlines a vocabulary into eight
  states. Twelve labels, the longest 1,183 CHARACTERS, and a dot source of 10,408. `dot -Tpng`
  printed `graph is too large for cairo-renderer bitmaps`, scaled, and then wrote a ZERO-BYTE
  FILE — a warning that looks survivable and is not. CHECK THE FILE AND NOT THE EXIT CODE. SVG
  rendered the same graph fine, which made it look like a graphviz quirk rather than a label
  problem. AFTER: 1,007 characters of dot and a 120KB PNG.
- WHAT WAS KEPT AND WHY: ▸ for initial, ◼ for final, ⊞ n states for a nesting node, and a guard
  on an arrow. ALL ARE STRUCTURAL, which is the test this decision applies to anything wanting
  into a label.
- WHAT WAS NOT BUILT: an option to put the schema back. Nobody has asked for it, the shape is
  right there to read, and `problems` answers what the schemas IMPLY better than a picture of
  them ever did.


## :nothing-is-persisted-here

DECIDED 2026-09-01, by the author. THIS LIBRARY STORES NOTHING: it outputs what happened, and what
becomes of that is the caller's. It AMENDS THE README rather than merely contradicting it, because
:source-of-truth would otherwise make this file the wrong one.
- WHAT WENT: datahike left deps.edn, where it had been a dependency nothing used;
  robertluo.state-graph.store left :layering, never having been built; and the README's
  persistence feature now says what the library does instead. Its `audition` and `trace`
  sub-bullets STAYED, because those are still what the output is FOR.
- WHAT IT COST, and it is the one thing this decision broke: an audit trail must know which event
  produced which state, and a stream of bare states cannot say. That is what forced
  :the-output-is-a-transition-and-not-a-state, which is this same decision seen from the output
  end.
- AND WHAT IT DID NOT COST: :what-is-persisted's argument is untouched and is now advice for
  whoever writes the store, outside this library.


---

## :a-published-check-answers-about-the-machine

The author's, 2026-09-04, on the two things the crank had to work around: *"the 2 findings
look like state-graph library's gaps"*. They were, and looking properly found a third.

### The measurement

One shape, one child, one proven fault inside the child:

| asked of the host | answer | the truth inside |
| --- | --- | --- |
| `problems` | `[{… :within [:inner]}]` | recurses |
| `readings` | `()` | `{:verdict :no}` |
| `confluence` | `()` | 1 pair |
| `coverage` | `()` | 1 |
| `views` | `()` | — |

`problems` has recursed since nesting landed — it maps itself over `shape/machines` and
prefixes `:within`. **Nothing else did.** So the same question got two answers depending on
which door you asked through, silently, and the one that told the truth was the one that
reports *complaints* rather than the one that reports *coverage*. A library whose position is
"could this ever have worked" had its complete answers only on the complaint path.

### Where the line goes

Not every check can recurse, and the reason is the answer's type:

- a check answering **maps** carries `:within` and recurses — `subsumption`, `views`,
  `readings`, `yields`, `coverage`, `confluence`, `laws`, `driving`
- a check answering a **set of ids** — `reachable`, `traps`, `dead-ends`, `finishable` — is
  about ONE graph and stays there. Two machines may name a state `:done`, and a set has
  nowhere to say which one it meant.

`problems` bridges the two by recursing itself, which is what it already did. The one change it
needed was to take only its **own** answers from the four checks it derives faults from —
otherwise every nested fault is reported twice, once from the child's verdict and once from the
recursion. That is asserted.

### The licence is one machine's, and this is not tidiness

`commuting` is `confluence` reduced to `{state-id #{#{a b}}}`, and `run` hands it to the async
layer as the LICENCE. It is a lookup **keyed by state id**. If `confluence` recursing had been
allowed to reach it, a child's pair would merge into a parent state that happens to share its
name, and the runtime would take a concurrency nothing proved. A wrong `:yes` there is an
order-dependent flake — the exact failure `:what-nesting-taught` records as having been live
once before.

So `commuting` filters to the outermost, and the crank's own confluence lookup filters the same
way, asking each *level* about its own shape. This is the third time this project has learned
that a new capability is not local, and the second time the place to look was a check that
reasoned about concurrency.

### `driving`: the static half of `awaiting`

The crank discovers at runtime that a state offers two reportable events it cannot choose
between, and parks. Nothing said so beforehand — `problems` calls such a shape fine. For a
library whose whole argument is that a graph can be checked before it runs, that is a hole in
the middle of the newest door.

`check/driving` is one verdict per state, recursing with `:within`:

| verdict | meaning |
| --- | --- |
| `:final` | nobody is asked anything |
| `:driver` | exactly one event here carries a `:report` |
| `:world` | none does — a park, and a legitimate one |
| `:join` | several do, and every pair among them is proven confluent |
| `:fork` | several do, and the shape does not prove it — **a driver must stop here** |

`:fork` is the one worth looking for: a shape that will park for ever at a state you meant to be
automatic. It is **published and never faulted**, and the line is worth stating because
`:ambiguous` went the other way. Two guards on one `[from event]` is nondeterminism *in the
machine*, so it is refused. Two reportable events is a question about *who produces an event*;
the machine is deterministic either way, and a shape may perfectly well want the world to
choose. What would be wrong is a driver choosing for it, and that is what the crank refuses.

A generative property asserts that `driving`'s verdict and `awaiting`'s answer agree for every
state a driven run lands in. Neither is derived from the other, so it is the only thing that can
catch one drifting from the other — and a static check nobody can rely on is a static check
nobody runs.


## :the-crank-is-the-door-report-was-missing

The author's, 2026-09-04, and the second time they had said it: *"again, `drive` and `step`, if
you have to live with them, add them to the state-graph api"* — after watching a driver be
written in a consumer for the second time, and immediately after *"park is a general ability,
not something every workflow needs to implement by itself"*.

### What was actually wrong

`:an-event-may-say-how-it-is-reported` added `{:report :reads}` and stopped there, on the
argument that this is *"the shape telling a caller HOW an event would be found, and a caller
choosing to ask"*. That argument is still right about the SEMANTICS — the machine does not move
itself, there is no queue, and `:a-handler-causes-nothing` is untouched. It was wrong about the
SURFACE. A declaration nothing in the library consumes is half a feature, and the half that was
missing turned out to be the same forty lines in every consumer:

```
what does this state await
which of those did the shape give a :report
what view does that report read
run it, put the event id on, apply it
go round
```

None of that is an application's. It is `:report`, `:done`, nesting and `confluence` — all of
them this library's own concepts — and the way we found out is that it got written twice.

### Why the count-what-is-awaited rule was wrong

The first driver's rule was: one out-edge and it drives, none and it is final, several and the
world chooses. That is wrong the moment a state offers a driver's event *beside* a person's
escape — an interruptible step, or a state a human may abandon. It awaits two, one of them is
reportable, and the run stopped dead. Measured on a shape whose `problems` was `[]`.

The rule is **how many can be REPORTED**, and `awaiting` is that rule as one value:

| answer | meaning |
| --- | --- |
| `{:final true}` | over |
| `{:from :world}` | the SHAPE's park — an event only a person can supply, the same on every run |
| `{:held true}` | the CALLER's park — this run's choice, and it moves no fingerprint |
| `{:from :driver :event e}` | go and find it out |
| `{:from :driver :events [a b]}` | a join, proven either way round |

The two parks being different is the whole of why supervision is general: `:held` is not in the
graph, so a workflow watched and a workflow left alone are the same machine.

### Nesting: `compile` was complete and discovery was not

Worth stating precisely, because the asymmetry is surprising. `compile` handles a nested child
completely — an event applied to a parent whose child is live routes inward, the child's own
vocabulary decides, and when the child reaches a final state the completion transition fires and
the `:yield` is harvested, all in one step. Verified by hand before the crank was written.

DISCOVERY is what was missing. A nesting node has no edge for its child's events — the child's
catalogue is the child's — so a driver reading the host's out-edges sees a node that awaits
nothing and is not final, and parks for ever on a machine that was ready to go. So the crank
follows `:sub` as deep as it goes and asks the innermost machine first, which is `inner first`
in the one place it had not yet been applied. `:within` on the answer is the path of hosts, and
`:on` carries it too, so a history can say where inside a machine something happened.

### The join, and a static check finally being load-bearing here

Two reportable events out of one state is a fork. A driver that picked one would be inventing an
order the shape never promised — **unless the shape has proved the order cannot be observed**,
which is exactly what `check/confluence` answers and exactly what the product construction of
`:a-join-is-the-product-and-the-licence` produces. So:

- every distinct pair among the reportable events is `:yes` in `confluence` → take them all, in
  one turn, through `:reports`, applied in a fixed order the shape has said makes no difference
- anything else → `:from :world`, and the caller settles it

A pair of the SAME event is not asked about; nothing is being chosen between. This is the second
place a static check is load-bearing at runtime — `run` taking the licence was the first — and it
is the answer to *"can the two writers start in parallel"*: the shape says whether they may, and
`:reports` is where a caller puts the concurrency. The library stays synchronous and depends on
no stream library at this layer.

### What it cost

Two functions on the facade, which had absorbed nesting, the completion transition and the
licence without gaining one. That is a real cost and the alternative was worse: every consumer
owning a copy of a loop that is about shapes. Measured twice before it was moved.

The consumer shrank by 160 lines and **no longer requires the facade at all** — it needs
`shape/fingerprint` to stamp a transcript row and nothing else. What is left of its driver is an
options map: a fingerprinted `:on` and a loud `:ignored`. That is the part that was ever about
that application, and it is four lines.

### Not taken

- **A concurrent crank.** `:reports` is an injection, so the library neither depends on manifold
  at this layer nor decides how many threads anybody has. The concurrent door is still `run`.
- **A budget, a retry limit, a give-up rule in `drive`.** Those are EDGES, where they can be
  drawn and checked. `drive` needs no counter because the stopping rule is in the shape — which
  is the only reason a loop belongs in a library at all, and it is why the consumer's `drive`
  was correctly deleted the first time and correctly restored now that it presumes nothing.


# Open questions


## MAY A STATE COMPLETE ON A CONDITION OVER ITS OWN DATA?

MAY A STATE COMPLETE ON A CONDITION OVER ITS OWN DATA? The one door three separate wants knock
on: `all n reports are in`, `k branches have arrived`, `still under budget`. Each is a COUNT or a
COMPARISON over what the state holds, so each is a guard over the state, which
:a-guard-is-a-schema-over-the-event refuses.
WHY IT IS NOT MERELY THAT REFUSAL AGAIN: :done already reads a fact about the state — is the
child final — and was allowed because completion is STRUCTURAL and has ONE unconditional target.
So the line already drawn is `the shape may read a structural fact to decide COMPLETION, never to
decide WHICH WAY`. The question is whether a fact about DATA can join it.
ONE OF THE THREE IS ANSWERED AND NEEDED NO DOOR, measured in ../coder: `still under budget` is a
guard on a NUMERIC BOUND over a count the DRIVER reports on the event, and two bounds that do not
meet are provably disjoint. So a retry budget lives in the shape today, which is evidence the
door is needed less than three wants made it look.
THE OBSTRUCTION IS DECIDABILITY AND IT IS REAL. `(= expected (count reviews))` is a relation
between two keys and no malli schema expresses it, so such a condition can only be a CLOSURE —
and :a-combine-is-how-a-patch-lands drew that line explicitly: a combine may be a closure because
it decides what a VALUE is, a guard may not because it decides WHERE THE MACHINE GOES. So the
honest answer today is no, and the driver counts.
WHAT WOULD CHANGE IT is a decidable spelling. The one worth thinking about: a node holding a map
keyed by item, where the KEY SET is fixed on entry and completion is `every value is present` —
structural rather than arithmetic, and `every sub is final` wearing different clothes. The bar is
an agent workflow that needs it, and `review these seven files` plausibly is one.
NARROWED 2026-09-05 AND STILL OPEN. A `:done` keyed by the child's FINAL STATE was built, and it
is not this question answered — it is a case this question never contemplated. All three wants
above are relations over DATA and the refusal rests on decidability; which final state a child
stopped in is the STRUCTURAL fact `:done` already reads, over a finite set known at construction,
dispatched by a map lookup. So `which way` is now decided by a structural fact and still never by
a data one, and the line moved exactly as far as `no closure decides where the machine goes`
allows. See :a-state-may-say-where-it-goes-when-it-completes.
WHAT IT SUGGESTS ABOUT THE REST: the useful question may not be `may completion be conditional`
but `which facts are structural`. A count of arrived branches is not one today because nothing
in the shape names the arrivals; a spelling that made them nodes would make it one.


## IS DYNAMIC FAN-OUT WANTED?

IS DYNAMIC FAN-OUT WANTED? `foreach` — one child per element of a list discovered at runtime,
joined when all are done. MOSTLY ANSWERED 2026-09-03 by trying it: the ACCUMULATION was already
expressible and the CONCURRENCY needed one character, so what is LEFT is only the completion
test, which is the question above.
THE TWO STRUCTURAL ANSWERS STAY REFUSED, and the reasons are worth keeping: a LATTICE GENERATED
PER RUN makes a shape per run and breaks `a shape is code`; a MARKING is the token model, and
:what-the-review-scored records what it would cost the static checks. The width being the
driver's is not a gap: a graph shows structure and a count is data. Fan-out ACROSS INSTANCES is
what `run` already does, and nothing joins those back.


## ARE INTERNAL EVENTS WANTED AT ALL?

ARE INTERNAL EVENTS WANTED AT ALL? The handler's signature is unchanged in the meantime, so
nothing is blocked. The motivation is HANDLER REUSE and not cascades for their own sake; the lean
is that a state raises and a handler never does, for which the reasons are in
:a-handler-causes-nothing. NARROWED 2026-09-03 AND NOT ANSWERED: the lean was taken as a
DETERMINISTIC CONTINUATION inside one step rather than as an event, so the queue still does not
exist, and the commonest reason to want an internal event — `move on now that this is finished` —
is what a completion transition now is.
THREE THINGS TO SETTLE BEFORE ANY OF IT: whether the machine may drive itself at all or a caller
triggers the next event by hand — the latter costs nothing and hides the flow from `check`, which
is the whole trade; if it may, whether the queue drains breadth-first or depth-first, which is
OBSERVABLE in the history and cannot be left to whatever `into` happens to do; and how an audit
trail tells what the world did from what the machine did, because a log that conflates them is
worse than one without the internal events at all.


## IS THE NODE-SIDE EXPOSURE NEEDED, OR IS THE EVENT-SIDE VIEW ENOUGH?

IS THE NODE-SIDE EXPOSURE NEEDED, OR IS THE EVENT-SIDE VIEW ENOUGH? A view declared by the EVENT
is least privilege by the handler's own word: a careless or shared handler declares
{:sees [:map [:token :string]]} and is handed the token. The remedy is for the data owner to have
the say — the node declares what it EXPOSES, the event what it NEEDS, the check verifies the one
is within the other — and it is ADDITIVE, default deny on both sides being today's behaviour.
WHY IT WAS NOT BUILT WITH THE REST: projection already bounds visibility by ABSENCE, which is the
stronger guarantee and covers the case that matters most, a state that never held the secret
being unable to leak it. Exposure only helps where a state MUST hold something a handler in the
same machine must not read. Whether an agent workflow really has that shape is the question, and
a real one asking for it is the bar.


## IS THE Context's :ignored STILL EARNING ITS PLACE?

IS THE Context's :ignored STILL EARNING ITS PLACE? Its stated job was that the store layer would
replace it with one that records, and there is no store layer: the stream door reports a miss as
:fired false, taken from compile/admits? and not from any callback. What is left for it is a
caller who folds BY HAND and wants to hear about a miss — real, and possibly not worth a key in
the Context. If it goes, that caller closes over admits? themselves, `index` and `admits?` both
being public for exactly this. It is three lines of surface, and the bar for removing it is a
second reader asking what it is for.


## SHOULD A TRANSITION DECLARE ITS :effects AND :idempotence?

AND ONE THING WORTH STEALING, not built, from :what-the-review-scored: a transition declaring its
:effects and :idempotence. The licence proves REORDERING is safe and says nothing about
RE-EXECUTION. Harmless today, a speculative take never re-running a handler; retry and replay
would both need it, and it is the same class of declared-law-plus-checker as :combine/commutes.


---

# What running it taught


## :gaps-in-the-repository

Found by reading deps.edn against README.md, and each will bite on first use:
- `clojure -M:dev` DOES NOT START A REPL. The :dev alias is :extra-paths and :extra-deps with no
  :main-opts; the alias with the main-opts is :nrepl. It is `clojure -M:dev:nrepl`, and :dev is
  wanted or the test path and kaocha are not on the classpath. THE README SAYS THIS CORRECTLY.
- kaocha is in :dev and not in :test, so the runner is `clojure -M:dev:test`, never
  `clojure -M:test`. And clj-kondo is an ALIAS, not a binary: `clojure -M:lint --lint src test
  notebook`.
- tests.edn is two suites over one tree, separated by :kaocha.filter/skip-meta [:integration] and
  :kaocha.filter/focus-meta [:integration]. The default :kaocha/ns-patterns is ["-test$"],
  which IS the <ns>_test.clj convention, so it is not configured.
- CLOSED, and kept only so nobody re-reports them: manifold IS a dependency now, and datahike is
  NOT — it was one nothing used.


## :ubergraph-0-9-0

READ FROM THE SOURCE and then seen happening.
- AN UBERGRAPH IS A MAP TYPE BUT A CLOSED ONE, AND IT FAILS SILENTLY. (assoc g :anything v) hits
  a `case` with no default and returns `this` UNCHANGED; (dissoc g k) returns `this`; and
  (with-meta g m) returns `this` while (meta g) is hardcoded nil. Verified: (= g (assoc g :junk
  1)) is TRUE and (meta (with-meta g {:a 1})) is NIL. So there is no slot on a graph for anything
  that is not a node or an edge, which is what decided :the-event-catalogue-is-denormalised.
- IT IS = AND IT IS EDN, contrary to what was assumed before reading it: `equiv` is an
  equal-graphs? and ubergraph->edn / edn->ubergraph both exist. The round trip obviously cannot
  carry a handler fn or a compiled malli schema, which is a fact about OUR attributes.
- ATTRIBUTES are attr, attrs, add-attr(s), set-attrs, remove-attr(s), over a node OR an edge;
  add-attrs MERGES while set-attrs REPLACES. Weight is not special: it is the :weight attribute
  defaulting to 1.
- multidigraph is the constructor this project wants: two events joining one pair of states are
  two edges, and a plain digraph would keep one. node-with-attrs and edge-with-attrs answer values
  build-graph accepts back.
- out-edges ARE STORED IN A SET — node-info is {:out-edges {dest-id #{edge}} ...} at core.clj:284
  — so THERE IS NO EDGE ORDER TO RECOVER. That is what makes document-order first-match guards
  unrepresentable here; see :a-guard-is-a-schema-over-the-event.
- viz-graph ANSWERS NOTHING USEFUL. It threads the dot string through a cond-> whose branches are
  (#(spit filename %)), dj/save! and dj/show! — so the value is spit's nil for :format :dot, and
  the SOURCE is only ever written out. The way to it as a value is to hand :filename a
  java.io.StringWriter, `spit` accepting any java.io.Writer; that is what check/dot does. Its
  :auto-label is useless here too, pprinting the whole attribute map — which for us holds a
  COMPILED SCHEMA and a CLOSURE.
- GRAPHVIZ CLUSTERS ARE NOT REACHABLE THROUGH IT, so a nested child is not drawn inside its
  parent: viz-graph builds its own dorothy element list with no hook for a subgraph. The
  alternatives were copying ubergraph's private dotid and sanitize-attrs, or rewriting the child's
  dot to prefix every node id — a small and fragile compiler. Instead the parent MARKS the node
  and the child is asked for its own picture.


## :what-target-1-taught

VERIFIED BY RUNNING, and the malli findings are the ones that keep paying:
- MALLI NAMES THE WRONG SCHEMA FOR A MISSING KEY, and it cost a test. (:schema error) is the
  WHOLE ENCLOSING MAP when a key is absent, and the offending child only when a present value is
  wrong. (mu/get-in root (:path error)) is right in BOTH. The error also carries
  :type :malli.core/missing-key, which is the only thing telling a missing key from a key whose
  value is legitimately nil. That is what shape/explain does, and why it keeps the ROOT schema.
- MALLI HAS NO `IS THIS A SCHEMA` PREDICATE for the thing people actually write. m/schema? is
  true ONLY of a compiled schema and false for the form [:map [:n :int]]. So `Schema` and
  `MapSchema` are :fn predicates that simply CALL m/schema — which works because MALLI RUNS A :fn
  PREDICATE THROUGH ITS OWN -safe-pred, so the throw comes back as `false` and the try/catch
  belongs to malli rather than to us, which is what lets this honour :no-bare-try-catch.
  (m/schema x) on an already-compiled x is identical? to x, so it costs nothing on the common path.
- MALLI NORMALISES [:map] TO THE FORM :map, so a bare :map is a legitimate `any map` schema.
- MALLI MAPS ARE OPEN BY DEFAULT and only {:closed true} refuses an extra key. See
  :what-the-design-conversation-verified for what that decided.
- gen/let IN test.check 1.1.1 DOES NOT SUPPORT :let BINDINGS — the symbol does not resolve, and
  the failure arrives as `Unable to resolve symbol` from inside the generator. Use gen/bind and
  gen/fmap explicitly. And mg/sample TAKES {:size n} AS THE COUNT, not as test.check's generator
  size, which matters in a property looking for a counterexample.
- KAOCHA IGNORES A FOCUS-META NOBODY CARRIES: with no ^:integration test in the tree, the gate
  silently RUNS THE UNIT TESTS and a bare run does everything TWICE. Resolved once there was one.
- AN ARGUMENT THAT MUST ACCEPT RUBBISH KEEPS :any, and that is a decision rather than a gap.
  `problems` and `shape` take [:* :any] because they must ACCEPT a malformed part in order to
  REPORT it — a tighter schema would refuse it with ::m/invalid-input instead of the list of what
  is wrong, and only under instrumentation, so the diagnosis would be both worse and different
  between dev and production.


## :what-target-2-taught

VERIFIED BY RUNNING, building the checks and the drawing:
- viz-graph WITH :format :dot NEEDS NO GRAPHVIZ — that branch is a `spit` of the dorothy string
  and every OTHER format shells out. So the drawing is testable on a machine with no `dot`.
- THE SEVEN PRIMITIVE TYPES ARE PAIRWISE DISJOINT, checked and not assumed — every value of each
  validated against the other six, nothing overlapped, :int against :double included. That check
  is what licenses `admits` to answer :no from a type difference alone, and `disjoint` inherited it.
- alg/pre-traverse walks DIRECTED edges from a start node, which is what reachability wants.
- THE INSTRUMENT COUNT CAUGHT A STALE REPL, and this is why it is asserted: it still said 14 after
  `check` was written, because test-support's `namespaces` had been edited on disk and not
  reloaded, so nine new fns were never collected. The number is a smoke alarm for the fixture AND
  for the REPL.
- ASSERT THAT NO `$eval` REACHED A LABEL. A closure in a picture is the failure mode.


## :what-the-design-conversation-verified

VERIFIED BY RUNNING while settling design questions, before anything was written down. Both
decided a design rather than a target.
- MALLI MAPS ARE OPEN BY DEFAULT, which is what makes `able to apply, but wrong` SILENT rather
  than loud: a handler's answer merged into a state with no edge for that event would validate
  against that state's own schema while carrying keys it never declared. It is the reason the
  data is discarded on a miss rather than merged.
- A FIRED TRANSITION CAN RETURN AN IDENTICAL STATE, so identical? cannot signal `ignored`. For
  s = {:id :a :n 1}, all of (merge s {}), (assoc s :id :a) and (assoc (merge s {}) :id :a) are
  identical? to s — Clojure's map assoc answers `this` when the value is already there. Checked
  because it was about to be recommended as a free signal.


## :what-the-handler-move-taught

VERIFIED BY RUNNING, moving the handler onto the event:
- DENORMALISATION PAID FOR ITSELF, and this is the finding worth keeping. The move changed
  shape.clj AND NOTHING ELSE — compile.clj and check.clj needed not one edit, because
  `transitions` already flattens the catalogue onto every edge and both read a shape only through
  it. A READING LAYER BETWEEN THE GRAPH AND ITS CONSUMERS IS WHAT LETS A STRUCTURAL CHANGE STAY
  LOCAL, and it has now done so twice — see :what-completion-taught.
- A 2-ARITY DELEGATING TO A 3-ARITY BREAKS UNDER ITS OWN INSTRUMENTATION when the extra argument
  refuses nil, the delegation going through the INSTRUMENTED var. Loosening to [:maybe Instance]
  is NOT the fix: Instance is `some?`, and `some?` behind a :maybe admits every value there is.
  The fix is a private helper both arities call, which is not instrumented.
- CLOJURE'S OWN DEREFABLES TEST THE DEREF DECISION WITH NO MANIFOLD: a delay, a promise and a
  future are all clojure.lang.IDeref. The same trick later proved the Context composes across a
  nesting boundary.


## :what-the-async-layer-taught

VERIFIED BY RUNNING, building robertluo.state-graph.async:
- s/connect IS ASYNCHRONOUS, AND IT COST A LOST STATE. Giving each machine its own stream and
  s/connect-ing them into one output DROPPED whatever was still in a connect pipeline when the
  output was closed. Seen, not theorised: instance `a` ran three events and only two states came
  out. The fix removes connect — every machine writes STRAIGHT to the shared sink, so a machine's
  :done cannot resolve until its last state has been ACCEPTED there.
- A BOUNDED DEREF IS THE ONLY HONEST ONE IN A STREAM TEST, so a machine that hangs FAILS instead
  of hanging the suite. And A TEST THAT DEREFS :done BEFORE DRAINING :states HANGS: backpressure
  is real and the second result has nobody to take it.
- ONLY ONE TEST NEEDED A CLOCK. Serialisation is asserted with a handler that really is slower and
  is ^:integration; everything else uses immediate deferreds and is deterministic.
- MANIFOLD DRAGS IN slf4j-api WITH NO BINDING, so the suite prints three SLF4J NOP lines on
  stderr. Noise, not a fault, and worth knowing before someone hunts it.


## :what-the-facade-taught

VERIFIED BY RUNNING, building the facade:
- A def ALIAS BYPASSES malli INSTRUMENTATION, which is why every re-export is a delegating defn.
  mi/instrument! replaces the VAR's root binding, so a value captured by (def state shape/state)
  is the raw function for ever: handed a bad argument it answers happily where the var throws.
  Measured both ways in the same session.
- AND THE NEAR MISS THAT WOULD HAVE HIDDEN IT: the alias APPEARED guarded at its 2-arity, because
  a defn whose body calls ITSELF goes through the var, so the delegation landed in the
  instrumented wrapper. Testing only the 2-arity would have licensed aliases everywhere.
- THE TWO DOORS AGREE, as a property rather than an example: for a generated shape and a generated
  event sequence, (map :state) off the stream equals the states the reduction passes through.
  Worth more than any number of examples, and the only thing that can refute
  :the-caller-owns-the-lifecycle.
- fan's :done RESOLVES {} WHERE NO EVENT EVER ARRIVED, which fell out of keying it by instance and
  is right: there is no machine until an event names one. The initial state is not a transition and
  never appears on :states either.
- THE FORMATTER AND THIS REPOSITORY DISAGREE about a prop/for-all body — a PostToolUse hook aligns
  it under the binding vector where every existing suite indents it four spaces. THE HOOK FIRES ON
  THE FILE-WRITING TOOLS AND NOT ON A SHELL HEREDOC, which is how the existing style was restored,
  and it is the workaround to reach for before the first edit rather than after.


## :confluence-was-measured-not-guessed

MEASURED over this project's own fixtures when the question was whether two events pending in one
state may be applied in completion order. `Commute by default` was proposed and the numbers
refused it.
- counter has ONE candidate pair, :set and :stop in :running, and it does NOT commute:
  set-then-stop lands :running then :done, while stop-then-set lands :done and then finds NO
  [done, set] edge, so the :set is silently discarded. trapped has one pair and it does not
  commute either. So ONE HUNDRED PER CENT of the candidate pairs that existed then fail
  confluence — wrong in every case there is, and wrong SILENTLY and ORDER-DEPENDENTLY.
- The finding is structural rather than a fixture accident: different events take you to different
  places, and that is what a state machine is FOR. WHAT IT DID NOT COVER is a JOIN, which is
  exactly what a commuting pair is and what those fixtures had none of — see
  :a-join-is-the-product-and-the-licence, where the first :yes was produced.


## :what-the-tutorial-taught

VERIFIED BY RUNNING, writing notebook/tutorial.clj — the first real CONSUMER of this API rather
than another test of it, which is why it found things the suites could not.
- kind/graphviz TAKES A VECTOR AND RENDERS IN THE BROWSER: (kind/graphviz [dot-string]), the
  value's FIRST element being the source, interpolated into a JS template literal that viz.js
  renders client-side. So a page full of this library's drawings needs NO graphviz installed to
  read. The one hazard is the template literal: a backtick in a node label would break it.
- THE DOT SOURCE WAS ONLY REACHABLE THROUGH A FILE, which is the GAP the tutorial found and the
  author closed the same day with check/dot. IT PAID TWICE: the notebook's helper went from eleven
  lines to four, and the graphviz-source test stopped needing a file, so it left the integration
  suite for the fast loop.
- `run` GIVES EVERY MACHINE THE SAME STARTING DATA, which async/fan does not — fan takes a
  function of the instance. Found by trying to write a pipeline whose initial state carried a
  per-manuscript title, and worked around by moving the title onto the event that STARTS the
  machine, which is better modelling anyway. Whether `run` should accept a function is the
  author's call.
- THE CROSS-INSTANCE INTERLEAVING IS VISIBLE AND IS NOT DETERMINISTIC, so the notebook says the
  ROW ORDER is not promised and shows the per-instance paths beside it, which are. A tutorial that
  asserted the interleaved order would flake.
- CLAY'S DEFAULTS, read from clay-default.edn: :base-target-path docs, :format [:html], :show and
  :browse true. `:render true` implies show, serve, browse and live-reload all false, which is what
  makes `clojure -X:notebook` headless. :exec-fn scicloj.clay.v2.api/make! with :exec-args is why
  the alias needs no build namespace.


## :what-the-parts-library-showed

MEASURED, checking the author's `most parts are shared, only the assembly differs` against the code
instead of agreeing with it.
- SHARING IS FREE AND COMPLETE. A vector of states and a vector of events, handlers and all,
  assemble into two different machines by concat plus different transitions, and both check clean.
  ONE CHILD SHAPE NESTS INTO TWO UNRELATED PARENTS with nothing to alias — a shape is an immutable
  value.
- BUT A SHARED CATALOGUE MUST BE SELECTED FROM AND NOT SPLATTED IN, and this bites on the first
  assembly that uses fewer events than the catalogue holds: :unused-event REFUSES the shape. The
  check is right — an event no transition fires IS dead code in that machine — so a parts library
  wants to be a MAP KEYED BY ID that each assembly selects from, never a vector to concat
  wholesale. Whoever builds the agent workflows should know that on day one rather than day three.


## :what-visibility-taught

VERIFIED BY RUNNING, building both halves of declared visibility:
- THE BREAKING CHANGE COST ONE TEST, and measuring it before recommending it is what settled a
  three-way design question that argument had not. Projecting the merge broke exactly one of 68
  tests: an async fixture whose state was a bare [:map] while its handler set :mark. Two others
  wanted the same correction on inspection — the `form` fixture wrote :name and :email into a
  state that declared neither, so the runtime had been quietly undoing what the fixture existed to
  demonstrate. THE GENERATIVE FIXTURES NEEDED NOTHING, which was the surprise: gen-shape's
  handlers all answer {}, so there was never anything to drop.
- AND THE SOUNDNESS DEPENDENCY RAN THE OTHER WAY FROM THE DESIGN, found by asking what the check
  would answer before building it: the READ half's check is only sound because the HOLD half
  exists. Reading was the interesting half and holding was the one that had to land first.
- AND IT BROKE A SOUNDNESS CLAIM TWO ENTRIES AWAY, which is the finding worth most here: adding a
  read made check/commutes UNSOUND, because it compared WRITE sets only. THE LESSON THIS PROJECT
  KEEPS RELEARNING: a new capability is not local, and the place to look is whatever OTHER check
  reasoned about what handlers could touch. Found by asking `does this actually solve the problem
  it was built for`, not by a test that already existed.


## :what-nesting-taught

VERIFIED BY RUNNING, building {:machine <a shape>}:
- THE SUBSUMPTION CHECK HAD TO LEARN ABOUT :sub, and until it did, nesting was broken in a way only
  the check could see: a nesting target's enter-schema REQUIRES :sub, which no handler may write
  and the step assocs on entry, so every edge into a nested node was condemned :target-refuses.
  Found one minute after it first worked. WHAT THE CHECK COMPOSES MUST BE WHAT compile COMPOSES,
  and every key the MACHINERY writes has to appear in both places or the check condemns correct
  shapes.
- THE ORDERING OF A NAMESPACE MATTERS MORE THAN IT LOOKS. `Shape` had to move ABOVE StateDef once
  a node could hold one, and the referential nesting check needs `enter-schema` and `initial-id`
  from the reading section below it — declared rather than moved, and deliberately not
  reimplemented: what the check asks has to be what runs.
- THE GENERATIVE PROPERTY EXTENDED WITHOUT AN ARGUMENT, which is a good sign for the design: nest
  one generated shape into a node of another and assert the parent lands in one of ITS nodes and
  the child in one of the CHILD'S. The two share an event vocabulary, so the child shadows the
  parent constantly, which is the interesting half rather than an accident.


## :what-seeding-and-outcomes-taught

VERIFIED BY BUILDING BOTH 2026-09-05, at the request of ../coder's polish machine — the first
consumer to want a nested machine it could LOOP over.
- THE LESSON IS NOT ABOUT NESTING, and it is the one worth keeping: I read `a nested child is
  entered with no data` and `a yield must hold at every final state` as facts about what nesting
  IS, and designed around them — a whole alternative in which the child machine was engaged
  through a function in the env instead. The author's correction was that these are
  IMPLEMENTATION LIMITS AND NOT PRINCIPLE LIMITS, and that letting one pick the design is the
  expensive mistake. Both turned out to be an absence rather than a decision: `:yield` had arrived
  late and nothing had ever carried the other direction, and the completion's single target was
  argued from DECIDABILITY, which says nothing about a finite set of node ids.
  THE TEST THAT SEPARATES THEM: find the sentence that REFUSED it. For a seed there was none —
  only a check recording the consequence. For a branching completion there was one, and reading it
  showed it was about data conditions and had not contemplated a structural one.
- THE TWO ARE ONE FEATURE IN PRACTICE and neither is much use alone. A seed lets a host RE-ENTER a
  child with a different job; per-outcome completion lets it tell what the child made of the last
  one. Without the second, polish had to read a `:fault`'s presence as a tea leaf and copy its good
  code aside under an invented key to stop a failed attempt overwriting it — a noun invented to
  route around a limit, which is exactly what the correction was about.
- WHAT THEY COST THE CHECKS: nothing structural, and the same reasons as ever. `seeds` is `admits`
  for the fifth and sixth time, `yields` got SHARPER, and one edge per outcome meant `reachable`,
  `dead-ends`, `finishable` and `traps` were told nothing.
- WHAT THEY COST AT RUNTIME: `phases` lost its precomputed `:first`, a child's initial state now
  being a function of the run. `arrive` takes the shape and makes it per entry.
- AND `covering` HAD A HOLE THE CHANGE OPENED, found by writing a consumer test that asserted a
  branch the tool could not see. A completion fires no event, so it was never scored — invisible
  while unconditional, and a silent gap once it could fork. See :cover-the-graph-by-running-it.
  THE TEMPTATION IS THE THING TO NOTE: the fix I nearly made was to weaken the assertion.
- MEASURED: 165 tests, 512 assertions, lint clean. In ../coder, 47 unit tests and 214 assertions
  over a machine that nests another, every branch of both driven by `covering` on `constantly`,
  with no model and no JVM.
- AND IT RAN LIVE THE SAME DAY, which is what says the two features are one: ../coder's
  notebook/beautiful_words.clj drove TWENTY TURNS through both machines in ONE run — a person's
  `:amend` reaching the nested machine, its own disputed branch firing inside the host, the host
  completing by `:implemented`, and the SAME NODE re-entered with a brief built from what the
  review found. None of that is expressible with a child that starts empty and a completion with
  one target.


## :what-guards-taught

VERIFIED BY RUNNING, building :a-guard-is-a-schema-over-the-event:
- `disjoint` COULD NOT LIVE BESIDE `admits`, which the design had assumed it would. The ambiguity
  check is REFERENTIAL — a shape whose determinism cannot be proven must not be CONSTRUCTIBLE, so
  it has to answer before the graph exists — and `check` sits above `shape`. So `primitive-types`
  and `entries-of` MOVED DOWN into shape. Subsumption and disjointness are siblings A LAYER APART,
  over one vocabulary. THE LESSON: where a check lives is decided by WHEN IT MUST ANSWER and not
  by what it resembles.
- `dis-map` NEVER ANSWERS :no, so the witness the design promised for :ambiguous is not there.
  Proving two MAP schemas OVERLAP needs a VALUE, and one shared key agreeing is not one — another
  key may still refuse. THE WITNESS DID LAND IN `coverage`, where the probe pins one key to one
  value so the value is in hand. Same idea, and it works only where something CONSTRUCTS the value.
- AND A HOLE THE DESIGN DID NOT SEE: A MALFORMED EVENT THAT FAILED EVERY GUARD LOOKED LIKE AN
  ORDINARY MISS, conflating a DEFECT with a legitimate miss. Fixed by conforming against the
  group's event schema when nothing matched; `candidates` is a separate reading for exactly this,
  so `entry` and the fall-through cannot come to disagree about whether there was an edge to refuse.
- THE TUTORIAL GAINED A SECTION, and writing it found two bugs the suite could not: both
  `problems` examples asked the FACADE, whose `problems` takes a BUILT shape — and :ambiguous is
  REFERENTIAL, so the constructor throws and there is no shape to ask about. The page now asks
  `shape/problems` of the PARTS. RENDERING THE NOTEBOOK IS WHAT CAUGHT IT.


## :what-the-phase-split-taught

VERIFIED BY RUNNING, splitting the step and taking the licence:
- THE LICENCE WAS UNSOUND AND NOTHING HAD NOTICED, found by ASKING rather than by a test. It was
  harmless for exactly as long as `drive` ignored it. THE HABIT WORTH KEEPING: before resting
  anything on a check, ask what it was reasoning about, because a check that is decorative is a
  check nobody has tested against reality. The detail is in :two-events-in-flight-at-once.
- THE SPLIT IS DECIDED BY WHAT EACH CROSSING DEPENDS ON, and the division came out exact: :event,
  :sees and :out belong to the PATCH half (an event either is what it says it is or is not,
  whatever state it meets; a view reads the state the handler SAW; :out is the event's own
  promise), while :answer and :enter belong to the APPLY half. :answer is the one that FORCED the
  split to exist — a patch-schema is the TARGET'S schema, and a licensed patch is applied where
  the target may be a DIFFERENT NODE from the one it was computed against.
- THE STEP IS DEFINED AS THE COMPOSITION and is asserted as a PROPERTY — patch-then-apply equals
  step, for any generated shape and any event, admitted or not. That is the only thing stopping
  the two doors drifting, and it cost three lines.
- AND IT TURNED :then FROM AN fmap INTO A BIND, which an existing test caught within a minute. The
  Context's words always said bind, but the old step used `then` exactly ONCE per call, so an fmap
  satisfied it and a test fixture was (fn [v f] (box (f v))). Two composed binds turn that into a
  container of a container. VERIFIED FIRST: d/chain flattens, flattens twice over, and chaining a
  deferred does not consume it.
- A PATCH HAS TO SAY WHOSE IT IS. :depth, and the two halves compare it against their own lookup —
  a child's patch may only be applied to that child. One comparison per level checks the whole
  descent. THE BUG THE SEAM CAUGHT WAS MY OWN, one layer up: async handed `apply` the patch
  DEFERRED rather than the patch, and the seam failed loudly at :depth instead of merging
  nonsense. Nine errors, all one cause.
- A SPECULATIVE TAKE IS FREE AND IS TAKEN ONLY WHERE IT PAYS. Start the handler, then reach for
  another event without waiting, and race the two: whatever the take brings is either the other
  half of a licensed pair or the next iteration's event, carried in `held`, so nothing is taken
  twice and nothing dropped. The reach is skipped where THIS state has no licensed pair, which is
  most states in most shapes.
- COMPLETION ORDER IS TESTABLE WITHOUT A CLOCK, which is better than the ^:integration test it
  replaces the need for: the first event's handler PARKS on a deferred the test resolves by hand,
  so the second can only land first. One timing test remains and is ^:integration, asserting the
  thing only a clock can.


## :what-the-combine-taught

VERIFIED BY RUNNING, building :a-combine-is-how-a-patch-lands:
- THE PROTOTYPE REFUTED MY OWN `SOUND` EXAMPLE, which is the finding that shaped the design.
  `best-of` written as (if (>= score-a score-b) a b) was offered as the correct version and
  generation broke it in forty samples: a TIE has no canonical winner. It took (compare [score by])
  — a TOTAL order — to make the law hold. IF THE PERSON PROPOSING THE MECHANISM GETS IT WRONG IN
  THE FIRST EXAMPLE, THE MECHANISM NEEDS A CHECKER AND NOT A DOCSTRING.
- AND THE LAW I TESTED FIRST WAS THE WRONG LAW. A function can be left-commutative in the fold and
  not commutative as a binary operation — the fold always puts the STATE first, so a rule that only
  ever discards the SECOND argument is consistent in both orders. Two of my three attempted
  counterexamples were not counterexamples for exactly that reason.
- GENERATION CANNOT REACH EVERY VIOLATION, and this is the number that decided the runtime check: a
  plausible domain rule — `a pinned choice wins outright` — is not left-commutative, and 27,000
  generated triples found nothing, malli having no reason to invent the string `pinned`. The
  special case must also be BEATABLE to violate anything, which cost two wrong examples before the
  right one.
- SO THE RUNTIME CHECK IS THE ENFORCEMENT AND THE GENERATIVE ONE IS THE DEVELOPMENT AID, each
  catching what the other cannot. The liar fixture is licensed by `commuting`, is NOT refuted by
  `laws`, and IS refused by :agree — all three asserted, because that combination is the whole
  argument for having the third.
- :agree HAD TO BECOME A PRE-CONDITION, which changed the async layer's shape: applying one patch
  and only then discovering the licence was invalid would emit a result derived from an unsound
  proof. So `pump` waits for BOTH patches before landing either — which costs nothing in
  wall-clock, the concurrency being in the HANDLERS and both already running, and only delays the
  FIRST result's row. AND IT BROKE THE TESTS THAT PROVED THE ORDERING, which is how the change
  announced itself: two gated tests read the first result and only then opened the gate, which
  under wait-for-both is a deadlock.
- MALLI KEEPS ARBITRARY ENTRY PROPERTIES and `mu/merge` carries them through, so {:combine f} on a
  map entry survives into `enter-schema` — checked before designing anything on it. m/children
  hands back [k props child], which `entries-of` already destructured and merely threw the props
  away.
- A `for` WHOSE BODY IS A `cond` PUTS nil IN `problems`, and every shape with a combine was refused
  with a vector of nils. `shape/problems` is a concat of a dozen comprehensions and the idiom there
  is :when, never a cond body.
- mg/sample TAKES A :seed AND HONOURS IT, so `laws` answers the same thing twice; unseeded it
  genuinely varies. An :fn schema with no :gen/gen throws :malli.generator/no-generator, which is
  malli's answer and not one to work around — `laws` documents it rather than swallowing it.
- `laws` IS DELIBERATELY NOT PART OF `problems`. `problems` is static, cheap and runs nothing;
  `laws` runs the author's own function a couple of thousand times. Mixing them would make
  `problems` a test runner. And `check` NOW REQUIRES malli.generator, so the facade loads
  test.check — already a :deps dependency, so nothing NEW is on the classpath; what changed is what
  is loaded.


## :what-the-review-scored

SCORED 2026-09-03 against the code and not against memory, when the author brought a DECLARATIVE
TRANSITION GRAPH proposal as a review of this architecture: transition fragments with declared
inputs/outputs/effects, a separate declarative assembly, and a TOKEN-FLOW runtime owning
scheduling, persistence, replay and cancellation. The first outside frame this design has been
held against, and two thirds of what it asked for turned out to be here already.
- THE ONE-LINE DIAGNOSIS: IT IS A DATAFLOW MODEL AND THIS IS A CONTROL-FLOW MODEL. There, a
  transition fires when its INPUTS ARE AVAILABLE — a build system, a Petri net, `make`. Here, one
  fires when AN EVENT ARRIVES AND THE STATE ADMITS IT. Nearly every difference falls out of that
  substitution, and it is why the proposal's worked example is a COMPILER PIPELINE: a closed system
  with no external cause. `Park until a human approves` has no dataflow spelling, there being no
  upstream node whose output is `the person clicked`. It is the INNER half of a workflow, and it is
  not wrong about that half.
- THE STATE-EXPLOSION ARGUMENT IS CORRECT AND WAS NOT NEWS: a join IS the product construction, 8
  states at n=3, measured two days earlier. THE COUNTER WORTH MAKING BACK is that a MARKING does
  not remove the explosion, it RELOCATES it — out of the shape, where it is drawable and statically
  checkable, into the runtime state, where it is neither. And there is a price the document never
  names: every static check here rests on ONE STATE BEING ONE MAP WITH ONE SCHEMA. Under a marked
  graph the state is a set of markings plus per-token payloads plus join buffers, and
  merge(from, out) ⊆ to loses its subject. It trades a decidable checker for a nicer picture.
- WHERE THIS LIBRARY IS ALREADY AHEAD, and it is the proposal's weakest section: its `essential
  constraint` is {:purity :effects :idempotence} — A DECLARATION THE ASSEMBLER TRUSTS. It promises
  `correct concurrency` and `type-checked` and names no mechanism for either. Here :sees declares
  reads, :out declares writes, :combine declares how a value lands, and then `commuting` PROVES the
  reorder by Bernstein, `laws` refutes a false combine by generation, and :agree re-verifies on the
  concrete values. A rule that lives only in a declaration is the repository's own named
  anti-pattern.
- THE OPERATOR TABLE, SCORED. Expressible: `then` (an edge), `choose` (a guard), `all`/`join-all`
  (the product lattice plus the licence), `recover` (an edge to a fault state). Expressible with
  the PRODUCER's help: `retry`, `join-quorum`, `join-any` and `foreach` — each needing a fact the
  driver reports. Partly: `scope` — cancellation is the unconditional escape and works, TIMEOUT IS
  THE CALLER'S CLOCK and is correct since an event is the only cause, and CLEANUP was missing.
  AND `foreach` SCORED WRONG at first, corrected by trying to build it, which is why the wrong
  score is recorded: it made the gap look STRUCTURAL when two thirds of it was a spelling.
- AND READING THE PARTIAL ROWS TOGETHER IS THE FINDING: they are ELEVEN WAYS OF WANTING ONE THING —
  a transition caused by the machine's own accumulated state rather than by the world. This library
  had refused that three times, each for a good and DIFFERENT reason, and each refusal left a named
  door. The review was the accumulated case for opening exactly one of them, which is what was
  built.
- WHAT WAS NOT ADOPTED AND WHY. `The runtime owns persistence, replay, observability` is a
  FRAMEWORK, and a runtime owning persistence has to own shape identity and versioning, which is
  the question v1 pushed out. And the ergonomic complaint — that a named `join-all` reads better
  than 8 states and 12 edges — is LEGITIMATE and belongs in the consumer.


## :what-completion-taught

VERIFIED BY RUNNING, building the completion transition:
- THE EDGE-OR-ATTRIBUTE QUESTION WAS THE WHOLE DESIGN, settled by counting what each way COSTS
  rather than by taste. As an edge, four traversals needed NOT ONE LINE; as an attribute, each
  would have had to learn about it or condemn correct shapes — a state reached only by completing
  would be :unreachable, and one whose only way out is completing would be a :dead-end. The cost of
  the edge was ONE `:when` in `shape/transitions`.
- AND `transitions` TURNED OUT TO BE THE SEAM AGAIN, for the second time: adding a whole new KIND
  of edge touched it and nothing else above it.
- THE MISTAKE I MADE IS THE ONE THIS FILE ALREADY RECORDED. :what-guards-taught says a notebook
  example must ask `shape/problems` OF THE PARTS for a REFERENTIAL fault. I wrote exactly that bug
  again for :done-cycle, and RENDERING THE NOTEBOOK caught it again. THE HABIT WORTH KEEPING IS THE
  RENDER, NOT THE MEMORY.
- THE LICENCE GUARD IS IMPLIED AND WAS KEPT ANYWAY, a deliberate exception to `only assert what can
  fail`: no shape `shape` will build can reach `commutes`'s completion refusal. But that argument
  SPANS TWO NAMESPACES and the licence is load-bearing, so the condition is stated where it is
  relied on, and the test asserts the fault that implies it.
- THE PASS-THROUGH PROPERTY IS THE ONE WORTH HAVING, and it is genuinely independent rather than
  the implementation restated: split one generated edge a -e-> b into a -e-> mid {:done b} and the
  reduction must end EXACTLY where it ended before. It compares two machines and recomputes nothing.
- A FIXTURE THAT CANNOT BE A CHILD: `shipping`'s own first state insists on a :total, and entering
  a child hands it NO DATA, so nesting it is :machine-cannot-start. A SHAPE WRITTEN TO BE A PARENT
  IS USUALLY NOT STARTABLE AS A CHILD.
- THE DRAWING WAS CHECKED AS A REAL PNG and not as dot source — dashed unlabelled arrows for the
  completions beside a solid labelled `cancel` for the abort, which is the distinction visible at a
  glance and the argument for drawing at all.
- AND THE FORMATTER HOOK REFLOWED A WHOLE SOURCE FILE on the first Edit, undoing hand-alignment in
  three entries nobody had touched. Reverted, and every edit after was done through the shell. Read
  :what-the-facade-taught BEFORE the first edit rather than after.


## :what-the-fan-out-licence-taught

VERIFIED BY RUNNING, taking the last thing :what-the-review-scored named as missing:
- A SET LITERAL OF TWO EQUAL EXPRESSIONS THROWS, and this is the finding worth most. `licensed?`
  asked (contains? pairs #{(:id a) (:id b)}), and with the two ids EQUAL that is `#{x x}` — which
  Clojure REFUSES at runtime with `Duplicate key:`, the reader form compiling to a construction
  that rejects duplicates. `hash-set` dedupes and `set` dedupes; ONLY THE #{} LITERAL throws.
  Verified all three. The throw landed inside a d/chain, so the machine did not crash — IT SIMPLY
  STOPPED, `done` never settled, `out` never closed, and the symptom was two timeouts and a nil. My
  own docstring had asserted `the encoding needed nothing` one edit earlier, which is what
  asserting-before-running buys you.
- THE ACCUMULATOR MUST BE A SET, AND `laws` REFUTED MY FIRST ATTEMPT IN FORTY SAMPLES. `into` on a
  VECTOR is order-dependent, so which worker reported first is visible in the answer — THE OBVIOUS
  SPELLING OF A JOIN ACCUMULATOR IS NOT COMMUTATIVE, which is :what-the-combine-taught's own
  caution landing on the person who wrote it. Set union works, and so does a map keyed by the item.
  Both are now fixtures, and the trap is in the README and the tutorial because everyone meets it
  first.
- `commutes` NEEDED NO CHANGE, which is the check on whether the widening was principled: with
  a = b the two events share a handler, an :out and a target, so ta = tb, the diamond closes
  wherever the target admits the event again, and the write-write filter covers every key the :out
  writes. The whole change was `(neg? (compare a b))` becoming `(not (pos? ...))`.
- AN EVENT THAT WRITES NOTHING COMMUTES WITH ITSELF, which fell out rather than being arranged —
  the write-write filter is empty, so the pair is vacuously licensed, and two empty patches leave
  the same state in either order.
- THE DIAGONAL CHANGED EIGHT TESTS AND EVERY NEW ROW WAS CORRECT ON INSPECTION: a join's arms are
  all :no (one :eval takes you somewhere that does not admit a second, which is the OPPOSITE of a
  fan-out), a no-combine self-pair is :unknown, and one fixture turned out to have been refusing its
  own same-id concurrency all along.


## :what-the-first-consumer-migration-taught

MEASURED 2026-09-03 by migrating ../coder off its workaround at the author's instruction — `remove
the current trick; use state-graph vocabulary only`. THE FIRST TIME anything built after the facade
has had a consumer, so it is the first outside evidence about the VOCABULARY rather than about the
mechanisms.
- WHAT IT VALIDATED, none of it needing a change here: guards on an enum tag, guards on NUMERIC
  BOUNDS, `coverage`, the refusal of a shape whose guards are not provably disjoint, and the Harel
  drawing with a :description. coder's shape went from 5 states / 5 events to 6 / 4, `problems`
  stayed empty, and src did not change by one line — which is the check that its driver was about
  DRIVING and not about that task's five states.
- THE :out IS THE EVENT'S AND THAT IS THE REAL FRICTION. coder's :judged leads to two targets
  needing DIFFERENT data — :fault requires a fault string, :implemented does not — and one :out
  serves both edges, so it must be weak enough for the green one and then cannot prove the red one.
  :target-refuses, on a correct shape. THE WAY OUT coder took is to declare NO :out on the guarded
  event and let the runtime :answer and :enter crossings enforce it; `subsumption` then answers
  :undeclared, which is coverage rather than a fault. THE ALTERNATIVE WAS WORSE AND IS WORTH
  NAMING: weakening the target's own schema to {:optional true} buys :yes back and is weakening a
  schema to please a checker.
- AND THE `attempts > 3` CASE WORKS, which the guard design predicted and nothing had tried:
  coder's retry budget is two guarded edges on :round with [:int {:max 8}] and [:int {:min 9}] —
  provably disjoint — so the stopping rule is in the shape and the driving loop needs no counter.
  IT NEEDED NO NEW DOOR: the driver reports the round as a FACT on the event and the guard reads it.
- AND A `--reset-session` KILLED AN nREPL. The session did not reset, the server went away, and the
  next eval failed inside the client's socket code, which reads like a bug in the tool rather than
  a dead server.


## :graphviz-and-the-devenv

pkgs.graphviz is in ../devenv.nix, because a drawing nobody can look at is not worth having.
graphviz 15.1.0; `dot` was NOT on the path before, and the devenv is shared.
- TWO TESTS, TWO REQUIREMENTS, and the split is deliberate. :format :dot is a spit and needs
  NOTHING. :format :png shells out, and that test is the only thing proving the RENDERING path —
  asserted on the PNG MAGIC BYTES (0x89 P N G), because a file existing proves only that something
  wrote one. Verified BOTH ways: outside the devenv the render test errors and the source test
  passes; inside, both pass. AND `dot` CAN WRITE A ZERO-BYTE FILE AND EXIT 0 on an oversized graph
  — see :a-node-is-labelled-by-its-id — so check the file and not the exit code.
- TO LOOK AT A SHAPE: (check/draw! sh) with no :save opens a viewer window; with
  {:save {:filename f :format :png}} it writes a file. Verified live: the `broken` fixture's
  island — two states reaching only each other — sits VISIBLY DETACHED from everything else. That
  picture is the argument for the library, and it is the thing a map literal cannot show.


## :dependency-notes

What each dependency is here FOR, so nobody reaches for the wrong one:
- ubergraph 0.9.0 — the shape. Multigraph and digraph in one library, attributes on nodes and
  edges, viz-graph for drawing. See :ubergraph-0-9-0 for its traps, of which there are several.
- manifold 0.4.3 — the async default, and nothing below that layer requires it. Its Deferred is a
  clojure.lang.IDeref, which is what lets the pure core deref one without depending on manifold;
  d/chain takes a plain value as happily as a deferred and FLATTENS; and s/connect is ASYNCHRONOUS,
  which cost a lost state once. It drags in slf4j-api with no binding.
- malli 0.20.1 — the shapes of states, events and every function signature. See the :reload-all
  rule; it is the one dependency that punishes a careless REPL.
- test.check 1.1.1 — in :deps and not :dev on purpose: generative tests are the unit suite here,
  not an extra. `check` loads it through malli.generator, so the facade does too.
- clay 2.0.22 — the tutorial, in the :notebook ALIAS and not in :deps: a library does not depend on
  the thing that documents it. It drags kindly in, which is where kind/graphviz comes from.


## :from-the-sibling-project

smart-boundary/AGENTS.md, in GIT HISTORY, was the same author's larger project, removed
2026-09-02. WHAT TRANSFERS IS METHOD, NOT FACT: schemas at every crossing, seams checked in the
code and not merely declared, `only assert what can fail`, and a knowledge section written in the
past tense about things actually observed. What does NOT transfer is any of its content — it is
about Anthropic's API, Datalevin, nREPL-as-a-map and LLM agents, none of which this library has.
Its living descendants are ../coder/AGENTS.md and ../llm-function/AGENTS.md.

