(ns bot.heuristic-corp-test
  ;; game.core/game.core.card werden schon hier vollstaendig requiret (nicht
  ;; erst in spaeteren Tasks) -- core/gain, get-counters und rezzed? werden
  ;; ab Task 6 gebraucht, bot.game-runner/decide-one! ebenfalls.
  (:require
   [bot.game-runner :as game-runner]
   [bot.heuristic-corp :as hc]
   [bot.legal :as legal]
   [bot.protocol :as bp]
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

(deftest regel-2-scoring-remote-aufbauen-nach-allen-zentralservern
  (do-game
    (new-game {:corp {:hand (repeat 4 "Ice Wall") :credits 20}})
    (core/gain state :corp :click 5)
    (let [bot (hc/heuristic-corp-bot 1)]
      (dotimes [_ 3]
        (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
        (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot}))
      (is (= 1 (count (get-ice state :hq))))
      (is (= 1 (count (get-ice state :rd))))
      (is (= 1 (count (get-ice state :archives))))
      (let [v (view/view-for state :corp)
            actions (legal/turn-actions v :corp)
            decision (bp/decide bot v actions)]
        (is (str/includes? (:reason decision) "Regel 2")
            "Regel 2 (Scoring-Remote aufbauen) muss im Decision-Log korrekt benannt sein"))
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-ice state :remote1)))
          "vierte Ice-Karte -> neuer Remote, alle Zentralserver schon geict"))))

(deftest regel-2-weiteres-ice-in-unsicheren-scoring-remote
  ;; Alle Zentralserver muessen VOR dem bot-Aufruf schon geict sein, sonst
  ;; greift Regel 1 (Zentralserver icen) zuerst und der Test isoliert nicht
  ;; Regel 2 -- deshalb HQ/R&D/Archives direkt (nicht ueber den Bot)
  ;; besetzen. Scoring-Remote hat danach 1 (schwaches) Ice, Runner hat viele
  ;; Credits -> unsicher -> Regel 2 legt WEITERES Ice nach statt eine
  ;; Agenda zu jammen.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Ice Wall" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Ice Wall" "R&D")
    (play-from-hand state :corp "Ice Wall" "Archives")
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

(deftest regel-3-kein-agenda-install-ohne-sicherheitspuffer
  ;; Gleiche Zentralserver-Vorbereitung wie oben. Ice Wall (Staerke 1, 1
  ;; Sub) ist ohne installierten Breaker fuer 2 Credits zu knacken
  ;; (raw-ice-cost(1,0)=max(1,2)=2) -- der Runner hat 20 Credits sichtbar,
  ;; 2 <= 20+4 ist wahr -> unsicher, selbst mit Puffer. Regel 3 darf also
  ;; NICHT greifen, Regel 2 legt stattdessen weiteres Ice nach.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Ice Wall" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Ice Wall" "R&D")
    (play-from-hand state :corp "Ice Wall" "Archives")
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

(deftest regel-5-5-credit-klick-als-letzter-fallback
  (do-game
    (new-game {:corp {:hand []}})
    (let [bot (hc/heuristic-corp-bot 1)
          credits-before (get-in @state [:corp :credit])]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= (inc credits-before) (get-in @state [:corp :credit]))))))

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
