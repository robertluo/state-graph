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
  ;; than anything of an async library's. THAT is what this proves, and it proves it with
  ;; CLOJURE'S OWN derefables — a delay and a promise are both IDeref — so the claim is
  ;; tested with no async library in sight. A core.async channel is NOT IDeref; see the
  ;; async namespace's `blocking` for the Context that takes from one.
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
  ;; :then and :pure are what keep any async library OUT of the core: swap them and the step
  ;; answers something else entirely, while compile never learns what that something is.
  ;; A one-key box stands in for a deferred — the point is that BOTH paths route through
  ;; the context, the transition through :then and the miss through :pure.
  ;;
  ;; THE BOX IS A LAWFUL BIND AND HAS TO BE. `then` unwraps its input and answers exactly
  ;; what the continuation answers, boxing only what is not already boxed — a go-block bind's own
  ;; behaviour, and what Context asks for. An fmap here (box (f v)) instead reads as a
  ;; container of a container the moment the step composes two of them, which is what
  ;; `phases` does.
  (let [g      (ts/counter)
        box    (fn [v] {:boxed v})
        boxed? (fn [x] (and (map? x) (contains? x :boxed)))
        unbox  (fn [x] (if (boxed? x) (:boxed x) x))
        step (c/compile g {:then (fn [v f] (let [r (f (unbox v))]
                                             (if (boxed? r) r (box r))))
                           :pure box})
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
  ;; clojure's own delay, so this needs no async library to prove.
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

;;; ------------------------------------------------------------------ the phases

(defspec the-step-is-its-two-halves 60
  ;; THE PROPERTY THAT KEEPS THEM FROM DRIFTING, and the reason `compile` is defined as
  ;; (:step (phases ...)) rather than written twice: patching and then applying must equal
  ;; stepping, for any shape and any event, admitted or not. Everything else about the
  ;; split is an optimisation of when the two halves run; this is what says they are the
  ;; same machine.
  (prop/for-all [parts ts/gen-shape
                 event ts/gen-event]
    (let [sh (apply shape/shape parts)
          {:keys [patch apply step]} (c/phases sh nil)
          s0 (c/initial sh {})]
      (= (step s0 event) (apply s0 event (patch s0 event))))))

(deftest a-patch-is-a-value-and-says-whose-it-is
  (let [sh (ts/counter)
        {:keys [patch]} (c/phases sh nil)
        s0 (c/initial sh {})]
    (is (= {:answer {:n 4} :depth 0} (patch s0 {:id :start :seed 4}))
        "the handler's answer, and the depth of the machine that owns it")
    (is (= :robertluo.state-graph.compile/missed (patch s0 {:id :stop}))
        "and an event no edge admits is not a patch at all — no handler ran")))

(deftest the-patch-is-never-stale-only-the-admission-is
  ;; THE CLAIM THE WHOLE SPLIT RESTS ON. A handler answers from the EVENT ALONE, so what
  ;; it computed while the machine was in one state is still exactly right in a later one
  ;; — and here the target is a DIFFERENT NODE from the one it was computed against, which
  ;; is precisely what the apply phase re-looks-up and what a single step cannot express.
  (let [sh (ts/join)
        {:keys [patch apply]} (c/phases sh nil)
        s0 (c/initial sh {})
        p-test (patch s0 {:id :test :test {:ok false}})]
    (is (= {:answer {:test {:ok false}} :depth 0} p-test))
    (testing "computed in :verifying, where its target would have been :tested"
      (is (= {:id :tested :test {:ok false}}
             (apply s0 {:id :test :test {:ok false}} p-test))))
    (testing "and the SAME patch applied in :evaled lands in :complete instead"
      (let [evaled (apply s0 {:id :eval :eval {:ok true}}
                          (patch s0 {:id :eval :eval {:ok true}}))]
        (is (= {:id :evaled :eval {:ok true}} evaled))
        (is (= {:id :complete :eval {:ok true} :test {:ok false}}
               (apply evaled {:id :test :test {:ok false}} p-test))
            "the apply phase reads the edge from where the patch LANDS")))))

(deftest the-two-halves-must-agree-about-whose-event-it-is
  ;; The seam the split rests on, checked in the code. A patch a CHILD's handler answered
  ;; may only be applied to that child, and one this machine answered only to this machine
  ;; — otherwise an answer would be merged into a state that never asked for it.
  (let [child (shape/shape
               (shape/state :c1 [:map] {:initial true})
               (shape/state :c2 [:map [:v :int]] {:final true})
               (shape/event :inner [:map [:v :int]])
               (shape/transition :c1 :inner :c2))
        sh (shape/shape
            (shape/state :p [:map [:v {:optional true} :int]]
                         {:initial true :machine child})
            (shape/state :q [:map] {:final true})
            ;; :inner IS THE CHILD'S WORD AND THE PARENT DOES NOT KNOW IT — declaring it
            ;; here would be :unused-event, an event no transition of THIS shape fires.
            ;; Which is the whole mechanism of nesting: the child's own vocabulary decides
            ;; who handles an event.
            (shape/event :out [:map])
            (shape/transition :p :out :q))
        {:keys [patch apply]} (c/phases sh nil)
        s0 (c/initial sh {})
        deep (patch s0 {:id :inner :v 1})]
    (is (= {:answer {:v 1} :depth 1} deep)
        "the child's handler ran, and the patch says it was one level down")
    (is (= {:id :p :sub {:id :c2 :v 1}} (apply s0 {:id :inner :v 1} deep))
        "and applied to the child, it lands in the child")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Patch does not belong"
                          (apply s0 {:id :inner :v 1} (assoc deep :depth 0)))
        "a child's patch claimed for the parent is refused")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Patch does not belong"
                          (apply s0 {:id :out} {:answer {} :depth 1}))
            "and so is the parent's claimed for a child that does not admit it")))

;;; --------------------------------------------------------------- the combines

(deftest a-key-with-a-combine-is-combined-and-one-without-replaces
  ;; What replaces the naive merge, and the only non-commutative thing the apply phase had.
  (let [f (ts/fanning)
        step (c/compile f)
        s0 (c/initial f {})
        a {:id :offer-a :best {:score 5 :by "a"}}
        b {:id :offer-b :best {:score 9 :by "b"}}]
    (is (= {:id :choosing} s0) ":best is optional, so nothing is held yet")
    (is (= {:id :choosing :best {:score 5 :by "a"}} (step s0 a))
        "a key the state does not hold yet is TAKEN — a combine needs two values")
    (is (= {:id :choosing :best {:score 9 :by "b"}}
           (step (step s0 a) b)
           (step (step s0 b) a))
        "and thereafter combined, so which offer landed second is not observable")
    (testing "while a key with no combine declared replaces, exactly as merge did"
      (let [g (ts/counter)
            st (c/compile g)]
        (is (= {:id :running :n 7}
               (st (st (c/initial g {}) {:id :start :seed 1}) {:id :set :to 7})))))))

(deftest a-declared-law-is-verified-on-the-values-and-not-trusted
  ;; THE SEAM THE CLOSURE COSTS. `commutes` reads {:combine/commutes true} and cannot
  ;; check it — the algebra of a closure is not statically knowable, and generation misses
  ;; a rare case (see check-test/generation-cannot-reach-every-violation). So when the
  ;; licence is actually taken, both patches are in hand and the claim is checked.
  (let [sticky (fn [a b] (if (= "pinned" (:by a)) a (ts/better a b)))
        liar (shape/shape
              (shape/state :s [:map [:best {:optional true
                                            :combine sticky
                                            :combine/commutes true} ts/Impl]]
                           {:initial true})
              (shape/event :p [:map [:best ts/Impl]])
              (shape/event :q [:map [:best ts/Impl]])
              (shape/transition :s :p :s)
              (shape/transition :s :q :s))
        {:keys [patch agree]} (c/phases liar nil)
        s0 (assoc (c/initial liar {}) :best {:score 0 :by "m"})
        ep {:id :p :best {:score 5 :by "pinned"}}
        eq {:id :q :best {:score 9 :by "z"}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"combine declared commutative is not"
         (agree s0 ep (patch s0 ep) eq (patch s0 eq)))
        "the exact case 2,744 generated triples could not reach")
    (testing "and it is silent where the promise holds"
      (let [f (ts/fanning)
            {:keys [patch agree]} (c/phases f nil)
            fs (assoc (c/initial f {}) :best {:score 0 :by "m"})
            a {:id :offer-a :best {:score 5 :by "a"}}
            b {:id :offer-b :best {:score 9 :by "b"}}]
        (is (nil? (agree fs a (patch fs a) b (patch fs b))))))
    (testing "and where the two patches share no key at all, there is nothing to ask"
      (let [j (ts/join)
            {:keys [patch agree]} (c/phases j nil)
            js (c/initial j {})
            e {:id :eval :eval {:ok true}}
            t {:id :test :test {:ok false}}]
        (is (nil? (agree js e (patch js e) t (patch js t))))))))

;;; ------------------------------------------------------ a completion transition

(defspec a-pass-through-is-transparent 60
  ;; THE ALGEBRAIC STATEMENT OF THE FEATURE, and an independent invariant rather than the
  ;; implementation restated: split one edge a -e-> b into a -e-> mid {:done b} and the
  ;; reduction must end EXACTLY where it ended before, data and all. Nothing here
  ;; recomputes what `continue` computes — it compares two machines.
  (prop/for-all [parts ts/gen-shape
                 events (gen/vector ts/gen-event 0 20)
                 n gen/nat]
    (let [edges (vec (ts/parts-of :transition parts))
          t (nth edges (mod n (count edges)))
          spliced (concat (remove #{t} parts)
                          [(shape/transition (:from t) (:event t) :mid)
                           (shape/state :mid [:map] {:done (:to t)})])
          plain (apply shape/shape parts)
          split (apply shape/shape spliced)]
      (= (reduce (c/compile plain) (c/initial plain {}) events)
         (reduce (c/compile split) (c/initial split {}) events)))))

(deftest a-state-with-no-machine-completes-ON-ENTRY
  ;; One rule, and it is UML's: a simple state has no activity to finish, so finishing it
  ;; is arriving.
  (let [sh (shape/shape (shape/state :a [:map [:n :int]] {:initial true})
                        (shape/state :b [:map [:n :int]] {:done :c})
                        (shape/state :c [:map [:n :int]] {:final true})
                        (shape/event :go [:map [:n :int]])
                        (shape/transition :a :go :b))]
    (is (= {:id :c :n 7} ((c/compile sh) (c/initial sh {:n 0}) {:id :go :n 7}))
        "the machine is never observed sitting in :b")))

(deftest a-chain-of-completions-is-followed-to-the-end
  (let [sh (shape/shape (shape/state :a [:map [:n :int]] {:initial true})
                        (shape/state :b [:map [:n :int]] {:done :c})
                        (shape/state :c [:map [:n :int]] {:done :d})
                        (shape/state :d [:map [:n :int]] {:final true})
                        (shape/event :go [:map [:n :int]])
                        (shape/transition :a :go :b))]
    (is (= {:id :d :n 3} ((c/compile sh) (c/initial sh {:n 0}) {:id :go :n 3})))))

(deftest initial-resolves-a-completion-transition-too
  ;; ENTERING IS ENTERING, and a continuation is pure — no event, no handler, no deferred —
  ;; so it runs inside `initial` as happily as inside a step.
  (let [sh (shape/shape (shape/state :a [:map [:n :int]] {:initial true :done :b})
                        (shape/state :b [:map [:n :int]] {:final true}))]
    (is (= {:id :b :n 5} (c/initial sh {:n 5})))))

(deftest a-parent-WAITS-for-its-child-and-harvests-what-it-finished-with
  ;; The statechart done-transition, and the thing nesting could not do: before this the
  ;; parent's only exit was an event, and taking one DISCARDED the child's work.
  (let [sh (ts/shipping)
        step (c/compile sh)
        start (c/initial sh {:total 30})]
    (is (= {:id :paying :total 30 :sub {:id :awaiting}} start))
    (testing "the child finishing completes the parent, which harvests and continues —
              through :shipped, which completes on entry, and on to :closed"
      (is (= {:id :closed :total 30 :receipt "R-30"}
             (step start {:id :authorize :receipt "R-30"}))))
    (testing "the child is DROPPED on the way out, a state holding only what it declares"
      (is (not (contains? (step start {:id :authorize :receipt "R-30"}) :sub))))))

(deftest an-escape-is-still-an-ABORT-and-still-yields-nothing
  ;; Which is the semantics nesting already had, and the reason the two must coexist:
  ;; abort is the commoner need and `only when the child has finished` is what :done adds.
  (let [sh (ts/shipping)
        step (c/compile sh)]
    (is (= {:id :cancelled :total 30}
           (step (c/initial sh {:total 30}) {:id :cancel})))))

(deftest a-nesting-node-SOWS-its-child-and-may-do-it-twice
  ;; THE THING NESTING COULD NOT DO. A child was always entered with NO data, so what job it
  ;; was doing could only come from the closure its shape was built from — which fixes it for
  ;; the life of the shape and makes re-entering the node with a DIFFERENT job impossible.
  ;; A :seed is :yield read backwards, and one loop over a child machine needs both.
  (let [sh   (ts/refining)
        step (c/compile sh)
        at   (fn [st] [(:id st) (:job st) (:id (:sub st)) (:job (:sub st)) (:answer st)])]
    (testing "the child starts holding what the parent sowed, and nothing else"
      (is (= [:working "write it" :idle "write it" nil]
             (at (step (c/initial sh {}) {:id :begin :job "write it"})))))
    (testing "it finishes, the parent harvests, and the child is dropped on the way out"
      (let [judging (-> (c/initial sh {})
                        (step {:id :begin :job "write it"})
                        (step {:id :answer :answer "v1"}))]
        (is (= [:judging "write it" nil nil "v1"] (at judging)))
        (is (not (contains? judging :sub)))

        (testing "and the SAME node is entered again with a different job — the child
                  restarted, sown afresh, holding the second job and not the first"
          (is (= [:working "polish it" :idle "polish it" "v1"]
                 (at (step judging {:id :verdict :verdict :again :job "polish it"})))))))))

(deftest a-completion-may-say-where-each-OUTCOME-goes-and-what-each-takes
  ;; The parent could not tell WHICH way its child finished: :done had one target, and a
  ;; :yield had to hold at every final state, so `it worked` and `it gave up` arrived
  ;; identically. Here they are two arrows, and the branch that gave up harvests NOTHING —
  ;; which is what lets the parent keep the answer it already had.
  (let [sh   (ts/refining)
        step (c/compile sh)
        run  (fn [& events] (reduce step (c/initial sh {}) events))]
    (testing "the child finishing well is harvested and reviewed"
      (is (= {:id :judging :job "write it" :answer "v1"}
             (run {:id :begin :job "write it"} {:id :answer :answer "v1"}))))
    (testing "the child giving up goes somewhere else and yields nothing"
      (is (= {:id :kept :job "write it"}
             (run {:id :begin :job "write it"} {:id :give-up}))))
    (testing "AND A SECOND LAP THAT GIVES UP KEEPS THE FIRST LAP'S ANSWER, which is the
              property the whole branch exists for: a state holds what it declares, so the
              good answer survives by never being overwritten"
      (is (= {:id :kept :job "polish it" :answer "v1"}
             (run {:id :begin :job "write it"}
                  {:id :answer :answer "v1"}
                  {:id :verdict :verdict :again :job "polish it"}
                  {:id :give-up}))))))

(deftest a-seed-is-a-seam-and-is-checked-at-runtime-too
  ;; :seed-unavailable is STRUCTURAL, so a shape whose node cannot provide the seed can
  ;; still be BUILT — `problems` is opt-in. What holds in production is this, and it says
  ;; which crossing failed rather than letting a nil into a child.
  (let [child (shape/shape (shape/state :c1 [:map [:job :string]] {:initial true})
                           (shape/state :c2 [:map [:job :string]] {:final true})
                           (shape/event :fin [:map])
                           (shape/transition :c1 :fin :c2))
        sh (shape/shape (shape/state :a [:map] {:initial true}
                                     )
                        (shape/state :b [:map [:job {:optional true} :string]]
                                     {:machine child :seed [:map [:job :string]]})
                        (shape/event :go [:map])
                        (shape/transition :a :go :b))
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"seed"
                                ((c/compile sh) (c/initial sh {}) {:id :go})))]
    (is (= :seed (:crossing (ex-data e))))
    (is (= :b (:to (ex-data e))))))

(deftest a-yield-is-a-seam-and-is-checked-at-runtime-too
  ;; :yield-unavailable is STRUCTURAL, so a shape whose child cannot provide the yield can
  ;; still be BUILT — `problems` is opt-in. What holds in production is this.
  (let [child (shape/shape (shape/state :d1 [:map] {:initial true})
                           (shape/state :d2 [:map [:other :int]] {:final true})
                           (shape/event :fin [:map [:other :int]])
                           (shape/transition :d1 :fin :d2))
        sh (shape/shape (shape/state :p [:map] {:initial true :machine child
                                                :done :z :yield [:map [:receipt :string]]})
                        (shape/state :z [:map [:receipt :string]] {:final true}))
        e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"yield"
                                ((c/compile sh) (c/initial sh {}) {:id :fin :other 1})))]
    (is (= :yield (:crossing (ex-data e))))
    (is (= :p (:from (ex-data e))))))
