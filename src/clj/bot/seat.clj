(ns bot.seat
  "Web-Seat: lässt Bots in Web-Lobbys auf Engine-Zustandsänderungen reagieren.
  Registriert sich beim Systemstart als Hook in web.game (Indirektion über
  Atom, weil dieser Namespace selbst web.game braucht — kein Require-Zyklus).
  Bedenkzeit schläft auf einem eigenen Thread; die State-Mutation läuft
  seriell auf dem Game-Thread der Lobby (wie menschliche Aktionen)."
  (:require
   [bot.game-runner :as runner]
   [bot.log :as blog]
   [bot.roster :as roster]
   [cljc.java-time.instant :as inst]
   [clojure.stacktrace :as stacktrace]
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
  Prüft zuerst offene Prompts der Bot-Seiten direkt: next-actor priorisiert
  immer :corp vor :runner (siehe next-actor unten). Bei Psi-Games
  (game.core.psi/psi-game) bekommen aber BEIDE Seiten gleichzeitig einen
  echten :psi-Prompt (kein :waiting/:run) — ohne diesen direkten Check
  müsste ein Runner-Bot warten, bis next-actor irgendwann zu ihm kommt,
  obwohl sein eigener Prompt längst beantwortbar ist."
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

(defn- pending-undo-consent
  "[side bot] wenn eine Undo-Turn-Anfrage der Gegenseite auf Zustimmung des
  Bots wartet, sonst nil. /undo-turn ist konsenspflichtig
  (game.core.commands/command-undo-turn setzt [side :undo-turn] und resettet
  erst, wenn beide Seiten zugestimmt haben) — der Bot chattet nie von sich
  aus, würde also ohne diesen Check jede Undo-Anfrage für immer offen lassen."
  [{:keys [state bots]}]
  (when (and state (not (:winner @state)))
    (some (fn [bot-side]
            (when-let [bot (get bots bot-side)]
              (let [human-side (if (= bot-side :corp) :runner :corp)]
                (when (and (get-in @state [human-side :undo-turn])
                           (not (get-in @state [bot-side :undo-turn])))
                  [bot-side bot]))))
          [:corp :runner])))

(defn- bot-work?
  "Gibt es für den Bot gerade etwas zu tun (Undo-Konsens ODER Spielzug)?
  Von run-loop!/notify! genutzt, um zu entscheiden, ob weiterzulaufen ist."
  [lobby]
  (or (pending-undo-consent lobby) (bot-to-act lobby)))

(defn- consent-to-undo!
  "Bot stimmt einer Undo-Turn-Anfrage zu — über denselben Kommando-Pfad wie
  ein menschlicher Chat-Befehl (game.main/handle-say -> command-parser),
  damit die Engine alleinige Regelinstanz bleibt. v1 bewertet die Anfrage
  nicht inhaltlich: Ablehnen würde eine sinnvolle Heuristik brauchen, die es
  noch nicht gibt, und ein Bot, der nie zustimmt, wäre für den Menschen
  genauso kaputt wie einer, der nie antwortet."
  [gameid side]
  (let [{:keys [difficulty] :as lobby} (bot-lobby gameid)
        bot-user {:username (roster/bot-username difficulty)}]
    (game/update-and-send-diffs! main/handle-say lobby side bot-user "/undo-turn")
    (blog/append-event! (log-path gameid)
                        {:event :undo-turn-consent
                         :side side
                         :difficulty difficulty
                         :reason "Automatische Zustimmung: v1-Bot bewertet Undo-Anfragen nicht inhaltlich; ohne Zustimmung bliebe die Konsens-Anfrage für immer offen, weil der Bot nie selbst chattet."})))

(defn- concede-bot! [lobby side e]
  (blog/append-event! (log-path (:gameid lobby))
                      {:event :concede
                       :side side
                       :error (ex-message e)
                       :data (ex-data e)
                       ;; F4 (Spec): Fehler + Stacktrace ins Decision-Log
                       :stacktrace (with-out-str (stacktrace/print-stack-trace e))})
  (timbre/warn e (str "Bot concedet nach Fehler in " (:gameid lobby) " (" side ")"))
  (game/update-and-send-diffs! main/handle-concede lobby side))

(defn- bump-last-update
  "Guarded last-update-Bump: no-op, wenn `gameid` nicht (mehr) in `lobbies`
  steckt. Verhindert einen Geister-Eintrag {gameid {:last-update ...}}, den
  ein plain assoc-in erzeugen würde, falls ein paralleles close-lobby! die
  Lobby zwischen bot-to-act und diesem Bump bereits entfernt hat."
  [lobbies gameid]
  (if (contains? lobbies gameid)
    (assoc-in lobbies [gameid :last-update] (inst/now))
    lobbies))

(defn- bot-step!
  "Ein Bot-Zug (läuft auf dem Game-Thread der Lobby): entweder einer
  Undo-Turn-Anfrage zustimmen (Vorrang, siehe pending-undo-consent) oder eine
  reguläre Spielentscheidung. Liefert true, wenn der Loop weiterlaufen soll."
  [gameid]
  (let [{:keys [state difficulty] :as lobby} (bot-lobby gameid)
        undo (pending-undo-consent lobby)
        act (when-not undo (bot-to-act lobby))]
    (cond
      undo
      (let [[side _bot] undo]
        (try
          (consent-to-undo! gameid side)
          (swap! app-state/app-state update :lobbies bump-last-update gameid)
          true
          (catch Exception e
            (concede-bot! lobby side e)
            false)))

      act
      (let [[side kind bot] act]
        (try
          (runner/decide-one!
           {:state state :side side :kind kind :bot bot
            :log-path (log-path gameid)
            :log-extra {:difficulty difficulty}
            ;; Anwendung über den Web-Pfad: Diffs an Clients, History fürs Replay
            :apply-fn (fn [_state side' kind' chosen]
                        (game/update-and-send-diffs!
                         runner/apply-choice! lobby side' kind' chosen))})
          ;; Bot-Aktivität zählt als Aktivität (sonst räumt
          ;; clear-inactive-lobbies laufende Bot-Partien ab)
          (swap! app-state/app-state update :lobbies bump-last-update gameid)
          true
          (catch Exception e
            (concede-bot! lobby side e)
            false)))

      :else false)))

(defn- run-loop! [gameid]
  (loop []
    (when-let [lobby (bot-lobby gameid)]
      (when (bot-work? lobby)
        ;; Undo-Konsens ist keine Spielentscheidung — keine künstliche
        ;; Bedenkzeit, der Mensch wartet sonst unnötig auf seine Undo-Anfrage.
        (when-not (pending-undo-consent lobby) (think!))
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
            (try
              (when (some-> (bot-lobby gameid) bot-work?)
                (notify! gameid))
              (catch Exception e
                (timbre/error e (str "Bot-Re-Check-Fehler in " gameid)))))))))
  nil)

(defn start-bot-vs-bot!
  "Startet eine frisch erzeugte bot-vs-bot-Lobby (kein menschlicher
  first-player vorhanden). start-game! ruft am Ende notify-bots!."
  [db gameid]
  (game/start-game! db gameid))

(defn register!
  "Beim Systemstart aufrufen (web.system, ig/init-key :bot/seat)."
  []
  (reset! game/bot-notify-fn notify!)
  (reset! lobby/bot-start-fn start-bot-vs-bot!)
  :registered)
