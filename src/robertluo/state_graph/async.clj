(ns robertluo.state-graph.async
  "A DEFAULT and not the core: manifold streams. An event stream reduced to a stream of
   results, one per event.

   EVERYTHING IT NEEDS ARRIVES AS A VALUE — a compiled step function, a way to make a first
   state, and a way to turn a step's answer into an output value. So it never learns what a
   shape is, and somebody with their own stream library can use `compile` directly and lose
   nothing. That is what makes this a battery rather than part of the core.

   PARALLELISM IS ACROSS INSTANCES and serialisation is within one. `drive` runs a single
   machine strictly in order; `fan` partitions a stream on :instance and runs one drive per
   machine, concurrently. See :parallel-is-across-instances in AGENTS.md, and
   :two-events-in-flight-at-once for why within one machine is the harder question.

   Requires manifold, and NOTHING BELOW THIS REQUIRES IT — which is the whole purpose of
   the Context that `compile` takes."
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]))

;;; ---------------------------------------------------------------- vocabulary

(def Source
  "A stream something can be taken from."
  [:fn s/source?])

(def Machine
  "What `drive` and `fan` answer. TWO DIFFERENT THINGS, so two names.

   :states is a source of one RESULT per event, closed when there are no more coming — by
   draining or by throwing, so a consumer is never left waiting on a machine that has
   stopped. What a result IS belongs to whoever passed the `result` function: the state by
   default, and a layer that knows the shape puts something richer there.

   :done resolves with the final state — `fan` answering one per instance — or ERRORS with
   whatever the step threw. That is where an error belongs: `compile` treats a crossing
   that does not hold as a DEFECT and not a fact about the run, and a stream of results has
   nowhere honest to put one.

   CONSUME :states, OR :done MAY NEVER RESOLVE. The output is unbuffered and backpressure
   is real, so a machine whose states nobody is reading stops rather than racing ahead.
   That is the correct behaviour and it is worth knowing before deref-ing :done first."
  [:map [:states Source] [:done :some]])

(def Result
  "HOW A STEP'S ANSWER BECOMES AN OUTPUT VALUE: the state an event was applied to, the
   event, and the state that came back.

   INJECTED FOR THE SAME REASON THE Context IS. Whether an event FIRED is shape knowledge
   and this layer has no shape — an event nobody handled answers the state unchanged, and
   there is nothing in the two states to tell that from a self-loop that fired. So a layer
   that does know the shape closes over one and hands the maker down as a VALUE, and this
   one goes on knowing only that some value is to be put."
  [:=> [:cat :map :map :map] :any])

(def state-only
  "The default `result` — put the new state and say nothing about what caused it. Exactly
   what this layer put before it was parameterised, so a caller who wants a stream of
   states passes nothing."
  (fn [_state _event state'] state'))

(def context
  "The Context to hand `compile` so that a handler may answer a deferred and the step
   answers one too. TWO FUNCTIONS, and that is the whole of what manifold contributes to
   the core — which still requires no manifold and never learns what a deferred is.

   Verified 2026-08-31 in this project's own REPL: a manifold Deferred implements
   clojure.lang.IDeref, which is what lets compile/synchronous deref one without a
   dependency; and d/chain takes a plain value as happily as a deferred, so a handler
   answering an ordinary map costs nothing here."
  {:then d/chain
   :pure d/success-deferred})

;;; ---------------------------------------------------------------------- pump

(defn- pump
  "Take events, step, put one result per event to `out`. Answers a deferred of the final
   state — the STATE and never a result, because the state is the accumulator and a result
   is only what a consumer is told.

   DOES NOT CLOSE `out`, and that is the whole reason it exists apart from `drive`: whoever
   made the sink closes it, which is what lets `fan` share ONE output between many machines.
   The first version of `fan` used s/connect from each machine's own stream instead, and
   s/connect is ASYNCHRONOUS — closing the shared output after every machine reported done
   raced the last value still in a connect pipeline, and lost it. Writing straight to the
   sink means a machine's :done cannot resolve until its last result has been ACCEPTED there."
  [step result initial events out]
  (d/loop [state initial]
    (d/chain
     (s/take! events ::drained)
     (fn [e]
       (if (identical? ::drained e)
         state
         (d/chain (step state e)
                  (fn [state']
                    (d/chain (s/put! out (result state e state'))
                             (fn [_] (d/recur state'))))))))))

(defn- closing
  "Close `out` when `done` settles, either way. d/catch here is manifold's combinator over a
   deferred and NOT a bare try/catch: it swallows nothing — `done` still errors for the
   caller — it only makes sure a consumer is not left waiting on a machine that has stopped."
  [done out]
  (-> done
      (d/chain (fn [_] (s/close! out)))
      (d/catch (fn [_] (s/close! out))))
  done)

;;; --------------------------------------------------------------------- drive

(defn drive
  "ONE machine: events in, states out, strictly SERIALISED — each event applied to what the
   last one produced, which is what a reduction means and what the step demands.

   BACKPRESSURE IS REAL. Nothing is taken from `events` while a handler is still in flight,
   so a slow handler slows its own machine and no other. That is the useful half of
   serialisation and it is free.

   SERIALISED IS THE ONLY THING CORRECT WITHOUT A LICENCE, and the licensed concurrency of
   :two-events-in-flight-at-once is NOT implemented here. The reason is worth stating
   plainly: taking it needs the HANDLER run apart from the APPLICATION — two handlers in
   flight, their patches applied in order of completion — and `compile` answers one step
   that does both at once. Nothing here can split that, so nothing here pretends to.

   `result` says what goes on :states, and defaults to the state alone. :done is the final
   state either way."
  {:malli/schema [:function [:=> [:cat ifn? :map Source] Machine]
                            [:=> [:cat ifn? :map Source Result] Machine]]}
  ([step initial events] (drive step initial events state-only))
  ([step initial events result]
   (let [out (s/stream)]
     {:states (s/source-only out)
      :done (closing (pump step result initial events out) out)})))

;;; ----------------------------------------------------------------------- fan

(defn fan
  "MANY machines at once, which is where the parallelism is: the stream is partitioned on
   :instance and each machine gets its own reduction, running concurrently. Results from
   every machine arrive on one :states stream.

   `initial-of` is called with an instance the first time that instance is seen, and answers
   that machine's first state — (fn [k] (compile/initial sh k {})) at a call site, which is
   how this stays ignorant of shapes. `result` says what goes on :states and defaults to the
   state alone.

   :done IS A MAP, {instance -> final state}, where `drive` answers one state — a vector
   would have said the same thing while making the caller guess which machine each entry
   belonged to, since a machine's own name is the one thing this function has in hand. Empty
   where no event ever arrived, there being no machine to report on.

   AN EVENT WITH NO :instance IS ITS OWN MACHINE, under the key nil. Deliberate, and it
   costs nothing: a caller who never names anything still works, and
   :one-ordered-stream-per-instance is satisfied by there being one partition.

   THE PARTITION KEY IS READ OFF THE EVENT and never off a state, because routing happens
   BEFORE any state is in hand. That is the load-bearing half of
   :an-instance-has-an-identity, and this is the function that bears it."
  {:malli/schema [:function [:=> [:cat ifn? ifn? Source] Machine]
                            [:=> [:cat ifn? ifn? Source Result] Machine]]}
  ([step initial-of events] (fan step initial-of events state-only))
  ([step initial-of events result]
   (let [out (s/stream)
         machines (atom {})
         done (d/loop []
                (d/chain
                 (s/take! events ::drained)
                 (fn [e]
                   (if (identical? ::drained e)
                     ;; close every input, then wait for every machine — a result still in
                     ;; flight when the events run out is still a result. ONE SNAPSHOT of
                     ;; the atom, so the keys and the deferreds cannot be zipped out of step.
                     (let [ms @machines]
                       (doseq [m (vals ms)] (s/close! (:in m)))
                       (if (empty? ms)
                         {}
                         (d/chain (apply d/zip (map :done (vals ms)))
                                  #(zipmap (keys ms) %))))
                     (let [k (:instance e)
                           m (or (@machines k)
                                 ;; only this loop creates one and it is sequential, so
                                 ;; there is no race here to guard.
                                 (let [in (s/stream)
                                       m {:in in :done (pump step result (initial-of k) in out)}]
                                   (swap! machines assoc k m)
                                   m))]
                       (d/chain (s/put! (:in m) e) (fn [_] (d/recur))))))))]
     {:states (s/source-only out)
      :done (closing done out)})))
