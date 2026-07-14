(ns bot.legal
  "Zählt legale Optionen ausschließlich aus der zensierten View auf (bot.view),
  nie aus dem rohen State. Die Engine bleibt Regelinstanz: hier wird nur
  angeboten, validiert wird in game.core.process-actions — abgelehnte Aktionen
  sind No-Ops und werden vom Runner per Retry aussortiert.")

(defn action [command args label]
  {:command command :args args :label label})

(defn- server-name
  "View-Zone-Keyword (:hq :rd :archives :remoteN) -> Servername für click-run."
  [zone-kw]
  (case zone-kw
    :hq "HQ"
    :rd "R&D"
    :archives "Archives"
    (str "Server " (subs (name zone-kw) (count "remote")))))

(defn- installed-corp-cards [view]
  (mapcat (fn [[_ srv]] (concat (:ices srv) (:content srv)))
          (get-in view [:corp :servers])))

(defn- runner-rig-cards [view]
  (let [rig (get-in view [:runner :rig])]
    (concat (:program rig) (:hardware rig) (:resource rig))))

(defn- hand-plays [view side]
  (for [c (get-in view [side :hand])
        :when (:playable c)]
    (action "play" {:card c} (str "play " (:title c)))))

(defn- ability-actions
  "Nutzbare Karten-Abilities.

  Der reale View-Summary (game.core.diffs/ability-summary) setzt :index NICHT
  auf der Ability selbst — dieser Key existiert in ability-keys nur für den
  (seltenen) Fall, dass eine Karten-Definition ihn selbst mitgibt. In der
  Praxis ist er nil, darum wird der Index hier aus der Listenposition in
  (:abilities c) rekonstruiert (map-indexed), analog zu game.core.actions/play-ability,
  das die Ability ebenfalls per Index in diesen Vektor nachschlägt."
  [view side]
  (let [cards (if (= side :corp)
                (installed-corp-cards view)
                (runner-rig-cards view))]
    (for [c cards
          [idx ab] (map-indexed vector (:abilities c))
          :when (and (:playable ab) (not (:dynamic ab)))]
      (action "ability" {:card c :ability idx}
              (str (or (:title c) "facedown card") ": " (:label ab))))))

(defn- scoreable? [c]
  (and (= "Agenda" (:type c))
       (>= (+ (:advance-counter c 0) (:extra-advance-counter c 0))
           (or (:current-advancement-requirement c) (:advancementcost c) Integer/MAX_VALUE))))

(defn- advanceable?
  "Ob c per Basic Action \"advance\" beworben werden darf.

  Agenden sind immer advanceable, ohne dass eine Karten-Definition das per
  :advanceable-Flag markiert (das Flag existiert nur für Assets/Ice/Upgrades
  mit eigener advance-Fähigkeit, siehe game.cards.assets, game.cards.ice) —
  siehe die Referenzlogik in src/cljs/nr/gameboard/board.cljs (action-list),
  die \"Agenda\" als eigenen Fall neben :advanceable behandelt."
  [c]
  (or (= "Agenda" (:type c))
      (= :always (:advanceable c))
      (and (:rezzed c) (= :while-rezzed (:advanceable c)))
      (and (not (:rezzed c)) (= :while-unrezzed (:advanceable c)))))

(defn- corp-click-actions [view]
  (let [corp (:corp view)
        credits (:credit corp 0)
        runner-tagged? (pos? (get-in view [:runner :tag :total] 0))
        installed (installed-corp-cards view)]
    (concat
     [(action "credit" nil "gain 1 credit")]
     (when (pos? (:deck-count corp 0))
       [(action "draw" nil "draw 1 card")])
     (hand-plays view :corp)
     (ability-actions view :corp)
     (when (pos? credits)
       (for [c installed :when (advanceable? c)]
         (action "advance" {:card c} (str "advance " (or (:title c) "facedown card")))))
     (for [c installed :when (scoreable? c)]
       (action "score" {:card c} (str "score " (:title c))))
     [(action "purge" nil "purge virus counters")]
     (when (and runner-tagged? (>= credits 2))
       [(action "trash-resource" nil "trash a runner resource")]))))

(defn- runner-click-actions [view]
  (let [runner (:runner view)
        credits (:credit runner 0)
        tags (get-in runner [:tag :total] 0)]
    (concat
     [(action "credit" nil "gain 1 credit")]
     (when (pos? (:deck-count runner 0))
       [(action "draw" nil "draw 1 card")])
     (hand-plays view :runner)
     (ability-actions view :runner)
     (for [zone (keys (get-in view [:corp :servers]))]
       (action "run" {:server (server-name zone)} (str "run " (server-name zone))))
     (when (and (pos? tags) (>= credits 2))
       [(action "remove-tag" nil "remove 1 tag")]))))

(defn turn-actions
  [view side]
  (let [clicks (get-in view [side :click] 0)]
    (if (pos? clicks)
      (vec (if (= side :corp)
             (corp-click-actions view)
             (runner-click-actions view)))
      [(action "end-turn" nil "end turn")])))

(defn- approached-ice
  "Das Ice an der aktuellen Run-Position, aus der View der jeweiligen Seite.

  :server im Run-Summary ist ein einelementiger Vektor mit dem Server-Zone-
  Keyword selbst (z.B. [:hq], [:remote1]) — siehe game.core.runs/make-run
  (s = [(last (server->zone state server))]) — nicht [:servers :hq]."
  [view]
  (when-let [run (:run view)]
    (let [server-kw (keyword (name (last (:server run))))
          ices (get-in view [:corp :servers server-kw :ices])
          pos (:position run)]
      (when (and pos (pos? pos) (<= pos (count ices)))
        (nth ices (dec pos))))))

(defn run-actions
  [view side]
  (let [run (:run view)
        encounter (:encounters view)
        current-ice (or (:ice encounter) (approached-ice view))]
    (vec
     (if (= side :corp)
       (concat
        [(action "continue" nil "no action (continue)")]
        (when (and current-ice (not (:rezzed current-ice)))
          [(action "rez" {:card current-ice} "rez current ice")])
        (when (and encounter (:rezzed current-ice)
                   (some #(not (:broken %)) (:subroutines current-ice)))
          [(action "unbroken-subroutines" {:card current-ice}
                   "fire unbroken subroutines")]))
       (concat
        [(action "continue" nil "continue run")]
        (when encounter (ability-actions view :runner))
        (when (= :movement (:phase run))
          [(action "jack-out" nil "jack out")]))))))

(defn- with-hosted
  "Karte plus alle (rekursiv) gehosteten Karten."
  [c]
  (cons c (mapcat with-hosted (:hosted c))))

(defn- visible-cards
  "Alles, was side für Select-Prompts anklicken könnte (nur View-Inhalte)."
  [view side]
  (mapcat with-hosted
          (concat
           (keep #(get-in view [% :identity]) [:corp :runner])
           (get-in view [side :hand])
           (installed-corp-cards view)
           (runner-rig-cards view)
           (get-in view [:runner :rig :facedown])
           (get-in view [:corp :discard])
           (get-in view [:runner :discard])
           (mapcat #(concat (get-in view [% :play-area])
                            (get-in view [% :scored])
                            (get-in view [% :current])
                            (get-in view [% :set-aside]))
                   [:corp :runner]))))

(defn- select-card-options
  "Anklickbare Karten eines Select-Prompts. Die Engine liefert in :selectable
  die cids aller legalen Ziele (game.core.prompts/show-select) — damit wird
  gefiltert, sofern vorhanden; sonst bleibt das Retry-Aussortieren des Runners
  die Absicherung. Bereits selektierte Karten (Multi-Select, :selected-Flag
  aus der View) werden nicht erneut angeboten, sonst würde der zweite Klick
  sie nur wieder abwählen (game.core.actions/select ist ein Toggle)."
  [view side prompt]
  (let [selectable (set (:selectable prompt))
        cards (->> (visible-cards view side)
                   (filter :cid)
                   (remove :selected))
        cards (if (seq selectable)
                (filter #(selectable (:cid %)) cards)
                cards)]
    ;; ein cid kann mehrfach sichtbar sein (z. B. Host + hosted-Liste)
    (for [c (vals (into {} (map (juxt :cid identity)) cards))]
      {:type :card :card c :label (or (:title c) "facedown card")})))

(defn prompt-options
  [view side]
  (let [prompt (get-in view [side :prompt-state])
        choices (:choices prompt)]
    (cond
      (= :select (:prompt-type prompt))
      (vec
       (concat
        (select-card-options view side prompt)
        ;; manche Select-Prompts haben zusätzlich Buttons (z. B. "Done")
        (for [c (when (sequential? choices) choices)]
          {:type :button :uuid (:uuid c) :label (str (:value c))})))

      ;; "Nenne eine Karte": Engine erwartet einen Titel-String; die legalen
      ;; Titel stehen in :autocomplete (game.core.engine, Zeile ~448).
      (and (map? choices) (:card-title choices))
      (mapv (fn [t] {:type :title :value t :label t})
            (:autocomplete choices))

      (= :trace (:prompt-type prompt))
      (mapv (fn [n] {:type :number :value n :label (str n)})
            (range 0 (inc (min (get-in view [side :credit] 0)
                               (if (number? choices) choices Integer/MAX_VALUE)))))

      (or (= :credit choices) (and (map? choices) (or (:number choices) (:counter choices))))
      (let [max-n (cond
                    (= :credit choices) (get-in view [side :credit] 0)
                    (:number choices) (:number choices)
                    :else (get-in view [side :credit] 0))]
        (mapv (fn [n] {:type :number :value n :label (str n)})
              (range 0 (inc max-n))))

      (sequential? choices)
      (mapv (fn [c] {:type :button
                     :uuid (:uuid c)
                     :label (str (or (get-in c [:value :title]) (:value c)))})
            choices)

      :else
      [])))
