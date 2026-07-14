# Solo-Modus (vs. Bot in der Web-Lobby) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In der Web-Lobby sind Spiele „vs. Bot" (Seitenwahl Corp/Runner) und „Bot vs. Bot" (Spectator) erstellbar; der Bot besetzt serverseitig den zweiten Seat, reagiert auf dieselben Engine-Zustandsänderungen wie ein menschlicher Client mit 1–2 s Bedenkzeit, und loggt Begründungen in eine Datei pro Partie.

**Architecture:** Neues Modul `bot.seat` reagiert über ein Hook-Atom in `web.game` (Registrierung beim Integrant-Systemstart via `bot.seat/register!` — kein Namespace-Zyklus) auf jede State-Änderung: `bot.game-runner/next-actor` bestimmt, ob eine Bot-Seite handeln muss; die Entscheidung läuft über die aus `step!` extrahierte Funktion `decide-one!` (Legal-Options, No-Op-Retry, Fingerprint) mit pluggable `apply-fn`, die im Web-Fall `web.game/update-and-send-diffs!` nutzt. Bedenkzeit schläft auf einem eigenen Thread; die State-Mutation selbst läuft seriell auf dem Game-Thread der Lobby. Ein separates UI-freies Modul `bot.roster` (Difficulty-Registry + Bot-Player-Konstruktion) wird von `web.lobby` konsumiert, damit `web.lobby` nie `bot.seat` braucht.

**Tech Stack:** Clojure (Server), Reagent/ClojureScript (Lobby-UI), Kaocha für Tests (`bin/test-focus <ns>` im Docker bzw. `lein kaocha --focus <ns>`), shadow-cljs (`npm run cljs:build`).

**Spec:** `docs/superpowers/specs/2026-07-14-solo-modus-design.md`

## Global Constraints

- **Kein Code unter `src/clj/game/core/` oder `src/clj/game/cards/` ändern** (Projektregel: Regelkern nur nach Absprache).
- **Informations-Hygiene:** Bots bekommen NUR Views aus `bot.view/view-for`; roher `@state` nur für Harness-Mechanik (eid, Fingerprint, next-actor).
- **Logging-Pflicht:** Jede Bot-Entscheidung als EDN-Zeile nach `logs/bot-games/<gameid>.edn` — `{:turn :phase :side :kind :options :choice :reason :no-op :difficulty}`. NICHTS davon ins Spiel-Log.
- **Multiplayer unangetastet:** Jede Änderung an `web/lobby.clj`/`web/game.clj` muss für Lobbys ohne `:bot-game` exakt das alte Verhalten liefern (Regressionstests bestehender Namespaces).
- **Stats:** `game-started`/`game-finished` (Replay!) laufen für Bot-Partien NORMAL; nur `update-deck-stats`/`update-game-stats`/`push-stats-update` werden bei `:bot-game` übersprungen.
- Tests: `lein kaocha --focus <ns>` bzw. `bin/test-focus <ns>` (Docker; nicht-interaktiv `docker exec netrunner-server-1 lein ...`). Vor dem letzten Commit alle `bot.*`- und betroffenen `web.*`-Namespaces + `npm run cljs:build`.
- Arbeitsbranch: `feat/solo-modus` (von `master`). Conventional Commits, deutsch.
- Voraussetzung: `data/cards.edn` existiert (`lein fetch` gelaufen).

---

### Task 1: `bot.log` — `:difficulty`-Feld + freie Events

**Files:**
- Modify: `src/clj/bot/log.clj`
- Test: `test/clj/bot/log_test.clj` (existiert, erweitern)

**Interfaces:**
- Consumes: nichts Neues
- Produces:
  - `bot.log/decision-entry` behält Whitelist, nimmt zusätzlich optional `:difficulty` auf (String; fehlt der Key, fehlt er auch im Entry)
  - `bot.log/append-event!` — `(append-event! path m)` schreibt eine beliebige Map als EDN-Zeile (für Fehler-/Concede-Events); `append-decision!` nutzt intern denselben Schreibpfad

- [ ] **Step 1: Failing Test schreiben** — in `test/clj/bot/log_test.clj` ergänzen:

```clojure
(deftest decision-entry-difficulty
  (testing "difficulty wird übernommen, wenn vorhanden"
    (is (= "random" (:difficulty (log/decision-entry {:turn 1 :side :corp :difficulty "random"})))))
  (testing "difficulty fehlt im Entry, wenn nicht übergeben"
    (is (not (contains? (log/decision-entry {:turn 1 :side :corp}) :difficulty)))))

(deftest append-event-schreibt-edn-zeile
  (let [f (java.io.File/createTempFile "bot-log" ".edn")
        path (.getPath f)]
    (log/append-event! path {:event :concede :side :runner :error "kaputt"})
    (log/append-event! path {:event :info})
    (let [lines (clojure.string/split-lines (slurp path))]
      (is (= 2 (count lines)))
      (is (= {:event :concede :side :runner :error "kaputt"}
             (clojure.edn/read-string (first lines)))))
    (.delete f)))
```

(Requires im ns ergänzen: `[clojure.edn]`, `[clojure.string]` falls nicht vorhanden.)

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `bin/test-focus bot.log-test`
Expected: FAIL (`append-event!` unbekannt; `:difficulty` fehlt)

- [ ] **Step 3: Implementierung** — `src/clj/bot/log.clj` komplett:

```clojure
(ns bot.log
  "Decision-Log: eine EDN-Zeile pro Bot-Entscheidung (Projektregel: Pflicht).
  options/choice sind Labels (Strings), nie rohe Karten-Maps."
  (:require
   [clojure.java.io :as io]))

(defn append-event!
  "Schreibt eine beliebige Map als eine EDN-Zeile (z.B. Fehler/Concede)."
  [path m]
  (io/make-parents (io/file path))
  (spit path (str (pr-str m) "\n") :append true)
  nil)

(defn decision-entry
  [{:keys [turn phase side kind options choice reason no-op difficulty]}]
  (cond-> {:turn turn
           :phase phase
           :side side
           :kind kind
           :options (vec options)
           :choice choice
           :reason reason
           :no-op (boolean no-op)}
    difficulty (assoc :difficulty difficulty)))

(defn append-decision!
  [path entry]
  (append-event! path (decision-entry entry)))
```

- [ ] **Step 4: Tests grün**

Run: `bin/test-focus bot.log-test`
Expected: PASS (alle, inkl. der bestehenden)

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/log.clj test/clj/bot/log_test.clj
git commit -m "feat(bot): Decision-Log um :difficulty und freie Events erweitert"
```

---

### Task 2: `bot.game-runner` — `decide-one!` extrahieren (Refactor)

**Files:**
- Modify: `src/clj/bot/game_runner.clj`
- Test: `test/clj/bot/game_runner_test.clj` (Regression + neuer Test)

**Interfaces:**
- Consumes: `bot.log/append-decision!` (Task 1, nimmt `:difficulty` mit)
- Produces (alles im ns `bot.game-runner`, von `bot.seat` konsumiert):
  - `next-actor` — unverändert public: `(next-actor state)` → `[side kind]` oder nil
  - `actionable-prompt?` — NEU public: `(actionable-prompt? state side)` → truthy, wenn die Seite einen beantwortbaren Prompt hat (nicht `:waiting`/`:run`)
  - `apply-choice!` — NEU public: `(apply-choice! state side kind chosen)` führt die gewählte Option über `game.core/process-action` aus
  - `decide-one!` — `(decide-one! {:keys [state side kind bot log-path apply-fn log-extra step-no]})`; `:apply-fn` default `apply-choice!` (4-arity wie oben), `:log-extra` Map wird in jeden Log-Eintrag gemergt. Wirft `ex-info "Keine ausführbare Option übrig"` wenn keine Option den State bewegt.

- [ ] **Step 1: Failing Test schreiben** — in `test/clj/bot/game_runner_test.clj` ergänzen:

```clojure
(deftest decide-one!-nutzt-apply-fn-und-log-extra
  (cards/load-all-cards!)
  (let [state (setup/init-game
               {:gameid 1 :format "casual"
                :players [(cards/player-entry "Corp" cards/gateway-corp)
                          (cards/player-entry "Runner" cards/gateway-runner)]})
        applied (atom [])
        f (java.io.File/createTempFile "decide-one" ".edn")
        bot (random/random-bot 1)
        ;; Nach init-game hat jede Seite den Keep/Mulligan-Prompt
        [side kind] (game-runner/next-actor state)]
    (game-runner/decide-one!
     {:state state :side side :kind kind :bot bot
      :log-path (.getPath f)
      :log-extra {:difficulty "random"}
      :apply-fn (fn [state side kind chosen]
                  (swap! applied conj [side kind (:label chosen)])
                  (game-runner/apply-choice! state side kind chosen))})
    (is (= 1 (count @applied)) "apply-fn genau einmal aufgerufen (kein No-Op bei Mulligan)")
    (let [entry (clojure.edn/read-string (first (clojure.string/split-lines (slurp f))))]
      (is (= "random" (:difficulty entry)) "log-extra landet im Log-Eintrag"))
    (.delete f)))
```

(ns-Requires ggf. ergänzen: `[bot.random :as random]`, `[clojure.edn]`, `[clojure.string]` — an vorhandene Requires des Test-ns anpassen.)

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `bin/test-focus bot.game-runner-test`
Expected: FAIL (`decide-one!` / public `apply-choice!` existieren nicht)

- [ ] **Step 3: Refactor** — in `src/clj/bot/game_runner.clj`:

`actionable-prompt?` und `apply-choice!` public machen (nur `defn-` → `defn`, Körper unverändert). Dann `step!` durch `decide-one!` ersetzen:

```clojure
(defn decide-one!
  "Eine Bot-Entscheidung: Bot fragen, anwenden (via :apply-fn), loggen.
  No-Ops: Option streichen, Bot erneut fragen. :log-extra wird in jeden
  Log-Eintrag gemergt (z.B. {:difficulty \"random\"}).
  Wirft ex-info \"Keine ausführbare Option übrig\", wenn nichts den State bewegt."
  [{:keys [state side kind bot log-path apply-fn log-extra step-no]
    :or {apply-fn apply-choice!}}]
  (let [v (view/view-for state side)]
    (loop [options (vec (options-for v side kind))]
      (when (empty? options)
        (throw (ex-info "Keine ausführbare Option übrig"
                        {:side side :kind kind :step step-no
                         :prompt (get-in v [side :prompt-state])})))
      (let [decision (if (= kind :prompt)
                       (bp/on-prompt bot v (get-in v [side :prompt-state]) options)
                       (bp/decide bot v options))
            chosen (or (:action decision) (:option decision))
            before (fingerprint state)
            _ (apply-fn state side kind chosen)
            progressed? (not= before (fingerprint state))]
        (when log-path
          (blog/append-decision! log-path
                                 (merge {:turn (:turn @state 0)
                                         :phase (view/phase-of v)
                                         :side side
                                         :kind kind
                                         :options (mapv :label options)
                                         :choice (:label chosen)
                                         :reason (:reason decision)
                                         :no-op (not progressed?)}
                                        log-extra)))
        (when-not progressed?
          (recur (vec (remove #{chosen} options))))))))
```

In `run-game` den `step!`-Aufruf ersetzen durch:

```clojure
(decide-one! {:state state :side side :kind kind :bot bot
              :log-path log-path :step-no steps})
```

`step!` selbst löschen. Achtung: `append-decision!` wurde bisher immer gerufen, auch mit `log-path` nil — `spit` auf nil würde werfen; der `(when log-path ...)`-Guard ist neu und korrekt (Sim übergibt Pfad, Tests oft nicht).

- [ ] **Step 4: Regression + neuer Test grün**

Run: `bin/test-focus bot.game-runner-test`
Expected: PASS (alle bestehenden Volltests + neuer Test)

- [ ] **Step 5: Sim-Smoke** (nutzt `run-game` → `decide-one!`):

Run: `bin/bot-sim 3 --seed 42`
Expected: 3/3 abgeschlossen (wie vor dem Refactor)

- [ ] **Step 6: Commit**

```bash
git add src/clj/bot/game_runner.clj test/clj/bot/game_runner_test.clj
git commit -m "refactor(bot): decide-one! mit pluggable apply-fn aus step! extrahiert"
```

---

### Task 3: `bot.roster` + Deck-Registry in `bot.cards`

**Files:**
- Create: `src/clj/bot/roster.clj`
- Modify: `src/clj/bot/cards.clj`
- Test: `test/clj/bot/roster_test.clj` (neu)

**Interfaces:**
- Consumes: `bot.cards/player-entry`, `bot.random/random-bot`
- Produces:
  - `bot.cards/bot-decks` — Map `{"gateway-corp" gateway-corp, "gateway-runner" gateway-runner}`
  - `bot.cards/bot-deck-for` — `(bot-deck-for side)`, side `"Corp"`/`"Runner"` → Deck-Map
  - `bot.roster/difficulties` — Map Difficulty-String → 0-arity Factory (liefert `bot.protocol/Bot`)
  - `bot.roster/difficulty?` — `(difficulty? d)` → boolean
  - `bot.roster/make-bot` — `(make-bot difficulty)` → Bot-Instanz; wirft ex-info bei unbekanntem Grad
  - `bot.roster/bot-username` — `(bot-username "random")` → `"Bot (Random)"`
  - `bot.roster/bot-player` — `(bot-player side difficulty)` → Player-Map `{:uid nil :bot true :side <side> :user {:username "Bot (Random)"} :deck {...engine-fertig...}}` (braucht geladene all-cards)

**WICHTIG:** `bot.roster` darf KEINE `web.*`-Namespaces requiren — `web.lobby` konsumiert es (sonst Zyklus `web.lobby → bot.roster → web.* → web.lobby`).

- [ ] **Step 1: Failing Test schreiben** — `test/clj/bot/roster_test.clj`:

```clojure
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
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `bin/test-focus bot.roster-test`
Expected: FAIL (ns `bot.roster` fehlt, `bot-deck-for` fehlt)

- [ ] **Step 3: Implementierung**

`src/clj/bot/cards.clj` — nach `gateway-runner` ergänzen:

```clojure
(def bot-decks
  "Registry der in der Lobby wählbaren Bot-Decks."
  {"gateway-corp" gateway-corp
   "gateway-runner" gateway-runner})

(defn bot-deck-for
  "Deck für eine Bot-Seite (\"Corp\"/\"Runner\") — vorerst fest System Gateway."
  [side]
  (get bot-decks (if (= side "Corp") "gateway-corp" "gateway-runner")))
```

`src/clj/bot/roster.clj`:

```clojure
(ns bot.roster
  "Difficulty-Registry + Bot-Player-Konstruktion für Web-Lobbys.
  Bewusst OHNE web.*-Abhängigkeiten: web.lobby konsumiert diesen Namespace,
  die Web-Laufzeit (bot.seat) liegt eine Schicht darüber."
  (:require
   [bot.cards :as cards]
   [bot.random :as random]
   [clojure.string :as str]))

(def difficulties
  "Registry Schwierigkeitsgrad -> Factory (0-arity, liefert bot.protocol/Bot)."
  {"random" #(random/random-bot (.nextLong (java.util.Random.)))})

(defn difficulty? [d]
  (contains? difficulties d))

(defn make-bot [difficulty]
  (if-let [f (get difficulties difficulty)]
    (f)
    (throw (ex-info "Unbekannter Schwierigkeitsgrad" {:difficulty difficulty}))))

(defn bot-username [difficulty]
  (str "Bot (" (str/capitalize difficulty) ")"))

(defn bot-player
  "Player-Map für eine Web-Lobby (braucht geladene all-cards).
  Form kompatibel zu web.lobby-Playern UND game.core.set-up/init-game."
  [side difficulty]
  (-> (cards/player-entry side (cards/bot-deck-for side))
      (assoc :uid nil :bot true)
      (assoc-in [:user :username] (bot-username difficulty))))
```

- [ ] **Step 4: Tests grün**

Run: `bin/test-focus bot.roster-test` und `bin/test-focus bot.cards-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/roster.clj src/clj/bot/cards.clj test/clj/bot/roster_test.clj
git commit -m "feat(bot): bot.roster — Difficulty-Registry und Bot-Player für Web-Lobbys"
```

---

### Task 4: `web.lobby` — Bot-Lobbys erzeugen

**Files:**
- Modify: `src/clj/web/lobby.clj` (`create-new-lobby`, `lobby-keys`)
- Test: `test/clj/web/lobby_test.clj` (erweitern)

**Interfaces:**
- Consumes: `bot.roster/bot-player`, `bot.roster/make-bot`, `bot.roster/difficulty?`
- Produces (Lobby-Map-Felder, von Task 5–8 konsumiert):
  - `:bot-game` — `"vs-bot"` | `"bot-vs-bot"` | nil (nil ⇒ exakt altes Verhalten)
  - `:difficulty` — Difficulty-String (nur wenn `:bot-game`)
  - `:bots` — `{:corp <Bot>|nil :runner <Bot>|nil}` (nur wenn `:bot-game`; NICHT in `lobby-keys` — geht nie an Clients)
  - `:bot-thinking?` — `(atom false)` Guard (nur wenn `:bot-game`; NICHT in `lobby-keys`)
  - `:bot-game` und `:difficulty` werden in `lobby-keys` aufgenommen (Client darf sie sehen)

- [ ] **Step 1: Failing Tests schreiben** — in `test/clj/web/lobby_test.clj` ergänzen:

```clojure
;; ns-Requires ergänzen: [bot.cards :as bot-cards]
(use-fixtures :once (fn [f] (bot-cards/load-all-cards!) (f)))

(defn- mklobby [options]
  (lobby/create-new-lobby {:uid "uid-1" :user {:username "david"} :options options}))

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
```

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `bin/test-focus web.lobby-test`
Expected: FAIL (neue Felder fehlen); bestehende Tests des ns weiterhin PASS

- [ ] **Step 3: Implementierung** — `src/clj/web/lobby.clj`:

ns-Require ergänzen: `[bot.roster :as roster]`.

In `create-new-lobby` die Options-Destrukturierung um `bot-game difficulty` erweitern und den Rumpf umbauen (bestehende Map-Literal bleibt, wird zu `base`):

```clojure
(defn- opposite-side [side]
  (if (= side "Corp") "Runner" "Corp"))

(defn- apply-bot-setup
  "Erweitert eine frisch erzeugte Lobby um Bot-Player. vs-bot: Bot auf der
  Gegenseite des Erstellers (Any Side ⇒ Corp). bot-vs-bot: beide Seiten Bots,
  Ersteller wird Spectator, allow-spectator erzwungen."
  [lobby bot-game difficulty uid user side]
  (case bot-game
    "vs-bot"
    (let [human-side (if (= side "Runner") "Runner" "Corp")
          bot-side (opposite-side human-side)
          human (assoc (first (:players lobby)) :side human-side)]
      (assoc lobby
             :players [human (roster/bot-player bot-side difficulty)]
             :bots {(side-from-str bot-side) (roster/make-bot difficulty)}
             :bot-thinking? (atom false)))
    "bot-vs-bot"
    (assoc lobby
           :players [(roster/bot-player "Corp" difficulty)
                     (roster/bot-player "Runner" difficulty)]
           :spectators [{:uid uid :user user}]
           :allow-spectator true
           :bots {:corp (roster/make-bot difficulty)
                  :runner (roster/make-bot difficulty)}
           :bot-thinking? (atom false))))
```

Am Ende von `create-new-lobby` (nach dem bisherigen Map-Literal, das an eine lokale Bindung `base` geht):

```clojure
(let [difficulty (or difficulty "random")
      bot-game (when (and (contains? #{"vs-bot" "bot-vs-bot"} bot-game)
                          (roster/difficulty? difficulty))
                 bot-game)]
  (if bot-game
    (-> base
        (assoc :bot-game bot-game :difficulty difficulty)
        (apply-bot-setup bot-game difficulty uid user side))
    base))
```

(`side-from-str` ist im ns bereits im Zugriff — wird von `leave-lobby!` benutzt; sonst Require prüfen.)

In `lobby-keys` ergänzen: `:bot-game` und `:difficulty` (NICHT `:bots`, NICHT `:bot-thinking?`).

- [ ] **Step 4: Tests grün**

Run: `bin/test-focus web.lobby-test`
Expected: PASS (alle, inkl. Bestand)

- [ ] **Step 5: Commit**

```bash
git add src/clj/web/lobby.clj test/clj/web/lobby_test.clj
git commit -m "feat(web): Lobby-Erzeugung für vs-bot und bot-vs-bot"
```

---

### Task 5: `web.lobby` — Leave/Close-Semantik + Stats-Skip

**Files:**
- Modify: `src/clj/web/lobby.clj` (`handle-leave-lobby`, `close-lobby!`)
- Test: `test/clj/web/lobby_test.clj` (erweitern)

**Interfaces:**
- Consumes: `:bot-game`-Flag aus Task 4
- Produces:
  - `handle-leave-lobby`: Lobby wird dissoc'd, sobald kein MENSCHLICHER Player mehr da ist (`(remove :bot players)`), nicht erst bei 0 Playern
  - `close-lobby!`: bei `:bot-game` laufen `game-finished` (Replay!) weiter, aber `update-deck-stats`/`update-game-stats`/`push-stats-update` nicht

- [ ] **Step 1: Failing Tests schreiben** — in `test/clj/web/lobby_test.clj` ergänzen:

```clojure
(deftest handle-leave-lobby-schliesst-bot-lobby
  (let [l (mklobby {:bot-game "vs-bot" :side "Corp" :difficulty "random"
                    :format "system-gateway" :title "t" :room "casual"})
        gameid (:gameid l)
        lobbies {gameid l}]
    (with-redefs [app-state/uid->lobby (fn [ls uid] (get ls gameid))]
      (is (= {} (lobby/handle-leave-lobby lobbies "uid-1" {:text "bye"}))
          "Ohne menschlichen Player wird die Lobby entfernt, Bot hält sie nicht offen"))))

(deftest handle-leave-lobby-normal-unveraendert
  ;; Regression: normale 2-Spieler-Lobby bleibt bestehen, wenn einer geht
  (let [l {:gameid "g1"
           :players [{:uid "u1" :user {:username "a"}}
                     {:uid "u2" :user {:username "b"}}]
           :spectators [] :corp-spectators [] :runner-spectators []
           :messages []}
        lobbies {"g1" l}]
    (with-redefs [app-state/uid->lobby (fn [ls uid] (get ls "g1"))]
      (let [result (lobby/handle-leave-lobby lobbies "u1" {:text "bye"})]
        (is (= ["u2"] (mapv :uid (get-in result ["g1" :players]))))))))

(deftest close-lobby-stats-skip-fuer-bot-spiele
  (let [calls (atom #{})
        record (fn [k] (fn [& _] (swap! calls conj k)))]
    (with-redefs [web.stats/game-finished (record :game-finished)
                  web.stats/update-deck-stats (record :update-deck-stats)
                  web.stats/update-game-stats (record :update-game-stats)
                  web.stats/push-stats-update (record :push-stats-update)]
      (reset! calls #{})
      (lobby/close-lobby! nil {:gameid "g-bot" :started true :bot-game "vs-bot"
                               :players [] :spectators []})
      (is (= #{:game-finished} @calls)
          "Bot-Spiel: nur game-finished (Replay), keine User-/Deck-Stats")
      (reset! calls #{})
      (lobby/close-lobby! nil {:gameid "g-normal" :started true
                               :players [] :spectators []})
      (is (= #{:game-finished :update-deck-stats :update-game-stats :push-stats-update} @calls)
          "Normales Spiel: alle Stats wie bisher"))))
```

(ns-Requires ergänzen: `[web.stats]`. `close-lobby!` ruft außerdem `clear-lobby-state`/`leave-pool!` — mit leeren Player-/Spectator-Listen und `:pool` nil sind die no-ops; falls `leave-pool!` auf nil wirft, im Test zusätzlich `lobby/leave-pool!` auf `(fn [& _])` redefen.)

Zusätzlich (Spec-Testliste: Replay-Record nach Bot-Partie) — prüft, dass der
bestehende `stats/game-finished`-Pfad mit einem Bot-Spiel-State ein `:replay`
schreibt (Mongo weggeredeft):

```clojure
(deftest game-finished-schreibt-replay-fuer-bot-spiel
  (let [written (atom nil)
        state (atom {:winner :corp :reason "Agenda" :turn 7
                     :options {:save-replay true}
                     :history [{:diff 1} {:diff 2}]
                     :corp {:user {:username "david"}}
                     :runner {:user {:username "Bot (Random)"}}})]
    (with-redefs [monger.collection/update (fn [_db _coll _q update-doc]
                                             (reset! written update-doc))
                  web.stats/delete-old-replay (fn [& _] nil)]
      (web.stats/game-finished nil {:gameid "g-bot" :state state :bot-game "vs-bot"})
      (is (string? (get-in @written ["$set" :replay]))
          "Replay-JSON wird auch für Bot-Spiele geschrieben"))))
```

(Der `$set`-Schlüssel stammt aus `monger.operators` — beim Schreiben des Tests
prüfen, ob er im Update-Doc als `"$set"` oder `:$set` ankommt, und die Assertion
entsprechend anpassen; das ist ein Capture-Detail, kein Verhaltensunterschied.)

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `bin/test-focus web.lobby-test`
Expected: FAIL (Bot-Lobby bleibt bestehen; Stats werden voll gerufen). Der Replay-Test ist ein Charakterisierungstest des Bestands und darf sofort PASS sein.

- [ ] **Step 3: Implementierung** — `src/clj/web/lobby.clj`:

In `handle-leave-lobby` die Zeile

```clojure
      (if (pos? (count players))
```

ersetzen durch

```clojure
      (if (pos? (count (remove :bot players)))
```

In `close-lobby!` den `when started`-Block ändern von

```clojure
   (when started
     (stats/game-finished db lobby)
     (stats/update-deck-stats db lobby)
     (stats/update-game-stats db lobby)
     (stats/push-stats-update db lobby))
```

zu

```clojure
   (when started
     (stats/game-finished db lobby)
     ;; Bot-User haben kein :_id — User-/Deck-Stats würden mit nil-Id schreiben.
     ;; game-finished bleibt: es trägt die Replay-Speicherung.
     (when-not (:bot-game lobby)
       (stats/update-deck-stats db lobby)
       (stats/update-game-stats db lobby)
       (stats/push-stats-update db lobby)))
```

- [ ] **Step 4: Tests grün**

Run: `bin/test-focus web.lobby-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/clj/web/lobby.clj test/clj/web/lobby_test.clj
git commit -m "feat(web): Bot-Lobbys schließen ohne Menschen; User-Stats-Skip, Replay bleibt"
```

---

### Task 6: `web.game` — Hook-Punkte + `start-game!`

**Files:**
- Modify: `src/clj/web/game.clj`
- Test: `test/clj/web/game_test.clj` (neu)

**Interfaces:**
- Consumes: nichts Neues (nur Refactor + Hook)
- Produces (von `bot.seat` in Task 7 konsumiert):
  - `web.game/bot-notify-fn` — `(defonce bot-notify-fn (atom (fn [_gameid] nil)))`
  - `web.game/notify-bots!` — `(notify-bots! gameid)` ruft `@bot-notify-fn`; Rückgabe nil
  - `web.game/start-game!` — `(start-game! db gameid)`: startet die Partie OHNE first-player-Check (Rumpf des bisherigen `try-start-game`); `try-start-game` behält Signatur `[db uid gameid]` und delegiert nach dem Check
  - Hook-Aufrufe: am Ende von `start-game!` (nach `send-state-to-participants`), nach `update-and-send-diffs!` im `:game/action`-Handler, nach `update-and-send-diffs!` im `:game/rejoin`-Handler

- [ ] **Step 1: Failing Test schreiben** — `test/clj/web/game_test.clj`:

```clojure
(ns web.game-test
  (:require
   [bot.cards :as bot-cards]
   [clojure.test :refer :all]
   [web.app-state :as app-state]
   [web.game :as game]
   [web.lobby :as lobby]
   [web.stats :as stats]
   [web.ws :as ws]))

(use-fixtures :once (fn [f] (bot-cards/load-all-cards!) (f)))

(use-fixtures :each (fn [f]
                      (reset! app-state/app-state {:lobbies {} :users {}})
                      (reset! game/bot-notify-fn (fn [_] nil))
                      (f)))

(defn- stub-io [f]
  (with-redefs [ws/chsk-send! (fn [& _] nil)
                stats/game-started (fn [& _] nil)
                stats/fetch-replay-record (fn [& _] nil)
                lobby/send-lobby-state (fn [& _] nil)
                lobby/broadcast-lobby-list (fn [& _] nil)]
    (f)))

(defn- make-started-vs-bot-lobby!
  "Erzeugt eine vs-bot-Lobby (Mensch = Corp), setzt das Menschen-Deck und
  startet sie über start-game!. Liefert die gameid."
  []
  (let [l (lobby/create-new-lobby
           {:uid "u1" :user {:username "david"}
            :options {:bot-game "vs-bot" :side "Corp" :difficulty "random"
                      :format "system-gateway" :title "t" :room "casual"}})
        gameid (:gameid l)
        human-deck (:deck (bot-cards/player-entry "Corp" bot-cards/gateway-corp))]
    (swap! app-state/app-state assoc-in [:lobbies gameid]
           (assoc-in l [:players 0 :deck] human-deck))
    (stub-io #(game/start-game! nil gameid))
    gameid))

(deftest start-game!-startet-ohne-first-player-check
  (let [gameid (make-started-vs-bot-lobby!)
        lobby? (app-state/get-lobby gameid)]
    (is (:started lobby?))
    (is (some? (:state lobby?)))
    (is (some? @(:state lobby?)))))

(deftest start-game!-ruft-bot-hook
  (let [notified (atom [])]
    (reset! game/bot-notify-fn (fn [gameid] (swap! notified conj gameid)))
    (let [gameid (make-started-vs-bot-lobby!)]
      (is (= [gameid] @notified) "Hook genau einmal nach dem Start"))))

(deftest notify-bots!-default-no-op
  (is (nil? (game/notify-bots! "irgendeine-id"))))
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `bin/test-focus web.game-test`
Expected: FAIL (`start-game!`, `bot-notify-fn`, `notify-bots!` existieren nicht)

- [ ] **Step 3: Implementierung** — `src/clj/web/game.clj`:

Hook-Atom + Helper (oberhalb von `try-start-game`):

```clojure
(defonce bot-notify-fn
  ;; Hook: bot.seat/register! ersetzt den No-Op beim Systemstart.
  ;; Indirektion statt Require, weil bot.seat selbst web.game braucht.
  (atom (fn [_gameid] nil)))

(defn notify-bots! [gameid]
  (@bot-notify-fn gameid)
  nil)
```

`try-start-game` aufspalten:

```clojure
(defn start-game!
  "Startet eine Lobby ohne first-player-Check (Bot-vs-Bot hat keinen
  menschlichen first-player). Menschliche Starts laufen über try-start-game."
  [db gameid]
  (let [{:keys [players started] :as lobby} (app-state/get-lobby gameid)]
    (when (and lobby (not started))
      (let [now (inst/now)
            replay-record (stats/fetch-replay-record db (:replay-id lobby))
            replay-timestamp (:replay-timestamp lobby)
            new-app-state
            (swap! app-state/app-state
                   update :lobbies handle-start-game gameid players now replay-record replay-timestamp)
            lobby? (get-in new-app-state [:lobbies gameid])]
        (when lobby?
          (stats/game-started db lobby?)
          (lobby/send-lobby-state lobby?)
          (lobby/broadcast-lobby-list)
          (send-state-to-participants :game/start lobby? (diffs/public-states (:state lobby?)))
          (notify-bots! gameid))))))

(defn try-start-game
  [db uid gameid]
  (let [{:keys [started] :as lobby} (app-state/get-lobby gameid)]
    (when (and lobby (lobby/first-player? uid lobby) (not started))
      (start-game! db gameid))))
```

Im `:game/action`-Handler nach der Zeile `(update-and-send-diffs! main/handle-action lobby side command args)` ergänzen:

```clojure
               (notify-bots! gameid)
```

Im `:game/rejoin`-Handler nach `(update-and-send-diffs! main/handle-rejoin lobby? user)` ergänzen:

```clojure
           (notify-bots! (:gameid lobby?))
```

- [ ] **Step 4: Tests grün**

Run: `bin/test-focus web.game-test` und Regression `bin/test-focus web.lobby-test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/clj/web/game.clj test/clj/web/game_test.clj
git commit -m "feat(web): Bot-Hook (notify-bots!) und start-game! ohne first-player-Check"
```

---

### Task 7: `bot.seat` — notify!-Loop, Fehlerpfad, Registrierung

**Files:**
- Create: `src/clj/bot/seat.clj`
- Modify: `src/clj/web/system.clj`, `resources/dev.edn`
- Test: `test/clj/bot/seat_test.clj` (neu)

**Interfaces:**
- Consumes: `bot.game-runner/{next-actor,actionable-prompt?,apply-choice!,decide-one!}` (Task 2), `bot.log/append-event!` (Task 1), `web.game/{update-and-send-diffs!,start-game!,bot-notify-fn}` (Task 6), `web.lobby/{game-thread,bot-start-fn}` (`bot-start-fn` kommt in Task 8 — hier nur `web.game`-Hook registrieren), Lobby-Felder `:bots`/`:bot-thinking?`/`:difficulty` (Task 4)
- Produces:
  - `bot.seat/*think-ms*` — dynamische Var `[min-ms max-ms]`, Default `[1000 2000]`; Tests binden `[0 0]`
  - `bot.seat/log-path` — `(log-path gameid)` → `"logs/bot-games/<gameid>.edn"`
  - `bot.seat/notify!` — `(notify! gameid)`; startet höchstens einen Bot-Loop pro Partie
  - `bot.seat/register!` — setzt `web.game/bot-notify-fn` (Task 8 erweitert um `web.lobby/bot-start-fn`)

- [ ] **Step 1: Failing Tests schreiben** — `test/clj/bot/seat_test.clj`:

```clojure
(ns bot.seat-test
  (:require
   [bot.cards :as bot-cards]
   [bot.game-runner :as runner]
   [bot.seat :as seat]
   [clojure.test :refer :all]
   [web.app-state :as app-state]
   [web.game :as game]
   [web.lobby :as lobby]
   [web.stats :as stats]
   [web.ws :as ws]))

(use-fixtures :once (fn [f] (bot-cards/load-all-cards!) (f)))
(use-fixtures :each (fn [f]
                      (reset! app-state/app-state {:lobbies {} :users {}})
                      (seat/register!)
                      (f)
                      (reset! game/bot-notify-fn (fn [_] nil))))

(defmacro with-stub-io [& body]
  `(with-redefs [ws/chsk-send! (fn [& _#] nil)
                 stats/game-started (fn [& _#] nil)
                 stats/fetch-replay-record (fn [& _#] nil)
                 lobby/send-lobby-state (fn [& _#] nil)
                 lobby/broadcast-lobby-list (fn [& _#] nil)]
     ~@body))

(defn- wait-until
  "Pollt bis pred truthy oder timeout-ms um; liefert (pred) oder nil."
  [pred timeout-ms]
  (loop [waited 0]
    (or (pred)
        (when (< waited timeout-ms)
          (Thread/sleep 50)
          (recur (+ waited 50))))))

(defn- start-vs-bot!
  "vs-bot-Lobby (Mensch = Corp) erzeugen + starten; liefert gameid."
  []
  (let [l (lobby/create-new-lobby
           {:uid "u1" :user {:username "david"}
            :options {:bot-game "vs-bot" :side "Corp" :difficulty "random"
                      :format "system-gateway" :title "t" :room "casual"}})
        gameid (:gameid l)
        human-deck (:deck (bot-cards/player-entry "Corp" bot-cards/gateway-corp))]
    (swap! app-state/app-state assoc-in [:lobbies gameid]
           (assoc-in l [:players 0 :deck] human-deck))
    (game/start-game! nil gameid)
    gameid))

(deftest vs-bot-bot-beantwortet-eigenen-mulligan
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [gameid (start-vs-bot!)
            state (:state (app-state/get-lobby gameid))]
        ;; start-game! hat notify! gerufen (register!-Hook). Der Bot (Runner)
        ;; beantwortet seinen Keep/Mulligan-Prompt, obwohl der Corp-Prompt
        ;; (Mensch) in next-actor Vorrang hätte.
        (is (wait-until #(not (runner/actionable-prompt? (:state (app-state/get-lobby gameid)) :runner))
                        10000)
            "Runner-Bot hat seinen Start-Prompt beantwortet")
        (is (runner/actionable-prompt? state :corp)
            "Corp-Prompt (Mensch) bleibt offen — Bot wartet auf den Menschen")
        (is (nil? (:winner @state)))))))

(deftest vs-bot-decision-log-mit-difficulty
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [gameid (start-vs-bot!)
            path (seat/log-path gameid)]
        (wait-until #(.exists (clojure.java.io/file path)) 10000)
        (let [entry (clojure.edn/read-string
                     (first (clojure.string/split-lines (slurp path))))]
          (is (= "random" (:difficulty entry)))
          (is (string? (:reason entry)))
          (is (= :runner (:side entry))))))))

(deftest bot-fehler-fuehrt-zu-concede
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [gameid (start-vs-bot!)
            state (:state (app-state/get-lobby gameid))]
        (with-redefs [runner/decide-one!
                      (fn [& _] (throw (ex-info "Keine ausführbare Option übrig" {})))]
          (seat/notify! gameid)
          (is (wait-until #(:winner @state) 10000)
              "Bot concedet nach Fehler — Partie endet")
          (is (= "Concede" (:reason @state))))))))
```

(ns-Requires im Test ergänzen: `[clojure.edn]`, `[clojure.java.io]`, `[clojure.string]`.)

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `bin/test-focus bot.seat-test`
Expected: FAIL (ns `bot.seat` fehlt)

- [ ] **Step 3: Implementierung** — `src/clj/bot/seat.clj`:

```clojure
(ns bot.seat
  "Web-Seat: lässt Bots in Web-Lobbys auf Engine-Zustandsänderungen reagieren.
  Registriert sich beim Systemstart als Hook in web.game (Indirektion über
  Atom, weil dieser Namespace selbst web.game braucht — kein Require-Zyklus).
  Bedenkzeit schläft auf einem eigenen Thread; die State-Mutation läuft
  seriell auf dem Game-Thread der Lobby (wie menschliche Aktionen)."
  (:require
   [bot.game-runner :as runner]
   [bot.log :as blog]
   [cljc.java-time.instant :as inst]
   [game.main :as main]
   [taoensso.timbre :as timbre]
   [web.app-state :as app-state]
   [web.game :as game]
   [web.lobby :as lobby]))

(def ^:dynamic *think-ms*
  "[min max] künstliche Bedenkzeit pro Entscheidung in ms; Tests binden [0 0]."
  [1000 2000])

(defn log-path [gameid]
  (str "logs/bot-games/" gameid ".edn"))

(defn- think! []
  (let [[lo hi] *think-ms*
        ms (+ lo (rand-int (inc (max 0 (- hi lo)))))]
    (when (pos? ms)
      (Thread/sleep (long ms)))))

(defn- bot-lobby [gameid]
  (let [lobby (app-state/get-lobby gameid)]
    (when (:bot-game lobby)
      lobby)))

(defn- bot-to-act
  "[side kind bot] wenn eine Bot-Seite handeln muss, sonst nil.
  Prüft zuerst offene Prompts der Bot-Seiten direkt: beim Spielstart haben
  BEIDE Seiten den Keep/Mulligan-Prompt, und next-actor würde den Prompt des
  Menschen priorisieren — der Bot soll seinen trotzdem sofort beantworten."
  [{:keys [state bots]}]
  (when (and state (not (:winner @state)))
    (or (some (fn [side]
                (when-let [bot (get bots side)]
                  (when (runner/actionable-prompt? state side)
                    [side :prompt bot])))
              [:corp :runner])
        (when-let [[side kind] (runner/next-actor state)]
          (when-let [bot (get bots side)]
            [side kind bot])))))

(defn- concede-bot! [lobby side e]
  (blog/append-event! (log-path (:gameid lobby))
                      {:event :concede
                       :side side
                       :error (ex-message e)
                       :data (ex-data e)})
  (timbre/warn e (str "Bot concedet nach Fehler in " (:gameid lobby) " (" side ")"))
  (game/update-and-send-diffs! main/handle-concede lobby side))

(defn- bot-step!
  "Eine Bot-Entscheidung (läuft auf dem Game-Thread der Lobby).
  Liefert true, wenn der Loop weiterlaufen soll."
  [gameid]
  (let [{:keys [state difficulty] :as lobby} (bot-lobby gameid)]
    (if-let [[side kind bot] (bot-to-act lobby)]
      (try
        (runner/decide-one!
         {:state state :side side :kind kind :bot bot
          :log-path (log-path gameid)
          :log-extra {:difficulty difficulty}
          ;; Anwendung über den Web-Pfad: Diffs an Clients, History fürs Replay
          :apply-fn (fn [_state side' kind' chosen]
                      (game/update-and-send-diffs!
                       runner/apply-choice! lobby side' kind' chosen))})
        ;; Bot-Aktivität zählt als Aktivität (sonst räumt clear-inactive-lobbies
        ;; laufende Bot-Partien ab)
        (swap! app-state/app-state assoc-in [:lobbies gameid :last-update] (inst/now))
        true
        (catch Exception e
          (concede-bot! lobby side e)
          false))
      false)))

(defn- run-loop! [gameid]
  (loop []
    (when-let [lobby (bot-lobby gameid)]
      (when (bot-to-act lobby)
        (think!)
        ;; Mutation seriell zum Menschen auf dem Game-Thread der Lobby
        (when @(lobby/game-thread lobby (bot-step! gameid))
          (recur))))))

(defn notify!
  "Hook: nach jeder State-Änderung aufgerufen (web.game). Startet höchstens
  einen Bot-Loop pro Partie (Guard :bot-thinking?, gesetzt bei Lobby-Erzeugung)."
  [gameid]
  (when-let [{:keys [bot-thinking?]} (bot-lobby gameid)]
    (when (and bot-thinking?
               (compare-and-set! bot-thinking? false true))
      (future
        (try
          (run-loop! gameid)
          (catch Exception e
            (timbre/error e (str "Bot-Loop-Fehler in " gameid)))
          (finally
            (reset! bot-thinking? false)
            ;; Race: Mensch hat gehandelt, während der Guard noch true war —
            ;; dessen notify! lief ins Leere. Einmal nachprüfen.
            (when (some-> (bot-lobby gameid) bot-to-act)
              (notify! gameid)))))))
  nil)

(defn register!
  "Beim Systemstart aufrufen (web.system, ig/init-key :bot/seat)."
  []
  (reset! game/bot-notify-fn notify!)
  :registered)
```

`src/clj/web/system.clj` — Require `[bot.seat]` ergänzen und neben den anderen `ig/init-key`-Methoden:

```clojure
(defmethod ig/init-key :bot/seat [_ _opts]
  (bot.seat/register!))
```

`resources/dev.edn` — Key ergänzen (neben `:game/quotes nil`):

```clojure
 :bot/seat nil
```

- [ ] **Step 4: Tests grün**

Run: `bin/test-focus bot.seat-test`
Expected: PASS (3 Tests). Danach Regression: `bin/test-focus bot.game-runner-test` und `bin/test-focus web.game-test`.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/seat.clj src/clj/web/system.clj resources/dev.edn test/clj/bot/seat_test.clj
git commit -m "feat(bot): bot.seat — Bot-Seat reagiert auf Engine-Events mit Bedenkzeit"
```

---

### Task 8: Bot-vs-Bot — Auto-Start + kompletter Durchlauf

**Files:**
- Modify: `src/clj/web/lobby.clj` (`try-create-lobby` + Hook-Atom), `src/clj/bot/seat.clj` (`register!` erweitern)
- Test: `test/clj/bot/seat_test.clj` (erweitern)

**Interfaces:**
- Consumes: `web.game/start-game!` (Task 6), Bot-Lobby aus Task 4
- Produces:
  - `web.lobby/bot-start-fn` — `(defonce bot-start-fn (atom (fn [_db _gameid] nil)))`; `try-create-lobby` ruft es für `bot-vs-bot`-Lobbys nach der Registrierung
  - `bot.seat/start-bot-vs-bot!` — `(start-bot-vs-bot! db gameid)` = `(game/start-game! db gameid)` (start-game! ruft am Ende selbst `notify-bots!`)
  - `bot.seat/register!` setzt zusätzlich `web.lobby/bot-start-fn`

- [ ] **Step 1: Failing Tests schreiben** — in `test/clj/bot/seat_test.clj` ergänzen:

```clojure
(deftest bot-vs-bot-partie-laeuft-komplett-durch
  (binding [seat/*think-ms* [0 0]]
    (with-stub-io
      (let [l (lobby/create-new-lobby
               {:uid "u1" :user {:username "david"}
                :options {:bot-game "bot-vs-bot" :difficulty "random"
                          :format "system-gateway" :title "t" :room "casual"}})
            gameid (:gameid l)]
        (swap! app-state/app-state assoc-in [:lobbies gameid] l)
        (game/start-game! nil gameid)
        (let [state (:state (app-state/get-lobby gameid))]
          (is (wait-until #(:winner @state) 120000)
              "Partie Random vs. Random endet mit Sieger")
          (is (pos? (:turn @state 0))))))))

(deftest try-create-lobby-startet-bot-vs-bot-automatisch
  (let [started (atom nil)]
    (with-redefs [lobby/bot-start-fn (atom (fn [_db gameid] (reset! started gameid)))
                  lobby/auto-select-decks (fn [_db l] l)
                  lobby/send-lobby-state (fn [& _] nil)
                  lobby/broadcast-lobby-list (fn [& _] nil)]
      (lobby/try-create-lobby
       nil "u1" {:username "david"}
       {:bot-game "bot-vs-bot" :difficulty "random"
        :format "system-gateway" :title "t" :room "casual"})
      (is (some? @started) "bot-start-fn wurde mit der gameid gerufen"))))

(deftest try-create-lobby-startet-vs-bot-nicht
  (let [started (atom nil)]
    (with-redefs [lobby/bot-start-fn (atom (fn [_db gameid] (reset! started gameid)))
                  lobby/auto-select-decks (fn [_db l] l)
                  lobby/send-lobby-state (fn [& _] nil)
                  lobby/broadcast-lobby-list (fn [& _] nil)]
      (lobby/try-create-lobby
       nil "u1" {:username "david"}
       {:bot-game "vs-bot" :side "Corp" :difficulty "random"
        :format "system-gateway" :title "t" :room "casual"})
      (is (nil? @started) "vs-bot startet über den normalen Start-Button"))))
```

Hinweis: Falls `lobby/try-create-lobby` über `assign-tournament-properties` (defmulti auf identity) für Nicht-Turnier-Lobbys wirft, weil keine `:default`-Methode existiert, im Test zusätzlich `lobby/assign-tournament-properties` redefen — zuerst aber nachsehen, ob eine `:default`-Methode existiert (`grep -rn "assign-tournament-properties" src/`).

- [ ] **Step 2: Tests laufen lassen — müssen fehlschlagen**

Run: `bin/test-focus bot.seat-test`
Expected: FAIL (`lobby/bot-start-fn` existiert nicht)

- [ ] **Step 3: Implementierung**

`src/clj/web/lobby.clj` — Hook-Atom (z.B. oberhalb von `try-create-lobby`):

```clojure
(defonce bot-start-fn
  ;; Hook: bot.seat/register! ersetzt den No-Op beim Systemstart.
  ;; Indirektion statt Require, weil web.game (das den Start ausführt)
  ;; selbst web.lobby braucht.
  (atom (fn [_db _gameid] nil)))
```

`try-create-lobby` — am Ende des `(when lobby? ...)`-Blocks ergänzen:

```clojure
      (when (= "bot-vs-bot" (:bot-game lobby?))
        (@bot-start-fn db (:gameid lobby?)))
```

`src/clj/bot/seat.clj` — ergänzen:

```clojure
(defn start-bot-vs-bot!
  "Startet eine frisch erzeugte bot-vs-bot-Lobby (kein menschlicher
  first-player vorhanden). start-game! ruft am Ende notify-bots!."
  [db gameid]
  (game/start-game! db gameid))
```

und `register!` erweitern:

```clojure
(defn register! []
  (reset! game/bot-notify-fn notify!)
  (reset! lobby/bot-start-fn start-bot-vs-bot!)
  :registered)
```

- [ ] **Step 4: Tests grün**

Run: `bin/test-focus bot.seat-test`
Expected: PASS (alle, inkl. Volldurchlauf — dauert wegen kompletter Partie einige Sekunden)

- [ ] **Step 5: Commit**

```bash
git add src/clj/web/lobby.clj src/clj/bot/seat.clj test/clj/bot/seat_test.clj
git commit -m "feat(bot): Bot-vs-Bot startet automatisch beim Erstellen"
```

---

### Task 9: Frontend — Spieltyp im Create-Formular + i18n

**Files:**
- Modify: `src/cljs/nr/new_game.cljs`
- Modify: `resources/public/i18n/en.ftl`, `resources/public/i18n/de.ftl`

**Interfaces:**
- Consumes: Server akzeptiert `:bot-game`/`:difficulty` in `:lobby/create` ?data (Task 4). `:bot-deck` wird NICHT gesendet — der Server wählt das Deck automatisch nach Seite (Registry hat pro Seite genau ein Deck); das Dropdown ist reine Anzeige.
- Produces: Create-Formular mit Spieltyp-Radio (Gegen Spieler / vs. Bot / Bot vs. Bot); bei vs-bot Seitenwahl nur Corp/Runner + Format-Zwang `system-gateway`; bei bot-vs-bot keine Seitenwahl. Keine cljs-Unit-Tests (kein Test-Setup für cljs im Repo) — Absicherung über `npm run cljs:build` + manueller Smoke in Task 10.

- [ ] **Step 1: `new-game-keys` erweitern** — in `src/cljs/nr/new_game.cljs`:

```clojure
(def new-game-keys
  [:allow-spectator
   :api-access
   :bot-game
   :description
   :difficulty
   :format
   ...])  ; Rest unverändert, alphabetisch einsortieren
```

- [ ] **Step 2: State-Defaults** — im `r/with-let` von `create-new-game` das `state`-Atom ergänzen:

```clojure
               state (r/atom {:flash-message ""
                              :format (or (get-in @app-state [:options :default-format]) "standard")
                              :room (:room @lobby-state)
                              :side "Any Side"
                              :bot-game nil
                              :difficulty "random"
                              ...})  ; Rest unverändert
```

und eine Cursor-Bindung ergänzen: `bot-game (r/cursor state [:bot-game])`.

- [ ] **Step 3: Spieltyp-Sektion** — neue Komponente (oberhalb von `side-section`):

```clojure
(defn game-type-section [state]
  [:section
   [tr-element :h3 [:lobby_game-type "Game type"]]
   (doall
     (for [[value tr-key label] [[nil :lobby_vs-player "Versus player"]
                                 ["vs-bot" :lobby_vs-bot "Versus bot"]
                                 ["bot-vs-bot" :lobby_bot-vs-bot "Bot vs. bot"]]]
       ^{:key (or value "human")}
       [:p
        [:label
         [:input
          {:type "radio"
           :name "bot-game"
           :checked (= (:bot-game @state) value)
           :on-change #(do (swap! state assoc :bot-game value)
                           (when value
                             ;; Bot-Decks sind System-Gateway-Starterdecks
                             (swap! state assoc :format "system-gateway")
                             (when (= "Any Side" (:side @state))
                               (swap! state assoc :side "Corp"))))}]
         (tr [tr-key label])]]))
   (when (:bot-game @state)
     [:div
      [:p
       [:label (tr [:lobby_bot-deck "Bot deck"]) " "
        [:select {:value "gateway" :disabled true}
         [:option {:value "gateway"} "System Gateway Starter"]]]]
      [:p
       [:label (tr [:lobby_bot-difficulty "Bot difficulty"]) " "
        [:select {:value (or (:difficulty @state) "random")
                  :on-change #(swap! state assoc :difficulty (.. % -target -value))}
         [:option {:value "random"} (tr [:lobby_bot-difficulty-random "Random"])]]]]])])
```

- [ ] **Step 4: `side-section` einschränken** — Signatur erweitern, Optionen abhängig vom Spieltyp:

```clojure
(defn side-section [side-state sides]
  [:section
   [tr-element :h3 [:lobby_side "Side"]]
   (doall
     (for [option sides]
       ^{:key option}
       [:p
        [:label [:input
                 {:type "radio"
                  :name "side"
                  :value option
                  :on-change #(reset! side-state (.. % -target -value))
                  :checked (= @side-state option)}]
         (tr-side option)]]))])
```

Im Render-Body von `create-new-game` den `:div.content`-Block anpassen:

```clojure
       [:div.content
        [title-section title]
        [game-type-section state]
        (when-not (= "bot-vs-bot" (:bot-game @state))
          [side-section side (if (= "vs-bot" (:bot-game @state))
                               ["Corp" "Runner"]
                               ["Any Side" "Corp" "Runner"])])
        [format-section fmt options gateway-type precon]
        [description-section description]
        [options-section options user]]
```

- [ ] **Step 5: i18n** — in `resources/public/i18n/en.ftl` (alphabetisch bei den anderen `lobby_`-Keys einsortieren):

```ftl
lobby_bot-deck = Bot deck
lobby_bot-difficulty = Bot difficulty
lobby_bot-difficulty-random = Random
lobby_bot-vs-bot = Bot vs. bot
lobby_game-type = Game type
lobby_vs-bot = Versus bot
lobby_vs-player = Versus player
```

in `resources/public/i18n/de.ftl`:

```ftl
lobby_bot-deck = Bot-Deck
lobby_bot-difficulty = Bot-Schwierigkeit
lobby_bot-difficulty-random = Zufällig
lobby_bot-vs-bot = Bot gegen Bot
lobby_game-type = Spieltyp
lobby_vs-bot = Gegen Bot
lobby_vs-player = Gegen Spieler
```

- [ ] **Step 6: Build + Übersetzungs-Konsistenz**

Run: `npm run cljs:build`
Expected: Build ohne Fehler/Warnungen zu `nr.new-game`

Run: `docker exec netrunner-server-1 lein missing-translations`
Expected: keine fehlenden Keys für die neuen `lobby_`-Einträge

- [ ] **Step 7: Commit**

```bash
git add src/cljs/nr/new_game.cljs resources/public/i18n/en.ftl resources/public/i18n/de.ftl
git commit -m "feat(ui): Spieltyp-Auswahl (vs. Bot, Bot vs. Bot) im Lobby-Formular"
```

---

### Task 10: Endabnahme — Testsuite, Build, manueller Smoke

**Files:**
- Keine neuen; ggf. Fixes aus Befunden

**Interfaces:**
- Consumes: alles aus Task 1–9

- [ ] **Step 1: Alle betroffenen Test-Namespaces**

Run:
```bash
bin/test-focus bot.log-test
bin/test-focus bot.game-runner-test
bin/test-focus bot.roster-test
bin/test-focus bot.cards-test
bin/test-focus bot.seat-test
bin/test-focus web.lobby-test
bin/test-focus web.game-test
```
Expected: alle PASS

- [ ] **Step 2: Sim-Regression** (Headless-Pfad unverändert funktionsfähig)

Run: `bin/bot-sim 5 --seed 42`
Expected: 5/5 abgeschlossen

- [ ] **Step 3: ClojureScript-Build**

Run: `npm run cljs:build`
Expected: fehlerfrei

- [ ] **Step 4: Manueller Smoke im Docker** (Server läuft: `docker start netrunner-server-1`, http://localhost:1042)

1. Lobby → New Game → Spieltyp „Versus bot", Seite Corp, Titel, Create.
2. Eigenes System-Gateway-Deck wählen → Start.
3. Prüfen: Bot beantwortet Mulligan nach ~1–2 s; Bot zieht/agiert in seinem Zug sichtbar; KEINE Bot-Begründungen im Spiel-Log.
4. `docker exec netrunner-server-1 cat "logs/bot-games/<gameid>.edn"` — Entries mit `:reason` und `:difficulty "random"`.
5. Neues Spiel „Bot vs. bot" erstellen → landet direkt als Spectator im laufenden Spiel; Partie läuft sichtbar durch.
6. vs-bot-Partie mit „Save replay" spielen (oder per Bot-Concede beenden), danach in der eigenen Spielhistorie prüfen: Eintrag mit Replay vorhanden.
7. Normales Multiplayer-Spiel (zwei Browser-Sessions) erstellen und starten — Regression: verhält sich wie vorher.

- [ ] **Step 5: Abschluss-Commit (nur falls Smoke Fixes erzeugt hat), sonst Branch fertig melden**

Danach: superpowers:finishing-a-development-branch (Merge/PR-Entscheidung beim User).
