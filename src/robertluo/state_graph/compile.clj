(ns robertluo.state-graph.compile
  "shape -> (fn [state event] state'). The only namespace that turns data into a
   function.

   The lifecycle of an instance is (reduce (compile shape) (initial shape data) events)
   and that is the whole runtime: no object, no atom, no protocol. Everything above
   this is a way of getting events into that reduction or results out of it — which is
   why a stream is a layer above and not the core. Such a layer takes the STEP FUNCTION
   as a value, so it requires neither this namespace nor a shape.

   Requires the shape and malli. It knows nothing of streams or databases."
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

(def Context
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

(def synchronous
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
   ;; {from {:to <id> :yield <schema>}} — WHERE A STATE GOES WHEN IT COMPLETES, read once
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
  {:malli/schema [:=> [:cat :map State Event] :boolean]}
  [idx state event]
  (boolean
   (or (entry idx state event)
       (when-let [m (get-in idx [:machines (:id state)])]
         (admits? (:index m) (:sub state) event)))))

(declare enter)

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
  [value to enter-schema seed ctx]
  (conform! enter-schema
            (cond-> (-> (select-keys value (mu/keys enter-schema))
                        (assoc :id to)
                        (into (select-keys value [:instance]))
                        (dissoc :sub))
              seed (assoc :sub seed))
            (assoc ctx :crossing :enter :to to)))

(defn- completed
  "THE VALUE A COMPLETED STATE HANDS ON, or nil where the state has not completed.

   A state with no nested machine completes ON ENTRY: there is no activity to finish, so
   finishing it is arriving. One WITH a machine completes when that child sits in a final
   state of its own — which is the only moment the child's result is guaranteed to be
   there, and therefore the only moment a :yield can be a guarantee rather than a hope. An
   escape by an ordinary event is an ABORT and yields nothing, which is the semantics
   nesting already had and this does not change.

   THE YIELD IS A SEAM AND IS CHECKED HERE, exactly as a :sees view is. The static check
   proves what it can from the schemas; this holds in production and gives the diagnosis
   `the child did not provide the yield` rather than letting a nil into the parent."
  [sh state yield]
  (let [id (:id state)
        child (shape/machine sh id)]
    (cond
      (nil? child) state
      (not (shape/final? child (:id (:sub state)))) nil
      (nil? yield) state
      :else (merge state (conform! yield
                                   (select-keys (:sub state) (mu/keys yield))
                                   {:crossing :yield :from id})))))

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
    (let [id (:id state)
          {:keys [to yield]} (conts id)]
      (if-let [value (and to (completed sh state yield))]
        (if (seen id)
          (throw (ex-info "A completion transition cycles"
                          {:crossing :done :at id :seen seen}))
          (recur (arrive value to (shape/enter-schema sh to)
                         (some-> (shape/machine sh to) (enter nil {}))
                         {:from id})
                 (conj seen id)))
        state))))

(defn- enter
  "The state a machine starts in, seeded with a nested machine's own first state wherever
   the node it starts in declares one, AND CONTINUED wherever that node completes on
   arrival. RECURSIVE, so nesting goes as deep as the shapes do.

   PRIVATE, and both arities of `initial` call it: a public 2-arity delegating to a public
   3-arity goes through the INSTRUMENTED var, which would check this nil against Instance
   and throw. That trap is recorded in AGENTS.md and this is the shape that avoids it."
  [sh instance data]
  (let [id (shape/initial-id sh)]
    (continue sh (shape/continuations sh)
              (arrive (cond-> data (some? instance) (assoc :instance instance))
                      id
                      (shape/enter-schema sh id)
                      (some-> (shape/machine sh id) (enter nil {}))
                      {}))))

(def Patch
  "WHAT A HANDLER ANSWERED, before any state has taken it — and whose machine it belongs
   to, a nested machine's handler answering one too.

   ::missed IS NOT A PATCH AND SAYS SO: no edge admitted the event, so no handler ran and
   there is nothing to apply. A legal outcome, not an error, and the reduction stays total.

   :depth IS THE ONE THING A PATCH MUST CARRY. A patch is computed against the state a
   machine was in and may be applied to a LATER one — that is the whole point of having two
   phases — and the patch itself is never stale for it: a handler answers FROM THE EVENT
   ALONE, so what it computed in S is still exactly right in T. What CAN have changed is
   WHICH MACHINE admits the event, and applying a child's patch to its parent would be
   silent nonsense. So the depth is recorded here and REFUSED on disagreement at the other
   end, which is the seam this whole split rests on."
  [:or [:= ::missed]
       [:map [:answer :map] [:depth [:int {:min 0}]]]])

(def Phases
  "The step in its two halves, the step itself, and the one check only the runtime can
   make. See `phases`."
  [:map [:patch fn?] [:apply fn?] [:agree fn?] [:step fn?]])

(defn phases
  "THE STEP IN TWO HALVES — {:patch :apply :step} — and the step BUILT OUT OF THE OTHER
   TWO, so that what runs in one call and what runs in two cannot come to disagree.

   :patch  (fn [state event] -> Patch)         runs the handler
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
  {:malli/schema [:=> [:cat shape/Shape [:maybe Context]] Phases]}
  [sh context]
  (let [{:keys [then pure ignored]} (merge synchronous context)
        idx   (index sh)
        conts (:continuations idx)
        ;; Every nested machine split ONCE, with the SAME Context, so a child may answer a
        ;; deferred wherever its parent may. `enter` is what makes its first state, and it
        ;; is computed here because entering a node is not the moment to discover that a
        ;; child cannot start.
        subs (into {}
                   (for [[id {:keys [shape index]}] (:machines idx)]
                     [id {:phases (phases shape context)
                          :index index
                          :first (enter shape nil {})}]))
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
                                  (arrive (landed state answer (get-in idx [:combines to]))
                                          to enter-schema (:first (subs to)) ctx))))
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
                            [:=> [:cat shape/Shape [:maybe Context]] ifn?]]}
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
                            [:=> [:cat shape/Shape [:maybe shape/Instance] :map] State]]}
  ([sh data] (enter sh nil data))
  ([sh instance data] (enter sh instance data)))
