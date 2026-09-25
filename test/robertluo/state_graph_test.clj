(ns robertluo.state-graph-test
  "What the facade itself can get wrong, and nothing that is already asserted below it.

   The delegations are deliberately untested: `sg/state` making a state is
   robertluo.state-graph.shapes's promise, and re-asserting it here would be testing our own
   code through a second door. What IS the facade's own is the TRANSITION RESULT — the
   record it builds, and :fired, which no layer below it can answer."
  (:require [clojure.core.async :as ca]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [robertluo.state-graph :as sg]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.shapes :as shape]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

(def ^:private patience 5000)

(defn- fed
  "A channel carrying exactly these events, then closed. Buffered, so the puts complete
   before anybody consumes and a test can arrange everything before it starts reading."
  [events]
  (let [in (ca/chan (max 1 (count events)))]
    (ca/onto-chan!! in events)
    in))

(defn- wait
  "What `ch` delivers, or ::timeout. BOUNDED, so a machine that hangs fails a test instead
   of hanging the suite."
  [ch]
  (let [[v port] (ca/alts!! [ch (ca/timeout patience)])]
    (if (= port ch) v ::timeout)))

(defn- ran
  "A shape run over these events: every result, and :done. BOTH WAITS ARE BOUNDED, so a
   machine that hangs fails a test instead of hanging the suite."
  [sh events]
  (let [{:keys [states done]} (sg/run sh {} (fed events))]
    {:results (wait (ca/into [] states))
     :done    (wait done)}))

;;; ----------------------------------------------------------------- properties

(defspec the-two-doors-agree 60
  ;; THE FACADE'S CENTRAL CLAIM, and the only property that can refute it: the stream door
  ;; and the reduction door are one machine. (map :state) off the results must be exactly
  ;; the states the reduction passes through — which also pins the promise that a result can
  ;; be read back down to a state whenever that is all somebody wants.
  (prop/for-all [parts ts/gen-shape
                 events (gen/vector ts/gen-event 0 8)]
    (let [sh (apply sg/shape parts)
          step (sg/compile sh)]
      (= (rest (reductions step (sg/initial sh {}) events))
         (map :state (:results (ran sh events)))))))

(defspec one-result-per-event-and-fired-agrees-with-the-graph 60
  ;; :fired is checked against the EDGE LIST rather than against the lookup that produced
  ;; it — shape/transitions instead of compile/index — so this is a second opinion and not
  ;; the implementation restated. If the index ever dropped an edge the two would part.
  (prop/for-all [parts ts/gen-shape
                 events (gen/vector ts/gen-event 0 8)]
    (let [sh (apply sg/shape parts)
          edges (set (map (juxt :from :event) (shape/transitions sh)))
          results (:results (ran sh events))]
      (and (= (count events) (count results))
           (= (map :event results) events)
           (every? true?
                   (map (fn [from {:keys [event fired]}]
                          (= fired (contains? edges [(:id from) (:id event)])))
                        (cons (sg/initial sh {}) (map :state results))
                        results))))))

(defspec every-result-is-a-transition 60
  (prop/for-all [parts ts/gen-shape
                 events (gen/vector ts/gen-event 0 8)]
    (let [sh (apply sg/shape parts)]
      (every? #(m/validate sg/Transition %) (:results (ran sh events))))))

;;; ------------------------------------------------------------- what run says

(deftest an-ignored-event-is-visible-and-a-self-loop-is-not-mistaken-for-one
  ;; The whole reason the output is a record. Both of these answer a state that carries the
  ;; same data, and only :fired tells them apart — a consumer storing history cannot
  ;; recover it by comparing states, `identical?` included.
  (let [sh (ts/form)
        {:keys [results]} (ran sh [{:id :touch} {:id :submit} {:id :touch}])]
    (is (= [{:id :filling} {:id :submitted} {:id :submitted}] (map :state results))
        "the last :touch changed nothing, having no edge out of :submitted")
    (is (= [true true false] (map :fired results))
        ":touch on :filling FIRED and answered the same state; :touch on :submitted did not")))

(deftest the-event-rides-along-so-a-caller-can-store-history
  (let [sh (ts/counter)
        {:keys [results]} (ran sh [{:id :start :seed 4} {:id :stop}])]
    (is (= [{:id :start :seed 4} {:id :stop}] (map :event results))
        "the whole event, not merely its id — a store needs what the machine was told")))

(deftest done-is-keyed-by-instance-and-holds-each-machines-last-state
  ;; The one thing :done says that :states does not: which machine ended where, without a
  ;; consumer having to fold the results themselves.
  (let [sh (ts/counter)
        {:keys [results done]} (ran sh [{:id :start :seed 1 :instance "a"}
                                        {:id :start :seed 9 :instance "b"}
                                        {:id :stop :instance "a"}])]
    (is (= {"a" {:id :done :instance "a" :n 1}
            "b" {:id :running :instance "b" :n 9}}
           done))
    (is (= done (into {} (map (juxt :instance :state)) results))
        "and it agrees with the last result each machine put")))

(deftest a-caller-who-names-nothing-finds-their-machine-under-nil
  (let [sh (ts/counter)
        {:keys [results done]} (ran sh [{:id :start :seed 2}])]
    (is (= {nil {:id :running :n 2}} done))
    (is (= [{:event {:id :start :seed 2} :state {:id :running :n 2} :fired true}]
           results)
        "and no :instance key appears anywhere, because nobody supplied one")))

(deftest a-machine-with-no-events-answers-nothing-rather-than-a-first-state
  ;; There is no machine until an event names one, so there is nothing to report on. The
  ;; initial state is not a transition and does not appear.
  (let [got (ran (ts/counter) [])]
    (is (= [] (:results got)))
    (is (= {} (:done got)))))

(deftest a-defect-reaches-done-and-closes-the-results
  ;; A crossing that does not hold is a DEFECT and belongs on :done, not on a stream of
  ;; things that happened. And the results MUST close, or a consumer waits for ever on a
  ;; machine that has already stopped.
  (let [sh (sg/shape (sg/state :a [:map] {:initial true})
                     (sg/state :b [:map [:n :int]] {:final true})
                     (sg/event :go [:map] (constantly {:n "seven"}) [:map [:n :int]])
                     (sg/transition :a :go :b))
        {:keys [states done]} (sg/run sh {} (fed [{:id :go}]))]
    (is (= [] (wait (ca/into [] states)))
        "closed, and closed EMPTY — the bad state was never a state")
    (is (instance? clojure.lang.ExceptionInfo (wait done)))))

(deftest data-the-first-state-refuses-is-a-defect-and-not-a-hang
  ;; The first state is made inside the stream door, the first time an instance is named, so
  ;; a refusal there is thrown on a go block's thread. It reaches :done with what the check
  ;; said, and the results close. Before the fan had a boundary catch this HUNG for ever.
  (let [{:keys [states done]} (sg/run (ts/shipping) {:total "thirty"}
                                      (fed [{:id :authorize :receipt "R-30"}]))]
    (is (= [] (wait (ca/into [] states))))
    (is (= :enter (:crossing (ex-data (wait done)))))))

;;; --------------------------------------------------------------- the vocabulary

(deftest the-facade-does-not-run-the-structural-checks-for-you
  ;; Deliberate: a shape you cannot build is a shape you cannot draw, and looking at a
  ;; half-finished machine is what the drawing is for. `problems` is the opt-in.
  (let [sh (ts/trapped)]
    (is (= [{:problem :trap :id :limbo} {:problem :trap :id :retrying}]
           (sg/problems sh))
        "built without complaint, and the fault is there to be asked about")))

(deftest sg-dot-is-the-drawing-as-data
  ;; The ONE delegation asserted here, and only because it is the answer to a question the
  ;; facade could not answer before: a caller who renders diagrams itself — this project's own
  ;; notebook, for one — needs the source as a value and never a file.
  (is (str/starts-with? (sg/dot (ts/counter)) "digraph")))

;;; ---------------------------------------------------------------- nesting

(deftest a-child-moving-is-a-transition-that-fired
  ;; :fired asks whether the machine handled the event, and a nested machine IS the machine.
  ;; The parent's :id does not change, so a consumer that compared ids would see nothing —
  ;; which is the same reason :fired exists at all.
  (let [child (sg/shape (sg/state :a [:map] {:initial true})
                        (sg/state :b [:map] {:final true})
                        (sg/event :go [:map] (constantly {}) [:map])
                        (sg/transition :a :go :b))
        sh (sg/shape (sg/state :host [:map] {:initial true :machine child})
                     (sg/state :done [:map] {:final true})
                     (sg/event :fin [:map] (constantly {}) [:map])
                     (sg/transition :host :fin :done))
        {:keys [results]} (ran sh [{:id :go} {:id :nobody-knows-this} {:id :fin}])]
    (is (= [[:host :b true] [:host :b false] [:done nil true]]
           (map (juxt (comp :id :state) (comp :id :sub :state) :fired) results)))))

;;; ------------------------------------------------------------- the licence

(deftest run-takes-the-concurrency-it-can-prove
  ;; THE FACADE'S OWN JOB HERE, and the only layer that can do it: `run` knows the shape,
  ;; so it computes `check/commuting` and hands the proof down. Neither `compile` nor
  ;; `async` could — one has no graph algorithms and the other has no shape.
  ;;
  ;; Deterministic without a clock, and by DEADLOCK rather than by order: :eval is fed
  ;; first and parks on a channel that only :test's HANDLER delivers. Serialised, :test's
  ;; handler is never called while :eval waits, so the machine would hang and this would
  ;; time out; it finishes only because both handlers were running at once. Which of the
  ;; two the machine saw land first is async_test's to assert, where the test can decide it.
  (let [gate (ca/promise-chan)
        sh (sg/shape
            (sg/state :verifying [:map] {:initial true})
            (sg/state :evaled    [:map [:eval :int]])
            (sg/state :tested    [:map [:test :int]])
            (sg/state :complete  [:map [:eval :int] [:test :int]] {:final true})
            (sg/event :eval [:map] (fn [_] gate) [:map [:eval :int]])
            (sg/event :test [:map] (fn [_] (ca/put! gate {:eval 1}) {:test 2})
                      [:map [:test :int]])
            (sg/transition :verifying :eval :evaled)
            (sg/transition :verifying :test :tested)
            (sg/transition :tested    :eval :complete)
            (sg/transition :evaled    :test :complete))
        {:keys [states done]} (sg/run sh {} (fed [{:id :eval} {:id :test}]))
        rows (wait (ca/into [] states))]
    (is (= {:verifying #{#{:eval :test}}} (check/commuting sh))
        "the pair is proven, so this is the branch under test")
    (is (= #{:eval :test} (set (map (comp :id :event) (when (vector? rows) rows))))
        "both events came back — which they could not have, serialised")
    (is (= {:id :complete :eval 1 :test 2} (:state (peek rows))))
    (is (every? true? (map :fired rows)))
    (is (= {nil {:id :complete :eval 1 :test 2}} (wait done)))))

(deftest a-join-reaches-its-target-only-once-both-events-have-landed
  ;; What the licence is FOR, through the front door and with no gate: a state whose schema
  ;; REQUIRES both keys is reachable only when both events have been handled, and either
  ;; order gets there. One event alone parks in an intermediate state, which is the join's
  ;; progress and is exactly what that state's schema says.
  (let [sh (ts/join)
        e {:id :eval :eval {:ok true}}
        t {:id :test :test {:ok false}}
        end (fn [evs] (get (:done (ran sh evs)) nil))]
    (is (= {:id :evaled :eval {:ok true}} (end [e]))
        "one event alone parks: the join is not satisfied")
    (is (= {:id :complete :eval {:ok true} :test {:ok false}} (end [e t]) (end [t e]))
        "and both orders reach one identical state")
    (testing "which is the same state the reduction door reaches"
      (is (= (end [e t])
             (reduce (sg/compile sh) (sg/initial sh {}) [e t])
             (reduce (sg/compile sh) (sg/initial sh {}) [t e]))))))

;;; ------------------------------------------------------ a completion transition

(deftest a-completion-transition-is-ONE-result-row-and-not-several
  ;; THE DESIGN DECISION NOTHING ELSE ASSERTS, and it is about the HISTORY rather than
  ;; about the machine: one result per EVENT, carrying the state the chain ended in.
  ;;
  ;; The intermediate states are not lost information. A continuation is a pure function of
  ;; the shape and the state, so an auditor holding the shape can reconstruct every hop —
  ;; and what a row could NOT be reconstructed from is an event, which is why rows are
  ;; counted by events. See :the-output-is-a-transition-and-not-a-state.
  ;;
  ;; IT IS ALSO WHY THE TWO DOORS STILL AGREE. A continuation is resolved inside the step,
  ;; so the reduction passes through exactly the states the stream reports; had it been
  ;; emitted by the stream layer instead, the property above would have had to weaken.
  (let [sh (ts/shipping)
        {:keys [states done]} (sg/run sh {:total 30} (fed [{:id :authorize :receipt "R-30"}]))
        results (wait (ca/into [] states))]
    (is (= 1 (count results))
        "one event, one row — though the machine moved :paying -> :shipped -> :closed")
    (is (= {:event {:id :authorize :receipt "R-30"}
            :state {:id :closed :total 30 :receipt "R-30"}
            :fired true}
           (first results)))
    (is (= {nil {:id :closed :total 30 :receipt "R-30"}}
           (wait done)))))
