(ns robertluo.state-graph.test-support
  "What every suite needs and no source namespace should have to know.

   Not named <ns>-test, so kaocha does not load it as a suite."
  (:require [clojure.test.check.generators :as gen]
            [malli.instrument :as mi]
            [robertluo.state-graph.shape :as shape]))

(def namespaces
  '[robertluo.state-graph.shape robertluo.state-graph.compile])

(defn instrumented
  "A fixture that makes the :malli/schema metadata actually do something. mi/collect!
   is a MACRO reading *ns*, so in a test file it would collect the TEST; clj-collect!
   is the plain function underneath it and takes {:ns [...]} as a value."
  [f]
  (mi/clj-collect! {:ns namespaces})
  (mi/instrument!)
  (f)
  (mi/unstrument!))

(def gen-shape
  "A WELL-FORMED shape, as the parts it is built from.

   Every state schema is [:map] and every event schema is [:map], on purpose: these
   generate the STRUCTURAL properties, where what is being asked is about the graph and
   the lookup. A schema that could reject something would make a failure ambiguous
   between the two, and what schemas enforce is asserted by example instead.

   Every event is fired by at least one transition and no two transitions share a
   [from event], so `problems` has nothing to find — which is itself the first property.

   gen/let in test.check 1.1.1 does NOT support :let bindings, hence bind and fmap."
  (gen/bind
   (gen/tuple (gen/choose 2 5) (gen/choose 1 4))
   (fn [[n-states n-events]]
     (let [sids (mapv #(keyword (str "s" %)) (range n-states))
           eids (mapv #(keyword (str "e" %)) (range n-events))]
       (gen/fmap
        (fn [groups]
          (concat (map-indexed (fn [i s] (shape/state s [:map] (when (zero? i) {:initial true}))) sids)
                  (map (fn [e] (shape/event e [:map])) eids)
                  (apply concat groups)))
        (apply gen/tuple
               (for [e eids]
                 (gen/bind
                  (gen/not-empty (gen/set (gen/elements sids)))
                  (fn [froms]
                    (gen/fmap
                     (fn [tos] (mapv (fn [f t] (shape/transition f e t (constantly {}))) froms tos))
                     (gen/vector (gen/elements sids) (count froms))))))))))))

(def gen-event
  "An event to feed a generated shape — mostly ones it knows, sometimes ones it does
   not, because what an unadmitted event does is half of what `compile` promises."
  (gen/fmap (fn [id] {:id id})
            (gen/elements [:e0 :e1 :e2 :e3 :e4 :e5 :no-such-event])))

(defn parts-of
  "The parts of one kind, out of a generated shape."
  [kind parts]
  (filter #(= kind (:robertluo.state-graph.shape/kind %)) parts))

(defn counter
  "The canonical example shape, where the schemas DO bite: a counter whose :n the
   events carry, since a handler never sees the state it is changing."
  []
  (shape/shape
   (shape/state :idle [:map] {:initial true})
   (shape/state :running [:map [:n :int]])
   (shape/state :done [:map [:n :int]] {:final true})
   (shape/event :start [:map [:seed :int]])
   (shape/event :set [:map [:to :int]])
   (shape/event :stop [:map])
   (shape/transition :idle :start :running (fn [e] {:n (:seed e)}) [:map [:n :int]])
   (shape/transition :running :set :running (fn [e] {:n (:to e)}) [:map [:n :int]])
   (shape/transition :running :stop :done (fn [_] {}))))
