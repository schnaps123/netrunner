(ns bot.view-test
  (:require
   [bot.view :as view]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [game.test-framework :refer :all]))

(defn- alle-titel [view]
  (let [titles (atom #{})]
    (walk/postwalk (fn [x]
                     (when (and (map? x) (:title x))
                       (swap! titles conj (:title x)))
                     x)
                   view)
    @titles))

(deftest corp-view-versteckt-runner-hand-und-decks
  (do-game
    (new-game {:corp {:hand ["Hedge Fund"]}
               :runner {:hand ["Sure Gamble" "Diesel"]}})
    (let [v (view/view-for state :corp)]
      (is (= [] (get-in v [:runner :hand])) "Runner-Handkarten unsichtbar")
      (is (= 2 (get-in v [:runner :hand-count])) "nur Anzahl sichtbar")
      (is (= ["Hedge Fund"] (mapv :title (get-in v [:corp :hand]))) "eigene Hand sichtbar")
      (is (= [] (get-in v [:corp :deck])) "eigenes Deck verdeckt (nur deck-count)")
      (is (not (contains? (alle-titel v) "Sure Gamble"))
          "Kein Runner-Handkarten-Titel taucht irgendwo in der Corp-View auf"))))

(deftest runner-view-versteckt-unrezztes-ice
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (let [v (view/view-for state :runner)
          ice (first (get-in v [:corp :servers :hq :ices]))]
      (is (some? ice) "Ice ist als Objekt sichtbar")
      (is (nil? (:title ice)) "Titel des unrezzten Ice verborgen"))))

(deftest playable-flag-auf-handkarten
  (do-game
    (new-game {:corp {:hand ["Hedge Fund"]}})
    (let [v (view/view-for state :corp)]
      (is (true? (:playable (first (get-in v [:corp :hand]))))
          "Hedge Fund bei 5 Credits + Klicks spielbar"))))

(deftest phase-of-grundfaelle
  (do-game
    (new-game)
    (is (= :action/corp (view/phase-of (view/view-for state :corp))))
    (take-credits state :corp)
    (is (= :action/runner (view/phase-of (view/view-for state :runner))))))
