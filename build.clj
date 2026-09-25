(ns build
  "The release, as the author's other libraries do it: `clojure -T:build ci` cleans, runs
   both suites and builds the jar; `clojure -T:build deploy` sends it to Clojars under
   io.github.robertluo. The version is 0.1 followed by the commit count, so every commit
   is a version and none is typed."
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as cb]))

(defn project
  "The project's coordinates and licence, merged under `opts`."
  [opts]
  (merge {:lib      'io.github.robertluo/state-graph
          :version  (format "0.1.%s" (b/git-count-revs nil))
          :scm      {:url "https://github.com/robertluo/state-graph"}
          :pom-data [[:licenses
                      [:license
                       [:name "MIT License"]
                       [:url "https://opensource.org/license/mit/"]]]]}
         opts))

(defn tests
  "Both suites: the fast one, then the gate. The gate shells out to graphviz."
  [opts]
  (-> opts
      (cb/run-task [:dev :test])))

(defn ci
  [opts]
  (-> opts project cb/clean tests cb/jar))

(defn deploy
  [opts]
  (-> opts project cb/deploy))
