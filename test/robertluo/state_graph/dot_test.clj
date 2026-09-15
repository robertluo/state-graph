(ns robertluo.state-graph.dot-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.shape :as shape]
            [clojure.string :as str]
            [robertluo.state-graph.check :refer [dot labelled]]))

(deftest dot-basic-shape
  (let [sh (shape/shape (shape/state :draft [:map] {:initial true})
                         (shape/state :done [:map] {:final true})
                         (shape/event :submit [:map])
                         (shape/transition :draft :submit :done))
        d (dot sh)]
    (testing "starts with digraph"
      (is (str/starts-with? d "digraph")))
    (testing "contains arrow"
      (is (str/includes? d "->")))
    (testing "contains event name"
      (is (str/includes? d "submit")))
    (testing "contains markers"
      (is (str/includes? d "▸"))
      (is (str/includes? d "◼")))
    (testing "no closures leak"
      (is (not (str/includes? d "$eval"))))))

(deftest dot-with-fn-guard-does-not-leak
  (let [sh (shape/shape (shape/state :a [:map] {:initial true})
                         (shape/state :b [:map] {:final true})
                         (shape/event :go [:map] (fn [e] e))
                         (shape/transition :a :go :b))
        d (dot sh)]
    (is (string? d))
    (is (not (str/includes? d "$eval")))
    (is (str/includes? d "digraph"))
    (is (str/includes? d "go"))))

(defspec dot-includes-all-labelled-nodes-and-edges 50
  (prop/for-all [n (gen/choose 2 6)]
    (let [ids (mapv #(keyword (str "s" %)) (range n))
          states (map-indexed
                   (fn [i id]
                     (shape/state id [:map]
                                  (cond-> {}
                                    (zero? i) (assoc :initial true)
                                    (= i (dec n)) (assoc :final true))))
                   ids)
          transitions (map (fn [[a b]] (shape/transition a :go b))
                            (partition 2 1 ids))
          sh (apply shape/shape (concat states [(shape/event :go [:map])] transitions))
          d (dot sh)
          {:keys [nodes edges]} (labelled sh)]
      (and (every? #(str/includes? d (:label %)) nodes)
           (every? #(str/includes? d (:label %)) edges)))))

(defspec dot-always-returns-a-string 30
  (prop/for-all [n (gen/choose 2 5)]
    (let [ids (mapv #(keyword (str "t" %)) (range n))
          states (map-indexed
                   (fn [i id]
                     (shape/state id [:map]
                                  (cond-> {}
                                    (zero? i) (assoc :initial true)
                                    (= i (dec n)) (assoc :final true))))
                   ids)
          transitions (map (fn [[a b]] (shape/transition a :go b))
                            (partition 2 1 ids))
          sh (apply shape/shape (concat states [(shape/event :go [:map])] transitions))]
      (and (string? (dot sh))
           (str/starts-with? (dot sh) "digraph")
           (str/includes? (dot sh) "->")))))
