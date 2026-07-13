(ns bot.log-test
  (:require
   [bot.log :as blog]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(def beispiel
  {:turn 3 :phase :action/corp :side :corp :kind :action
   :options ["gain 1 credit" "draw 1 card"] :choice "draw 1 card"
   :reason "random-bot: uniform zufällig, 1 von 2 legalen Aktionen"
   :no-op false})

(deftest decision-entry-edn-roundtrip
  (let [entry (blog/decision-entry beispiel)]
    (is (= entry (edn/read-string (pr-str entry)))
        "Eintrag überlebt pr-str/read-string verlustfrei")
    (is (every? #(contains? entry %)
                [:turn :phase :side :kind :options :choice :reason :no-op]))))

(deftest append-decision!-schreibt-eine-edn-zeile-pro-eintrag
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "bot-log-test-" (System/currentTimeMillis) ".edn"))]
    (try
      (blog/append-decision! (.getPath f) beispiel)
      (blog/append-decision! (.getPath f) (assoc beispiel :turn 4))
      (let [lines (str/split-lines (slurp f))]
        (is (= 2 (count lines)))
        (is (= [3 4] (map #(:turn (edn/read-string %)) lines))))
      (finally (.delete f)))))
