;; # robertluo.state-graph — a tutorial
;;
;; A finite state machine whose **shape is a graph**. This notebook works through the whole
;; facade — ten functions — and ends with a workflow big enough to be worth drawing.
;;
;; Every diagram below is the library's own drawing, rendered in your browser. Reading this
;; page needs nothing installed; `sg/draw!` with a real image format needs graphviz.

(ns tutorial
  (:require [manifold.deferred :as d]
            [manifold.stream :as s]
            [robertluo.state-graph :as sg]
            [robertluo.state-graph.shape :as shape]
            [scicloj.kindly.v4.kind :as kind]))

;; ## A machine is three kinds of part
;;
;; A **state** is a node, shaped by a malli schema and validated on enter. The schema
;; describes the state's *own* data — `:id` (which node it is in) and `:instance` (which run
;; it belongs to) are the machine's to write, and it writes them for you.

(sg/state :todo [:map [:what :string]] {:initial true})

;; A part is a plain map, so a shape is something you can build with `map`, `for`, or
;; anything else that makes data. Exactly one state carries `{:initial true}`.
;;
;; An **event** is shaped by a schema too, and it *carries its handler*. By default the
;; handler takes *the event alone* — nothing of the state it is about to change — and answers
;; a map that is **merged into** the state. The last argument declares that map's schema, which
;; is what makes the static check further down possible. Where a handler genuinely does need
;; something from the state it is changing, the **event declares what it may see**: visibility
;; from the inside is declared, never automatic, and *Reading the state* below is that half.

(sg/event :start [:map [:who :string]] (fn [e] {:who (:who e)}) [:map [:who :string]])

;; A **transition** is an edge: from a state, on an event, to a state. Three keywords and no
;; function, because what handles an event belongs to the event.

(sg/transition :todo :start :doing)

;; ## A shape is a graph

(def task
  (sg/shape
   (sg/state :todo  [:map [:what :string]]                  {:initial true})
   (sg/state :doing [:map [:what :string] [:who :string]])
   (sg/state :done  [:map [:what :string] [:who :string]]    {:final true})

   (sg/event :start  [:map [:who :string]] (fn [e] {:who (:who e)}) [:map [:who :string]])
   (sg/event :finish [:map]                (constantly {})          [:map])

   (sg/transition :todo  :start  :doing)
   (sg/transition :doing :finish :done)))

;; `sg/shape` will not build a broken machine. Everything answerable from the parts alone —
;; a transition naming a state nobody defined, two transitions leaving one state on one
;; event, an event no transition fires, a state redeclaring `:id` — throws, with the faults
;; in `ex-data`. The layer below answers the same question without throwing, which is handy
;; when you are generating shapes:

(shape/problems (sg/state :a [:map] {:initial true})
                (sg/event :go [:map] (constantly {}))
                (sg/transition :a :go :nowhere))

;; Here is the machine itself. The drawing marks the initial state `▸`, gives a final state
;; a double circle, and labels every node with its schema. One helper, used throughout:

(defn picture
  "The shape as a diagram on this page.

   `sg/dot` is the drawing as DATA — the graphviz source, as a string, needing nothing
   installed — where `sg/draw!` is the drawing as an effect, opening a viewer or writing a
   file. Clay hands the source to viz.js, which draws it in the browser."
  [sh]
  (kind/graphviz [(sg/dot sh)]))

(picture task)

;; ## Running it: the reduction
;;
;; A shape compiles to an ordinary function of a state and an event. That is the whole
;; runtime — no object, no atom, no protocol.

(def step (sg/compile task))

(sg/initial task {:what "write the tutorial"})

;; The lifecycle of an instance is a reduction over a seq of events:

(reduce step
        (sg/initial task {:what "write the tutorial"})
        [{:id :start :who "ada"} {:id :finish}])

;; `reductions` shows the machine walking, which is the same thing said with more detail:

(reductions step
            (sg/initial task {:what "write the tutorial"})
            [{:id :start :who "ada"} {:id :finish}])

;; A step goes anywhere an accumulator does — a fold, a transducer, `core.async`, a test.
;; Nothing in the core knows what a stream is.

;; ## An event the state cannot handle
;;
;; It is **not an error**. Nothing controls the order events arrive in behind a stream, so a
;; `:finish` landing before a `:start` is ordinary traffic and not a defect. The handler
;; never runs, and the state comes back unchanged:

(step (sg/initial task {:what "write the tutorial"}) {:id :finish})

;; Which raises the question the stream door answers: how would a caller *know*? The state
;; is untouched, so nothing in it says anything happened. Hold that thought.

;; ## What is a defect
;;
;; A crossing that does not hold. An event that is not what its schema says, a handler
;; answering something its own declaration denies, or a state its target's schema will not
;; admit — those are bugs, not facts about the run, and they throw with the problem as data.
;;
;; The library itself never catches; this page does, in order to print one. That is what
;; *errors are data* buys — a map you can read, match on and log, rather than prose:

(-> (try (step (sg/initial task {:what "write the tutorial"})
               {:id :start :who 42})
         (catch clojure.lang.ExceptionInfo e (ex-data e)))
    (select-keys [:crossing :from :event :to :errors]))

;; ## What the graph buys
;;
;; This is the part no other FSM library has. Everything that needs the *built graph* is
;; opt-in, because a half-finished machine is worth looking at — a shape you cannot build is
;; a shape you cannot draw.

(sg/problems task)

;; Now a machine with three *kinds* of fault, every one of them structural — it passes
;; construction and is quietly wrong.

(def leaky
  (sg/shape
   (sg/state :draft     [:map]               {:initial true})
   (sg/state :review    [:map [:by :string]])
   (sg/state :limbo     [:map [:by :string]])
   (sg/state :retrying  [:map [:by :string]])
   (sg/state :published [:map [:by :string]] {:final true})
   (sg/state :archived  [:map [:by :string]] {:final true})

   (sg/event :submit  [:map] (constantly {}) [:map])
   (sg/event :publish [:map] (constantly {}))
   (sg/event :oops    [:map] (constantly {}))
   (sg/event :retry   [:map] (constantly {}))
   (sg/event :back    [:map] (constantly {}))

   (sg/transition :draft    :submit  :review)
   (sg/transition :review   :publish :published)
   (sg/transition :review   :oops    :limbo)
   (sg/transition :limbo    :retry   :retrying)
   (sg/transition :retrying :back    :limbo)))

(sg/problems leaky)

;; - `:unreachable` — a traversal from the initial state never arrives. Note that this is
;;   *not* `has no in-edge`: two states that reach only each other both have in-edges and
;;   are both unreachable.
;; - `:trap` — reachable, has somewhere to go, and can never reach an ending. `:limbo` and
;;   `:retrying` bounce off each other for ever. A dead end is a trap of size one; this is
;;   the smallest interesting one, and the reason a `no out-edge` check is not enough.
;; - `:target-refuses` — `:submit` declares that it answers `[:map]`, so nothing ever sets
;;   `:by`, and `:review` requires it. **Found without running anything.** The checker is
;;   partial on purpose: it answers yes, no, or *don't know*, reports only what it can
;;   prove, and never lies.
;;
;; And the same faults by the means a person is better at. The pocket that `:oops` leads
;; into has no arrow reaching a double circle, and `:archived` floats free:

(picture leaky)

;; ## Running it: the stream
;;
;; The other door. `sg/run` takes a source of events and answers a source of results and a
;; deferred. **The caller owns the lifecycle either way** — this is the same reduction with
;; the loop shipped, not a different kind of machine.

(defn fed
  "A source carrying exactly these events, then closed."
  [events]
  (let [in (s/stream (max 1 (count events)))]
    (s/put-all! in events)
    (s/close! in)
    in))

(def ran
  (sg/run task {:what "write the tutorial"}
          (fed [{:id :start :who "ada"} {:id :finish} {:id :finish}])))

(deref (s/reduce conj [] (:states ran)) 1000 ::timeout)

;; `:states` carries one **transition result** per event: what the machine was told, the
;; state it produced, and whether it `:fired` at all. That last field is the answer to the
;; question held above — the second `:finish` had nowhere to go, and the result says so
;; where the state could not. It is the one fact a consumer cannot recover for itself: an
;; ignored event answers the state unchanged, and a self-loop whose handler answers `{}`
;; answers a state identical to the old one.
;;
;; This library **stores nothing**. The results *are* the history, and writing them down is
;; yours — which is why they carry the event and not just the state.

(deref (:done ran) 1000 ::timeout)

;; `:done` is a deferred `{instance -> final state}`. Nobody named a machine here, so the
;; one machine is under `nil`.
;;
;; A defect belongs on `:done` and never on a stream of things that happened. `d/catch` is
;; manifold's combinator, not a bare try/catch:

(-> (sg/run task {:what "x"} (fed [{:id :start :who 42}]))
    :done
    (d/catch ex-data)
    (deref 1000 ::timeout)
    :crossing)

;; ## A workflow worth drawing
;;
;; Nine states, nine events, twelve transitions: a manuscript through review and publishing.
;; Look at what the shape says before reading the code — the review cycle, the retry loop on
;; `:publishing`, and one event (`:withdraw`) leaving three different states.

(def pipeline
  (sg/shape
   (sg/state :draft             [:map]                                     {:initial true})
   (sg/state :submitted         [:map [:title :string] [:author :string]])
   (sg/state :in-review         [:map [:title :string] [:author :string] [:reviewer :string]])
   (sg/state :changes-requested [:map [:title :string] [:author :string]
                                 [:reviewer :string] [:notes :string]])
   (sg/state :approved          [:map [:title :string] [:author :string] [:reviewer :string]])
   (sg/state :publishing        [:map [:title :string] [:author :string] [:attempt :int]])
   (sg/state :published         [:map [:title :string] [:author :string] [:url :string]]
             {:final true})
   (sg/state :rejected          [:map [:title :string] [:reason :string]]   {:final true})
   (sg/state :withdrawn         [:map [:title :string]]                     {:final true})

   (sg/event :submit          [:map [:title :string] [:author :string]]
             (fn [e] (select-keys e [:title :author]))
             [:map [:title :string] [:author :string]])
   (sg/event :assign          [:map [:reviewer :string]] (fn [e] {:reviewer (:reviewer e)})
             [:map [:reviewer :string]])
   (sg/event :request-changes [:map [:notes :string]]    (fn [e] {:notes (:notes e)})
             [:map [:notes :string]])
   (sg/event :revise          [:map [:title :string]]    (fn [e] {:title (:title e)})
             [:map [:title :string]])
   (sg/event :approve         [:map]                     (constantly {})
             [:map])
   (sg/event :reject          [:map [:reason :string]]   (fn [e] {:reason (:reason e)})
             [:map [:reason :string]])
   (sg/event :publish         [:map [:attempt :int]]     (fn [e] {:attempt (:attempt e)})
             [:map [:attempt :int]])
   (sg/event :confirm         [:map [:url :string]]      (fn [e] {:url (:url e)})
             [:map [:url :string]])
   (sg/event :withdraw        [:map]                     (constantly {})
             [:map])

   (sg/transition :draft             :submit          :submitted)
   (sg/transition :submitted         :assign          :in-review)
   (sg/transition :in-review         :request-changes :changes-requested)
   (sg/transition :changes-requested :revise          :in-review)
   (sg/transition :in-review         :approve         :approved)
   (sg/transition :in-review         :reject          :rejected)
   (sg/transition :approved          :publish         :publishing)
   (sg/transition :publishing        :publish         :publishing)
   (sg/transition :publishing        :confirm         :published)
   (sg/transition :submitted         :withdraw        :withdrawn)
   (sg/transition :in-review         :withdraw        :withdrawn)
   (sg/transition :changes-requested :withdraw        :withdrawn)))

(picture pipeline)

;; Nothing structurally wrong with it — every state reachable, every state able to finish,
;; and every handler's answer admitted by the state it lands in:

(sg/problems pipeline)

;; Three things in that shape are worth naming.
;;
;; **`:publish` leaves two different states**, `:approved` and `:publishing`, with one
;; handler and one declaration. The handler is the *event's*; where the machine lands is the
;; *graph's*. The self-loop is a retry.
;;
;; **The retry cannot count itself.** A handler never sees the state it is changing, so
;; `:attempt` cannot be incremented — the event carries the number, and whoever produces the
;; event knows which attempt it is. This is the sharpest constraint in the library and the
;; one to design around.
;;
;; **`:withdraw` leaves three states.** One event, one handler, three edges — and the
;; subsumption check asks separately, for each of them, whether `:withdrawn` admits what
;; comes out.
;;
;; Now a real event log for two manuscripts at once. `:instance` is what routes an event to
;; its machine, and it is read off the **event**, because routing happens before any state
;; is in hand.

(def log
  [{:instance "m-1" :id :submit :title "On computable numbers" :author "ada"}
   {:instance "m-2" :id :submit :title "A note on parsers" :author "linus"}
   {:instance "m-1" :id :assign :reviewer "grace"}
   {:instance "m-1" :id :request-changes :notes "tighten section 3"}
   {:instance "m-1" :id :confirm :url "https://journal.example/early"}
   {:instance "m-2" :id :withdraw}
   {:instance "m-1" :id :revise :title "On computable numbers (v2)"}
   {:instance "m-1" :id :approve}
   {:instance "m-1" :id :publish :attempt 1}
   {:instance "m-1" :id :publish :attempt 2}
   {:instance "m-1" :id :confirm :url "https://journal.example/17"}])

(def published
  (sg/run pipeline {} (fed log)))

(def history
  (deref (s/reduce conj [] (:states published)) 5000 ::timeout))

;; The history a caller would store — one row per event, whatever became of it:

(kind/table
 {:column-names [:instance :event :landed-in :fired]
  :row-vectors (for [r history]
                 [(:instance r) (-> r :event :id) (-> r :state :id) (:fired r)])})

;; Read that table for the three things it shows.
;;
;; **The `:confirm` that arrived early did not fire.** It is row five, the machine sat in
;; `:changes-requested`, and nothing happened — no error, no handler, no state change, and a
;; row in the history saying so. Without `:fired` that row is indistinguishable from a
;; transition that worked.
;;
;; **The two machines interleave.** `m-2` finished while `m-1` was still in review, and the
;; order of these rows is *not* promised: parallelism is across instances. What is promised
;; is that each machine's own events are applied strictly in order — one partition is one
;; machine, serialised.

(kind/table
 {:column-names [:instance :path]
  :row-vectors (for [[inst rs] (group-by :instance history)]
                 [inst (->> rs (map (comp :id :state)) (cons :draft) (interpose "→")
                            (apply str))])})

;; **And each machine ends somewhere the shape allows**, carrying everything it gathered:

(deref (:done published) 5000 ::timeout)

;; And note what the published manuscript does **not** carry: no `:reviewer`, no `:notes`
;; from a review round three transitions back, no `:attempt` from the retry. A node holds
;; exactly what its schema declares and the rest is dropped on entry — so `:published`, which
;; declares a title, an author and a url, holds a title, an author and a url. That is the
;; subject of the next section.

;; ## Nesting: a machine in a node
;;
;; The pipeline above is nine states in one graph, and that is roughly where one graph stops
;; being readable. The answer is not a bigger graph: a state can carry **a whole machine of
;; its own**, and then each machine stays the size a person can hold.
;;
;; Here is a payment, on its own, as an ordinary machine:

(def payment
  (sg/shape
   (sg/state :unpaid     [:map]                 {:initial true})
   (sg/state :authorized [:map [:auth :string]])
   (sg/state :captured   [:map [:auth :string]] {:final true})

   (sg/event :authorize [:map [:auth :string]] (fn [e] {:auth (:auth e)}) [:map [:auth :string]])
   (sg/event :capture   [:map]                 (constantly {})           [:map])

   (sg/transition :unpaid     :authorize :authorized)
   (sg/transition :authorized :capture    :captured)))

(picture payment)

;; And an order that *contains* it. One option on one state is the whole of the syntax:

(def order
  (sg/shape
   (sg/state :cart      [:map] {:initial true})
   (sg/state :paying    [:map] {:machine payment})
   (sg/state :shipped   [:map] {:final true})
   (sg/state :cancelled [:map] {:final true})

   (sg/event :checkout [:map] (constantly {}) [:map])
   (sg/event :ship     [:map] (constantly {}) [:map])
   (sg/event :cancel   [:map] (constantly {}) [:map])

   (sg/transition :cart   :checkout :paying)
   (sg/transition :paying :ship     :shipped)
   (sg/transition :paying :cancel   :cancelled)))

;; `:paying ⊞ 3 states` is the marker for a node that nests one. The child is not drawn
;; inside its parent — graphviz clusters are not reachable through ubergraph — so a nested
;; machine is two pictures, and the parent's says where to look:

(picture order)

;; Now watch one order run. The parent's `:id` and the child's sit side by side:

(def order-step (sg/compile order))

(kind/table
 {:column-names [:event :order :payment]
  :row-vectors (let [events [{:id :checkout}
                             {:id :authorize :auth "tok_9"}
                             {:id :capture}
                             {:id :ship}]]
                 (map (fn [e s] [(:id e) (:id s) (-> s :sub :id)])
                      (cons nil events)
                      (reductions order-step (sg/initial order {}) events)))})

;; **Inner first.** A nested machine gets every event before the node's own edges do — so
;; `:authorize` and `:capture` moved the payment while the order stood still in `:paying`,
;; and only `:ship` moved the order.
;;
;; Which means **the child's own vocabulary decides who handles an event**. `:authorize` is
;; the payment's word, so the order never sees it. `:cancel` is not, so it escapes at once:

(mapv (juxt :id (comp :id :sub))
      (reductions order-step (sg/initial order {}) [{:id :checkout} {:id :cancel}]))

;; **A finished child stops competing**, and this is the part that makes nesting cost the
;; design nothing at all. A final state admits nothing — you saw that at the top of this page
;; — so once the payment is `:captured` every later event falls straight through to the
;; order. No guards, no done-event, no queue, no run-to-completion:

(let [captured (reduce order-step (sg/initial order {})
                       [{:id :checkout} {:id :authorize :auth "tok_9"} {:id :capture}])]
  {:child-is-done (:sub captured)
   :and-ignores-its-own-events (= captured (order-step captured {:id :authorize :auth "again"}))
   :so-the-parent-gets-the-next-one (:id (order-step captured {:id :ship}))})

;; `:sub` belongs to the machinery, exactly as `:id` and `:instance` do: it is seeded when the
;; node is entered, dropped on the way out, restarted if the node is re-entered, and a handler
;; that answers `{:sub ...}` is **refused** — an event is the only way a machine transitions,
;; so naming `:sub` is asking for a move nobody granted. And a child is an ordinary shape, so the
;; static checks recurse into it and report its faults under the node that hosts it:

(sg/problems
 (sg/shape (sg/state :p [:map] {:initial true :machine (sg/shape
                                                       (sg/state :i [:map] {:initial true})
                                                       (sg/state :a [:map])
                                                       (sg/state :b [:map])
                                                       (sg/event :x [:map] (constantly {}) [:map])
                                                       (sg/event :y [:map] (constantly {}) [:map])
                                                       (sg/transition :i :x :a)
                                                       (sg/transition :a :y :b)
                                                       (sg/transition :b :x :a))})
           (sg/state :q [:map] {:final true})
           (sg/event :e [:map] (constantly {}) [:map])
           (sg/transition :p :e :q)))

;; A child that can never finish is a fault of the child, reported `:within [:p]` — a path,
;; because nesting nests.
;;
;; ### The limit, said plainly
;;
;; **The escape is unconditional.** Nothing stops `:ship` firing while the payment is half
;; done, because "only when the child has finished" is a fact about the *state*, and a guard
;; — the next section — reads the *event*:

(mapv (juxt :id (comp :id :sub))
      (reductions order-step (sg/initial order {}) [{:id :checkout} {:id :ship}]))

;; So deciding *when* is the producer's job, and the child's state is on every result, so a
;; producer can see exactly what it needs to decide. A door left open, not designed: a node
;; could declare where to go when its child finishes, which is the statechart
;; done-transition and needs no event queue here.

;; ## Branching: a guard is a schema
;;
;; Every machine branches. The question worth asking is **where the deciding lives** — and
;; there are only two answers. Put it in a handler, or in whatever code feeds the machine its
;; events, and the branch is a `cond` somewhere else: the drawing cannot show it, `problems`
;; cannot check it, and a reader has to go and find it. Put it **on the edge, as data**, and
;; both can.

(def judged
  (sg/event :judged [:map [:verdict [:enum :green :red]]
                          [:fault {:optional true} [:string {:min 1}]]]
            (fn [e] (select-keys e [:fault]))
            [:map [:fault {:optional true} [:string {:min 1}]]]))

;; That handler is deliberately *not* a pure lift. `:verdict` is **routing information** — it
;; tells the edge where to go, and no state holds it — so a lift, which answers every key the
;; schema declares, would answer a key the target does not admit and the patch check would
;; refuse it. A guarded event usually spells its handler out for exactly that reason.

(def review
  (sg/shape
   (sg/state :written     [:map [:code :string]] {:initial true})
   (sg/state :implemented [:map [:code :string]] {:final true})
   (sg/state :faulted     [:map [:code :string] [:fault {:optional true} [:string {:min 1}]]])
   judged
   (sg/event :again [:map] (constantly {}) [:map])
   (sg/transition :written :judged :implemented {:when [:map [:verdict [:= :green]]]})
   (sg/transition :written :judged :faulted     {:when [:map [:verdict [:= :red]]]})
   (sg/transition :faulted :again :written)))

(picture review)

;; The branch is in the picture, in Harel's own notation — `judged [verdict=:green]`. One
;; event, two arrows, and what separates them is written down:

(let [step (sg/compile review)
      s0   (sg/initial review {:code "(defn answer [] 42)"})]
  [(step s0 {:id :judged :verdict :green})
   (step s0 {:id :judged :verdict :red :fault "it threw"})])

;; `:when` is to a transition what `:sees` is to an event: an optional map schema, declared
;; where the thing it constrains lives. It describes the event's **payload** — what the event
;; carries, without `:id` and `:instance`, exactly as a state's schema describes the state
;; without them.
;;
;; ### Two edges must be PROVABLY exclusive
;;
;; This is the one check that demands proven *safety* rather than reporting a proven fault,
;; because determinism is the contract. Guards that might both hold are refused, and there is
;; no declaration order to fall back on — a graph's out-edges are a set, not a list:

(shape/problems (sg/state :a [:map] {:initial true})
                (sg/state :b [:map])
                (sg/state :c [:map] {:final true})
                (sg/event :go [:map [:v [:enum :x :y :z]]] (constantly {}) [:map])
                (sg/transition :a :go :b {:when [:map [:v [:enum :x :y]]]})
                (sg/transition :a :go :c {:when [:map [:v [:enum :y :z]]]}))

;; Asked of the parts, because `sg/shape` would REFUSE to build that one — determinism is
;; referential, so a machine nobody can predict never gets to exist. `:y` could fire either. Change one to `[:= :x]` and
;; it is provable and allowed. What can be proved: a finite domain (`[:= v]`, `[:enum …]`),
;; disjoint types, a closed map with no room for a key the other side insists on, and numeric
;; ranges that do not meet. Anything else answers `:unknown` — and `:unknown` is refused,
;; because a guard the library cannot separate is a machine nobody can predict.
;;
;; ### There is no `:else`
;;
;; An event no guard admits fires no edge, which is the `ignored` you have already seen. So a
;; single guard is a **filter** as much as a branch, and the reduction stays total:

(let [step (sg/compile review)
      faulted (step (sg/initial review {:code "x"}) {:id :judged :verdict :red :fault "boom"})]
  [(= faulted (step faulted {:id :judged :verdict :red})) ; :faulted has no :judged edge
   (:id (step faulted {:id :again}))])

;; A **malformed** event is still a defect, though, and still throws — a guard refines a
;; schema the event must already satisfy, so a verdict that is not in the enum is not a miss:

(-> (try ((sg/compile review) (sg/initial review {:code "x"}) {:id :judged :verdict :amber})
         (catch clojure.lang.ExceptionInfo e (ex-data e)))
    (select-keys [:crossing :event :errors]))

;; ## Reading the state, and what a state holds
;;
;; Two halves of one question, and it is the question that decides whether a machine is safe to
;; hand a workflow: **what may something inside the machine see?**
;;
;; The first half is what a state *holds*. A node holds exactly what it declares — the merge is
;; projected onto its schema's keys on the way in — so data stops flowing through states that
;; never mentioned it:

(def flow
  (sg/shape
   (sg/state :one [:map [:x :int]] {:initial true})
   (sg/state :two [:map [:y :int]] {:final true})
   (sg/event :next [:map] (constantly {:y 1}) [:map [:y :int]])
   (sg/transition :one :next :two)))

[(sg/initial flow {:x 7 :undeclared "dropped at the door"})
 (reduce (sg/compile flow) (sg/initial flow {:x 7}) [{:id :next}])]

;; `:x` came that far and no further, because `:two` never mentioned it. Dropping a field is
;; free — declare one fewer — and carrying one across several states is explicit, which is the
;; cost side of the same coin.
;;
;; The second half is what a handler may *read*. It takes the event alone, which is what keeps
;; it reusable; when it genuinely needs something from the state it is changing, **the event
;; declares what it may see**:

(def briefing
  (sg/shape
   (sg/state :briefed  [:map [:goal :string] [:token :string]] {:initial true})
   (sg/state :answered [:map [:goal :string] [:token :string] [:answer :string]] {:final true})

   (sg/event :ask [:map [:q :string]]
             (fn [event seen] {:answer (str "asked " (pr-str seen) " about " (:q event))})
             [:map [:answer :string]]
             {:sees [:map [:goal :string]]})

   (sg/transition :briefed :ask :answered)))

(reduce (sg/compile briefing)
        (sg/initial briefing {:goal "ship it" :token "s3cret"})
        [{:id :ask :q "how"}])

;; **The token never reached the handler.** It is right there in the state, and the handler was
;; handed `{:goal "ship it"}` — the state projected onto the view and validated against it.
;; Visibility from the inside is declared, never automatic, and narrowed to the keys named.
;;
;; Declared on the *event* rather than the node, which is what keeps the handler reusable: it
;; names what it needs **by shape**, so it works in any state that satisfies the view. And it is
;; provable — a handler asking to read what a state cannot guarantee is a fault found before
;; anything runs, including the case where the key is merely optional there:

(sg/problems
 (sg/shape (sg/state :a [:map [:goal {:optional true} :string]] {:initial true})
           (sg/state :b [:map] {:final true})
           (sg/event :go [:map] (fn [_ _seen] {}) [:map] {:sees [:map [:goal :string]]})
           (sg/transition :a :go :b)))

;; A view cannot rest on a maybe. And that check is sound only *because* a node holds what it
;; declares: while a schema was a lower bound on the state, a key could arrive from three
;; transitions back and `:no` would have proven nothing. The two halves hold each other up.
;;
;; ### Accumulating, with a policy
;;
;; A view is also how a machine accumulates something — and the policy stays ordinary code,
;; which a `conj` on the schema could never have expressed. Read the old value, answer the new
;; one, and keep the last two:

(def convo
  (sg/shape
   (sg/state :talking [:map [:messages [:vector :string]]] {:initial true})
   (sg/state :hung-up [:map [:messages [:vector :string]]] {:final true})

   (sg/event :say [:map [:text :string]]
             (fn [event seen]
               {:messages (vec (take-last 2 (conj (:messages seen) (:text event))))})
             [:map [:messages [:vector :string]]]
             {:sees [:map [:messages [:vector :string]]]})
   (sg/event :bye [:map] (constantly {}) [:map])

   (sg/transition :talking :say :talking)
   (sg/transition :talking :bye :hung-up)))

(reduce (sg/compile convo) (sg/initial convo {:messages []})
        (for [t ["one" "two" "three" "four"]] {:id :say :text t}))

;; Cap it, summarise it, drop the oldest tool output, keep everything — the handler decides,
;; because it is a function. What the *shape* decides is who may read what, and which nodes
;; carry it at all.
;;
;; One consequence worth knowing if you ever take the concurrency licence: a handler that reads
;; can have a **stale patch**. `check/confluence` accounts for it — two pending events commute
;; only if neither writes what the other writes and neither reads what the other writes — so a
;; pair like this one is never licensed to run in completion order.

;; ## Beneath the facade
;;
;; `robertluo.state-graph` is the only namespace an application needs, but it is a
;; convenience over four that are usable directly — `.shape`, `.compile`, `.check` and
;; `.async`. Drop through when you want something the facade does not offer.
;;
;; `check/commuting`, for instance, asks which pairs of events pending in one state could
;; safely be applied in whichever order finished first. For this pipeline, as for every
;; shape measured so far, the answer is none — divergence is what a state machine is *for*:

(require '[robertluo.state-graph.check :as check])

(check/commuting pipeline)

;; `check/coverage` is the other one worth knowing, and it lives down here rather than in
;; `problems` for a reason: it says whether the guards on a `[state, event]` leave a gap, and
;; **a gap is not a fault** — it is exactly what a filter is for. So it is published rather
;; than complained about. The review machine has no gap; `:green` and `:red` are the whole of
;; the enum, and that is provable:

(check/coverage review)

;; Take one of those two edges away and the verdict becomes
;; `{:verdict :no :witness {:verdict :red}}` — not an error, but the value that proves an
;; event can reach nothing from there, which is worth knowing either way.

;; So `sg/run` serialises within a machine, always, and says so rather than pretending.
;;
;; The other reason to drop through is starting data: `sg/run` gives every machine the same
;; initial data, and `async/fan` takes a function of the instance instead. This pipeline
;; sidesteps it by having `:draft` carry nothing and letting `:submit` bring the title.

;; ## Rendering this notebook
;;
;; ```
;; clojure -X:notebook          # writes docs/tutorial.html
;; ```
;;
;; Or, from a REPL started with the `:notebook` alias, for a live page that reloads as you
;; edit:

(comment
  (require '[scicloj.clay.v2.api :as clay])
  (clay/make! {:source-path "notebook/tutorial.clj"})
  (clay/browse!))
