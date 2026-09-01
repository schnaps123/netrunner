(ns bot.heuristic-corp-test
  ;; game.core/game.core.card werden schon hier vollstaendig requiret (nicht
  ;; erst in spaeteren Tasks) -- core/gain, get-counters und rezzed? werden
  ;; ab Task 6 gebraucht, bot.game-runner/decide-one! ebenfalls.
  (:require
   [bot.game-runner :as game-runner]
   [bot.heuristic-corp :as hc]
   [bot.legal :as legal]
   [bot.protocol :as bp]
   [bot.view :as view]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.core.card :refer [get-counters rezzed?]]
   [game.test-framework :refer :all]))

(deftest decide-delegiert-noch-vollstaendig-an-random-v1-skeleton
  (do-game
    (new-game)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (some #{(:action decision)} actions))
      (is (str/includes? (:reason decision) "random-bot")))))

(deftest mulligan-ohne-ice-und-econ
  (do-game
    (new-game {:corp {:hand (repeat 5 "Hostile Takeover")}
               :options {:dont-start-game true}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Mulligan" (:label (:option decision))))
      (is (str/includes? (:reason decision) "Ice=0"))
      (is (str/includes? (:reason decision) "Econ=0")))))

(deftest keep-mit-ice-in-hand
  (do-game
    (new-game {:corp {:hand (cons "Ice Wall" (repeat 4 "Hostile Takeover"))}
               :options {:dont-start-game true}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Keep" (:label (:option decision)))))))

(deftest keep-mit-econ-in-hand
  (do-game
    (new-game {:corp {:hand (cons "Hedge Fund" (repeat 4 "Hostile Takeover"))}
               :options {:dont-start-game true}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Keep" (:label (:option decision)))))))

(deftest sonstiger-prompt-wird-an-random-delegiert
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Enigma" "Hedge Fund"] :credits 20}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Enigma" "R&D")
    (core/resolve-ability
     state :corp
     {:prompt "Choose 2 pieces of ice"
      :choices {:max 2 :card #(and (:installed %) (= "ICE" (:type %)))}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (some #{(:option decision)} options)))))

(deftest regel-1-zentralserver-icen-prioritaet-hq
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-ice state :hq))))
      (is (= "Ice Wall" (:title (get-ice state :hq 0)))))))

(deftest regel-2-scoring-remote-aufbauen-nach-allen-zentralservern
  (do-game
    (new-game {:corp {:hand (repeat 4 "Ice Wall") :credits 20}})
    (core/gain state :corp :click 5)
    (let [bot (hc/heuristic-corp-bot 1)]
      (dotimes [_ 3]
        (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
        (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot}))
      (is (= 1 (count (get-ice state :hq))))
      (is (= 1 (count (get-ice state :rd))))
      (is (= 1 (count (get-ice state :archives))))
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-ice state :remote1)))
          "vierte Ice-Karte -> neuer Remote, alle Zentralserver schon geict"))))

(deftest regel-2-weiteres-ice-in-unsicheren-scoring-remote
  ;; Alle Zentralserver muessen VOR dem bot-Aufruf schon geict sein, sonst
  ;; greift Regel 1 (Zentralserver icen) zuerst und der Test isoliert nicht
  ;; Regel 2 -- deshalb HQ/R&D/Archives direkt (nicht ueber den Bot)
  ;; besetzen. Scoring-Remote hat danach 1 (schwaches) Ice, Runner hat viele
  ;; Credits -> unsicher -> Regel 2 legt WEITERES Ice nach statt eine
  ;; Agenda zu jammen.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Ice Wall" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Ice Wall" "R&D")
    (play-from-hand state :corp "Ice Wall" "Archives")
    (play-from-hand state :corp "Ice Wall" "New remote")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 2 (count (get-ice state :remote1)))
          "unsicherer Remote (Runner hat 20 Credits) -> zweites Ice statt Agenda-Install")
      (is (empty? (get-content state :remote1))))))
