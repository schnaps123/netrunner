(ns bot.difficulties-test
  (:require
   [bot.difficulties :as sut]
   [clojure.test :refer [deftest is]]))

(deftest known?-test
  (is (sut/known? "random"))
  (is (sut/known? "heuristic"))
  (is (not (sut/known? "gibtsnicht"))))

(deftest available-for-side?-test
  (is (sut/available-for-side? "random" :corp))
  (is (sut/available-for-side? "random" :runner))
  (is (sut/available-for-side? "heuristic" :corp))
  (is (not (sut/available-for-side? "heuristic" :runner))
      "Heuristik-Bot existiert bisher nur fuer die Corp-Seite (Schritt 7a)")
  (is (not (sut/available-for-side? "gibtsnicht" :corp))))

(deftest for-side-test
  (is (= ["random" "heuristic"] (sut/for-side :corp)))
  (is (= ["random"] (sut/for-side :runner))))

(deftest for-sides-test
  (is (= ["random"] (sut/for-sides [:corp :runner]))
      "bot-vs-bot: nur Grade, die fuer BEIDE Seiten verfuegbar sind")
  (is (= ["random" "heuristic"] (sut/for-sides [:corp]))))

(deftest label-key-test
  (is (= :lobby_bot-difficulty-random (sut/label-key "random")))
  (is (= :lobby_bot-difficulty-heuristic (sut/label-key "heuristic"))))

(deftest bot-sides-test
  (is (= [:runner] (sut/bot-sides "vs-bot" "Corp"))
      "Mensch spielt Corp -> Bot ist Runner")
  (is (= [:runner] (sut/bot-sides "vs-bot" "Any Side"))
      "Any Side normalisiert wie serverseitig zu Corp -> Bot ist Runner")
  (is (= [:corp] (sut/bot-sides "vs-bot" "Runner"))
      "Mensch spielt Runner -> Bot ist Corp")
  (is (= [:corp :runner] (sut/bot-sides "bot-vs-bot" "Corp")))
  (is (= [] (sut/bot-sides nil "Corp"))))

(deftest options-for-lobby-test
  (is (= ["random"] (sut/options-for-lobby "vs-bot" "Corp"))
      "Bot=Runner -> nur random, kein heuristic")
  (is (= ["random" "heuristic"] (sut/options-for-lobby "vs-bot" "Runner"))
      "Bot=Corp -> auch heuristic verfuegbar")
  (is (= ["random"] (sut/options-for-lobby "bot-vs-bot" "Corp"))
      "bot-vs-bot: Schnittmenge -> nur random")
  (is (= [] (sut/options-for-lobby nil "Corp"))))
