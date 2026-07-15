(ns bot.run-windows-test
  "Fenster-Matrix für gemischte Mensch/Bot-Partien: jedes Run-Timing-Fenster
  (Initiation, Approach/Rez, Encounter/Subs, Movement, Access) in beide
  Richtungen. Invarianten:
  - Bot WARTET, solange der Mensch Priority hat (Runner hat sie in jedem
    Fenster zuerst; die Corp erst nach seinem Pass).
  - Bot HANDELT, sobald der Mensch abgegeben hat.
  - Hat der Runner im Encounter gepasst und es gibt pending Subs, ist
    \"fire unbroken subroutines\" die EINZIGE Corp-Option (Zugzwang, kein
    Münzwurf gegen continue — Smoke-Test-Fund 3, Karunā).
  Der Mensch wird über game.test-framework/process-action geskriptet; die
  Bot-Seite läuft über die Produktionskette seat/bot-to-act -> next-actor ->
  legal/run-actions."
  (:require
   [bot.legal :as legal]
   [bot.random :as bot-random]
   [bot.seat]
   [bot.view :as view]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.core.card :refer [rezzed?]]
   [game.test-framework :refer :all]))

(defn- lobby-with-bot [state side]
  {:state state :bots {side (bot-random/random-bot 1)}})

(defn- bot-waits? [state bot-side]
  (nil? (#'bot.seat/bot-to-act (lobby-with-bot state bot-side))))

(defn- bot-acts? [state bot-side]
  (when-let [[side kind _] (#'bot.seat/bot-to-act (lobby-with-bot state bot-side))]
    [side kind]))

(defn- run-commands [state side]
  (->> (legal/run-actions (view/view-for state side) side)
       (map :command)
       set))

;; ---------------------------------------------------------------------------
;; Richtung A: Mensch = Runner, Bot = Corp
;; ---------------------------------------------------------------------------

(deftest corp-bot-wartet-in-jedem-fenster-bis-der-mensch-passt
  (do-game
    (new-game {:corp {:hand ["Whitespace" "Hedge Fund"] :credits 10}
               :runner {:hand ["Mayfly"] :credits 10}})
    (play-from-hand state :corp "Whitespace" "HQ")
    (take-credits state :corp)
    (play-from-hand state :runner "Mayfly")
    (run-on state "HQ" {:wait-at-initiation true})
    ;; Initiation
    (is (= :initiation (get-in @state [:run :phase])))
    (is (bot-waits? state :corp) "Initiation: Mensch hat Priority, Bot wartet")
    (core/process-action "continue" state :runner nil)
    (is (= [:corp :run] (bot-acts? state :corp)) "Initiation: Mensch passte, Bot handelt")
    (core/process-action "continue" state :corp nil)
    ;; Approach (Rez-Fenster)
    (is (= :approach-ice (get-in @state [:run :phase])))
    (is (bot-waits? state :corp) "Approach: Bot wartet vor dem Runner-Pass")
    (core/process-action "continue" state :runner nil)
    (is (= [:corp :run] (bot-acts? state :corp)) "Approach: Bot darf rezzen")
    (is (contains? (run-commands state :corp) "rez") "Rez wird angeboten")
    (core/process-action "rez" state :corp {:card (get-ice state :hq 0)})
    (is (rezzed? (get-ice state :hq 0)))
    (is (= [:corp :run] (bot-acts? state :corp)) "nach dem Rez schließt die Corp das Fenster")
    (core/process-action "continue" state :corp nil)
    ;; Encounter (Paid-Ability-Fenster des Menschen)
    (is (= :encounter-ice (get-in @state [:run :phase])))
    (is (bot-waits? state :corp) "Encounter: Fenster gehört dem Menschen, Bot wartet")
    (is (not (contains? (run-commands state :corp) "unbroken-subroutines"))
        "kein Subs-Feuern, solange der Mensch sein Fenster hat")
    (auto-pump-and-break state (get-program state 0))
    (is (every? :broken (:subroutines (get-ice state :hq 0)))
        "Mensch konnte im Fenster brechen")
    (core/process-action "continue" state :runner nil)
    (is (= [:corp :run] (bot-acts? state :corp)) "Encounter: Mensch passte, Bot handelt")
    (is (= #{"continue"} (run-commands state :corp))
        "alles gebrochen: nur continue")
    (core/process-action "continue" state :corp nil)
    ;; Movement
    (is (= :movement (get-in @state [:run :phase])))
    (is (bot-waits? state :corp) "Movement: Bot wartet vor dem Runner-Pass")
    (core/process-action "continue" state :runner nil)
    (is (= [:corp :run] (bot-acts? state :corp)) "Movement: Mensch passte, Bot handelt")
    (core/process-action "continue" state :corp nil)
    ;; Access
    (is (= :success (get-in @state [:run :phase])))
    (is (bot-waits? state :corp)
        "Access: Runner-Prompt offen, Corp hat nur einen :waiting-Prompt")))

(deftest corp-bot-muss-ungebrochene-subs-feuern
  ;; Smoke-Test-Fund 3 (Karunā): Mensch klickt \"let subroutines fire\",
  ;; der Bot würfelte zwischen continue und fire — continue ließ das Ice
  ;; wirkungslos passieren. Pending Subs nach Runner-Pass = Zugzwang.
  (do-game
    (new-game {:corp {:hand ["Whitespace"] :credits 10}
               :runner {:credits 5}})
    (play-from-hand state :corp "Whitespace" "HQ")
    (take-credits state :corp)
    (run-on state "HQ")
    (core/process-action "continue" state :runner nil)
    (core/process-action "rez" state :corp {:card (get-ice state :hq 0)})
    (core/process-action "continue" state :corp nil)
    (is (= :encounter-ice (get-in @state [:run :phase])))
    ;; Mensch bricht nichts und passt
    (core/process-action "continue" state :runner nil)
    (is (= [:corp :run] (bot-acts? state :corp)))
    (is (= #{"unbroken-subroutines"} (run-commands state :corp))
        "pending Subs nach Runner-Pass: Feuern ist die EINZIGE Option")
    (core/process-action "unbroken-subroutines" state :corp {:card (get-ice state :hq 0)})
    (is (= 2 (get-in @state [:runner :credit])) "Sub 1 wirkt: 3 Credits weg")
    (is (nil? (:run @state)) "Sub 2 wirkt: End the run")))

;; ---------------------------------------------------------------------------
;; Richtung B: Mensch = Corp, Bot = Runner
;; ---------------------------------------------------------------------------

(deftest runner-bot-handelt-zuerst-und-wartet-nach-eigenem-pass
  (do-game
    (new-game {:corp {:hand ["Whitespace"] :credits 10}
               :runner {:hand ["Mayfly"] :credits 10}})
    (play-from-hand state :corp "Whitespace" "HQ")
    (take-credits state :corp)
    (play-from-hand state :runner "Mayfly")
    (run-on state "HQ" {:wait-at-initiation true})
    ;; Initiation: Runner-Bot hat Priority und handelt sofort
    (is (= [:runner :run] (bot-acts? state :runner))
        "Initiation: Runner-Bot handelt, ohne auf den Mensch-Corp zu warten")
    (core/process-action "continue" state :runner nil)
    (is (bot-waits? state :runner) "Runner-Bot passte — Mensch-Corp ist dran")
    (core/process-action "continue" state :corp nil)
    ;; Approach
    (is (= :approach-ice (get-in @state [:run :phase])))
    (is (= [:runner :run] (bot-acts? state :runner)))
    (core/process-action "continue" state :runner nil)
    (is (bot-waits? state :runner) "Rez-Fenster des Mensch-Corp: Bot wartet")
    (core/process-action "rez" state :corp {:card (get-ice state :hq 0)})
    (core/process-action "continue" state :corp nil)
    ;; Encounter: Runner-Bot bekommt sein Break-Fenster
    (is (= :encounter-ice (get-in @state [:run :phase])))
    (is (= [:runner :run] (bot-acts? state :runner)))
    (is (contains? (run-commands state :runner) "ability")
        "Breaker-Ability wird im Encounter angeboten")
    (core/process-action "continue" state :runner nil)
    (is (bot-waits? state :runner)
        "Runner-Bot passte — Feuer-Entscheidung liegt beim Mensch-Corp")
    (core/process-action "unbroken-subroutines" state :corp {:card (get-ice state :hq 0)})
    (is (= 6 (get-in @state [:runner :credit])) "Whitespace: 3 Credits weg (9 -> 6)")
    (is (nil? (:run @state)) "unter 7 Credits: End the run beendet den Run")))
