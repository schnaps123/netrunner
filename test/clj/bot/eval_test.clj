(ns bot.eval-test
  (:require
   [bot.eval :as beval]
   [bot.view :as bview]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.test-framework :refer :all]))

(deftest credit-diff-mehr-credits-bessere-bewertung
  ;; core/gain statt click-credit: click-credit tauscht 1 Klick gegen 1
  ;; Credit — unter gleicher Klick-/Credit-Gewichtung netto score-neutral
  ;; (kein Bug, siehe click-diff-sinkt-nach-eigenem-klick). Um die
  ;; Credit-Dimension isoliert zu prüfen, Credits ohne Klick-Kosten vergeben.
  (do-game
    (new-game)
    (let [before (beval/evaluate state :corp)]
      (core/gain state :corp :credit 3)
      (let [after (beval/evaluate state :corp)]
        (is (= 3 (- (:credit-diff after) (:credit-diff before)))
            "eigener Credit-Gewinn erhöht die Credit-Differenz entsprechend")
        (is (< (:score before) (:score after))
            "mehr Credits verbessern die Gesamtbewertung")))))

(deftest card-advantage-sinkt-beim-ausspielen
  (do-game
    (new-game {:corp {:hand ["Hedge Fund"]}})
    (let [before (beval/evaluate state :corp)]
      (play-from-hand state :corp "Hedge Fund")
      (let [after (beval/evaluate state :corp)]
        (is (= 1 (- (:card-advantage before) (:card-advantage after)))
            "gespielte Karte verlässt Hand+Deck-Pool Richtung Discard")))))

(deftest click-diff-sinkt-nach-eigenem-klick
  ;; click-credit tauscht 1 Klick gegen 1 Credit — score bleibt unter
  ;; gleicher Gewichtung netto neutral, nur die Klick-Dimension wird geprüft.
  (do-game
    (new-game)
    (let [before (beval/evaluate state :corp)]
      (click-credit state :corp)
      (let [after (beval/evaluate state :corp)]
        (is (= 1 (- (:click-diff before) (:click-diff after)))
            "verbrauchter eigener Klick senkt die Klick-Differenz um 1")))))

(deftest agenda-diff-steigt-nach-score
  (do-game
    (new-game {:corp {:hand ["Hostile Takeover"]}})
    (play-from-hand state :corp "Hostile Takeover" "New remote")
    (let [before (beval/evaluate state :corp)]
      (score-agenda state :corp (get-content state :remote1 0))
      (let [after (beval/evaluate state :corp)]
        (is (pos? (- (:agenda-diff after) (:agenda-diff before)))
            "gescorte Agenda erhöht die Agenda-Differenz")
        (is (< (:score before) (:score after))
            "Agenda-Gewichtung schlägt im Gesamtscore durch")))))

(deftest score-ist-nullsumme
  (do-game
    (new-game)
    (click-credit state :corp)
    (is (zero? (+ (:score (beval/evaluate state :corp))
                   (:score (beval/evaluate state :runner))))
        "Bewertung ist symmetrisch: Vorteil der einen Seite = Nachteil der anderen")))

;; --- Server-Bedrohungsschätzung ---

(deftest mehr-ice-hoehere-geschaetzte-kosten
  (do-game
    (new-game {:corp {:hand [(qty "Ice Wall" 2)]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [one (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
      (play-from-hand state :corp "Ice Wall" "HQ")
      (rez state :corp (get-ice state :hq 1))
      (let [two (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
        (is (> two one) "zweites rezztes Ice erhöht die geschätzten Kosten")))))

(deftest unrezztes-ice-nutzt-unknown-default-fuer-runner
  ;; Ice Wall ist ein schwaches/billiges Ice (Stärke 1). Wenn der Runner
  ;; (fremde, unrezzte Corp-Ice sind für ihn privat) trotzdem einen HÖHEREN
  ;; Wert schätzt als die Corp selbst (die die echten, schwachen Werte
  ;; kennt), beweist das: der generische Unknown-Default wird verwendet,
  ;; nicht 0 und nicht die echten (durchgesickerten) Werte.
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (let [runner-cost (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])
          corp-cost (get-in (beval/evaluate state :corp) [:servers :hq :estimated-cost])]
      (is (pos? runner-cost) "unrezztes Ice zählt nicht als 0 Bedrohung")
      (is (> runner-cost corp-cost)
          "Runner-Schätzung basiert auf dem Unknown-Default, nicht auf dem echten schwachen Ice Wall"))))

(deftest corp-sicht-auf-eigenes-unrezztes-ice-nutzt-echte-werte
  (do-game
    (new-game {:corp {:hand [(qty "Ice Wall" 1)]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (let [unrezzt (get-in (beval/evaluate state :corp) [:servers :hq :estimated-cost])]
      (rez state :corp (get-ice state :hq 0))
      (let [rezzt (get-in (beval/evaluate state :corp) [:servers :hq :estimated-cost])]
        (is (= unrezzt rezzt)
            "Corp sieht eigenes Ice unrezzt bereits mit den echten Werten — Rezzen ändert die Schätzung nicht")))))

(deftest rez-erschwinglichkeit-senkt-bedrohung-ohne-auf-0-zu-fallen
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (swap! state assoc-in [:corp :credit] 0)
    (let [arm (get-in (beval/evaluate state :corp) [:servers :hq :estimated-cost])]
      (swap! state assoc-in [:corp :credit] 20)
      (let [reich (get-in (beval/evaluate state :corp) [:servers :hq :estimated-cost])]
        (is (pos? arm) "Bluff ohne Rez-Deckung ist nicht wertlos")
        (is (< arm reich)
            "Corp kann sich den Rez nicht leisten ⇒ Bedrohung wird abgewertet")))))

(deftest icebreaker-senkt-bedrohungsschaetzung
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}
               :runner {:hand ["Corroder"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [ohne-breaker (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
      (take-credits state :corp)
      (play-from-hand state :runner "Corroder")
      (let [mit-breaker (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
        (is (< mit-breaker ohne-breaker)
            "installierter Icebreaker senkt die geschätzten Durchbruchskosten")))))

(deftest runner-can-afford-kippt-mit-credits
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (swap! state assoc-in [:runner :credit] 0)
    (let [arm (get-in (beval/evaluate state :runner) [:servers :hq :runner-can-afford?])]
      (swap! state assoc-in [:runner :credit] 20)
      (let [reich (get-in (beval/evaluate state :runner) [:servers :hq :runner-can-afford?])]
        (is (false? arm) "0 Credits reichen nicht für den Durchbruch")
        (is (true? reich) "20 Credits reichen für den Durchbruch")))))

(deftest score-bleibt-nullsumme-mit-server-bedrohung
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (is (pos? (get-in (beval/evaluate state :corp) [:threat-level]))
        "Test-Voraussetzung: es gibt überhaupt eine Bedrohung zu verrechnen")
    (is (zero? (+ (:score (beval/evaluate state :corp))
                   (:score (beval/evaluate state :runner))))
        "Threat-Level-Vorzeichen (+Corp/-Runner) bleibt nullsummen-konsistent")))

(deftest breaker-typ-mismatch-senkt-bedrohung-nicht
  ;; Carmen ist ein Sentry-Breaker (siehe game.cards.programs/"Carmen":
  ;; (break-sub 1 1 "Sentry")). Gegen Ice Wall (Barrier) darf er die
  ;; Bedrohungsschaetzung NICHT senken -- ein Bug vor diesem Fix nahm den
  ;; global staerksten installierten Breaker unabhaengig vom Ice-Typ.
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}
               :runner {:hand ["Carmen"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [ohne-breaker (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
      (take-credits state :corp)
      (play-from-hand state :runner "Carmen")
      (let [mit-falschem-typ (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
        (is (= 2 ohne-breaker) "Baseline: raw-ice-cost(Staerke 1, Breaker 0) = max(1, 1-0+1) = 2")
        (is (= mit-falschem-typ ohne-breaker)
            "Carmen (Sentry) hilft nicht gegen Ice Wall (Barrier) -- Typ-Match, kein globaler Staerkenwert")))))

(deftest echte-break-kosten-corroder-vs-ice-wall
  ;; Corroder: Staerke 2, Fracter, "1cr: break 1 Barrier-Sub", "1cr: +1
  ;; Staerke". Ice Wall: Staerke 1, 1 Subroutine. Keine Pump noetig (2>=1),
  ;; 1 Sub * 1cr = 1.
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}
               :runner {:hand ["Corroder"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (take-credits state :corp)
    (play-from-hand state :runner "Corroder")
    (is (= 1 (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])))))

(deftest echte-break-kosten-skalieren-mit-subroutine-anzahl
  ;; Battlement: Staerke 2, Barrier, 2 Subroutinen ("End the run" je zweimal).
  ;; Corroder (Staerke 2) braucht keine Pump, aber 2 Subs * 1cr = 2.
  (do-game
    (new-game {:corp {:hand ["Battlement"] :credits 10}
               :runner {:hand ["Corroder"] :credits 10}})
    (play-from-hand state :corp "Battlement" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (take-credits state :corp)
    (play-from-hand state :runner "Corroder")
    (is (= 2 (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])))))

(deftest echte-break-kosten-inkl-pump
  ;; Bastion: Staerke 4, Barrier, 1 Subroutine. Corroder (Staerke 2) muss
  ;; erst 2 Staerke pumpen (2 * 1cr = 2cr), dann 1 Sub brechen (1cr) = 3cr.
  (do-game
    (new-game {:corp {:hand ["Bastion"] :credits 10}
               :runner {:hand ["Corroder"] :credits 10}})
    (play-from-hand state :corp "Bastion" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (take-credits state :corp)
    (play-from-hand state :runner "Corroder")
    (is (= 3 (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])))))

(deftest exotische-break-kosten-fallen-auf-staerke-delta-zurueck
  ;; Musaazi bricht Sentry-Subs fuer Virus-Counter statt Credits (kein
  ;; reiner Credit-Preis) -- die Kosten-Schaetzung darf nicht crashen,
  ;; sondern faellt auf die (typgenaue) Staerke-Delta-Schaetzung aus Task 1
  ;; zurueck. Tithe: Staerke 1, Sentry, 2 Subs. Musaazi: Staerke 1.
  (do-game
    (new-game {:corp {:hand ["Tithe"]}
               :runner {:hand ["Musaazi"] :credits 10}})
    (play-from-hand state :corp "Tithe" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [ohne-breaker (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
      (take-credits state :corp)
      (play-from-hand state :runner "Musaazi")
      (let [mit-musaazi (get-in (beval/evaluate state :runner) [:servers :hq :estimated-cost])]
        (is (= 2 ohne-breaker) "raw-ice-cost(Staerke 1, Breaker 0) = max(1, 1-0+1) = 2")
        (is (= 1 mit-musaazi)
            "Typ-Match (Sentry) senkt weiterhin die Staerke-Delta-Schaetzung auf max(1, 1-1+1)=1, kein Crash trotz Virus-Kosten")))))

(deftest evaluate-view-liefert-dasselbe-wie-evaluate
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (is (= (beval/evaluate state :corp)
           (beval/evaluate-view (bview/view-for state :corp) :corp))
        "evaluate ist nur noch ein duenner Wrapper um evaluate-view")))

(deftest servers-threat-ist-oeffentlich-und-view-basiert
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (is (contains? (beval/servers-threat (bview/view-for state :corp)) :hq))))
