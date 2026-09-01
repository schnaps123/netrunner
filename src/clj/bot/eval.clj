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

(defn- opponent [side]
  (if (= side :corp) :runner :corp))

(defn- card-count
  "Karten, die einer Seite noch zur Verfügung stehen (Hand + Deck)."
  [player-view]
  (+ (:hand-count player-view 0) (:deck-count player-view 0)))

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
        servers {}
        threat-level 0
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
