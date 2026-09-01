(ns bot.eval-test
  (:require
   [bot.eval :as beval]
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
