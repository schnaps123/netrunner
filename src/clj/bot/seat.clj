(ns bot.seat
  "Web-Seat: lässt Bots in Web-Lobbys auf Engine-Zustandsänderungen reagieren.
  Registriert sich beim Systemstart als Hook in web.game (Indirektion über
  Atom, weil dieser Namespace selbst web.game braucht — kein Require-Zyklus).
  Bedenkzeit schläft auf einem eigenen Thread; die State-Mutation läuft
  seriell auf dem Game-Thread der Lobby (wie menschliche Aktionen)."
  (:require
   [bot.game-runner :as runner]
   [bot.log :as blog]
   [cljc.java-time.instant :as inst]
   [game.main :as main]
   [taoensso.timbre :as timbre]
   [web.app-state :as app-state]
   [web.game :as game]
   [web.lobby :as lobby]))

(def ^:dynamic *think-ms*
  "[min max] künstliche Bedenkzeit pro Entscheidung in ms; Tests binden [0 0]."
  [1000 2000])

(defn log-path [gameid]
  (str "logs/bot-games/" gameid ".edn"))

(defn- think! []
  (let [[lo hi] *think-ms*
        ms (+ lo (rand-int (inc (max 0 (- hi lo)))))]
    (when (pos? ms)
      (Thread/sleep (long ms)))))

(defn- bot-lobby [gameid]
  (let [lobby (app-state/get-lobby gameid)]
    (when (:bot-game lobby)
      lobby)))

(defn- bot-to-act
  "[side kind bot] wenn eine Bot-Seite handeln muss, sonst nil.
  Prüft zuerst offene Prompts der Bot-Seiten direkt: beim Spielstart haben
  BEIDE Seiten den Keep/Mulligan-Prompt, und next-actor würde den Prompt des
  Menschen priorisieren — der Bot soll seinen trotzdem sofort beantworten."
  [{:keys [state bots]}]
  (when (and state (not (:winner @state)))
    (or (some (fn [side]
                (when-let [bot (get bots side)]
                  (when (runner/actionable-prompt? state side)
                    [side :prompt bot])))
              [:corp :runner])
        (when-let [[side kind] (runner/next-actor state)]
          (when-let [bot (get bots side)]
            [side kind bot])))))

(defn- concede-bot! [lobby side e]
  (blog/append-event! (log-path (:gameid lobby))
                      {:event :concede
                       :side side
                       :error (ex-message e)
                       :data (ex-data e)})
  (timbre/warn e (str "Bot concedet nach Fehler in " (:gameid lobby) " (" side ")"))
  (game/update-and-send-diffs! main/handle-concede lobby side))

(defn- bot-step!
  "Eine Bot-Entscheidung (läuft auf dem Game-Thread der Lobby).
  Liefert true, wenn der Loop weiterlaufen soll."
  [gameid]
  (let [{:keys [state difficulty] :as lobby} (bot-lobby gameid)]
    (if-let [[side kind bot] (bot-to-act lobby)]
      (try
        (runner/decide-one!
         {:state state :side side :kind kind :bot bot
          :log-path (log-path gameid)
          :log-extra {:difficulty difficulty}
          ;; Anwendung über den Web-Pfad: Diffs an Clients, History fürs Replay
          :apply-fn (fn [_state side' kind' chosen]
                      (game/update-and-send-diffs!
                       runner/apply-choice! lobby side' kind' chosen))})
        ;; Bot-Aktivität zählt als Aktivität (sonst räumt clear-inactive-lobbies
        ;; laufende Bot-Partien ab)
        (swap! app-state/app-state assoc-in [:lobbies gameid :last-update] (inst/now))
        true
        (catch Exception e
          (concede-bot! lobby side e)
          false))
      false)))

(defn- run-loop! [gameid]
  (loop []
    (when-let [lobby (bot-lobby gameid)]
      (when (bot-to-act lobby)
        (think!)
        ;; Mutation seriell zum Menschen auf dem Game-Thread der Lobby
        (when @(lobby/game-thread lobby (bot-step! gameid))
          (recur))))))

(defn notify!
  "Hook: nach jeder State-Änderung aufgerufen (web.game). Startet höchstens
  einen Bot-Loop pro Partie (Guard :bot-thinking?, gesetzt bei Lobby-Erzeugung)."
  [gameid]
  (when-let [{:keys [bot-thinking?]} (bot-lobby gameid)]
    (when (and bot-thinking?
               (compare-and-set! bot-thinking? false true))
      (future
        (try
          (run-loop! gameid)
          (catch Exception e
            (timbre/error e (str "Bot-Loop-Fehler in " gameid)))
          (finally
            (reset! bot-thinking? false)
            ;; Race: Mensch hat gehandelt, während der Guard noch true war —
            ;; dessen notify! lief ins Leere. Einmal nachprüfen.
            (when (some-> (bot-lobby gameid) bot-to-act)
              (notify! gameid)))))))
  nil)

(defn register!
  "Beim Systemstart aufrufen (web.system, ig/init-key :bot/seat)."
  []
  (reset! game/bot-notify-fn notify!)
  :registered)
