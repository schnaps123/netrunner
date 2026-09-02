(ns bot.sim-test
  (:require
   [bot.protocol :as bp]
   [bot.sim :as sim]
   [clojure.test :refer :all]
   [clojure.tools.cli :refer [parse-opts]]))

(deftest bot-for-seitenbewusst
  (is (satisfies? bp/Bot (#'sim/bot-for "heuristic" :corp 1)))
  (is (satisfies? bp/Bot (#'sim/bot-for "random" :runner 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nicht für diese Seite verfügbar"
                        (#'sim/bot-for "heuristic" :runner 1))
      "Kein Heuristik-Bot fuer die Runner-Seite -> klarer Fehler statt stillschweigend falscher Bot"))

(deftest cli-validierung-seitenbewusst
  (testing "--corp-bot heuristic ist gueltig"
    (let [{:keys [errors]} (parse-opts ["--corp-bot" "heuristic"] sim/cli-options)]
      (is (empty? errors))))
  (testing "--runner-bot heuristic ist UNGUELTIG (kein Runner-Heuristik-Bot)"
    (let [{:keys [errors]} (parse-opts ["--runner-bot" "heuristic"] sim/cli-options)]
      (is (seq errors))))
  (testing "--runner-bot random bleibt gueltig"
    (let [{:keys [errors]} (parse-opts ["--runner-bot" "random"] sim/cli-options)]
      (is (empty? errors)))))
