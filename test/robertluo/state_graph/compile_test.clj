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
                       (shape/event :go [:map] (constantly {:id :somewhere-else}))
                       (shape/transition :a :go :b))]
    (is (= {:id :b} ((c/compile g) (c/initial g {}) {:id :go})))))

(deftest initial-enters-through-the-same-validation-as-every-other-state
  (let [g (ts/counter)]
    (testing "a caller's :id is overwritten, the same way a handler's is"
      (is (= {:id :idle} (c/initial g {:id :lies}))))
    (testing "and the initial state's own schema still has to be satisfied"
      (let [strict (shape/shape (shape/state :a [:map [:n :int]] {:initial true})
                                (shape/state :b [:map] {:final true})
                                (shape/event :go [:map] (constantly {}))
                                (shape/transition :a :go :b))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"enter" (c/initial strict {})))]
        ;; :schema is the OFFENDING CHILD and not the enclosing map, which is what
        ;; malli's own (:schema error) would give for a missing key; :type is what
        ;; tells a missing key from one whose value is legitimately nil.
        (is (= [{:in [:n] :value nil :schema :int :type :malli.core/missing-key}]
               (:errors (ex-data e))))))))

(deftest naming-the-run-is-optional-and-a-handler-cannot-change-it
  ;; :instance is the THIRD identity — :id on a state is its node, :id on an event is its
  ;; type — and it is the machinery's to write. Named once at the start, it rides the
  ;; whole reduction, and a handler answering one is overruled exactly as a handler
  ;; answering :id is.
  (let [g      (ts/counter)
        events [{:id :start :seed 0} {:id :set :to 7} {:id :stop}]]
    (testing "unnamed, and the README's headline reduction costs nothing for it"
      (is (= {:id :done :n 7} (reduce (c/compile g) (c/initial g {}) events))))

    (testing "named once, and carried the whole way without being spelled again"
      (is (= {:id :done :n 7 :instance "order-4711"}
             (reduce (c/compile g) (c/initial g "order-4711" {}) events))))

    (testing "a handler answering :instance is overruled, like one answering :id"
      (let [sneaky (shape/shape
                    (shape/state :a [:map] {:initial true})
                    (shape/state :b [:map] {:final true})
                    (shape/event :go [:map] (constantly {:instance "somebody-elses"}))
                    (shape/transition :a :go :b))]
        (is (= {:id :b :instance "mine"}
               ((c/compile sneaky) (c/initial sneaky "mine" {}) {:id :go})))))

    (testing "an event may name a run too, and the step does not care — it has only one
              in hand. That key is for the layer that ROUTES, which cannot read it off a
              state, having none yet"
      (is (= {:id :running :n 1 :instance "x"}
             ((c/compile g) (c/initial g "x" {}) {:id :start :seed 1 :instance "x"}))))))

;;; ----------------------------------------------------------------- the context

(deftest a-deferred-under-the-default-is-dereferenced
  ;; The decision was `just deref it`, and the mechanism is clojure.lang.IDeref rather
  ;; than anything of manifold's. THAT is what this proves, and it proves it with
  ;; CLOJURE'S OWN derefables — a delay and a promise are both IDeref — so the claim is
  ;; tested today, with no manifold on the classpath at all.
  ;;
  ;; What is NOT proven here and stays UNVERIFIED: that manifold's Deferred implements
  ;; IDeref. That is read and reasoned, and is to be checked the day manifold lands.
  (doseq [[what wrap] [["a delay"   #(delay %)]
                       ["a promise" #(doto (promise) (deliver %))]
                       ["a future"  #(future %)]
                       ["a plain map, which is not IDeref at all" identity]]]
    (testing what
      (let [g (shape/shape (shape/state :a [:map] {:initial true})
                           (shape/state :b [:map [:n :int]] {:final true})
                           (shape/event :go [:map] (fn [_] (wrap {:n 7})) [:map [:n :int]])
                           (shape/transition :a :go :b))]
        (is (= {:id :b :n 7} ((c/compile g) (c/initial g {}) {:id :go})))))))

(deftest an-ignored-event-can-be-heard
  ;; :ignored is the whole of `not an error, but not silent`. The DEFAULT is silent and
  ;; answers the state unchanged, which is what every other test here relies on; a layer
  ;; that wants to record replaces it. Asserted on what it is HANDED, since a recorder
  ;; that cannot tell which event went unhandled records nothing worth having.
  (let [g    (ts/counter)
        seen (atom [])
        step (c/compile g {:ignored (fn [state event]
                                      (swap! seen conj [(:id state) (:id event)])
                                      state)})
        init (c/initial g {})]
    (testing "the state is unchanged either way"
      (is (= init (step init {:id :stop})))
      (is (= init (step init {:id :no-such-event}))))
    (is (= [[:idle :stop] [:idle :no-such-event]] @seen)
        "both the catalogued event this state has no edge for and the unknown one")
    (testing "and a transition that DOES fire says nothing"
      (is (= {:id :running :n 1} (step init {:id :start :seed 1})))
      (is (= 2 (count @seen))))))

(deftest the-container-is-the-callers
  ;; :then and :pure are what keep manifold OUT of the core: swap them and the step
  ;; answers something else entirely, while compile never learns what that something is.
  ;; A one-key box stands in for a deferred — the point is that BOTH paths route through
  ;; the context, the transition through :then and the miss through :pure.
  (let [g    (ts/counter)
        box  (fn [v] {:boxed v})
        step (c/compile g {:then (fn [v f] (box (f v))) :pure box})
        init (c/initial g {})]
    (is (= {:boxed {:id :running :n 3}} (step init {:id :start :seed 3}))
        "a fired transition came back through :then")
    (is (= {:boxed init} (step init {:id :nope}))
        "and an event nobody handled came back through :pure")))

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
                             (shape/event :go [:map] (constantly {:n "seven"}) [:map [:n :int]])
                             (shape/transition :a :go :b))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"out"
                                    ((c/compile bad) (c/initial bad {}) {:id :go})))]
        (is (= :out (:crossing (ex-data e))))
        (is (= {:n "seven"} (:value (ex-data e))))))

    (testing "a state the target will not admit, where nothing declared :out"
      (let [bad (shape/shape (shape/state :a [:map] {:initial true})
                             (shape/state :b [:map [:n :int]] {:final true})
                             (shape/event :go [:map] (constantly {:n "seven"}))
                             (shape/transition :a :go :b))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"enter"
                                    ((c/compile bad) (c/initial bad {}) {:id :go})))]
        (is (= :enter (:crossing (ex-data e))))
        (is (= {:id :b :n "seven"} (:value (ex-data e))))))))
