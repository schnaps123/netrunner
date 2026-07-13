(ns bot.random-test
  (:require
   [bot.protocol :as bp]
   [bot.random :as random]
   [clojure.test :refer :all]))

(deftest decide-waehlt-nur-legale-aktionen
  (let [bot (random/random-bot 42)
        actions [{:command "credit"} {:command "draw"} {:command "end-turn"}]
        results (doall (repeatedly 100 #(bp/decide bot {} actions)))]
    (is (every? #(some #{(:action %)} actions) results)
        "Wahl ist immer Element der übergebenen legalen Aktionen")
    (is (every? #(and (string? (:reason %)) (seq (:reason %))) results)
        "Jede Entscheidung hat einen nicht-leeren Begründungstext")
    (is (= (set actions) (set (map :action results)))
        "Über 100 Züge wird jede Option mindestens einmal gewählt (uniform)")))

(deftest decide-deterministisch-pro-seed
  (let [actions [{:command "credit"} {:command "draw"} {:command "end-turn"}]
        run (fn [] (let [bot (random/random-bot 7)]
                     (mapv :action (doall (repeatedly 20 #(bp/decide bot {} actions))))))]
    (is (= (run) (run)) "Gleicher Seed ⇒ gleiche Zugfolge")))

(deftest on-prompt-waehlt-nur-gegebene-optionen
  (let [bot (random/random-bot 1)
        options [{:type :button :label "Keep"} {:type :button :label "Mulligan"}]
        results (doall (repeatedly 50 #(bp/on-prompt bot {} {:msg "Keep hand?"} options)))]
    (is (every? #(some #{(:option %)} options) results))
    (is (= (set options) (set (map :option results))))
    (is (every? #(string? (:reason %)) results))))
