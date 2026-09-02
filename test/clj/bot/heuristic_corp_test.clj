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

(deftest gratis-rez-angebot-waehlt-teuerstes-ice
  ;; Bug aus einer echten Partie: ein Gratis-/Rabatt-Rez-Angebot (z.B. Send
  ;; a Message :stolen -- "rez an ice, ignoring all costs") fiel bisher
  ;; ungenutzt an den Zufall durch und wurde abgelehnt. rez-offer-choice
  ;; waehlt jetzt strukturell (installiert + unrezzt + Ice) das teuerste
  ;; passende Ice unter den Optionen.
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Bastion"] :credits 0}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (core/resolve-ability
     state :corp
     {:prompt "Choose a piece of ice to rez, ignoring all costs"
      :choices {:card #(and (:installed %) (= "ICE" (:type %)) (not (:rezzed %)))}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Bastion" (:label (:option decision)))
          "teuerstes Ice (Bastion, Rez 4) gewaehlt statt Ice Wall (Rez 1)"))))

(deftest gratis-install-angebot-installiert-wenn-gebraucht
  ;; Bug aus einer echten Partie: Brân 1.0s "Ice aus HQ/Archiv
  ;; installieren"-Subroutine fiel ungenutzt an den Zufall durch.
  ;; free-install-offer-choice installiert jetzt, wenn ein Zentralserver
  ;; (hier: HQ) noch Bedarf hat.
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (core/resolve-ability
     state :corp
     {:prompt "Choose an ice to install"
      :choices {:card #(and (= "ICE" (:type %)) (not (:installed %)))}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Ice Wall" (:label (:option decision)))
          "HQ braucht noch Ice -> Angebot wird gezielt genutzt"))))

(deftest gratis-install-angebot-ungenutzt-wenn-nicht-gebraucht
  ;; Umgekehrter Fall: Zentralen ausreichend taxiert, kein Scoring-Remote
  ;; -- kein Verwendungszweck, das Angebot wird NICHT blind gegriffen,
  ;; sondern faellt sichtbar (Log-Markierung) an den Zufall.
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Bastion" "Bastion"] :credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (core/resolve-ability
     state :corp
     {:prompt "Choose an ice to install"
      :choices {:card #(and (= "ICE" (:type %)) (not (:installed %)))}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (str/includes? (:reason decision) "kein Playbook-Handler")
          "kein Bedarf -> kein gezielter Griff, sichtbar an den Zufall delegiert"))))

(deftest trash-um-zu-bezahlen-prompt-waehlt-unrezztes-ice-zuerst
  ;; Bug aus einer echten Partie (dritte Partie): der Bot trashte zweimal
  ;; eigenes Ice, um ein neues guenstiger zu installieren (Ice-Install
  ;; kostet 1 Credit pro bereits vorhandenem Ice auf dem Server, die Engine
  ;; bietet als Rueckfall-Zahlungsvariante an, vorhandenes Ice zu trashen).
  ;; try-install-ice verhindert das jetzt bereits am Entscheidungspunkt
  ;; (siehe ice-install-affordable?); dieser Test prueft direkt den
  ;; Rueckfall-Prompt-Handler: rezztes Ice (schuetzt aktiv) wird niemals
  ;; vor unrezztem geopfert.
  (do-game
    (new-game {:corp {:hand ["Ice Wall" "Bastion"] :credits 20}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (play-from-hand state :corp "Bastion" "HQ")
    (rez state :corp (get-ice state :hq 1))
    (core/resolve-ability
     state :corp
     {:prompt "Trash ice protecting HQ (minimum 1)"
      :choices {:card #(and (:installed %) (= "ICE" (:type %)))}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (= "Ice Wall" (:label (:option decision)))
          "unrezztes Ice Wall gewaehlt statt rezztes Bastion"))))

(deftest pflicht-abwurf-wirft-nie-eine-agenda-ab
  ;; Bug aus einer echten Partie (zweite Partie): der Pflicht-Abwurf-Prompt
  ;; am Zugende (game.core.turns/handle-end-of-turn-discard) fiel ungehandelt
  ;; an den Zufall durch und warf "Offworld Office", eine Agenda, ab -- ueber
  ;; zwei Partien wurde dadurch nie eine Agenda installiert. discard-choice
  ;; routet den Prompt jetzt ueber das Playbook: nie eine Agenda, guenstigste
  ;; Nicht-Agenda-Karte (HAND-CARD-DISCARD-COST) zuerst.
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover" "Regolith Mining License" "Ice Wall"] :credits 20}})
    (core/resolve-ability
     state :corp
     {:prompt "Discard down to 2 cards"
      :choices {:card core/in-hand? :max 1 :all true}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (not= "Hostile Takeover" (:label (:option decision)))
          "Agenda darf nie zum Abwurf gewaehlt werden")
      (is (= "Ice Wall" (:label (:option decision)))
          "guenstigste Nicht-Agenda-Karte (ICE, Kosten 1) statt Regolith Mining License (Asset, Kosten 5)"))))

(deftest pflicht-abwurf-faellt-sichtbar-an-den-zufall-wenn-nur-agenden-zur-wahl-stehen
  ;; Randfall: steht ausschliesslich eine Agenda zur Wahl (Hand voller
  ;; Agenden), gibt discard-choice nil zurueck -- kein Verbot, das den Bot
  ;; blockiert, sondern ein sichtbar markierter Zufalls-Fallback.
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover" "Hostile Takeover"] :credits 20}})
    (core/resolve-ability
     state :corp
     {:prompt "Discard down to 1 cards"
      :choices {:card core/in-hand? :max 1 :all true}
      :effect (fn [_ _ _ _ _])}
     (get-in @state [:corp :identity]) nil)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          prompt (get-in v [:corp :prompt-state])
          options (legal/prompt-options v :corp)
          decision (bp/on-prompt bot v prompt options)]
      (is (str/includes? (:reason decision) "kein Playbook-Handler")
          "nur Agenden zur Wahl -- kein gezielter Griff, sichtbar an den Zufall delegiert"))))

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

(deftest regel-1-breite-vor-tiefe-rd-vor-zweitem-hq-ice
  ;; Breite-vor-Tiefe-Korrektur (2026-09-02, aus der zweiten gespielten
  ;; Partie: Palisade+Whitespace+Diviner alle in Zug 1 auf HQ, R&D/Archives
  ;; blieben unberuehrt). Tithe (Staerke 1, Rez-Kosten 1) taxiert HQ nur
  ;; unzureichend, ABER R&D hat noch GAR KEIN Ice -- Regel 1 muss jetzt
  ;; zuerst R&D sein erstes Ice geben (Breite), statt HQ sofort ein zweites
  ;; zu spendieren (Tiefe). Frueher (central-needing-ice ohne Breite/Tiefe-
  ;; Trennung) waere hier faelschlich ein zweites Ice auf HQ gelegt worden.
  (do-game
    (new-game {:corp {:hand ["Tithe" "Ice Wall"] :credits 20}})
    (play-from-hand state :corp "Tithe" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "R&D")
          "HQ hat schon Ice (wenn auch unzureichend), R&D noch gar keins -> Breite zuerst")
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 1 (count (get-ice state :hq)))
          "kein zweites Ice auf HQ, solange R&D noch gar keins hat")
      (is (= 1 (count (get-ice state :rd)))))))

(deftest regel-1-tiefe-nach-breite-zweites-ice-auf-hq
  ;; Fortsetzung des obigen Falls: HABEN HQ und R&D beide schon je ein Ice
  ;; (Breite abgeschlossen), ist Tithe auf HQ allein weiterhin unzureichend
  ;; -- jetzt DARF Regel 1 ein zweites Ice auf HQ legen (Tiefe), innerhalb
  ;; von CENTRAL-ICE-BASE-DEPTH, ganz ohne beobachteten Breach. Alle Agenda-
  ;; Kopien schon beim Runner gestohlen (ALLE 7 laut TOTAL-AGENDA-COPIES-IN-
  ;; DECK, im Runner-Score-Area, NICHT im Corp-Discard -- sonst wuerde
  ;; archives-has-agenda? Archives selbst zum Breite-Ziel machen) -- KEIN
  ;; Remote-Vorbau-Anspruch mehr, sonst wuerde Regel 2 (jetzt hoeher
  ;; priorisiert als Zentralserver-Tiefe) den Test verfaelschen, indem sie
  ;; einen neuen Remote statt des zweiten HQ-Ice waehlt.
  (do-game
    (new-game {:corp {:hand ["Tithe" "Ice Wall" "Ice Wall"] :credits 20}
               :runner {:score-area ["Offworld Office" "Offworld Office" "Offworld Office"
                                      "Send a Message" "Send a Message"
                                      "Superconducting Hub" "Superconducting Hub"]}})
    (play-from-hand state :corp "Tithe" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (play-from-hand state :corp "Ice Wall" "R&D")
    (rez state :corp (get-ice state :rd 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 1")
          "HQ+R&D beide schon je ein Ice -- Breite abgeschlossen")
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= 2 (count (get-ice state :hq)))
          "Breite abgeschlossen -> Tiefe: HQ zuerst laut Basis-Prioritaet")
      (is (= 1 (count (get-ice state :rd)))))))

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

(deftest regel-2-kein-ice-install-wenn-nur-durch-eigenes-ice-trashen-bezahlbar
  ;; Bug aus einer echten Partie (dritte Partie, Zuege 12+15): der Bot
  ;; trashte zweimal eigenes Ice, um ein drittes fuer 0 Credit zu
  ;; installieren -- netto ein Server, der dadurch SCHWAECHER wurde.
  ;; Aufbau wie regel-2-weiteres-ice-in-unsicheren-scoring-remote oben,
  ;; aber Credits danach auf 1 reduziert: reicht fuer die Kartenkosten von
  ;; Ice Wall (1) allein, NICHT fuer Kartenkosten + Ice-Install-Aufpreis
  ;; (1 Credit fuer das bereits vorhandene Ice Wall auf dem Remote).
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Bastion" "Bastion" "Bastion" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "Archives")
    (play-from-hand state :corp "Ice Wall" "New remote")
    (swap! state assoc-in [:corp :credit] 1)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (not (str/includes? (:reason decision) "Regel 2"))
          "1 Credit deckt Kartenkosten (1), aber nicht +Aufpreis(1 vorhandenes Ice) -- kein Install")
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (= 1 (count (get-ice state :remote1)))
          "kein zweites Ice installiert, wenn nur durch Ice-Trashen bezahlbar"))))

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

(deftest regel-scoring-fenster-credits-erfordert-nur-einen-advance-schritt
  ;; Korrektur 2026-09-03 (zweite Anpassung an credit-ready? im selben Tag):
  ;; die erste Version verlangte den GESAMTEN Restadvancement-Betrag beim
  ;; Install-Gate -- zu streng, denn die Credits fuer WEITERE Advances
  ;; verdient die Corp ueber die folgenden Zuege (Regel 3.5/
  ;; try-fund-score-line deckt genau diesen laufenden Bedarf ohnehin ab).
  ;; 200-Partien-Vergleich zeigte einen Rueckgang Agenda-Siege 57 -> 48 und
  ;; :credits als neu dominante Gap-Dimension -- Verdacht: die Uebersrenge
  ;; erklaert das. credit-ready? verlangt jetzt nur noch Rez-Budget PLUS
  ;; HOECHSTENS EINEN Advance-Schritt (1 Credit), nicht den vollen Rest.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Bastion"] :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10 :credit 10)
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (play-from-hand state :corp "Priority Requisition" "Server 1")
    (click-advance state :corp (get-content state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)]
      (testing "Rez-Budget + 1 Advance-Schritt reicht, obwohl 4 Advances insgesamt fehlen"
        (swap! state assoc-in [:corp :credit] (inc hc/REZ-BUDGET-MARGIN))
        (let [v (view/view-for state :corp)
              decision (bp/decide bot v (legal/turn-actions v :corp))]
          (is (not (contains? (:scoring-gap decision) :credits))
              "3 Credits (0 Rez-Budget + 2 Puffer + 1 Advance-Schritt) reichen -- der Rest kommt ueber die naechsten Zuege")))
      (testing "unter Rez-Budget + 1 Advance-Schritt -> :credits weiterhin im Gap"
        (swap! state assoc-in [:corp :credit] hc/REZ-BUDGET-MARGIN)
        (let [v (view/view-for state :corp)
              decision (bp/decide bot v (legal/turn-actions v :corp))]
          (is (contains? (:scoring-gap decision) :credits)
              "2 Credits reichen nicht mal fuer den naechsten einen Advance-Schritt (0+2+1=3)"))))))

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

(deftest regel-3-kein-fast-advance-bypass-beim-install-mehr
  ;; Korrektur 2026-09-02: der fruehere Fast-Advance-Bypass beim Install
  ;; (Seamless Launch in der Hand macht die Sicherheitsfrage angeblich
  ;; irrelevant) ist entfernt. Grund: same-turn Install+Seamless-Launch ist
  ;; UNMOEGLICH -- place-advancement-counter verlangt eine Karte, die NICHT
  ;; diesen Zug installiert wurde (siehe try-score-line). Eine echte
  ;; Spielpartie zeigte den Schaden: der Bypass installierte eine unsichere
  ;; Agenda, Seamless Launch landete danach mangels gueltigem Ziel
  ;; zufaellig auf einem ANDEREN Ice statt der Agenda. Ein unsicherer
  ;; Remote (Runner hat 20 Credits) blockiert den Install jetzt auch MIT
  ;; Seamless Launch in der Hand.
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
      (is (empty? (get-content state :remote1))
          "Seamless Launch in der Hand darf die Sicherheitsfrage nicht mehr uebergehen"))))

(deftest regel-3-geduldsgrenze-installiert-trotz-anhaltender-unsicherheit
  ;; Totlauf-Fix (2026-09-02, aus der zweiten Partie: :scoring-gap #{:tax}
  ;; blieb ueber mehrere Zuege unveraendert, der Bot klickte bei 20-29
  ;; Credits nur noch fuer Credits, ohne die schon gehaltene Agenda zu
  ;; riskieren). Remote an der Ice-Obergrenze (3x Ice Wall, einzeln zu
  ;; schwach), Runner hat viele Credits -> bleibt dauerhaft unsicher
  ;; (safe-for-commitment? bleibt false). run-history direkt mit einem
  ;; SCORING-PATIENCE-TURNS Zuege alten Stall-Zeitpunkt konstruiert (wie
  ;; regel-1-breach-zaehler-uebersteuert-basis-prioritaet oben) -- die
  ;; Geduldsgrenze installiert die Agenda trotzdem.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "New remote")
    (play-from-hand state :corp "Ice Wall" "Server 1")
    (play-from-hand state :corp "Ice Wall" "Server 1")
    (is (= 3 (count (get-ice state :remote1))) "Testaufbau: Ice-Obergrenze erreicht")
    (let [current-turn (:turn (view/view-for state :corp) 0)
          run-history (atom {:breaches [] :last-run-phase nil :last-run-server nil
                             :remote-stall-since {:zone :remote1
                                                   :turn (- current-turn hc/SCORING-PATIENCE-TURNS)}})
          bot (hc/->HeuristicCorpBot (random/random-bot 1) run-history)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (game-runner/decide-one! {:state state :side :corp :kind :prompt :bot bot})
      (is (= "Priority Requisition" (:title (get-content state :remote1 0)))
          "Geduldsgrenze erreicht -> Agenda installiert, trotz anhaltend unsicherer Taxierung"))))

(deftest regel-3-geduldsgrenze-noch-nicht-erreicht-kein-vorzeitiger-install
  ;; Gegenprobe: derselbe Stall-Zustand, aber erst SCORING-PATIENCE-TURNS
  ;; minus 1 Zuege alt -- die Geduldsgrenze greift noch nicht, Regel 3
  ;; installiert nicht, Regel 2 (weiteres Ice) kann auch nicht mehr (Ober-
  ;; grenze erreicht) -- der Bot weicht auf Econ aus, installiert aber
  ;; keinesfalls die Agenda vorzeitig.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall" "Ice Wall" "Ice Wall"]
                      :credits 20}
               :runner {:credits 20}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Ice Wall" "New remote")
    (play-from-hand state :corp "Ice Wall" "Server 1")
    (play-from-hand state :corp "Ice Wall" "Server 1")
    (let [current-turn (:turn (view/view-for state :corp) 0)
          run-history (atom {:breaches [] :last-run-phase nil :last-run-server nil
                             :remote-stall-since {:zone :remote1
                                                   :turn (- current-turn (dec hc/SCORING-PATIENCE-TURNS))}})
          bot (hc/->HeuristicCorpBot (random/random-bot 1) run-history)]
      (game-runner/decide-one! {:state state :side :corp :kind :action :bot bot})
      (is (empty? (get-content state :remote1))
          "Geduldsgrenze noch nicht erreicht -> keine vorzeitige Agenda-Installation"))))

(deftest regel-2-5-ziehen-hoch-priorisiert-wenn-nur-agenda-fehlt
  ;; Ergaenzung aus der Gap-Statistik (2026-09-02): scheitert das Scoring-
  ;; Fenster AUSSCHLIESSLICH an :agenda (Credits+Taxierung stehen schon),
  ;; ist Ziehen die produktivste Handlung -- outrankt sogar Regel 5.4
  ;; (Econ-Operation spielen), obwohl Hedge Fund spielbar waere.
  (do-game
    (new-game {:corp {:hand ["Hedge Fund" "Bastion" "Bastion" "Bastion"]
                      :deck (repeat 15 "Hedge Fund")
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (= #{:agenda} (:scoring-gap decision)) "Testaufbau-Kontrolle: nur :agenda fehlt")
      (is (str/includes? (:reason decision) "Regel 2.5")))))

(deftest regel-2-5-greift-nicht-in-der-caution-zone
  ;; Dieselben Grenzen wie Regel 5.5: in der Caution-Zone (Restdeck <
  ;; DECK-CAUTION-THRESHOLD) greift die Sonderprioritaet NICHT. Hand nach
  ;; Setup bewusst NICHT duenn (3 uebrige Ice-Wall-Karten, die nirgends
  ;; mehr gebraucht werden -- Zentralen und Remote sind schon ausreichend
  ;; taxiert), damit auch Regel 5.5 (duenne Hand) nicht stattdessen greift
  ;; -- isoliert zeigt das: in der Caution-Zone entscheidet Regel 5.6.
  (do-game
    (new-game {:corp {:hand ["Bastion" "Bastion" "Bastion" "Ice Wall" "Ice Wall" "Ice Wall"]
                      :deck (repeat (dec hc/DECK-CAUTION-THRESHOLD) "Hedge Fund")
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (= #{:agenda} (:scoring-gap decision)) "Testaufbau-Kontrolle: nur :agenda fehlt")
      (is (not (str/includes? (:reason decision) "Regel 2.5"))
          "Caution-Zone -> Sonderprioritaet greift nicht, Regel 5.6 stattdessen")
      (is (str/includes? (:reason decision) "Regel 5.6")))))

(deftest regel-2-5-ziehen-trotz-voller-hand-wenn-alles-wertlos
  ;; Praezisierung 2026-09-02: volle Hand ist KEIN hartes Verbot, sondern
  ;; eine Abwaegung. Sind alle Handkarten ohnehin wertlos (hier:
  ;; unbezahlbare Operationen bei 2 Credits -- Hedge Fund kostet 5,
  ;; Government Subsidy mehr), lohnt sich Ziehen trotzdem: der drohende
  ;; Abwurf kostet nichts.
  (do-game
    (new-game {:corp {:hand ["Bastion" "Bastion" "Bastion" "Hedge Fund" "Hedge Fund"
                             "Government Subsidy" "Government Subsidy" "Government Subsidy"]
                      :deck (repeat 15 "Hedge Fund")
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (swap! state assoc-in [:corp :credit] 2)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (= 5 (count (get-in v [:corp :hand]))) "Testaufbau-Kontrolle: Hand an Maximalgroesse")
      (is (= #{:agenda} (:scoring-gap decision)) "Testaufbau-Kontrolle: nur :agenda fehlt")
      (is (str/includes? (:reason decision) "Regel 2.5")))))

(deftest regel-2-5-kein-ziehen-bei-voller-hand-voller-wertvoller-karten
  ;; Umgekehrter Fall: alle Handkarten sind gerade wertvoll UND spielbar
  ;; (Econ-Assets) -- der Vergleich faellt zugunsten des Spielens aus,
  ;; Regel 2.5 tritt zurueck, Regel 5.1 installiert stattdessen.
  (do-game
    (new-game {:corp {:hand ["Bastion" "Bastion" "Bastion" "Regolith Mining License"
                             "Regolith Mining License" "Nico Campaign" "Nico Campaign" "Nico Campaign"]
                      :deck (repeat 15 "Hedge Fund")
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10)
    (play-from-hand state :corp "Bastion" "HQ")
    (play-from-hand state :corp "Bastion" "R&D")
    (play-from-hand state :corp "Bastion" "New remote")
    (rez state :corp (get-ice state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (= 5 (count (get-in v [:corp :hand]))) "Testaufbau-Kontrolle: Hand an Maximalgroesse")
      (is (= #{:agenda} (:scoring-gap decision)) "Testaufbau-Kontrolle: nur :agenda fehlt")
      (is (not (str/includes? (:reason decision) "Regel 2.5")))
      (is (str/includes? (:reason decision) "Regel 5.1")))))

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

(deftest regel-4-advanct-trotz-unsicherer-taxierung-weiter
  ;; Korrektur 2026-09-02, aus einer echten Partie bestaetigt: einmal
  ;; installiert, blockiert die Sicherheitsfrage das weitere Advancen
  ;; NICHT mehr -- eine liegende, unfertige Agenda ist die riskanteste
  ;; Position im Spiel, Fertigstellen schlaegt Zoegern. Runner hat viele
  ;; Credits (Remote gilt als unsicher/nicht lohnend), trotzdem wird
  ;; weiter advanced statt (wie vorher) auf Regel 5.6 auszuweichen.
  (do-game
    (new-game {:corp {:hand ["Priority Requisition"] :credits 20}
               :runner {:credits 20}})
    (play-from-hand state :corp "Priority Requisition" "New remote")
    (core/gain state :corp :click 10 :credit 10)
    (click-advance state :corp (get-content state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 4 (weiter advancen)")
          "advanct trotz unsicherer Taxierung weiter, statt zu zoegern"))))

(deftest regel-4-schlaegt-regel-1-wenn-beide-verfuegbar
  ;; Reihenfolge-Korrektur 2026-09-02: Scoren/Advancen (Regel 4) outrankt
  ;; jetzt Zentralserver-icen (Regel 1) -- vorher blockierte ein noch
  ;; unversorgter Zentralserver jedes Advancen (in einer echten Partie
  ;; ueber mehrere Zuege bestaetigt: Regel 5.6 statt "advance" gewaehlt,
  ;; obwohl "advance" als legale Option verfuegbar war).
  (do-game
    (new-game {:corp {:hand ["Priority Requisition" "Ice Wall"] :credits 20}
               :runner {:credits 0}})
    (play-from-hand state :corp "Priority Requisition" "New remote")
    (core/gain state :corp :click 10 :credit 10)
    (click-advance state :corp (get-content state :remote1 0))
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 4")
          "Advancen der liegenden Agenda schlaegt Regel 1, obwohl HQ noch Ice braucht"))))

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

(deftest regel-3-5-credits-fuer-laufende-score-linie-vor-ziehen
  ;; Bug aus einer echten Partie (dritte Partie, Zug 16): Agenda mit 3/4
  ;; Advancements im Remote, 0 Credits -- der Bot bestueckte einen neuen
  ;; Server und zog, statt Credits fuer die schon fast fertige Agenda zu
  ;; beschaffen. 0 Credits -> "advance" ist nicht legal (Basisaktion kostet
  ;; 1 Klick + 1 Credit) -> Regel 3.5 outrankt Ziehen (Regel 5.5) UND neue
  ;; Ice-Projekte (leere Hand -- kein Ice zum Installieren ohnehin, aber
  ;; auch keine Econ-Karte -- generischer Credit-Klick, Regel 5.6, bleibt
  ;; als einziges legales Mittel)."
  (do-game
    (new-game {:corp {:hand ["Priority Requisition"]
                      :deck (repeat 15 "Hedge Fund")
                      :credits 20}
               :runner {:credits 0}})
    (core/gain state :corp :click 10 :credit 10)
    (play-from-hand state :corp "Priority Requisition" "New remote")
    (click-advance state :corp (get-content state :remote1 0))
    (swap! state assoc-in [:corp :credit] 0)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 3.5")
          "0 Credits blockieren die laufende Score-Linie -> Credits-Beschaffen schlaegt Ziehen")
      (is (str/includes? (:reason decision) "Regel 5.6")))))

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

(deftest regel-5-5-reichlich-credits-loest-ziehen-vor-credit-klick-aus
  ;; Wiedereinführung 2026-09-02 (aus einer echten Partie: 13-16 Credits,
  ;; wiederholtes Klicken statt Ziehen): Agenda in der Hand, Hand nicht
  ;; duenn, Deck komfortabel voll, aber Credits >= PLENTY-CREDITS -> Ziehen
  ;; (Regel 5.5) schlaegt den generischen Credit-Klick (Regel 5.6). Kein
  ;; Widerspruch zur fruehereren Entfernung von PLENTY-CREDITS (siehe
  ;; should-draw?-Docstring): Regel 5.5 steht strukturell schon hinter
  ;; allen produktiveren Regeln, konkurriert hier nur noch gegen den reinen
  ;; Credit-Klick.
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover" "Hostile Takeover" "Hostile Takeover"]
                      :deck (repeat 15 "Hedge Fund")
                      :credits 30}})
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (str/includes? (:reason decision) "Regel 5.5 (Karte ziehen)")
          "reichlich Credits (>= PLENTY-CREDITS) loesen Ziehen vor dem Credit-Klick aus"))))

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
