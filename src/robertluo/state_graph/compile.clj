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
  (:require [malli.util :as mu]
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

(defn index
  "Everything the step looks up, computed once — :edges keyed by [state-id event-id],
   which is what determinism buys, and :machines keyed by the node that nests one.

   RECURSIVE, because nesting is: a child's own index sits under its parent's node, so a
   step or an `admits?` can descend without recomputing anything."
  {:malli/schema [:=> [:cat shape/Shape] :map]}
  [sh]
  {:edges (into {}
                (for [{:keys [from event to handler out sees schema]} (shape/transitions sh)]
                  [[from event] {:to to :handler handler :out out :sees sees
                                 :event-schema schema
                                 :patch-schema (shape/patch-schema sh to)
                                 :enter-schema (shape/enter-schema sh to)}]))
   :machines (into {}
                   (for [[id child] (shape/machines sh)]
                     [id {:shape child :index (index child)}]))})

(defn- entry
  "What the step needs for this state and this event, or nil where no edge admits it.
   ONE definition of the lookup, because `admits?` publishes the same answer and two
   readings of it could drift into disagreeing."
  [idx state event]
  (get-in idx [:edges [(:id state) (:id event)]]))

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

(defn- enter
  "The state a machine starts in, seeded with a nested machine's own first state wherever
   the node it starts in declares one. RECURSIVE, so nesting goes as deep as the shapes do.

   PRIVATE, and both arities of `initial` call it: a public 2-arity delegating to a public
   3-arity goes through the INSTRUMENTED var, which would check this nil against Instance
   and throw. That trap is recorded in AGENTS.md and this is the shape that avoids it."
  [sh instance data]
  (let [id (shape/initial-id sh)
        child (shape/machine sh id)
        schema (shape/enter-schema sh id)]
    (conform! schema
              (cond-> (-> (select-keys data (mu/keys schema)) (assoc :id id))
                (some? instance) (assoc :instance instance)
                child (assoc :sub (enter child nil {})))
              {:crossing :enter :to id})))

(defn compile
  "The shape as an ordinary Clojure function of a state and an event.

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
   refuses a typo, and never by a special case. It used to be silently overwritten.

   WITH NO CONTEXT the step is synchronous and answers a State, which is what Step says.
   With one, the return type is the CALLER'S to know — a deferred State under manifold —
   so that arity promises a function and no more."
  {:malli/schema [:function [:=> [:cat shape/Shape] Step]
                            [:=> [:cat shape/Shape [:maybe Context]] ifn?]]}
  ([sh] (compile sh nil))
  ([sh context]
   (let [{:keys [then pure ignored]} (merge synchronous context)
         idx  (index sh)
         ;; Every nested machine compiled ONCE, with the SAME Context, so a child may
         ;; answer a deferred wherever its parent may. `enter` is what makes its first
         ;; state, and it is computed here because entering a node is not the moment to
         ;; discover that a child cannot start.
         subs (into {}
                    (for [[id {:keys [shape index]}] (:machines idx)]
                      [id {:step (compile shape context)
                           :index index
                           :first (enter shape nil {})}]))]
     (fn step [state event]
       (let [m (subs (:id state))]
         (cond
           ;; INNER FIRST. A nested machine gets every event before this node's own edges
           ;; do, which is what makes the parent's edges the ESCAPE and needs no guard: a
           ;; child that has finished admits nothing, so the next event falls straight
           ;; through to here.
           (and m (admits? (:index m) (:sub state) event))
           (then ((:step m) (:sub state) event)
                 (fn [sub'] (assoc state :sub sub')))

           :else
           (if-let [{:keys [to handler out sees event-schema patch-schema enter-schema]}
                    (entry idx state event)]
             (let [ctx {:from (:id state) :event (:id event) :to to}]
               (conform! event-schema event (assoc ctx :crossing :event))
               ;; A VIEW IS A SEAM AND IS CHECKED HERE TOO. The static check proves what it
               ;; can from the schemas; this holds in production and gives the diagnosis
               ;; `the state did not provide the view` rather than a nil inside a handler.
               ;; A handler with no view declared keeps its one argument, so nothing that
               ;; existed before this learns that views exist.
               (then (if sees
                       (handler event (conform! sees
                                                (select-keys state (mu/keys sees))
                                                (assoc ctx :crossing :sees)))
                       (handler event))
                     (fn [answer]
                       ;; :out is validated only where it is declared. Its real job is the
                       ;; STATIC check; here it buys a better diagnosis — `the handler is
                       ;; wrong` rather than `the state is wrong` one line later.
                       (when out (conform! out answer (assoc ctx :crossing :out)))
                       ;; AND THE ANSWER MUST BE ONE THE TARGET WILL TAKE. `:out` is what a
                       ;; handler PROMISES and is optional; this is what the state ADMITS
                       ;; and is not. A patch-schema is the target's own schema with every
                       ;; key optional and the map closed, so a key the state does not
                       ;; declare is REFUSED rather than projected away in silence — which
                       ;; is what it used to be, `mu/keys` dropping it a line below.
                       ;; IDENTITY NEEDS NO SPECIAL CASE HERE. A state schema describes the
                       ;; map without :id, :instance or :sub, so a handler naming any of
                       ;; the three is answering an undeclared key and this is what refuses
                       ;; it. An event is the only way a transition happens.
                       (conform! patch-schema answer (assoc ctx :crossing :answer))
                       ;; A NODE HOLDS WHAT IT DECLARES AND NOTHING ELSE. The merge is
                       ;; PROJECTED onto the keys of the target's enter-schema, so data
                       ;; stops flowing through states that never mentioned it. That is
                       ;; what bounds internal visibility BY ABSENCE — a handler cannot see
                       ;; what the state it is changing does not hold — and it is what makes
                       ;; the view check sound: a declared schema is now what a node HAS
                       ;; rather than a lower bound on it. It also removes `a merge cannot
                       ;; remove a key`: dropping a field is declaring one fewer.
                       ;; The cost, said out loud: a bare [:map] node holds nothing but its
                       ;; :id, so a state that carries data must say which.
                       ;;
                       ;; :id, :instance AND :sub go on AFTER the projection. A handler
                       ;; cannot move the machine sideways past the edge that decides where
                       ;; it lands, cannot move it to another run, and cannot reach into a
                       ;; nested machine. Identity is never a handler's to say.
                       (conform! enter-schema
                                 (let [sub (subs to)]
                                   (cond-> (-> (merge state answer)
                                               (select-keys (mu/keys enter-schema))
                                               (assoc :id to)
                                               (into (select-keys state [:instance]))
                                               (dissoc :sub))
                                     sub (assoc :sub (:first sub))))
                                 (assoc ctx :crossing :enter)))))
             (pure (ignored state event)))))))))

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
