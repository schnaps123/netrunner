(ns bot.roster
  "Difficulty-Registry + Bot-Player-Konstruktion für Web-Lobbys.
  Bewusst OHNE web.*-Abhängigkeiten: web.lobby konsumiert diesen Namespace,
  die Web-Laufzeit (bot.seat) liegt eine Schicht darüber."
  (:require
   [bot.cards :as cards]
   [bot.random :as random]
   [clojure.string :as str]))

(def difficulties
  "Registry Schwierigkeitsgrad -> Factory (0-arity, liefert bot.protocol/Bot)."
  {"random" #(random/random-bot (.nextLong (java.util.Random.)))})

(defn difficulty? [d]
  (contains? difficulties d))

(defn make-bot [difficulty]
  (if-let [f (get difficulties difficulty)]
    (f)
    (throw (ex-info "Unbekannter Schwierigkeitsgrad" {:difficulty difficulty}))))

(defn bot-username [difficulty]
  (str "Bot (" (str/capitalize difficulty) ")"))

(defn bot-player
  "Player-Map für eine Web-Lobby (braucht geladene all-cards).
  Form kompatibel zu web.lobby-Playern UND game.core.set-up/init-game."
  [side difficulty]
  (-> (cards/player-entry side (cards/bot-deck-for side))
      (assoc :uid nil :bot true)
      (assoc-in [:user :username] (bot-username difficulty))))
