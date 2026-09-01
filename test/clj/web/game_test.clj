(ns web.game-test
  (:require
   [bot.cards :as bot-cards]
   [clojure.test :refer :all]
   [web.app-state :as app-state]
   [web.game :as game]
   [web.lobby :as lobby]
   [web.stats :as stats]
   [web.ws :as ws]))

(use-fixtures :once (fn [f] (bot-cards/load-all-cards!) (f)))

(use-fixtures :each (fn [f]
                      (reset! app-state/app-state {:lobbies {} :users {}})
                      (reset! game/bot-notify-fn (fn [_] nil))
                      (f)))

(defn- wait-until
  "Pollt bis pred truthy oder timeout-ms um; liefert (pred) oder nil."
  [pred timeout-ms]
  (loop [waited 0]
    (or (pred)
        (when (< waited timeout-ms)
          (Thread/sleep 50)
          (recur (+ waited 50))))))

(defn- stub-io [f]
  (with-redefs [ws/chsk-send! (fn [& _] nil)
                stats/game-started (fn [& _] nil)
                stats/fetch-replay-record (fn [& _] nil)
                lobby/send-lobby-state (fn [& _] nil)
                lobby/broadcast-lobby-list (fn [& _] nil)]
    (f)))

(defn- make-started-vs-bot-lobby!
  "Erzeugt eine vs-bot-Lobby (Mensch = Corp), setzt das Menschen-Deck und
  startet sie über start-game!. Liefert die gameid."
  []
  (let [l (lobby/create-new-lobby
           {:uid "u1" :user {:username "david"}
            :options {:bot-game "vs-bot" :side "Corp" :difficulty "random"
                      :format "system-gateway" :title "t" :room "casual"}})
        gameid (:gameid l)
        human-deck (:deck (bot-cards/player-entry "Corp" bot-cards/gateway-corp))]
    (swap! app-state/app-state assoc-in [:lobbies gameid]
           (assoc-in l [:players 0 :deck] human-deck))
    (stub-io #(game/start-game! nil gameid))
    gameid))

(deftest start-game!-startet-ohne-first-player-check
  (let [gameid (make-started-vs-bot-lobby!)
        lobby? (app-state/get-lobby gameid)]
    (is (:started lobby?))
    (is (some? (:state lobby?)))
    (is (some? @(:state lobby?)))))

(deftest start-game!-ruft-bot-hook
  (let [notified (atom [])]
    (reset! game/bot-notify-fn (fn [gameid] (swap! notified conj gameid)))
    (let [gameid (make-started-vs-bot-lobby!)]
      (is (= [gameid] @notified) "Hook genau einmal nach dem Start"))))

(deftest notify-bots!-default-no-op
  (is (nil? (game/notify-bots! "irgendeine-id"))))

(deftest game--say-ruft-bot-hook
  ;; Bug: /undo-turn (konsenspflichtig) hing für immer, weil :game/say im
  ;; Gegensatz zu :game/action und :game/rejoin den Bot-Hook nie rief — der
  ;; Bot bekam nie mit, dass eine Undo-Anfrage auf seine Zustimmung wartet.
  (let [notified (atom [])]
    (reset! game/bot-notify-fn (fn [gameid] (swap! notified conj gameid)))
    (let [gameid (make-started-vs-bot-lobby!)]
      (reset! notified [])
      (stub-io
       #(ws/-msg-handler {:id :game/say
                          :ring-req {:user {:username "david"}}
                          :uid "u1"
                          :?data {:gameid gameid :msg "hello"}
                          :timestamp 0}))
      ;; :game/say läuft wie :game/action über lobby/game-thread (asynchron,
      ;; eigener Pool) — pollen statt sofort zu prüfen, sonst räumt die
      ;; Assertion vor dem verzögerten Hook-Aufruf ab (und dessen später
      ;; ankommender Write würde den NÄCHSTEN Test verunreinigen).
      (is (wait-until #(= [gameid] @notified) 5000)
          "Bot-Hook wird auch nach :game/say gerufen"))))
