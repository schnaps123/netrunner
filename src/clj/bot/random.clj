(ns bot.random
  "Schwierigkeitsgrad 0: wählt uniform zufällig aus den legalen Optionen."
  (:require
   [bot.protocol :as bp]))

(defn- pick [^java.util.Random rng coll]
  (let [v (vec coll)]
    (nth v (.nextInt rng (count v)))))

(defrecord RandomBot [^java.util.Random rng]
  bp/Bot
  (decide [_ _view legal-actions]
    {:action (pick rng legal-actions)
     :reason (str "random-bot: uniform zufällig, 1 von "
                  (count legal-actions) " legalen Aktionen")})
  (on-prompt [_ _view _prompt options]
    {:option (pick rng options)
     :reason (str "random-bot: uniform zufällig, 1 von "
                  (count options) " Prompt-Optionen")}))

(defn random-bot
  "Baut einen seedbaren Random-Bot (deterministisch pro Seed)."
  [seed]
  (->RandomBot (java.util.Random. (long seed))))
