(ns bot.cards-test
  (:require
   [bot.cards :as cards]
   [clojure.test :refer :all]
   [game.utils :refer [server-card]]))

(use-fixtures :once (fn [f] (cards/load-all-cards!) (f)))

(deftest demo-deck-karten-existieren
  (doseq [deck [cards/demo-corp cards/demo-runner]]
    (is (some? (server-card (:identity deck)))
        (str "Identity nicht gefunden: " (:identity deck)))
    (doseq [[title _qty] (:cards deck)]
      (is (some? (server-card title)) (str "Karte nicht gefunden: " title)))))

(deftest player-entry-hat-init-game-format
  (let [entry (cards/player-entry "Corp" cards/demo-corp)]
    (is (= "Corp" (:side entry)))
    (is (map? (get-in entry [:deck :identity])))
    (is (= "Corp" (get-in entry [:deck :identity :side])))
    (is (every? #(and (map? (:card %)) (pos-int? (:qty %)))
                (get-in entry [:deck :cards])))
    (is (= 15 (reduce + (map :qty (get-in entry [:deck :cards])))))))
