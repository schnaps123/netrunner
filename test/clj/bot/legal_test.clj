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

(deftest card-title-prompt-bietet-autocomplete-titel
  (do-game
    (new-game)
    ;; erzeugt denselben Prompt wie z.B. "Complete Image": Engine berechnet
    ;; die legalen Titel vor (:autocomplete, game.core.engine ~448)
    (core/resolve-ability state :corp
                          {:prompt "Name a Runner card"
                           :choices {:card-title (fn [_ _ _ _ [target]]
                                                   (= "Sure Gamble" (:title target)))}
                           :effect (fn [_ _ _ _ _])}
                          (get-in @state [:corp :identity]) nil)
    (let [options (legal/prompt-options (view/view-for state :corp) :corp)]
      (is (= [{:type :title :value "Sure Gamble" :label "Sure Gamble"}]
             options)
          "genau die von der Engine erlaubten Titel, als :title-Optionen"))))

(deftest multi-select-bietet-nur-selectable-und-keine-schon-gewaehlten
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Enigma" "Hedge Fund"] :credits 20}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Enigma" "R&D")
    (core/resolve-ability state :corp
                          {:prompt "Choose 2 pieces of ice"
                           :choices {:max 2
                                     :card #(and (:installed %)
                                                 (= "ICE" (:type %)))}
                           :effect (fn [_ _ _ _ _])}
                          (get-in @state [:corp :identity]) nil)
    (let [options (legal/prompt-options (view/view-for state :corp) :corp)
          cards (filter #(= :card (:type %)) options)]
      (is (= #{"Ice Wall" "Enigma"} (set (map :label cards)))
          ":selectable filtert Handkarten/Nicht-Ice weg")
      (is (= ["Done"] (map :label (filter #(= :button (:type %)) options)))
          "Done-Button des Multi-Selects wird angeboten")
      ;; erste Karte anklicken -> darf nicht erneut angeboten werden,
      ;; sonst wäre der zweite Klick nur ein Deselect-Toggle
      (let [ice-wall (:card (first (filter #(= "Ice Wall" (:label %)) cards)))
            eid (:eid (first (get-in @state [:corp :prompt])))]
        (core/process-action "select" state :corp {:card ice-wall :eid eid})
        (let [options' (legal/prompt-options (view/view-for state :corp) :corp)]
          (is (= #{"Enigma"} (set (map :label (filter #(= :card (:type %)) options'))))
              "bereits selektierte Karte verschwindet aus den Optionen"))))))

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
