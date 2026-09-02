(ns bot.heuristic-corp
  "Schwierigkeitsgrad 'heuristic': regelbasiertes Corp-Playbook, siehe
  Design-Spec docs/superpowers/specs/2026-09-01-heuristic-corp-bot-design.md.
  Baut auf bot.eval (Bedrohungsschätzung) und bot.legal (angebotene
  Aktionen/Prompt-Optionen) auf. Wählt ausschließlich aus den von
  legal-actions/prompt-options gelieferten Optionen (bot.protocol-Vertrag) —
  keine eigene Options-Fabrikation, keine Regel-Nachbildung. Ein
  eingebetteter bot.random-Bot beantwortet alles, was das Playbook (noch)
  nicht abdeckt."
  (:require
   [bot.eval :as eval]
   [bot.legal :as legal]
   [bot.protocol :as bp]
   [bot.random :as random]))

;; --- Konstanten (justierbar) ---

(def MULLIGAN-ECON-CARDS
  "Fixe, gateway-corp-spezifische Econ-Liste für die Mulligan-Heuristik
  (siehe Design-Spec Teil 2 — YAGNI, analog zur Seamless-Launch-Erkennung
  in einer späteren Regel: der Bot spielt ausschließlich das
  System-Gateway-Starterdeck)."
  #{"Hedge Fund" "Government Subsidy" "Nico Campaign" "Regolith Mining License"})

(def ECON-ASSET-CARDS
  #{"Regolith Mining License" "Nico Campaign"})

(def ECON-CLICK-ABILITY-CARDS
  "Econ-Assets mit einer manuellen Klick-Ability, die einem generischen
  1-Credit-Klick vorgezogen wird (Regel 5.3)."
  #{"Regolith Mining License"})

(def ECON-OPERATION-CARDS
  #{"Hedge Fund" "Government Subsidy"})

(def CENTRAL-ICE-PRIORITY
  "Reihenfolge, in der ungeschützte Zentralserver geict werden (Regel 1)."
  [:hq :rd :archives])

(def ASSUMED-RUNNER-INCOME-PER-TURN
  "Sicherheitspuffer in Credits: wird auf den sichtbaren Runner-Credit-Stand
  aufgeschlagen, bevor ein Server als 'sicher genug' für Agenda-Install/
  -Advance gilt (der Runner verdient VOR seinem nächsten Zug noch Geld —
  siehe Design-Spec Teil 2)."
  4)

;; --- View-Helper ---

(defn- corp-servers [view] (get-in view [:corp :servers]))
(defn- server-ices [view zone] (:ices (get (corp-servers view) zone)))
(defn- server-content [view zone] (:content (get (corp-servers view) zone)))
(defn- remote-zone? [zone] (not (contains? #{:hq :rd :archives} zone)))
(defn- agenda-card? [c] (= "Agenda" (:type c)))
(defn- runner-credit [view]
  (+ (get-in view [:runner :credit] 0) (get-in view [:runner :run-credit] 0)))

(defn- central-needing-ice [view]
  (first (filter #(empty? (server-ices view %)) CENTRAL-ICE-PRIORITY)))

(defn- scoring-remote-zone
  "Auswahl-Heuristik: erst Remote mit Agenda drin, sonst Remote mit >=1 Ice
  ohne Agenda, sonst nil (Design-Spec Teil 2 — Single-Remote-Fokus, YAGNI)."
  [view]
  (let [remotes (filter (fn [[z _]] (remote-zone? z)) (corp-servers view))]
    (or (ffirst (filter (fn [[_ sv]] (some agenda-card? (:content sv))) remotes))
        (ffirst (filter (fn [[_ sv]] (and (seq (:ices sv)) (not-any? agenda-card? (:content sv)))) remotes)))))

(defn- remote-has-agenda? [view zone]
  (boolean (some agenda-card? (server-content view zone))))

(defn- server-threat-for [view zone]
  (get (eval/servers-threat view) zone))

(defn- safe-for-commitment?
  "Ist `zone` (mit Sicherheitspuffer!) für eine mehrzügige Verpflichtung
  (Agenda-Install/-Advance) sicher genug? Siehe ASSUMED-RUNNER-INCOME-PER-
  TURN — ein Vergleich gegen den AKTUELLEN Runner-Credit-Stand würde die
  Bedrohung systematisch unterschätzen (der Runner verdient nach dem
  Corp-Zug noch Geld)."
  [view zone]
  (boolean
   (when-let [threat (server-threat-for view zone)]
     (> (:estimated-cost threat) (+ (runner-credit view) ASSUMED-RUNNER-INCOME-PER-TURN)))))

(defn- ice-install-target
  "Wohin als nächstes Ice installiert werden soll: Zone-Keyword eines
  Zentralservers, Zone-Keyword eines bestehenden, noch unsicheren Scoring-
  Remotes, `:new-remote` für einen frischen Remote, oder nil (kein
  Ice-Install nötig)."
  [view]
  (or (central-needing-ice view)
      (let [zone (scoring-remote-zone view)]
        (cond
          (nil? zone) :new-remote
          (and (not (remote-has-agenda? view zone))
               (not (safe-for-commitment? view zone)))
          zone))))

(defn- server-label [target]
  (if (= :new-remote target) "New remote" (legal/server-name target)))

;; --- legal-actions-Helper ---

(defn- first-play-of-type [legal-actions type-str]
  (first (filter #(and (= "play" (:command %)) (= type-str (get-in % [:args :card :type])))
                 legal-actions)))

(defn- first-play-of-titles [legal-actions titles]
  (first (filter #(and (= "play" (:command %)) (contains? titles (get-in % [:args :card :title])))
                 legal-actions)))

;; --- Regel 1+2: Ice installieren ---

(defn- try-install-ice
  [view legal-actions]
  (when-let [target (ice-install-target view)]
    (when-let [act (first-play-of-type legal-actions "ICE")]
      {:action act
       :reason (str "heuristic-corp: Regel "
                    (if (= target (central-needing-ice view)) "1 (Zentralserver icen)" "2 (Scoring-Remote aufbauen)")
                    " -> " (server-label target) ", installiere " (get-in act [:args :card :title]))})))

(defn- agenda-install-target
  [view]
  (when-let [zone (scoring-remote-zone view)]
    (when (and (seq (server-ices view zone))
               (not (remote-has-agenda? view zone))
               (safe-for-commitment? view zone))
      zone)))

;; --- Regel 3: Agenda platzieren ---

(defn- try-install-agenda
  [view legal-actions]
  (when-let [zone (agenda-install-target view)]
    (when-let [act (first-play-of-type legal-actions "Agenda")]
      (let [threat (server-threat-for view zone)]
        {:action act
         :reason (str "heuristic-corp: Regel 3 (Agenda platzieren) -> " (server-label zone)
                      " estimated-cost=" (:estimated-cost threat)
                      " > runner-credit(" (runner-credit view) ")+buffer("
                      ASSUMED-RUNNER-INCOME-PER-TURN ") -> sicher, installiere "
                      (get-in act [:args :card :title]))}))))

(defn- find-legal [legal-actions command pred]
  (first (filter #(and (= command (:command %)) (pred %)) legal-actions)))

(defn- remaining-advancement [agenda]
  (- (or (:current-advancement-requirement agenda) 0)
     (+ (:advance-counter agenda 0) (:extra-advance-counter agenda 0))))

;; --- Regel 4: Scoren ---

(defn- try-score-line
  [view legal-actions]
  (when-let [zone (scoring-remote-zone view)]
    (when (remote-has-agenda? view zone)
      (let [agenda (first (filter agenda-card? (server-content view zone)))
            clicks (get-in view [:corp :click] 0)
            remaining (remaining-advancement agenda)
            score-act (find-legal legal-actions "score" #(= (:title agenda) (get-in % [:args :card :title])))
            advance-act (find-legal legal-actions "advance" #(= (:title agenda) (get-in % [:args :card :title])))
            seamless-act (first-play-of-type legal-actions "Operation")]
        (cond
          score-act
          {:action score-act
           :reason (str "heuristic-corp: Regel 4 (Scoren) -> " (:title agenda) " ist fertig advanced, score")}

          ;; Deadline-Score-Linie geht der Sicherheitspruefung vor: ist das
          ;; Restadvancement diesen Zug per Seamless Launch abschliessbar,
          ;; wird geschlossen, unabhaengig davon, ob der Server nach der
          ;; Sicherheitsheuristik "sicher" waere (z.B. noch kein Ice im
          ;; Remote -- estimated-cost 0 gilt sonst immer als unsicher). Nur
          ;; das offene, mehrzuegige Normal-Advancen unten bleibt sicherheits-
          ;; gated (siehe ASSUMED-RUNNER-INCOME-PER-TURN-Kommentar).
          (and (<= remaining (+ clicks 2))
               seamless-act
               (= "Seamless Launch" (get-in seamless-act [:args :card :title])))
          {:action seamless-act
           :reason (str "heuristic-corp: Regel 4 (Score-Linie) -> Seamless Launch auf "
                        (:title agenda) ", Restadvancement=" remaining " <= Klicks(" clicks ")+2")}

          (not (safe-for-commitment? view zone))
          nil

          advance-act
          {:action advance-act
           :reason (str "heuristic-corp: Regel 4 (weiter advancen) -> " (:title agenda)
                        " Restadvancement=" remaining)})))))

;; --- Prompt-Routing: Seamless-Launch-Ziel ---

(defn- select-seamless-target
  [view options]
  (when-let [zone (scoring-remote-zone view)]
    (when-let [agenda (first (filter agenda-card? (server-content view zone)))]
      (first (filter #(and (= :card (:type %)) (= (:cid agenda) (get-in % [:card :cid]))) options)))))

;; --- Regel 5: Econ ---

(defn- try-install-econ-asset
  [_view legal-actions]
  (when-let [act (first-play-of-titles legal-actions ECON-ASSET-CARDS)]
    {:action act
     :reason (str "heuristic-corp: Regel 5.1 (Econ-Asset installieren) -> "
                  (get-in act [:args :card :title]))}))

(defn- installed-unrezzed-econ-asset [view]
  (->> (vals (corp-servers view))
       (mapcat :content)
       (filter #(and (contains? ECON-ASSET-CARDS (:title %)) (not (:rezzed %))))
       first))

(defn- try-rez-econ-asset
  [view legal-actions]
  (when-let [asset (installed-unrezzed-econ-asset view)]
    (when-let [act (find-legal legal-actions "rez" #(= (:cid asset) (get-in % [:args :card :cid])))]
      {:action act :reason (str "heuristic-corp: Regel 5.2 (Econ-Asset rezzen) -> " (:title asset))})))

(defn- try-econ-click-ability
  [_view legal-actions]
  (when-let [act (find-legal legal-actions "ability"
                             #(contains? ECON-CLICK-ABILITY-CARDS (get-in % [:args :card :title])))]
    {:action act :reason (str "heuristic-corp: Regel 5.3 (Klick-fuer-Credits) -> " (:label act))}))

(defn- try-play-econ-operation
  [_view legal-actions]
  (when-let [act (first-play-of-titles legal-actions ECON-OPERATION-CARDS)]
    {:action act :reason (str "heuristic-corp: Regel 5.4 (Econ-Operation spielen) -> "
                              (get-in act [:args :card :title]))}))

(defn- try-econ
  [view legal-actions]
  (or (try-install-econ-asset view legal-actions)
      (try-rez-econ-asset view legal-actions)
      (try-econ-click-ability view legal-actions)
      (try-play-econ-operation view legal-actions)
      (when-let [act (find-legal legal-actions "credit" (constantly true))]
        {:action act :reason "heuristic-corp: Regel 5.5 (Klick fuer Credit)"})))

;; --- Prompt-Routing: Server-Wahl ---

(defn- select-target-server
  "Server-Name-String für den Install-Prompt von `card` — nil, wenn `card`
  gerade nicht Teil einer aktiven Install-Entscheidung ist."
  [view card]
  (case (:type card)
    "ICE" (some-> (ice-install-target view) server-label)
    "Agenda" (some-> (agenda-install-target view) server-label)
    "Asset" (when (contains? ECON-ASSET-CARDS (:title card)) "New remote")
    nil))

(defn- choose-by-label [options label reason]
  (when-let [opt (first (filter #(= label (:label %)) options))]
    {:option opt :reason reason}))

;; --- Mulligan ---

(defn- mulligan-decision
  [view options]
  (let [hand (get-in view [:corp :hand])
        ice-count (count (filter #(= "ICE" (:type %)) hand))
        econ-count (count (filter #(contains? MULLIGAN-ECON-CARDS (:title %)) hand))
        label (if (and (zero? ice-count) (zero? econ-count)) "Mulligan" "Keep")]
    {:option (first (filter #(= label (:label %)) options))
     :reason (str "heuristic-corp: Mulligan-Check -> Ice=" ice-count " Econ=" econ-count " -> " label)}))

;; --- Bot ---

(defrecord HeuristicCorpBot [random-delegate]
  bp/Bot
  (decide [_ view legal-actions]
    (or (try-install-ice view legal-actions)
        (try-install-agenda view legal-actions)
        (try-score-line view legal-actions)
        (try-econ view legal-actions)
        {:action (first legal-actions)
         :reason "heuristic-corp: Regel 6 (Fallback) -> keine Regel griff, erste Option"}))
  (on-prompt [_ view prompt options]
    (if (= :mulligan (:prompt-type prompt))
      (mulligan-decision view options)
      (or (when-let [label (select-target-server view (:card prompt))]
            (choose-by-label options label
                              (str "heuristic-corp: Server-Wahl fuer " (:title (:card prompt)) " -> " label)))
          (when (= :select (:prompt-type prompt))
            (when-let [opt (select-seamless-target view options)]
              {:option opt :reason (str "heuristic-corp: Seamless-Launch-Ziel -> " (:label opt))}))
          (bp/on-prompt random-delegate view prompt options)))))

(defn heuristic-corp-bot
  "Baut einen seedbaren Heuristik-Corp-Bot. `seed` steuert nur den
  eingebetteten Random-Delegate (für Prompts/Fallbacks ohne eigene Regel) —
  das Playbook selbst ist deterministisch."
  [seed]
  (->HeuristicCorpBot (random/random-bot seed)))
