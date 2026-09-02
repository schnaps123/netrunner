(ns bot.heuristic-corp-test
  ;; game.core/game.core.card werden schon hier vollstaendig requiret (nicht
  ;; erst in spaeteren Tasks) -- core/gain, get-counters und rezzed? werden
  ;; ab Task 6 gebraucht, bot.game-runner/decide-one! ebenfalls.
  (:require
   [bot.game-runner :as game-runner]
   [bot.heuristic-corp :as hc]
   [bot.legal :as legal]
   [bot.protocol :as bp]
   [bot.random :as random]
   [bot.view :as view]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.core.card :refer [get-counters rezzed?]]
   [game.test-framework :refer :all]))

(deftest decide-liefert-immer-eine-legale-aktion-mit-heuristic-corp-begruendung
  ;; Bis Task 9 delegierte decide fuer nicht abgedeckte Faelle an random-bot
  ;; (daher der urspruengliche Testname "...v1-skeleton"); seit Regel 6
  ;; (finaler Fallback: erste angebotene Option) deckt decide JEDEN Fall
  ;; selbst ab -- random-delegate wird nur noch von on-prompt genutzt (siehe
  ;; Task-9-Brief). Der Reason-String traegt darum immer den
  ;; "heuristic-corp:"-Prefix, nie mehr "random-bot".
  (do-game
    (new-game)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (some #{(:action decision)} actions))
      (is (str/starts-with? (:reason decision) "heuristic-corp:")))))

(deftest mulligan-ohne-ice-und-econ
  (do-game
    (new-game {:corp {:hand (repeat 5 "Hostile Takeover")}
               :options {:dont-start-game true}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Mulligan" (:label (:option decision))))
      (is (str/includes? (:reason decision) "Ice=0"))
      (is (str/includes? (:reason decision) "Econ=0")))))

(deftest keep-mit-ice-in-hand
  (do-game
    (new-game {:corp {:hand (cons "Ice Wall" (repeat 4 "Hostile Takeover"))}
               :options {:dont-start-game true}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Keep" (:label (:option decision)))))))

(deftest keep-mit-econ-in-hand
  (do-game
    (new-game {:corp {:hand (cons "Hedge Fund" (repeat 4 "Hostile Takeover"))}
               :options {:dont-start-game true}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Keep" (:label (:option decision)))))))

(deftest sonstiger-prompt-wird-an-random-delegiert
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Enigma" "Hedge Fund"] :credits 20}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Enigma" "R&D")
    (core/resolve-ability
     state :corp
     {:prompt "Choose 2 pieces of ice"
      :choices {:max 2 :card #(and (:installed %) (= "ICE" (:type %)))}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (some #{(:option decision)} options)))))

(deftest regel-1-zentralserver-icen-prioritaet-hq
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 1")
          "Regel 1 (Zentralserver icen) muss im Decision-Log korrekt benannt sein")
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-ice state :hq))))
      (is (= "Ice Wall" (:title (get-ice state :hq 0)))))))

(deftest regel-1-zentraltaxierung-leitet-sich-von-runner-einkommen-ab
  ;; Konsolidierung 2026-09-02: MIN-CENTRAL-TAX-CREDITS (fixe 5) faellt weg
  ;; -- die Zentralserver-Schwelle leitet sich stattdessen von
  ;; ASSUMED-RUNNER-INCOME-PER-TURN ab ("ein Server, der weniger kostet als
  ;; der Runner pro Zug verdient, ist Gratis-Farmen"). with-redefs beweist
  ;; die tatsaechliche Kopplung, nicht nur einen zufaellig passenden
  ;; Zahlenwert: Ice Wall (Kosten 2 ohne Breaker) gilt bei angenommenem
  ;; Runner-Einkommen 1 schon als ausreichend, obwohl es beim Standardwert
  ;; (4) nicht reicht (siehe regel-1-ein-einzelnes-billiges-ice-reicht-nicht
  ;; fuer denselben Fall ohne Redef).
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)]
      (with-redefs [hc/ASSUMED-RUNNER-INCOME-PER-TURN 1]
        (let [decision (bp/decide bot v actions)]
          (is (str/includes? (:reason decision) "R&D")
              "HQ (Ice Wall, Kosten 2) gilt bei Einkommensannahme 1 schon als ausreichend -> naechstes Ziel ist R&D"))))))

(deftest regel-1-ein-einzelnes-billiges-ice-reicht-nicht
  ;; Kern der Diagnose aus der gespielten Partie: Tithe (Staerke 1, Rez-
  ;; Kosten 1) taxiert nur raw-ice-cost(1,0)=max(1,2)=2 -- unter
  ;; MIN-CENTRAL-TAX-CREDITS (5). HQ galt frueher ("hat >=1 Ice") sofort als
  ;; fertig; jetzt muss Regel 1 ein ZWEITES Ice auf HQ legen, weil die
  ;; Taxierung noch zu niedrig ist -- R&D/Archives bleiben unberuehrt (die
  ;; Breach-Prioritaet ist hier noch bei 0/0, HQ bleibt an erster Stelle
  ;; der Basis-Reihenfolge).
  (do-game
    (new-game {:corp {:hand ["Tithe" "Ice Wall"] :credits 20}})
    (play-from-hand state :corp "Tithe" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 1")
          "HQ gilt trotz vorhandenem Tithe noch als unzureichend taxiert")
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 2 (count (get-ice state :hq)))
          "zweites Ice auf HQ, nicht auf R&D/Archives -- ein Tithe allein reicht nicht"))))

(deftest regel-1-archives-ohne-agenda-braucht-keine-taxierung
  ;; Echtes Netrunner-Regel: ein Zugriff auf ein agendafreies Archiv kostet
  ;; die Corp nichts -- Archives fällt darum aus der Pflichttaxierung
  ;; heraus, solange keine Agenda dort liegt (auch wenn 0 Ice dort steht).
  ;; HQ/R&D vorab mit Bastion ausreichend taxiert, damit Regel 1 dafuer
  ;; nicht mehr greift und der Test isoliert Archives prueft.
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Bastion" "Bastion"] :credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (empty? (get-ice state :archives))
          "kein Ice auf Archives, obwohl es 0 Ice hat -- keine Agenda dort in Aussicht"))))

(deftest regel-1-archives-mit-agenda-braucht-wieder-taxierung
  ;; Sobald eine Agenda im Archiv liegt (z.B. durch Discard/Milling),
  ;; greift die normale Taxierungsschwelle wieder -- ein zugängliches
  ;; Archiv mit Agenda ist nicht mehr "wertlos".
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Bastion" "Bastion"] :credits 20
                      :discard ["Hostile Takeover"]}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Archives")
          "Archives enthaelt eine Agenda -> zaehlt wieder zur Pflichttaxierung"))))

(deftest regel-1-breach-zaehler-uebersteuert-basis-prioritaet
  ;; R&D steht in CENTRAL-ICE-PRIORITY hinter HQ, holt sich aber mit 3
  ;; juengsten Breaches (aktueller Zug) die Verstaerkungspriorität vor HQ
  ;; zurueck -- direkt konstruiertes run-history statt einer echten Partie,
  ;; weil hier NUR central-priority-order isoliert geprueft wird (die
  ;; End-to-End-Zaehlung selbst deckt
  ;; breach-zaehler-erkennt-wiederholte-hq-durchbrueche unten ab). Archives
  ;; scheidet fuer diesen Test aus (seit der Archives-Ausnahme nie
  ;; Pflichttaxierung ohne Agenda dort, egal wie oft gebreached)."
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (let [current-turn (:turn (view/view-for state :corp) 0)
          run-history (atom {:breaches (vec (repeat 3 {:zone :rd :turn current-turn}))
                             :last-run-phase nil :last-run-server nil})
          bot (hc/->HeuristicCorpBot (random/random-bot 1) run-history)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "R&D")
          "R&D trotz niedrigerer Basis-Prioritaet als HQ zuerst, wegen 3 juengster Breaches")
      (is (str/includes? (:reason decision) "3 Breach(es)")
          "Zaehler muss im Decision-Log genannt werden"))))

(deftest regel-2-scoring-remote-aufbauen-nach-allen-zentralservern
  ;; Bastion (Staerke 4, Rez-Kosten 4) statt Ice Wall auf HQ/R&D:
  ;; raw-ice-cost(4,0)=5 erreicht MIN-CENTRAL-TAX-CREDITS (5), ein einzelner
  ;; Ice Wall (Kosten 2) waere unter der neuen Taxierungsschwelle fuer immer
  ;; "noch nicht fertig" geblieben (siehe zentrale Diagnose der Spielpartie:
  ;; ein einzelnes billiges Ice reicht nicht mehr). Nur HQ+R&D, NICHT
  ;; Archives: seit der Archives-Ausnahme braucht Archives ohne Agenda dort
  ;; keine Pflichttaxierung, die Zentralserver-Phase ist also nach zwei
  ;; Servern fertig. "Hostile Takeover" in der Hand, weil Regel 2 seit der
  ;; Realistische-Aussicht-Korrektur nur noch oeffnet, wenn eine Agenda in
  ;; der Hand liegt (oder laut Decklist noch welche im Deck stecken).
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover" "Bastion" "Bastion" "Bastion"] :credits 20}})
    (core/gain state :corp :click 5)
    (let [bot (hc/heuristic-corp-bot 1)]
      (dotimes [_ 2]
        (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
        (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot}))
      (is (= 1 (count (get-ice state :hq))))
      (is (= 1 (count (get-ice state :rd))))
      (is (empty? (get-ice state :archives))
          "Archives ohne Agenda -- keine Pflichttaxierung")
      (let [v (view/view-for state :corp)
            actions (legal/turn-actions v :corp)
            decision (bp/decide bot v actions)]
        (is (str/includes? (:reason decision) "Regel 2")
            "Regel 2 (Scoring-Remote aufbauen) muss im Decision-Log korrekt benannt sein"))
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-ice state :remote1)))
          "dritte Ice-Karte -> neuer Remote, HQ/R&D schon ausreichend taxiert, Archives braucht keine ohne Agenda"))))

(deftest regel-2-weiteres-ice-in-unsicheren-scoring-remote
  ;; Alle Zentralserver muessen VOR dem bot-Aufruf schon AUSREICHEND taxiert
  ;; sein (Bastion, siehe oben), sonst greift Regel 1 zuerst und der Test
  ;; isoliert nicht Regel 2 -- deshalb HQ/R&D/Archives direkt (nicht ueber
  ;; den Bot) besetzen. Scoring-Remote hat danach 1 schwaches Ice Wall
  ;; (bewusst schwach -- DAS ist, was hier getestet wird: der Remote selbst
  ;; ist unsicher), Runner hat viele Credits -> unsicher -> Regel 2 legt
  ;; WEITERES Ice nach statt eine Agenda zu jammen.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Bastion" "Bastion" "Bastion" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "Archives")
    (play-from-hand state :corp "Ice Wall" "New remote")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 2 (count (get-ice state :remote1)))
          "unsicherer Remote (Runner hat 20 Credits) -> zweites Ice statt Agenda-Install")
      (is (empty? (get-content state :remote1))))))

(deftest regel-3-agenda-platzieren-wenn-sicher
  ;; Alle Zentralserver zuerst manuell (nicht ueber den Bot) icen, sonst
  ;; wuerde Regel 1 vor Regel 3 greifen und der Test isoliert nicht die
  ;; Agenda-Platzierung. Bastion (Staerke 4, Rez-Kosten 4) statt Ice Wall
  ;; im Remote: ohne installierten Runner-Breaker liegt
  ;; raw-ice-cost(4,0)=5 ueber dem Sicherheitspuffer (0 Runner-Credits + 4
  ;; Puffer = 4) -- 5 > 4, Server gilt als sicher -> Agenda wird
  ;; installiert. Ein einzelner Ice Wall (Kosten 2) waere mit 2 <= 4 IMMER
  ;; "unsicher" gewesen, unabhaengig vom Runner-Credit-Stand.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Ice Wall" "Bastion"]
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Ice Wall" "R&D")
    (play-from-hand state :corp "Ice Wall" "Archives")
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-content state :remote1))))
      (is (= "Priority Requisition" (:title (get-content state :remote1 0)))))))

(deftest regel-3-rez-budget-nutzt-teuerstes-einzelnes-ice-nicht-die-summe
  ;; Scoring-Fenster-Zielmodell Phase 1: credit-ready? verlangt nur das
  ;; teuerste einzelne unrezzte Ice auf dem Remote (+ REZ-BUDGET-MARGIN),
  ;; NICHT die Summe aller unrezzten Ice -- der Runner begegnet Ice
  ;; nacheinander, ein Run endet oft schon am ersten. Remote hat Bastion
  ;; (Rez 4, unrezzt) + Ice Wall (Rez 1, unrezzt): Maximum=4, Summe=5. Bei
  ;; genau 6 Credits (Maximum+Puffer=4+2=6) reicht das Rez-Budget; eine
  ;; Summen-Variante (5+2=7) waere hier faelschlich blockiert.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Bastion" "Bastion" "Bastion" "Ice Wall"]
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "New remote")
    (play-from-hand state :corp "Ice Wall" "Server 1")
    (let [bot (hc/heuristic-corp-bot 1)]
      (testing "unter dem Maximum (3 Credits < 4+2) -> :credits im Gap, kein Install"
        (swap! state assoc-in [:corp :credit] 3)
        (let [v (view/view-for state :corp)
              decision (bp/decide bot v (legal/turn-actions v :corp))]
          (is (contains? (:scoring-gap decision) :credits)))
        (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
        (is (empty? (get-content state :remote1))))
      (testing "am Maximum+Puffer (6 Credits = 4+2), nicht an der Summe (5+2=7) -> Fenster offen"
        (swap! state assoc-in [:corp :credit] 6)
        (let [v (view/view-for state :corp)
              decision (bp/decide bot v (legal/turn-actions v :corp))]
          (is (not (contains? (:scoring-gap decision) :credits))))
        (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
        (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
        (is (= "Priority Requisition" (:title (get-content state :remote1 0))))))))

(deftest regel-3-commitment-als-risikoabwaegung-nicht-als-sicherheitsgarantie
  ;; Scoring-Fenster-Zielmodell Phase 2: absolute Sicherheit (Kosten >
  ;; gesamtes Runner-Budget) ist nicht mehr Voraussetzung, sondern
  ;; Sonderfall. Bastion (rezzt, Kosten 5) gegen Runner mit 5 Credits:
  ;; Budget = 5+4(Puffer) = 9 >= 5 -- der Runner KOENNTE sich den Einbruch
  ;; technisch leisten (absolute Sicherheit waere hier FALSCH: 5>9 gilt
  ;; nicht), ist danach aber mit 9-5=4 Credits fast pleite, gemessen an
  ;; Priority Requisition (3 Punkte * COMMIT-RISK-CREDITS-PER-POINT(3) = 9
  ;; akzeptable Armut, 4<=9) -- der Tausch lohnt sich, Regel 3 installiert
  ;; trotzdem.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Bastion"] :credits 20}
               :runner {:credits 5}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Ice Wall" "R&D")
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= "Priority Requisition" (:title (get-content state :remote1 0)))
          "Runner koennte den Einbruch technisch bezahlen, waere danach aber fast pleite -- Tausch lohnt sich"))))

(deftest regel-3-fast-advance-linie-uebergeht-sicherheitsfrage-beim-install
  ;; Design-Spec Scoring-Fenster-Zielmodell Phase 2: eine bei Install schon
  ;; absehbare Fast-Advance-Linie (Seamless Launch + passendes
  ;; Restadvancement) macht die Sicherheitsfrage irrelevant -- selbst ein
  ;; unsicherer Remote (Runner hat 20 Credits, weit ueber jeder akzeptablen
  ;; Armuts-Schwelle) blockiert den Install nicht, wenn die Agenda quasi
  ;; sofort gescort werden kann. Grosszuegiger Klick-Vorrat (core/gain),
  ;; damit das exakte Restadvancement von Priority Requisition nicht
  ;; bekannt sein muss -- die Bedingung ist so oder so erfuellt.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Seamless Launch" "Ice Wall" "Ice Wall" "Bastion"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Ice Wall" "R&D")
    (play-from-hand state :corp "Bastion" "New remote")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= "Priority Requisition" (:title (get-content state :remote1 0)))
          "Fast-Advance-Linie (Seamless Launch) macht die Sicherheitsfrage irrelevant"))))

(deftest decide-liefert-scoring-gap-wenn-remote-existiert
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "New remote")
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          decision (bp/decide bot v (legal/turn-actions v :corp))]
      (is (set? (:scoring-gap decision))
          "sobald ein Scoring-Remote existiert, traegt jede Entscheidung :scoring-gap"))))

(deftest decide-liefert-kein-scoring-gap-ohne-remote
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          decision (bp/decide bot v (legal/turn-actions v :corp))]
      (is (not (contains? decision :scoring-gap))
          "ohne jeden Scoring-Remote gibt es kein Fenster zu bewerten"))))

(deftest regel-3-kein-agenda-install-ohne-sicherheitspuffer
  ;; Zentralserver mit Bastion ausreichend taxiert (siehe Regel-2-Tests oben
  ;; -- ein einzelner Ice Wall bliebe unter MIN-CENTRAL-TAX-CREDITS fuer
  ;; immer "nicht fertig" und Regel 1 wuerde erneut greifen statt Regel 2/3
  ;; zu testen). Remote-Ice bewusst schwacher Ice Wall (Staerke 1, 1 Sub):
  ;; ohne installierten Breaker fuer 2 Credits zu knacken
  ;; (raw-ice-cost(1,0)=max(1,2)=2) -- der Runner hat 20 Credits sichtbar,
  ;; 2 <= 20+4 ist wahr -> unsicher, selbst mit Puffer. Regel 3 darf also
  ;; NICHT greifen, Regel 2 legt stattdessen weiteres Ice nach.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Bastion" "Bastion" "Bastion" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "Archives")
    (play-from-hand state :corp "Ice Wall" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (empty? (get-content state :remote1))
          "Remote unsicher (Runner kann die geringe Ice-Wall-Bedrohung leicht bezahlen) -> keine Agenda")
      (is (= 2 (count (get-ice state :remote1)))
          "stattdessen greift Regel 2: weiteres Ice statt Agenda-Install"))))

(deftest regel-4-score-fertig-advancte-agenda-sofort
  ;; Hostile Takeover: advancementcost 2 -- zwei Advance-Klicks vorab
  ;; manuell (nicht ueber den Bot), damit die Agenda beim Bot-Aufruf schon
  ;; fertig advanced ist und Regel 4 sofort scoren muss statt weiter
  ;; advancen zu wollen.
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover"] :credits 20}})
    (play-from-hand state :corp "Hostile Takeover" "New remote")
    (core/gain state :corp :click 10 :credit 10)
    (dotimes [_ 2] (click-advance state :corp (get-content state :remote1 0)))
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= 1 (count (get-scored state :corp)))))))

(deftest regel-4-advanct-normal-wenn-sicher-und-keine-score-linie
  ;; Bastion (Staerke 4, Rez-Kosten 4) statt Ice Wall fuer den Remote: ohne
  ;; installierten Runner-Breaker liegt raw-ice-cost(4,0)=5 ueber dem
  ;; Sicherheitspuffer (0 Runner-Credits + 4 Puffer = 4) -- der Server gilt
  ;; als sicher (ein einzelner Ice Wall waere mit Kosten 2 <= 4 IMMER
  ;; "unsicher" gewesen, unabhaengig vom Runner-Credit-Stand). Zentralserver
  ;; werden mit Ice Wall vorbereitet, nur damit Regel 1 nicht mehr greift.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Ice Wall" "Bastion"]
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Ice Wall" "R&D")
    (play-from-hand state :corp "Ice Wall" "Archives")
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (play-from-hand state :corp "Priority Requisition" "Server 1")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= 1 (get-counters (get-content state :remote1 0) :advancement))))))

(deftest regel-4-seamless-launch-score-linie
  ;; Priority Requisition (Advancement-Kosten 5) hat schon 1 Advancement aus
  ;; einer vorherigen Runde (Rest 4). 8 verfuegbare Klicks diesen Zug (Rest
  ;; 4 <= 8+2) -> die Deadline-Bedingung greift, und Regel 4 bevorzugt die
  ;; Seamless-Launch-Linie GRUNDSAETZLICH vor normalem Advancen, sobald sie
  ;; verfuegbar ist (siehe cond-Reihenfolge in try-score-line) -- die
  ;; Zaehlerpruefung (1 -> 3) beweist, dass genau EIN Seamless-Launch-Play
  ;; entschieden wurde, nicht ein einzelner Klick-Advance (waere 1 -> 2).
  (do-game
    ;; :deck explizit noetig -- starting-hand zieht NUR aus dem Deck-Pool,
    ;; der ohne :deck-Angabe komplett in die Starthand wandert (siehe
    ;; Kommentar in eval_test.clj/credit-diff-Test), waere also sonst leer.
    (new-game {:corp {:hand ["Priority Requisition"] :deck ["Seamless Launch"] :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 5)
    (play-from-hand state :corp "Priority Requisition" "New remote")
    (click-advance state :corp (get-content state :remote1 0))
    (take-credits state :corp)
    (take-credits state :runner)
    (starting-hand state :corp ["Seamless Launch"])
    (core/gain state :corp :click 5)
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 3 (get-counters (get-content state :remote1 0) :advancement))
          "Seamless Launch (+2) auf die Agenda gezielt, nicht auf ein anderes Ziel"))))

(deftest regel-4-seamless-launch-score-linie-trotz-frueherer-econ-operation-in-hand
  ;; Regressionstest fuer den Final-Review-Fund: try-score-line suchte den
  ;; Seamless-Launch-Kandidaten frueher per first-play-of-type "Operation"
  ;; (erste spielbare Operation IN HANDREIHENFOLGE), nicht per Titel. Steht
  ;; eine andere spielbare Econ-Operation (Hedge Fund) vor Seamless Launch in
  ;; der Hand, band sich seamless-act an Hedge Fund, der nachfolgende
  ;; Titel-Vergleich schlug fehl, und die Score-Linie wurde faelschlich
  ;; uebersprungen -- der Bot spielte stattdessen Hedge Fund (Regel 5.4).
  ;; Aufbau wie regel-4-seamless-launch-score-linie oben, aber mit Hedge Fund
  ;; VOR Seamless Launch in der Starthand (starting-hand haengt Karten in
  ;; der uebergebenen Reihenfolge ans Handende an -- core/move ohne :front/
  ;; :index fuegt am Ende ein, siehe game.core.moving/move).
  (do-game
    (new-game {:corp {:hand ["Priority Requisition"] :deck ["Hedge Fund" "Seamless Launch"] :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 5)
    (play-from-hand state :corp "Priority Requisition" "New remote")
    (click-advance state :corp (get-content state :remote1 0))
    (take-credits state :corp)
    (take-credits state :runner)
    (starting-hand state :corp ["Hedge Fund" "Seamless Launch"])
    (core/gain state :corp :click 5 :credit 10)
    (is (= "Hedge Fund" (:title (first (get-in @state [:corp :hand]))))
        "Testaufbau-Kontrolle: Hedge Fund liegt tatsaechlich vor Seamless Launch in der Hand")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 3 (get-counters (get-content state :remote1 0) :advancement))
          "Seamless Launch (+2) muss trotz frueher in der Hand liegendem Hedge Fund gewaehlt werden"))))

(deftest regel-5-1-econ-asset-installieren
  (do-game
    (new-game {:corp {:hand ["Regolith Mining License"] :credits 10}})
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= "Regolith Mining License" (:title (get-content state :remote1 0)))))))

(deftest regel-5-2-econ-asset-rezzen
  (do-game
    (new-game {:corp {:hand ["Regolith Mining License"] :credits 10}})
    (play-from-hand state :corp "Regolith Mining License" "New remote")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (rezzed? (get-content state :remote1 0))))))

(deftest regel-5-3-klick-fuer-credits-vor-generischem-credit-klick
  (do-game
    (new-game {:corp {:hand ["Regolith Mining License"] :credits 10}})
    (play-from-hand state :corp "Regolith Mining License" "New remote")
    (rez state :corp (get-content state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)
          credits-before (get-in @state [:corp :credit])]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= (+ 3 credits-before) (get-in @state [:corp :credit]))
          "Regolith gibt 3 Credits pro Klick, generischer Klick nur 1"))))

(deftest regel-5-4-econ-operation-spielen
  (do-game
    ;; :credits 0 (wie im Brief) waere hier ein Test-Bug: Hedge Fund kostet 5
    ;; Credits, mit 0 Credits ist die Karte gar nicht :playable und taucht nie
    ;; in legal-actions auf -- try-play-econ-operation koennte sie dann NIE
    ;; finden, unabhaengig von der Implementierung. :credits 5 (Start-Credits,
    ;; analog operations_test.clj) macht sie bezahlbar und erhaelt gleichzeitig
    ;; die im Brief erwartete Netto-Differenz von +4 (5 -5 +9 = 9).
    (new-game {:corp {:hand ["Hedge Fund"] :credits 5}})
    (let [bot (hc/heuristic-corp-bot 1)
          credits-before (get-in @state [:corp :credit])]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= (+ 4 credits-before) (get-in @state [:corp :credit]))
          "Hedge Fund gibt netto +4 (Kosten 5, Ertrag 9 -- oder wie auch immer, Hauptsache Operation gespielt")
      (is (empty? (get-in @state [:corp :hand]))))))

(deftest regel-5-6-credit-klick-als-letzter-fallback
  ;; :deck [] explizit noetig: mit leerer Hand UND leerem Deck ist "draw"
  ;; keine legale Option mehr, sonst wuerde seit der Ziehen-Regel (5.5)
  ;; ZIEHEN statt Klicken gewaehlt (leere Hand hat trivial keine
  ;; Agenda/kein Ice -> should-draw? waere wahr) -- dieser Test soll aber
  ;; gezielt den ALLERLETZTEN Fallback pruefen, wenn wirklich nichts mehr
  ;; geht.
  (do-game
    (new-game {:corp {:hand [] :deck []}})
    (let [bot (hc/heuristic-corp-bot 1)
          credits-before (get-in @state [:corp :credit])]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= (inc credits-before) (get-in @state [:corp :credit]))))))

(deftest regel-5-5-karte-ziehen-vor-credit-klick
  ;; Leere Hand (keine Agenda) mit deck-count komfortabel ueber DRAW-SAFETY-
  ;; BUFFER -> Ziehen (Regel 5.5) muss VOR dem generischen Credit-Klick
  ;; (Regel 5.6) gewaehlt werden -- Kernfix fuer "der Bot zieht nie, klickt
  ;; stattdessen stumpf fuer Credits" aus der gespielten Partie.
  (do-game
    (new-game {:corp {:hand [] :deck (repeat 10 "Hedge Fund")}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 5.5 (Karte ziehen)")))))

(deftest regel-5-5-kein-ziehen-unterhalb-des-sicherheitsbodens
  ;; Regressionstest fuer die 200-Partien-Regression vom 2026-09-02: leere
  ;; Hand (wuerde ohne Sicherheitsboden sofort Ziehen ausloesen), aber
  ;; deck-count liegt bei/unter DRAW-SAFETY-BUFFER -- die Corp zieht JEDEN
  ;; Zug ohnehin verpflichtend (game.core.turns), freiwilliges Ziehen hier
  ;; wuerde das Decking nur beschleunigen. Regel 5.5 darf NICHT greifen,
  ;; Regel 5.6 (Credit-Klick) stattdessen.
  (do-game
    (new-game {:corp {:hand [] :deck (repeat hc/DRAW-SAFETY-BUFFER "Hedge Fund")}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 5.6 (Klick fuer Credit)")
          "Sicherheitsboden erreicht (deck-count = DRAW-SAFETY-BUFFER) -> kein freiwilliges Ziehen mehr"))))

(deftest regel-5-5-reichlich-credits-ist-kein-eigenstaendiger-ziehen-grund
  ;; Korrektur 2026-09-02: "Credits >= PLENTY-CREDITS" fliegt als eigener
  ;; Ziehen-Auslöser raus -- reich sein ist kein Grund zu ziehen. Agenda in
  ;; der Hand, Hand nicht duenn, Deck komfortabel voll, aber viele Credits
  ;; -> trotzdem kein Ziehen, Regel 5.6 stattdessen.
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover" "Hostile Takeover" "Hostile Takeover"]
                      :deck (repeat 15 "Hedge Fund")
                      :credits 30}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 5.6 (Klick fuer Credit)")
          "viele Credits allein loesen kein Ziehen mehr aus"))))

(deftest regel-5-5-zurueckhaltend-unter-deck-caution-threshold
  ;; Korrektur 2026-09-02: keine harte 1x-pro-Zug-Obergrenze mehr, aber
  ;; zunehmend zurueckhaltend unter DECK-CAUTION-THRESHOLD (~10 Karten):
  ;; "keine Agenda in der Hand" allein reicht dort NICHT mehr als Grund,
  ;; erst der Sicherheitsboden (DRAW-SAFETY-BUFFER) stoppt Ziehen ganz.
  ;; Deck-count zwischen beiden Schwellen, keine Agenda in der Hand, Hand
  ;; nicht duenn -> kein Ziehen (Regel 5.6 stattdessen), obwohl "keine
  ;; Agenda" oberhalb der Caution-Schwelle sofort gezogen haette (siehe
  ;; regel-5-5-karte-ziehen-vor-credit-klick oben, deck=10)."
  (do-game
    (new-game {:corp {:hand ["Hedge Fund" "Hedge Fund" "Hedge Fund"]
                      :deck (repeat (dec hc/DECK-CAUTION-THRESHOLD) "Hedge Fund")
                      :credits 0}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 5.6 (Klick fuer Credit)")
          "unter der Caution-Schwelle loest 'keine Agenda' allein kein Ziehen mehr aus"))))

(deftest regel-5-5-duenne-hand-zieht-trotzdem-in-der-caution-zone
  ;; Eine wirklich duenne Hand (HAND-SIZE-LOW) loest Ziehen weiterhin aus,
  ;; auch unterhalb DECK-CAUTION-THRESHOLD -- nur der harte Sicherheitsboden
  ;; (DRAW-SAFETY-BUFFER) stoppt es vollstaendig.
  (do-game
    (new-game {:corp {:hand [] :deck (repeat (dec hc/DECK-CAUTION-THRESHOLD) "Hedge Fund")}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 5.5 (Karte ziehen)")
          "duenne Hand zieht auch in der Caution-Zone, solange ueber DRAW-SAFETY-BUFFER"))))

(deftest regel-5-5-kein-ice-in-hand-ist-kein-eigenstaendiger-ziehen-grund
  ;; Regressionstest fuer die 200-Partien-Regression: Agenda in der Hand
  ;; (Agenda-Bedingung also falsch), KEIN Ice in der Hand, Hand nicht duenn,
  ;; Credits nicht ueppig, Deck komfortabel voll -- unter der alten Logik
  ;; (ice-in-hand? als eigener Auslöser) haette das trotzdem gezogen, weil
  ;; das Gateway-Deck nur 16 von 34 Ice-Karten hat und diese Bedingung
  ;; dadurch fast das ganze Spiel ueber wahr bleibt. Jetzt NICHT mehr.
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover" "Hedge Fund" "Hedge Fund"]
                      :deck (repeat 10 "Hedge Fund")
                      :credits 0}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 5.6 (Klick fuer Credit)")
          "kein Ice in der Hand ist allein kein Ziehen-Grund mehr -> Klicken statt Ziehen"))))

(deftest regel-2-obergrenze-ice-pro-scoring-remote
  ;; Scoring-Remote hat bereits MAX-ICE-PER-SCORING-REMOTE (3) Ice-Wall,
  ;; Runner hat viele Credits (unsicher), eine Agenda liegt in der Hand
  ;; (Regel-2-Aufbaubedingung sonst erfuellt) -- trotzdem darf KEIN viertes
  ;; Ice installiert werden.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Bastion" "Bastion" "Bastion"
                             "Ice Wall" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "Archives")
    (play-from-hand state :corp "Ice Wall" "New remote")
    (play-from-hand state :corp "Ice Wall" "Server 1")
    (play-from-hand state :corp "Ice Wall" "Server 1")
    (let [bot (hc/heuristic-corp-bot 1)]
      (is (= 3 (count (get-ice state :remote1))) "Testaufbau: Obergrenze schon erreicht")
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= 3 (count (get-ice state :remote1)))
          "Obergrenze erreicht -> kein viertes Ice, obwohl Remote weiter unsicher ist")
      (is (empty? (get-content state :remote1)) "auch keine Agenda-Notinstallation"))))

(deftest regel-2-remote-vorbau-ohne-agenda-in-hand-wenn-noch-agenden-im-deck
  ;; Keine Agenda in der Hand, aber laut Decklist (TOTAL-AGENDA-COPIES-IN-
  ;; DECK) stecken noch alle 5 Kopien im ungesehenen Rest-Deck -- Regel 2
  ;; darf trotzdem einen neuen Scoring-Remote eroeffnen: echtes Netrunner-
  ;; Spiel baut vor, damit der Remote fertig ist, wenn die Agenda kommt.
  ;; Agenda muss erst FUERS INSTALLIEREN (Regel 3) in der Hand liegen, nicht
  ;; schon fuers Vorbauen (Korrektur der urspruenglichen "Realistische-
  ;; Aussicht"-Regel, die zu streng war und Agenda-Siege einbrechen liess).
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Bastion" "Bastion" "Bastion"] :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "Archives")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-ice state :remote1)))
          "Remote vorgebaut, obwohl keine Agenda in der Hand liegt -- Deck kann noch eine liefern"))))

(deftest regel-2-kein-remote-vorbau-wenn-keine-agenden-mehr-im-spiel
  ;; Alle 5 bekannten Agenda-Kopien bereits anderswo aufgetaucht (3x
  ;; Offworld Office im eigenen Archiv, 2x Send a Message beim Runner
  ;; gestohlen) -- keine realistische Aussicht mehr, egal wie gut die
  ;; Zentralen taxiert sind. Regel 2 darf KEINEN neuen Remote mehr
  ;; eroeffnen.
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Bastion" "Bastion" "Bastion"] :credits 20
                      :discard ["Offworld Office" "Offworld Office" "Offworld Office"]}
               :runner {:credits 0
                        :score-area ["Send a Message" "Send a Message"]}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "Archives")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (nil? (get-in @state [:corp :servers :remote1]))
          "kein neuer Remote -- alle Agenda-Kopien schon anderswo aufgetaucht"))))

(deftest regel-5-1-econ-asset-installation-loggt-trash-risiko
  (do-game
    (new-game {:corp {:hand ["Regolith Mining License"] :credits 10}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "trashbar")
          "Regel 5.1 muss das Trash-Risiko eines ungeschuetzten Remotes im Log nennen"))))

(defrecord AlwaysRunHQBot []
  bp/Bot
  (decide [_ _view legal-actions]
    (or (when-let [act (first (filter #(and (= "run" (:command %))
                                             (= "HQ" (get-in % [:args :server])))
                                       legal-actions))]
          {:action act :reason "test: run HQ"})
        {:action (first legal-actions) :reason "test: fallback"}))
  (on-prompt [_ _view _prompt options]
    {:option (first options) :reason "test: first option"}))

(deftest breach-zaehler-erkennt-wiederholte-hq-durchbrueche
  ;; Beweis fuer den in der Klarstellung geforderten End-to-End-Nachweis:
  ;; der Breach-Zaehler muss in einer ECHTEN, ueber die Engine gespielten
  ;; Partie tatsaechlich hochzaehlen -- nicht nur in einer isolierten
  ;; Pruefung der reinen Zaehl-Funktion (das deckt
  ;; regel-1-breach-zaehler-uebersteuert-basis-prioritaet oben ab). Corp-
  ;; Deck bewusst OHNE jede Ice-Karte: HQ bleibt die ganze Partie
  ;; ungeschuetzt, jeder HQ-Run des Runner-Test-Bots (der IMMER HQ anlaeuft)
  ;; erreicht darum garantiert :run/movement -- genau der Uebergang, den
  ;; track-run-progress! als Breach zaehlt.
  (let [corp-bot (hc/heuristic-corp-bot 1)
        result (game-runner/run-game
                {:corp-bot corp-bot
                 :runner-bot (->AlwaysRunHQBot)
                 :corp-deck {:identity "The Syndicate: Profit over Principle"
                             :cards [["Hedge Fund" 10]]}
                 :runner-deck {:identity "The Catalyst: Convention Breaker"
                               :cards [["Sure Gamble" 10]]}
                 :max-steps 500})]
    (is (:completed? result))
    (is (>= (count (filter #(= :hq (:zone %)) (:breaches @(:run-history corp-bot)))) 3)
        "HQ muss ueber die Partie hinweg mehrfach als gebreached gezaehlt worden sein")))

(deftest rez-waehrend-eines-runs-wenn-bezahlbar
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (take-credits state :corp)
    (run-on state "HQ")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :run :bot bot})
      (is (rezzed? (get-ice state :hq 0))))))

(deftest kein-rez-waehrend-eines-runs-wenn-unbezahlbar
  ;; Reihenfolge wichtig: take-credits verbraucht die VERBLEIBENDEN Klicks der
  ;; Corp als "credit"-Aktionen (siehe game.test-framework/take-credits) --
  ;; ein swap! auf 0 VOR take-credits wuerde von diesen Restklicks wieder
  ;; hochgefuellt (2 verbleibende Klicks nach Ice-Wall-Install -> 0+2=2
  ;; Credits, Ice Wall waere mit Rez-Kosten 1 wieder bezahlbar). Der swap!
  ;; muss darum NACH take-credits erfolgen, damit die Corp beim Run
  ;; tatsaechlich 0 Credits hat.
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (take-credits state :corp)
    (swap! state assoc-in [:corp :credit] 0)
    (run-on state "HQ")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :run :bot bot})
      (is (not (rezzed? (get-ice state :hq 0)))))))
