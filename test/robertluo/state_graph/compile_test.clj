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

(deftest the-edge-decides-the-id-and-a-handler-that-says-otherwise-is-REFUSED
  ;; AN EVENT IS THE ONLY WAY A TRANSITION HAPPENS. A handler naming :id is asking for a
  ;; transition it was not given, and it used to be silently overwritten — which made the
  ;; rule a convention the code quietly repaired rather than one it enforced.
  ;;
  ;; IT NEEDS NO SPECIAL CASE. A state schema describes the map WITHOUT :id, :instance or
  ;; :sub, so naming one is answering a key the state does not declare, and the patch check
  ;; refuses it for the same reason it refuses a typo.
  (let [g (shape/shape (shape/state :a [:map] {:initial true})
                       (shape/state :b [:map] {:final true})
                       (shape/event :go [:map] (constantly {:id :somewhere-else}))
                       (shape/transition :a :go :b))
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"answer"
                                ((c/compile g) (c/initial g {}) {:id :go})))]
    (is (= :answer (:crossing (ex-data e))))
    (is (= :malli.core/extra-key (:type (first (:errors (ex-data e))))))
    (is (= [:id] (:in (first (:errors (ex-data e))))))))

(deftest a-key-the-target-does-not-declare-is-refused-and-not-dropped
  ;; The general rule the one above is a case of. The merge is projected onto the target's
  ;; own keys, so an undeclared key never reached the state anyway — it EVAPORATED. A
  ;; handler computing something the machine throws away is a defect, and silence made it
  ;; look like a feature.
  (let [g (shape/shape (shape/state :a [:map] {:initial true})
                       (shape/state :b [:map [:n :int]] {:final true})
                       (shape/event :go [:map] (constantly {:n 1 :typo 2}))
                       (shape/transition :a :go :b))
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"answer"
                                ((c/compile g) (c/initial g {}) {:id :go})))]
    (is (= [:typo] (:in (first (:errors (ex-data e))))))))

(deftest a-handler-answers-a-PATCH-so-saying-nothing-is-always-allowed
  ;; The other half of the patch check: every key OPTIONAL. A handler says what changed,
  ;; and what it does not mention the state it is changing already holds.
  (let [g (shape/shape (shape/state :a [:map [:n :int]] {:initial true})
                       (shape/state :b [:map [:n :int]] {:final true})
                       (shape/event :go [:map] (constantly {}))
                       (shape/transition :a :go :b))]
    (is (= {:id :b :n 3} ((c/compile g) (c/initial g {:n 3}) {:id :go})))))

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

    (testing "a handler answering :instance is REFUSED, like one answering :id — moving a
              run to another run is a transition nobody gave it either"
      (let [sneaky (shape/shape
                    (shape/state :a [:map] {:initial true})
                    (shape/state :b [:map] {:final true})
                    (shape/event :go [:map] (constantly {:instance "somebody-elses"}))
                    (shape/transition :a :go :b))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"answer"
                                    ((c/compile sneaky) (c/initial sneaky "mine" {}) {:id :go})))]
        (is (= [:instance] (:in (first (:errors (ex-data e))))))))

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

    (testing "a handler answering a value the target's schema denies, where nothing
              declared :out — caught as the ANSWER and not as the state, which is the
              better diagnosis for the same reason :out is"
      (let [bad (shape/shape (shape/state :a [:map] {:initial true})
                             (shape/state :b [:map [:n :int]] {:final true})
                             (shape/event :go [:map] (constantly {:n "seven"}))
                             (shape/transition :a :go :b))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"answer"
                                    ((c/compile bad) (c/initial bad {}) {:id :go})))]
        (is (= :answer (:crossing (ex-data e))))
        (is (= {:n "seven"} (:value (ex-data e))))))

    (testing "and :enter is still the crossing that catches what only the WHOLE state can
              be wrong about — a required key nobody supplied, which a patch is allowed
              not to mention"
      (let [bad (shape/shape (shape/state :a [:map] {:initial true})
                             (shape/state :b [:map [:n :int]] {:final true})
                             (shape/event :go [:map] (constantly {}))
                             (shape/transition :a :go :b))
            e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"enter"
                                    ((c/compile bad) (c/initial bad {}) {:id :go})))]
        (is (= :enter (:crossing (ex-data e))))
        (is (= :malli.core/missing-key (:type (first (:errors (ex-data e))))))))))

;;; ---------------------------------------------------------------- nesting

(defn- child []
  (shape/shape
   (shape/state :unpaid     [:map]                 {:initial true})
   (shape/state :authorized [:map [:auth :string]])
   (shape/state :captured   [:map [:auth :string]] {:final true})
   (shape/event :authorize [:map [:auth :string]] (fn [e] {:auth (:auth e)}) [:map [:auth :string]])
   (shape/event :capture   [:map]                 (constantly {})           [:map])
   (shape/transition :unpaid     :authorize :authorized)
   (shape/transition :authorized :capture    :captured)))

(defn- parent []
  (shape/shape
   (shape/state :cart      [:map] {:initial true})
   (shape/state :paying    [:map] {:machine (child)})
   (shape/state :shipped   [:map] {:final true})
   (shape/state :cancelled [:map] {:final true})
   (shape/event :checkout [:map] (constantly {}) [:map])
   (shape/event :ship     [:map] (constantly {}) [:map])
   (shape/event :cancel   [:map] (constantly {}) [:map])
   (shape/transition :cart      :checkout :paying)
   (shape/transition :paying    :checkout :paying)
   (shape/transition :paying    :ship     :shipped)
   (shape/transition :paying    :cancel   :cancelled)))

(deftest a-nested-machine-gets-the-event-first
  ;; INNER FIRST, and the parent stays where it is while its child moves.
  (let [g (parent)
        step (c/compile g)]
    (is (= [[:cart nil] [:paying :unpaid] [:paying :authorized] [:paying :captured] [:shipped nil]]
           (mapv (juxt :id (comp :id :sub))
                 (reductions step (c/initial g {})
                             [{:id :checkout} {:id :authorize :auth "tok_9"}
                              {:id :capture} {:id :ship}])))
        "two events moved the child, and only the third moved the parent")))

(deftest a-child-shields-only-what-it-knows
  ;; The mechanism that makes the parent's edges the ESCAPE and needs no guard: whether an
  ;; event reaches the parent is decided by the CHILD'S OWN VOCABULARY.
  (let [g (parent)
        step (c/compile g)
        paying (step (c/initial g {}) {:id :checkout})]
    (is (= :cancelled (:id (step paying {:id :cancel})))
        ":cancel is not the child's word, so it escapes at once")
    (is (= [:paying :authorized] ((juxt :id (comp :id :sub)) (step paying {:id :authorize :auth "t"})))
        ":authorize is the child's word, so the parent never sees it")))

(deftest a-finished-child-stops-competing
  ;; The property the whole design rests on: no done-event, no guard, no queue — a final
  ;; state admits nothing, so every later event falls through to the parent by itself.
  (let [g (parent)
        step (c/compile g)
        captured (reduce step (c/initial g {})
                         [{:id :checkout} {:id :authorize :auth "t"} {:id :capture}])]
    (is (= :captured (:id (:sub captured))))
    (is (= captured (step captured {:id :authorize :auth "again"}))
        "the child is done and answers unchanged")
    (is (= :shipped (:id (step captured {:id :ship}))))))

(deftest the-machinery-owns-sub-and-a-handler-that-reaches-for-it-is-REFUSED
  ;; :sub is what a nested machine is doing, and reaching into it is a transition in
  ;; somebody else's machine. Refused by the patch check, no special case: no state schema
  ;; declares :sub.
  (let [reaching (shape/shape
                  (shape/state :cart   [:map] {:initial true})
                  (shape/state :paying [:map] {:machine (child)})
                  (shape/event :checkout [:map] (constantly {:sub {:id :hacked}}) [:map])
                  (shape/transition :cart :checkout :paying))
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"answer"
                                ((c/compile reaching) (c/initial reaching {}) {:id :checkout})))]
    (is (= [:sub] (:in (first (:errors (ex-data e)))))))

  (let [g (parent)
        step (c/compile g)
        paying (step (c/initial g {}) {:id :checkout})
        moved (step paying {:id :authorize :auth "t"})]
    (is (= {:id :unpaid} (:sub paying))
        "the machinery seeded the child's first state, the handler having said nothing")
    (is (= {:id :unpaid} (:sub (step moved {:id :checkout})))
        "re-entering the node RESTARTS the child, entering being entering")
    (is (= {:id :shipped} (step moved {:id :ship}))
        "and leaving drops it — a merge keeps every key, so a child left behind would ride along")))

(deftest the-first-state-seeds-a-nested-machine
  (let [g (shape/shape
           (shape/state :only [:map] {:initial true :machine (child)})
           (shape/state :done [:map] {:final true})
           (shape/event :fin [:map] (constantly {}) [:map])
           (shape/transition :only :fin :done))]
    (is (= {:id :only :sub {:id :unpaid}} (c/initial g {}))
        "a run starting in a nesting node starts its child too")))

(deftest a-nested-handler-may-answer-later
  ;; The Context composes ACROSS the nesting boundary: the child is compiled with the same
  ;; one, so a child handler may answer a derefable wherever a parent's may. Asserted with
  ;; clojure's own delay, so this needs no manifold to prove.
  (let [g (shape/shape
           (shape/state :out [:map] {:initial true})
           (shape/state :in  [:map] {:machine (shape/shape
                                               (shape/state :a [:map] {:initial true})
                                               (shape/state :b [:map [:n :int]] {:final true})
                                               (shape/event :go [:map] (fn [_] (delay {:n 7}))
                                                            [:map [:n :int]])
                                               (shape/transition :a :go :b))})
           (shape/event :enter [:map] (constantly {}) [:map])
           (shape/transition :out :enter :in))
        step (c/compile g)]
    (is (= {:id :in :sub {:id :b :n 7}}
           (step (step (c/initial g {}) {:id :enter}) {:id :go})))))

(defspec nesting-keeps-every-state-a-node-the-graph-admits 60
  ;; The structural invariant, extended through the boundary: whatever arrives, the parent is
  ;; in one of ITS nodes and its child — when there is one — is in one of the CHILD'S. The
  ;; generated shapes share an event vocabulary, so the child shadows the parent constantly,
  ;; which is the interesting half.
  (prop/for-all [outer ts/gen-shape
                 inner ts/gen-shape
                 events (gen/vector ts/gen-event 0 20)]
    (let [kid (apply shape/shape inner)
          host (last (ts/parts-of :state outer))
          g (apply shape/shape (map (fn [p] (if (= p host) (assoc p :machine kid) p)) outer))
          nodes (set (shape/states g))
          kid-nodes (set (shape/states kid))
          end (reduce (c/compile g) (c/initial g {}) events)]
      (and (contains? nodes (:id end))
           (if (:sub end)
             (contains? kid-nodes (:id (:sub end)))
             (nil? (shape/machine g (:id end))))))))

;;; ------------------------------------------------------- what a node holds

(deftest a-node-holds-what-it-declares-and-nothing-else
  ;; The merge is PROJECTED onto the target's declared keys on entry, so data stops flowing
  ;; through states that never mentioned it. This is what bounds visibility BY ABSENCE.
  (let [g (shape/shape
           (shape/state :one [:map [:x :int]] {:initial true})
           (shape/state :two [:map [:y :int]] {:final true})
           (shape/event :next [:map] (constantly {:y 1}) [:map [:y :int]])
           (shape/transition :one :next :two))
        step (c/compile g)]
    (is (= {:id :one :x 7} (c/initial g {:x 7 :undeclared :dropped}))
        "the FIRST state is projected too, or it would be the one state holding what it never
         declared")
    (is (= {:id :two :y 1} (step (c/initial g {:x 7}) {:id :next}))
        ":x came this far and no further, :two having never mentioned it")))

(deftest projection-keeps-what-the-machinery-owns
  (let [g (shape/shape
           (shape/state :a [:map] {:initial true})
           (shape/state :b [:map] {:machine (child)})
           (shape/event :go [:map] (constantly {}) [:map])
           (shape/transition :a :go :b))
        step (c/compile g)]
    (is (= {:id :b :instance "run-1" :sub {:id :unpaid}}
           (step (c/initial g "run-1" {}) {:id :go}))
        ":instance and :sub survive a projection that drops everything undeclared")))

;;; ------------------------------------------------------------ a declared view

(deftest a-handler-sees-exactly-what-was-declared
  ;; The security property, and the reason a view is declared rather than automatic: the state
  ;; holds a token, the handler asked for a goal, and the token never reaches it.
  (let [seen (atom [])
        g (shape/shape
           (shape/state :briefed  [:map [:goal :string] [:token :string]] {:initial true})
           (shape/state :answered [:map [:goal :string] [:token :string] [:answer :string]]
                        {:final true})
           (shape/event :ask [:map [:q :string]]
                        (fn [e v] (swap! seen conj v) {:answer (str (:goal v) "/" (:q e))})
                        [:map [:answer :string]]
                        {:sees [:map [:goal :string]]})
           (shape/transition :briefed :ask :answered))
        end ((c/compile g) (c/initial g {:goal "ship it" :token "s3cret"}) {:id :ask :q "how"})]
    (is (= [{:goal "ship it"}] @seen)
        "one key, and not the one next to it")
    (is (= "ship it/how" (:answer end))
        "and the handler could actually use it, which is the point")))

(deftest a-view-is-a-seam-and-is-checked-at-runtime-too
  ;; The static check proves what it can; this holds in production, and it is the difference
  ;; between a diagnosis and a nil turning up inside somebody's handler.
  (let [g (shape/shape
           (shape/state :a [:map [:goal {:optional true} :string]] {:initial true})
           (shape/state :b [:map] {:final true})
           (shape/event :go [:map] (fn [_ _v] {}) [:map] {:sees [:map [:goal :string]]})
           (shape/transition :a :go :b))
        step (c/compile g)]
    (is (= {:id :b} (step (c/initial g {:goal "there"}) {:id :go}))
        "the optional key was there, so the view held")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a valid sees"
                          (step (c/initial g {}) {:id :go}))
        "and when it was not, the crossing says so rather than the handler finding a nil")))

;;; ----------------------------------------------------------------------- guards

(defn- judging
  "One event leading two ways, which is the whole of what a guard is for. The verdict is
   a fact the world reports; where it SENDS the machine is the shape's to say."
  []
  (shape/shape
   (shape/state :written     [:map [:code :string]] {:initial true})
   (shape/state :implemented [:map [:code :string]] {:final true})
   (shape/state :faulted     [:map [:code :string] [:fault :string]])
   (shape/event :judged [:map [:verdict [:enum :green :red]]
                              [:fault {:optional true} [:string {:min 1}]]]
                (fn [e] (select-keys e [:fault]))
                [:map [:fault {:optional true} [:string {:min 1}]]])
   (shape/transition :written :judged :implemented {:when [:map [:verdict [:= :green]]]})
   (shape/transition :written :judged :faulted     {:when [:map [:verdict [:= :red]]]})
   (shape/transition :faulted :judged :written     {:when [:map [:verdict [:= :green]]]})))

(deftest a-guard-decides-which-edge-fires
  (let [g (judging)
        step (c/compile g)
        s0 (c/initial g {:code "(defn answer [])"})]
    (is (= :implemented (:id (step s0 {:id :judged :verdict :green}))))
    (is (= {:id :faulted :code "(defn answer [])" :fault "it threw"}
           (step s0 {:id :judged :verdict :red :fault "it threw"}))
        "and the other way carries what that branch needs")))

(deftest an-event-no-guard-admits-fires-nothing
  ;; There is no :else, and none is wanted: the fallback every guarded FSM needs is one
  ;; this library already had. A well-formed event no guard wants simply misses, the
  ;; reduction stays total, and a layer above reports :fired false.
  (let [g (judging)
        step (c/compile g)
        faulted (step (c/initial g {:code "x"}) {:id :judged :verdict :red :fault "boom"})]
    (is (= faulted (step faulted {:id :judged :verdict :red :fault "again"}))
        ":faulted only goes back on :green, so a second :red is a miss and not a move")
    (is (false? (c/admits? (c/index g) faulted {:id :judged :verdict :red :fault "again"}))
        "and `admits?` says so without taking the step, guards and all")
    (is (true? (c/admits? (c/index g) faulted {:id :judged :verdict :green})))))

(deftest a-refused-guard-and-a-malformed-event-are-not-the-same-thing
  ;; Only one of the two may be a defect. A guard is a REFINEMENT of a schema the event
  ;; must already satisfy, so where edges exist the event is conformed before the miss is
  ;; believed — otherwise a typo in a verdict would be swallowed as an ordinary miss.
  (let [g (judging)
        step (c/compile g)
        s0 (c/initial g {:code "x"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a valid event"
                          (step s0 {:id :judged :verdict :amber})))
    (is (= s0 (step s0 {:id :nothing-fires-this}))
        "while an event with no edge at all is still the quiet miss it always was")))
