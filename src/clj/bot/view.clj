(ns bot.view
  "Zensierte Spielsicht für Bots — exakt die Information, die der echte Client
  der jeweiligen Seite über den Diff-Layer bekommt. Bots dürfen NIE den rohen
  @state sehen; view-for ist die einzige erlaubte Quelle."
  (:require
   [game.core.diffs :as diffs]))

(defn view-for
  [state side]
  (diffs/state-summary (diffs/strip-state state) state side))

(defn phase-of
  [view]
  (cond
    (:corp-phase-12 view)   :corp-phase-12
    (:runner-phase-12 view) :runner-phase-12
    (:run view)             (keyword "run" (name (get-in view [:run :phase] :unknown)))
    (:end-turn view)        :between-turns
    :else                   (keyword "action" (name (or (:active-player view) :none)))))
