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

(def gateway-corp
  "Offizielles System-Gateway-Corp-Starterdeck (34 Karten, Null Signal Games,
  NRDB-Decklist 0665c5c7-f7f1-4674-86b5-ca4e371888f2)."
  {:identity "The Syndicate: Profit over Principle"
   :cards [["Offworld Office" 3] ["Send a Message" 2] ["Superconducting Hub" 2]
           ["Nico Campaign" 2] ["Regolith Mining License" 2] ["Urtica Cipher" 2]
           ["Manegarm Skunkworks" 1]
           ["Government Subsidy" 2] ["Hedge Fund" 3] ["Seamless Launch" 2]
           ["Brân 1.0" 2] ["Diviner" 2] ["Karunā" 2] ["Palisade" 3]
           ["Tithe" 2] ["Whitespace" 2]]})

(def gateway-runner
  "Offizielles System-Gateway-Runner-Starterdeck (30 Karten, Null Signal Games,
  NRDB-Decklist d71397b7-af7b-475c-8984-18360a64f6ee)."
  {:identity "The Catalyst: Convention Breaker"
   :cards [["Creative Commission" 2] ["Jailbreak" 3] ["Overclock" 2]
           ["Sure Gamble" 3] ["Tread Lightly" 2] ["VRcation" 2]
           ["Docklands Pass" 1] ["Pennyshaver" 1]
           ["Carmen" 2] ["Cleaver" 2] ["Mayfly" 2] ["Unity" 2]
           ["Red Team" 1] ["Smartware Distributor" 2]
           ["Telework Contract" 2] ["Verbal Plasticity" 1]]})

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
