(ns bot.legal-test
  (:require
   [bot.legal :as legal]
   [bot.view :as view]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.test-framework :refer :all]))

(defn- commands-of [actions] (set (map :command actions)))

(deftest corp-aktionsphase-basis
  (do-game
    ;; Explizites :deck neben :hand, sonst besteht der Corp-Deck-Pool
    ;; (game.test-framework/make-decks) nur aus den Hand-Karten selbst, die
    ;; komplett in die Hand gezogen werden -> :deck-count waere 0 und "draw"
    ;; wuerde nie angeboten, unabhaengig von der legal.clj-Implementierung.
    (new-game {:corp {:hand ["Hedge Fund" "Ice Wall"] :deck ["Hedge Fund"]}})
    (let [actions (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (contains? (commands-of actions) "credit"))
      (is (contains? (commands-of actions) "draw"))
      (is (= 2 (count (filter #(= "play" (:command %)) actions)))
          "beide spielbaren Handkarten werden angeboten")
      (is (not (contains? (commands-of actions) "end-turn"))
          "end-turn nicht anbieten solange Klicks übrig sind")
      (is (every? #(and (string? (:label %)) (seq (:label %))) actions)))))

(deftest keine-klicks-nur-end-turn
  (do-game
    (new-game)
    (core/gain state :corp :click -3)
    (let [actions (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (= ["end-turn"] (mapv :command actions))))))

(deftest runner-bekommt-run-optionen-auf-alle-server
  (do-game
    (new-game {:corp {:hand ["PAD Campaign"]}})
    (play-from-hand state :corp "PAD Campaign" "New remote")
    (take-credits state :corp)
    (let [actions (legal/turn-actions (view/view-for state :runner) :runner)
          servers (set (map #(get-in % [:args :server])
                            (filter #(= "run" (:command %)) actions)))]
      (is (= #{"HQ" "R&D" "Archives" "Server 1"} servers)))))

(deftest score-nur-fuer-fertige-agenden
  (do-game
    (new-game {:corp {:hand ["Priority Requisition"]}})
    (play-from-hand state :corp "Priority Requisition" "New remote")
    (let [ohne (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (not (contains? (commands-of ohne) "score"))
          "Agenda ohne Advancements nicht scorebar"))
    (core/gain state :corp :click 10 :credit 10)
    (dotimes [_ 5]
      (click-advance state :corp (get-content state :remote1 0)))
    (let [mit (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (contains? (commands-of mit) "score")))))

(deftest prompt-optionen-fuer-button-prompt
  (do-game
    ;; :dont-start-game gehoert unter :options (siehe make-decks in
    ;; game.test-framework) -- auf oberster Ebene wird es ignoriert, new-game
    ;; klickt dann sofort "Keep" fuer beide Seiten und der Prompt ist schon
    ;; wieder leer, bevor der Test ihn abfragt.
    (new-game {:options {:dont-start-game true}})
    (let [options (legal/prompt-options (view/view-for state :corp) :corp)]
      (is (= #{"Keep" "Mulligan"} (set (map :label options))))
      (is (every? #(= :button (:type %)) options))
      (is (every? :uuid options) "Button-Optionen tragen die uuid für den choice-Command"))))

(deftest run-optionen-approach-und-encounter
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (take-credits state :corp)
    (run-on state "HQ")
    ;; Approach: Corp darf rezzen oder continue
    (let [corp-opts (legal/run-actions (view/view-for state :corp) :corp)]
      (is (contains? (commands-of corp-opts) "continue"))
      (is (contains? (commands-of corp-opts) "rez")))
    (let [runner-opts (legal/run-actions (view/view-for state :runner) :runner)]
      (is (contains? (commands-of runner-opts) "continue")))))
