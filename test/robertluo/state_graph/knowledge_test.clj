(ns robertluo.state-graph.knowledge-test
  "THE KNOWLEDGE IS METADATA, AND THIS HOLDS IT TO ITS VOCABULARY.

   What this library knows about itself — its decisions, rules, rejected alternatives,
   lessons and open questions — lives in :knowledge metadata on the var or namespace each
   is about. See the facade namespace's :knowledge-is-metadata and :the-knowledge-vocabulary.
   A vocabulary nothing checks is a convention, so this suite asserts, over every node in
   every namespace: that it fits the schema, that its id is unique, that every id it cites
   exists, that every code entity it names exists, and that its edges point DOWN the arrow
   and never up. The tutorial's ns form is read as data, being on no classpath."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [robertluo.state-graph]
            [robertluo.state-graph.explore]
            [robertluo.state-graph.test-support]))

(def namespaces
  "Every namespace whose :knowledge is collected — the seven of src, the facade and the test
   support. The tutorial is read separately, see `tutorial-nodes`."
  '[robertluo.state-graph.graph robertluo.state-graph.shape robertluo.state-graph.compile robertluo.state-graph.check
    robertluo.state-graph.async robertluo.state-graph.drive robertluo.state-graph.explore
    robertluo.state-graph robertluo.state-graph.test-support])

(def Node
  "One piece of knowledge, as the facade's :the-knowledge-vocabulary says it."
  [:map {:closed true}
   [:id :keyword]
   [:kind [:enum :decision :rule :rejected :lesson :open]]
   [:says [:string {:min 1}]]
   [:why {:optional true} [:string {:min 1}]]
   [:from {:optional true} [:string {:min 1}]]
   [:when {:optional true} [:re #"\d{4}-\d{2}-\d{2}"]]
   [:see {:optional true} [:vector {:min 1} :keyword]]
   [:cites {:optional true} [:vector {:min 1} :keyword]]
   [:supersedes {:optional true} [:vector {:min 1} :keyword]]])

(defn- loaded-nodes
  "Every node in `namespaces`, each stamped with :home (the namespace it is attached to)
   and :holder (that namespace, or the var)."
  []
  (for [ns-sym namespaces
        :let [the-ns (the-ns ns-sym)]
        [holder ks] (cons [ns-sym (:knowledge (meta the-ns))]
                          (for [[_ v] (ns-interns the-ns)] [(symbol v) (:knowledge (meta v))]))
        node ks]
    (assoc node :home ns-sym :holder holder)))

(defn- tutorial-nodes
  "The tutorial's ns-level nodes, read off its first form as data — the notebook is on no
   classpath, and its knowledge is about clay and kindly, which nothing here loads."
  []
  (let [form (read-string (slurp "notebook/tutorial.clj"))]
    (for [node (:knowledge (first (filter map? form)))]
      (assoc node :home 'tutorial :holder 'tutorial))))

(defn- requires*
  "The namespaces `ns-sym` requires, transitively — what is BELOW it in the arrow."
  [ns-sym]
  (loop [todo [ns-sym] seen #{}]
    (if-let [[n & more] (seq todo)]
      (let [below (when-let [the-ns (find-ns n)]
                    (remove seen (map ns-name (vals (ns-aliases the-ns)))))]
        (recur (into (vec more) below) (into seen below)))
      seen)))

(defn- names-what?
  "Does `kw` name a var or a namespace that exists? A qualified keyword is a var, an
   unqualified one a namespace."
  [kw]
  (if-let [nsn (namespace kw)]
    (some-> (find-ns (symbol nsn)) ns-interns (get (symbol (name kw))))
    (find-ns (symbol (name kw)))))

(defn- ns-of
  "The namespace a :see target belongs to."
  [kw]
  (symbol (or (namespace kw) (name kw))))

(deftest every-node-fits-the-vocabulary
  (let [nodes (concat (loaded-nodes) (tutorial-nodes))]
    (is (< 100 (count nodes)) "the migration landed")
    (doseq [{:keys [holder] :as node} nodes]
      (is (m/validate Node (dissoc node :home :holder))
          (str holder " " (:id node) " " (pr-str (m/explain Node (dissoc node :home :holder))))))))

(deftest every-id-is-unique
  (let [ids (map :id (concat (loaded-nodes) (tutorial-nodes)))
        dupes (for [[id n] (frequencies ids) :when (< 1 n)] id)]
    (is (empty? dupes) (pr-str dupes))))

(deftest every-edge-resolves
  (let [nodes (concat (loaded-nodes) (tutorial-nodes))
        ids   (set (map :id nodes))]
    (doseq [{:keys [id cites supersedes see]} nodes]
      (testing (str id)
        (is (empty? (remove ids (concat cites supersedes)))
            (str "cites an id nobody defines: " (pr-str (remove ids (concat cites supersedes)))))
        (is (empty? (remove names-what? see))
            (str "sees a name nothing defines: " (pr-str (remove names-what? see))))))))

(deftest every-edge-points-down-or-sideways
  (let [nodes   (loaded-nodes)
        home-of (into {} (map (juxt :id :home)) (concat nodes (tutorial-nodes)))
        above?  (fn [target home] (and (not= target home) (contains? (requires* target) home)))
        upward  (for [{:keys [id home cites supersedes see]} nodes
                      [edge target target-ns] (concat (for [t see] [:see t (ns-of t)])
                                                      (for [c (concat cites supersedes)] [:cites c (home-of c)]))
                      :when (above? target-ns home)]
                  {:node id :on home edge target :which-lives-on target-ns})]
    (is (empty? upward)
        (str "an edge that points UP the arrow: " (pr-str upward)))))

(deftest a-superseded-node-is-still-there
  (let [nodes (loaded-nodes)
        ids   (set (map :id nodes))]
    (doseq [{:keys [id supersedes]} nodes, old supersedes]
      (is (ids old) (str id " supersedes " old ", which must stay — a node is added to, never edited")))))
