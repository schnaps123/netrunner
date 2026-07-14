(ns bot.roster-test
  (:require
   [bot.cards :as cards]
   [bot.protocol :as bp]
   [bot.roster :as roster]
   [clojure.test :refer :all]))

(use-fixtures :once (fn [f] (cards/load-all-cards!) (f)))

(deftest deck-registry
  (is (= cards/gateway-corp (cards/bot-deck-for "Corp")))
  (is (= cards/gateway-runner (cards/bot-deck-for "Runner"))))

(deftest difficulty-registry
  (is (roster/difficulty? "random"))
  (is (not (roster/difficulty? "gibtsnicht")))
  (is (satisfies? bp/Bot (roster/make-bot "random")))
  (is (thrown? clojure.lang.ExceptionInfo (roster/make-bot "gibtsnicht"))))

(deftest bot-player-form
  (let [p (roster/bot-player "Runner" "random")]
    (is (nil? (:uid p)))
    (is (true? (:bot p)))
    (is (= "Runner" (:side p)))
    (is (= "Bot (Random)" (get-in p [:user :username])))
    (is (= "The Catalyst: Convention Breaker" (get-in p [:deck :identity :title])))
    (is (every? #(and (map? (:card %)) (pos? (:qty %))) (get-in p [:deck :cards]))
        "Deck engine-fertig: Karten als server-card-Maps mit :qty")))
