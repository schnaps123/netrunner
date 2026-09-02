(ns bot.roster
  "Difficulty-Registry + Bot-Player-Konstruktion für Web-Lobbys.
  Bewusst OHNE web.*-Abhängigkeiten: web.lobby konsumiert diesen Namespace,
  die Web-Laufzeit (bot.seat) liegt eine Schicht darüber.
  Seiten-Verfügbarkeit (welcher Grad für :corp/:runner existiert) kommt aus
  bot.difficulties (cljc, geteilt mit der Lobby-UI) — hier stehen nur die
  JVM-seitigen Factories (java.util.Random, konkrete Bot-Konstruktoren)."
  (:require
   [bot.cards :as cards]
   [bot.difficulties :as difficulties]
   [bot.heuristic-corp :as heuristic-corp]
   [bot.random :as random]
   [clojure.string :as str]))

(def ^:private bot-factories
  "difficulty -> side -> Factory (0-arity, liefert bot.protocol/Bot).
  Seiten, für die ein Grad (noch) keinen Bot hat, fehlen als Key —
  bot.difficulties/registry ist die Quelle der Wahrheit dafür, welche
  Kombination als 'verfügbar' gilt (siehe available-for-side?)."
  {"random" {:corp #(random/random-bot (.nextLong (java.util.Random.)))
             :runner #(random/random-bot (.nextLong (java.util.Random.)))}
   "heuristic" {:corp #(heuristic-corp/heuristic-corp-bot (.nextLong (java.util.Random.)))}})

(defn difficulty? [d]
  (difficulties/known? d))

(defn available-for-side? [difficulty side]
  (difficulties/available-for-side? difficulty side))

(defn make-bot
  "Baut einen Bot für `difficulty` auf `side` (Default :corp, für bestehende
  seitenlose Aufrufer). Wirft ex-info, wenn der Grad unbekannt ist ODER für
  diese Seite (noch) keinen Bot hat (z.B. \"heuristic\" + :runner)."
  ([difficulty] (make-bot difficulty :corp))
  ([difficulty side]
   (if-let [f (get-in bot-factories [difficulty side])]
     (f)
     (throw (ex-info "Schwierigkeitsgrad nicht für diese Seite verfügbar"
                     {:difficulty difficulty :side side})))))

(defn bot-username [difficulty]
  (str "Bot (" (str/capitalize difficulty) ")"))

(defn bot-player
  "Player-Map für eine Web-Lobby (braucht geladene all-cards).
  Form kompatibel zu web.lobby-Playern UND game.core.set-up/init-game."
  [side difficulty]
  (-> (cards/player-entry side (cards/bot-deck-for side))
      (assoc :uid nil :bot true)
      (assoc-in [:user :username] (bot-username difficulty))))
