(ns bot.log
  "Decision-Log: eine EDN-Zeile pro Bot-Entscheidung (Projektregel: Pflicht).
  options/choice sind Labels (Strings), nie rohe Karten-Maps."
  (:require
   [clojure.java.io :as io]))

(defn append-event!
  "Schreibt eine beliebige Map als eine EDN-Zeile (z.B. Fehler/Concede)."
  [path m]
  (io/make-parents (io/file path))
  (spit path (str (pr-str m) "\n") :append true)
  nil)

(defn decision-entry
  [{:keys [turn phase side kind options choice reason no-op difficulty scoring-gap]}]
  (cond-> {:turn turn
           :phase phase
           :side side
           :kind kind
           :options (vec options)
           :choice choice
           :reason reason
           :no-op (boolean no-op)}
    difficulty (assoc :difficulty difficulty)
    scoring-gap (assoc :scoring-gap scoring-gap)))

(defn append-decision!
  [path entry]
  (append-event! path (decision-entry entry)))
