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
  "Agenda-Kopien in bot.cards/gateway-corp (Offworld Office x3, Send a
  Message x2) -- fest, weil der Bot ausschließlich dieses eine Deck spielt
  (YAGNI, wie MULLIGAN-ECON-CARDS). Für 'stecken laut Decklist noch Agenden
  im Rest-Deck?' (Regel 2 Vorbau-Gate, siehe agendas-remaining-in-deck?)."
  5)

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

(defn- central-needing-ice [view run-history]
  (first (filter #(central-tax-too-low? view %) (central-priority-order view run-history))))

(defn- scoring-remote-zone
  "Auswahl-Heuristik: erst Remote mit Agenda drin, sonst Remote mit >=1 Ice
  ohne Agenda, sonst nil (Design-Spec Teil 2 — Single-Remote-Fokus, YAGNI)."
  [view]
  (let [remotes (filter (fn [[z _]] (remote-zone? z)) (corp-servers view))]
    (or (ffirst (filter (fn [[_ sv]] (some agenda-card? (:content sv))) remotes))
        (ffirst (filter (fn [[_ sv]] (and (seq (:ices sv)) (not-any? agenda-card? (:content sv)))) remotes)))))

(defn- remote-has-agenda? [view zone]
  (boolean (some agenda-card? (server-content view zone))))

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

(defn- credit-ready?
  "Kann sich die Corp JETZT leisten, das teuerste einzelne unrezzte Ice auf
  `zone` zu rezzen (plus REZ-BUDGET-MARGIN)? Scoring-Fenster-Dimension
  ':credits' (Design-Spec Scoring-Fenster-Zielmodell, Phase 1)."
  [view zone]
  (>= (corp-credit view) (+ (max-unrezzed-ice-rez-cost view zone) REZ-BUDGET-MARGIN)))

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

(defn- ice-install-target
  "Wohin als nächstes Ice installiert werden soll: Zone-Keyword eines
  Zentralservers, Zone-Keyword eines bestehenden, noch unsicheren Scoring-
  Remotes, `:new-remote` für einen frischen Remote, oder nil (kein
  Ice-Install nötig). Der Scoring-Remote-Zweig (Regel 2, sowohl Eröffnung
  als auch weiteres Ice) greift, wenn eine Agenda in der Hand liegt ODER
  laut Decklist noch welche im Rest-Deck stecken (agendas-remaining-in-
  deck?) -- eine Agenda muss erst FÜRS INSTALLIEREN (Regel 3) in der Hand
  liegen, nicht schon fürs Vorbauen: echtes Netrunner-Spiel baut den
  Scoring-Remote vor, damit er fertig ist, wenn die Agenda kommt. Sind alle
  Agenda-Kopien bereits anderswo aufgetaucht (gescort/gestohlen/verworfen)
  UND liegt keine in der Hand, gibt es keine realistische Aussicht mehr --
  dann baut Regel 2 nicht mehr blind weiter. MAX-ICE-PER-SCORING-REMOTE
  deckelt zusätzlich, wie oft Regel 2 einen bestehenden Remote nachicet,
  bevor sie abbricht (siehe dort)."
  [view run-history]
  (or (central-needing-ice view run-history)
      (when (or (agenda-in-hand? view) (agendas-remaining-in-deck? view))
        (let [zone (scoring-remote-zone view)]
          (cond
            (nil? zone) :new-remote
            (and (not (remote-has-agenda? view zone))
                 (not (safe-for-commitment? view zone))
                 (< (count (server-ices view zone)) MAX-ICE-PER-SCORING-REMOTE))
            zone)))))

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
  [view legal-actions run-history]
  (when-let [target (ice-install-target view run-history)]
    (when-let [act (first-play-of-type legal-actions "ICE")]
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
                      " -> " (server-label target) ", installiere " (get-in act [:args :card :title]))}))))

(defn- find-legal [legal-actions command pred]
  (first (filter #(and (= command (:command %)) (pred %)) legal-actions)))

(defn- fast-advance-line-available?
  "Ist beim JETZIGEN Install schon eine Score-Linie mit minimaler
  Exposition absehbar (Seamless Launch in der Hand, Restadvancement der
  Kandidaten-Agenda passt in die nach dem Install diesen Zug noch
  verfügbaren Klicks + Seamless-Bonus)? Dann ist die Agenda nur eine
  Runner-Runde (oder weniger) lang exponiert -- Taxierung/Commitment-
  Risiko spielt dann kaum eine Rolle. Wendet dieselbe Deadline-Logik wie
  try-score-line (Regel 4) an, aber VOR dem Install auf die Kandidaten-
  Agenda, statt erst danach auf eine schon installierte -- Design-Spec
  Scoring-Fenster-Zielmodell, Phase 2: die Prüfung gehört an die Install-
  Entscheidung, nicht erst ans Scoren."
  [view legal-actions]
  (when-let [agenda (first (filter agenda-card? (corp-hand view)))]
    (when-let [seamless-act (first-play-of-titles legal-actions #{"Seamless Launch"})]
      (let [clicks-after-install (max 0 (dec (get-in view [:corp :click] 0)))
            requirement (or (:current-advancement-requirement agenda)
                            (:advancementcost agenda) 0)]
        (<= requirement (+ clicks-after-install 2))))))

(defn- agenda-install-target
  "Scoring-Fenster-Zielmodell Phase 1+2: `zone` qualifiziert, wenn EINE
  Fast-Advance-Linie ansteht (fast-advance?, deren minimale Exposition
  Sicherheitsfragen ohnehin irrelevant macht) ODER :tax UND :credits nicht
  (mehr) im Gap stecken (scoring-window-gap ohne :agenda -- ob eine Agenda
  tatsächlich installierbar ist, prüft die aufrufende try-install-agenda
  separat über legal-actions)."
  [view fast-advance?]
  (when-let [zone (scoring-remote-zone view)]
    (when (and (seq (server-ices view zone))
               (not (remote-has-agenda? view zone))
               (or fast-advance?
                   (empty? (disj (scoring-window-gap view zone) :agenda))))
      zone)))

;; --- Regel 3: Agenda platzieren ---

(defn- try-install-agenda
  [view legal-actions]
  (let [fast-advance? (fast-advance-line-available? view legal-actions)]
    (when-let [zone (agenda-install-target view fast-advance?)]
      (when-let [act (first-play-of-type legal-actions "Agenda")]
        (let [threat (server-threat-for view zone)]
          {:action act
           :reason (str "heuristic-corp: Regel 3 (Agenda platzieren) -> " (server-label zone)
                        (if fast-advance?
                          " Fast-Advance-Linie absehbar (Seamless Launch, minimale Exposition) -> Sicherheitsfrage übergangen"
                          (str " estimated-cost=" (:estimated-cost threat)
                               ", Runner-Budget=" (+ (runner-credit view) ASSUMED-RUNNER-INCOME-PER-TURN)
                               ", akzeptable Armut=" (* (candidate-agenda-points view) COMMIT-RISK-CREDITS-PER-POINT)
                               ", Rez-Budget " (corp-credit view) ">=" (max-unrezzed-ice-rez-cost view zone)
                               "+" REZ-BUDGET-MARGIN))
                        " -> installiere " (get-in act [:args :card :title]))})))))

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
            seamless-act (first-play-of-titles legal-actions #{"Seamless Launch"})]
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
          ;; seamless-act wird per Titel gesucht (first-play-of-titles), nicht
          ;; per "erste spielbare Operation" -- sonst gewinnt eine frueher in
          ;; der Hand liegende Operation (z.B. Hedge Fund) das Matching und
          ;; die Score-Linie wird faelschlich uebersprungen.
          (and (<= remaining (+ clicks 2))
               seamless-act)
          {:action seamless-act
           :reason (str "heuristic-corp: Regel 4 (Score-Linie) -> Seamless Launch auf "
                        (:title agenda) ", Restadvancement=" remaining " <= Klicks(" clicks ")+2")}

          (not (safe-for-commitment? view zone (or (:agendapoints agenda) DEFAULT-AGENDA-POINTS)))
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

  'Reichlich Credits' und 'kein Ice in der Hand' sind BEWUSST keine
  eigenständigen Auslöser (Korrektur 2026-09-02 bzw. Regression vom
  2026-09-02): reich sein ist kein Ziehen-Grund, und das Gateway-Deck hat
  nur 16 von 34 Ice-Karten -- 'kein Ice' wird spätestens ab Mitte der
  Partie dauerhaft wahr und triggerte vorher praktisch endloses Ziehen."
  [view]
  (let [dc (deck-count view)]
    (and (> dc DRAW-SAFETY-BUFFER)
         (or (<= (hand-size view) HAND-SIZE-LOW)
             (and (>= dc DECK-CAUTION-THRESHOLD)
                  (not (agenda-in-hand? view)))))))

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

;; --- Prompt-Routing: Server-Wahl ---

(defn- select-target-server
  "Server-Name-String für den Install-Prompt von `card` — nil, wenn `card`
  gerade nicht Teil einer aktiven Install-Entscheidung ist."
  [view card run-history]
  (case (:type card)
    "ICE" (some-> (ice-install-target view run-history) server-label)
    ;; fast-advance? hier immer true: die Gate-Entscheidung ist schon in
    ;; try-install-agenda gefallen (das legal-actions hat), dieser Aufruf
    ;; dient nur noch der Server-Wahl-Routing für den Folge-Prompt.
    "Agenda" (some-> (agenda-install-target view true) server-label)
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
    (let [decision (if (:run view)
                     (rez-decision view legal-actions)
                     (or (try-install-ice view legal-actions run-history)
                         (try-install-agenda view legal-actions)
                         (try-score-line view legal-actions)
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
            (when-let [opt (select-seamless-target view options)]
              {:option opt :reason (str "heuristic-corp: Seamless-Launch-Ziel -> " (:label opt))}))
          (bp/on-prompt random-delegate view prompt options)))))

(defn heuristic-corp-bot
  "Baut einen seedbaren Heuristik-Corp-Bot. `seed` steuert nur den
  eingebetteten Random-Delegate (für Prompts/Fallbacks ohne eigene Regel) —
  das Playbook selbst ist deterministisch. `run-history` ist rein interner
  Bot-State (Breach-Zähler pro Zentralserver, siehe track-run-progress!) --
  kein Zugriff auf rohen Engine-State, nur Buchführung über ohnehin per view
  gesehene Werte."
  [seed]
  (->HeuristicCorpBot (random/random-bot seed)
                      (atom {:breaches [] :last-run-phase nil :last-run-server nil})))
