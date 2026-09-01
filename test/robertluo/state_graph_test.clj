(ns robertluo.state-graph-test
  "What the facade itself can get wrong, and nothing that is already asserted below it.

   The delegations are deliberately untested: `sg/state` making a state is
   robertluo.state-graph.shape's promise, and re-asserting it here would be testing our own
   code through a second door. What IS the facade's own is the TRANSITION RESULT — the
   record it builds, and :fired, which no layer below it can answer."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [manifold.stream :as s]
            [robertluo.state-graph :as sg]
            [robertluo.state-graph.shape :as shape]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

(def ^:private patience 5000)

(defn- fed
  "A source carrying exactly these events, then closed. Buffered, so the puts resolve
   before anybody consumes and a test can arrange everything before it starts reading."
  [events]
  (let [in (s/stream (max 1 (count events)))]
    (s/put-all! in events)
    (s/close! in)
    in))

(defn- ran
  "A shape run over these events: every result, and :done. BOTH DEREFS ARE BOUNDED, so a
   machine that hangs fails a test instead of hanging the suite."
  [sh events]
  (let [{:keys [states done]} (sg/run sh {} (fed events))]
    {:results (deref (s/reduce conj [] states) patience ::timeout)
     :done    (deref done patience ::timeout)}))

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
    (is (= [] (deref (s/reduce conj [] states) patience ::timeout))
        "closed, and closed EMPTY — the bad state was never a state")
    (is (thrown? clojure.lang.ExceptionInfo (deref done patience ::timeout)))))

;;; --------------------------------------------------------------- the vocabulary

(deftest the-facade-does-not-run-the-structural-checks-for-you
  ;; Deliberate: a shape you cannot build is a shape you cannot draw, and looking at a
  ;; half-finished machine is what the drawing is for. `problems` is the opt-in.
  (let [sh (ts/trapped)]
    (is (= [{:problem :trap :id :limbo} {:problem :trap :id :retrying}]
           (sg/problems sh))
        "built without complaint, and the fault is there to be asked about")))

(deftest ^:integration the-drawing-is-reachable-from-the-facade
  ;; :format :dot is a spit and needs no graphviz — every other format shells out to `dot`.
  ;; This asserts the facade's door to it, not the drawing, which check-test owns.
  (let [f (str (System/getProperty "java.io.tmpdir") "/sg-facade-" (System/currentTimeMillis) ".dot")]
    (try
      (sg/draw! (ts/counter) {:save {:filename f :format :dot}})
      (let [src (slurp f)]
        (is (re-find #"digraph" src))
        (is (not (re-find #"\$eval" src)) "a closure in a picture is the failure mode"))
      (finally (.delete (java.io.File. f))))))
