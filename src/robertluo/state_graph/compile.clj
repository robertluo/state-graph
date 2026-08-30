(ns robertluo.state-graph.compile
  "shape -> (fn [state event] state'). The only namespace that turns data into a
   function.

   The lifecycle of an instance is (reduce (compile shape) (initial shape data) events)
   and that is the whole runtime: no object, no atom, no protocol. Everything above
   this is a way of getting events into that reduction or states out of it — which is
   why a stream is the async layer and a log is the persistence layer, and why neither
   of them is the core. Both take the STEP FUNCTION as a value, so neither requires
   this namespace and neither ever learns what a shape is.

   Requires the shape and malli. It knows nothing of streams or databases."
  (:refer-clojure :exclude [compile])
  (:require [robertluo.state-graph.shape :as shape]))

(def State
  "A state is a map that says which node it is in. The name cannot live only in the
   graph: the step function has to know whose out-edges to search."
  [:map [:id shape/Id]])

(def Event
  "An event says its own name for the same reason, and the step matches an edge on it."
  [:map [:id shape/Id]])

(def Step [:=> [:cat State Event] State])

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

(defn compile
  "The shape as an ordinary Clojure function of a state and an event.

   An event the current state has no transition for leaves the state UNCHANGED — an
   event a state does not care about is not an error, and it keeps the reduction total.
   What throws is a crossing that does not hold: an event that is not what the edge
   says it is, a handler answering something its own :out denies, or a state that its
   target's schema will not admit. Those are defects, not facts about the run.

   The handler takes THE EVENT ALONE. Its answer is merged into the state and the
   target's :id is assoc'd AFTER, so a handler that writes :id is simply overwritten:
   identity is the shape's to say."
  {:malli/schema [:=> [:cat shape/Shape] Step]}
  [sh]
  (let [idx (index sh)]
    (fn step [state event]
      (if-let [{:keys [to handler out event-schema enter-schema]}
               (idx [(:id state) (:id event)])]
        (let [ctx {:from (:id state) :event (:id event) :to to}]
          (conform! event-schema event (assoc ctx :crossing :event))
          (let [answer (handler event)]
            ;; :out is validated only where it is declared. Its real job is the STATIC
            ;; check; here it buys a better diagnosis — `the handler is wrong` rather
            ;; than `the state is wrong` one line later.
            (when out (conform! out answer (assoc ctx :crossing :out)))
            (conform! enter-schema (assoc (merge state answer) :id to)
                      (assoc ctx :crossing :enter))))
        state))))

(defn initial
  "The first state, ENTERED THROUGH THE SAME VALIDATION as every other one. The shape
   knows which node a run starts in; the starting data is the caller's."
  {:malli/schema [:=> [:cat shape/Shape :map] State]}
  [sh data]
  (let [id (shape/initial-id sh)]
    (conform! (shape/enter-schema sh id) (assoc data :id id)
              {:crossing :enter :to id})))
