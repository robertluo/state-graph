(ns robertluo.state-graph.shape-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [robertluo.state-graph.shape :as shape]
            [robertluo.state-graph.test-support :as ts]
            [ubergraph.core :as uber]))

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

;;; -------------------------------------------------------------- the combines

(deftest a-key-may-say-how-a-patch-lands-on-it
  ;; What replaces the naive merge, read back off the node that declared it. The combine
  ;; itself is a CLOSURE — merging is domain logic and no fixed vocabulary of :+ and :max
  ;; expresses it — and what it PROMISES is data, because no function yields its own
  ;; algebra and the promise is the only part a checker can read.
  (let [f (ts/fanning)
        got (shape/combines f :choosing)]
    (is (= #{:best} (set (keys got))))
    (is (= ts/better (:combine (got :best))) "the function itself, not a name for one")
    (is (true? (:commutes? (got :best))))
    (is (= [:map [:score :int] [:by :string]] (m/form (:schema (got :best))))
        "and the key's own schema COMPILED, as `entries-of` answers one, which is what a
         law is generated from"))
  (is (= {} (shape/combines (ts/counter) :running))
      "a node declaring none is empty, and every shape written before this declared none"))

(deftest a-combine-and-its-promise-are-checked-referentially
  ;; Two faults answerable from the parts: whether the thing declared is a function, and
  ;; whether a law was declared with nothing to be a law about. Whether the law is TRUE is
  ;; a different question — check/laws refutes it, compile verifies it on the values.
  (is (= [{:problem :combine-not-a-function :id :x :key :k}]
         (shape/problems (shape/state :x [:map [:k {:combine 7} :int]] {:initial true}))))
  (is (= [{:problem :law-without-combine :id :x :key :k}]
         (shape/problems (shape/state :x [:map [:k {:combine/commutes true} :int]]
                                      {:initial true}))))
  (is (empty? (filter (comp #{:combine-not-a-function :law-without-combine} :problem)
                      (shape/problems (shape/state :x [:map [:k {:combine +} :int]]
                                                   {:initial true}))))
      "a combine with no law declared is fine — it simply licenses nothing"))

;;; ------------------------------------------------------ a completion transition

(deftest a-completion-transition-is-an-edge-and-not-a-node-attribute
  ;; WHICH IS WHAT BUYS THE STRUCTURAL CHECKS FOR NOTHING — `reachable`, `dead-ends`,
  ;; `finishable` and `traps` all walk the graph. Asserted here rather than there because
  ;; this is the fact those four rest on.
  (let [sh (shape/shape (shape/state :a [:map] {:initial true :done :b})
                        (shape/state :b [:map] {:final true}))]
    (testing "the graph has the arrow"
      (is (= [:b] (map uber/dest (uber/out-edges sh :a)))))
    (testing "`transitions` is about EVENTS and leaves it out"
      (is (empty? (shape/transitions sh))))
    (testing "`continuations` is where it is read, and nothing is left on the node"
      (is (= {:a [{:to :b}]} (shape/continuations sh)))
      (is (nil? (uber/attr sh :a :done))))
    (testing "and with no :outcome on it, which is what says EVERY way of finishing goes
              here — the form every shape written before outcomes existed has"
      (is (= [nil] (map :outcome (get (shape/continuations sh) :a)))))))

(deftest a-yield-rides-on-the-completion-edge
  (let [child (shape/shape (shape/state :c1 [:map] {:initial true})
                           (shape/state :c2 [:map [:r :string]] {:final true})
                           (shape/event :fin [:map [:r :string]])
                           (shape/transition :c1 :fin :c2))
        sh (shape/shape (shape/state :a [:map] {:initial true :machine child
                                                :done :b :yield [:map [:r :string]]})
                        (shape/state :b [:map [:r :string]] {:final true}))]
    (is (= :b (:to (first (get (shape/continuations sh) :a)))))
    (is (= [:map [:r :string]]
           (m/form (:yield (first (get (shape/continuations sh) :a))))))))

(deftest what-a-completion-transition-may-not-be
  (let [ok (fn [& parts] (map :problem (apply shape/problems parts)))
        ;; A CHILD THAT CAN START, which is not `shipping` — its own first state insists on
        ;; a :total and entering a child hands it no data at all, so nesting that one is
        ;; :machine-cannot-start. Worth meeting here rather than in anger.
        child (shape/shape (shape/state :c1 [:map] {:initial true})
                           (shape/state :c2 [:map] {:final true})
                           (shape/event :fin [:map])
                           (shape/transition :c1 :fin :c2))
        endless (shape/shape (shape/state :e [:map] {:initial true})
                             (shape/event :spin [:map])
                             (shape/transition :e :spin :e))]
    (testing "naming a state that is not there"
      (is (= [:unknown-state]
             (ok (shape/state :a [:map] {:initial true :done :nope})))))
    (testing "completing and being final are contradictory"
      (is (= [:done-and-final]
             (ok (shape/state :a [:map] {:initial true})
                 (shape/state :z [:map] {:final true :done :a})
                 (shape/event :go [:map]) (shape/transition :a :go :z)))))
    (testing "a state that continues ON ENTRY can never receive an event, so its own
              out-edges are dead code — the same fault as :unused-event"
      (is (= [:done-with-edges]
             (ok (shape/state :a [:map] {:initial true :done :z})
                 (shape/state :z [:map] {:final true})
                 (shape/event :go [:map]) (shape/transition :a :go :z)))))
    (testing "a NESTING node's edges are its ESCAPE and are not dead"
      (is (empty?
           (ok (shape/state :a [:map] {:initial true :machine child :done :z})
               (shape/state :z [:map] {:final true})
               (shape/event :go [:map]) (shape/transition :a :go :z)))))
    (testing "a :done waiting on a child that has no way to finish can never fire"
      (is (= [:machine-cannot-finish]
             (ok (shape/state :a [:map] {:initial true :machine endless :done :z})
                 (shape/state :z [:map] {:final true})))))))

(deftest a-cycle-among-entry-fired-continuations-is-a-PROVEN-infinite-loop
  ;; And being PROVEN is the whole reason it may be a fault at all: a completion transition
  ;; is unconditional, so the relation is a plain functional graph.
  (testing "both members are named"
    (is (= [{:problem :done-cycle :id :a} {:problem :done-cycle :id :b}]
           (filter (comp #{:done-cycle} :problem)
                   (shape/problems (shape/state :a [:map] {:initial true :done :b})
                                   (shape/state :b [:map] {:done :a}))))))
  (testing "a state LEADING INTO a cycle is not itself one, and is not named"
    (is (= [:b :c]
           (map :id (filter (comp #{:done-cycle} :problem)
                            (shape/problems (shape/state :a [:map] {:initial true :done :b})
                                            (shape/state :b [:map] {:done :c})
                                            (shape/state :c [:map] {:done :b})))))))
  (testing "a cycle through a nesting node needs EVENTS to close and is legal"
    (let [child (shape/shape (shape/state :c1 [:map] {:initial true})
                             (shape/state :c2 [:map] {:final true})
                             (shape/event :fin [:map])
                             (shape/transition :c1 :fin :c2))]
      (is (empty? (filter (comp #{:done-cycle} :problem)
                          (shape/problems
                           (shape/state :a [:map] {:initial true :machine child :done :b})
                           (shape/state :b [:map] {:done :a}))))))))

(deftest a-yield-needs-a-child-to-harvest-from-and-a-moment-to-harvest-on
  (let [ok (fn [& parts] (map :problem (apply shape/problems parts)))
        child (shape/shape (shape/state :c1 [:map] {:initial true})
                           (shape/state :c2 [:map] {:final true})
                           (shape/event :fin [:map])
                           (shape/transition :c1 :fin :c2))]
    (is (= [:yield-without-machine]
           (ok (shape/state :a [:map] {:initial true :done :z :yield [:map]})
               (shape/state :z [:map] {:final true}))))
    (is (= [:yield-without-done]
           (ok (shape/state :a [:map] {:initial true :machine child
                                       :yield [:map]})
               (shape/state :z [:map] {:final true})
               (shape/event :go [:map]) (shape/transition :a :go :z))))))

(deftest a-nesting-node-may-say-what-its-child-STARTS-with
  ;; :seed IS :yield'S MIRROR — one carries parent -> child at entry, the other child ->
  ;; parent at completion. Without it a nested machine could only ever be told its job by
  ;; the closure its shape was built from, so a node could not be RE-ENTERED with a
  ;; different job, which is exactly what a loop over a child machine is.
  (let [child (shape/shape (shape/state :c1 [:map [:job :string]] {:initial true})
                           (shape/state :c2 [:map [:job :string]] {:final true})
                           (shape/event :fin [:map])
                           (shape/transition :c1 :fin :c2))
        sh (shape/shape (shape/state :a [:map [:job :string]]
                                     {:initial true :machine child :seed [:map [:job :string]]})
                        (shape/state :z [:map] {:final true})
                        (shape/event :out [:map]) (shape/transition :a :out :z))]
    (testing "it is read off the NODE, where a yield is read off the edge — a seed is about
              entering this state, which is not a transition anywhere"
      (is (= [:map [:job :string]] (m/form (shape/seed sh :a))))
      (is (nil? (shape/seed sh :z))))
    (testing "and the child, which insists on data, could not have been nested WITHOUT one"
      (is (= [:machine-cannot-start]
             (map :problem
                  (shape/problems (shape/state :a [:map] {:initial true :machine child})
                                  (shape/state :z [:map] {:final true})
                                  (shape/event :out [:map]) (shape/transition :a :out :z))))))
    (testing "a seed with nothing to sow into is a declaration nobody reads, exactly as a
              yield with nothing to harvest from is"
      (is (= [:seed-without-machine]
             (map :problem
                  (shape/problems (shape/state :a [:map] {:initial true :seed [:map]})
                                  (shape/state :z [:map] {:final true})
                                  (shape/event :out [:map])
                                  (shape/transition :a :out :z))))))))

(deftest a-completion-may-say-where-each-OUTCOME-goes
  ;; NOT A GUARD, and that is the whole argument for it: what it reads is the STRUCTURAL
  ;; fact :done already reads — which state the child is in — one notch finer, over a set
  ;; that is finite and known at construction. No schema, no predicate, nothing to prove
  ;; disjoint. `MAY A STATE COMPLETE ON A CONDITION OVER ITS OWN DATA?` refuses the
  ;; ARITHMETIC cases and refuses them on decidability; this is not one of them.
  (let [child (shape/shape (shape/state :c1 [:map] {:initial true})
                           (shape/state :won  [:map [:prize :int]] {:final true})
                           (shape/state :lost [:map] {:final true})
                           (shape/event :win  [:map [:prize :int]])
                           (shape/event :lose [:map])
                           (shape/transition :c1 :win  :won)
                           (shape/transition :c1 :lose :lost))
        sh (shape/shape
            (shape/state :a [:map] {:initial true :machine child
                                    :done {:won  {:to :paid :yield [:map [:prize :int]]}
                                           :lost {:to :done}}})
            (shape/state :paid [:map [:prize :int]] {:final true})
            (shape/state :done [:map] {:final true}))]
    (testing "one EDGE per outcome, which is what buys the traversals for nothing all over
              again: two ways for a child to finish are two arrows"
      (is (= #{:paid :done} (set (map uber/dest (uber/out-edges sh :a)))))
      (is (= [{:outcome :lost :to :done}
              {:outcome :won :to :paid}]
             (mapv #(dissoc % :yield) (get (shape/continuations sh) :a)))))
    (testing "and the yield belongs to the branch it is harvested on"
      (is (= [nil [:map [:prize :int]]]
             (mapv #(some-> (:yield %) m/form) (get (shape/continuations sh) :a)))))
))

(deftest an-outcome-is-one-of-the-child-s-final-states
  (let [ok (fn [& parts] (map :problem (apply shape/problems parts)))
        child (shape/shape (shape/state :c1 [:map] {:initial true})
                           (shape/state :c2 [:map] {:final true})
                           (shape/event :fin [:map])
                           (shape/transition :c1 :fin :c2))]
    (testing "a key naming anything else is a branch that can never be taken"
      (is (= [:unknown-outcome]
             (ok (shape/state :a [:map] {:initial true :machine child
                                         :done {:c1 {:to :z}}})
                 (shape/state :z [:map] {:final true})))))
    (testing "and outcomes need a child to have them"
      (is (= [:outcome-without-machine]
             (ok (shape/state :a [:map] {:initial true :done {:c2 {:to :z}}})
                 (shape/state :z [:map] {:final true})))))
    (testing "a per-outcome :done carries each branch's own :yield, so one beside it is a
              second spelling that could only ever drift"
      (is (= [:yield-with-outcomes]
             (ok (shape/state :a [:map] {:initial true :machine child
                                         :done {:c2 {:to :z}} :yield [:map]})
                 (shape/state :z [:map] {:final true})))))
    (testing "every target is still checked for being there at all"
      (is (= [:unknown-state]
             (ok (shape/state :a [:map] {:initial true :machine child
                                         :done {:c2 {:to :nope}}})))))))

(deftest an-event-may-say-how-it-is-reported
  ;; THE ONE THING THE SHAPE COULD NOT SAY until now: whether an event comes from
  ;; the DRIVER or from the WORLD. A :report is that declaration, and an event
  ;; without one is the world's to supply.
  (let [reported (shape/event :judge [:map [:verdict :keyword]]
                              (fn [e] (select-keys e [:verdict])) nil
                              {:reads [:map [:n :int]]
                               :report (fn [seen] {:verdict (if (even? (:n seen)) :green :red)})})]
    (testing "the constructor keeps both halves, where it used to drop unknown options"
      (is (fn? (:report reported)))
      (is (some? (:reads reported))))

    (testing "and they are denormalised onto every edge that fires the event, so a driver
              asks the SHAPE which events it drives rather than writing them down again"
      (let [sh (shape/shape (shape/state :a [:map [:n :int]] {:initial true})
                            (shape/state :z [:map [:verdict :keyword]] {:final true})
                            reported
                            (shape/transition :a :judge :z))]
        (is (= #{:judge} (set (keys (shape/reports sh)))))
        (is (= {:verdict :green} ((:report (get (shape/reports sh) :judge)) {:n 4})))
        (is (= {:verdict :red} ((:report (get (shape/reports sh) :judge)) {:n 3})))))))

(deftest a-view-with-nothing-to-read-it-is-refused
  ;; :reads is the view a REPORT is handed, so one without a report is a view
  ;; nothing will ever see — answerable from the parts, so the shape never exists.
  (let [ok (fn [& parts] (map :problem (apply shape/problems parts)))]
    (is (= [:reads-without-report]
           (ok (shape/state :a [:map] {:initial true})
               (shape/state :z [:map] {:final true})
               (shape/event :go [:map] (fn [_] {}) nil {:reads [:map [:n :int]]})
               (shape/transition :a :go :z))))))

(deftest a-shape-has-a-stable-id-and-hash-is-not-it
  ;; A TRANSCRIPT ROW THAT CANNOT SAY WHICH MACHINE PRODUCED IT is a row nobody
  ;; can audit, so a shape needs an id that survives a JVM restart.
  (let [sig  (fn [] [:and vector? [:fn {:error/message "nope"} (fn [x] (vector? x))]])
        make (fn [{:keys [to schema handler guard]
                   :or {to :z schema :int handler (fn [e] (select-keys e [:n]))}}]
               (shape/shape
                (shape/state :a [:map [:n schema] [:sig (sig)]] {:initial true})
                (shape/state :z [:map [:n :int] [:sig (sig)]] {:final true})
                (shape/state :y [:map [:n :int] [:sig (sig)]] {:final true})
                (shape/event :go [:map [:n :int]] handler)
                (if guard
                  (shape/transition :a :go to {:when guard})
                  (shape/transition :a :go to))))
        base (make {})]

    (testing "`hash` CANNOT be it, and that is why this exists: two structurally
              identical shapes are neither = nor equal-hashed, their handlers being
              distinct closures and their schemas distinct compiled objects — so it
              would change on every namespace load"
      (is (not= (hash base) (hash (make {}))))
      (is (not= base (make {}))))

    (testing "the fingerprint IS stable, including over a schema holding an INLINE
              closure — which `m/form` renders as an #object with a hex address that
              differs every process, hence `plain` erasing it"
      (is (= (shape/fingerprint base) (shape/fingerprint (make {}))))
      (is (= 64 (count (shape/fingerprint base)))))

    (testing "and it moves for everything that is DATA"
      (is (not= (shape/fingerprint base) (shape/fingerprint (make {:schema :string}))))
      (is (not= (shape/fingerprint base) (shape/fingerprint (make {:to :y}))))
      (is (not= (shape/fingerprint base)
                (shape/fingerprint (make {:guard [:map [:n [:int {:max 3}]]]})))))

    (testing "AND NOT FOR A HANDLER, which is the limit and has to be said out loud:
              it proves the GRAPH matched, never that the same code ran"
      (is (= (shape/fingerprint base)
             (shape/fingerprint (make {:handler (fn [_] {:n 99})})))))

    (testing "`canonical` is what to diff when two disagree, since a hash can only
              say `different`"
      (is (= (shape/canonical base) (shape/canonical (make {}))))
      (is (not= (shape/canonical base) (shape/canonical (make {:to :y})))))))

(deftest a-nested-machine-is-its-child-s-fingerprint
  ;; So the recursion terminates, and a change deep in a child still moves the parent.
  (let [child (fn [s] (shape/shape (shape/state :c1 [:map] {:initial true})
                                   (shape/state :c2 [:map [:v s]] {:final true})
                                   (shape/event :inner [:map [:v s]])
                                   (shape/transition :c1 :inner :c2)))
        parent (fn [s] (shape/shape
                        (shape/state :p1 [:map] {:initial true :machine (child s)})
                        (shape/state :p2 [:map] {:final true})
                        (shape/event :out [:map])
                        (shape/transition :p1 :out :p2)))]
    (is (= (shape/fingerprint (parent :int)) (shape/fingerprint (parent :int))))
    (is (not= (shape/fingerprint (parent :int)) (shape/fingerprint (parent :string)))
        "a change inside the CHILD moves the parent's fingerprint")))
