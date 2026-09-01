(ns bot.eval
  "Kartenunabhängige Zustandsbewertung aus der zensierten Sicht einer Seite.
  Nutzt ausschließlich bot.view/view-for — nie roher @state (Projektregel:
  Informations-Hygiene). Speist perspektivisch sowohl Heuristik-Bots als auch
  einen späteren Trainer, deshalb gilt dieselbe Sichtbarkeitsbeschränkung wie
  für echte Bot-Entscheidungen."
  (:require
   [bot.view :as view]
   [game.core.card-defs :refer [card-def]]))

(def ^:private AGENDA-WEIGHT
  "Agenda-Punkte zählen überproportional: nahe an 7 (bzw. weniger bei
  Corp-Boni/Mali) zu sein entscheidet das Spiel unabhängig von allen anderen
  Dimensionen."
  3)

(def UNKNOWN-ICE-STRENGTH
  "Angenommene Stärke eines unrezzten gegnerischen Ice, dessen echte Werte
  in der zensierten View fehlen (private-card — kein :strength). Format-
  Erwartungswert statt 0, sonst wären Corp-Bluffs (bewusst nicht rezztes
  billiges Ice) wertlos und geschützte/ungeschützte Server sähen für den
  Runner gleich aus. Justierbar."
  4)

(def UNKNOWN-ICE-REZ-COST
  "Angenommene Rez-Kosten eines unrezzten gegnerischen Ice, analog
  UNKNOWN-ICE-STRENGTH. Justierbar."
  4)

(def UNAFFORDABLE-ICE-DISCOUNT
  "Abschlagsfaktor auf die Bedrohungsschätzung eines noch nicht rezzten Ice,
  wenn die Corp die (echten oder geschätzten) Rez-Kosten mit ihren aktuell
  sichtbaren Credits nicht aufbringen könnte. Kein Nullwert: die Corp kann
  bis zum tatsächlichen Run noch Credits sammeln, ein Bluff ohne aktuelle
  Deckung ist also abgeschwächt, nicht wertlos. Justierbar."
  0.3)

(defn- opponent [side]
  (if (= side :corp) :runner :corp))

(defn- card-count
  "Karten, die einer Seite noch zur Verfügung stehen (Hand + Deck)."
  [player-view]
  (+ (:hand-count player-view 0) (:deck-count player-view 0)))

(defn- icebreaker? [program]
  (some #{"Icebreaker"} (:subtypes program)))

(defn- ice-subtypes
  "Öffentlich sichtbare Subtypen dieses Ice — leer, wenn unbekannt (siehe
  ice-known? weiter unten)."
  [ice]
  (set (:subtypes ice)))

(defn- matches-ice-type?
  [breaks ice-subtypes]
  (or (contains? breaks "All")
      (boolean (some breaks ice-subtypes))))

(defn- installed-breakers [runner-view]
  (filter icebreaker? (get-in runner-view [:rig :program])))

(defn- matching-breaker-strength
  "Stärkster installierter Icebreaker, dessen statisch gelesener Subtyp
  (card-def — öffentliche, gedruckte Karteninfo, identisch für jede Kopie
  einer Karte) zu `ice-subtypes` passt. Kein Typ-Match ⇒ 0, selbst wenn ein
  ANDERER Breaker installiert ist (Fix des v1-Bugs: vorher zählte der
  global stärkste Breaker unabhängig vom Ice-Typ, siehe
  bot-eval-v1-backlog)."
  [runner-view ice-subtypes]
  (->> (installed-breakers runner-view)
       (filter (fn [c]
                 (some #(and (:break %) (matches-ice-type? (:breaks %) ice-subtypes))
                       (:abilities (card-def c)))))
       (map #(or (:current-strength %) (:strength %) 0))
       (apply max 0)))

(defn- best-breaker-strength
  "Stärkster installierter Icebreaker des Runners, 0 falls keiner installiert.
  Fallback-Wert für Ice mit unbekanntem Subtyp (unrezztes gegnerisches Ice —
  dort kann ohnehin nicht typgenau gematcht werden, siehe
  matching-breaker-strength)."
  [runner-view]
  (->> (get-in runner-view [:rig :program])
       (filter icebreaker?)
       (map #(or (:current-strength %) (:strength %) 0))
       (apply max 0)))

(defn- ice-known?
  "Sind die echten Werte dieses Ice in der View sichtbar? Rezztes Ice ist für
  beide Seiten öffentlich; unrezztes Ice nur für die besitzende Corp (siehe
  game.core.card/is-public?). private-card lässt :strength weg."
  [ice]
  (some? (:strength ice)))

(defn- ice-strength+cost
  "[strength rez-cost] — echte Werte falls sichtbar, sonst die Unknown-
  Defaults. Nie 0 für unbekanntes Ice (siehe UNKNOWN-ICE-*)."
  [ice]
  (if (ice-known? ice)
    [(or (:current-strength ice) (:strength ice)) (:cost ice)]
    [UNKNOWN-ICE-STRENGTH UNKNOWN-ICE-REZ-COST]))

(defn- raw-ice-cost [strength best-breaker-strength]
  (max 1 (inc (- strength best-breaker-strength))))

(defn- credit-cost
  "Summe reiner Credit-Kosten aus einem break-sub/strength-pump-Kostenvektor
  (Vektor von game.core.payment/->c-Maps), oder nil, wenn eine Komponente
  kein reiner Credit-Betrag ist (X-Cost, Virus-/Power-Counter, Trash, ...) —
  dann ist die Ability für eine Kosten-Schätzung nicht nutzbar (dokumentierte
  Vereinfachung, siehe Design-Spec Teil 1, Punkt 6)."
  [cost]
  (when (and (seq cost)
             (every? #(and (= :credit (:cost/type %)) (number? (:cost/amount %))) cost))
    (reduce + 0 (map :cost/amount cost))))

(defn- min-or-nil [xs]
  (when (seq xs) (apply min xs)))

(defn- pump-abilities [card]
  (filter :pump (:abilities (card-def card))))

(defn- break-abilities [card ice-subtypes]
  (filter #(and (:break %) (matches-ice-type? (:breaks %) ice-subtypes))
          (:abilities (card-def card))))

(defn- pump-cost-for-ice
  "Credits, um `card` mindestens `needed` zusätzliche Stärke zu geben — 0,
  wenn keine Stärke fehlt, nil ohne nutzbare (reine Credit-)Pump-Ability."
  [card needed]
  (if (<= needed 0)
    0
    (min-or-nil
     (keep (fn [ab]
             (let [per-use (:pump ab)
                   cost (credit-cost (:cost ab))]
               (when (and cost (number? per-use) (pos? per-use))
                 (* cost (long (Math/ceil (/ (double needed) per-use)))))))
           (pump-abilities card)))))

(defn- break-cost-for-ice
  "Credits, um alle `subs-count` Subroutinen dieses Ice mit `card` zu
  brechen — 0 ohne Subroutinen, nil ohne passende (reine Credit-)Break-
  Ability. `:break 0` bedeutet 'beliebig viele Subs in einer Zahlung'."
  [card ice-subtypes subs-count]
  (if (zero? subs-count)
    0
    (min-or-nil
     (keep (fn [ab]
             (let [n (:break ab)
                   per-use (if (pos? n) n subs-count)
                   cost (credit-cost (:break-cost ab))]
               (when cost
                 (* cost (long (Math/ceil (/ (double subs-count) per-use)))))))
           (break-abilities card ice-subtypes)))))

(defn- breaker-cost-for-ice
  [card ice-strength ice-subtypes subs-count]
  (let [breaker-strength (or (:current-strength card) (:strength card) 0)
        needed (max 0 (- ice-strength breaker-strength))
        pump (pump-cost-for-ice card needed)]
    (when pump
      (when-let [break (break-cost-for-ice card ice-subtypes subs-count)]
        (+ pump break)))))

(defn- best-breach-cost
  "Echte, minimale Credit-Kosten über alle installierten Icebreaker hinweg,
  dieses Ice vollständig zu durchbrechen — nil, wenn kein installierter
  Breaker mit reinen Credit-Kosten passt (Fallback: raw-ice-cost, siehe
  ice-threat)."
  [runner-view ice-strength ice-subtypes subs-count]
  (min-or-nil
   (keep #(breaker-cost-for-ice % ice-strength ice-subtypes subs-count)
         (installed-breakers runner-view))))

(defn- ice-threat
  "Geschätzte Kosten, dieses eine Ice zu überwinden, aus Sicht der Seite, die
  `ice` sieht. Bereits rezztes Ice ist bezahlt (kein Abschlag); noch nicht
  rezztes Ice ist nur eine potenzielle Bedrohung — kann die Corp die (echten
  oder geschätzten) Rez-Kosten mit ihren aktuell sichtbaren Credits nicht
  aufbringen, wird die Rohbedrohung mit UNAFFORDABLE-ICE-DISCOUNT abgewertet.
  Bekannter Ice-Subtyp: erst echte Credit-Kosten versuchen
  (best-breach-cost), sonst Stärke-Delta-Fallback mit typgenauem Breaker
  (matching-breaker-strength)."
  [ice corp-credit runner-view best-breaker-strength]
  (let [[strength rez-cost] (ice-strength+cost ice)
        subtypes (ice-subtypes ice)
        subs-count (count (:subroutines ice))
        effective-breaker-strength (if (seq subtypes)
                                      (matching-breaker-strength runner-view subtypes)
                                      best-breaker-strength)
        real (when (seq subtypes)
               (best-breach-cost runner-view strength subtypes subs-count))
        raw (or real (raw-ice-cost strength effective-breaker-strength))]
    (if (or (:rezzed ice) (>= corp-credit (or rez-cost 0)))
      raw
      (* raw UNAFFORDABLE-ICE-DISCOUNT))))

(defn- server-threat
  [server-view corp-credit runner-credit runner-view best-breaker-strength]
  (let [ices (:ices server-view)
        estimated-cost (reduce + 0 (map #(ice-threat % corp-credit runner-view best-breaker-strength) ices))]
    {:ice-count (count ices)
     :rezzed-count (count (filter :rezzed ices))
     :ice-strength (reduce + 0 (map #(first (ice-strength+cost %)) ices))
     :estimated-cost estimated-cost
     :runner-can-afford? (<= estimated-cost runner-credit)}))

(defn servers-threat
  "Pro Corp-Server eine Bedrohungsschätzung: kommt der Runner vermutlich
  durch, und was kostet es ihn? Nutzt nur öffentlich sichtbare Felder der
  View (Credits beider Seiten, installierte Icebreaker, Ice-Status).
  Öffentlich: Bot-Konsumenten wie bot.heuristic-corp bekommen nur eine View,
  nie rohen @state (Projektregel Informations-Hygiene) — sie greifen direkt
  hierauf zu, statt evaluate/2 zu nutzen, das intern state anfordert."
  [v]
  (let [corp-credit (get-in v [:corp :credit] 0)
        runner-view (get v :runner)
        runner-credit (+ (:credit runner-view 0) (:run-credit runner-view 0))
        best-breaker (best-breaker-strength runner-view)]
    (into {}
          (map (fn [[server-kw server-view]]
                 [server-kw (server-threat server-view corp-credit runner-credit runner-view best-breaker)]))
          (get-in v [:corp :servers]))))

(defn evaluate-view
  "Wie `evaluate`, nimmt aber eine bereits berechnete View statt `state` —
  der Einstiegspunkt für Bot-Konsumenten, die nur eine View besitzen (siehe
  servers-threat-Docstring)."
  [v side]
  (let [opp (opponent side)
        own (get v side)
        their (get v opp)
        credit-diff (- (:credit own 0) (:credit their 0))
        agenda-diff (- (:agenda-point own 0) (:agenda-point their 0))
        card-advantage (- (card-count own) (card-count their))
        click-diff (- (:click own 0) (:click their 0))
        servers (servers-threat v)
        threat-level (reduce + 0 (map :estimated-cost (vals servers)))
        threat-term (if (= side :corp) threat-level (- threat-level))
        score (+ credit-diff
                 (* AGENDA-WEIGHT agenda-diff)
                 card-advantage
                 click-diff
                 threat-term)]
    {:credit-diff credit-diff
     :agenda-diff agenda-diff
     :card-advantage card-advantage
     :click-diff click-diff
     :servers servers
     :threat-level threat-level
     :score score}))

(defn evaluate
  "Bewertet `state` aus Sicht von `side` (:corp oder :runner) — dünner
  Wrapper um evaluate-view (siehe dort), berechnet nur die View. Symmetrisch:
  (evaluate state :corp) und (evaluate state :runner) summieren sich in
  ihrem :score zu 0."
  [state side]
  (evaluate-view (view/view-for state side) side))
