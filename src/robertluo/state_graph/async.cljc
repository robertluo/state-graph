(ns robertluo.state-graph.async
  "A DEFAULT and not the core: core.async channels. An event channel reduced to a channel
   of results, one per event.

   EVERYTHING IT NEEDS ARRIVES AS A VALUE — a compiled step function, a way to make a first
   state, and a way to turn a step's answer into an output value. So it never learns what a
   shape is, and somebody with their own stream library can use `compile` directly and lose
   nothing. That is what makes this a battery rather than part of the core.

   PARALLELISM IS ACROSS INSTANCES and serialisation is within one — unless a LICENCE says
   otherwise. `drive` runs a single machine in order; `fan` partitions a channel on
   :instance and runs one drive per machine, concurrently. Given a `Licence` — the step in
   two halves and the pairs `check/commuting` proved — a machine will also run TWO handlers
   at once where the order they finish in cannot be observed. See
   :parallel-is-across-instances on `fan` and :two-events-in-flight-at-once on
   `check/confluence` for what that proof is.

   AN EXCEPTION IS A VALUE HERE. A go block is a boundary an exception does not cross, so
   where one would be lost at that boundary it is caught and handed on AS IT IS — the same
   object, with its message, its data, its cause and its trace — and :done delivers it.

   Written for both hosts, and requires core.async and NOTHING BELOW THIS REQUIRES IT —
   which is the whole purpose of the Context that `compile` takes."
  {:knowledge
   [{:id :the-defaults-are-batteries
     :kind :decision
     :says "This namespace is a DEFAULT and not the core. Someone with their own stream library must be able to use the compiled step directly and lose nothing, so it may not take a shape as an argument — and it does not: it is handed a step, a way to make a first state, a way to make an output value and a licence, all as VALUES, and never learns what a shape, a schema or a graph is."
     :cites [:inject-a-function-and-never-thread-options :compilation-and-lifecycle]}
    {:id :manifold-drags-in-slf4j-with-no-binding
     :kind :lesson
     :says "manifold drags in slf4j-api with no binding, so the suite prints three SLF4J NOP lines on stderr. Noise, not a fault, and worth knowing before someone hunts it."}
    {:id :dependency-manifold
     :kind :decision
     :says "manifold 0.4.3 is the async default, and nothing below this layer requires it. Its Deferred is a clojure.lang.IDeref, which is what lets the pure core deref one without depending on manifold; d/chain takes a plain value as happily as a deferred and FLATTENS; s/connect is ASYNCHRONOUS, which cost a lost state once."
     :cites [:the-defaults-are-batteries :s-connect-is-asynchronous]}
    {:id :manifold-is-pinned-at-what-its-consumers-resolve
     :kind :decision
     :says "manifold is pinned at 0.5.0 since 2026-09-15, for the release: 0.4.3 had been the pin since the library began, every consumer in the repository it grew up in pinned 0.5.0, and one classpath keeps one version — so the library's pin was the only thing in the repository still saying otherwise. The whole suite had passed on 0.5.0 with an override on 2026-09-02 and passes on it now; only long-stable API is used."
     :when "2026-09-15"
     :supersedes [:dependency-manifold]
     :cites [:dependency-manifold]}
    {:id :core-async-is-the-async-default
     :kind :decision
     :says "core.async 1.9.865 is the async default since 2026-09-25, replacing manifold, and this namespace is .cljc. ClojureScript is the goal and manifold is JVM-only; core.async is the one channel library on both hosts, and its unbuffered channel, promise-chan and alts! are what the pump and the fan need, one for one. Nothing below this layer requires it, exactly as nothing required manifold."
     :why "What it cost, said out loud: a channel carries no error, so an exception had to become a value — see :an-exception-at-a-go-boundary-is-handed-on-as-it-is; and a channel is not IDeref, so a handler answering one no longer runs under compile's synchronous default on its own — see `blocking`. What it bought beyond portability: alts! completes EXACTLY ONE of the operations it races, so the speculative take manifold committed and had to carry forward is simply not taken when it loses. And the slf4j noise went with manifold."
     :from "the author, 2026-09-25: `ClojureScript is the goal, go with core.async`"
     :when "2026-09-25"
     :supersedes [:manifold-is-pinned-at-what-its-consumers-resolve]
     :cites [:the-defaults-are-batteries]}
    {:id :an-exception-at-a-go-boundary-is-handed-on-as-it-is
     :kind :decision
     :says "An exception thrown inside a go block does not cross it — it goes to the uncaught handler and the block's channel simply closes, which for a machine is :done never settling and :states never closing. So wherever one would be lost at that boundary, and only there, it is CAUGHT AND HANDED ON AS IT IS: the same Throwable, not wrapped, not summarised, not converted to a map, so its message, ex-data, cause chain and stack all arrive. :done delivers it in place of the final state. That is not the bare try/catch the library forbids: nothing is swallowed and nothing is thrown away."
     :from "the author, 2026-09-25: `it has to be because the boundary of an exception. We need to turn the exception into data, however, not throw away information.`"
     :when "2026-09-25"
     :cites [:core-async-is-the-async-default]}]}
  (:require [clojure.core.async :as a]
            ;; The JVM names the protocol's interface directly in `port?`, and core.async has
            ;; loaded it; only ClojureScript asks the protocol by its alias.
            #?(:cljs [clojure.core.async.impl.protocols :as p])))

;;; ---------------------------------------------------------------- vocabulary

(defn- port?
  "Whether `x` is something a value can be taken from. core.async publishes no predicate,
   and ReadPort is the protocol every channel and promise-chan implements.

   instance? ON THE JVM, not satisfies?: this is asked of every step's answer and every
   bind, and the common answer is NO — a plain map — which is where satisfies? walks the
   class's ancestors. Every core.async port implements the protocol's interface directly,
   so the answers are the same. See :port-is-asked-by-instance-on-the-jvm."
  {:knowledge
   [{:id :port-is-asked-by-instance-on-the-jvm
     :kind :decision
     :says "`port?` is instance? on the JVM and satisfies? only on ClojureScript. It is asked of every step's answer and every bind, and the usual answer is NO, a plain map, which is exactly where satisfies? walks the class's ancestors. MEASURED here: 20,000 events through `sg/run` took about 630 ms with satisfies? and about 240 ms with instance?, the reviewer's own figures being 590 and 225. Every core.async port implements the protocol's interface directly, so the answers do not change; what is given up is a foreign port that satisfies ReadPort only by extend-protocol, which nothing here makes."
     :from "a review of PR #4, 2026-09-25, suggesting the change with its measurement; the author agreed"
     :when "2026-09-25"
     :cites [:core-async-is-the-async-default]}]}
  [x]
  #?(:clj  (instance? clojure.core.async.impl.protocols.ReadPort x)
     :cljs (satisfies? p/ReadPort x)))

(defn- error?
  "Whether a value that came off a channel is an exception handed on in place of one."
  [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defn- raise
  "Inside a go block: the value — or, where it is an exception handed on, that exception
   thrown again, for the one catch at the edge of this block to hand on in its turn."
  [x]
  (if (error? x) (throw x) x))

(defn- present
  "What came off `port`, or an exception saying nothing did. A channel closed EMPTY is a
   handler that answered nothing, and a nil handed on would fail one seam later with a
   worse diagnosis."
  [x]
  (if (nil? x)
    (ex-info "A channel closed without answering a value" {:crossing :channel})
    x))

(defn- settled
  "A promise-chan answering `x`, or `x` itself where it is already a port."
  [x]
  (if (port? x) x (doto (a/promise-chan) (a/put! x))))

(def Source
  "A channel something can be taken from."
  [:fn port?])

(def ^{:knowledge
       [{:id :consume-states-or-done-may-never-resolve
         :kind :lesson
         :says "The output is unbuffered and backpressure is real, so a machine whose :states nobody is reading stops rather than racing ahead, and a test that derefs :done before draining :states HANGS. That is the correct behaviour, and every deref of a machine in a test is bounded for it."
         :cites [:the-defaults-are-batteries]}
        {:id :fans-done-resolves-empty-where-no-event-arrived
         :kind :lesson
         :says "`fan`'s :done resolves {} where no event ever arrived, which fell out of keying it by instance and is right: there is no machine until an event names one. The initial state is not a transition and never appears on :states either."}]}
  Machine
  "What `drive` and `fan` answer. TWO DIFFERENT THINGS, so two names.

   :states is a channel of one RESULT per event, closed when there are no more coming — by
   draining or by throwing, so a consumer is never left waiting on a machine that has
   stopped. What a result IS belongs to whoever passed the `result` function: the state by
   default, and a layer that knows the shape puts something richer there.

   :done is a promise-chan delivering the final state — `fan` delivering one per instance —
   or THE EXCEPTION the step threw, as it was thrown. That is where an error belongs:
   `compile` treats a crossing that does not hold as a DEFECT and not a fact about the run,
   and a channel of results has nowhere honest to put one. A promise-chan, so it may be
   read as often as anybody likes.

   CONSUME :states, OR :done MAY NEVER DELIVER. The output is unbuffered and backpressure
   is real, so a machine whose states nobody is reading stops rather than racing ahead.
   That is the correct behaviour and it is worth knowing before waiting on :done first."
  [:map [:states Source] [:done Source]])

(def Result
  "HOW A STEP'S ANSWER BECOMES AN OUTPUT VALUE: the state an event was applied to, the
   event, and the state that came back. Never nil, which no channel will carry.

   INJECTED FOR THE SAME REASON THE Context IS. Whether an event FIRED is shape knowledge
   and this layer has no shape — an event nobody handled answers the state unchanged, and
   there is nothing in the two states to tell that from a self-loop that fired. So a layer
   that does know the shape closes over one and hands the maker down as a VALUE, and this
   one goes on knowing only that some value is to be put."
  [:=> [:cat :map :map :map] :some])

(def state-only
  "The default `result` — put the new state and say nothing about what caused it. Exactly
   what this layer put before it was parameterised, so a caller who wants a channel of
   states passes nothing."
  (fn [_state _event state'] state'))

(def ^{:knowledge
       [{:id :the-runtime-takes-the-licence
         :kind :decision
         :says "The runtime takes the licence the checks prove, since 2026-09-03: the step in its two halves, plus the pairs `check/commuting` proved, handed down as values exactly as the step is — so this layer still knows nothing of shapes, and all a shape-aware layer passes is the proof. ABSENT MEANS SERIALISE, which is what every caller got before this existed. Measured: two 400ms handlers on the licensed pair of a join went 843ms to 418ms."
         :when "2026-09-03"
         :cites [:two-events-in-flight-at-once :the-split-is-decided-by-what-each-crossing-depends-on :the-defaults-are-batteries]}
        {:id :agree-is-not-optional
         :kind :decision
         :says ":agree is not optional, and requiring it is the point. Part of what licensed a pair may be a claim about a CLOSURE — that a domain combine is commutative — which no static check can settle, so the claim is verified on the concrete values before either patch lands. A Licence that carried no way to do that would be a licence to be silently wrong."
         :cites [:the-runtime-takes-the-licence :agree-verifies-the-law-on-the-concrete-values]}
        {:id :agree-had-to-become-a-precondition
         :kind :lesson
         :says "Applying one patch and only then discovering the licence was invalid would emit a result derived from an unsound proof, so `pump` waits for BOTH patches before landing either. It costs nothing in wall-clock — the concurrency is in the handlers and both are already running — and delays only the first result's row. It broke the tests that proved the ordering: two gated tests read the first result and only then opened the gate, which under wait-for-both is a deadlock."
         :cites [:agree-is-not-optional]}
        {:id :a-set-literal-of-two-equal-expressions-throws
         :kind :lesson
         :says "A same-id licence is a SINGLETON in the same set-of-sets, so one lookup serves both kinds — but it must be built with `hash-set` and never with #{}: a set LITERAL of two expressions that turn out equal throws `Duplicate key` at RUNTIME, where hash-set and set dedupe. The throw landed inside a d/chain, so the machine did not crash — it simply STOPPED, :done never settled, :states never closed, and the symptom was two timeouts and a nil. My own docstring had asserted `the encoding needed nothing` one edit earlier."
         :cites [:the-diagonal-is-the-fan-out]}]}
  Licence
  "WHAT A PROVEN PAIR NEEDS IN ORDER TO RUN AT ONCE, and the answer to the one thing this
   layer could not do: the step IN ITS TWO HALVES, plus the pairs themselves.

     :patch  (fn [state event] -> a patch, or a channel of one, see compile/phases)  the handler, run
     :apply  (fn [state event patch] -> a State, or a channel of one)  the patch, landed
     :agree  (fn [state ea pa eb pb])                                  throws if the two disagree
     :pairs  {state-id #{#{event-a event-b}}}                          check/commuting, verbatim

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

(defn- then
  "compile's :then over channels — a BIND, so it flattens: where `v` is a port, what `f`
   answers for its value, taken off again if `f` answered a port too. A plain `v` goes
   straight to `f`, with no block and no channel, so a handler answering a map costs
   nothing here and a throw from it reaches whoever called the step.

   THE CATCH IS THE BOUNDARY, and :an-exception-at-a-go-boundary-is-handed-on-as-it-is is
   why it is not a bare one: an exception taken off `v`, thrown by `f`, or taken off what
   `f` answered is handed on as the block's value, the same object, and never swallowed."
  [v f]
  (if (port? v)
    (a/go
      (try
        (let [r (f (raise (present (a/<! v))))]
          (if (port? r) (present (a/<! r)) r))
        (catch #?(:clj Throwable :cljs :default) e e)))
    (f v)))

(def ^{:knowledge
       [{:id :a-manifold-deferred-is-ideref
         :kind :lesson
         :says "Verified 2026-08-31 in this project's own REPL: a manifold Deferred implements clojure.lang.IDeref, which is what lets the synchronous default deref one without a dependency, and d/chain takes a plain value as happily as a deferred, so a handler answering an ordinary map costs nothing here. Clojure's own delay, promise and future are IDeref too, which is how the deref decision was tested with no manifold."
         :when "2026-08-31"
         :cites [:a-deferred-under-the-synchronous-default-is-dereferenced]}
        {:id :a-handler-answers-a-channel
         :kind :decision
         :says "Under this Context a handler may answer a CHANNEL — a promise-chan, a go block, a/thread — delivering its map, or delivering an exception to fail. Anything else is a value already available. :pure puts a value in a promise-chan, so the step always answers a channel here, as it always answered a deferred."
         :when "2026-09-25"
         :cites [:core-async-is-the-async-default :a-handler-may-answer-later]}]}
  context
  "The Context to hand `compile` so that a handler may answer a channel and the step
   answers one too. TWO FUNCTIONS, and that is the whole of what core.async contributes to
   the core — which still requires no core.async and never learns what a channel is.

   A HANDLER MAY ANSWER A CHANNEL delivering its map, or an exception to fail with. A
   handler that BLOCKS should answer (a/thread ...) rather than block where it was called,
   because a go block's pool is small and a machine is meant to hold no thread while it
   waits."
  {:then then
   :pure settled})

#?(:clj
   (def ^{:knowledge
          [{:id :a-channel-is-not-ideref
            :kind :lesson
            :says "Verified 2026-09-25 in this project's own REPL: a core.async channel is NOT clojure.lang.IDeref, where a manifold Deferred was. So compile's synchronous default, which derefs what is IDeref and passes on what is not, would hand a handler's channel to the :out check as if it were the answer — one Context no longer served a handler written for the stream."
            :when "2026-09-25"
            :cites [:a-manifold-deferred-is-ideref :a-deferred-under-the-synchronous-default-is-dereferenced]}
           {:id :blocking-is-the-jvm-s-way-to-mix-the-doors
            :kind :decision
            :says "`blocking` is the Context that runs a handler written for the stream through a synchronous door — `(compile sh blocking)`, or {:context blocking} to a driver — by TAKING from a channel with <!!, where the synchronous default derefs. It lives here because only this layer may know what a channel is, and it is JVM-only because a JavaScript host cannot block at all: on ClojureScript a handler that answers a channel runs through the stream door and nowhere else."
            :when "2026-09-25"
            :cites [:a-channel-is-not-ideref :the-defaults-are-batteries]}]}
     blocking
     "The Context for running, SYNCHRONOUSLY, a shape whose handlers answer channels: a
      channel is taken from with <!! and anything else is used as it is, so the step
      answers a State, as under compile's default. An exception delivered on a channel is
      THROWN, the same object, because a synchronous step has nowhere else to put it.

      JVM ONLY. A JavaScript host cannot block, so there a handler that answers a channel
      is run through `drive` or `fan`."
     {:then (fn [v f] (f (if (port? v) (raise (present (a/<!! v))) v)))
      :pure identity}))

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
  "Take events, step, put one result per event to `out`. Answers a promise-chan of the
   final state — the STATE and never a result, because the state is the accumulator and a
   result is only what a consumer is told — or of the exception that stopped it.

   DOES NOT CLOSE `out`, and that is the whole reason it exists apart from `drive`: whoever
   made the sink closes it, which is what lets `fan` share ONE output between many machines.
   The first version of `fan` used s/connect from each machine's own stream instead, and
   s/connect is ASYNCHRONOUS — closing the shared output after every machine reported done
   raced the last value still in a connect pipeline, and lost it. Writing straight to the
   sink means a machine's :done cannot deliver until its last result has been ACCEPTED there.

   WITH A LICENCE IT WILL RUN TWO HANDLERS AT ONCE. The shape of it is one speculative
   take: start this event's handler, then race it against the next event with alts!. alts!
   completes EXACTLY ONE of the two, so a take that loses the race never happened and
   nothing is taken twice; an event that wins it is either the other half of a licensed
   pair or the next iteration's event, carried forward in `held`.

   ONLY EVER TWO, deliberately. `check/commuting` is a PAIRWISE relation on ONE state, which
   is precisely what :two-events-in-flight-at-once designed and proved; three in flight would
   need the licence re-established at each intermediate state, and inventing that here would
   be taking more than was proven.

   ONE CATCH, AT THE EDGE OF THE BLOCK, and it hands the exception on as :done's value —
   see :an-exception-at-a-go-boundary-is-handed-on-as-it-is. Everything inside throws."
  [step result initial events out licence]
  (let [{patch :patch land :apply agree :agree pairs :pairs} licence
        done (a/promise-chan)]
    (a/go
      (a/>!
       done
       (try
         (loop [state initial held nil]
           (let [e (or held (a/<! events))]
             (cond
               ;; nil is a closed channel: drained.
               (nil? e) state

               ;; STRICTLY IN ORDER — each event applied to what the last one produced,
               ;; which is what a reduction means. Taken with no licence at all AND wherever
               ;; THIS state has no licensed pair, which is most states in most shapes:
               ;; there is then nothing a second event in flight could be paired with, so
               ;; reaching for one early would buy nothing and hold an event for no reason.
               (or (nil? licence) (empty? (get pairs (:id state))))
               (let [state' (raise (present (a/<! (settled (step state e)))))]
                 (a/>! out (result state e state'))
                 (recur state' nil))

               :else
               (let [p1 (settled (patch state e))
                     ;; WHICH HAPPENS FIRST: this handler settling, or another event
                     ;; arriving. :priority so that where both are ready the handler wins,
                     ;; which is the serial order and never needs a licence.
                     [v port] (a/alts! [p1 events] :priority true)]
                 (if (and (not= port p1) (some? v) (licensed? pairs state e v))
                   ;; TWO IN FLIGHT. Both handlers were selected from the SAME state, so
                   ;; there is no speculation about which edge either belongs to; the
                   ;; patches are then applied AS THEY LAND rather than as they arrived,
                   ;; which is the whole of the licence. `commutes` proved the diamond
                   ;; closes and the patches satisfy Bernstein's conditions, so where the
                   ;; machine ends up cannot tell you which finished first.
                   (let [e2 v
                         p2 (settled (patch state e2))
                         ;; WHICH LANDED FIRST — the only thing the race is for. Both
                         ;; handlers are already running, so this costs no wall-clock
                         ;; either way. THE RACE CARRIES THE PATCH for whichever won and
                         ;; the loser's port to be waited on second.
                         [pa port'] (a/alts! [p1 p2] :priority true)
                         [ea eb pb-port] (if (= port' p1) [e e2 p2] [e2 e p1])
                         pa (raise (present pa))
                         ;; BOTH PATCHES BEFORE EITHER LANDS. The concurrency is in the
                         ;; HANDLERS and they have both already run, so waiting here costs
                         ;; only the first RESULT's latency and never the machine's — and
                         ;; it buys the one thing worth more: :agree becomes a genuine
                         ;; PRE-CONDITION. Applying one patch and then discovering the
                         ;; licence was invalid would emit a result derived from an unsound
                         ;; proof, which is the silent wrongness this whole check exists for.
                         pb (raise (present (a/<! pb-port)))
                         _  (agree state ea pa eb pb)
                         s1 (raise (present (a/<! (settled (land state ea pa)))))
                         _  (a/>! out (result state ea s1))
                         s2 (raise (present (a/<! (settled (land s1 eb pb)))))]
                     (a/>! out (result s1 eb s2))
                     (recur s2 nil))
                   ;; NOT A PAIR — the handler landed first, or what arrived may not be
                   ;; applied beside it, or the events ran out. Finish this event and carry
                   ;; forward whatever the race took: an event, or nothing.
                   (let [p      (raise (present (if (= port p1) v (a/<! p1))))
                         state' (raise (present (a/<! (settled (land state e p)))))]
                     (a/>! out (result state e state'))
                     (recur state' (when (not= port p1) v))))))))
         (catch #?(:clj Throwable :cljs :default) ex ex))))
    done))

(defn- closing
  "Close `out` when `done` delivers, whatever it delivers — a final state or an exception —
   so a consumer is not left waiting on a machine that has stopped. `done` is a
   promise-chan, so taking from it here leaves it for the caller."
  [done out]
  (a/go (a/<! done) (a/close! out))
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
   state either way, or the exception that stopped the machine."
  {:malli/schema [:function [:=> [:cat ifn? :map Source] Machine]
                            [:=> [:cat ifn? :map Source Result] Machine]
                            [:=> [:cat ifn? :map Source Result [:maybe Licence]] Machine]]
   :knowledge
   [{:id :a-speculative-take-is-skipped-where-no-licensed-pair
     :kind :decision
     :says "With a licence the pump runs two handlers at once by one speculative take: start this event's handler, reach for another event WITHOUT waiting, and race the two. Whatever the take brings is never wasted — the other half of a licensed pair, or the next iteration's event carried forward. The reach is SKIPPED where this state has no licensed pair, which is most states in most shapes, or a shape with one licensed pair would hold an event early everywhere."
     :cites [:the-runtime-takes-the-licence :only-ever-two-in-flight]}
    {:id :completion-order-is-testable-without-a-clock
     :kind :lesson
     :says "Completion order is testable without a clock: park the first event's handler on a deferred the test resolves by hand, so the second can only land first. One timing test remains and is ^:integration, asserting the thing only a clock can — serialisation with a handler that really is slower."
     :cites [:a-speculative-take-is-skipped-where-no-licensed-pair]}
    {:id :the-race-carries-the-patch-and-not-its-deferred
     :kind :lesson
     :says "The race carries the PATCH for whichever handler won and the loser's deferred to be waited on second. `apply` takes a patch: a deferred handed to it has no :depth and fails at that seam, which is how this was found rather than shipped."
     :cites [:a-patch-has-to-say-whose-it-is]}
    {:id :a-rendezvous-decides-a-race-a-gate-does-not
     :kind :lesson
     :says "Parking the first handler on a gate and opening it once both events were TAKEN stopped deciding the race under core.async: 16 runs in 20 came back in arrival order under instrumentation. Under manifold the whole chain ran in the test's own thread, so the race was decided before `drive` returned; a go block runs on another thread, and the pump computing the second patch — slow with malli instrumenting — looked at the two only after the gate was open, when both had answered and :priority rightly chose arrival order. The machine was right and the test was not. The test now answers the patches itself on UNBUFFERED channels, so each delivery is a rendezvous with the very take that decides and returns only once the machine has made it; 20 runs in 20. Through the facade, where :patch cannot be reached, concurrency is proved by DEADLOCK instead: the second handler opens the first one's gate, which a serialised machine never reaches."
     :when "2026-09-25"
     :supersedes [:completion-order-is-testable-without-a-clock]
     :cites [:core-async-is-the-async-default]}
    {:id :the-async-layer-is-mutation-tested
     :kind :lesson
     :says "A bug in a go block stops a machine in silence, so on 2026-09-25 the tests of this namespace were held to the code by MUTATION: nineteen deliberate breakages — the boundary catches removed, a bind that did not flatten, a held event dropped, the second race fixed in arrival order, :agree skipped, the output never closed, the fan's put not raced — each applied alone and the async and facade suites run against it. Seventeen turned them red. The two that did not were the fan closing its other machines on the way out, which nothing could see and which went — see :a-stopped-fan-leaves-its-machines-to-the-collector. One more survives on purpose: :priority on the FIRST race, handler before event. Where both are ready it only chooses whether the second handler starts before the first has landed, and a licensed pair ends in the same rows either way; it saves a speculative start and is not a correctness claim. The run also found `run` HANGING on data its first state refuses, the one regression manifold's d/chain had been hiding. Re-run the mutations after changing `then`, `pump` or `fan`."
     :when "2026-09-25"
     :cites [:an-exception-at-a-go-boundary-is-handed-on-as-it-is :a-rendezvous-decides-a-race-a-gate-does-not]}]}
  ([step initial events] (drive step initial events state-only))
  ([step initial events result] (drive step initial events result nil))
  ([step initial events result licence]
   (let [out (a/chan)]
     {:states out
      :done (closing (pump step result initial events out licence) out)})))

;;; ----------------------------------------------------------------------- fan

(defn fan
  "MANY machines at once, which is where the parallelism is: the channel is partitioned on
   :instance and each machine gets its own reduction, running concurrently. Results from
   every machine arrive on one :states channel.

   `initial-of` is called with an instance the first time that instance is seen, and answers
   that machine's first state — (fn [k] (compile/initial sh k {})) at a call site, which is
   how this stays ignorant of shapes. `result` says what goes on :states and defaults to the
   state alone.

   :done DELIVERS A MAP, {instance -> final state}, where `drive` delivers one state — a
   vector would have said the same thing while making the caller guess which machine each
   entry belonged to, since a machine's own name is the one thing this function has in
   hand. Empty where no event ever arrived, there being no machine to report on. OR IT
   DELIVERS THE EXCEPTION that stopped any one machine, or that `initial-of` threw.

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
                            [:=> [:cat ifn? ifn? Source Result [:maybe Licence]] Machine]]
   :knowledge
   [{:id :parallel-is-across-instances
     :kind :decision
     :says "`Automatically parallel` means ACROSS INSTANCES and nothing else: events partitioned by instance, one sequential reduction each, run at once. Orthogonal regions inside one machine are out."
     :why "Why one machine cannot parallelise is a DATA DEPENDENCY and not anything about manifold: ADMISSION. Whether this state admits this event cannot be known until the previous step has landed, so a parallel map over one machine's stream would run handlers SPECULATIVELY, and since deferreds exist so handlers can do I/O, speculation means real effects for events the machine ignores. A third reason holds where that fails: a handler that CAUSES an event makes parallel handlers interleave WRONGLY, which is why statecharts have run-to-completion. The first version of the argument was that handler SELECTION needs the state; that stopped being true when the handler became the event's, and the conclusion survived the change to its reason."
     :cites [:a-handler-belongs-to-the-event :a-handler-causes-nothing]}
    {:id :one-ordered-stream-per-instance
     :kind :decision
     :says "A machine is fed ONE TOTALLY ORDERED stream. A caller with several sources merges them into one order before the machine sees them, because the caller is the only one who can — the machine has no clock. It is a requirement the library states rather than an assumption it quietly makes."
     :why "What it buys is a whole feature: if external order is guaranteed then an event this state cannot handle is never EARLY, so DEFERRED EVENTS — the UML mechanism where a state parks an event and the machine re-delivers it — are not needed: a per-instance queue, a re-drive on every state change and a deadlock case, all avoided by writing an assumption down. Where it breaks — two producers with no shared clock, an at-least-once transport, a partitioned queue spanning one instance — is the caller's to fix upstream."
     :cites [:parallel-is-across-instances]}
    {:id :the-partition-key-is-read-off-the-event
     :kind :decision
     :says "The partition key is read off the EVENT and never off a state, because routing happens BEFORE any state is in hand. That is the load-bearing half of :an-instance-has-an-identity, and this is the function that bears it. An event with no :instance is its own machine, under the key nil."
     :cites [:an-instance-has-an-identity :one-ordered-stream-per-instance]}
    {:id :done-is-a-map-keyed-by-instance
     :kind :decision
     :says ":done is a MAP {instance -> final state} where `drive` answers one state. A vector would have said the same thing while making the caller guess which machine each entry belonged to, since a machine's own name is the one thing this function has in hand. The wart, accepted: a caller who named nothing finds their machine under nil."
     :cites [:the-partition-key-is-read-off-the-event]}
    {:id :s-connect-is-asynchronous
     :kind :lesson
     :says "s/connect is asynchronous, and it cost a LOST STATE: giving each machine its own stream and connecting them into one output dropped whatever was still in a connect pipeline when the output was closed — instance a ran three events and only two states came out. Every machine now writes STRAIGHT to the shared sink, so a machine's :done cannot resolve until its last result has been ACCEPTED there, and only whoever made the sink closes it."
     :cites [:parallel-is-across-instances]}
    {:id :a-stopped-machine-stops-the-fan
     :kind :decision
     :says "An event routed to a machine that has already stopped on a defect is not put and waited on for ever: the put is raced against that machine's :done, and where :done has delivered, the fan delivers the same exception, closes every machine's input and stops. Under manifold the put would have pended with nobody taking, and the fan's :done would never have settled."
     :when "2026-09-25"
     :cites [:an-exception-at-a-go-boundary-is-handed-on-as-it-is :consume-states-or-done-may-never-resolve]}
    {:id :a-stopped-fan-leaves-its-machines-to-the-collector
     :kind :decision
     :says "An event routed to a machine that has already stopped is still not put and waited on for ever: the put is raced against that machine's :done, and the fan delivers the exception it finds there. `initial-of` throwing is handed on the same way, by the fan's own boundary catch — before it had one, a first state its schema refused HUNG `run` for ever. Either way the fan closes NOTHING ELSE. Closing every other machine's input was written and then refuted by mutation: removing it, on either road out, left every test green, because nothing a caller holds can see it. :states is closed by `closing` and :done has delivered; a machine still mid-handler finds the output closed and its put answers false; a pump parked on an input nobody can reach any more is garbage with it. Code no test can fail is code that was guarding nothing, so it went, and with it the atom it had been the only reason for."
     :when "2026-09-25"
     :supersedes [:a-stopped-machine-stops-the-fan]
     :cites [:a-stopped-machine-stops-the-fan]}]}
  ([step initial-of events] (fan step initial-of events state-only))
  ([step initial-of events result] (fan step initial-of events result nil))
  ([step initial-of events result licence]
   (let [out  (a/chan)
         done (a/promise-chan)]
     (a/go
       (a/>!
        done
        (try
          ;; ONLY THIS LOOP CREATES A MACHINE and it is sequential, so the map of them is
          ;; the loop's own accumulator and nothing needs guarding.
          (loop [machines {}]
            (let [e (a/<! events)]
              (if (nil? e)
                ;; close every input, then wait for every machine — a result still in
                ;; flight when the events run out is still a result. The first exception
                ;; any of them delivers is what the fan delivers.
                (do (doseq [m (vals machines)] (a/close! (:in m)))
                    (loop [acc {} [[k m] & more] (seq machines)]
                      (if (nil? m)
                        acc
                        (let [v (a/<! (:done m))]
                          (if (error? v) v (recur (assoc acc k v) more))))))
                (let [k (:instance e)
                      ;; `initial-of` is the caller's and may throw — a first state its
                      ;; own schema refuses — which the catch below hands on.
                      m (or (get machines k)
                            (let [in (a/chan)]
                              {:in in :done (pump step result (initial-of k) in out licence)}))
                      machines (assoc machines k m)
                      [v port] (a/alts! [[(:in m) e] (:done m)])]
                  (if (= port (:done m))
                    ;; THE MACHINE STOPPED before it would take this — a defect, since
                    ;; only a closed input ends one otherwise. See
                    ;; :a-stopped-machine-stops-the-fan.
                    v
                    (recur machines))))))
          ;; THE BOUNDARY, as in `pump`: see :an-exception-at-a-go-boundary-is-handed-on-as-it-is.
          ;; Nothing else is closed on the way out, and nothing needs to be — see
          ;; :a-stopped-fan-leaves-its-machines-to-the-collector.
          (catch #?(:clj Throwable :cljs :default) ex ex))))
     {:states out
      :done (closing done out)})))
