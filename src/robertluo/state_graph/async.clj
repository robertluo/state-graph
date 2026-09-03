(ns robertluo.state-graph.async
  "A DEFAULT and not the core: manifold streams. An event stream reduced to a stream of
   results, one per event.

   EVERYTHING IT NEEDS ARRIVES AS A VALUE — a compiled step function, a way to make a first
   state, and a way to turn a step's answer into an output value. So it never learns what a
   shape is, and somebody with their own stream library can use `compile` directly and lose
   nothing. That is what makes this a battery rather than part of the core.

   PARALLELISM IS ACROSS INSTANCES and serialisation is within one — unless a LICENCE says
   otherwise. `drive` runs a single machine in order; `fan` partitions a stream on :instance
   and runs one drive per machine, concurrently. Given a `Licence` — the step in two halves
   and the pairs `check/commuting` proved — a machine will also run TWO handlers at once
   where the order they finish in cannot be observed. See :parallel-is-across-instances in
   AGENTS.md and :two-events-in-flight-at-once for what that proof is.

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

(def Licence
  "WHAT A PROVEN PAIR NEEDS IN ORDER TO RUN AT ONCE, and the answer to the one thing this
   layer could not do: the step IN ITS TWO HALVES, plus the pairs themselves.

     :patch  (fn [state event] -> a deferred Patch)         the handler, run
     :apply  (fn [state event patch] -> a deferred State)   the patch, landed
     :agree  (fn [state ea pa eb pb])                       throws if the two disagree
     :pairs  {state-id #{#{event-a event-b}}}               check/commuting, verbatim

   :agree IS NOT OPTIONAL, and requiring it is the point. Part of what licensed a pair may
   be a claim about a CLOSURE — that a domain combine is commutative — which no static
   check can settle. So the claim is verified on the concrete values before either patch
   lands, and a Licence that carried no way to do that would be a licence to be silently
   wrong.

   HANDED DOWN AS VALUES, exactly as the step is, so this layer STILL knows nothing of
   shapes, schemas or graphs — which is the rule the whole batteries idea rests on. Only a
   layer that knows the shape can prove a pair commutes, and all it passes is the proof.

   ABSENT MEANS SERIALISE, which is what every caller got before this existed and what
   `drive` and `fan` still do when nothing is passed. Serialised is always correct; this is
   the only thing that makes anything else correct."
  [:map [:patch fn?] [:apply fn?] [:agree fn?]
        [:pairs [:map-of :keyword [:set [:set :keyword]]]]])

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

(defn- licensed?
  "Whether these two events, PENDING IN THIS STATE, were PROVED applicable in order of
   completion. Nothing is inferred here — this is a set lookup of somebody else's proof.

   TWO OF THE SAME EVENT MAY BE LICENSED, since 2026-09-03, and that is the FAN-OUT case:
   n workers feeding one accumulating state send n events of ONE id. It was refused before
   on the ground that `two events of one id run one handler and write one set of keys, so
   they conflict with each other by construction` — true under a merge, and no longer true
   with a COMMUTATIVE COMBINE on every key that :out writes, which is precisely what
   `commutes` requires before licensing such a pair.
   THE ENCODING NEEDED NOTHING — a same-id licence is a SINGLETON in the same set-of-sets,
   so `commuting` publishes #{:found} beside #{:eval :test} and this lookup asks one question
   for both. But it must be built with `hash-set` AND NOT WITH #{}: a set LITERAL of two
   expressions that turn out equal throws `Duplicate key` at RUNTIME, where `hash-set`
   dedupes. Verified, and it is how this was found — the throw landed inside a d/chain and
   the machine simply stopped."
  [pairs state a b]
  (contains? (get pairs (:id state)) (hash-set (:id a) (:id b))))

(defn- pump
  "Take events, step, put one result per event to `out`. Answers a deferred of the final
   state — the STATE and never a result, because the state is the accumulator and a result
   is only what a consumer is told.

   DOES NOT CLOSE `out`, and that is the whole reason it exists apart from `drive`: whoever
   made the sink closes it, which is what lets `fan` share ONE output between many machines.
   The first version of `fan` used s/connect from each machine's own stream instead, and
   s/connect is ASYNCHRONOUS — closing the shared output after every machine reported done
   raced the last value still in a connect pipeline, and lost it. Writing straight to the
   sink means a machine's :done cannot resolve until its last result has been ACCEPTED there.

   WITH A LICENCE IT WILL RUN TWO HANDLERS AT ONCE. The shape of it is one speculative
   take: start this event's handler, then reach for another event WITHOUT waiting, and race
   the two. Whatever the take brings is never wasted — it is either the other half of a
   licensed pair or the next iteration's event, carried forward in `held` — so the reach
   costs nothing when no second event is coming.

   ONLY EVER TWO, deliberately. `check/commuting` is a PAIRWISE relation on ONE state, which
   is precisely what :two-events-in-flight-at-once designed and proved; three in flight would
   need the licence re-established at each intermediate state, and inventing that here would
   be taking more than was proven."
  [step result initial events out licence]
  (let [{patch :patch land :apply agree :agree pairs :pairs} licence
        emit (fn [state event state']
               (d/chain (s/put! out (result state event state')) (fn [_] state')))]
    (d/loop [state initial held nil]
      (d/chain
       (or held (s/take! events ::drained))
       (fn [e]
         (cond
           (identical? ::drained e) state

           ;; STRICTLY IN ORDER — each event applied to what the last one produced, which
           ;; is what a reduction means. Taken with no licence at all AND wherever THIS
           ;; state has no licensed pair, which is most states in most shapes: there is
           ;; then nothing a second event in flight could be paired with, so reaching for
           ;; one early would buy nothing and hold an event for no reason.
           (or (nil? licence) (empty? (get pairs (:id state))))
           (d/chain (step state e)
                    (fn [state'] (emit state e state'))
                    (fn [state'] (d/recur state' nil)))

           :else
           (let [p1  (patch state e)
                 nxt (s/take! events ::drained)]
             (d/chain
              ;; WHICH HAPPENS FIRST: this handler settling, or another event arriving.
              (d/alt (d/chain p1 (fn [_] ::settled))
                     (d/chain nxt (fn [e2] [::arrived e2])))
              (fn [outcome]
                (if-let [e2 (when (vector? outcome)
                              (let [v (second outcome)]
                                (when (and (not (identical? ::drained v))
                                           (licensed? pairs state e v))
                                  v)))]
                  ;; TWO IN FLIGHT. Both handlers were selected from the SAME state, so
                  ;; there is no speculation about which edge either belongs to; the patches
                  ;; are then applied AS THEY LAND rather than as they arrived, which is the
                  ;; whole of the licence. `commutes` proved the diamond closes and the
                  ;; patches satisfy Bernstein's conditions, so where the machine ends up
                  ;; cannot tell you which finished first.
                  ;;
                  ;; THE RACE CARRIES THE PATCH AND NOT ITS DEFERRED for whichever won, and
                  ;; the loser's deferred to be waited on second. `apply` takes a PATCH: a
                  ;; deferred handed to it has no :depth and fails at that seam, which is
                  ;; how this was found rather than shipped.
                  (let [p2 (patch state e2)]
                    (d/chain
                     ;; WHICH LANDED FIRST — the only thing the race is for. Both handlers
                     ;; are already running, so this costs no wall-clock either way.
                     (d/alt (d/chain p1 (fn [v] [e v e2 p2]))
                            (d/chain p2 (fn [v] [e2 v e p1])))
                     (fn [[ea pa eb pb]]
                       ;; BOTH PATCHES BEFORE EITHER LANDS. The concurrency is in the
                       ;; HANDLERS and they have both already run, so waiting here costs
                       ;; only the first RESULT's latency and never the machine's — and it
                       ;; buys the one thing worth more: :agree becomes a genuine
                       ;; PRE-CONDITION. Applying one patch and then discovering the
                       ;; licence was invalid would emit a result derived from an unsound
                       ;; proof, which is the silent wrongness this whole check exists for.
                       (d/chain
                        pb
                        (fn [pbv]
                          (agree state ea pa eb pbv)
                          (d/chain
                           (land state ea pa)
                           (fn [s1] (emit state ea s1))
                           (fn [s1] (d/chain (land s1 eb pbv)
                                             (fn [s2] (emit s1 eb s2))
                                             (fn [s2] (d/recur s2 nil))))))))))
                  ;; NOT A PAIR — the handler landed first, or what arrived may not be
                  ;; applied beside it. Finish this event and carry the take forward: `nxt`
                  ;; is the same deferred either way, so nothing is ever taken twice and
                  ;; nothing is dropped.
                  (d/chain p1
                           (fn [p] (land state e p))
                           (fn [state'] (emit state e state'))
                           (fn [state'] (d/recur state' nxt)))))))))))))

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

   SERIALISED IS THE ONLY THING CORRECT WITHOUT A LICENCE, and with one it will run two
   handlers AT ONCE. A `Licence` carries the step in its two halves and the pairs
   `check/commuting` proved: where the next event to arrive is licensed against the one in
   flight, both handlers run and their patches are applied AS THEY LAND. Pass nothing and
   this serialises exactly as it always did.

   THE COST OF TAKING IT, said out loud, and there are two. :states reports in COMPLETION
   order and not arrival order, so a pair may appear swapped — not a flake, the pair having
   been proved to end in the same state either way round, but visible to whoever stores the
   results, and an audit trail should represent what happened rather than a sequence that
   did not. And the FIRST of a pair waits for the second's handler before its result is
   put, because the law that licensed them is verified with both patches in hand. The
   machine reaches its final state no later for it; only the intermediate row is delayed.

   `result` says what goes on :states, and defaults to the state alone. :done is the final
   state either way."
  {:malli/schema [:function [:=> [:cat ifn? :map Source] Machine]
                            [:=> [:cat ifn? :map Source Result] Machine]
                            [:=> [:cat ifn? :map Source Result [:maybe Licence]] Machine]]}
  ([step initial events] (drive step initial events state-only))
  ([step initial events result] (drive step initial events result nil))
  ([step initial events result licence]
   (let [out (s/stream)]
     {:states (s/source-only out)
      :done (closing (pump step result initial events out licence) out)})))

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
   :an-instance-has-an-identity, and this is the function that bears it.

   A `Licence` applies WITHIN each machine and is shared by all of them, being a fact about
   the shape and not about any one run. Across instances is where the parallelism was
   already; this is the narrower concurrency inside one."
  {:malli/schema [:function [:=> [:cat ifn? ifn? Source] Machine]
                            [:=> [:cat ifn? ifn? Source Result] Machine]
                            [:=> [:cat ifn? ifn? Source Result [:maybe Licence]] Machine]]}
  ([step initial-of events] (fan step initial-of events state-only))
  ([step initial-of events result] (fan step initial-of events result nil))
  ([step initial-of events result licence]
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
                                       m {:in in :done (pump step result (initial-of k)
                                                             in out licence)}]
                                   (swap! machines assoc k m)
                                   m))]
                       (d/chain (s/put! (:in m) e) (fn [_] (d/recur))))))))]
     {:states (s/source-only out)
      :done (closing done out)})))
