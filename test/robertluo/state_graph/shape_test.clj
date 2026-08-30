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
        go   (shape/event :go [:map])
        t    (shape/transition :idle :go :run (constantly {}))
        kinds (fn [& parts] (mapv :problem (apply shape/problems parts)))]

    (testing "a part that is not the thing it says it is — one check for a handler that
              is not a fn, a schema that is not a map schema, and every missing key"
      (is (= [:malformed] (kinds idle run go (assoc t :handler "not a fn")))))

    (testing "two parts under one id, which a map would silently collapse"
      (is (= [:duplicate] (kinds idle run (shape/state :run [:map]) go t))))

    (testing "a transition naming a state or an event that is not there"
      (is (= [:unknown-state] (kinds idle run go (shape/transition :idle :go :nowhere (constantly {})))))
      (is (= [:unknown-event] (kinds idle run go t (shape/transition :run :ghost :idle (constantly {}))))))

    (testing "determinism — two transitions sharing a [from event] make compile a search"
      (is (= [:ambiguous] (kinds idle run go t (shape/transition :idle :go :idle (constantly {}))))))

    (testing "an event nothing fires. The catalogue exists only here, so this is the
              only moment the check is answerable at all"
      (is (= [:unused-event] (kinds idle run go t (shape/event :never [:map])))))

    (testing "exactly one root, because the reachability check above needs one"
      (is (= [:initial] (kinds (shape/state :idle [:map]) run go t)))
      (is (= [:initial] (kinds idle (shape/state :run [:map] {:initial true}) go t))))

    (testing ":id is the shape's word, not a state's"
      (is (= [:id-declared]
             (kinds (shape/state :idle [:map [:id :keyword]] {:initial true}) run go t))))))

(deftest a-schema-argument-has-to-be-a-map-schema
  ;; The constructors declare MapSchema rather than :any, so a schema that is not one
  ;; is refused where it is PASSED. Asserted on (:type (ex-data e)) and not on the
  ;; message, which is the bare keyword ":malli.core/invalid-input" and matches no
  ;; sentence. This needs the instrumentation fixture to mean anything.
  (doseq [bad [[:vector :int] "nonsense" 42 nil]]
    (testing (pr-str bad)
      (let [e (is (thrown? clojure.lang.ExceptionInfo (shape/state :idle bad)))]
        (is (= :malli.core/invalid-input (:type (ex-data e)))))
      (let [e (is (thrown? clojure.lang.ExceptionInfo (shape/event :go bad)))]
        (is (= :malli.core/invalid-input (:type (ex-data e)))))))
  (testing "a form and an already-compiled schema are both fine"
    (is (map? (shape/state :idle [:map [:n :int]])))
    (is (map? (shape/state :idle (m/schema [:map [:n :int]])))))
  (testing "and an :out that is not a map schema is refused too, while nil is not"
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (shape/transition :a :go :b (constantly {}) [:vector :int])))]
      (is (= :malli.core/invalid-input (:type (ex-data e)))))
    (is (map? (shape/transition :a :go :b (constantly {}))))))

(deftest shape-refuses-to-build-and-says-why
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"problems"
                                (shape/shape (shape/state :idle [:map] {:initial true})
                                             (shape/event :ghost [:map]))))]
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

(deftest enter-schema-writes-the-id-in
  ;; Asserted by validating VALUES rather than comparing forms: a form comparison is
  ;; the derivation restated, and m/form over a [:fn ...] is not comparable anyway.
  (let [running (shape/enter-schema (ts/counter) :running)]
    (is (m/validate running {:id :running :n 1}))
    (is (not (m/validate running {:id :idle :n 1})) "landing elsewhere is a failure to enter")
    (is (not (m/validate running {:id :running})) "the state's own schema still applies")))

(deftest the-shape-knows-where-a-run-starts-and-ends
  (let [g (ts/counter)]
    (is (= :idle (shape/initial-id g)))
    (is (shape/final? g :done))
    (is (not (shape/final? g :running)))))
