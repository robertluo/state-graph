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
  (:require [robertluo.state-graph.shape :as shape]))

(def State
  "A state is a map that says which node it is in, and optionally which RUN it belongs
   to. The node cannot live only in the graph: the step function has to know whose
   out-edges to search."
  [:map [:id shape/Id] [:instance {:optional true} shape/Instance]])

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
  "{[state-id event-id] -> what the step needs}, computed once. This is what
   determinism buys: a LOOKUP, where a guard would have made it an ordered search."
  {:malli/schema [:=> [:cat shape/Shape] :map]}
  [sh]
  (into {}
        (for [{:keys [from event to handler out schema]} (shape/transitions sh)]
          [[from event] {:to to :handler handler :out out
                         :event-schema schema
                         :enter-schema (shape/enter-schema sh to)}])))

(defn- entry
  "What the step needs for this state and this event, or nil where no edge admits it.
   ONE definition of the lookup, because `admits?` publishes the same answer and two
   readings of it could drift into disagreeing."
  [idx state event]
  (idx [(:id state) (:id event)]))

(defn admits?
  "Whether this state has a transition for this event — the step's own lookup, answered
   WITHOUT taking the step.

   WHAT IT IS FOR: a layer above needs to report whether an event fired, and the step
   cannot tell it. An event nobody handled answers the state UNCHANGED, and a fired
   self-loop whose handler answers {} answers a state that is `identical?` to the old
   one — verified, which is why this is a lookup and not a comparison.

   Takes the INDEX and not the shape, so that a caller computes it once. `index` is
   public for exactly this reason."
  {:malli/schema [:=> [:cat :map State Event] :boolean]}
  [idx state event]
  (some? (entry idx state event)))

(defn compile
  "The shape as an ordinary Clojure function of a state and an event.

   An event the current state has no transition for leaves the state UNCHANGED and THE
   HANDLER IS NEVER CALLED — an event a state does not care about is not an error, and it
   keeps the reduction total. It is not silent either: see Context's :ignored. What throws
   is a crossing that does not hold: an event that is not what the edge says it is, a
   handler answering something its own :out denies, or a state that its target's schema
   will not admit. Those are defects, not facts about the run.

   The handler takes THE EVENT ALONE and is the EVENT'S, not the edge's. Its answer is
   merged into the state and the target's :id is assoc'd AFTER, so a handler that writes
   :id is simply overwritten: identity is the shape's to say.

   WITH NO CONTEXT the step is synchronous and answers a State, which is what Step says.
   With one, the return type is the CALLER'S to know — a deferred State under manifold —
   so that arity promises a function and no more."
  {:malli/schema [:function [:=> [:cat shape/Shape] Step]
                            [:=> [:cat shape/Shape [:maybe Context]] ifn?]]}
  ([sh] (compile sh nil))
  ([sh context]
   (let [{:keys [then pure ignored]} (merge synchronous context)
         idx (index sh)]
     (fn step [state event]
       (if-let [{:keys [to handler out event-schema enter-schema]}
                (entry idx state event)]
         (let [ctx {:from (:id state) :event (:id event) :to to}]
           (conform! event-schema event (assoc ctx :crossing :event))
           (then (handler event)
                 (fn [answer]
                   ;; :out is validated only where it is declared. Its real job is the
                   ;; STATIC check; here it buys a better diagnosis — `the handler is
                   ;; wrong` rather than `the state is wrong` one line later.
                   (when out (conform! out answer (assoc ctx :crossing :out)))
                   ;; :id AND :instance go on AFTER the merge. A handler cannot move the
                   ;; machine sideways past the edge that decides where it lands, and it
                   ;; cannot move it to another run either. Identity is never a
                   ;; handler's to say.
                   (conform! enter-schema
                             (-> (merge state answer)
                                 (assoc :id to)
                                 (into (select-keys state [:instance])))
                             (assoc ctx :crossing :enter)))))
         (pure (ignored state event)))))))

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
  ([sh data] (initial sh nil data))
  ([sh instance data]
   (let [id (shape/initial-id sh)]
     (conform! (shape/enter-schema sh id)
               (cond-> (assoc data :id id)
                 (some? instance) (assoc :instance instance))
               {:crossing :enter :to id}))))
