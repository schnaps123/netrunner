(ns web.lobby-test
  (:require
   [bot.cards :as bot-cards]
   [clojure.test :refer :all]
   [web.app-state :as app-state]
   [web.lobby :as lobby]))

(use-fixtures :once (fn [f] (bot-cards/load-all-cards!) (f)))

(defn- mklobby [options]
  (lobby/create-new-lobby {:uid "uid-1" :user {:username "david"} :options options}))

(def auto-opts
  {:auto-select-default-deck-casual true
   :default-decks {:Corp {:standard "corp-standard"
                          :eternal "corp-eternal"}
                   :Runner {:standard "runner-standard"}}})

(deftest auto-select-deck-id-test
  (testing "returns the default deck id for the given side and format in casual"
    (is (= "corp-standard" (lobby/auto-select-deck-id auto-opts "casual" "standard" "Corp")))
    (is (= "corp-eternal" (lobby/auto-select-deck-id auto-opts "casual" "eternal" "Corp")))
    (is (= "runner-standard" (lobby/auto-select-deck-id auto-opts "casual" "standard" "Runner"))))

  (testing "nil when casual auto-select is disabled"
    (is (nil? (lobby/auto-select-deck-id (assoc auto-opts :auto-select-default-deck-casual false)
                                         "casual" "standard" "Corp"))))

  (testing "tournament uses its own setting, not the casual one"
    (is (nil? (lobby/auto-select-deck-id auto-opts "competitive" "standard" "Corp")))
    (is (= "corp-standard"
           (lobby/auto-select-deck-id (assoc auto-opts :auto-select-default-deck-tournament true)
                                      "competitive" "standard" "Corp"))))

  (testing "nil in rooms that never auto-select"
    (is (nil? (lobby/auto-select-deck-id auto-opts "angel-arena" "standard" "Corp"))))

  (testing "nil for Any Side"
    (is (nil? (lobby/auto-select-deck-id auto-opts "casual" "standard" "Any Side"))))

  (testing "nil when no default deck for that side+format"
    (is (nil? (lobby/auto-select-deck-id auto-opts "casual" "eternal" "Runner")))
    (is (nil? (lobby/auto-select-deck-id (assoc auto-opts :default-decks {})
                                         "casual" "standard" "Corp")))))

(defn- player
  ([side] (player side nil))
  ([side deck]
   (cond-> {:uid side :side side :user {:username side}}
     deck (assoc :deck deck))))

(defn- auto-select
  [lobby & {:keys [options deck-found?] :or {options auto-opts deck-found? true}}]
  (with-redefs [lobby/find-deck-for-user (fn [_db deck-id _user] (when deck-found? {:_id deck-id}))
                lobby/process-deck (fn [deck] (when deck (assoc deck :identity {:title "id"})))
                lobby/valid-deck-for-lobby? (fn [_lobby _deck] true)
                app-state/get-user (fn [_uid] {:options options})]
    (lobby/auto-select-decks nil lobby)))

(deftest auto-select-decks-test
  (testing "selects each deckless player's default deck for the lobby format"
    (let [result (auto-select {:room "casual" :format "standard"
                               :players [(player "Corp") (player "Runner")]})]
      (is (= "corp-standard" (get-in result [:players 0 :deck :_id])))
      (is (= "runner-standard" (get-in result [:players 1 :deck :_id])))))

  (testing "leaves a player that already chose a deck untouched"
    (let [chosen {:_id "manual"}
          result (auto-select {:room "casual" :format "standard"
                               :players [(player "Corp" chosen)]})]
      (is (= chosen (get-in result [:players 0 :deck])))))

  (testing "no-op once the game has started"
    (let [result (auto-select {:room "casual" :format "standard" :started true
                               :players [(player "Corp")]})]
      (is (nil? (get-in result [:players 0 :deck])))))

  (testing "no-op when the configured default deck no longer exists"
    (let [result (auto-select {:room "casual" :format "standard"
                               :players [(player "Corp")]}
                              :deck-found? false)]
      (is (nil? (get-in result [:players 0 :deck])))))

  (testing "no-op for non-constructed games"
    (let [opts {:auto-select-default-deck-casual true
                :default-decks {:Corp {:quick-draft "qd-deck"
                                       :chimera "ch-deck"}}}
          deck (fn [lobby] (get-in (auto-select (assoc lobby :players [(player "Corp")]) :options opts)
                                   [:players 0 :deck :_id]))]
      (is (nil? (deck {:room "casual" :format "quick-draft"})))
      (is (nil? (deck {:room "casual" :format "chimera"})))))

  (testing "no-op for precon gateway, but works for non-precon gateway"
    (let [opts {:auto-select-default-deck-casual true
                :default-decks {:Corp {:system-gateway "sg-deck"}}}
          deck (fn [lobby] (get-in (auto-select (assoc lobby :players [(player "Corp")]) :options opts)
                                   [:players 0 :deck :_id]))]
      (is (nil? (deck {:room "casual" :format "system-gateway" :precon :beginner})))
      (is (= "sg-deck" (deck {:room "casual" :format "system-gateway"}))))))

(deftest create-vs-bot-lobby
  (let [l (mklobby {:bot-game "vs-bot" :side "Runner" :difficulty "random"
                    :format "system-gateway" :title "t" :room "casual"})]
    (is (= "vs-bot" (:bot-game l)))
    (is (= 2 (count (:players l))))
    (is (= ["Runner" "Corp"] (mapv :side (:players l))) "Bot nimmt Gegenseite")
    (is (= "uid-1" (:uid (first (:players l)))) "Ersteller bleibt first-player")
    (let [bot (second (:players l))]
      (is (:bot bot))
      (is (nil? (:uid bot)))
      (is (some? (:deck bot)) "Bot-Deck ab Erstellung gesetzt"))
    (is (some? (get-in l [:bots :corp])))
    (is (nil? (get-in l [:bots :runner])))
    (is (false? @(:bot-thinking? l)))))

(deftest create-vs-bot-lobby-default-corp
  (let [l (mklobby {:bot-game "vs-bot" :side "Any Side" :difficulty "random"
                    :format "system-gateway" :title "t" :room "casual"})]
    (is (= ["Corp" "Runner"] (mapv :side (:players l)))
        "Any Side wird für vs-bot zu Corp normalisiert")))

(deftest create-bot-vs-bot-lobby
  (let [l (mklobby {:bot-game "bot-vs-bot" :difficulty "random"
                    :format "system-gateway" :title "t" :room "casual"})]
    (is (every? :bot (:players l)))
    (is (= [{:uid "uid-1" :user {:username "david"}}] (vec (:spectators l)))
        "Ersteller wird Spectator")
    (is (true? (:allow-spectator l)) "Spectators erzwungen erlaubt")
    (is (some? (get-in l [:bots :corp])))
    (is (some? (get-in l [:bots :runner])))))

(deftest create-bot-lobby-validierung
  (testing "unbekannter bot-game-Wert ⇒ normale Lobby"
    (let [l (mklobby {:bot-game "quatsch" :title "t" :room "casual" :side "Any Side"})]
      (is (nil? (:bot-game l)))
      (is (= 1 (count (:players l))))))
  (testing "unbekannte difficulty ⇒ normale Lobby"
    (let [l (mklobby {:bot-game "vs-bot" :difficulty "gibtsnicht"
                      :title "t" :room "casual" :side "Corp"})]
      (is (nil? (:bot-game l)))
      (is (= 1 (count (:players l))))))
  (testing "ohne bot-game exakt altes Verhalten"
    (let [l (mklobby {:title "t" :room "casual" :side "Any Side"})]
      (is (nil? (:bot-game l)))
      (is (nil? (:bots l)))
      (is (= 1 (count (:players l)))))))

(deftest bot-lobby-nicht-joinbar
  ;; Spec: fremde Spieler können Bot-Lobbys nicht joinen. Trägt der bestehende
  ;; Guard in insert-user-as-player (nur bei genau 1 Player) — hier festnageln.
  (let [l (mklobby {:bot-game "vs-bot" :side "Corp" :difficulty "random"
                    :format "system-gateway" :title "t" :room "casual"})]
    (is (= (:players l)
           (:players (lobby/insert-user-as-player l "uid-2" {:username "eve"} nil)))
        "vs-bot-Lobby hat 2 Player — Join ist ein No-Op")))

(deftest bot-lobby-summary-ohne-interna
  (let [l (mklobby {:bot-game "vs-bot" :side "Corp" :difficulty "random"
                    :format "system-gateway" :title "t" :room "casual"})
        summary (lobby/lobby-summary l)]
    (is (= "vs-bot" (:bot-game summary)) ":bot-game geht an den Client")
    (is (not (contains? summary :bots)) "Bot-Instanzen nie an den Client")
    (is (not (contains? summary :bot-thinking?)))))
