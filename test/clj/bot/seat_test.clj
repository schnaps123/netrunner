(ns bot.seat-test
  (:require
   [bot.cards :as bot-cards]
   [bot.game-runner :as runner]
   [bot.legal :as legal]
   [bot.log :as blog]
   [bot.random :as bot-random]
   [bot.seat :as seat]
   [bot.view :as view]
   [clojure.edn]
   [clojure.java.io]
   [clojure.string]
   [clojure.test :refer :all]
   [game.main :as main]
   [web.app-state :as app-state]
   [web.game :as game]
   [web.lobby :as lobby]
   [web.stats :as stats]
   [web.ws :as ws]))

(use-fixtures :once (fn [f] (bot-cards/load-all-cards!) (f)))
(use-fixtures :each (fn [f]
                      (reset! app-state/app-state {:lobbies {} :users {}})
                      (seat/register!)
                      (f)
                      (reset! game/bot-notify-fn (fn [_] nil))))

(defmacro with-stub-io [& body]
  `(with-redefs [ws/chsk-send! (fn [& _#] nil)
                 stats/game-started (fn [& _#] nil)
                 stats/fetch-replay-record (fn [& _#] nil)
                 lobby/send-lobby-state (fn [& _#] nil)
                 lobby/broadcast-lobby-list (fn [& _#] nil)]
     ~@body))

(defn- wait-until
  "Pollt bis pred truthy oder timeout-ms um; liefert (pred) oder nil."
  [pred timeout-ms]
  (loop [waited 0]
    (or (pred)
        (when (< waited timeout-ms)
          (Thread/sleep 50)
          (recur (+ waited 50))))))

(defn- start-vs-bot!
  "vs-bot-Lobby (Mensch = Runner, Bot = Corp) erzeugen + starten; liefert gameid.
  Abweichung vom Brief: dort war Mensch = Corp / Bot = Runner vorgesehen. Die
  Keep/Mulligan-Sequenz dieser Engine (game.core.set-up/init-hands+mulligan)
  lässt jedoch IMMER zuerst Corp entscheiden — Runners eigener Mulligan-Prompt
  steckt bis dahin hinter einem :waiting-Prompt (siehe show-wait-prompt in
  set_up.clj, hart auf :runner verdrahtet) und ist daher nicht actionable.
  Mit Mensch = Corp würde der Bot (Runner) beim Start NIE etwas zu tun
  bekommen, ohne dass der Test selbst eine menschliche Aktion simuliert
  (was der Brief nicht tut) — die drei Tests unten könnten so nie grün
  werden. Mit Bot = Corp bekommt der Bot sofort seinen eigenen, echten
  Mulligan-Prompt (Corp wartet auf niemanden) und die Tests prüfen die
  eigentlich relevanten Verhaltensweisen (Guard/Loop, Decision-Log,
  Fehlerpfad→Concede) wie im Brief beabsichtigt."
  []
  (let [l (lobby/create-new-lobby
           {:uid "u1" :user {:username "david"}
            :options {:bot-game "vs-bot" :side "Runner" :difficulty "random"
                      :format "system-gateway" :title "t" :room "casual"}})
        gameid (:gameid l)
        human-deck (:deck (bot-cards/player-entry "Runner" bot-cards/gateway-runner))]
    (swap! app-state/app-state assoc-in [:lobbies gameid]
           (assoc-in l [:players 0 :deck] human-deck))
    (game/start-game! nil gameid)
    gameid))

(deftest vs-bot-bot-beantwortet-eigenen-mulligan
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [gameid (start-vs-bot!)
            state (:state (app-state/get-lobby gameid))]
        ;; start-game! hat notify! gerufen (register!-Hook). Der Bot (Corp)
        ;; beantwortet seinen Keep/Mulligan-Prompt selbständig, ohne dass der
        ;; Mensch (Runner) etwas tun muss.
        (is (wait-until #(not (runner/actionable-prompt? (:state (app-state/get-lobby gameid)) :corp))
                        10000)
            "Corp-Bot hat seinen Start-Prompt beantwortet")
        (is (runner/actionable-prompt? state :runner)
            "Runner-Prompt (Mensch) ist jetzt offen — Bot wartet auf den Menschen")
        (is (nil? (:winner @state)))))))

(deftest vs-bot-decision-log-mit-difficulty
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [gameid (start-vs-bot!)
            path (seat/log-path gameid)]
        (wait-until #(.exists (clojure.java.io/file path)) 10000)
        (let [entry (clojure.edn/read-string
                     (first (clojure.string/split-lines (slurp path))))]
          (is (= "random" (:difficulty entry)))
          (is (string? (:reason entry)))
          (is (= :corp (:side entry))))))))

(deftest bot-fehler-fuehrt-zu-concede
  ;; Abweichung vom Brief: with-redefs muss bereits `start-vs-bot!` umschließen,
  ;; nicht erst danach ansetzen. start-vs-bot! löst über register!/notify-bots!
  ;; bereits die erste (asynchrone) Bot-Entscheidung aus; mit *think-ms* [0 0]
  ;; ist diese so schnell abgeschlossen, dass ein mit-redefs erst NACH
  ;; start-vs-bot! den echten (nicht den redefinierten) decide-one! erwischen
  ;; kann — mit-redefs bindet die Var global (nicht nur im Testthread), muss
  ;; also schon aktiv sein, bevor die Engine überhaupt läuft.
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (with-redefs [runner/decide-one!
                    (fn [& _] (throw (ex-info "Keine ausführbare Option übrig" {})))]
        (let [gameid (start-vs-bot!)
              state (:state (app-state/get-lobby gameid))]
          (seat/notify! gameid)
          (is (wait-until #(:winner @state) 10000)
              "Bot concedet nach Fehler — Partie endet")
          (is (= "Concede" (:reason @state))))))))

(deftest bot-to-act-bevorzugt-eigenen-parallelen-prompt
  ;; Psi-Games (game.core.psi/psi-game) zeigen BEIDEN Seiten gleichzeitig
  ;; einen echten :psi-Prompt. next-actor priorisiert dabei immer :corp vor
  ;; :runner — bot-to-act soll dem Runner-Bot trotzdem seinen eigenen Prompt
  ;; sofort beantworten lassen, statt auf next-actor zu warten.
  (let [bot (bot-random/random-bot 1)
        state (atom {:corp {:prompt (list {:prompt-type :psi :msg "bid" :eid 1})}
                     :runner {:prompt (list {:prompt-type :psi :msg "bid" :eid 2})}})
        lby {:state state :bots {:runner bot}}]
    (is (= [:corp :prompt] (runner/next-actor state))
        "next-actor würde hier Corp priorisieren")
    (is (= [:runner :prompt bot] (#'seat/bot-to-act lby))
        "bot-to-act beantwortet trotzdem sofort den eigenen Runner-Prompt")))

(deftest bot-to-act-wartet-wenn-nur-gegenseite-einen-prompt-hat
  ;; Hat NUR Corp einen actionable Prompt und sitzt der Bot auf Runner, muss
  ;; der Bot warten (kein Bot auf der Seite, die next-actor priorisieren würde).
  (let [bot (bot-random/random-bot 1)
        state (atom {:corp {:prompt (list {:prompt-type :psi :msg "bid" :eid 1})}
                     :runner {:prompt (list)}})
        lby {:state state :bots {:runner bot}}]
    (is (= [:corp :prompt] (runner/next-actor state))
        "next-actor würde Corp wählen")
    (is (nil? (#'seat/bot-to-act lby))
        "bot-to-act liefert nil — der Runner-Bot wartet")))

(deftest bot-vs-bot-partie-laeuft-komplett-durch
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [l (lobby/create-new-lobby
               {:uid "u1" :user {:username "david"}
                :options {:bot-game "bot-vs-bot" :difficulty "random"
                          :format "system-gateway" :title "t" :room "casual"}})
            gameid (:gameid l)]
        (swap! app-state/app-state assoc-in [:lobbies gameid] l)
        (game/start-game! nil gameid)
        (let [state (:state (app-state/get-lobby gameid))]
          (is (wait-until #(:winner @state) 120000)
              "Partie Random vs. Random endet mit Sieger")
          (is (pos? (:turn @state 0))))))))

(deftest try-create-lobby-startet-bot-vs-bot-automatisch
  (let [started (atom nil)]
    (with-redefs [lobby/bot-start-fn (atom (fn [_db gameid] (reset! started gameid)))
                  lobby/auto-select-decks (fn [_db l] l)
                  lobby/send-lobby-state (fn [& _] nil)
                  lobby/broadcast-lobby-list (fn [& _] nil)]
      (lobby/try-create-lobby
       nil "u1" {:username "david"}
       {:bot-game "bot-vs-bot" :difficulty "random"
        :format "system-gateway" :title "t" :room "casual"})
      (is (some? @started) "bot-start-fn wurde mit der gameid gerufen"))))

(deftest f3-bump-last-update-ohne-lobby-kein-geist
  ;; F3: parallel zu bot-step! kann close-lobby! die Lobby bereits entfernt
  ;; haben. Ein plain assoc-in würde dann einen Geister-Eintrag
  ;; {gameid {:last-update ...}} erzeugen. Der guarded Bump ist ein No-Op.
  (let [result (#'seat/bump-last-update {} "geister-gameid")]
    (is (= {} result))
    (is (not (contains? result "geister-gameid")))))

(deftest f3-bump-last-update-mit-lobby
  ;; Regression: existiert die Lobby, wird :last-update wie bisher gesetzt.
  (let [ls {"g1" {:last-update :old}}
        result (#'seat/bump-last-update ls "g1")]
    (is (not= :old (get-in result ["g1" :last-update])))))

(deftest f4-concede-bot-loggt-stacktrace
  ;; F4 (Spec): Fehler + Stacktrace ins Decision-Log.
  (let [logged (atom nil)]
    (with-redefs [blog/append-event! (fn [_path m] (reset! logged m))]
      (#'seat/concede-bot! {:gameid "g-test"} :corp (ex-info "boom" {:foo 1})))
    (is (= :concede (:event @logged)))
    (is (= :corp (:side @logged)))
    (is (= "boom" (:error @logged)))
    (is (= {:foo 1} (:data @logged)))
    (is (string? (:stacktrace @logged)))
    (is (not (clojure.string/blank? (:stacktrace @logged))))))

(deftest pending-undo-consent-erkennt-offene-anfrage
  (let [bot (bot-random/random-bot 1)
        state (atom {:turn-state {} :corp {:undo-turn true} :runner {}})
        lby {:state state :bots {:runner bot}}]
    (is (= [:runner bot] (#'seat/pending-undo-consent lby))
        "Corp (Mensch) hat zugestimmt, Runner-Bot muss noch konsentieren")))

(deftest pending-undo-consent-nil-ohne-bot-auf-der-anderen-seite
  (let [state (atom {:turn-state {} :corp {:undo-turn true} :runner {}})
        lby {:state state :bots {:corp (bot-random/random-bot 1)}}]
    (is (nil? (#'seat/pending-undo-consent lby))
        "Corp selbst ist der Bot, der schon zugestimmt hat — nichts offen")))

(deftest pending-undo-consent-nil-wenn-schon-konsentiert
  (let [bot (bot-random/random-bot 1)
        state (atom {:turn-state {} :corp {:undo-turn true} :runner {:undo-turn true}})
        lby {:state state :bots {:runner bot}}]
    (is (nil? (#'seat/pending-undo-consent lby))
        "beide Seiten haben zugestimmt — Engine hätte längst zurückgesetzt")))

(deftest pending-undo-consent-nil-ohne-anfrage
  (let [bot (bot-random/random-bot 1)
        state (atom {:turn-state {} :corp {} :runner {}})
        lby {:state state :bots {:runner bot}}]
    (is (nil? (#'seat/pending-undo-consent lby)))))

(deftest vs-bot-bot-stimmt-undo-turn-automatisch-zu
  ;; Smoke-Test-Notiz: /undo-turn ist konsenspflichtig (game.core.commands/
  ;; command-undo-turn) — der Bot chattet nie von sich aus, muss also
  ;; automatisch zustimmen, sonst hängt die Anfrage für immer.
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [gameid (start-vs-bot!)
            {:keys [state] :as lobby} (app-state/get-lobby gameid)]
        ;; Corp-Bot beantwortet zuerst seinen eigenen Mulligan-Prompt; erst
        ;; danach entlarvt clear-wait-prompt den echten Runner-Prompt (siehe
        ;; game.core.set-up/keep-hand) — auf DEN warten, nicht nur darauf,
        ;; dass Corps Prompt weg ist (sonst race: Runner-Prompt noch :waiting).
        (is (wait-until #(runner/actionable-prompt? (:state (app-state/get-lobby gameid)) :runner)
                        10000))
        ;; Runner (Mensch) "keept" manuell, um den Zug zu starten (:turn-state
        ;; existiert erst, sobald ein Zug begonnen hat — command-undo-turn ist
        ;; sonst ein No-Op, siehe game.core.commands/command-undo-turn).
        ;; Wie im echten :game/action-Handler über lobby/game-thread laufen
        ;; lassen (und mit @ abwarten) — sonst konkurriert dieser Test-Thread
        ;; unserialisiert mit dem Bot-Loop-Pool-Thread auf demselben State-Atom
        ;; (genau die Race, vor der die Serialisierung eigentlich schützt).
        (let [v (view/view-for state :runner)
              keep-opt (first (filter #(= "Keep" (:label %))
                                      (legal/prompt-options v :runner)))]
          (is (some? keep-opt))
          @(lobby/game-thread
            lobby
            (game/update-and-send-diffs!
             (fn [s _] (runner/apply-choice! s :runner :prompt keep-opt))
             lobby :runner)
            ;; Wie im echten :game/action-Pfad: erst nach dem Hook-Aufruf holt
            ;; der Bot next-actor -> [:corp :start-turn] ab und startet den Zug.
            (seat/notify! gameid)))
        (is (wait-until #(:turn-state @state) 10000) "Zug hat begonnen")
        ;; Mensch (Runner) schickt /undo-turn — wie im echten :game/say-Pfad.
        ;; Kein Zwischen-Check auf [:runner :undo-turn]: sobald der Bot
        ;; zustimmt (Konsens komplett, kein think!-Delay dafür), resettet
        ;; command-undo-turn den State auf den Snapshot von VOR der Anfrage —
        ;; die Flag ist dann selbst schon wieder weg. Das Log ist der einzige
        ;; stabile, nicht-transiente Beleg für die Zustimmung.
        @(lobby/game-thread
          lobby
          (game/update-and-send-diffs!
           main/handle-say lobby :runner {:username "david"} "/undo-turn")
          (seat/notify! gameid))
        (is (wait-until
             #(some (fn [line]
                      (= :undo-turn-consent (:event (clojure.edn/read-string line))))
                    (clojure.string/split-lines
                     (try (slurp (seat/log-path gameid)) (catch Exception _ ""))))
             10000)
            "Bot hat einen :undo-turn-consent-Eintrag geloggt")
        (is (wait-until #(not (get-in @state [:corp :undo-turn])) 10000)
            "Konsens abgeschlossen — Engine hat zurückgesetzt (Flag verschwunden)")
        (is (nil? (:winner @state)) "Partie läuft normal weiter, kein Absturz/Concede")))))

(deftest try-create-lobby-startet-vs-bot-nicht
  (let [started (atom nil)]
    (with-redefs [lobby/bot-start-fn (atom (fn [_db gameid] (reset! started gameid)))
                  lobby/auto-select-decks (fn [_db l] l)
                  lobby/send-lobby-state (fn [& _] nil)
                  lobby/broadcast-lobby-list (fn [& _] nil)]
      (lobby/try-create-lobby
       nil "u1" {:username "david"}
       {:bot-game "vs-bot" :side "Corp" :difficulty "random"
        :format "system-gateway" :title "t" :room "casual"})
      (is (nil? @started) "vs-bot startet über den normalen Start-Button"))))
