(ns robertluo.state-graph.draw-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.shape :as shape]
            [clojure.string :as str]
            [robertluo.state-graph.check :refer [draw! dot]]))

(deftest draw-invalid-shape-with-save-throws
  (testing "an invalid shape throws when asked to save as dot"
    (is (thrown? Exception
                 (draw! :rubbish {:save {:filename "/tmp/draw-test-rubbish-save.dot" :format :dot}})))))

(deftest draw-invalid-shape-no-save-throws
  (testing "an invalid shape throws even with no save option (would try to render/open)"
    (is (thrown? Exception (draw! :rubbish)))))

(deftest draw-dot-save-writes-graphviz-source
  (testing "saving with :format :dot spits the graphviz source, matching (dot sh)"
    (let [sh (shape/shape (shape/state :draft [:map] {:initial true})
                           (shape/state :done [:map] {:final true})
                           (shape/event :submit [:map])
                           (shape/transition :draft :submit :done))
          f (str (System/getProperty "java.io.tmpdir") "/draw-test-" (System/nanoTime) ".dot")]
      (draw! sh {:save {:filename f :format :dot}})
      (let [content (slurp f)]
        (is (str/starts-with? content "digraph"))
        (is (= content (dot sh)))))))

(deftest draw-png-save-produces-nonempty-file
  (testing "saving with a real graphviz format shells out and writes a usable (non-empty) file"
    (let [sh (shape/shape (shape/state :draft [:map] {:initial true})
                           (shape/state :done [:map] {:final true})
                           (shape/event :submit [:map])
                           (shape/transition :draft :submit :done))
          f (str (System/getProperty "java.io.tmpdir") "/draw-test-" (System/nanoTime) ".png")]
      (draw! sh {:save {:filename f :format :png}})
      (is (pos? (.length (java.io.File. f)))))))

(deftest draw-invalid-format-throws-ex-info-with-details
  (testing "an invalid graphviz format throws ex-info carrying enough to see what went wrong: the filename, the format, and graphviz's stderr -- without assuming exact key names"
    (let [sh (shape/shape (shape/state :draft [:map] {:initial true})
                           (shape/state :done [:map] {:final true})
                           (shape/event :submit [:map])
                           (shape/transition :draft :submit :done))
          f (str (System/getProperty "java.io.tmpdir") "/draw-test-" (System/nanoTime) ".nope")
          data (try
                 (draw! sh {:save {:filename f :format :nope}})
                 nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (map? data))
      (is (some #(= % f) (vals data)))
      (is (some #(= % :nope) (vals data)))
      (is (some (fn [v] (and (string? v) (str/includes? (str/lower-case v) "not recognized"))) (vals data))))))

(defspec dot-output-well-formed 20
  (prop/for-all [n (gen/choose 2 5)]
    (let [names (map #(keyword (str "s" %)) (range n))
          states (map-indexed
                   (fn [i k]
                     (shape/state k [:map]
                                  (cond-> {}
                                    (zero? i) (assoc :initial true)
                                    (= i (dec n)) (assoc :final true))))
                   names)
          event (shape/event :evt [:map])
          transitions (map (fn [[a b]] (shape/transition a :evt b)) (partition 2 1 names))
          sh (apply shape/shape (concat states [event] transitions))
          s (dot sh)]
      (and (string? s)
           (str/starts-with? s "digraph")
           (every? #(str/includes? s (name %)) names)))))

(defspec draw-dot-save-matches-dot-source 10
  (prop/for-all [n (gen/choose 2 4)]
    (let [names (map #(keyword (str "t" %)) (range n))
          states (map-indexed
                   (fn [i k]
                     (shape/state k [:map]
                                  (cond-> {}
                                    (zero? i) (assoc :initial true)
                                    (= i (dec n)) (assoc :final true))))
                   names)
          event (shape/event :go [:map])
          transitions (map (fn [[a b]] (shape/transition a :go b)) (partition 2 1 names))
          sh (apply shape/shape (concat states [event] transitions))
          f (str (System/getProperty "java.io.tmpdir") "/draw-test-prop-" (System/nanoTime) ".dot")]
      (draw! sh {:save {:filename f :format :dot}})
      (= (slurp f) (dot sh)))))