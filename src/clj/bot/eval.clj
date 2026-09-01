(ns bot.eval
  "Kartenunabhängige Zustandsbewertung aus der zensierten Sicht einer Seite.
  Nutzt ausschließlich bot.view/view-for — nie roher @state (Projektregel:
  Informations-Hygiene). Speist perspektivisch sowohl Heuristik-Bots als auch
  einen späteren Trainer, deshalb gilt dieselbe Sichtbarkeitsbeschränkung wie
  für echte Bot-Entscheidungen."
  (:require
   [bot.view :as view]))

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

(defn- best-breaker-strength
  "Stärkster installierter Icebreaker des Runners, 0 falls keiner installiert.
  v1-Vereinfachung: kein Fracter/Decoder/Killer-Matching gegen den Ice-Typ,
  nur der global stärkste Brecher (siehe Backlog-Notiz für Schritt 7)."
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

(defn- ice-threat
  "Geschätzte Kosten, dieses eine Ice zu überwinden, aus Sicht der Seite, die
  `ice` sieht. Bereits rezztes Ice ist bezahlt (kein Abschlag); noch nicht
  rezztes Ice ist nur eine potenzielle Bedrohung — kann die Corp die (echten
  oder geschätzten) Rez-Kosten mit ihren aktuell sichtbaren Credits nicht
  aufbringen, wird die Rohbedrohung mit UNAFFORDABLE-ICE-DISCOUNT abgewertet."
  [ice corp-credit best-breaker-strength]
  (let [[strength rez-cost] (ice-strength+cost ice)
        raw (raw-ice-cost strength best-breaker-strength)]
    (if (or (:rezzed ice) (>= corp-credit (or rez-cost 0)))
      raw
      (* raw UNAFFORDABLE-ICE-DISCOUNT))))

(defn- server-threat
  [server-view corp-credit runner-credit best-breaker-strength]
  (let [ices (:ices server-view)
        estimated-cost (reduce + 0 (map #(ice-threat % corp-credit best-breaker-strength) ices))]
    {:ice-count (count ices)
     :rezzed-count (count (filter :rezzed ices))
     :ice-strength (reduce + 0 (map #(first (ice-strength+cost %)) ices))
     :estimated-cost estimated-cost
     :runner-can-afford? (<= estimated-cost runner-credit)}))

(defn- servers-threat
  "Pro Corp-Server eine Bedrohungsschätzung: kommt der Runner vermutlich
  durch, und was kostet es ihn? Nutzt nur öffentlich sichtbare Felder der
  View (Credits beider Seiten, installierte Icebreaker, Ice-Status)."
  [v]
  (let [corp-credit (get-in v [:corp :credit] 0)
        runner-view (get v :runner)
        runner-credit (+ (:credit runner-view 0) (:run-credit runner-view 0))
        best-breaker (best-breaker-strength runner-view)]
    (into {}
          (map (fn [[server-kw server-view]]
                 [server-kw (server-threat server-view corp-credit runner-credit best-breaker)]))
          (get-in v [:corp :servers]))))

(defn evaluate
  "Bewertet `state` aus Sicht von `side` (:corp oder :runner), ausschließlich
  über die zensierte View (bot.view/view-for). Liefert eine Map mit
  Einzeldimensionen und einem gewichteten Gesamtscore (:score), positiv = gut
  für `side`. Symmetrisch: (evaluate state :corp) und (evaluate state
  :runner) summieren sich in ihrem :score zu 0."
  [state side]
  (let [v (view/view-for state side)
        opp (opponent side)
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
