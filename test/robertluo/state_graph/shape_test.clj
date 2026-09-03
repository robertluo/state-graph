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

(deftest an-event-given-only-a-schema-is-a-pure-lift
  ;; MOST HANDLERS ARE A select-keys, and the four-argument form said that three times:
  ;; the schema, a (fn [e] {:k (:k e)}) per key, and an :out that is the schema again. One
  ;; of the three is the fact.
  (let [short-form (shape/event :brief [:map [:brief :string]])
        long-form  (shape/event :brief [:map [:brief :string]]
                                (fn [e] {:brief (:brief e)})
                                [:map [:brief :string]])]
    (is (= {:brief "b"} ((:handler short-form) {:id :brief :brief "b"})))
    (is (= ((:handler long-form) {:id :brief :brief "b"})
           ((:handler short-form) {:id :brief :brief "b"}))
        "the same answer as spelling it out")
    (is (= (m/form (:out long-form)) (m/form (:out short-form)))
        "and the same :out, which is what the static check reads"))

  (testing "an event that carries nothing"
    (is (= {} ((:handler (shape/event :green [:map])) {:id :green}))))

  (testing "and it lifts NEITHER :id NOR :instance, which no state schema declares and the
            patch check refuses — the lift cannot name them because `mu/keys` does not"
    (is (= {:brief "b"}
           ((:handler (shape/event :brief [:map [:brief :string]]))
            {:id :brief :instance "run-1" :brief "b" :extra 1}))))

  (testing "an OPTIONAL key absent from the event is absent from the patch, which is
            exactly what a patch schema allows"
    (is (= {} ((:handler (shape/event :maybe [:map [:x {:optional true} :int]])) {:id :maybe})))
    (is (= {:x 1} ((:handler (shape/event :maybe [:map [:x {:optional true} :int]])) {:id :maybe :x 1})))))

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

;;; ----------------------------------------------------------------------- guards

(deftest disjoint-never-lies
  ;; What licenses two edges on one [state, event]: a PROOF that no one event can fire
  ;; both. Every :yes below is decidable, and the last two are the check declining to
  ;; guess rather than failing — :unknown is an answer.
  (doseq [[verdict a b]
          [[:yes [:map [:v [:= :green]]]      [:map [:v [:= :red]]]]
           [:yes [:map [:v :int]]             [:map [:v :string]]]
           [:yes [:map {:closed true}]        [:map [:fault :string]]]
           [:yes [:map [:n [:int {:max 2}]]]  [:map [:n [:int {:min 3}]]]]
           [:yes [:map [:n [:< 3]]]           [:map [:n [:>= 3]]]]
           [:yes [:= :a]                      [:= :b]]
           [:no  [:= :a]                      [:enum :a :b]]
           [:unknown [:map [:v [:= :green]]]  [:map [:v [:enum :green :red]]]]
           [:unknown [:map [:n [:int {:min 0 :max 5}]]] [:map [:n [:int {:min 3}]]]]
           [:unknown [:map [:v {:optional true} :int]]
                     [:map [:v {:optional true} :string]]]]]
    (is (= verdict (shape/disjoint a b)) (pr-str [a b])))

  (testing "a key OPTIONAL ON BOTH SIDES conflicts with nothing, a value being free to
            leave it out — which is the one place the map rule is not simply `some key
            disagrees`"
    (is (= :yes (shape/disjoint [:map [:v :int]] [:map [:v {:optional true} :string]]))
        "insisted on by one side is enough")))

(deftest a-guard-refines-the-event-rather-than-replacing-it
  ;; `accepted` is check/produced's sibling and has the same job: compose what the step
  ;; composes, or a check answers about something that never runs.
  (is (= [:map [:v [:= :green]]]
         (m/form (shape/accepted {:schema (m/schema [:map [:v [:enum :green :red]]])
                                  :when   (m/schema [:map [:v [:= :green]]])}))))
  (is (= [:map [:v [:enum :green :red]]]
         (m/form (shape/accepted {:schema (m/schema [:map [:v [:enum :green :red]]])})))
      "and with no guard it is the event's own schema, so one reading serves both"))

(deftest two-edges-on-one-event-must-be-PROVABLY-exclusive
  ;; The one check here that demands proven SAFETY rather than reporting a proven fault,
  ;; because determinism is the contract. And an ordered `first match wins` is not the
  ;; alternative on offer: ubergraph keeps out-edges in a SET.
  (let [parts [(shape/state :a [:map] {:initial true})
               (shape/state :b [:map])
               (shape/state :c [:map] {:final true})
               (shape/event :go [:map [:v [:enum :x :y :z]]] (constantly {}) [:map])]
        problems-of (fn [& ts] (apply shape/problems (concat parts ts)))]

    (testing "guards that cannot both hold are two legal edges"
      (is (= [] (problems-of (shape/transition :a :go :b {:when [:map [:v [:= :x]]]})
                             (shape/transition :a :go :c {:when [:map [:v [:= :y]]]})))))

    (testing "guards that might both hold are refused, and the fault says which two"
      (is (= [{:problem :ambiguous :from :a :event :go :to [:b :c] :verdict :unknown}]
             (problems-of (shape/transition :a :go :b {:when [:map [:v [:enum :x :y]]]})
                          (shape/transition :a :go :c {:when [:map [:v [:enum :y :z]]]})))))

    (testing "an UNGUARDED edge beside a guarded one needs no special case — with no
              :when there is nothing to refine, so it is disjoint from nothing"
      (is (= [:ambiguous]
             (mapv :problem
                   (problems-of (shape/transition :a :go :b)
                                (shape/transition :a :go :c {:when [:map [:v [:= :y]]]}))))))))

(deftest an-event-may-not-declare-the-machinery-s-words-either
  ;; An event's schema describes its PAYLOAD — what it carries. :id and :instance ride in
  ;; the value so the step can read them, and the step conforms the event with those keys
  ;; taken off, so declaring one would be describing something that is never checked.
  (is (= [{:problem :reserved-declared :id :go :key :id}]
         (shape/problems (shape/state :a [:map] {:initial true})
                         (shape/state :b [:map])
                         (shape/event :go [:map [:id :keyword]] (constantly {}))
                         (shape/transition :a :go :b)))))
