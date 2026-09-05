;; # robertluo.state-graph — a tutorial
;;
;; A finite state machine whose **shape is a graph**. This notebook works through the whole
;; facade — twelve functions and three doors — and ends with a workflow big enough to be
;; worth drawing.
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
;; deferred. **The caller owns the lifecycle in all three** — this is the same reduction with
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

;; ## Running it: the crank
;;
;; The third door, and the only one where **the events are found rather than fed**. Both doors
;; above take their events from somewhere else — a vector you already have, a stream somebody
;; else fills. An event can instead declare how it is *found*:

(def build
  (sg/shape
   (sg/state :queued  [:map [:commit :string]]                                     {:initial true})
   (sg/state :built   [:map [:commit :string] [:artefact :string]])
   (sg/state :tested  [:map [:commit :string] [:artefact :string] [:passed :boolean]])
   (sg/state :shipped [:map [:commit :string] [:artefact :string] [:passed :boolean]]
             {:final true})

   ;; `:report` goes and finds the fact; `:reads` is the view of the state it needs in order
   ;; to do so, projected and validated exactly as `:sees` is. In a real machine these reach
   ;; a compiler, a test runner, a model — anything that answers a question about the world.
   (sg/event :compile [:map [:artefact :string]]
             {:reads  [:map [:commit :string]]
              :report (fn [seen] {:artefact (str (:commit seen) ".jar")})})
   (sg/event :test [:map [:passed :boolean]]
             {:reads  [:map [:artefact :string]]
              :report (fn [seen] {:passed (boolean (seq (:artefact seen)))})})

   ;; AND AN EVENT WITH NO `:report` COMES FROM THE WORLD. That is a park, in data: nobody
   ;; but a person can say this one happened.
   (sg/event :ship [:map] (constantly {}) [:map])

   (sg/transition :queued :compile :built)
   (sg/transition :built  :test    :tested)
   (sg/transition :tested :ship    :shipped)))

(picture build)

;; `sg/drive` turns the crank until the machine stops moving. Everything beyond the shape is
;; an option, and `:data` is what the first state carries:

(def opts {:data {:commit "9f89a78"}})

(def parked (sg/drive build [] opts))

;; **A run is the vector of events**, so that vector is the whole result — nothing was stored
;; and nothing mutated. Where it got to is a reduction over it:

(require '[robertluo.state-graph.drive :as drive]
         '[robertluo.state-graph.check :as check])

(drive/where build parked opts)

;; It stopped at `:tested` because `:ship` has no `:report`. `drive/awaiting` is the whole
;; driving rule as one value, and it answers four ways. Here are three of them:

[(drive/awaiting build [] opts)
 (drive/awaiting build parked opts)
 (drive/awaiting build (drive/advance build parked {:id :ship} opts) opts)]

;; `:from :driver` — go and find it out. `:from :world` — a **park**, and a legitimate one:
;; somebody outside says what happened, and `drive/advance` is the door they come in by.
;; `:final` — over.
;;
;; The fourth is what the *caller* said not to do this turn:

(drive/awaiting build [] (assoc opts :permitted #{}))

[(sg/step build [] (assoc opts :permitted #{}))
 (sg/step build [] (assoc opts :permitted #{:compile}))]

;; `:permitted` is one turn's permission — a set of event ids, or any predicate over one — so
;; supervising a run is an argument and not a state. That matters more than it looks: `:held`
;; is nowhere in the graph, so a workflow watched and a workflow left alone are the **same
;; machine**, with the same `shape/fingerprint`. Gate states would have made them different.
;;
;; `:on` is told each applied event. It is where a caller writes a history, and the only
;; reason this door has to know that histories exist:

(let [rows (atom [])]
  (sg/drive build [] (assoc opts :on #(swap! rows conj ((juxt (comp :id :event) :from :to) %))))
  @rows)

;; ### Two reportable events out of one state
;;
;; A driver that picked one would be inventing an order the shape never promised, so it will
;; not. This one is a genuine question for the world:

(def offer
  (sg/shape (sg/state :asked    [:map] {:initial true})
            (sg/state :accepted [:map] {:final true})
            (sg/state :refused  [:map] {:final true})
            (sg/event :accept [:map] {:reads [:map] :report (constantly {})})
            (sg/event :refuse [:map] {:reads [:map] :report (constantly {})})
            (sg/transition :asked :accept :accepted)
            (sg/transition :asked :refuse :refused)))

[(drive/awaiting offer []) (sg/drive offer [])]

;; **Unless the shape has proved the order cannot be observed** — which is exactly what the
;; product construction of *A join* below gives you. Two independent branches from one state:

(def gather
  (sg/shape (sg/state :asked [:map]                                 {:initial true})
            (sg/state :coded [:map [:code :string]])
            (sg/state :lawed [:map [:law :string]])
            (sg/state :ready [:map [:code :string] [:law :string]]  {:final true})
            (sg/event :write [:map [:code :string]]
                      {:reads [:map] :report (constantly {:code "(defn answer [] 1)"})})
            (sg/event :draft [:map [:law :string]]
                      {:reads [:map] :report (constantly {:law "(prop/for-all …)"})})
            (sg/transition :asked :write :coded)
            (sg/transition :asked :draft :lawed)
            (sg/transition :coded :draft :ready)
            (sg/transition :lawed :write :ready)))

(drive/awaiting gather [])

;; `:events` rather than `:event` — both are found in **one turn** and applied in an order the
;; shape has already said makes no difference:

[(mapv :id (sg/drive gather [])) (drive/where gather (sg/drive gather []))]

;; The reports go through `:reports`, which defaults to running them in order. Hand it one
;; that runs them at once — `d/zip` over `d/future`, say — and a proven join costs the slower
;; of the two rather than the sum. This layer depends on no stream library either way.
;;
;; ### And the graph can be asked the same question
;;
;; `check/driving` is the static half of `awaiting`: one verdict per state, before anything
;; runs. `:driver`, `:world`, `:join`, `:final` — and `:fork`, which is the one to look for:

(kind/table
 {:column-names [:state :awaits :reports :verdict]
  :row-vectors (for [{:keys [id awaits reports verdict]} (concat (check/driving build)
                                                                 (check/driving offer))]
                 [id (sort awaits) (sort reports) verdict])})

;; A `:fork` is a state that will park for ever if you meant it to be automatic, and
;; `sg/problems` calls such a shape perfectly fine. It is **published and never faulted**,
;; because a shape may well want the world to choose; what would be wrong is a driver choosing
;; for it.

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
;; machine, in order — apart from a pair proven not to care, which is the join section below.

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
;; **Every published check does this, and not only `problems`.** `subsumption`, `views`,
;; `readings`, `yields`, `coverage`, `confluence`, `laws` and `driving` all recurse and carry
;; `:within`. The checks that answer a *set of ids* — `reachable`, `traps`, `dead-ends`,
;; `finishable` — are about one graph and stay there: two machines may name a state the same,
;; and a set has nowhere to say which one it meant.
;;
;; **And the crank reports into the machine that is actually running.** A nesting node has no
;; edge for its child's events, so a driver reading only the host's out-edges would see a node
;; that awaits nothing and is not final, and park for ever on a machine that was ready to go.
;; It follows `:sub` as deep as it goes and asks the innermost machine first — which is *inner
;; first* again, on the half that produces events rather than the half that applies them:

(def paying
  (sg/shape
   (sg/state :taking [:map] {:initial true
                             :machine (sg/shape
                                       (sg/state :unpaid [:map] {:initial true})
                                       (sg/state :paid   [:map [:auth :string]] {:final true})
                                       (sg/event :authorize [:map [:auth :string]]
                                                 {:reads  [:map]
                                                  :report (constantly {:auth "tok_9"})})
                                       (sg/transition :unpaid :authorize :paid))
                             :done :taken
                             :yield [:map [:auth :string]]})
   (sg/state :taken [:map [:auth :string]] {:final true})))

[(drive/awaiting paying [])
 (sg/drive paying [])
 (drive/where paying (sg/drive paying []))]
;;
;; ### Sowing the child: `:seed`
;;
;; A child starts with **nothing at all** unless the node says otherwise, and for a while that
;; was the whole story: whatever the child was doing had to come from the closure its shape was
;; built from, which is a constant. `{:seed <a map schema>}` is the other half — projected off
;; the node's own value on the way **in**, exactly as `:yield` is projected off the child's
;; final state on the way **out**. One boundary, and now both directions across it.

(def attempt
  (sg/shape
   (sg/state :calling [:map [:endpoint :string]] {:initial true})
   (sg/state :replied [:map [:endpoint :string]] {:final true})

   (sg/event :reply [:map])
   (sg/transition :calling :reply :replied)))

(def fetch
  (sg/shape
   (sg/state :trying   [:map [:endpoint :string]] {:initial true
                                                   :machine attempt
                                                   :seed  [:map [:endpoint :string]]
                                                   :done  :choosing})
   (sg/state :choosing [:map [:endpoint :string]])
   (sg/state :over     [:map [:endpoint :string]] {:final true})

   (sg/event :again [:map [:endpoint :string]])
   (sg/event :stop  [:map])

   (sg/transition :choosing :again :trying)
   (sg/transition :choosing :stop  :over)))

;; **What it buys is one node re-entered with a different job.** `:trying` is entered twice
;; below and the child runs twice, on a different endpoint each time — and which endpoint is a
;; fact about the *run*, which is the thing a closure could never have been:

(def fetch-step (sg/compile fetch))

(kind/table
 {:column-names [:event :fetch :endpoint :attempt :sown]
  :row-vectors (let [events [{:id :reply} {:id :again :endpoint "backup"} {:id :reply}]]
                 (map (fn [e s] [(:id e) (:id s) (:endpoint s)
                                 (-> s :sub :id) (-> s :sub :endpoint)])
                      (cons nil events)
                      (reductions fetch-step (sg/initial fetch {:endpoint "primary"}) events)))})

;; Take the seed away and that machine **cannot be built at all**. `:calling` insists on an
;; `:endpoint` and a node that sows nothing could never give it one, so the child could never
;; begin — which the parts alone can say, so it is referential and the constructor refuses it:

(shape/problems
 (shape/state :trying   [:map [:endpoint :string]] {:initial true :machine attempt
                                                    :done :choosing})
 (shape/state :choosing [:map [:endpoint :string]] {:final true}))

;; A seed is a crossing with **two sides, and either can be wrong**, so `check/seeds` answers
;; twice for every seeded node — can this node *provide* it, and will the child *take* it:

(check/seeds fetch)

;; Which is `yields` read backwards, and deliberately shaped like it: one carries parent to
;; child at entry and the other child to parent at completion, so the two checks are mirror
;; images and neither needed machinery the other did not.
;;
;; ### An escape is an ABORT
;;
;; Nothing stops `:ship` firing while the payment is half done, and the child's work is
;; simply gone when it does:

(mapv (juxt :id (comp :id :sub))
      (reductions order-step (sg/initial order {}) [{:id :checkout} {:id :ship}]))

;; That is right for `:cancel` and wrong for `:ship`. Aborting is the commoner need, so it
;; stays what an event does — and for the parent to **wait** instead, the node says where to
;; go when it *completes*.

;; ## Completing: `:done` and `:yield`
;;
;; A state can say where it goes when it is **finished** — no event, no handler, no patch.
;; **One rule, and it is UML's: a state completes when it has nothing left to do.** A state
;; with no machine has no activity to finish, so *finishing it is arriving*. A state with one
;; completes when that child reaches a final state.
;;
;; Here is the same order with `:ship` **gone**. That event was the producer telling the
;; machine that payment had finished, which is a thing the shape can now work out for itself:

(def waiting-order
  (sg/shape
   (sg/state :cart      [:map [:total :int]] {:initial true})
   (sg/state :paying    [:map [:total :int]] {:machine payment
                                              :done  :shipped
                                              :yield [:map [:auth :string]]})
   (sg/state :shipped   [:map [:total :int] [:auth :string]] {:done :closed})
   (sg/state :closed    [:map [:total :int] [:auth :string]] {:final true})
   (sg/state :cancelled [:map [:total :int]] {:final true})

   (sg/event :checkout [:map] (constantly {}) [:map])
   (sg/event :cancel   [:map] (constantly {}) [:map])

   (sg/transition :cart   :checkout :paying)
   (sg/transition :paying :cancel   :cancelled)))

;; The completion transitions are drawn **dashed and unlabelled**, which is UML's own
;; notation for them: there is no event to name, arriving being the whole of the cause.

(picture waiting-order)

(def waiting-step (sg/compile waiting-order))

(kind/table
 {:column-names [:event :order :payment]
  :row-vectors (let [events [{:id :checkout}
                             {:id :authorize :auth "tok_9"}
                             {:id :capture}]]
                 (map (fn [e s] [(:id e) (:id s) (-> s :sub :id)])
                      (cons nil events)
                      (reductions waiting-step (sg/initial waiting-order {:total 30}) events)))})

;; Look at the last row. **One event, and the machine moved three times**: `:capture`
;; finished the payment, so `:paying` completed and *harvested* its `:auth`, so `:shipped`
;; was entered — and `:shipped` completes on arrival, so the machine went straight on to
;; `:closed`. The child is dropped on the way, a state holding only what it declares:

(reduce waiting-step (sg/initial waiting-order {:total 30})
        [{:id :checkout} {:id :authorize :auth "tok_9"} {:id :capture}])

;; `:yield` is what a finished child hands **up**. It needs a `:machine` to harvest from and
;; a `:done` to harvest *on*, because completing is the only moment the child is guaranteed
;; final — and so the only moment the schema is a guarantee rather than a hope.
;;
;; Which is what makes the check exact rather than hopeful. A parent asking for what its
;; child cannot finish with is a **proven** fault, before anything runs — and it is asked of
;; **every** final state the child has, a child being free to finish in any of them:

(sg/problems
 (sg/shape (sg/state :p [:map] {:initial true
                                :done  :z
                                :yield [:map [:receipt :string]]
                                :machine (sg/shape
                                          (sg/state :d1 [:map] {:initial true})
                                          (sg/state :ok  [:map [:receipt :string]] {:final true})
                                          (sg/state :bad [:map] {:final true})
                                          (sg/event :win  [:map [:receipt :string]])
                                          (sg/event :lose [:map])
                                          (sg/transition :d1 :win  :ok)
                                          (sg/transition :d1 :lose :bad))})
           (sg/state :z [:map [:receipt :string]] {:final true})))

;; `:bad` is a way for that child to finish with no `:receipt` at all, so the yield is a
;; promise the shape cannot keep — and `problems` names the state that breaks it.

;; An escape is still an abort, and still yields nothing:

(reduce waiting-step (sg/initial waiting-order {:total 30}) [{:id :checkout} {:id :cancel}])

;; ### Or say where each OUTCOME goes
;;
;; That `:bad` fault has a second answer, and it is the better one whenever the two ways of
;; finishing *mean* different things. `:done` may be **a map keyed by the child's final
;; state** — one edge per outcome, each carrying a `:yield` of its own:

(def settle
  (sg/shape
   (sg/state :deciding [:map] {:initial true})
   (sg/state :ok       [:map [:receipt :string]] {:final true})
   (sg/state :bad      [:map] {:final true})

   (sg/event :win  [:map [:receipt :string]])
   (sg/event :lose [:map])

   (sg/transition :deciding :win  :ok)
   (sg/transition :deciding :lose :bad)))

(def claim
  (sg/shape
   (sg/state :settling [:map] {:initial true
                               :machine settle
                               :done {:ok  {:to :receipted :yield [:map [:receipt :string]]}
                                      :bad {:to :written-off}}})
   (sg/state :receipted   [:map [:receipt :string]] {:final true})
   (sg/state :written-off [:map] {:final true})))

(picture claim)

;; **Two dashed arrows out of one node**, and labelled now — `[ok]` and `[bad]` — because
;; there are two structural facts to tell apart where before there was one. The child's own
;; final state is the whole of the label: there is still no event, and still nothing else to
;; name. One `:win` or one `:lose` inside the child is the whole difference:

(def claim-step (sg/compile claim))

[(reduce claim-step (sg/initial claim {}) [{:id :win :receipt "r-1"}])
 (reduce claim-step (sg/initial claim {}) [{:id :lose}])]

;; And it makes the check **sharper rather than looser**. A per-outcome yield rests on its
;; own final state and no other, so `:ok` is asked about `:ok` alone — where the bare form
;; had to ask every final state the child has, and `:bad` was exactly what broke it:

(check/yields claim)

;; An outcome must name one of the child's own final states, and a misspelling is a branch
;; that can never be taken. Referential, like the rest of this:

(shape/problems
 (shape/state :settling  [:map] {:initial true :machine settle :done {:nope {:to :receipted}}})
 (shape/state :receipted [:map] {:final true}))

;; Two more come with it, and both are declarations nobody would read: outcomes without a
;; `:machine` to have them (`:outcome-without-machine`), and a `:yield` sitting *beside* a
;; per-outcome `:done` when each branch already carries its own (`:yield-with-outcomes`).
;;
;; What a completion still cannot be is a condition over the **data** — "complete to `:a` if
;; the total is over 100" — which is a different question and a much harder one.

;; ### It is not a guard, and that is what it buys
;;
;; Neither form is. What both read is a **structural** fact — that the child has finished,
;; and which of its final states it finished in — over a set that is finite and known at
;; construction, dispatched by a map lookup on an id. No schema, no predicate, and nothing to
;; prove disjoint, so determinism is untouched.
;;
;; What that gets you is a fault no guard could have: a **cycle** among states that complete
;; on entry is a *proven* infinite loop. Those states have no child and so exactly one
;; unconditional way out, which makes the relation a plain graph.
;;
;; This one is **referential** — answerable from the parts alone — so it is `shape/problems`
;; that is asked, and a machine that would spin for ever never gets built at all:

(shape/problems (sg/state :a [:map] {:initial true :done :b})
                (sg/state :b [:map] {:done :a}))

;; A cycle *through* a nesting node is legal — the events are what break it. And the
;; structural checks needed no teaching at all, because a completion transition is a **real
;; edge**: `reachable`, `dead-ends`, `finishable` and `traps` all walk the graph.

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

;; ## A join: two events, one destination
;;
;; There is no join operator, and none is needed. A state whose schema **requires** both keys
;; is reachable only once both events have been handled — and the states along the way declare
;; what has arrived so far, so **the state name is the join's progress and the schema says
;; so**. That is projection working for you: a node holds exactly what it declares.

(def R [:map [:ok :boolean]])

(def verify
  (sg/shape
   (sg/state :verifying [:map] {:initial true})
   (sg/state :evaled    [:map [:eval R]])
   (sg/state :tested    [:map [:test R]])
   (sg/state :complete  [:map [:eval R] [:test R]] {:final true})
   (sg/event :eval [:map [:eval R]])                       ; pure lifts
   (sg/event :test [:map [:test R]])
   (sg/transition :verifying :eval :evaled)  (sg/transition :tested :eval :complete)
   (sg/transition :verifying :test :tested)  (sg/transition :evaled :test :complete)))

(kind/graphviz [(sg/dot verify)])

;; Two routes, one destination — and one event alone simply parks, because the join is not
;; satisfied yet:

(let [step (sg/compile verify)
      s0   (sg/initial verify {})
      e    {:id :eval :eval {:ok true}}
      t    {:id :test :test {:ok false}}]
  {:eval-alone   (reduce step s0 [e])
   :eval-then-test (reduce step s0 [e t])
   :test-then-eval (reduce step s0 [t e])})

;; The two orders land in one **identical** state. That is not a coincidence to be hoped for,
;; it is a property of this shape that can be proven before anything runs:

(check/commuting verify)

;; `{:verifying #{#{:eval :test}}}` is a **licence**: those two events, pending in that state,
;; may be applied in whichever order finishes first. What is proven is a closing diamond —
;; both routes existing and rejoining — plus Bernstein's conditions on the patches: neither
;; writes what the other writes, and neither *reads* through a view what the other writes.
;;
;; `sg/run` computes this and takes it. Two 400ms handlers on that pair cost **400ms, not
;; 800** — the handlers run at once and their patches are applied as they land. The price is
;; that `:states` then reports the pair in *completion* order, so the two rows may come back
;; swapped against the order they were fed. No state is ever wrong; the pair was proved to
;; land in the same one either way.
;;
;; The cost of the shape is the DFA's own: the product of *n* independent events is 2^n
;; states. Four here, eight for three events. Worth knowing before joining five things.

;; ## How a patch lands: a combine
;;
;; A patch is *merged* into the state, and a merge is last-write-wins. That one operation is
;; the only non-commutative thing in the whole apply phase — and both halves of the licence
;; above traced back to it. Two patches touching one key could never be licensed, and a merge
;; cannot express a change relative to what the state already holds, which is what forces a
;; `{:sees …}` view; and a view closes the licence from the other side.
;;
;; So a key may say how a patch lands on it. Here is *fan out and take the best*:

(defn better
  "A TOTAL order — ties broken on :by. See the warning below."
  [a b]
  (if (pos? (compare [(:score a) (:by a)] [(:score b) (:by b)])) a b))

(def Impl [:map [:score :int] [:by :string]])

(def choosing
  (sg/shape
   (sg/state :choosing
             [:map [:best {:optional true
                           :combine better
                           :combine/commutes true} Impl]]
             {:initial true})
   (sg/state :chosen [:map [:best {:optional true} Impl]] {:final true})
   (sg/event :offer-a [:map [:best Impl]])
   (sg/event :offer-b [:map [:best Impl]])
   (sg/event :settle  [:map])
   (sg/transition :choosing :offer-a :choosing)
   (sg/transition :choosing :offer-b :choosing)
   (sg/transition :choosing :settle :chosen)))

;; Both events write the **same key**, and the pair is licensed anyway:

(check/commuting choosing)

;; A key with no combine replaces, exactly as before. One with a combine is combined with
;; what the state already holds — and a key the state does not hold *yet* is simply taken,
;; a combine needing two values where there is only one:

(let [step (sg/compile choosing)
      s0   (sg/initial choosing {})
      a    {:id :offer-a :best {:score 5 :by "a"}}
      b    {:id :offer-b :best {:score 9 :by "b"}}]
  {:nothing-held-yet (step s0 a)
   :a-then-b         (step (step s0 a) b)
   :b-then-a         (step (step s0 b) a)})

;; ### The combine is a function; the promise is data
;;
;; Merging is **domain logic** — keep the best-scoring implementation with its provenance,
;; deduplicate review comments by line — and no fixed vocabulary of `:+` and `:max` expresses
;; that. So the combine is an ordinary closure. That is allowed here where it is refused for a
;; **guard**, and the line between them is worth knowing: a guard decides *where the machine
;; goes*, which is structural and has to be decided from the guard's own shape, while a
;; combine decides *what a value is*, inside a state, exactly as a handler's body always has.
;;
;; But no function yields its own algebra, so `:combine/commutes` is declared beside it as
;; data — and that declaration is the only part `check/commuting` reads. It is checked rather
;; than trusted, at two strengths, and it needs both.
;;
;; `check/laws` **refutes** a law by generating from the key's own schema. It never answers
;; `:yes`, because generation can refute a law and cannot prove one:

(check/laws choosing)

;; Here is the trap, and it is the commonest one. `>=` on the score alone looks like the same
;; function, but a **tie has no canonical winner**, so the answer depends on which patch
;; arrived first:

(check/laws
 (sg/shape
  (sg/state :c [:map [:best {:optional true
                             :combine (fn [a b] (if (>= (:score a) (:score b)) a b))
                             :combine/commutes true} Impl]]
            {:initial true})
  (sg/event :o [:map [:best Impl]])
  (sg/transition :c :o :c)))

;; `:verdict :no` with the two values that disagree. The other law, `:closed`, is checked for
;; every combine whether it claims commutativity or not: `f` of two values of the key's schema
;; must answer a value of that schema, or the static subsumption check is reasoning about a
;; type the state will never hold.
;;
;; And the second strength: **`sg/run` verifies the same claim on the concrete values**
;; whenever the licence is actually taken, before either patch lands. Why both — a plausible
;; domain rule, *a pinned choice wins outright*, survived 27,000 generated triples and is not
;; commutative. Generation finds the mistakes that are about **values**; the runtime catches
;; the ones about **rare** values, and turns a false promise into a defect that stops the
;; machine rather than an order-dependent flake.
;;
;; Expect the licence to widen less than it first appears: most domain merges are not
;; commutative until you make the order total.

;; ### Fan-out: n of one event
;;
;; A combine also licenses **two events of the same id**, and that is the fan-out: *n* workers
;; each reporting a result send *n* events of one kind into one accumulating state. The shape
;; of it is a single self-loop:

(def gathering
  (sg/shape
   (sg/state :gathering [:map [:seen {:combine into :combine/commutes true} [:set :int]]]
             {:initial true})
   (sg/state :gathered [:map [:seen [:set :int]]] {:final true})
   (sg/event :found [:map [:seen [:set :int]]])
   (sg/event :stop  [:map] (constantly {}) [:map])
   (sg/transition :gathering :found :gathering)
   (sg/transition :gathering :stop  :gathered)))

(check/commuting gathering)

;; `#{:found}` is a **singleton**, and that is the fan-out licence sitting in the same
;; set-of-sets as a two-event one. Two of a kind were refused outright before: they run one
;; handler and write one set of keys, so under a merge they conflicted by construction. With a
;; commutative combine on that key they do not, and `commutes` needed no change to say so.
;;
;; Watch the accumulation, and note that `:stop` with **itself** is `:no` — it leaves
;; `:gathering`, so a second one meets a state with no edge for it:

(check/confluence gathering)

;; Here is the trap, and every first attempt at a join walks into it. `into` on a **vector**
;; is order-dependent, so which worker reported first is visible in the answer:

(check/laws
 (sg/shape
  (sg/state :s [:map [:xs {:combine into :combine/commutes true} [:vector :int]]]
            {:initial true})
  (sg/event :add [:map [:xs [:vector :int]]])
  (sg/transition :s :add :s)))

;; **Set union is** what a join wants, or a map keyed by the item.
;;
;; And what the shape does *not* say is **how wide the fan is**. Nothing here counts to *n* or
;; notices when the collection is full — that would be a guard over the state, which this
;; library refuses. Whoever dispatched the work is the only party that knows *n*, so `:stop` is
;; theirs to send. A graph shows structure; a count is data.

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
;; One consequence worth knowing, and it is why the licence of the previous section is narrower
;; than it looks: a handler that reads can have a **stale patch**. What it computed from `:n`
;; while the machine was in one state is wrong in the next if the other event changed `:n`.
;; `check/confluence` accounts for it — two pending events commute only if neither writes what
;; the other writes **and** neither reads what the other writes — so a pair like this one is
;; never licensed, and `sg/run` will serialise it.

;; ## Beneath the facade
;;
;; `robertluo.state-graph` is the only namespace an application needs, but it is a
;; convenience over six that are usable directly — `.shape`, `.compile`, `.check`, `.drive`,
;; `.async` and `.explore`. Drop through when you want something the facade does not offer,
;; which is what `drive/awaiting`, `drive/where`, `drive/advance`, `check/driving` and
;; `check/seeds` were above.
;;
;; `check/confluence`, for instance, publishes every verdict and not merely the licences, so
;; the check's own coverage is readable. For this pipeline the answer is none — divergence is
;; what a state machine is *for*, and a join is the exception rather than the rule:

(check/confluence pipeline)

;; `check/coverage` is the other one worth knowing, and it lives down here rather than in
;; `problems` for a reason: it says whether the guards on a `[state, event]` leave a gap, and
;; **a gap is not a fault** — it is exactly what a filter is for. So it is published rather
;; than complained about. The review machine has no gap; `:green` and `:red` are the whole of
;; the enum, and that is provable:

(check/coverage review)

;; Take one of those two edges away and the verdict becomes
;; `{:verdict :no :witness {:verdict :red}}` — not an error, but the value that proves an
;; event can reach nothing from there, which is worth knowing either way.

;; So `sg/run` serialises within a machine wherever nothing has been proven, which is most of
;; the time, and runs two handlers at once exactly where it can show the order will not matter.
;;
;; The other reason to drop through is starting data: `sg/run` gives every machine the same
;; initial data, and `async/fan` takes a function of the instance instead. This pipeline
;; sidesteps it by having `:draft` carry nothing and letting `:submit` bring the title.

;; ### Covering the graph by RUNNING it
;;
;; Everything in `check` answers *could this ever have worked*, from the graph alone.
;; `robertluo.state-graph.explore` answers **did it** — by driving, for real, through the
;; handlers and the guards and the schema at every crossing.

(require '[robertluo.state-graph.explore :as explore])

;; The technique is one sentence, and this page has already paid for it: **a shape is a
;; function of its env**, so whatever a report reaches for — a model, a socket, a clock, a
;; budget — arrived as a *value*, and an ordinary function goes in its place. Here is `review`
;; from further up written that way, with a `:write` that goes and finds the code rather than
;; being handed it, and both seams taken as arguments:

(defn reviewing
  [{:keys [writer judge]}]
  (sg/shape
   (sg/state :blank       [:map] {:initial true})
   (sg/state :written     [:map [:code :string]])
   (sg/state :implemented [:map [:code :string]] {:final true})
   (sg/state :faulted     [:map [:code :string] [:fault {:optional true} [:string {:min 1}]]])

   (sg/event :write  [:map [:code :string]] {:reads [:map] :report writer})
   (sg/event :judged [:map [:verdict [:enum :green :red]]
                           [:fault {:optional true} [:string {:min 1}]]]
             (fn [e] (select-keys e [:fault]))
             [:map [:fault {:optional true} [:string {:min 1}]]]
             {:reads [:map [:code :string]] :report judge})
   (sg/event :again  [:map])

   (sg/transition :blank   :write  :written)
   (sg/transition :written :judged :implemented {:when [:map [:verdict [:= :green]]]})
   (sg/transition :written :judged :faulted     {:when [:map [:verdict [:= :red]]]})
   (sg/transition :faulted :again  :written)))

;; `covering` takes that constructor, a base env, and the alternatives to vary. The base env
;; has to build a shape **on its own** — the constructor is called once on it alone, to ask
;; the structural questions — so every seam has a value here even where it is about to be
;; overridden:

(def env {:writer (constantly {:code "(defn answer [] 42)"})
          :judge  (constantly {:verdict :green})})

(explore/covering reviewing env {})

;; Two of four, on one run, and the interesting number is **`:gaps`** —
;; `[:written :judged :faulted]` is an edge a driver *could* have taken and nothing produced
;; a payload for. That is the red branch: the one a live run reaches once a week and a suite
;; reaches never. Vary the judge and it closes:

(explore/covering reviewing env
                  {:judge [(constantly {:verdict :green})
                           (constantly {:verdict :red :fault "it threw"})]})

;; One run per **combination**, so the cost is the product of the alternatives and not a
;; search over turns — and no model, no socket and no clock in any of them.
;;
;; **`:gaps` is empty and one transition is still uncovered, and that is the whole design.**
;; Three kinds of edge cannot be taken by any driver however you fake the world, and calling
;; them failures would cry wolf on every real machine:
;;
;; - `:no-report` — the event is the **world's**. `:again` is a person deciding to try again,
;;   and no substitution reaches a person. That is the one left above.
;; - `:join-order` — the state is a *proven* join, so the crank takes its events at once in
;;   one order and the other orderings' halfway states are never entered. Exactly the property
;;   that makes a join safe is what makes half its diamond undrivable.
;; - `:unvisited-state` — nothing reached the state this edge leaves from, so the reason is
;;   upstream and this edge is not the thing to fix.
;;
;; Subtract those and the residue is `:gaps`, which is the only number that means you missed
;; something. **A completion transition is scored too** — a `:done` keyed by the child's final
;; state is a fork, and a fork no run took is precisely what this exists to name.
;;
;; And it is not only functions. A budget that is an edge is covered by varying a **plain
;; number**, where otherwise it would take as many real laps as the budget allows.

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
