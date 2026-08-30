(ns robertluo.state-graph.compile-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.compile :as c]
            [robertluo.state-graph.shape :as shape]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

;;; ----------------------------------------------------------------- properties

(defspec a-reduction-lands-only-in-a-state-the-graph-admits 100
  ;; The honest structural invariant, and it holds whatever the handlers do: whatever
  ;; sequence of events arrives — including ones the shape has never heard of — the
  ;; machine is somewhere the shape actually declares.
  (prop/for-all [parts ts/gen-shape
                 events (gen/vector ts/gen-event 0 20)]
    (let [g (apply shape/shape parts)
          end (reduce (c/compile g) (c/initial g {}) events)]
      (contains? (set (shape/states g)) (:id end)))))

(defspec an-event-with-no-transition-from-here-changes-nothing 100
  (prop/for-all [parts ts/gen-shape]
    (let [g (apply shape/shape parts)
          init (c/initial g {})]
      (= init ((c/compile g) init {:id :no-such-event})))))

(defspec a-prefix-and-then-the-rest-equals-the-whole 100
  ;; Not a test of reduce, which promises this for any pure fn. A test that the step
  ;; IS pure — that `compile` closed over an index and not over a run.
  (prop/for-all [parts ts/gen-shape
                 events (gen/vector ts/gen-event 0 20)
                 n gen/nat]
    (let [g (apply shape/shape parts)
          step (c/compile g)
          init (c/initial g {})
          [as bs] (split-at n events)]
      (= (reduce step (reduce step init as) bs)
         (reduce step init events)))))

;;; ------------------------------------------------------------------- the step

(deftest the-lifecycle-is-a-reduction
  (let [g (ts/counter)]
    (is (= {:id :done :n 7}
           (reduce (c/compile g) (c/initial g {})
                   [{:id :start :seed 0} {:id :set :to 7} {:id :stop}])))))

(deftest the-edge-decides-the-id-and-not-the-handler
  ;; The handler's answer is MERGED and the target's :id is assoc'd after, so a handler
  ;; cannot move the machine sideways past the edge that was supposed to decide it.
  (let [g (shape/shape (shape/state :a [:map] {:initial true})
                       (shape/state :b [:map] {:final true})
                       (shape/event :go [:map])
                       (shape/transition :a :go :b (constantly {:id :somewhere-else})))]
    (is (= {:id :b} ((c/compile g) (c/initial g {}) {:id :go})))))

(deftest initial-enters-through-the-same-validation-as-every-other-state
  (let [g (ts/counter)]
    (testing "a caller's :id is overwritten, the same way a handler's is"
      (is (= {:id :idle} (c/initial g {:id :lies}))))
    (testing "and the initial state's own schema still has to be satisfied"
      (let [strict (shape/shape (shape/state :a [:map [:n :int]] {:initial true})
                                (shape/state :b [:map] {:final true})
                                (shape/event :go [:map])
                                (shape/transition :a :go :b (constantly {})))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"enter" (c/initial strict {})))]
        ;; :schema is the OFFENDING CHILD and not the enclosing map, which is what
        ;; malli's own (:schema error) would give for a missing key; :type is what
        ;; tells a missing key from one whose value is legitimately nil.
        (is (= [{:in [:n] :value nil :schema :int :type :malli.core/missing-key}]
               (:errors (ex-data e))))))))

;;; ------------------------------------------------------- the crossings that throw

(deftest every-crossing-is-checked-in-the-code
  (let [g (ts/counter)
        step (c/compile g)
        running (step (c/initial g {}) {:id :start :seed 0})]

    (testing "an event that is not what its edge says it is"
      (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"event"
                                    (step running {:id :set :to "seven"})))]
        (is (= {:from :running :event :set :to :running :crossing :event}
               (select-keys (ex-data e) [:from :event :to :crossing])))
        (is (= [{:in [:to] :value "seven" :schema :int}] (:errors (ex-data e))))))

    (testing "a handler answering something its own :out denies — the better diagnosis,
              since the enter check one line later would blame the state instead"
      (let [bad (shape/shape (shape/state :a [:map] {:initial true})
                             (shape/state :b [:map [:n :int]] {:final true})
                             (shape/event :go [:map])
                             (shape/transition :a :go :b (constantly {:n "seven"}) [:map [:n :int]]))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out"
                                    ((c/compile bad) (c/initial bad {}) {:id :go})))]
        (is (= :out (:crossing (ex-data e))))
        (is (= {:n "seven"} (:value (ex-data e))))))

    (testing "a state the target will not admit, where nothing declared :out"
      (let [bad (shape/shape (shape/state :a [:map] {:initial true})
                             (shape/state :b [:map [:n :int]] {:final true})
                             (shape/event :go [:map])
                             (shape/transition :a :go :b (constantly {:n "seven"})))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"enter"
                                    ((c/compile bad) (c/initial bad {}) {:id :go})))]
        (is (= :enter (:crossing (ex-data e))))
        (is (= {:id :b :n "seven"} (:value (ex-data e))))))))
