(ns robertluo.state-graph.shape-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [robertluo.state-graph.shape :as shape]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

;;; ----------------------------------------------------------------- properties

(defspec a-well-formed-shape-has-no-problems 100
  ;; The independent invariant for a checker: it must not ACCUSE what is fine. Every
  ;; example below asserts a check fires; only a property can assert it stays quiet.
  (prop/for-all [parts ts/gen-shape]
    (empty? (apply shape/problems parts))))

(defspec no-transition-is-lost-to-the-graph 100
  ;; Not a test that ubergraph adds an edge — a test that `multidigraph` was the right
  ;; call. Two events joining one pair of states are two edges, and a plain digraph
  ;; would silently keep one of them, which is the kind of loss nothing else notices.
  (prop/for-all [parts ts/gen-shape]
    (= (count (ts/parts-of :transition parts))
       (count (shape/transitions (apply shape/shape parts))))))

;;; ------------------------------------------------------------------- the checks

(deftest problems-are-data
  (let [idle (shape/state :idle [:map] {:initial true})
        run  (shape/state :run [:map])
        go   (shape/event :go [:map] (constantly {}))
        t    (shape/transition :idle :go :run)
        kinds (fn [& parts] (mapv :problem (apply shape/problems parts)))]

    (testing "a part that is not the thing it says it is — one check for a handler that
              is not a fn, a schema that is not a map schema, and every missing key.
              The handler hangs off the EVENT, so that is where a bad one goes"
      (is (= [:malformed] (kinds idle run (assoc go :handler "not a fn") t))))

    (testing "two parts under one id, which a map would silently collapse"
      (is (= [:duplicate] (kinds idle run (shape/state :run [:map]) go t))))

    (testing "a transition naming a state or an event that is not there"
      (is (= [:unknown-state] (kinds idle run go (shape/transition :idle :go :nowhere))))
      (is (= [:unknown-event] (kinds idle run go t (shape/transition :run :ghost :idle)))))

    (testing "determinism — two transitions sharing a [from event] make compile a search"
      (is (= [:ambiguous] (kinds idle run go t (shape/transition :idle :go :idle)))))

    (testing "an event nothing fires. The catalogue exists only here, so this is the
              only moment the check is answerable at all"
      (is (= [:unused-event] (kinds idle run go t (shape/event :never [:map] (constantly {}))))))

    (testing "exactly one root, because the reachability check above needs one"
      (is (= [:initial] (kinds (shape/state :idle [:map]) run go t)))
      (is (= [:initial] (kinds idle (shape/state :run [:map] {:initial true}) go t))))

    (testing ":id and :instance are the machinery's words, not a state's"
      (is (= [:reserved-declared]
             (kinds (shape/state :idle [:map [:id :keyword]] {:initial true}) run go t)))
      (is (= [:reserved-declared]
             (kinds (shape/state :idle [:map [:instance :string]] {:initial true}) run go t)))
      (is (= [{:problem :reserved-declared :id :run :key :instance}]
             (shape/problems idle (shape/state :run [:map [:instance :string]]) go t))
          "and it says WHICH key, since there are two of them now"))))

(deftest a-schema-argument-has-to-be-a-map-schema
  ;; The constructors declare MapSchema rather than :any, so a schema that is not one
  ;; is refused where it is PASSED. Asserted on (:type (ex-data e)) and not on the
  ;; message, which is the bare keyword ":malli.core/invalid-input" and matches no
  ;; sentence. This needs the instrumentation fixture to mean anything.
  (doseq [bad [[:vector :int] "nonsense" 42 nil]]
    (testing (pr-str bad)
      (let [e (is (thrown? clojure.lang.ExceptionInfo (shape/state :idle bad)))]
        (is (= :malli.core/invalid-input (:type (ex-data e)))))
      (let [e (is (thrown? clojure.lang.ExceptionInfo (shape/event :go bad (constantly {}))))]
        (is (= :malli.core/invalid-input (:type (ex-data e)))))))
  (testing "a form and an already-compiled schema are both fine"
    (is (map? (shape/state :idle [:map [:n :int]])))
    (is (map? (shape/state :idle (m/schema [:map [:n :int]])))))
  (testing "and an :out that is not a map schema is refused too, while nil is not.
            :out is the EVENT's now, so that is where it is refused"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (shape/event :go [:map] (constantly {}) [:vector :int])))]
      (is (= :malli.core/invalid-input (:type (ex-data e)))))
    (is (map? (shape/event :go [:map] (constantly {}))))))

(deftest shape-refuses-to-build-and-says-why
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"problems"
                                (shape/shape (shape/state :idle [:map] {:initial true})
                                             (shape/event :ghost [:map] (constantly {})))))]
    (is (= [{:problem :unused-event :id :ghost}] (:problems (ex-data e))))))

;;; ------------------------------------------------------------------- reading

(deftest an-edge-carries-its-events-schema
  ;; The catalogue is consumed at construction and not kept, so if this does not hold
  ;; the event's schema is gone for good and `compile` has nothing to check against.
  (let [tick (->> (shape/transitions (ts/counter))
                  (filter #(= :set (:event %)))
                  first)]
    (is (m/validate (:schema tick) {:id :set :to 3}))
    (is (not (m/validate (:schema tick) {:id :set :to "three"})))))

(deftest one-event-one-handler-however-many-edges
  ;; What moving the handler onto the EVENT bought, and the only thing that can regress
  ;; it. Two edges fire :hop, from different states to different targets, and ONE
  ;; declaration stands behind both — where before there were two, free to disagree.
  ;; This fails if `shape` writes the catalogue onto some edges and not others.
  (let [h  (fn [e] {:n (:n e)})
        sh (shape/shape
            (shape/state :a [:map] {:initial true})
            (shape/state :b [:map [:n :int]])
            (shape/state :c [:map [:n :int]])
            (shape/event :hop [:map [:n :int]] h [:map [:n :int]])
            (shape/transition :a :hop :b)
            (shape/transition :b :hop :c))
        hops (filter #(= :hop (:event %)) (shape/transitions sh))]
    (is (= 2 (count hops)) "two edges, one event")
    (is (every? #(identical? h (:handler %)) hops)
        "the event's handler reached every edge that fires it, and is the SAME one")
    (is (every? #(some? (:out %)) hops)
        "and so did its :out, which is what the static check reads")))

(deftest enter-schema-writes-the-id-in
  ;; Asserted by validating VALUES rather than comparing forms: a form comparison is
  ;; the derivation restated, and m/form over a [:fn ...] is not comparable anyway.
  (let [running (shape/enter-schema (ts/counter) :running)]
    (is (m/validate running {:id :running :n 1}))
    (is (not (m/validate running {:id :idle :n 1})) "landing elsewhere is a failure to enter")
    (is (not (m/validate running {:id :running})) "the state's own schema still applies")
    (testing ":instance is permitted and never required — one machine reduced over one
              seq needs no name for itself, and the async layer will insist instead"
      (is (m/validate running {:id :running :n 1 :instance "order-4711"}))
      (is (m/validate running {:id :running :n 1}))
      (is (not (m/validate running {:id :running :n 1 :instance nil}))
          "but a partition key that may be nil is a bug waiting for the second machine"))))

(deftest the-shape-knows-where-a-run-starts-and-ends
  (let [g (ts/counter)]
    (is (= :idle (shape/initial-id g)))
    (is (shape/final? g :done))
    (is (not (shape/final? g :running)))))

;;; ---------------------------------------------------------------- nesting

(deftest a-nested-machine-must-be-able-to-start
  ;; Entering a node with a machine enters that child at its own initial state with NO
  ;; data, so a child insisting on some could never begin. Referential — answerable from
  ;; the parts — which is why it is refused at construction and not at the first event.
  (let [needy (shape/shape
               (shape/state :needs [:map [:x :int]] {:initial true})
               (shape/state :z     [:map [:x :int]] {:final true})
               (shape/event :g [:map] (constantly {}) [:map])
               (shape/transition :needs :g :z))]
    (is (= [{:problem :machine-cannot-start :id :host :initial :needs}]
           (shape/problems (shape/state :host [:map] {:initial true :machine needy}))))))

(deftest sub-is-the-machinerys-word
  ;; Like :id and :instance: a state redeclaring it would be describing something written
  ;; over it on every entry.
  (is (= [{:problem :reserved-declared :id :s :key :sub}]
         (shape/problems (shape/state :s [:map [:sub :map]] {:initial true})))))
