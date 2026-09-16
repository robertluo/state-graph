(ns robertluo.state-graph.compile
  "shape -> (fn [state event] state'). The only namespace that turns data into a
   function.

   The lifecycle of an instance is (reduce (compile shape) (initial shape data) events)
   and that is the whole runtime: no object, no atom, no protocol. Everything above
   this is a way of getting events into that reduction or results out of it — which is
   why a stream is a layer above and not the core. Such a layer takes the STEP FUNCTION
   as a value, so it requires neither this namespace nor a shape.

   Requires the shape and malli. It knows nothing of streams or databases."
  {:knowledge
   [{:id :compilation-and-lifecycle
     :kind :decision
     :says "(compile shape) answers a pure function of a state and an event, and the lifecycle of an instance is (reduce step initial events). That is the whole runtime: no object, no atom, no protocol. Everything else in the library is a way of getting events into that reduction or results out of it."
     :why "It is why a stream is a layer above and not the core, and why `run` is a caller this library ships rather than a second kind of machine."
     :cites [:the-shape-is-a-graph]}
    {:id :the-first-target
     :kind :lesson
     :says "The first target was the SPINE — shape and compile, ending in a working reduction — and deliberately not the static checks, though they are the differentiator. A check written over a shape that nothing has ever run is a check over a shape that is probably wrong; `compile` is the cheapest thing that can say whether the shape is expressive enough."
     :cites [:compilation-and-lifecycle]}
    {:id :inject-a-function-and-never-thread-options
     :kind :rule
     :says "Do not thread options through layers this library does not own — inject a function that closes over them. The Context is the pattern: the compiler is parameterised by HOW A VALUE BECOMES AVAILABLE and never learns what a deferred is; the async layer is handed a compiled step and never learns what a shape is."
     :see [:robertluo.state-graph.compile/Context]}
    {:id :inner-first
     :kind :decision
     :says "A nested child gets every event BEFORE the node's own edges do, so the parent's edges are the ESCAPE and the child's own vocabulary decides who handles an event — :authorize is the payment's word and the order never sees it; :cancel is not, so it escapes at once. A finished child admits nothing and stops competing, so every later event falls straight through to the parent."
     :why "It is what made nesting cost the design nothing: no guards, no done-event, no internal queue and no run-to-completion — v1's own constraints gave correct hierarchical semantics rather than standing in their way. ONE reading of `whose event is this` is asked by both halves of the step."
     :cites [:a-machine-can-nest-in-a-node]}
    {:id :sub-is-the-machinerys
     :kind :decision
     :says ":sub is a nested machine's own state and is the machinery's, like :id and :instance: sown when the node is entered, DROPPED when it is left — a merge keeps every key, so a child left behind would ride into a state that never declared it — and RESTARTED when the node is re-entered, entering being entering."
     :cites [:inner-first :the-schema-describes-the-map-without-the-machinery-keys]}
    {:id :a-node-holds-what-it-declares
     :kind :decision
     :says "The HOLD half of declared visibility: a value arriving at a node is PROJECTED onto the keys of that node's enter-schema, so a node holds exactly what it declares and data stops flowing through states that never mentioned it. Visibility is then bounded by ABSENCE rather than by permission, which is stronger than any read rule, and it dissolved `a merge cannot remove a key`: dropping a field is declaring one fewer."
     :why "A bare [:map] node holds nothing but its :id, so a state that carries data must say which. :id, :instance and :sub go on after the projection."
     :cites [:internal-visibility-is-declared-and-not-automatic]}
    {:id :the-view-check-is-only-sound-under-projection
     :kind :lesson
     :says "The soundness dependency ran the other way from the design: the READ half's check is only sound because the HOLD half exists. While a node's schema was a lower bound on what it held, a key could arrive from three transitions back, so `admits` answering :no proved nothing and `problems` would have condemned shapes that run. Holding had to land before reading."
     :cites [:a-node-holds-what-it-declares]}
    {:id :projection-cost-exactly-one-test
     :kind :lesson
     :says "Projecting the merge on entry was a breaking change and broke exactly one of 68 tests — an async fixture whose state was a bare [:map] while its handler set :mark — and two other fixtures wanted the same correction on inspection. The generative fixtures needed nothing, their handlers all answering {}."
     :why "Measuring it before recommending it is what settled a three-way question that argument had not: opt-in per node, shape-wide, or a separate :keeps declaration. The two cheaper designs existed only to avoid a cost that turned out not to be there, so neither was built."
     :cites [:a-node-holds-what-it-declares]}
    {:id :sown-off-the-projected-value
     :kind :decision
     :says "A child is sown off the PROJECTED value that has just arrived at its node, never the merge in flight. It is what keeps check's `seeds` local and sound: a node holds exactly what it declares, so whether THIS node's schema guarantees the seed has a proof. Sowing off the pre-projection value would have let a key three transitions back reach a child no state on the way admitted holding — the same unsoundness projection removed from views."
     :cites [:a-node-may-sow-its-child :a-node-holds-what-it-declares]}
    {:id :arrive-is-one-definition-of-entering
     :kind :decision
     :says "What arriving at a node MEANS is said in one place, called from the three places a machine ever enters a state: the first state of a run, the far end of a transition, and the far end of a completion. Those differ in what they hand over, never in what arriving means, and three copies is exactly how what the check composes and what the compiler composes come to disagree."
     :cites [:what-is-checked-must-be-what-runs :a-node-holds-what-it-declares]}
    {:id :a-patch-has-to-say-whose-it-is
     :kind :decision
     :says "A patch carries :depth, and the two halves of the step compare it against their own lookup — a child's patch may only be applied to that child, and this machine's only to this machine. One comparison per level checks the whole descent."
     :why "The bug the seam caught was one layer up: the async layer handed `apply` the patch DEFERRED rather than the patch, and the seam failed loudly at :depth instead of merging nonsense. Nine errors, all one cause."
     :see [:robertluo.state-graph.compile/phases]
     :cites [:the-split-is-decided-by-what-each-crossing-depends-on]}
    {:id :the-patch-is-never-stale-only-the-admission-is
     :kind :decision
     :says "A handler answers from the event alone, so what it computed while the machine was in S is still exactly right in T; only whether T admits the event can have changed, and that is looked up again at application time. The exception is a handler that READS a view, which is why the concurrency condition includes reads."
     :see [:robertluo.state-graph.compile/phases]
     :cites [:a-handler-belongs-to-the-event :a-handler-never-sees-the-state]}]}
  (:refer-clojure :exclude [compile])
  (:require [malli.core :as m]
            [malli.util :as mu]
            [robertluo.state-graph.shape :as shape]))

(def State
  "A state is a map that says which node it is in, and optionally which RUN it belongs
   to. The node cannot live only in the graph: the step function has to know whose
   out-edges to search.

   :sub IS A NESTED MACHINE'S OWN STATE, present exactly while the node it is in declares
   one. Machinery's, like :id — written when the node is entered, dropped when it is left,
   and never a handler's to touch."
  [:map [:id shape/Id]
        [:instance {:optional true} shape/Instance]
        [:sub {:optional true} [:ref #'State]]])

(def Event
  "An event says its own name for the same reason, and the step matches an edge on it.
   It may say which run it is for too — the step does not care, having only ever one in
   hand, but the async layer routes on exactly that and cannot read it off a state."
  [:map [:id shape/Id] [:instance {:optional true} shape/Instance]])

(def Step
  "What the synchronous step is. Under a Context of the caller's the return type is
   theirs to know, so this describes the default and not every step compile can build."
  [:=> [:cat State Event] State])

(def ^{:knowledge
       [{:id :a-handler-may-answer-later
         :kind :decision
         :says "A handler may answer a DEFERRED rather than a plain map, so an instance waiting on I/O holds no thread. The compiler never learns what a deferred is: it is parameterised by a `then` and a `pure` and composes the step out of those two, so the async layer passes manifold's and requires manifold on its own account."
         :why "It is what a stream library is for, and with parallelism living across instances it is what stops one slow handler starving the pool. The cost, said out loud: the step's return type is the caller's to know, and the synchronous path can now block."
         :cites [:inject-a-function-and-never-thread-options]}
        {:id :then-is-a-bind-and-not-an-fmap
         :kind :lesson
         :says ":then must FLATTEN — it is a bind and not a map. The words always said so, but the old step used `then` exactly once per call, so an fmap satisfied it and a test fixture was (fn [v f] (box (f v))). The phase split composes two binds, and an fmap there yields a container of a container that fails at the :depth seam. Verified first: d/chain flattens, twice over, and chaining a deferred does not consume it."
         :cites [:a-handler-may-answer-later]}
        {:id :how-the-step-says-a-thing-was-ignored
         :kind :decision
         :says "A THIRD injected function, `ignored`, of a state and an event, defaulting to (fn [state _event] state). A caller folding by hand replaces it to hear about a miss; the stream door reports a miss as data from `admits?` instead."
         :cites [:an-ignored-event-is-not-an-error-but-is-not-silent]}
        {:id :a-richer-step-return-was-turned-down
         :kind :rejected
         :says "An outcome value — {:state s :outcome :ignored} — as the step's return was turned down. It is the structural answer, impossible to miss, but (reduce step init events) would stop yielding STATES, and that reduction is the README's own headline sentence."
         :cites [:how-the-step-says-a-thing-was-ignored]}
        {:id :identical-is-not-the-ignored-signal
         :kind :lesson
         :says "identical? cannot signal `ignored`: a fired self-loop whose handler answers {} returns a state identical? to the old one — Clojure's map assoc answers `this` when the value is already there. Metadata on the state is worse, merge and assoc preserving it so a stale flag would ride into every later state. Checked because it was about to be recommended as a free signal."
         :cites [:how-the-step-says-a-thing-was-ignored]}
        {:id :is-the-contexts-ignored-still-earning-its-place
         :kind :open
         :says "Is :ignored still earning its place? Its stated job was that a store layer would replace it with one that records, and there is no store layer: the stream door reports a miss as :fired false, from `admits?` and not from any callback. What is left is a caller who folds by hand and wants to hear about a miss. Three lines of surface, and the bar for removing it is a second reader asking what it is for; such a caller would close over `admits?` themselves."
         :cites [:how-the-step-says-a-thing-was-ignored]}]}
  Context
  "HOW A VALUE BECOMES AVAILABLE, and what becomes of an event nobody handled. The only
   thing `compile` is parameterised by, and the reason this namespace never learns what a
   deferred is: the async layer passes manifold's, and nothing here requires manifold.

   :then    a value and a continuation. Answers whatever the continuation answers, in
            whatever container the caller works in — (fn [v f] (f v)) here, d/chain there.

            IT MUST FLATTEN, which is a BIND and not a map: where the continuation answers
            a container, `then` answers THAT container and never one wrapped around it.
            The words above always said so — `answers whatever the continuation answers` —
            and nothing depended on it until the step came apart into `phases`, both of
            whose halves answer a container, so the step now composes two binds where it
            used to take one. d/chain flattens; the default answers f's value untouched.
            An fmap in this slot yields a container of a container and fails at the seam
            rather than silently, `applying` finding no :depth on what it was handed.
   :pure    a value already available, put into that same container.
   :ignored a state and an event no edge admits, answering the state. The default is
            SILENT, and a caller folding by hand replaces it to hear about a miss.

            IT IS NO LONGER THE ONLY WAY, and no longer the one that matters. Nothing
            here stores anything, so a layer above reports a miss as DATA on the same
            output as everything else — and it gets that fact from `admits?`, not from
            here, because a callback cannot be put on a stream."
  [:map [:then {:optional true} fn?]
        [:pure {:optional true} fn?]
        [:ignored {:optional true} fn?]])

(def ^{:knowledge
       [{:id :a-deferred-under-the-synchronous-default-is-dereferenced
         :kind :decision
         :says "A deferred answered under the synchronous default is DEREFERENCED rather than refused: synchronous is exactly what `block until it is available` means, so there is nothing to refuse. It costs no dependency — clojure.lang.IDeref is Clojure's, a manifold deferred implements it, and a map does not, so the common path is untouched."
         :why "Better than the guard that was going to be recommended. No default timeout is chosen because choosing one is policy; clojure.core/deref has a 3-arity if a bounded wait is ever wanted."
         :from "the author, 2026-08-31"
         :when "2026-08-31"
         :cites [:a-handler-may-answer-later]}]}
  synchronous
  "The default Context — blocking, dependency-free, and exactly the step this namespace
   had before it was parameterised at all.

   A DEFERRED HERE IS DEREFERENCED rather than refused, because synchronous is precisely
   what `block until it is available` means, so there is nothing to refuse.
   clojure.lang.IDeref is CLOJURE'S and not manifold's — a manifold deferred implements
   it, which is what makes @d work — so this needs no dependency to honour one. A
   handler's answer is a MAP and a map is not IDeref, so the common path is untouched.

   The cost, said out loud: this can block for ever. No timeout is chosen because that is
   policy; clojure.core/deref has a 3-arity if a bounded wait is ever wanted."
  {:then    (fn [v f] (f (if (instance? clojure.lang.IDeref v) @v v)))
   :pure    identity
   :ignored (fn [state _event] state)})

(defn- conform!
  "A crossing checked IN THE CODE and not merely declared — instrumentation is a dev
   affordance and these hold in production. Answers the value, so it composes."
  [schema value ctx]
  (if-let [errors (shape/explain schema value)]
    (throw (ex-info (str "Not a valid " (name (:crossing ctx)))
                    (assoc ctx :value value :errors errors)))
    value))

(defn- payload
  "The event without the machinery's own keys. A SCHEMA OVER AN EVENT DESCRIBES WHAT IT
   CARRIES — its own schema and any guard alike — exactly as a state's schema describes the
   state without :id, :instance and :sub. Those keys ride in the value so the step can read
   them; they are not part of what the event says.

   The HANDLER still gets the whole event. What is narrowed is what is CHECKED, which is
   what has to agree with what a guard is checked against, or the two would be reasoning
   about different values."
  [event]
  (dissoc event :id :instance))

(defn- landed
  "The patch applied to the state, key by key.

   A KEY WITH NO COMBINE REPLACES, which is exactly what `merge` did and what every shape
   written before combines existed still gets. A key WITH one is combined with what the
   state already holds — and a key the state does not hold yet is simply TAKEN, a combine
   needing two values where there is only one.

   THIS IS THE ONLY NON-COMMUTATIVE THING IN THE APPLY PHASE, which is why replacing it
   widens the concurrency licence: everything after it — the projection, the identity keys,
   the enter validation — is a pure function of the value this produces."
  [state answer combines]
  (reduce-kv (fn [m k v]
               (assoc m k (if-let [f (and (contains? state k) (combines k))]
                            (f (get state k) v)
                            v)))
             state answer))

(defn index
  "Everything the step looks up, computed once — :edges keyed by [state-id event-id],
   which is what determinism buys, and :machines keyed by the node that nests one.

   THE VALUE IS A VECTOR OF CANDIDATES, because a guard lets one event lead two ways. It
   is still a LOOKUP and never a search: the candidates were proved DISJOINT before the
   shape was built, so at most one can admit an event and the order they sit in cannot
   matter — which is just as well, ubergraph keeping out-edges in a set.

   RECURSIVE, because nesting is: a child's own index sits under its parent's node, so a
   step or an `admits?` can descend without recomputing anything."
  {:malli/schema [:=> [:cat shape/Shape] :map]}
  [sh]
  {:edges (reduce (fn [m {:keys [from event to handler out sees schema] :as t}]
                    (update m [from event] (fnil conj [])
                            {:to to :handler handler :out out :sees sees
                             :when (:when t)
                             :event-schema schema
                             :patch-schema (shape/patch-schema sh to)
                             :enter-schema (shape/enter-schema sh to)}))
                  {} (shape/transitions sh))
   ;; {node-id {k f}} — HOW A PATCH LANDS on each key the node declares a combine for.
   ;; Keyed by NODE and not by edge, because a combine is the data owner's and the same
   ;; key must combine the same way however it arrives.
   :combines (into {}
                   (for [id (shape/states sh)]
                     [id (into {} (for [[k v] (shape/combines sh id)
                                        :when (:combine v)]
                                    [k (:combine v)]))]))
   :machines (into {}
                   (for [[id child] (shape/machines sh)]
                     [id {:shape child :index (index child)}]))
   ;; {from [{:outcome <id?> :to <id> :yield <schema>} ...]} — WHERE A STATE GOES WHEN IT
   ;; COMPLETES, and where each OUTCOME goes where it says so. Read once
   ;; so that the step pays a map lookup and not a walk over every edge.
   :continuations (shape/continuations sh)})

(defn- candidates
  "Every edge this state has for this event's ID, guards not yet consulted. One reading,
   so that `entry` and the step's fall-through cannot disagree about whether there was an
   edge to refuse."
  [idx state event]
  (get-in idx [:edges [(:id state) (:id event)]]))

(defn- entry
  "What the step needs for this state and this event, or nil where no edge admits it.
   ONE definition of the lookup, because `admits?` publishes the same answer and two
   readings of it could drift into disagreeing.

   A GUARDED CANDIDATE IS TRIED AGAINST THE EVENT'S PAYLOAD, and an unguarded one admits
   whatever reaches it. Nothing here decides between two that both match, because the
   constructor refused a shape where two could."
  [idx state event]
  (let [carried (payload event)]
    (some (fn [c] (when (or (nil? (:when c)) (m/validate (:when c) carried)) c))
          (candidates idx state event))))

(defn admits?
  "Whether this machine has a transition for this event HERE — the step's own lookup,
   answered WITHOUT taking the step, and DESCENDING into a nested machine exactly as the
   step does.

   WHAT IT IS FOR: a layer above needs to report whether an event fired, and the step
   cannot tell it. An event nobody handled answers the state UNCHANGED, and a fired
   self-loop whose handler answers {} answers a state that is `identical?` to the old
   one — verified, which is why this is a lookup and not a comparison.

   Takes the INDEX and not the shape, so that a caller computes it once. `index` is
   public for exactly this reason."
  {:malli/schema [:=> [:cat :map State Event] :boolean]
   :knowledge
   [{:id :fired-needs-a-lookup-and-not-a-comparison
     :kind :decision
     :says "Whether an event FIRED is answered by the step's own lookup, published, over one private reading that both it and the step call — so the two cannot drift. An ignored event answers the state unchanged and a fired self-loop can too, so no comparison of states can tell them apart."
     :cites [:identical-is-not-the-ignored-signal]}]}
  [idx state event]
  (boolean
   (or (entry idx state event)
       (when-let [m (get-in idx [:machines (:id state)])]
         (admits? (:index m) (:sub state) event)))))

(declare enter sown)

(defn- arrive
  "A VALUE ARRIVING AT A NODE: projected onto what that node declares, given the node's
   identity, and seeded with a nested machine's own first state.

   ONE DEFINITION OF IT, called from all three places a machine ever enters a state — the
   first state of a run, the far end of a transition, and the far end of a COMPLETION
   TRANSITION. Those differ in what they hand over, never in what arriving MEANS, and three
   copies of this is exactly how `what the check composes` and `what compile composes` come
   to disagree.

   A NODE HOLDS WHAT IT DECLARES AND NOTHING ELSE. The value is PROJECTED onto the keys of
   the node's enter-schema, so data stops flowing through states that never mentioned it.
   That is what bounds internal visibility BY ABSENCE — a handler cannot see what the state
   it is changing does not hold — and it is what makes the view check sound: a declared
   schema is what a node HAS rather than a lower bound on it. It also removes `a merge
   cannot remove a key`: dropping a field is declaring one fewer. The cost, said out loud:
   a bare [:map] node holds nothing but its :id, so a state that carries data must say which.

   :id, :instance AND :sub GO ON AFTER THE PROJECTION. A handler cannot move the machine
   sideways past the edge that decides where it lands, cannot move it to another run, and
   cannot reach into a nested machine. A child left behind would ride into a state that
   never declared it, so :sub is dropped and re-seeded rather than carried."
  [sh value to enter-schema ctx]
  (let [base (-> (select-keys value (mu/keys enter-schema))
                 (assoc :id to)
                 (into (select-keys value [:instance]))
                 (dissoc :sub))]
    (conform! enter-schema
              (cond-> base
                (shape/machine sh to) (assoc :sub (sown sh to base)))
              (assoc ctx :crossing :enter :to to))))

(defn- sown
  "THE FIRST STATE OF THE MACHINE THIS NODE NESTS, started with what the node sows into it:
   nothing at all where it declares no :seed, and otherwise the seed's own keys taken off
   the value that has just ARRIVED here.

   OFF THE PROJECTED VALUE AND NOT THE ONE IN FLIGHT, which is what makes the check local
   and sound: a node holds exactly what it declares, so `check/seeds` asks whether THIS
   node's schema guarantees the seed and gets a proof rather than a guess. Sowing out of
   the pre-projection merge would have let a key three transitions back reach a child that
   no state on the way ever admitted holding.

   IT IS A SEAM AND IS CHECKED HERE, exactly as a :sees view and a :yield are. The static
   check proves what it can from the schemas; this holds in production and gives the
   diagnosis `the node did not provide the seed` rather than letting a nil into a child."
  [sh to value]
  (let [child (shape/machine sh to)]
    (enter child nil (if-let [seed (shape/seed sh to)]
                       (conform! seed (select-keys value (mu/keys seed))
                                 {:crossing :seed :to to})
                       {}))))

(defn- completed
  "THE VALUE A COMPLETED STATE HANDS ON, or nil where the state has not completed.

   ANSWERS [<the completion taken> <the value>], or nil.

   A state with no nested machine completes ON ENTRY: there is no activity to finish, so
   finishing it is arriving. One WITH a machine completes when that child sits in a final
   state of its own — which is the only moment the child's result is guaranteed to be
   there, and therefore the only moment a :yield can be a guarantee rather than a hope. An
   escape by an ordinary event is an ABORT and yields nothing, which is the semantics
   nesting already had and this does not change.

   WHICH final state it sat in is the one thing about the child a completion may read, and
   reading it is not a guard: the set is finite and known at construction, and the dispatch
   is a map lookup on an id rather than a schema anybody has to prove disjoint. It is the
   same structural fact `is the child final` asked one notch finer.

   THE YIELD IS A SEAM AND IS CHECKED HERE, exactly as a :sees view is. The static check
   proves what it can from the schemas; this holds in production and gives the diagnosis
   `the child did not provide the yield` rather than letting a nil into the parent."
  [sh state entries]
  (let [id    (:id state)
        child (shape/machine sh id)
        ;; WHICH COMPLETION THIS IS, and it is a LOOKUP. An entry with no :outcome is the
        ;; unconditional form and answers every way the child can finish; one with an
        ;; :outcome answers only the final state it names. A child that has finished in a
        ;; way this node declares no transition for has not completed it — the parent goes
        ;; on sitting there, and its own edges are still the escape.
        final (when child (:id (:sub state)))
        entry (when (or (nil? child) (shape/final? child final))
                (first (filter #(or (nil? (:outcome %)) (= final (:outcome %))) entries)))]
    (when entry
      [entry (if-let [yield (:yield entry)]
               (merge state (conform! yield
                                      (select-keys (:sub state) (mu/keys yield))
                                      {:crossing :yield :from id}))
               state)])))

(defn- continue
  "EVERY COMPLETION TRANSITION FROM HERE, followed until the machine is somewhere that
   declares none or has not completed. Answers the state it ends in.

   NO EVENT, NO HANDLER AND NO PATCH, so this is PURE — it needs neither the Context nor a
   deferred, and can run inside `initial` as happily as inside a step. Which is why the
   first state of a run resolves a continuation too: entering is entering.

   `conts` IS HANDED IN rather than read off the shape, because this sits on the hot path
   and `shape/continuations` walks every edge. A shape declaring none pays ONE MAP LOOKUP
   per transition. The rare path recomputes an enter-schema and a child's first state per
   hop, which is the right way round.

   THE `seen` GUARD IS FOR A GRAPH NOBODY BUILT WITH `shape`. The constructor PROVES an
   entry-fired cycle cannot exist — :done-cycle — so arriving at this throw means a graph
   assembled by hand, and a diagnosis is worth more than a hang."
  [sh conts state]
  (loop [state state seen #{}]
    (let [id (:id state)]
      (if-let [[{:keys [to]} value] (some->> (conts id) (completed sh state))]
        (if (seen id)
          (throw (ex-info "A completion transition cycles"
                          {:crossing :done :at id :seen seen}))
          (recur (arrive sh value to (shape/enter-schema sh to) {:from id})
                 (conj seen id)))
        state))))

(defn- enter
  "The state a machine starts in, seeded with a nested machine's own first state wherever
   the node it starts in declares one, AND CONTINUED wherever that node completes on
   arrival. RECURSIVE, so nesting goes as deep as the shapes do.

   PRIVATE, and both arities of `initial` call it: a public 2-arity delegating to a public
   3-arity goes through the INSTRUMENTED var, which would check this nil against Instance
   and throw. That trap is `initial`'s :a-2-arity-delegating-to-a-3-arity-breaks-under-instrumentation,
   and this is the shape that avoids it."
  [sh instance data]
  (let [id (shape/initial-id sh)]
    (continue sh (shape/continuations sh)
              (arrive sh
                      (cond-> data (some? instance) (assoc :instance instance))
                      id
                      (shape/enter-schema sh id)
                      {}))))

(def Phases
  "The step in its two halves, the step itself, and the one check only the runtime can
   make. See `phases`."
  [:map [:patch fn?] [:apply fn?] [:agree fn?] [:step fn?]])

(defn phases
  "THE STEP IN TWO HALVES — {:patch :apply :step} — and the step BUILT OUT OF THE OTHER
   TWO, so that what runs in one call and what runs in two cannot come to disagree.

   :patch  (fn [state event] -> {:answer m :depth n}, or ::missed)   runs the handler
   :apply  (fn [state event patch] -> State)   lands it
   :step   (fn [state event] -> State)         both, which is `compile`

   WHY THE SPLIT EXISTS, and it is the only reason: a licensed pair of events may run AT
   ONCE. `check/commuting` proves which pairs pending in one state can be applied in order
   of COMPLETION, and taking that licence needs the HANDLER run apart from the APPLICATION
   — two handlers in flight, their patches applied as they land. One step doing both cannot
   be split by a caller: calling it twice from the same state answers two whole states
   derived from it, and combining those is only correct for self-loops with disjoint
   patches, which is LESS than the licence gives.

   WHICH CROSSING BELONGS TO WHICH HALF is decided by what it depends on, and the division
   is exact:
     :event  the patch phase — an event either is what it says it is or is not, whatever
             state it meets
     :sees   the patch phase, READING THE STATE THE HANDLER SAW. Which is precisely why
             Bernstein's conditions include reads: a view read in S is stale in T if the
             other event wrote it, and `commutes` refuses such a pair
     :out    the patch phase. It is the EVENT'S promise about its own answer and no state
             is party to it
     :answer the APPLY phase, and it cannot be anywhere else — a patch-schema is the
             TARGET'S own schema, and a licensed patch is applied where the target may be
             a different node from the one it was computed against. That is the whole
             difference between the two halves
     :enter  the apply phase, being about the state that comes out

   THE LOOKUP IS DONE TWICE, once per half, and the second one is the authority: it reads
   the edge from the state the patch is ACTUALLY landing on. A licence guarantees that edge
   exists — the diamond closing is what `commutes` proves — so its absence is a DEFECT and
   throws rather than being quietly ignored."
  {:malli/schema [:=> [:cat shape/Shape [:maybe Context]] Phases]
   :knowledge
   [{:id :the-split-is-decided-by-what-each-crossing-depends-on
     :kind :decision
     :says "The step is in two halves — a PATCH half that runs the handler and an APPLY half that lands it — and which crossing belongs to which is decided by what it depends on. :event, :sees and :out are the patch half; :answer and :enter are the apply half. :answer FORCED the split to exist: a patch-schema is the TARGET'S own schema, and a licensed patch is applied where the target may be a different node from the one it was computed against."
     :why "The only reason the split exists is that a licensed pair of events may run at once, and taking that licence needs the handler run apart from the application. The lookup is done twice, once per half, and the second is the authority: it reads the edge from the state the patch is actually landing on, and its absence is a defect that throws."
     :when "2026-09-03"
     :cites [:two-events-in-flight-at-once]}
    {:id :the-step-is-defined-as-the-composition
     :kind :decision
     :says "The one-call step is BUILT from the two halves — patch, then apply — and asserted equal to them as a property over generated shapes and events, admitted or not. That is the only thing stopping the one-call door and the two-call door drifting, and it cost three lines."
     :cites [:the-split-is-decided-by-what-each-crossing-depends-on]}
    {:id :agree-verifies-the-law-on-the-concrete-values
     :kind :decision
     :says "When a licensed pair is taken, the combine law is checked on the two patches in hand, over only the keys both write, before either lands. `commutes` licensed the pair only where the combine is declared identically on both intermediate states and the join, so one f suffices and it is read off the join node."
     :why "The declaration is a claim about a CLOSURE and no static check can settle it. Measured: 27,000 generated triples missed a plausible domain rule — a pinned choice wins outright — that is not commutative. So the runtime check is the ENFORCEMENT and the generative one is the development aid."
     :cites [:the-promise-is-data-and-checked-at-two-strengths]}
    {:id :a-childs-first-state-is-a-function-of-the-run
     :kind :decision
     :says "A nested machine's first state is no longer precomputed once per machine: a node that sows a :seed starts its child with what has just arrived, so what the child begins with is a function of the RUN and is made once per entry. That a child CAN start is still answered before anything runs — :machine-cannot-start for a seedless node, check's `seeds` for a seeded one."
     :cites [:a-node-may-sow-its-child :sown-off-the-projected-value]}]}
  [sh context]
  (let [{:keys [then pure ignored]} (merge synchronous context)
        idx   (index sh)
        conts (:continuations idx)
        ;; Every nested machine split ONCE, with the SAME Context, so a child may answer a
        ;; deferred wherever its parent may.
        ;;   ITS FIRST STATE IS NOT PRECOMPUTED ANY MORE, and could not be: a node that
        ;;   sows a :seed starts its child with what has just arrived, so what the child
        ;;   begins with is a function of the run and not of the shape. `arrive` makes it,
        ;;   once per entry, through `sown`. That a child CAN start is answered before
        ;;   anything runs — :machine-cannot-start for a seedless node and `check/seeds`
        ;;   for a seeded one — so nothing was being discovered here that is not still
        ;;   discovered earlier.
        subs (into {}
                   (for [[id {:keys [shape index]}] (:machines idx)]
                     [id {:phases (phases shape context)
                          :index index}]))
        ;; ONE READING OF `whose event is this`, asked by both halves. INNER FIRST: a
        ;; nested machine gets every event before this node's own edges do, which is what
        ;; makes the parent's edges the ESCAPE and needs no guard — a child that has
        ;; finished admits nothing, so the next event falls straight through.
        inner (fn [state event]
                (let [m (subs (:id state))]
                  (when (and m (admits? (:index m) (:sub state) event)) m)))]
    (letfn
     [(patching
        [state event]
        (if-let [m (inner state event)]
          (then ((:patch (:phases m)) (:sub state) event)
                (fn [p] (cond-> p (map? p) (update :depth inc))))
          (if-let [{:keys [to handler out sees event-schema]} (entry idx state event)]
            (let [ctx {:from (:id state) :event (:id event) :to to}]
              (conform! event-schema (payload event) (assoc ctx :crossing :event))
              ;; A VIEW IS A SEAM AND IS CHECKED HERE TOO. The static check proves what it
              ;; can from the schemas; this holds in production and gives the diagnosis
              ;; `the state did not provide the view` rather than a nil inside a handler.
              ;; A handler with no view declared keeps its one argument, so nothing that
              ;; existed before views learns that they exist.
              (then (if sees
                      (handler event (conform! sees
                                               (select-keys state (mu/keys sees))
                                               (assoc ctx :crossing :sees)))
                      (handler event))
                    (fn [answer]
                      ;; :out is validated only where it is declared. Its real job is the
                      ;; STATIC check; here it buys a better diagnosis — `the handler is
                      ;; wrong` rather than `the state is wrong` one phase later.
                      (when out (conform! out answer (assoc ctx :crossing :out)))
                      {:answer answer :depth 0})))
            ;; NO EDGE ADMITTED IT — but `every guard refused` and `there was no edge` are
            ;; different things, and only one of them may be a defect. Where edges exist,
            ;; the event is conformed against their schema before the miss is believed:
            ;; A GUARD IS A REFINEMENT OF A SCHEMA THE EVENT MUST ALREADY SATISFY, so a
            ;; malformed one throws here rather than being reported as an ordinary miss.
            (let [cs (candidates idx state event)]
              (when (seq cs)
                (conform! (:event-schema (first cs)) (payload event)
                          {:from (:id state) :event (:id event) :crossing :event}))
              (pure ::missed)))))

      (applying
        [state event p]
        (if (identical? ::missed p)
          (pure (ignored state event))
          (let [m (inner state event)]
            ;; THE TWO HALVES MUST AGREE ABOUT WHOSE EVENT IT IS. A patch a child's handler
            ;; answered may only be applied to that child, and one this machine answered
            ;; only to this machine — otherwise a `{:answer ...}` would be merged into a
            ;; state that never asked for it. Deeper levels assert the same thing of
            ;; themselves, so one comparison per level checks the whole descent.
            (when (not= (boolean m) (pos? (:depth p)))
              (throw (ex-info "Patch does not belong to the machine it is being applied to"
                              {:crossing :depth :from (:id state) :event (:id event)
                               :depth (:depth p) :nested (boolean m)})))
            (if m
              (then ((:apply (:phases m)) (:sub state) event (update p :depth dec))
                    ;; THE CHILD MAY HAVE JUST FINISHED, which is the second moment a
                    ;; completion transition fires: this node completes when its child
                    ;; reaches a final state, so the parent continues with no event of its
                    ;; own. Nothing else about the parent's state has changed.
                    (fn [sub'] (continue sh conts (assoc state :sub sub'))))
              (if-let [{:keys [to patch-schema enter-schema]} (entry idx state event)]
                (let [ctx {:from (:id state) :event (:id event) :to to}
                      answer (:answer p)]
                  ;; THE ANSWER MUST BE ONE THE TARGET WILL TAKE. `:out` is what a handler
                  ;; PROMISES and is optional; this is what the state ADMITS and is not. A
                  ;; patch-schema is the target's own schema with every key optional and
                  ;; the map closed, so a key the state does not declare is REFUSED rather
                  ;; than projected away in silence.
                  ;; IDENTITY NEEDS NO SPECIAL CASE HERE. A state schema describes the map
                  ;; without :id, :instance or :sub, so a handler naming any of the three
                  ;; is answering an undeclared key and this is what refuses it. An event
                  ;; is the only way a transition happens.
                  (conform! patch-schema answer (assoc ctx :crossing :answer))
                  ;; THE PATCH LANDS, THE VALUE ARRIVES, AND THEN EVERY COMPLETION
                  ;; TRANSITION FROM WHERE IT LANDED. What arriving means is `arrive`'s to
                  ;; say and is said in one place; a state that completes on arrival goes
                  ;; on at once, with no event — see `continue`.
                  (pure (continue sh conts
                                  (arrive sh (landed state answer (get-in idx [:combines to]))
                                          to enter-schema ctx))))
                ;; THE LICENCE PROMISED THIS EDGE. `commutes` proves the diamond closes
                ;; before anything is applied out of order, so an edge missing HERE is a
                ;; licence that was wrong or a caller applying a patch where it does not
                ;; belong. Either is a defect, and the patch phase already distinguished a
                ;; genuine miss by answering ::missed.
                (throw (ex-info "No edge admits this event where its patch is applied"
                                {:crossing :apply :from (:id state)
                                 :event (:id event)})))))))

      (agreeing
        [state ea pa eb pb]
        ;; THE LAW, CHECKED WHERE IT MATTERS AND NOT TRUSTED. `commutes` proves the diamond
        ;; and reads the declared :combine/commutes, but that declaration is a claim about a
        ;; CLOSURE and no static check can settle it — a generative one refutes it at best,
        ;; and MEASURED, 27,000 generated triples missed a plausible domain rule (a pinned
        ;; choice wins outright) that is not commutative. Here both patches are in hand, so
        ;; the claim is checked on the CONCRETE VALUES.
        ;;
        ;; ONLY THE KEYS BOTH PATCHES WRITE. Every other key is disjoint and was proven
        ;; statically, so there is nothing to ask about it. Which also makes this cost
        ;; nothing on the common path: no shared key, no work.
        ;;
        ;; ONE f SUFFICES because `commutes` licensed the pair only where the combine is
        ;; declared IDENTICALLY on both intermediate states and on the join node — so this
        ;; reads it off the join node and does not have to compose three.
        (let [a (:answer pa) b (:answer pb)]
          (when (and (map? a) (map? b))
            (let [shared (filter (set (keys b)) (keys a))]
              (when (seq shared)
                (let [ta (:to (entry idx state ea))
                      ;; where the second event lands from there. A licensed pair is never
                      ;; guarded, so {:id ta} is the whole of what the lookup needs.
                      x  (when ta (:to (entry idx {:id ta} eb)))
                      fs (get-in idx [:combines x])]
                  (doseq [k shared
                          :let [f (get fs k)]
                          :when f
                          :let [held? (contains? state k)
                                s0 (get state k)
                                ab (if held?
                                     (f (f s0 (get a k)) (get b k))
                                     (f (get a k) (get b k)))
                                ba (if held?
                                     (f (f s0 (get b k)) (get a k))
                                     (f (get b k) (get a k)))]
                          :when (not= ab ba)]
                    (throw (ex-info "A combine declared commutative is not, on these values"
                                    {:crossing :combine :key k :from (:id state)
                                     :events [(:id ea) (:id eb)]
                                     :held s0 :patches [(get a k) (get b k)]
                                     :answers [ab ba]})))))))))]
      {:patch patching
       :apply applying
       :agree agreeing
       :step  (fn [state event]
                (then (patching state event) (fn [p] (applying state event p))))})))

(defn compile
  "The shape as an ordinary Clojure function of a state and an event. `phases` in one
   call, and literally built from it, so the one-call door and the two-call door cannot
   drift.

   An event the current state has no transition for leaves the state UNCHANGED and THE
   HANDLER IS NEVER CALLED — an event a state does not care about is not an error, and it
   keeps the reduction total. It is not silent either: see Context's :ignored. What throws
   is a crossing that does not hold: an event that is not what the edge says it is, a
   handler answering something its own :out denies, or a state that its target's schema
   will not admit. Those are defects, not facts about the run.

   The handler takes THE EVENT ALONE and is the EVENT'S, not the edge's. Its answer is a
   PATCH, and it is checked against one: the target's own schema with every key optional
   and the map closed, so a key that state does not declare is REFUSED. That is what makes
   AN EVENT THE ONLY WAY A TRANSITION HAPPENS — a handler naming :id, :instance or :sub is
   naming a key no state schema declares, so identity is refused by the same rule that
   refuses a typo, and never by a special case.

   WITH NO CONTEXT the step is synchronous and answers a State, which is what Step says.
   With one, the return type is the CALLER'S to know — a deferred State under manifold —
   so that arity promises a function and no more."
  {:malli/schema [:function [:=> [:cat shape/Shape] Step]
                            [:=> [:cat shape/Shape [:maybe Context]] ifn?]]
   :knowledge
   [{:id :an-ignored-event-is-not-an-error-but-is-not-silent
     :kind :decision
     :says "An event the current state has no transition for is NOT AN ERROR — the reduction stays total — but the step must SAY it happened. The handler does not run: no edge means no target, so there is no enter-schema to validate against and nothing to apply the data to."
     :why "Not an error because nothing controls the order events arrive in behind a stream; a :cancel landing after :complete is ordinary traffic. Not silent because an event that SHOULD have transitioned looks exactly like one correctly ignored, and no static check can see a runtime fact. Not merging the data is what makes it safe, malli maps being open."
     :from "the author's phrase for the hazard: `able to apply, but wrong`"
     :cites [:malli-maps-are-open-by-default :how-the-step-says-a-thing-was-ignored :there-is-no-else]}
    {:id :the-answer-crossing-sits-between-out-and-enter
     :kind :decision
     :says "Three crossings on the way out of a handler stay distinct: :out is what the handler PROMISED and is checked only where declared, buying the diagnosis `the handler is wrong` one phase early; :answer is what the target ADMITS and is not optional; :enter keeps the one thing only a whole state can be wrong about, a required key nobody supplied."
     :cites [:an-event-is-the-only-way-a-transition-happens :a-handler-answers-a-map-and-declares-it]}
    {:id :every-seam-is-conformed-in-the-code
     :kind :rule
     :says "A crossing is checked IN THE CODE and not merely declared — a view, a seed, a yield, a patch, an entry. Instrumentation is a dev affordance; these hold in production and give the diagnosis `the state did not provide the view` rather than a nil inside a handler."}]}
  ([sh] (compile sh nil))
  ([sh context] (:step (phases sh context))))

(defn initial
  "The first state, ENTERED THROUGH THE SAME VALIDATION as every other one. The shape
   knows which node a run starts in; the starting data is the caller's.

   NAME THE RUN and the key is written for you — that is the whole of `hidden by the
   constructors`. A caller says which machine they mean and never spells :instance, here
   or anywhere after, since the step carries it and refuses to let a handler touch it.

   nil NAMES NOTHING and is not an error: the state simply gets no :instance key. That is
   what async/fan passes for an event carrying none, so (fn [k] (initial sh k {})) is a
   call site that works whether the caller names machines or not.

   The 3-arity therefore takes [:maybe Instance], which — Instance being `some?` — asserts
   nothing about that argument, AND THAT COSTS NOTHING REAL. The invariant worth having is
   that no state ever carries a nil :instance, and that lives on the enter schema, where it
   is checked on every entry rather than once at the door."
  {:malli/schema [:function [:=> [:cat shape/Shape :map] State]
                            [:=> [:cat shape/Shape [:maybe shape/Instance] :map] State]]
   :knowledge
   [{:id :a-2-arity-delegating-to-a-3-arity-breaks-under-instrumentation
     :kind :lesson
     :says "A public 2-arity delegating to a public 3-arity goes through the INSTRUMENTED var, and breaks when the extra argument refuses nil. Loosening to [:maybe Instance] is not the fix — Instance is some?, and some? behind a :maybe asserts nothing. The fix is a private helper both arities call."
     :cites [:an-instance-has-an-identity]}
    {:id :the-first-state-is-continued-too
     :kind :decision
     :says "The first state of a run resolves a completion transition exactly as a transition's far end does: entering is entering. A continuation is PURE — no event, no handler, no patch — so it needs neither the Context nor a deferred and runs inside `initial` as happily as inside a step."
     :cites [:a-state-may-say-where-it-goes-when-it-completes :arrive-is-one-definition-of-entering]}]}
  ([sh data] (enter sh nil data))
  ([sh instance data] (enter sh instance data)))
