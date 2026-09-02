(ns robertluo.state-graph
  "THE FACADE: the vocabulary a user needs, and the only require an application should
   have.

   BUILD a shape out of states, events and transitions; LOOK at it with `problems`, `draw!`
   and `dot`, which is what having a graph buys; then RUN it through one of two doors.

   A NODE MAY NEST A WHOLE MACHINE — {:machine sh} on a state — which is how a big problem
   stays readable. See `state`.

   WHAT ANYTHING INSIDE THE MACHINE MAY SEE IS DECLARED, never automatic. A node holds exactly
   the keys its schema names, and a handler reads only through a view its event declares —
   {:sees <a map schema>} on `event`, which is also how a machine accumulates.

   TWO DOORS, ONE MACHINE, AND THE CALLER OWNS THE LIFECYCLE IN BOTH. That is the whole
   answer to who owns it, and the doors are not two designs:

     the reduction — (reduce (compile sh) (initial sh {}) events), the README's own
                     headline sentence. A step is an ordinary function of a state and an
                     event, so it goes in a fold, a transducer, core.async, a test, or
                     anything else that can hold an accumulator.

     the stream    — (run sh {} events) -> {:states :done}. A source of events in, a
                     source of Transitions out. The state lives in a manifold loop's
                     accumulator exactly as it lives in reduce's; there is no cell
                     holding it and no object to own. `run` is a CALLER THIS LIBRARY
                     SHIPS, not a second kind of machine.

   IT STORES NOTHING. What comes out of `run` is what happened — the event, the state it
   produced, and whether it fired at all — which is everything an audit trail or a trace
   needs. Writing it down is the caller's, and this library has no database in it.

   THESE ARE DELEGATIONS AND NOT ALIASES, which is not cosmetic: (def state shape/state)
   would capture the raw function, and malli's instrument! replaces the VAR's root, so
   every call through such an alias would silently skip the guard the owning namespace
   declares. Measured 2026-09-01. They carry no :malli/schema of their own for the
   matching reason — the contract belongs to the namespace that owns the function, one
   declaration and not two, and a copy here could only drift. `run` is the one function
   this namespace really adds, so it is the one that carries a schema.

   Requires everything below it, which is what makes one require enough — including
   `check`, so an application loads the graph algorithms it may never run. That is what a
   facade costs; a user who minds requires robertluo.state-graph.compile directly."
  (:refer-clojure :exclude [compile])
  (:require [robertluo.state-graph.async :as async]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.compile :as compile]
            [robertluo.state-graph.shape :as shape]))

;;; ---------------------------------------------------------------- vocabulary

(def Instance
  "What names a RUN of the machine. The caller's to choose — a uuid, an order number, a
   string — and never nil."
  shape/Instance)

(def State
  "A state as it exists at runtime: a map saying which node it is in, and which run it
   belongs to when it has a name."
  compile/State)

(def Event
  "An event as it arrives: a map saying its own type, and which run it is for."
  compile/Event)

(def Transition
  "WHAT `run` PUTS ON :states — a transition result and not a bare state.

   The reason is that this library stores nothing and the caller does. A state does not
   say what caused it, and an event that nobody handled produces a state identical to the
   one before it — so from a stream of states alone, no consumer can build the history
   this library has just declined to keep. A result can be read back down to states with
   (map :state) whenever that is all somebody wants; the other direction does not exist.

   :fired is false where the state had no transition for the event. That is NOT an error —
   nothing controls the order events arrive in behind a stream — but it is the one fact no
   consumer could recover for itself, and the reason `admits?` is public.

   :instance is DERIVED FROM THE STATE and not from the event, so there is one source for
   it, and it is absent where a caller never named the run."
  [:map [:event Event]
        [:state State]
        [:fired :boolean]
        [:instance {:optional true} Instance]])

;;; -------------------------------------------------------------- building one

(defn state
  "A state: an id, the malli schema of its DATA, and optionally {:initial true},
   {:final true} or {:machine <a shape>}. Exactly one state in a shape is the initial one.

   THE SCHEMA IS WHAT THIS NODE HOLDS, not a lower bound on it: the merge is projected onto
   these keys on entry, so anything a state does not declare is dropped at its door. Data that
   must survive several states is declared by each of them, and dropping a field is declaring
   one fewer. That is also what bounds what anything INSIDE the machine can see — a state that
   never held a secret cannot leak one.

   It describes the map WITHOUT :id, :instance and :sub — what a state is called, which run it
   belongs to, and what a nested machine is doing are the machine's to say and never a
   handler's.

   {:machine sh} NESTS A WHOLE MACHINE IN THIS NODE. While the parent sits here, that child
   gets every event FIRST and this node's own edges get only what the child does not know —
   so the child's vocabulary decides who handles what, and a child that has finished admits
   nothing and stops competing. The child's state lives under :sub, seeded on entry, dropped
   on the way out, and visible on every result. It is an ordinary shape, so it is checked
   and drawn as one."
  ([id schema] (shape/state id schema))
  ([id schema opts] (shape/state id schema opts)))

(defn event
  "An event: an id, the malli schema of its DATA, the HANDLER that answers it, and
   optionally the schema of what that handler answers.

   GIVEN ONLY AN ID AND A SCHEMA it is a PURE LIFT — the handler answers exactly the keys
   the schema declares and the :out is that schema, which is what most events are and what
   the longer form says three times:

     (event :brief [:map [:brief Brief]])       ; handler and :out are the schema's to give
     (event :green [:map])                      ; an event that carries nothing

   BY DEFAULT the handler takes THE EVENT ALONE — nothing of the state it is about to change —
   and answers a map that is merged into the state. Declaring that map's schema is what lets
   `problems` prove, without running anything, that a target will not admit what a handler
   produces.

   {:sees <a map schema>} as a fifth argument gives the handler a VIEW, and it is the only way
   anything inside the machine reads the state: declared, never automatic, and narrowed to
   exactly the keys named. The handler then takes two arguments, (handler event seen).
   Declaring it on the EVENT rather than the node is what keeps the handler reusable — it names
   what it needs by shape, not by node — and `problems` proves whether the states it reads can
   actually provide it.

   A VIEW IS ALSO HOW A MACHINE ACCUMULATES, and the policy stays ordinary code: read the old
   value, answer the new one, cap or summarise it however the task wants. That is why nothing
   in the shape combines keys for you — a combine could only ever grow."
  ([id schema] (shape/event id schema))
  ([id schema handler] (shape/event id schema handler))
  ([id schema handler out] (shape/event id schema handler out))
  ([id schema handler out opts] (shape/event id schema handler out opts)))

(defn transition
  "An edge: from a state, on an event, to a state. What handles the event belongs to the
   event, so two edges firing one event cannot disagree about it."
  [from event to]
  (shape/transition from event to))

(defn shape
  "The parts as a graph, or a throw carrying what is wrong with them in ex-data.

   What is checked here is REFERENTIAL — answerable from the parts alone, so a shape with a
   transition to a state nobody defined cannot be built. What needs the built graph is
   `problems`, and it is deliberately not run here: a shape under construction is worth
   drawing, and a shape you cannot build is a shape you cannot look at."
  [& parts]
  (apply shape/shape parts))

;;; -------------------------------------------------- looking at what you built

(defn problems
  "What is STRUCTURALLY wrong with a built shape, as DATA — a state nothing reaches, a dead
   end, a trap it can never finish from, a handler whose answer the target refuses. Empty
   when nothing is.

   Only PROVEN faults, and this is the check no other FSM library has: it is answered
   WITHOUT RUNNING ANYTHING."
  [sh]
  (check/problems sh))

(defn draw!
  "The shape as a picture — the same question `problems` answers, by the means a person is
   better at. An unreachable state is obvious in a drawing and invisible in a map literal.

   Needs graphviz, except for {:save {:filename f :format :dot}}, which writes the source
   and is a plain spit. No :save at all opens a viewer. It answers nothing: for the source
   as a VALUE, ask `dot`."
  ([sh] (check/draw! sh))
  ([sh opts] (check/draw! sh opts)))

(defn dot
  "The same drawing as GRAPHVIZ SOURCE, as a string — for anything that renders a diagram
   itself rather than shelling out to graphviz: a notebook, a web page, a docs build. Needs
   nothing installed."
  [sh]
  (check/dot sh))

;;; ------------------------------------------------------------- the reduction

(defn compile
  "The shape as an ordinary Clojure function of a state and an event, answering the next
   state. The runtime is then (reduce step (initial sh {}) events) and there is nothing
   else to it — no object, no atom, no protocol.

   An event the state has no transition for answers the state UNCHANGED and never calls the
   handler. Pass a Context to be told about it, or use the stream door, which says so as
   data. What throws is a crossing that does not hold — those are defects, not facts about
   the run."
  ([sh] (compile/compile sh))
  ([sh context] (compile/compile sh context)))

(defn initial
  "The first state: the node the shape starts in, carrying the caller's data, and entered
   through the same validation as every other state.

   NAME THE RUN and the key is written for you — a caller says which machine they mean and
   never spells :instance again, here or in a handler, which could not reach it anyway."
  ([sh data] (compile/initial sh data))
  ([sh instance data] (compile/initial sh instance data)))

;;; ----------------------------------------------------------------- the stream

(defn run
  "A shape, the data every machine starts with, and a source of events -> {:states :done}.

   :states is a source of Transitions, one per event, closed when no more are coming.
   CONSUME IT, OR :done MAY NEVER RESOLVE — backpressure is real, so a machine whose
   results nobody reads stops rather than racing ahead.

   :done is a deferred {instance -> final state}, or an error carrying whatever the step
   threw. A caller who named nothing finds their machine under nil.

   ONE FUNCTION FOR ONE MACHINE AND FOR MANY, because the stream is partitioned on
   :instance and one partition is one machine — each reduced strictly in order, all of them
   at once. That is where the parallelism is and where it stays: within a machine, an event
   is applied to what the last one produced, which is what a reduction means.

   Handlers may answer deferreds here, which is the point of the door: a machine waiting on
   I/O holds no thread, and a slow handler slows only its own machine."
  {:malli/schema [:=> [:cat shape/Shape :map async/Source] async/Machine]}
  [sh data events]
  (let [idx (compile/index sh)]
    (async/fan (compile/compile sh async/context)
               (fn [instance] (compile/initial sh instance data))
               events
               (fn [state event state']
                 (cond-> {:event event
                          :state state'
                          :fired (compile/admits? idx state event)}
                   (some? (:instance state')) (assoc :instance (:instance state')))))))
