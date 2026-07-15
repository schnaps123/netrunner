(ns bot.game-runner-test
  (:require
   [bot.cards :as cards]
   [bot.game-runner :as gr]
   [bot.random :as random]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.core.set-up :as setup]
   [game.test-framework :refer :all]))

(deftest next-actor-aktionsphase
  (do-game
    (new-game)
    (is (= [:corp :action] (gr/next-actor state)))
    (take-credits state :corp)
    (is (= [:runner :action] (gr/next-actor state)))))

(deftest next-actor-mulligan-prompt
  (do-game
    ;; :dont-start-game muss laut game.test-framework/make-decks unter :options
    ;; verschachtelt sein (destructured als {:keys [corp runner options]}) —
    ;; sonst greift new-game der auto-"Keep"-Pfad und es gibt gar keinen Prompt mehr.
    (new-game {:options {:dont-start-game true}})
    (is (= [:corp :prompt] (gr/next-actor state))
        "Mulligan-Prompt der Corp kommt zuerst")))

(deftest next-actor-movement-runner-continued-zuerst
  ;; Smoke-Test-Bug (vs-Bot, Mensch = Runner): Red-Team-Ability-Run auf R&D.
  ;; game.core.runs/continue speichert in [:run :no-action] die SEITE, die
  ;; gepasst hat (:corp/:runner), kein Boolean. Continued der Mensch in einem
  ;; Timing-Fenster ZUERST, muss danach die Corp (Bot) handeln — ein
  ;; bool-interpretiertes no-action schickte next-actor zurück zum Runner,
  ;; der Bot wurde nie aktiv und der Run hing (Breach ohne Wirkung).
  (do-game
    (new-game {:corp {:deck [(qty "Hedge Fund" 10)] :hand []}
               :runner {:hand ["Red Team"]}})
    (take-credits state :corp)
    (play-from-hand state :runner "Red Team")
    (card-ability state :runner (get-resource state 0) 0)
    (click-prompt state :runner "R&D")
    (is (:run @state) "Ability-Run läuft")
    (is (= :initiation (get-in @state [:run :phase])))
    (is (= [:corp :run] (gr/next-actor state))
        "Initiation: niemand hat gepasst — Corp zuerst")
    ;; Corp (Bot) passt zuerst — "has no further action"
    (core/process-action "continue" state :corp nil)
    (is (= [:runner :run] (gr/next-actor state))
        "Corp hat gepasst — Runner ist dran")
    (core/process-action "continue" state :runner nil)
    (is (= :movement (get-in @state [:run :phase])) "R&D ohne Ice: direkt Movement")
    ;; Movement: der MENSCH continued zuerst — "will continue the run"
    (core/process-action "continue" state :runner nil)
    (is (= :runner (get-in @state [:run :no-action]))
        "Engine speichert die passende Seite, kein Boolean")
    (is (= [:corp :run] (gr/next-actor state))
        "Runner hat gepasst — jetzt muss die Corp (Bot) handeln")
    ;; Corp continued — Run erreicht den Breach, kein Hänger
    (core/process-action "continue" state :corp nil)
    (is (= :success (get-in @state [:run :phase])))
    (click-prompt state :runner "No action")
    (is (nil? (:run @state)) "Run sauber beendet")))

(deftest random-vs-random-komplette-partie
  (let [log-file (io/file (System/getProperty "java.io.tmpdir")
                          (str "bot-game-" (System/currentTimeMillis) ".edn"))
        result (gr/run-game {:corp-bot (random/random-bot 1)
                             :runner-bot (random/random-bot 2)
                             :log-path (.getPath log-file)
                             :max-steps 3000})]
    (try
      (testing "Partie terminiert (Sieger oder Step-Cap)"
        (is (map? result))
        (is (<= (:steps result) 3000))
        (is (or (:completed? result) (= 3000 (:steps result)))))
      (testing "Log: eine valide EDN-Zeile pro Entscheidung, mit Pflicht-Keys"
        (let [lines (str/split-lines (slurp log-file))]
          (is (pos? (count lines)))
          (doseq [line (take 50 lines)]
            (let [e (edn/read-string line)]
              (is (every? #(contains? e %)
                          [:turn :phase :side :kind :options :choice :reason :no-op])
                  (str "Zeile unvollständig: " line))
              (is (every? string? (:options e)) "Optionen sind Labels, keine Karten-Maps")))))
      (finally (.delete log-file)))))

(deftest decide-one!-nutzt-apply-fn-und-log-extra
  (cards/load-all-cards!)
  (let [state (setup/init-game
               {:gameid 1 :format "casual"
                :players [(cards/player-entry "Corp" cards/gateway-corp)
                          (cards/player-entry "Runner" cards/gateway-runner)]})
        applied (atom [])
        f (java.io.File/createTempFile "decide-one" ".edn")
        bot (random/random-bot 1)
        ;; Nach init-game hat jede Seite den Keep/Mulligan-Prompt
        [side kind] (gr/next-actor state)]
    (gr/decide-one!
     {:state state :side side :kind kind :bot bot
      :log-path (.getPath f)
      :log-extra {:difficulty "random"}
      :apply-fn (fn [state side kind chosen]
                  (swap! applied conj [side kind (:label chosen)])
                  (gr/apply-choice! state side kind chosen))})
    (is (= 1 (count @applied)) "apply-fn genau einmal aufgerufen (kein No-Op bei Mulligan)")
    (let [entry (edn/read-string (first (str/split-lines (slurp f))))]
      (is (= "random" (:difficulty entry)) "log-extra landet im Log-Eintrag"))
    (.delete f)))
