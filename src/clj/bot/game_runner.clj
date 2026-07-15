(ns bot.game-runner
  "Headless Runner: spielt eine komplette Partie zwischen zwei Bots.
  Regelauswertung läuft ausschließlich über game.core/process-action;
  dieser Namespace orchestriert nur, welche Seite wann gefragt wird.
  Roher @state wird hier NUR für Mechanik benutzt (Resolver, Prompt-eid,
  Progress-Fingerprint) — Bot-Input ist immer bot.view/view-for."
  (:require
   [bot.cards :as cards]
   [bot.legal :as legal]
   [bot.log :as blog]
   [bot.protocol :as bp]
   [bot.view :as view]
   [game.core :as core]
   [game.core.set-up :as setup]))

(defn- raw-prompt [state side]
  (-> @state side :prompt seq first))

(defn actionable-prompt? [state side]
  (when-let [p (raw-prompt state side)]
    (not (contains? #{:waiting :run} (:prompt-type p)))))

(defn next-actor
  [state]
  (let [s @state]
    (cond
      (:winner s) nil
      (actionable-prompt? state :corp) [:corp :prompt]
      (actionable-prompt? state :runner) [:runner :prompt]

      (or (seq (:encounters s)) (:run s))
      ;; :no-action speichert die SEITE, die bereits gepasst hat
      ;; (game.core.runs/continue), kein Boolean — handeln muss die andere.
      ;; false/nil: noch niemand hat gepasst, Corp zuerst.
      (let [no-action (or (:no-action (peek (:encounters s)))
                          (:no-action (:run s)))]
        (if (= :corp no-action) [:runner :run] [:corp :run]))

      (:corp-phase-12 s) [:corp :phase-12]
      (:runner-phase-12 s) [:runner :phase-12]
      (:end-turn s) [(if (= (:active-player s) :corp) :runner :corp) :start-turn]
      :else [(:active-player s) :action])))

(defn- options-for [view side kind]
  (case kind
    :prompt     (legal/prompt-options view side)
    :run        (legal/run-actions view side)
    :phase-12   [(legal/action "end-phase-12" nil "end phase 1.2")]
    :start-turn [(legal/action "start-turn" nil "start turn")]
    :action     (legal/turn-actions view side)))

(defn apply-choice!
  [state side kind chosen]
  (if (= kind :prompt)
    (let [eid (:eid (raw-prompt state side))]
      (case (:type chosen)
        :card   (core/process-action "select" state side {:card (:card chosen) :eid eid})
        :number (core/process-action "choice" state side {:choice (:value chosen) :eid eid})
        :title  (core/process-action "choice" state side {:choice (:value chosen) :eid eid})
        :button (core/process-action "choice" state side {:choice {:uuid (:uuid chosen)} :eid eid})))
    (core/process-action (:command chosen) state side (:args chosen))))

(defn- counter-digest
  "Summe aller Karten-Counter einer Seite (installierte Karten, Play-Area).
  Nötig, weil z.B. Pay-Credit-Selects nur Counter bewegen (Overclock & Co.),
  ohne Klicks/Credits/Prompt-eid zu ändern."
  [s side]
  (let [cards (concat (get-in s [side :play-area])
                      (get-in s [side :current])
                      (get-in s [side :rig :program])
                      (get-in s [side :rig :hardware])
                      (get-in s [side :rig :resource])
                      (mapcat (fn [[_ srv]] (concat (:ices srv) (:content srv)))
                              (get-in s [side :servers])))]
    (reduce + 0 (mapcat (comp vals :counter) cards))))

(defn- fingerprint
  "Kompakter Zustands-Abdruck, um No-Op-Aktionen zu erkennen."
  [state]
  (let [s @state]
    [(get-in s [:corp :click]) (get-in s [:runner :click])
     (get-in s [:corp :credit]) (get-in s [:runner :credit])
     (count (get-in s [:corp :prompt])) (count (get-in s [:runner :prompt]))
     (:eid (raw-prompt state :corp)) (:eid (raw-prompt state :runner))
     ;; Prompt-Text ändert sich bei mehrstufigen Prompts mit stabiler eid
     ;; (z.B. "Choose a credit providing card (1 of 3)"), Counter bei
     ;; Pay-Credit-Selects — beides sonst unsichtbare Fortschritte.
     (:msg (raw-prompt state :corp)) (:msg (raw-prompt state :runner))
     (counter-digest s :corp) (counter-digest s :runner)
     (:turn s) (:active-player s) (:end-turn s)
     (:corp-phase-12 s) (:runner-phase-12 s)
     (select-keys (:run s) [:phase :position :no-action :server])
     (count (:encounters s)) (:no-action (peek (:encounters s)))
     ;; Multi-Select: jeder Kartenklick toggelt nur das :selected-Flag
     ;; ([side :selected], game.core.actions/select) — ohne diesen Eintrag
     ;; sähe der No-Op-Check echte Selektionsfortschritte nicht.
     (mapv (fn [sel] (mapv :cid (:cards sel))) (get-in s [:corp :selected]))
     (mapv (fn [sel] (mapv :cid (:cards sel))) (get-in s [:runner :selected]))
     (count (:log s)) (:winner s)]))

(defn decide-one!
  "Eine Bot-Entscheidung: Bot fragen, anwenden (via :apply-fn), loggen.
  No-Ops: Option streichen, Bot erneut fragen. :log-extra wird in jeden
  Log-Eintrag gemergt (z.B. {:difficulty \"random\"}).
  Wirft ex-info \"Keine ausführbare Option übrig\", wenn nichts den State bewegt."
  [{:keys [state side kind bot log-path apply-fn log-extra step-no]
    :or {apply-fn apply-choice!}}]
  (let [v (view/view-for state side)]
    (loop [options (vec (options-for v side kind))]
      (when (empty? options)
        (throw (ex-info "Keine ausführbare Option übrig"
                        {:side side :kind kind :step step-no
                         :prompt (get-in v [side :prompt-state])})))
      (let [decision (if (= kind :prompt)
                       (bp/on-prompt bot v (get-in v [side :prompt-state]) options)
                       (bp/decide bot v options))
            chosen (or (:action decision) (:option decision))
            before (fingerprint state)
            _ (apply-fn state side kind chosen)
            progressed? (not= before (fingerprint state))]
        (when log-path
          (blog/append-decision! log-path
                                 (merge {:turn (:turn @state 0)
                                         :phase (view/phase-of v)
                                         :side side
                                         :kind kind
                                         :options (mapv :label options)
                                         :choice (:label chosen)
                                         :reason (:reason decision)
                                         :no-op (not progressed?)}
                                        log-extra)))
        (when-not progressed?
          (recur (vec (remove #{chosen} options))))))))

(defn run-game
  [{:keys [corp-bot runner-bot log-path max-steps corp-deck runner-deck]
    :or {max-steps 5000
         corp-deck cards/demo-corp
         runner-deck cards/demo-runner}}]
  (cards/load-all-cards!)
  (let [state (setup/init-game
               {:gameid 1
                :format "casual"
                :players [(cards/player-entry "Corp" corp-deck)
                          (cards/player-entry "Runner" runner-deck)]})]
    (loop [steps 0]
      (if-let [[side kind] (when (< steps max-steps) (next-actor state))]
        (let [bot (if (= side :corp) corp-bot runner-bot)]
          (decide-one! {:state state :side side :kind kind :bot bot
                        :log-path log-path :step-no steps})
          (recur (inc steps)))
        {:winner (:winner @state)
         :reason (:reason @state)
         :turn (:turn @state)
         :steps steps
         :completed? (some? (:winner @state))}))))
