(ns robertluo.state-graph.async-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [robertluo.state-graph.async :as a]
            [robertluo.state-graph.compile :as c]
            [robertluo.state-graph.shape :as shape]
            [robertluo.state-graph.test-support :as ts]))

(use-fixtures :once ts/instrumented)

(def ^:private patience 5000)

(defn- collect
  "Every state a machine produced, and its final answer. BOTH DEREFS ARE BOUNDED, so a
   machine that hangs fails a test instead of hanging the suite — which matters more here
   than anywhere else in this project, streams being the one thing that can wait for ever."
  [{:keys [states done]}]
  {:states (deref (s/reduce conj [] states) patience ::timeout)
   :done   (deref done patience ::timeout)})

(defn- fed
  "A source carrying exactly these events, then closed. BUFFERED, so the puts resolve with
   nobody consuming yet and a test can arrange everything before it starts reading."
  [events]
  (let [in (s/stream (max 1 (count events)))]
    (s/put-all! in events)
    (s/close! in)
    in))

;;; --------------------------------------------------------------------- drive

(deftest an-event-stream-becomes-a-state-stream
  (let [sh (ts/counter)
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                              (fed [{:id :start :seed 0} {:id :set :to 7} {:id :stop}])))]
    (is (= [{:id :running :n 0} {:id :running :n 7} {:id :done :n 7}] (:states got)))
    (is (= {:id :done :n 7} (:done got)) ":done carries the last state, not merely nil")))

(deftest a-handler-may-answer-later
  ;; What a/context is FOR: the handler answers a deferred, so the step answers one, and the
  ;; reduction is unbothered. compile/synchronous handles the same shape by DEREFERENCING —
  ;; two Contexts, one step, and the core knowing nothing of either.
  (let [sh (shape/shape
            (shape/state :a [:map] {:initial true})
            (shape/state :b [:map [:n :int]] {:final true})
            (shape/event :go [:map] (fn [_] (d/success-deferred {:n 7})) [:map [:n :int]])
            (shape/transition :a :go :b))
        step (c/compile sh a/context)]
    (testing "under this Context the step itself answers a deferred"
      (is (d/deferred? (step (c/initial sh {}) {:id :go}))))
    (testing "and the same shape run synchronously derefs it instead"
      (is (= {:id :b :n 7} ((c/compile sh) (c/initial sh {}) {:id :go}))))
    (is (= {:states [{:id :b :n 7}] :done {:id :b :n 7}}
           (collect (a/drive step (c/initial sh {}) (fed [{:id :go}])))))))

(deftest an-event-nobody-handled-still-answers-a-state
  ;; The reduction stays total, so there is one state per event and a consumer counting
  ;; them is not misled about how much arrived.
  (let [sh (ts/counter)
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                              (fed [{:id :stop} {:id :start :seed 3}])))]
    (is (= [{:id :idle} {:id :running :n 3}] (:states got))
        ":stop from :idle changed nothing, and said so by answering the state unchanged")))

(deftest a-defect-reaches-done-and-closes-the-states
  ;; A crossing that does not hold is a DEFECT, so it belongs on :done. And :states MUST
  ;; close, or a consumer waits for ever on a machine that has already stopped — which is
  ;; the worst failure a stream layer has available to it.
  (let [sh (shape/shape
            (shape/state :a [:map] {:initial true})
            (shape/state :b [:map [:n :int]] {:final true})
            (shape/event :go [:map] (constantly {:n "seven"}) [:map [:n :int]])
            (shape/transition :a :go :b))
        {:keys [states done]} (a/drive (c/compile sh a/context) (c/initial sh {})
                                       (fed [{:id :go}]))]
    (is (= [] (deref (s/reduce conj [] states) patience ::timeout))
        "closed, and closed EMPTY — the bad state was never a state")
    (is (thrown? clojure.lang.ExceptionInfo (deref done patience ::timeout)))))

;;; ----------------------------------------------------------------------- fan

(deftest fan-gives-every-instance-its-own-reduction
  (let [sh (ts/counter)
        got (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {}))
                            (fed [{:id :start :seed 1   :instance "a"}
                                  {:id :start :seed 100 :instance "b"}
                                  {:id :set   :to 5     :instance "a"}
                                  {:id :stop            :instance "b"}
                                  {:id :stop            :instance "a"}])))]
    (testing "one state per event and NONE LOST, which is the s/connect race exactly:
              the first version forwarded each machine's states through s/connect, and
              closing the shared output after every machine reported done dropped whatever
              was still in a pipeline"
      (is (= 5 (count (:states got)))))

    (is (= {"a" [[:running 1] [:running 5] [:done 5]]
            "b" [[:running 100] [:done 100]]}
           (into {} (map (fn [[k v]] [k (mapv (juxt :id :n) v)]))
                 (group-by :instance (:states got))))
        "each machine reduced its OWN events and none of anybody else's")

    (testing ":done waits for every machine, so a state in flight when the events run out
              is still a state"
      (is (= #{"a" "b"} (set (map :instance (:done got))))))))

(deftest an-unnamed-event-is-its-own-machine
  ;; A caller who never names anything still works, and the partition is the one machine.
  (let [sh (ts/counter)
        got (collect (a/fan (c/compile sh a/context) (fn [k] (c/initial sh k {}))
                            (fed [{:id :start :seed 2} {:id :stop}])))]
    (is (= [{:id :running :n 2} {:id :done :n 2}] (:states got))
        "and no :instance key appears, because nobody supplied one")))

;;; --------------------------------------------------------------- the ordering

(deftest ^:integration serialisation-survives-a-slow-handler
  ;; The ordering guarantee, and it needs a handler that really is slower — hence
  ;; ^:integration, this being the one test here that depends on a clock. The ASSERTION is
  ;; about order rather than timing, so it does not care how slow: if the reduction were
  ;; not serialised the fast event could overtake, and nothing takes from `events` while a
  ;; handler is still in flight.
  (let [sh (shape/shape
            (shape/state :s [:map] {:initial true})
            (shape/event :slow [:map] (fn [_] (d/future (Thread/sleep 150) {:mark :slow}))
                         [:map [:mark :keyword]])
            (shape/event :fast [:map] (constantly {:mark :fast}) [:map [:mark :keyword]])
            (shape/transition :s :slow :s)
            (shape/transition :s :fast :s))
        got (collect (a/drive (c/compile sh a/context) (c/initial sh {})
                              (fed [{:id :slow} {:id :fast}])))]
    (is (= [:slow :fast] (mapv :mark (:states got)))
        "the slow one still landed first")))
