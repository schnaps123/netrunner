(ns bot.game-runner-test
  (:require
   [bot.game-runner :as gr]
   [bot.random :as random]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
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
