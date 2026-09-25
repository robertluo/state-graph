(ns robertluo.state-graph.licence-agrees-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [robertluo.state-graph.shapes :as shape]
            [robertluo.state-graph.check :as check]
            [robertluo.state-graph.test-support :as ts]
            [robertluo.state-graph.crank :refer [licence-agrees]]))

(deftest licence-agrees-examples
  (testing "given example calls"
    (is (= {:licensed 1 :disagreeing []}
           (licence-agrees
            (shape/shape
             (shape/state :s [:map] {:initial true})
             (shape/state :ta [:map [:a :int]])
             (shape/state :tb [:map [:b :int]])
             (shape/state :x [:map [:a :int] [:b :int]] {:final true})
             (shape/event :a [:map [:a :int]])
             (shape/event :b [:map [:b :int]])
             (shape/transition :s :a :ta)
             (shape/transition :s :b :tb)
             (shape/transition :ta :b :x)
             (shape/transition :tb :a :x))
            5)))
    (is (= {:licensed 0 :disagreeing []}
           (licence-agrees
            (shape/shape
             (shape/state :idle [:map] {:initial true})
             (shape/state :running [:map] {:final true})
             (shape/state :cancelled [:map] {:final true})
             (shape/event :start [:map])
             (shape/event :cancel [:map])
             (shape/transition :idle :start :running)
             (shape/transition :idle :cancel :cancelled))
            5)))
    (is (= 1 (:licensed
              (licence-agrees
               (shape/shape
                (shape/state :sum [:map [:n {:combine + :combine/commutes true} :int]] {:initial true})
                (shape/event :add [:map [:n :int]])
                (shape/transition :sum :add :sum))
               3))))))

(deftest licence-agrees-shape-of-result
  (testing "return value always matches its schema shape"
    (let [shp (shape/shape
               (shape/state :s [:map] {:initial true})
               (shape/state :ta [:map [:a :int]])
               (shape/state :tb [:map [:b :int]])
               (shape/state :x [:map [:a :int] [:b :int]] {:final true})
               (shape/event :a [:map [:a :int]])
               (shape/event :b [:map [:b :int]])
               (shape/transition :s :a :ta)
               (shape/transition :s :b :tb)
               (shape/transition :ta :b :x)
               (shape/transition :tb :a :x))
          result (licence-agrees shp 2)]
      (is (contains? result :licensed))
      (is (contains? result :disagreeing))
      (is (int? (:licensed result)))
      (is (>= (:licensed result) 0))
      (is (vector? (:disagreeing result))))))

(defspec licence-agrees-never-disagrees 40
  (prop/for-all [parts ts/gen-shape
                 samples (gen/choose 1 5)]
    (let [shp (apply shape/shape parts)
          {:keys [disagreeing]} (licence-agrees shp samples)]
      (empty? disagreeing))))

(defspec licence-agrees-licensed-matches-commuting-count 40
  (prop/for-all [parts ts/gen-shape
                 samples (gen/choose 1 5)]
    (let [shp (apply shape/shape parts)
          expected (reduce + 0 (map (comp count val) (check/commuting shp)))
          {:keys [licensed]} (licence-agrees shp samples)]
      (= expected licensed))))

(defspec licence-agrees-licensed-independent-of-samples 30
  (prop/for-all [parts ts/gen-shape
                 s1 (gen/choose 1 5)
                 s2 (gen/choose 1 5)]
    (let [shp (apply shape/shape parts)
          l1 (:licensed (licence-agrees shp s1))
          l2 (:licensed (licence-agrees shp s2))]
      (= l1 l2))))
