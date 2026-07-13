(ns bot.cards
  "Kartendaten + Demo-Decks für Headless-Spiele (ohne Mongo/Webserver).
  Loading-Muster analog game.test-framework/load-all-cards."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [game.utils :refer [server-card]]
   [jinteki.cards :refer [all-cards]]))

(defn load-all-cards!
  []
  (when (empty? @all-cards)
    (->> (io/file "data/cards.edn")
         slurp
         edn/read-string
         (map (juxt :title identity))
         (into {})
         (reset! all-cards))
    (require '[game.cards.agendas]
             '[game.cards.assets]
             '[game.cards.basic]
             '[game.cards.events]
             '[game.cards.hardware]
             '[game.cards.ice]
             '[game.cards.identities]
             '[game.cards.operations]
             '[game.cards.programs]
             '[game.cards.resources]
             '[game.cards.upgrades]))
  nil)

(def demo-corp
  {:identity "Haas-Bioroid: Engineering the Future"
   :cards [["Hedge Fund" 3] ["PAD Campaign" 3] ["Ice Wall" 3]
           ["Enigma" 3] ["Priority Requisition" 3]]})

(def demo-runner
  {:identity "Kate \"Mac\" McCaffrey: Digital Tinker"
   :cards [["Sure Gamble" 3] ["Diesel" 3] ["Dirty Laundry" 3]
           ["Corroder" 3] ["Gordian Blade" 3]]})

(defn- deck-entry [title qty]
  (let [card (server-card title)]
    (when-not card
      (throw (ex-info (str "Karte nicht in all-cards (lein fetch gelaufen?): " title)
                      {:title title})))
    {:card card :qty qty}))

(defn player-entry
  [side {:keys [identity cards]}]
  {:side side
   :user {:username (str "Bot-" side)}
   :deck {:identity (server-card identity)
          :cards (mapv #(apply deck-entry %) cards)}})
