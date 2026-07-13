(ns bot.log
  "Decision-Log: eine EDN-Zeile pro Bot-Entscheidung (Projektregel: Pflicht).
  options/choice sind Labels (Strings), nie rohe Karten-Maps."
  (:require
   [clojure.java.io :as io]))

(defn decision-entry
  [{:keys [turn phase side kind options choice reason no-op]}]
  {:turn turn
   :phase phase
   :side side
   :kind kind
   :options (vec options)
   :choice choice
   :reason reason
   :no-op (boolean no-op)})

(defn append-decision!
  [path entry]
  (io/make-parents (io/file path))
  (spit path (str (pr-str (decision-entry entry)) "\n") :append true)
  nil)
