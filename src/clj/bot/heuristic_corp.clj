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
   [bot.random :as random]
   [clojure.string :as str]))

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
  "Basis-Reihenfolge (Tiebreak), in der Zentralserver verstärkt werden, wenn
  kein Breach-Zähler (siehe BREACH-WINDOW-TURNS) sie umsortiert: HQ/R&D vor
  Archives (Schadenspotenzial — Kartenverlust/Agenda-Diebstahl vs. reiner
  Trash-Zugriff; HQ vs. R&D-Reihenfolge unverändert aus v1 übernommen, siehe
  regel-1-zentralserver-icen-prioritaet-hq)."
  [:hq :rd :archives])

(def ^:private CENTRAL-BASE-RANK
  "zone -> Tiebreak-Rang aus CENTRAL-ICE-PRIORITY, fuer central-priority-order."
  (zipmap CENTRAL-ICE-PRIORITY (range)))

(def BREACH-WINDOW-TURNS
  "Fenster (in Corp-Zügen, siehe (:turn view)) für recent-breach-count:
  nur Breaches aus den letzten N Zügen zählen für die
  Verstärkungspriorität. Verhindert, dass ein Breach-Muster aus der
  Frühphase die Priorität bis zum Spielende dominiert, obwohl der Runner
  längst einen anderen Server angreift."
  6)

(def MAX-ICE-PER-SCORING-REMOTE
  "Obergrenze für Ice auf dem Scoring-Remote (Regel 2). Ohne Grenze icet der
  Bot einen Remote, der wegen des Sicherheitspuffers (ASSUMED-RUNNER-INCOME-
  PER-TURN) nie 'sicher genug' wird, endlos weiter. Ist die Grenze erreicht
  und der Server gilt weiter als unsicher, bricht Regel 2 ab -- siehe
  ice-install-target; der Bot zieht/spielt Econ statt weiterzuicen."
  3)

(def CENTRAL-ICE-BASE-DEPTH
  "Bis zu dieser Ice-Zahl pro Zentralserver ist Nachverstaerkung auch OHNE
  beobachteten Breach erlaubt (frueher Grundschutz). Darueber hinaus (mehr
  als 1-2 Ice frueh auf demselben Server) nur, wenn der Breach-Zaehler
  tatsaechlichen Druck auf GENAU diesem Server zeigt -- sonst verschwendet
  Regel 1 Tempo auf einen Server, waehrend ein anderer Zentralserver noch gar
  kein Ice hat (Breite-vor-Tiefe-Korrektur 2026-09-02, aus einer echten
  Partie: Palisade+Whitespace+Diviner alle in Zug 1 auf HQ, R&D/Archives
  blieben unberuehrt -- siehe central-zero-ice-needing/central-depth-needing)."
  2)

(def SCORING-PATIENCE-TURNS
  "Nach so vielen Corp-Zuegen im Stall-Zustand (Scoring-Remote an der Ice-
  Obergrenze, aber weiterhin nicht sicher genug fuer ein Commitment, Agenda
  liegt in der Hand) installiert Regel 3 die Agenda trotzdem, statt weiter
  auf 'sicher genug' zu warten -- siehe track-remote-stall!/
  patience-exhausted?. Grund (aus einer echten Partie: :scoring-gap #{:tax}
  stand ueber mehrere Zuege unveraendert, der Bot klickte bei 20-29 Credits
  nur noch fuer Credits): der Remote wird durch simples Zuwarten NICHT
  sicherer, wenn der Runner pro Zug mehr Einkommen hat als die Ice-
  Obergrenze noch an zusaetzlicher Taxierung liefern kann -- warten ist fuer
  die Corp die einzige garantiert verlierende Strategie. Justierbar."
  4)

(def HAND-SIZE-LOW
  "Handkartenzahl, ab der Ziehen (Regel 5.5) bevorzugt wird, selbst wenn
  zufällig sowohl Ice als auch eine Agenda in der (dann sehr dünnen) Hand
  liegen -- eine ausgedünnte Hand blockiert auf Dauer jede andere Regel."
  2)

(def DRAW-SAFETY-BUFFER
  "Harte UND-Bedingung für Regel 5.5 (Ziehen): unterhalb dieses Rest-Deck-
  Stands wird NICHT mehr freiwillig gezogen, egal wie stark die anderen
  Ziehen-Gründe sprechen. Grund: die Corp hat JEDEN Zug ohnehin einen
  verpflichtenden Startzug-Zieh (game.core.turns, unabhängig von diesem
  Playbook) -- ohne diesen Puffer beschleunigt freiwilliges Ziehen das
  eigene Decking, statt es zu vermeiden (siehe 200-Partien-Regression vom
  2026-09-02: 18 freiwillige + 11 verpflichtende Zieh-Aktionen = exakt das
  komplette Rest-Deck, Partie endete durch Decking noch vor der nächsten
  Bot-Entscheidung)."
  4)

(def DECK-CAUTION-THRESHOLD
  "Ab diesem Rest-Deck-Stand (bzw. darunter) wird Ziehen (Regel 5.5)
  zurückhaltender, aber nicht sofort gestoppt (das bleibt DRAW-SAFETY-
  BUFFER vorbehalten): 'keine Agenda in der Hand' allein reicht unterhalb
  dieser Schwelle NICHT mehr als Ziehen-Grund -- nur eine wirklich dünne
  Hand (HAND-SIZE-LOW) rechtfertigt dann noch freiwilliges Ziehen. Keine
  harte Ein-Klick-pro-Zug-Grenze (Korrektur 2026-09-02: manchmal muss der
  Bot nach Agenden/Ice graben, auch mehrfach pro Zug) -- nur eine
  zweistufige Zurückhaltung, die sich dem Sicherheitsboden annähert."
  10)

(def PLENTY-CREDITS
  "Ab diesem Corp-Credit-Stand ist Ziehen (Regel 5.5) einem weiteren
  generischen 1-Credit-Klick (Regel 5.6) vorzuziehen -- ein Credit, den
  der Bot absehbar nicht braucht, ist schlechter investiert als eine neue
  Karte. Siehe should-draw?-Docstring für die Wiedereinführungs-Begründung
  (2026-09-02, aus einer echten Partie: 13-16 Credits, wiederholtes
  Klicken statt Ziehen/Vorbauen)."
  12)

(def AGENDA-SEARCH-VALUE
  "Näherungswert, wie viel gezieltes Graben nach der fehlenden Agenda
  (Regel 2.5) wert ist, wenn die Hand schon an/über der Maximalgröße liegt
  und ein Pflicht-Abwurf am Zugende droht -- verglichen gegen den Wert der
  Karte, die dabei am ehesten geopfert würde (HAND-CARD-DISCARD-COST der
  günstigsten Handkarte). Kein hartes Verbot bei voller Hand: ein Abwurf
  ist der Preis, meist billiger als ein Zug ohne jede andere produktive
  Option -- aber eine echte Abwägung, kein Freifahrtschein. Justierbar."
  3)

(def HAND-CARD-DISCARD-COST
  "Ungefährer Wert pro Kartentyp, eine Handkarte durch Pflicht-Abwurf am
  Zugende zu verlieren -- 0 für eine Karte, die JETZT ohnehin keine legale
  Aktion hat (discard-cost, siehe dort: nichts zu tun, sicher zu verlieren
  -- Regel 1/2/5.1-5.4 laufen ohnehin vorher oder parallel, ein Ice ohne
  aktuelles Installationsziel oder eine unbezahlbare Operation zählen
  darüber schon als 'ohne legale Aktion'). Asset/Upgrade höher gewichtet
  als Ice/Operation (gleicher Gedanke wie trash-weight im Wert-Modell-
  Design: laufende Engine-Teile sind wertvoller als Einweg-Karten oder
  gerade ungebrauchtes Ice). Justierbar."
  {"Asset" 5 "Upgrade" 4 "ICE" 1 "Operation" 1})

(def TOTAL-AGENDA-COPIES-IN-DECK
  "Agenda-Kopien in bot.cards/gateway-corp -- Offworld Office x3 (2 Punkte),
  Send a Message x2 (3 Punkte), Superconducting Hub x2 (2 Punkte) = 7.
  Korrektur 2026-09-02: Superconducting Hub wurde zunächst übersehen
  (klingt wie ein Asset, ist aber laut Kartendaten :type \"Agenda\") -- der
  alte Wert (5) ließ agendas-remaining-in-deck? zu früh 'keine mehr' melden.
  Fest, weil der Bot ausschließlich dieses eine Deck spielt (YAGNI, wie
  MULLIGAN-ECON-CARDS). Für 'stecken laut Decklist noch Agenden im
  Rest-Deck?' (Regel 2 Vorbau-Gate, siehe agendas-remaining-in-deck?)."
  7)

(def DEFAULT-AGENDA-POINTS
  "Fallback-Agenda-Punktwert für commitment-worth-it? (siehe dort), wenn
  gerade keine konkrete Kandidaten-Agenda bekannt ist (z.B. Regel 2s
  Vorbau-Entscheidung, bevor eine Agenda in der Hand liegt). gateway-corp
  hat unterschiedliche Werte (Offworld Office 2, Send a Message 3) --
  Mittelwert-nahe Näherung, kein Rateversuch bei tatsächlich bekannter
  Karte (dort wird immer der echte :agendapoints-Wert gelesen)."
  2)

(def COMMIT-RISK-CREDITS-PER-POINT
  "Wie viele Credits 'Rest-Vermögen' beim Runner NACH einem erfolgreichen
  Einbruch pro Agenda-Punkt noch als akzeptabler Tausch gelten (Design-Spec
  Scoring-Fenster-Zielmodell, Phase 2 -- Commitment als Risikoabwägung
  statt Sicherheitsgarantie: absolute Sicherheit, Kosten > gesamtes Budget,
  bleibt abgedeckt, wird aber zum Sonderfall statt zur Voraussetzung).
  Justierbar."
  3)

(def REZ-BUDGET-MARGIN
  "Kleiner Aufschlag auf max-unrezzed-ice-rez-cost für credit-ready? (siehe
  dort): der Runner begegnet Ice auf einem Server NACHEINANDER, die Corp
  rezzt nur nach Bedarf, ein Run endet oft schon am ersten Ice (besonders
  mit ETR-Ice, siehe bot.eval/has-etr-subroutine?) -- die Summe ALLER
  unrezzten Ice-Rez-Kosten als Budget zu verlangen wäre darum zu streng
  (würde :credits im Scoring-Fenster fast dauerhaft als fehlend melden und
  Regel 3 wieder blockieren, derselbe Fehler wie die frühere feste
  MIN-CENTRAL-TAX-CREDITS-Schwelle). Der kleine Puffer deckt den häufigen
  Fall ab, dass danach noch ein zweites, günstiges Ice bezahlt werden muss,
  ohne gleich wieder worst-case zu rechnen."
  2)

(def ASSUMED-RUNNER-INCOME-PER-TURN
  "Angenommenes Runner-Einkommen pro Zug in Credits — die Bezugsgröße für
  BEIDE Sicherheits-Modi von tax-sufficient? (siehe dort): als Puffer auf
  den aktuellen Runner-Credit-Stand für einmalige Verpflichtungen (Agenda-
  Install/-Advance — der Runner verdient VOR seinem nächsten Zug noch
  Geld), UND als eigenständige Schwelle für laufende Zentralserver-
  Taxierung (ein Server, der weniger kostet als der Runner pro Zug
  verdient, ist Gratis-Farmen). Kein willkürlicher fixer Wert mehr für
  Zentralen (siehe frühere MIN-CENTRAL-TAX-CREDITS, entfernt 2026-09-02) —
  beide Fragen sind jetzt an dieselbe Größe gekoppelt."
  4)

;; --- View-Helper ---

(defn- corp-servers [view] (get-in view [:corp :servers]))
(defn- server-ices [view zone] (:ices (get (corp-servers view) zone)))
(defn- server-content [view zone] (:content (get (corp-servers view) zone)))
(defn- remote-zone? [zone] (not (contains? #{:hq :rd :archives} zone)))
(defn- agenda-card? [c] (= "Agenda" (:type c)))
(defn- ice-card? [c] (= "ICE" (:type c)))
(defn- runner-credit [view]
  (+ (get-in view [:runner :credit] 0) (get-in view [:runner :run-credit] 0)))
(defn- corp-hand [view] (get-in view [:corp :hand]))
(defn- corp-credit [view] (get-in view [:corp :credit] 0))
(defn- agenda-in-hand? [view] (boolean (some agenda-card? (corp-hand view))))
(defn- ice-in-hand? [view] (boolean (some ice-card? (corp-hand view))))
(defn- hand-size [view] (count (corp-hand view)))
(defn- deck-count [view] (get-in view [:corp :deck-count] 0))

(defn- agendas-remaining-in-deck?
  "Stecken laut bekannter Decklist (TOTAL-AGENDA-COPIES-IN-DECK) noch
  Agenda-Kopien im ungesehenen Rest-Deck? Zählt alle SICHTBAREN Agenda-
  Kopien (Hand, Archives, beide Score-Areas, alle installierten Remote-
  Contents) gegen die bekannte Gesamtzahl. Für Regel 2s Vorbau-Gate: ein
  Scoring-Remote lohnt sich vorzubauen, wenn realistisch noch eine Agenda
  kommen KANN -- nicht erst, wenn schon eine in der Hand liegt (das bleibt
  Regel 3s strengere Bedingung fürs tatsächliche Installieren)."
  [view]
  (let [seen (+ (count (filter agenda-card? (corp-hand view)))
                (count (filter agenda-card? (get-in view [:corp :discard])))
                (count (filter agenda-card? (get-in view [:corp :scored])))
                (count (filter agenda-card? (get-in view [:runner :scored])))
                (count (mapcat #(filter agenda-card? (:content %)) (vals (corp-servers view)))))]
    (< seen TOTAL-AGENDA-COPIES-IN-DECK)))

(defn- server-threat-for [view zone]
  (get (eval/servers-threat view) zone))

(defn- archives-has-agenda?
  "Liegt eine Agenda im Archiv (:discard, nicht :servers :archives :content
  -- ein dorthin verworfenes/gemilltes Kartenexemplar landet im Discard,
  nicht als 'installierter' Server-Inhalt)? Echtes Netrunner-Regel: ein
  Zugriff auf ein agendafreies Archiv kostet die Corp nichts, Archives
  braucht also nur Taxierung, wenn es eine Agenda trägt."
  [view]
  (boolean (some agenda-card? (get-in view [:corp :discard]))))

(defn- tax-sufficient?
  "Gemeinsame Bewertungsfunktion für beide Sicherheits-Fragen des
  Playbooks, beide auf derselben estimated-cost-Quelle (bot.eval/
  servers-threat) aufsetzend, aber mit unterschiedlicher Vergleichsgröße
  -- die beiden Fragen sind NICHT dasselbe:
  - :ongoing (Zentralserver-Taxierung): 'kann der Runner diesen Server
    JEDE RUNDE wieder billig abfarmen?' Laufende Miete über viele Züge --
    Vergleichsgröße ist darum das Runner-EINKOMMEN pro Zug
    (ASSUMED-RUNNER-INCOME-PER-TURN), NICHT sein aktueller Credit-Stand.
    Ein Server, der weniger kostet als der Runner pro Zug verdient, ist
    Gratis-Farmen; spürbar mehr zwingt ihn, einen ganzen Zug dafür
    aufzuwenden.
  - :one-time (Scoring-Remote-Commitment): 'kann er in DIESEM EINEN Zug
    profitabel einbrechen und die committete Agenda stehlen?' Einmalige
    Alles-oder-nichts-Frage -- Vergleichsgröße ist der aktuelle Runner-
    Credit-Stand PLUS ein Zug Einkommen (der Runner verdient zwischen
    Commitment und der Corp-Advance-Phase noch einen Zug lang Geld)."
  [view zone mode]
  (let [cost (or (:estimated-cost (server-threat-for view zone)) 0)
        threshold (case mode
                    :ongoing ASSUMED-RUNNER-INCOME-PER-TURN
                    :one-time (+ (runner-credit view) ASSUMED-RUNNER-INCOME-PER-TURN))]
    (> cost threshold)))

(defn- central-tax-too-low?
  "Ist `zone` als laufendes Gratis-Farm-Ziel unterhalb dessen, was der
  Runner pro Zug verdient (tax-sufficient? :ongoing)? Ersetzt die alte
  'hat der Server >=1 Ice'-Prüfung, die ein einzelnes billiges Ice (z.B.
  Tithe) für immer als 'fertig versorgt' zählte, obwohl der Runner trivial
  durchkam. Ausnahme Archives: ohne Agenda dort ist ein Zugriff wertlos
  für den Runner (archives-has-agenda?) -- kein Pflicht-Taxierungsziel,
  auch mit 0 Ice."
  [view zone]
  (if (and (= zone :archives) (not (archives-has-agenda? view)))
    false
    (not (tax-sufficient? view zone :ongoing))))

(defn- recent-breach-count
  "Wie oft `zone` in den letzten BREACH-WINDOW-TURNS Corp-Zügen (siehe
  (:turn view)) gebreached wurde -- siehe track-run-progress! für die
  Zählung. Alte Breaches fallen aus dem Fenster, statt für immer die
  Priorität zu dominieren."
  [run-history zone current-turn]
  (count (filter #(and (= zone (:zone %)) (< (- current-turn (:turn %)) BREACH-WINDOW-TURNS))
                 (:breaches @run-history))))

(defn- central-priority-order
  "Zentralserver sortiert nach Verstärkungspriorität: zuerst nach jüngsten
  Breaches absteigend (ein wiederholt durchbrochener Server geht vor allen
  anderen), dann CENTRAL-ICE-PRIORITY als Tiebreak."
  [view run-history]
  (let [current-turn (get view :turn 0)]
    (sort-by (fn [zone] [(- (recent-breach-count run-history zone current-turn))
                          (get CENTRAL-BASE-RANK zone)])
             CENTRAL-ICE-PRIORITY)))

(defn- central-zero-ice-needing
  "Erster Zentralserver in Verstaerkungspriotitaet, der noch UNTER-taxiert
  ist UND noch GAR KEIN Ice hat -- die Breite-Phase von Regel 1 (siehe
  CENTRAL-ICE-BASE-DEPTH): jeder relevante Zentralserver bekommt sein
  erstes Ice, bevor irgendeiner ein zweites bekommt."
  [view run-history]
  (first (filter #(and (central-tax-too-low? view %) (zero? (count (server-ices view %))))
                 (central-priority-order view run-history))))

(defn- central-depth-needing
  "Erster Zentralserver in Verstaerkungspriotitaet, der noch UNTER-taxiert
  ist und (a) unter CENTRAL-ICE-BASE-DEPTH liegt (frueher Grundschutz, auch
  ohne Breach erlaubt) oder (b) tatsaechlichen Breach-Druck zeigt -- die
  Tiefe-Phase von Regel 1, greift erst NACH der Breite-Phase (siehe
  ice-install-target)."
  [view run-history]
  (let [current-turn (get view :turn 0)]
    (first (filter (fn [zone]
                     (and (central-tax-too-low? view zone)
                          (let [ice-count (count (server-ices view zone))]
                            (or (< ice-count CENTRAL-ICE-BASE-DEPTH)
                                (pos? (recent-breach-count run-history zone current-turn))))))
                   (central-priority-order view run-history)))))

(defn- central-needing-ice
  "Naechster Zentralserver, der laut Taxierung Ice braucht -- fuer Logging/
  Central?-Erkennung in try-install-ice und fuer free-install-offer-choice.
  NICHT direkt fuer die Ziel-Reihenfolge (siehe ice-install-target: Breite
  vor Tiefe, Scoring-Remote dazwischen)."
  [view run-history]
  (or (central-zero-ice-needing view run-history)
      (central-depth-needing view run-history)))

(defn- scoring-remote-zone
  "Auswahl-Heuristik: erst Remote mit Agenda drin, sonst Remote mit >=1 Ice
  ohne Agenda, sonst nil (Design-Spec Teil 2 — Single-Remote-Fokus, YAGNI)."
  [view]
  (let [remotes (filter (fn [[z _]] (remote-zone? z)) (corp-servers view))]
    (or (ffirst (filter (fn [[_ sv]] (some agenda-card? (:content sv))) remotes))
        (ffirst (filter (fn [[_ sv]] (and (seq (:ices sv)) (not-any? agenda-card? (:content sv)))) remotes)))))

(defn- remote-has-agenda? [view zone]
  (boolean (some agenda-card? (server-content view zone))))

(defn- remaining-advancement [agenda]
  (- (or (:current-advancement-requirement agenda) 0)
     (+ (:advance-counter agenda 0) (:extra-advance-counter agenda 0))))

(defn- candidate-agenda-points
  "Punktwert der ersten Agenda in der Hand (first-play-of-type in
  try-install-agenda wählt ohnehin per Handreihenfolge) -- DEFAULT-AGENDA-
  POINTS, wenn keine in der Hand liegt (z.B. Regel 2s Vorbau-Entscheidung)."
  [view]
  (or (:agendapoints (first (filter agenda-card? (corp-hand view))))
      DEFAULT-AGENDA-POINTS))

(defn- commitment-worth-it?
  "Lohnt sich eine einmalige Verpflichtung (Agenda-Install/-Advance) auf
  `zone` für `agenda-points` Punkte? Risikoabwägung statt Sicherheits-
  garantie (Design-Spec Scoring-Fenster-Zielmodell, Phase 2): echte Corp-
  Spieler scoren fast nie unter absoluter Sicherheit. Vergleicht nicht
  'kann der Runner überhaupt reinkommen', sondern 'wie arm ist er DANACH,
  gemessen am Wert der Agenda' -- post-break-wealth = sein Budget
  (aktueller Credit-Stand + ein Zug Einkommen) minus die geschätzten
  Durchbruchskosten. Absolute Sicherheit (Kosten > gesamtes Budget) bleibt
  abgedeckt: negatives post-break-wealth ist immer <= der (nicht-
  negativen) akzeptablen Armuts-Schwelle -- wird aber zum SONDERFALL statt
  zur Voraussetzung. Ein Runner, der sich den Einbruch gerade noch leisten
  kann, aber danach fast pleite ist, ist ebenfalls ein akzeptabler Tausch."
  [view zone agenda-points]
  (let [budget (+ (runner-credit view) ASSUMED-RUNNER-INCOME-PER-TURN)
        cost (or (:estimated-cost (server-threat-for view zone)) 0)
        post-break-wealth (- budget cost)
        acceptable-poverty (* agenda-points COMMIT-RISK-CREDITS-PER-POINT)]
    (<= post-break-wealth acceptable-poverty)))

(defn- safe-for-commitment?
  "Ist `zone` für eine einmalige Verpflichtung (Agenda-Install/-Advance)
  ein akzeptabler Tausch (commitment-worth-it?)? `agenda-points` optional
  -- ohne bekannte Kandidaten-Agenda wird candidate-agenda-points als
  Näherung verwendet."
  ([view zone] (safe-for-commitment? view zone (candidate-agenda-points view)))
  ([view zone agenda-points] (commitment-worth-it? view zone agenda-points)))

(defn- max-unrezzed-ice-rez-cost
  "Teuerste einzelne Rez-Kosten unter den noch unrezzten Ice auf `zone` —
  0, wenn alle schon rezzt sind oder keins existiert. Untergrenze für
  credit-ready? (siehe dort und REZ-BUDGET-MARGIN), bewusst NICHT die
  Summe aller unrezzten Ice."
  [view zone]
  (->> (server-ices view zone)
       (remove :rezzed)
       (keep :cost)
       (apply max 0)))

(defn- remaining-advancement-cost
  "Credits, um eine im Scoring-Remote bereits liegende, unfertig advancte
  Agenda fertig zu advancen -- 1 Credit pro 'advance'-Klick (Basisaktion,
  siehe corp-click-actions), 0 wenn keine Agenda dort liegt oder sie schon
  fertig ist. Teil von credit-ready? (siehe dort).

  BEKANNTE UEBERSTRENGE (notiert 2026-09-03, noch NICHT behoben): verlangt
  aktuell die GESAMTEN Restadvancement-Kosten auf einmal. Das ist zu
  streng -- die Credits fuer spaetere Advances kann die Corp in den
  FOLGENDEN Zuegen verdienen (Regel 3.5/try-fund-score-line deckt genau
  das schon ab), sie schon beim Install zu verlangen blockiert das
  Scoring-Fenster unnoetig lange. 200-Partien-Vergleich (2026-09-03):
  Agenda-Siege 57 -> 48 nach dieser Aenderung, Gap-Verteilung kippte auf
  :credits als dominante Dimension (6597 von ~12800 Gap-Eintraegen) --
  passt zu dieser Ueberstrenge als Erklaerung, auch wenn der Rueckgang
  allein am Rand des dokumentierten ±5-Rauschens liegt. Naechste Session:
  hier auf HOECHSTENS EINEN Advance-Schritt (1 Credit) begrenzen, statt
  (max 0 (remaining-advancement agenda)) den vollen Rest zu verlangen."
  [view zone]
  (if-let [agenda (first (filter agenda-card? (server-content view zone)))]
    (max 0 (remaining-advancement agenda))
    0))

(defn- credit-ready?
  "Kann sich die Corp JETZT leisten, (a) das teuerste einzelne unrezzte Ice
  auf `zone` zu rezzen (plus REZ-BUDGET-MARGIN) UND (b) eine dort bereits
  liegende, unfertig advancte Agenda fertig zu advancen (remaining-
  advancement-cost)? Scoring-Fenster-Dimension ':credits' (Design-Spec
  Scoring-Fenster-Zielmodell, Phase 1). Korrektur 2026-09-03, aus einer
  echten Partie: eine Agenda mit 3 von 4 Advancements lag im Remote, der
  Bot hatte 0 Credits -- credit-ready? kannte bis dahin nur das Rez-Budget,
  nicht die Advance-Kosten bis zum Score, meldete das Fenster also
  faelschlich als 'Credits bereit' und Regel 3.5 (siehe
  try-fund-score-line) konnte nie greifen."
  [view zone]
  (>= (corp-credit view)
      (+ (max-unrezzed-ice-rez-cost view zone) REZ-BUDGET-MARGIN
         (remaining-advancement-cost view zone))))

(defn- scoring-window-gap
  "Welche der drei Scoring-Fenster-Voraussetzungen fehlen gerade für
  `zone`: :credits (credit-ready?), :tax (safe-for-commitment?, Modus
  :one-time), :agenda (agenda-in-hand?). Leeres Set = Fenster offen (alle
  drei erfüllt). Design-Spec Scoring-Fenster-Zielmodell, Phase 1 -- ersetzt
  noch NICHT Regel 1/2/5.x, nur Regel 3s eigene Bedingung (siehe
  agenda-install-target); wird für JEDE Entscheidung geloggt (siehe
  decide), damit sich über viele Partien aggregieren lässt, welche
  Dimension am häufigsten blockiert, statt Konstanten nach Gefühl zu
  drehen."
  [view zone]
  (cond-> #{}
    (not (credit-ready? view zone)) (conj :credits)
    (not (safe-for-commitment? view zone)) (conj :tax)
    (not (agenda-in-hand? view)) (conj :agenda)))

(defn- remote-ice-target
  "Scoring-Remote-Zweig von Regel 2 (isoliert aus ice-install-target): greift,
  wenn eine Agenda in der Hand liegt ODER laut Decklist noch welche im
  Rest-Deck stecken (agendas-remaining-in-deck?) -- eine Agenda muss erst
  FÜRS INSTALLIEREN (Regel 3) in der Hand liegen, nicht schon fürs Vorbauen:
  echtes Netrunner-Spiel baut den Scoring-Remote vor, damit er fertig ist,
  wenn die Agenda kommt. Sind alle Agenda-Kopien bereits anderswo
  aufgetaucht (gescort/gestohlen/verworfen) UND liegt keine in der Hand,
  gibt es keine realistische Aussicht mehr -- dann baut Regel 2 nicht mehr
  blind weiter. MAX-ICE-PER-SCORING-REMOTE deckelt zusätzlich, wie oft
  Regel 2 einen bestehenden Remote nachicet, bevor sie abbricht."
  [view]
  (when (or (agenda-in-hand? view) (agendas-remaining-in-deck? view))
    (let [zone (scoring-remote-zone view)]
      (cond
        (nil? zone) :new-remote
        (and (not (remote-has-agenda? view zone))
             (not (safe-for-commitment? view zone))
             (< (count (server-ices view zone)) MAX-ICE-PER-SCORING-REMOTE))
        zone))))

(defn- ice-install-target
  "Wohin als nächstes Ice installiert werden soll: Zone-Keyword eines
  Zentralservers, Zone-Keyword eines bestehenden, noch unsicheren Scoring-
  Remotes, `:new-remote` für einen frischen Remote, oder nil (kein
  Ice-Install nötig). Dreistufige Reihenfolge (Breite-vor-Tiefe-Korrektur
  2026-09-02, aus einer echten Partie: der Bot stapelte 3 Ice auf HQ in Zug
  1, R&D/Archives blieben unberuehrt, waehrend der Scoring-Remote erst spaet
  und zufaellig entstand):
  1. central-zero-ice-needing (Breite): JEDER relevante Zentralserver
     bekommt zuerst sein erstes Ice.
  2. remote-ice-target (siehe dort): danach ist der Scoring-Remote
     wichtiger als ein zweites Ice auf einem schon versorgten Zentralserver
     -- dort wird gescort, das ist der Sinn des ganzen Spiels.
  3. central-depth-needing (Tiefe): erst danach werden Zentralserver weiter
     verstaerkt (CENTRAL-ICE-BASE-DEPTH ohne Breach-Nachweis, darueber nur
     mit beobachtetem Breach-Druck auf GENAU diesem Server)."
  [view run-history]
  (or (central-zero-ice-needing view run-history)
      (remote-ice-target view)
      (central-depth-needing view run-history)))

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

(defn- ice-install-surcharge
  "Zusätzlicher Credit-Preis, ein Ice auf `zone` zu installieren -- 1 Credit
  pro bereits vorhandenem Ice dort (game.core.installing/corp-install-cost:
  ice-cost = Anzahl Karten im Ziel-Slot). 0 für :new-remote (noch kein
  Slot, keine Vorbelegung)."
  [view zone]
  (if (= :new-remote zone)
    0
    (count (server-ices view zone))))

(defn- ice-install-affordable?
  "Kann sich die Corp den VOLLEN Installationspreis (Kartenkosten +
  ice-install-surcharge) für `card` auf `zone` leisten, OHNE dabei
  bereits installiertes eigenes Ice trashen zu müssen? Die Engine bietet
  als Rückfall-Zahlungsvariante an, vorhandenes Ice auf demselben Server
  zu trashen, um eine Differenz zu decken (game.core.installing/corp-
  install-pay) -- ohne diese Prüfung würde try-install-ice das Angebot
  wählen, auch wenn die Engine dafür eigene Ice-Karten opfern müsste.
  Korrektur 2026-09-03, aus einer echten Partie: der Bot trashte zweimal
  eigenes Ice auf HQ, um ein neues Ice günstiger zu installieren -- netto
  ein Server, der dadurch SCHWÄCHER statt stärker wurde."
  [view zone card]
  (>= (corp-credit view) (+ (or (:cost card) 0) (ice-install-surcharge view zone))))

(defn- try-install-ice
  [view legal-actions run-history]
  (when-let [target (ice-install-target view run-history)]
    (when-let [act (first-play-of-type legal-actions "ICE")]
      (when (ice-install-affordable? view target (get-in act [:args :card]))
        (let [central? (= target (central-needing-ice view run-history))]
          {:action act
           :reason (str "heuristic-corp: Regel "
                        (if central?
                          (str "1 (Zentralserver icen, Taxierung "
                               (or (:estimated-cost (server-threat-for view target)) 0)
                               "<=Runner-Einkommen/Zug(" ASSUMED-RUNNER-INCOME-PER-TURN "), "
                               (recent-breach-count run-history target (get view :turn 0))
                               " Breach(es) in " BREACH-WINDOW-TURNS " Zügen)")
                          "2 (Scoring-Remote aufbauen)")
                        " -> " (server-label target) ", installiere " (get-in act [:args :card :title]))})))))

(defn- find-legal [legal-actions command pred]
  (first (filter #(and (= command (:command %)) (pred %)) legal-actions)))

(defn- patience-exhausted?
  "Wartet der Bot schon SCORING-PATIENCE-TURNS Züge oder länger im Stall-
  Zustand (siehe track-remote-stall!: Remote an der Ice-Obergrenze, Agenda
  in der Hand, aber weiterhin nicht sicher genug)? Grundlage für den
  Geduldsgrenze-Bypass in agenda-install-target -- KEIN genereller
  Sicherheits-Bypass, nur für :tax/safe-for-commitment?, siehe dort."
  [run-history view]
  (when-let [{:keys [turn]} (:remote-stall-since @run-history)]
    (>= (- (get view :turn 0) turn) SCORING-PATIENCE-TURNS)))

(defn- agenda-install-target
  "Scoring-Fenster-Zielmodell Phase 1: `zone` qualifiziert, wenn :credits
  nicht im Gap steckt (credit-ready?) UND entweder :tax nicht im Gap steckt
  (safe-for-commitment?) ODER die Geduldsgrenze erreicht ist
  (patience-exhausted?, siehe dort und SCORING-PATIENCE-TURNS -- 'warten ist
  fuer die Corp die einzige garantiert verlierende Strategie', aus einer
  echten Partie: :scoring-gap #{:tax} blieb über mehrere Züge unverändert,
  der Remote war an der Ice-Obergrenze, der Bot klickte bei 20-29 Credits
  nur noch für Credits, statt die schon gehaltene Agenda zu riskieren). Ob
  eine Agenda tatsächlich installierbar ist, prüft die aufrufende
  try-install-agenda separat über legal-actions. KEIN Fast-Advance-Bypass
  mehr (Korrektur 2026-09-02, entfernt): der frühere Phase-2-Bypass nahm
  an, Seamless Launch könne die Agenda NOCH IM SELBEN Zug erreichen —
  unmöglich, place-advancement-counter verlangt eine Karte, die NICHT
  diesen Zug installiert wurde (siehe try-score-line). Eine echte
  Spielpartie zeigte den Schaden: der Bypass ließ eine unsichere Agenda
  installieren, Seamless Launch landete danach zufällig auf einem
  Ice-Ziel statt der (nicht wählbaren) Agenda. Frühestens NÄCHSTEN Zug ist
  Seamless Launch nutzbar, das deckt bereits der Standard-Risiko-Puffer
  (ASSUMED-RUNNER-INCOME-PER-TURN) ab."
  [view run-history]
  (when-let [zone (scoring-remote-zone view)]
    (when (and (seq (server-ices view zone))
               (not (remote-has-agenda? view zone))
               (credit-ready? view zone)
               (or (safe-for-commitment? view zone)
                   (patience-exhausted? run-history view)))
      zone)))

;; --- Regel 3: Agenda platzieren ---

(defn- try-install-agenda
  [view legal-actions run-history]
  (when-let [zone (agenda-install-target view run-history)]
    (when-let [act (first-play-of-type legal-actions "Agenda")]
      (let [threat (server-threat-for view zone)
            patience? (and (not (safe-for-commitment? view zone))
                           (patience-exhausted? run-history view))]
        {:action act
         :reason (str "heuristic-corp: Regel 3 (Agenda platzieren) -> " (server-label zone)
                      " estimated-cost=" (:estimated-cost threat)
                      ", Runner-Budget=" (+ (runner-credit view) ASSUMED-RUNNER-INCOME-PER-TURN)
                      ", akzeptable Armut=" (* (candidate-agenda-points view) COMMIT-RISK-CREDITS-PER-POINT)
                      ", Rez-Budget " (corp-credit view) ">=" (max-unrezzed-ice-rez-cost view zone)
                      "+" REZ-BUDGET-MARGIN
                      (when patience?
                        (str " -- Geduldsgrenze erreicht (SCORING-PATIENCE-TURNS="
                             SCORING-PATIENCE-TURNS "), installiere trotz unsicherer Taxierung"))
                      " -> installiere " (get-in act [:args :card :title]))}))))

;; --- Regel 4: Scoren ---

(defn- try-score-line
  "KEIN Sicherheits-Rueckzieher mehr auf advance-act (Korrektur 2026-09-02):
  einmal installiert, ist die Verpflichtung schon eingegangen (try-install-
  agenda hat sie vorher geprueft) -- eine liegende, unfertige Agenda ist
  die riskanteste Position im ganzen Spiel, ein erneuter Sicherheits-Check
  wuerde nur dazu fuehren, dass sie halbfertig liegen bleibt, statt fertig
  zu werden. Solange advance-act legal ist (Engine prueft Bezahlbarkeit:
  1 Klick + 1 Credit pro Advance), wird weiter advanced."
  [view legal-actions]
  (when-let [zone (scoring-remote-zone view)]
    (when (remote-has-agenda? view zone)
      (let [agenda (first (filter agenda-card? (server-content view zone)))
            clicks (get-in view [:corp :click] 0)
            remaining (remaining-advancement agenda)
            score-act (find-legal legal-actions "score" #(= (:title agenda) (get-in % [:args :card :title])))
            advance-act (find-legal legal-actions "advance" #(= (:title agenda) (get-in % [:args :card :title])))
            ;; place-advancement-counter (Seamless Launch) verlangt eine
            ;; Karte, die NICHT diesen Zug installiert wurde -- sonst landet
            ;; Seamless Launch mangels gueltigem Ziel zufaellig auf einer
            ;; ANDEREN installierten Karte (siehe agenda-install-target-
            ;; Korrektur, aus einer echten Partie bestaetigt).
            seamless-eligible? (not= :this-turn (:installed agenda))
            seamless-act (when seamless-eligible?
                          ;; seamless-act wird per Titel gesucht (first-play-of-titles),
                          ;; nicht per "erste spielbare Operation" -- sonst gewinnt eine
                          ;; frueher in der Hand liegende Operation (z.B. Hedge Fund) das
                          ;; Matching und die Score-Linie wird faelschlich uebersprungen.
                          (first-play-of-titles legal-actions #{"Seamless Launch"}))]
        (cond
          score-act
          {:action score-act
           :reason (str "heuristic-corp: Regel 4 (Scoren) -> " (:title agenda) " ist fertig advanced, score")}

          (and (<= remaining (+ clicks 2))
               seamless-act)
          {:action seamless-act
           :reason (str "heuristic-corp: Regel 4 (Score-Linie) -> Seamless Launch auf "
                        (:title agenda) ", Restadvancement=" remaining " <= Klicks(" clicks ")+2")}

          advance-act
          {:action advance-act
           :reason (str "heuristic-corp: Regel 4 (weiter advancen) -> " (:title agenda)
                        " Restadvancement=" remaining
                        (when-not seamless-eligible?
                          " (Seamless Launch heute nicht nutzbar -- erst naechsten Zug installiert)"))})))))

;; --- Prompt-Routing: Seamless-Launch-Ziel ---

(defn- select-seamless-target
  [view options]
  (when-let [zone (scoring-remote-zone view)]
    (when-let [agenda (first (filter agenda-card? (server-content view zone)))]
      (first (filter #(and (= :card (:type %)) (= (:cid agenda) (get-in % [:card :cid]))) options)))))

;; --- Prompt-Routing: Pflicht-Abwurf am Zugende ---

(defn- card-options [options]
  (filter #(= :card (:type %)) options))

(defn- discard-prompt?
  "Ist `prompt` der Pflicht-Abwurf am Zugende (game.core.turns/
  handle-end-of-turn-discard, :prompt (str \"Discard down to \" ...))? Am
  Nachrichtentext erkannt, nicht an der Options-Form -- ein normaler Select-
  Prompt kann strukturell genauso aussehen (Handkarten + 'Hide'-Button)."
  [prompt]
  (str/starts-with? (or (:msg prompt) "") "Discard down to"))

(defn- discard-choice
  "Wählt aus den Optionen des Pflicht-Abwurf-Prompts die güngstigste
  Handkarte -- NIEMALS eine Agenda (Bug aus einer echten Partie: der
  Abwurf-Prompt fiel ungehandelt an den Zufall durch und warf 'Offworld
  Office', eine Agenda, ab -- das erklärt, warum über zwei Partien nie eine
  Agenda installiert wurde). Bewertung analog zu HAND-CARD-DISCARD-COST
  (Asset/Upgrade teurer als Ice/Operation zu verlieren). nil, wenn außer
  Agenden nichts zur Auswahl steht (z.B. eine überfüllte Hand aus
  ausschließlich Agenden) -- dann bleibt nur der Zufalls-Fallback, sichtbar
  im Log markiert (siehe random-fallback-with-log)."
  [options]
  (let [cands (remove #(agenda-card? (:card %)) (card-options options))]
    (when (seq cands)
      (apply min-key #(get HAND-CARD-DISCARD-COST (:type (:card %)) 2) cands))))

;; --- Prompt-Routing: Gratis-/Rabatt-Rez- und -Install-Angebote ---

(defn- rez-offer-choice
  "Wählt aus den Optionen eines Select-Prompts das teuerste bereits
  installierte, unrezzte eigene Ice -- für Gratis-/Rabatt-Rez-Angebote
  (z.B. Send a Message :stolen/:on-score: 'rez an ice, ignoring all
  costs'; eine echte Spielpartie zeigte, dass so ein Angebot bisher
  ungenutzt an random-delegate durchfiel und abgelehnt wurde). Strukturell
  erkannt (installiert + unrezzt + Ice-Typ), nicht am Prompt-Text -- robust
  auch, wenn ein Angebot zusätzlich andere Optionen mitbringt. nil, wenn
  keine passende Karte unter den Optionen ist."
  [options]
  (let [rezzable (filter #(let [c (:card %)]
                            (and (= "ICE" (:type c)) (:installed c) (not (:rezzed c))))
                         (card-options options))]
    (when (seq rezzable)
      (apply max-key #(or (:cost (:card %)) 0) rezzable))))

(defn- free-install-offer-choice
  "Wählt aus den Optionen eines Select-Prompts ein Ice zum Gratis-/Rabatt-
  Install (z.B. Brân 1.0: 'install an ice from HQ or Archives'). Greift
  NUR, wenn laut Regel 1/2 ohnehin Bedarf besteht (central-needing-ice
  bzw. ein noch unsicherer, unter der Ice-Obergrenze liegender Scoring-
  Remote) -- ein Gratis-Install ohne Verwendungszweck ist kein Gewinn.
  Filtert defensiv auf Ice-Typ-Optionen, unabhängig davon, ob der Prompt
  (bot.legal-Fallback ohne :selectable von der Engine, siehe dort)
  zusätzlich fachfremde Optionen mitbringt."
  [view run-history options]
  (let [ice-opts (filter #(= "ICE" (:type (:card %))) (card-options options))
        remote-needs-ice? (when-let [zone (scoring-remote-zone view)]
                            (and (not (remote-has-agenda? view zone))
                                 (not (safe-for-commitment? view zone))
                                 (< (count (server-ices view zone)) MAX-ICE-PER-SCORING-REMOTE)))]
    (when (and (seq ice-opts) (or (central-needing-ice view run-history) remote-needs-ice?))
      (first ice-opts))))

;; --- Prompt-Routing: Trash-um-zu-bezahlen-Ice (Rückfallabsicherung) ---

(defn- trash-to-pay-ice-prompt?
  "Ist `prompt` der Trash-um-zu-bezahlen-Prompt (game.core.installing/
  corp-install-pay: reicht das Kreditguthaben für einen Ice-Install nicht,
  bietet die Engine an, vorhandenes Ice auf demselben Server zu trashen)?
  Am Nachrichtentext erkannt ('Trash ice protecting ...', siehe dort)."
  [prompt]
  (str/starts-with? (or (:msg prompt) "") "Trash ice protecting"))

(defn- trash-to-pay-ice-choice
  "Wählt für den Trash-um-zu-bezahlen-Prompt IMMER zuerst unrezztes Ice --
  rezztes Ice schützt den Server aktiv, ein Tausch 'eigenes Ice weg für ein
  neues billiger' ist strukturell ein Verlustgeschäft (Korrektur
  2026-09-03, aus einer echten Partie: der Bot trashte zweimal eigenes Ice
  auf HQ, um ein neues Ice für 0 Credit zu installieren, und machte den
  Server damit netto SCHWÄCHER). Reine Rückfallabsicherung -- try-install-
  ice (siehe ice-install-affordable?) verhindert bereits, dass der Bot
  selbst in diesen Prompt hineinläuft; dieser Handler greift nur, falls die
  Engine ihn dennoch zeigt (z.B. durch einen dem Bot unbekannten
  Kostenmodifikator)."
  [options]
  (let [cands (card-options options)
        unrezzed (remove #(:rezzed (:card %)) cands)]
    (first (or (seq unrezzed) cands))))

(defn- random-fallback-with-log
  "Delegiert an random-delegate, hängt aber eine sichtbare Markierung an
  den Reason-String -- 'kein Regel-Handler fuer diesen Prompt-Typ' soll im
  Decision-Log auffallen, statt unmarkiert als 'random-bot: ...'
  durchzurutschen (Ergänzung aus einer echten Partie: mehrere Select-
  Prompts fielen unbemerkt an den Zufall durch, weil nichts das anzeigte)."
  [random-delegate view prompt options]
  (update (bp/on-prompt random-delegate view prompt options)
          :reason #(str "heuristic-corp: Regel 6 (kein Playbook-Handler fuer "
                        (name (:prompt-type prompt)) "-Prompt) -> " %)))

;; --- Regel 5: Econ ---

(defn- try-install-econ-asset
  [_view legal-actions]
  (when-let [act (first-play-of-titles legal-actions ECON-ASSET-CARDS)]
    {:action act
     :reason (str "heuristic-corp: Regel 5.1 (Econ-Asset installieren) -> "
                  (get-in act [:args :card :title])
                  " (ungeschützter neuer Remote -- trivial trashbar, kein Schutz geplant)")}))

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

(defn- should-draw?
  "Ziehen (Regel 5.5) ist reine Rückfallaktion (sitzt in try-econs or-Kette
  nach allen produktiven Regeln 5.1-5.4 -- feuert also nur, wenn nichts
  Besseres greift) mit Vorrang vor dem generischen Credit-Klick (Regel
  5.6). Keine harte Ein-Klick-pro-Zug-Grenze: manchmal muss der Bot
  mehrfach pro Zug nach Agenden/Ice graben.

  Zweistufig statt einzelner harter Schwelle:
  1. Unterhalb DRAW-SAFETY-BUFFER: nie (harter Boden, siehe dort -- die
     Corp zieht jeden Zug ohnehin verpflichtend, freiwilliges Ziehen würde
     das eigene Decking nur beschleunigen).
  2. Zwischen DRAW-SAFETY-BUFFER und DECK-CAUTION-THRESHOLD: nur noch eine
     wirklich dünne Hand (HAND-SIZE-LOW) rechtfertigt Ziehen -- 'keine
     Agenda in der Hand' allein reicht hier nicht mehr, das Deck wird
     knapp.
  3. Ab DECK-CAUTION-THRESHOLD aufwärts: normal, 'keine Agenda' ODER dünne
     Hand lösen Ziehen aus.

  'Kein Ice in der Hand' ist BEWUSST kein eigenständiger Auslöser
  (Regression vom 2026-09-02): das Gateway-Deck hat nur 16 von 34
  Ice-Karten -- diese Bedingung wird spätestens ab Mitte der Partie
  dauerhaft wahr und triggerte vorher praktisch endloses Ziehen.

  'Reichlich Credits' (PLENTY-CREDITS) WURDE entfernt (Korrektur
  2026-09-02: 'reich sein ist kein Ziehen-Grund'), aber aus einer echten
  Partie mit anderer Begründung WIEDER aufgenommen: dort klickte der Bot
  bei 13-16 Credits (weit über jedem absehbaren Bedarf) wiederholt für
  einen einzelnen Credit, obwohl nichts Produktives mehr griff -- kein
  Widerspruch zur früheren Entfernung, weil Regel 5.5 strukturell schon
  IMMER hinter allen produktiveren Regeln (1-4, 5.1-5.4, 2.5) steht: dieser
  Auslöser konkurriert nur noch gegen den reinen Credit-Klick (Regel 5.6),
  nie gegen eine wichtigere Priorität. Ein ungebrauchter Credit ist
  schlechter als eine neue Karte."
  [view]
  (let [dc (deck-count view)]
    (and (> dc DRAW-SAFETY-BUFFER)
         (or (<= (hand-size view) HAND-SIZE-LOW)
             (and (>= dc DECK-CAUTION-THRESHOLD)
                  (or (not (agenda-in-hand? view))
                      (>= (corp-credit view) PLENTY-CREDITS)))))))

(defn- try-draw
  [view legal-actions]
  (when (should-draw? view)
    (when-let [act (find-legal legal-actions "draw" (constantly true))]
      {:action act
       :reason (str "heuristic-corp: Regel 5.5 (Karte ziehen) -> Agenda-in-Hand="
                    (agenda-in-hand? view) " Ice-in-Hand=" (ice-in-hand? view)
                    " Handgroesse=" (hand-size view) " Credits=" (corp-credit view))})))

(defn- try-econ
  [view legal-actions]
  (or (try-install-econ-asset view legal-actions)
      (try-rez-econ-asset view legal-actions)
      (try-econ-click-ability view legal-actions)
      (try-play-econ-operation view legal-actions)
      (try-draw view legal-actions)
      (when-let [act (find-legal legal-actions "credit" (constantly true))]
        {:action act :reason "heuristic-corp: Regel 5.6 (Klick fuer Credit)"})))

;; --- Regel 3.5: Credits fuer eine laufende Score-Linie beschaffen ---

(defn- score-line-blocked-by-credits?
  "Liegt im Scoring-Remote eine unfertig advancte Agenda, UND ist das
  naechste 'advance' gerade NICHT möglich, weil die Corp 0 Credits hat
  (corp-click-actions bietet 'advance' nur bei (pos? credits) an -- die
  Basisaktion kostet immer genau 1 Klick + 1 Credit)? Grundlage für
  try-fund-score-line: eine liegende, unfertige Agenda ist die riskanteste
  Position im Spiel (siehe try-score-line) -- fehlt NUR das Geld dafür, ist
  Credits-Beschaffen wichtiger als jedes neue Projekt."
  [view legal-actions]
  (when-let [zone (scoring-remote-zone view)]
    (when (remote-has-agenda? view zone)
      (let [agenda (first (filter agenda-card? (server-content view zone)))]
        (and (pos? (remaining-advancement agenda))
             (zero? (corp-credit view))
             (nil? (find-legal legal-actions "advance"
                               #(= (:title agenda) (get-in % [:args :card :title])))))))))

(defn- try-fund-score-line
  "Regel 3.5: blockiert eine laufende Score-Linie AUSSCHLIESSLICH das Fehlen
  von Credits (score-line-blocked-by-credits?), ist Credits-Beschaffen die
  hoechste Prioritaet -- vor neuen Projekten (weiteres Ice auf einem
  ANDEREN/neuen Server, Regel 1/2) und vor Ziehen (Regel 5.5, bringt keine
  Credits). Aus einer echten Partie: Agenda mit 3/4 Advancements im Remote,
  0 Credits -- der Bot bestueckte stattdessen einen neuen Server und zog,
  statt die schon fast fertige Agenda zu finanzieren. Nutzt dieselbe
  Prioritaet wie Regel 5.1-5.4/5.6 (Asset installieren -> Asset rezzen ->
  Klick-Ability -> Operation spielen -> genereller Credit-Klick), NUR OHNE
  Regel 5.5 (Ziehen bringt keine Credits fuers naechste Advance)."
  [view legal-actions]
  (when (score-line-blocked-by-credits? view legal-actions)
    (some-> (or (try-install-econ-asset view legal-actions)
                (try-rez-econ-asset view legal-actions)
                (try-econ-click-ability view legal-actions)
                (try-play-econ-operation view legal-actions)
                (when-let [act (find-legal legal-actions "credit" (constantly true))]
                  {:action act :reason "heuristic-corp: Regel 5.6 (Klick fuer Credit)"}))
            (update :reason #(str "heuristic-corp: Regel 3.5 (Credits fuer laufende Score-Linie beschaffen) -> " %)))))

;; --- Prompt-Routing: Server-Wahl ---

(defn- select-target-server
  "Server-Name-String für den Install-Prompt von `card` — nil, wenn `card`
  gerade nicht Teil einer aktiven Install-Entscheidung ist."
  [view card run-history]
  (case (:type card)
    "ICE" (some-> (ice-install-target view run-history) server-label)
    "Agenda" (some-> (agenda-install-target view run-history) server-label)
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

;; --- Rez-Entscheidung im Run-Fenster ---

(defn- rez-decision
  "Rez lohnt sich immer, wenn bezahlbar: unrezztes Ice schützt nichts (nur
  rezztes Ice feuert Subroutinen), ein bereits laufender Run bietet keinen
  Vorteil durch Zurückhalten (kein Bluffing-Repertoire in v1 — siehe
  Design-Spec, Korrektur-Absatz zur Rez-Entscheidung: ein Vorher/Nachher-
  Vergleich über bot.eval aus Corp-Sicht wäre degeneriert, weil die Corp
  ihre eigenen Ice-Werte immer kennt)."
  [view legal-actions]
  (if-let [act (find-legal legal-actions "rez" (constantly true))]
    (let [ice (get-in act [:args :card])
          rez-cost (or (:cost ice) 0)
          corp-credit (get-in view [:corp :credit] 0)]
      (if (<= rez-cost corp-credit)
        {:action act
         :reason (str "heuristic-corp: Rez -> " (:title ice) " rez-cost=" rez-cost
                      " <= corp-credit(" corp-credit "), Subroutinen sollen wirken")}
        {:action (find-legal legal-actions "continue" (constantly true))
         :reason (str "heuristic-corp: kein Rez -> " (:title ice) " rez-cost=" rez-cost
                      " > corp-credit(" corp-credit ")")}))
    {:action (first legal-actions)
     :reason "heuristic-corp: Rez-Fenster ohne Rez-Option, erste angebotene Option"}))

;; --- Breach-Zähler (interner Bot-State) ---

(defn- track-run-progress!
  "Aktualisiert `run-history` bei JEDEM decide-Aufruf mit dem aktuellen Run-
  Zustand (auch außerhalb eines Runs -- dann räumt es einfach auf). Zählt
  einen 'Breach', sobald ein Run in :movement wechselt: das ist die Phase,
  in der der Runner an ALLEN Ice vorbei ist, und (empirisch über
  bot.game-runner geprüft, siehe heuristic-corp-test) die einzige Run-Phase,
  in der die Corp zuverlässig gefragt wird -- :run/success bekommt die Corp
  NICHT immer (kein Rez-Fenster dort), auf einen Hook an :success wäre also
  kein Verlass. 'Breach' heißt hier folglich 'an der Verteidigung
  vorbeigekommen', nicht zwingend 'hat erfolgreich zugegriffen' (Jack-out
  direkt danach zählt hier bewusst mit -- die Verteidigung hat trotzdem
  nicht getaxt). Dedupliziert Mehrfachaufrufe INNERHALB derselben
  :movement-Phase (z.B. Rez-Retry-Schleifen) über last-run-phase/
  last-run-server, damit ein einzelner Run nicht mehrfach gezählt wird."
  [run-history view]
  (let [run (:run view)
        phase (:phase run)
        zone (some-> run :server last name keyword)
        turn (get view :turn 0)
        {:keys [last-run-phase last-run-server]} @run-history]
    (when (and (= :movement phase) zone
               (not (and (= last-run-phase :movement) (= last-run-server zone))))
      (swap! run-history update :breaches (fnil conj []) {:zone zone :turn turn}))
    (swap! run-history assoc :last-run-phase phase :last-run-server zone)))

(defn- track-remote-stall!
  "Aktualisiert `run-history` bei JEDEM decide-Aufruf mit dem Stall-Zustand
  des Scoring-Remotes: Ice-Obergrenze erreicht (MAX-ICE-PER-SCORING-REMOTE),
  weiterhin nicht sicher genug (safe-for-commitment?), Agenda liegt in der
  Hand. Merkt sich den ERSTEN Zug, in dem dieser Zustand beobachtet wurde
  (:remote-stall-since) -- patience-exhausted? vergleicht das gegen den
  aktuellen Zug. Wechselt der Remote (anderer zone-Wert) oder verlässt der
  Stall-Zustand, wird der Zähler zurückgesetzt: die Geduldsgrenze bezieht
  sich auf EINEN konkreten, andauernden Stall, nicht auf die Partie
  insgesamt."
  [run-history view]
  (let [zone (scoring-remote-zone view)
        turn (get view :turn 0)
        stalled? (and zone
                      (not (remote-has-agenda? view zone))
                      (agenda-in-hand? view)
                      (>= (count (server-ices view zone)) MAX-ICE-PER-SCORING-REMOTE)
                      (not (safe-for-commitment? view zone)))]
    (if stalled?
      (swap! run-history update :remote-stall-since
             (fn [m] (if (and m (= (:zone m) zone)) m {:zone zone :turn turn})))
      (swap! run-history dissoc :remote-stall-since))))

;; --- Bot ---

(defn- current-scoring-gap
  "scoring-window-gap (siehe dort) für den aktuellen Scoring-Remote (siehe
  scoring-remote-zone) -- nil ohne einen, das Fenster-Konzept greift erst,
  sobald ein Remote existiert."
  [view]
  (when-let [zone (scoring-remote-zone view)]
    (scoring-window-gap view zone)))

;; --- Regel 2.5: gezielt nach der fehlenden Agenda graben ---

(defn- max-hand-size
  "Maximale Handkartenzahl AUS DEM SPIELSTATE (game.core.hand-size,
  Default 5, aber Karten/Effekte können sie verändern -- nie fest
  annehmen)."
  [view]
  (get-in view [:corp :hand-size :total] 5))

(defn- hand-card-has-legal-action?
  "Hat `card` JETZT irgendeine legale Aktion (spielen, Ability, rezzen,
  advancen)? Wenn nicht, ist sie diesen Zug ohnehin totes Gewicht --
  Grundlage für discard-cost."
  [legal-actions card]
  (boolean (some #(and (contains? #{"play" "ability" "rez" "advance"} (:command %))
                       (= (:cid card) (get-in % [:args :card :cid])))
                 legal-actions)))

(defn- discard-cost
  "Ungefährer Wert, `card` an den Pflicht-Abwurf am Zugende zu verlieren --
  0, wenn sie JETZT ohnehin keine legale Aktion hat (nichts zu tun, siehe
  hand-card-has-legal-action?), sonst ein Platzhalterwert nach Kartentyp
  (HAND-CARD-DISCARD-COST)."
  [legal-actions card]
  (if (hand-card-has-legal-action? legal-actions card)
    (get HAND-CARD-DISCARD-COST (:type card) 2)
    0))

(defn- draw-worth-discard-risk?
  "Lohnt sich Ziehen (Regel 2.5), obwohl die Hand schon an/über der
  Maximalgröße liegt und ein Pflicht-Abwurf am Zugende droht? Kein hartes
  Verbot, sondern eine Abwägung: Wert des Suchens (AGENDA-SEARCH-VALUE)
  gegen den Wert der Karte, die am ehesten geopfert würde (die günstigste
  in der Hand, discard-cost) -- ein Abwurf ist der Preis, meist billiger
  als ein Zug ohne jede andere produktive Option, aber keine Garantie:
  eine Hand voller wertvoller, gerade spielbarer Karten gewinnt den
  Vergleich."
  [view legal-actions]
  (let [hand (corp-hand view)]
    (or (< (count hand) (max-hand-size view))
        (empty? hand)
        (>= AGENDA-SEARCH-VALUE (apply min (map #(discard-cost legal-actions %) hand))))))

(defn- try-draw-for-scoring-window
  "Fehlt dem Scoring-Fenster AUSSCHLIESSLICH :agenda (Credits UND Taxierung
  stehen schon), ist Ziehen keine Rückfallaktion mehr, sondern die
  produktivste verfügbare Handlung -- der Bot gräbt gezielt nach der
  fehlenden Zutat, statt Econ zu spielen oder zu klicken (Ergänzung aus der
  Gap-Statistik, 2026-09-02: :agenda löste :tax als häufigsten Blocker ab,
  sobald Phase 2 griff). Outrankt Regel 5.1-5.6 (try-econ), aber NICHT
  Regel 1/2 (echte, von diesem Fenster unabhängige Verteidigungslücken
  bleiben Vorrang). Begrenzt durch DRAW-SAFETY-BUFFER (harter Boden) UND
  DECK-CAUTION-THRESHOLD (Zurückhaltung bei kleinem Restdeck) -- dieselben
  Grenzen wie Regel 5.5, nur höher priorisiert: in der Caution-Zone greift
  diese Sonderpriorität NICHT, dort entscheidet wie gehabt Regel 5.5.
  ZUSÄTZLICH begrenzt durch draw-worth-discard-risk? -- eine volle Hand
  bedeutet einen Pflicht-Abwurf am Zugende, kein hartes Verbot, aber eine
  Abwägung Wert-des-Suchens gegen Wert-des-drohenden-Abwurfs."
  [view legal-actions]
  (when (= #{:agenda} (current-scoring-gap view))
    (let [dc (deck-count view)]
      (when (and (> dc DRAW-SAFETY-BUFFER)
                 (>= dc DECK-CAUTION-THRESHOLD)
                 (draw-worth-discard-risk? view legal-actions))
        (when-let [act (find-legal legal-actions "draw" (constantly true))]
          {:action act
           :reason (str "heuristic-corp: Regel 2.5 (Scoring-Fenster fehlt nur Agenda -> gezielt ziehen) "
                        "-> Handgroesse=" (hand-size view) "/" (max-hand-size view) " Deck=" dc)})))))

(defrecord HeuristicCorpBot [random-delegate run-history]
  bp/Bot
  (decide [_ view legal-actions]
    (track-run-progress! run-history view)
    (track-remote-stall! run-history view)
    (let [decision (if (:run view)
                     (rez-decision view legal-actions)
                     ;; try-score-line ZUERST (Korrektur 2026-09-02): eine
                     ;; liegende, unfertige Agenda ist die riskanteste
                     ;; Position im Spiel -- Fertigstellen schlägt neues
                     ;; Ice bauen, sonst blockiert Regel 1/2 das Advancen
                     ;; ganzer Züge lang (in einer echten Partie bestätigt).
                     (or (try-score-line view legal-actions)
                         (try-fund-score-line view legal-actions)
                         (try-install-ice view legal-actions run-history)
                         (try-install-agenda view legal-actions run-history)
                         (try-draw-for-scoring-window view legal-actions)
                         (try-econ view legal-actions)
                         {:action (first legal-actions)
                          :reason "heuristic-corp: Regel 6 (Fallback) -> keine Regel griff, erste Option"}))]
      ;; :scoring-gap wird für JEDE Entscheidung mitgeloggt (siehe
      ;; bot.game-runner/decide-one!), unabhängig davon, welche Regel
      ;; letztlich griff -- Grundlage für die Gap-Aggregation über viele
      ;; Partien (Design-Spec Scoring-Fenster-Zielmodell, Phase 1).
      (if-let [gap (current-scoring-gap view)]
        (assoc decision :scoring-gap gap)
        decision)))
  (on-prompt [_ view prompt options]
    (if (= :mulligan (:prompt-type prompt))
      (mulligan-decision view options)
      (or (when-let [label (select-target-server view (:card prompt) run-history)]
            (choose-by-label options label
                              (str "heuristic-corp: Server-Wahl fuer " (:title (:card prompt)) " -> " label)))
          (when (= :select (:prompt-type prompt))
            (or (when (discard-prompt? prompt)
                  (when-let [opt (discard-choice options)]
                    {:option opt
                     :reason (str "heuristic-corp: Regel (Pflicht-Abwurf) -> " (:label opt)
                                  " (Typ=" (:type (:card opt)) ", Kosten="
                                  (get HAND-CARD-DISCARD-COST (:type (:card opt)) 2)
                                  ", Agenden nie abgeworfen)")}))
                (when-let [opt (select-seamless-target view options)]
                  {:option opt :reason (str "heuristic-corp: Seamless-Launch-Ziel -> " (:label opt))})
                (when-let [opt (rez-offer-choice options)]
                  {:option opt :reason (str "heuristic-corp: Regel (Gratis-/Rabatt-Rez-Angebot) -> "
                                            (:label opt) " (teuerstes rezzbares Ice)")})
                (when-let [opt (free-install-offer-choice view run-history options)]
                  {:option opt :reason (str "heuristic-corp: Regel (Gratis-/Rabatt-Install-Angebot) -> "
                                            (:label opt))})
                (when (trash-to-pay-ice-prompt? prompt)
                  (when-let [opt (trash-to-pay-ice-choice options)]
                    {:option opt
                     :reason (str "heuristic-corp: Regel (Trash-um-zu-bezahlen, Rueckfall) -> " (:label opt)
                                  " (unrezzt bevorzugt, nie wertvolles Ice zuerst geopfert)")}))))
          (random-fallback-with-log random-delegate view prompt options)))))

(defn heuristic-corp-bot
  "Baut einen seedbaren Heuristik-Corp-Bot. `seed` steuert nur den
  eingebetteten Random-Delegate (für Prompts/Fallbacks ohne eigene Regel) —
  das Playbook selbst ist deterministisch. `run-history` ist rein interner
  Bot-State (Breach-Zähler pro Zentralserver, siehe track-run-progress!;
  Scoring-Remote-Stall-Zeitpunkt für die Geduldsgrenze, siehe
  track-remote-stall!) -- kein Zugriff auf rohen Engine-State, nur
  Buchführung über ohnehin per view gesehene Werte."
  [seed]
  (->HeuristicCorpBot (random/random-bot seed)
                      (atom {:breaches [] :last-run-phase nil :last-run-server nil})))
