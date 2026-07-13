# Headless Game Runner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein headless Game Runner unter `src/clj/bot/`, der eine komplette Partie zwischen zwei programmatischen Bots spielt — erster Bot: uniform zufällig — mit EDN-Decision-Log pro Entscheidung.

**Architecture:** Der Runner nutzt `game.core.set-up/init-game` + `game.core/process-action` (exakt der Server-Pfad, ohne Websocket/Mongo) als einzige Regelinstanz. Bots implementieren ein Protokoll mit `decide` (Aktionsphase) und `on-prompt` (Engine-Prompts) und sehen ausschließlich die zensierte Sicht aus `game.core.diffs/state-summary` — dieselbe Information wie ein echter Client. Ein Prioritäts-Resolver bestimmt pro Schritt, welche Seite handeln muss (Prompt → Run/Encounter → Phase 1.2 → Zugstart → Aktionsphase).

**Tech Stack:** Clojure, bestehende Engine (`game.core.*`), Kaocha für Tests (`lein kaocha --focus <ns>` bzw. `bin/test-focus <ns>`).

## Global Constraints

- **Kein Code unter `src/clj/game/core/` oder `src/clj/game/cards/` ändern** (Projektregel: Regelkern nur nach Absprache). Alles Neue liegt unter `src/clj/bot/` und `test/clj/bot/`.
- **Informations-Hygiene:** Bots bekommen NUR Views aus `game.core.diffs` (`strip-state` + `state-summary`). Der rohe `@state` darf ausschließlich vom Runner-Harness für Mechanik benutzt werden (Prompt-`:eid` fürs Beantworten, Prioritäts-Resolver, Progress-Fingerprint) — nie als Bot-Input.
- **Logging-Pflicht:** Jede Bot-Entscheidung wird als eine EDN-Zeile geloggt: `{:turn :phase :side :kind :options :choice :reason :no-op}`. Optionen/Wahl als Labels (Strings), nie rohe Karten-Maps.
- **Engine bleibt Regelinstanz:** Der Bot bietet Aktionen nur an; illegale Aktionen weist `process-action` ab (No-Op). Der Runner erkennt No-Ops per State-Fingerprint und lässt den Bot ohne die tote Option neu wählen.
- Tests: `lein kaocha --focus <ns>` (bzw. `bin/test-focus <ns>` in Docker). Vor dem letzten Commit alle `bot.*`-Test-Namespaces laufen lassen.
- Quellpfade: `src/clj` ist in `:source-paths` (project.clj:8), `test/clj` in `:test-paths`; Kaocha findet Namespaces auf `-test$` automatisch (tests.edn:6).
- Arbeitsbranch: `feat/bot-game-runner` (von `master` abzweigen). Conventional Commits (`feat:`, `test:`).
- Voraussetzung: `data/cards.edn` existiert (via `lein fetch`), sonst schlagen Karten-Tests mit klarer Meldung fehl.

## Bekannte v1-Grenzen (bewusst, dokumentiert)

- Keine dynamischen Abilities (`auto-pump`, `auto-pump-and-break`) und keine Identity-Abilities in der Aktionsaufzählung — Icebreaker werden nur über ihre normalen `:abilities` (mit `:playable`-Flag) angeboten.
- Trace-Gebote werden 0..eigene Credits angeboten; exakte Obergrenze validiert die Engine.
- `purge`/`trash-resource` werden angeboten, ohne Klick-Anzahl vorzuprüfen — ein abgelehnter Versuch ist ein No-Op und wird per Retry-Mechanik aussortiert.
- Select-Prompts: Kandidaten = alle für die Seite sichtbaren Karten; ungültige Ziele sind No-Ops, der Bot probiert (zufällig) weiter. Das entspricht einem Menschen, der auf Karten klickt.

---

### Task 1: Bot-Protokoll + Random-Bot

**Files:**
- Create: `src/clj/bot/protocol.clj`
- Create: `src/clj/bot/random.clj`
- Test: `test/clj/bot/random_test.clj`

**Interfaces:**
- Consumes: nichts (rein, keine Engine-Abhängigkeit)
- Produces:
  - `bot.protocol/Bot` — Protokoll mit
    `(decide [bot view legal-actions])` → `{:action <Element aus legal-actions> :reason <String>}`
    `(on-prompt [bot view prompt options])` → `{:option <Element aus options> :reason <String>}`
  - `bot.random/random-bot` — `(random-bot seed)` → Bot-Instanz (deterministisch pro Seed)

- [ ] **Step 1: Failing Test schreiben**

`test/clj/bot/random_test.clj`:

```clojure
(ns bot.random-test
  (:require
   [bot.protocol :as bp]
   [bot.random :as random]
   [clojure.test :refer :all]))

(deftest decide-waehlt-nur-legale-aktionen
  (let [bot (random/random-bot 42)
        actions [{:command "credit"} {:command "draw"} {:command "end-turn"}]
        results (doall (repeatedly 100 #(bp/decide bot {} actions)))]
    (is (every? #(some #{(:action %)} actions) results)
        "Wahl ist immer Element der übergebenen legalen Aktionen")
    (is (every? #(and (string? (:reason %)) (seq (:reason %))) results)
        "Jede Entscheidung hat einen nicht-leeren Begründungstext")
    (is (= (set actions) (set (map :action results)))
        "Über 100 Züge wird jede Option mindestens einmal gewählt (uniform)")))

(deftest decide-deterministisch-pro-seed
  (let [actions [{:command "credit"} {:command "draw"} {:command "end-turn"}]
        run (fn [] (let [bot (random/random-bot 7)]
                     (mapv :action (doall (repeatedly 20 #(bp/decide bot {} actions))))))]
    (is (= (run) (run)) "Gleicher Seed ⇒ gleiche Zugfolge")))

(deftest on-prompt-waehlt-nur-gegebene-optionen
  (let [bot (random/random-bot 1)
        options [{:type :button :label "Keep"} {:type :button :label "Mulligan"}]
        results (doall (repeatedly 50 #(bp/on-prompt bot {} {:msg "Keep hand?"} options)))]
    (is (every? #(some #{(:option %)} options) results))
    (is (= (set options) (set (map :option results))))
    (is (every? #(string? (:reason %)) results))))
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `lein kaocha --focus bot.random-test`
Expected: FAIL (Namespace `bot.protocol` / `bot.random` nicht gefunden)

- [ ] **Step 3: Minimal-Implementierung**

`src/clj/bot/protocol.clj`:

```clojure
(ns bot.protocol
  "Schnittstelle für Solo-Bots. Bots sehen NUR die zensierte View
  (game.core.diffs/state-summary via bot.view/view-for) — nie den rohen State.")

(defprotocol Bot
  (decide [bot view legal-actions]
    "Aktionsphase. view: zensierte Spielsicht der eigenen Seite.
     legal-actions: nicht-leerer Vektor von Aktions-Maps
     {:command <process-action-Command-String> :args <Map|nil> :label <String>}.
     Rückgabe: {:action <ein Element aus legal-actions> :reason <String>}.")
  (on-prompt [bot view prompt options]
    "Engine-Prompt beantworten. prompt: zensierter Prompt (aus [:<side> :prompt-state]
     der View). options: nicht-leerer Vektor von Options-Maps (bot.legal/prompt-options).
     Rückgabe: {:option <ein Element aus options> :reason <String>}."))
```

`src/clj/bot/random.clj`:

```clojure
(ns bot.random
  "Schwierigkeitsgrad 0: wählt uniform zufällig aus den legalen Optionen."
  (:require
   [bot.protocol :as bp]))

(defn- pick [^java.util.Random rng coll]
  (let [v (vec coll)]
    (nth v (.nextInt rng (count v)))))

(defrecord RandomBot [^java.util.Random rng]
  bp/Bot
  (decide [_ _view legal-actions]
    {:action (pick rng legal-actions)
     :reason (str "random-bot: uniform zufällig, 1 von "
                  (count legal-actions) " legalen Aktionen")})
  (on-prompt [_ _view _prompt options]
    {:option (pick rng options)
     :reason (str "random-bot: uniform zufällig, 1 von "
                  (count options) " Prompt-Optionen")}))

(defn random-bot
  "Baut einen seedbaren Random-Bot (deterministisch pro Seed)."
  [seed]
  (->RandomBot (java.util.Random. (long seed))))
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `lein kaocha --focus bot.random-test`
Expected: PASS (3 Tests)

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/protocol.clj src/clj/bot/random.clj test/clj/bot/random_test.clj
git commit -m "feat(bot): Bot-Protokoll (decide/on-prompt) + uniformer Random-Bot"
```

---

### Task 2: EDN-Decision-Log

**Files:**
- Create: `src/clj/bot/log.clj`
- Test: `test/clj/bot/log_test.clj`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `bot.log/decision-entry` — `[{:turn :phase :side :kind :options :choice :reason :no-op}] → Map` (normalisiert)
  - `bot.log/append-decision!` — `[path entry] → nil`, hängt eine EDN-Zeile (`pr-str` + `\n`) an die Datei an, legt Elternverzeichnisse an

- [ ] **Step 1: Failing Test schreiben**

`test/clj/bot/log_test.clj`:

```clojure
(ns bot.log-test
  (:require
   [bot.log :as blog]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(def beispiel
  {:turn 3 :phase :action/corp :side :corp :kind :action
   :options ["gain 1 credit" "draw 1 card"] :choice "draw 1 card"
   :reason "random-bot: uniform zufällig, 1 von 2 legalen Aktionen"
   :no-op false})

(deftest decision-entry-edn-roundtrip
  (let [entry (blog/decision-entry beispiel)]
    (is (= entry (edn/read-string (pr-str entry)))
        "Eintrag überlebt pr-str/read-string verlustfrei")
    (is (every? #(contains? entry %)
                [:turn :phase :side :kind :options :choice :reason :no-op]))))

(deftest append-decision!-schreibt-eine-edn-zeile-pro-eintrag
  (let [f (io/file (System/getProperty "java.io.tmpdir")
                   (str "bot-log-test-" (System/currentTimeMillis) ".edn"))]
    (try
      (blog/append-decision! (.getPath f) beispiel)
      (blog/append-decision! (.getPath f) (assoc beispiel :turn 4))
      (let [lines (str/split-lines (slurp f))]
        (is (= 2 (count lines)))
        (is (= [3 4] (map #(:turn (edn/read-string %)) lines))))
      (finally (.delete f)))))
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `lein kaocha --focus bot.log-test`
Expected: FAIL (`bot.log` nicht gefunden)

- [ ] **Step 3: Minimal-Implementierung**

`src/clj/bot/log.clj`:

```clojure
(ns bot.log
  "Decision-Log: eine EDN-Zeile pro Bot-Entscheidung (Projektregel: Pflicht).
  options/choice sind Labels (Strings), nie rohe Karten-Maps."
  (:require
   [clojure.java.io :as io]))

(defn decision-entry
  [{:keys [turn phase side kind options choice reason no-op]}]
  {:turn turn
   :phase phase
   :side side
   :kind kind
   :options (vec options)
   :choice choice
   :reason reason
   :no-op (boolean no-op)})

(defn append-decision!
  [path entry]
  (io/make-parents (io/file path))
  (spit path (str (pr-str (decision-entry entry)) "\n") :append true)
  nil)
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `lein kaocha --focus bot.log-test`
Expected: PASS (2 Tests)

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/log.clj test/clj/bot/log_test.clj
git commit -m "feat(bot): EDN-Decision-Log (eine Zeile pro Entscheidung)"
```

---

### Task 3: Karten-Loading + Demo-Decks

**Files:**
- Create: `src/clj/bot/cards.clj`
- Test: `test/clj/bot/cards_test.clj`

**Interfaces:**
- Consumes: `game.utils/server-card` (Titel → Karten-Map aus `@all-cards`), `jinteki.cards/all-cards`
- Produces:
  - `bot.cards/load-all-cards!` — `[] → nil`, idempotent; lädt `data/cards.edn` in `jinteki.cards/all-cards` und `require`t alle `game.cards.*` (Vorbild: `game.test-framework/load-all-cards`, test_framework.clj:24-47)
  - `bot.cards/player-entry` — `[side-str deck-map] → Map` im Format, das `game.core.set-up/init-game` unter `:players` erwartet (`{:side "Corp" :user {...} :deck {:identity <Karten-Map> :cards [{:card <Karten-Map> :qty n} ...]}}` — Format siehe test_framework.clj:297-314 + 374-389)
  - `bot.cards/demo-corp`, `bot.cards/demo-runner` — kleine feste Decks (15 Karten; kleine Decks ⇒ Corp deckt nach ~10-15 Zügen aus ⇒ garantierte Terminierung)

- [ ] **Step 1: Failing Test schreiben**

`test/clj/bot/cards_test.clj`:

```clojure
(ns bot.cards-test
  (:require
   [bot.cards :as cards]
   [clojure.test :refer :all]
   [game.utils :refer [server-card]]))

(use-fixtures :once (fn [f] (cards/load-all-cards!) (f)))

(deftest demo-deck-karten-existieren
  (doseq [deck [cards/demo-corp cards/demo-runner]]
    (is (some? (server-card (:identity deck)))
        (str "Identity nicht gefunden: " (:identity deck)))
    (doseq [[title _qty] (:cards deck)]
      (is (some? (server-card title)) (str "Karte nicht gefunden: " title)))))

(deftest player-entry-hat-init-game-format
  (let [entry (cards/player-entry "Corp" cards/demo-corp)]
    (is (= "Corp" (:side entry)))
    (is (map? (get-in entry [:deck :identity])))
    (is (= "Corp" (get-in entry [:deck :identity :side])))
    (is (every? #(and (map? (:card %)) (pos-int? (:qty %)))
                (get-in entry [:deck :cards])))
    (is (= 15 (reduce + (map :qty (get-in entry [:deck :cards])))))))
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `lein kaocha --focus bot.cards-test`
Expected: FAIL (`bot.cards` nicht gefunden)

- [ ] **Step 3: Minimal-Implementierung**

`src/clj/bot/cards.clj`:

```clojure
(ns bot.cards
  "Kartendaten + Demo-Decks für Headless-Spiele (ohne Mongo/Webserver).
  Loading-Muster analog game.test-framework/load-all-cards."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [game.utils :refer [server-card]]
   [jinteki.cards :refer [all-cards]]))

(defn load-all-cards!
  []
  (when (empty? @all-cards)
    (->> (io/file "data/cards.edn")
         slurp
         edn/read-string
         (map (juxt :title identity))
         (into {})
         (reset! all-cards))
    (require '[game.cards.agendas]
             '[game.cards.assets]
             '[game.cards.basic]
             '[game.cards.events]
             '[game.cards.hardware]
             '[game.cards.ice]
             '[game.cards.identities]
             '[game.cards.operations]
             '[game.cards.programs]
             '[game.cards.resources]
             '[game.cards.upgrades]))
  nil)

(def demo-corp
  {:identity "Haas-Bioroid: Engineering the Future"
   :cards [["Hedge Fund" 3] ["PAD Campaign" 3] ["Ice Wall" 3]
           ["Enigma" 3] ["Priority Requisition" 3]]})

(def demo-runner
  {:identity "Kate \"Mac\" McCaffrey: Digital Tinker"
   :cards [["Sure Gamble" 3] ["Diesel" 3] ["Dirty Laundry" 3]
           ["Corroder" 3] ["Gordian Blade" 3]]})

(defn- deck-entry [title qty]
  (let [card (server-card title)]
    (when-not card
      (throw (ex-info (str "Karte nicht in all-cards (lein fetch gelaufen?): " title)
                      {:title title})))
    {:card card :qty qty}))

(defn player-entry
  [side {:keys [identity cards]}]
  {:side side
   :user {:username (str "Bot-" side)}
   :deck {:identity (server-card identity)
          :cards (mapv #(apply deck-entry %) cards)}})
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `lein kaocha --focus bot.cards-test`
Expected: PASS (2 Tests)

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/cards.clj test/clj/bot/cards_test.clj
git commit -m "feat(bot): Karten-Loading ohne DB + feste Demo-Decks"
```

---

### Task 4: Zensierte Bot-View

**Files:**
- Create: `src/clj/bot/view.clj`
- Test: `test/clj/bot/view_test.clj`

**Interfaces:**
- Consumes: `game.core.diffs/strip-state`, `game.core.diffs/state-summary` (diffs.clj:503-517)
- Produces:
  - `bot.view/view-for` — `[state side] → Map`: exakt die Client-Sicht der Seite (Gegner-Hand nur als `:hand-count`, unrezzte Corp-Karten ohne `:title`, `:playable`-Flags auf Handkarten/Abilities, eigener Prompt unter `[side :prompt-state]`)
  - `bot.view/phase-of` — `[view] → Keyword` fürs Log (`:corp-phase-12`, `:runner-phase-12`, `:run/<phase>`, `:between-turns`, `:action/<side>`)

- [ ] **Step 1: Failing Test schreiben**

`test/clj/bot/view_test.clj` (nutzt das Test-Framework-DSL — `do-game` bindet `state`):

```clojure
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
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `lein kaocha --focus bot.view-test`
Expected: FAIL (`bot.view` nicht gefunden)

- [ ] **Step 3: Minimal-Implementierung**

`src/clj/bot/view.clj`:

```clojure
(ns bot.view
  "Zensierte Spielsicht für Bots — exakt die Information, die der echte Client
  der jeweiligen Seite über den Diff-Layer bekommt. Bots dürfen NIE den rohen
  @state sehen; view-for ist die einzige erlaubte Quelle."
  (:require
   [game.core.diffs :as diffs]))

(defn view-for
  [state side]
  (diffs/state-summary (diffs/strip-state state) state side))

(defn phase-of
  [view]
  (cond
    (:corp-phase-12 view)   :corp-phase-12
    (:runner-phase-12 view) :runner-phase-12
    (:run view)             (keyword "run" (name (get-in view [:run :phase] :unknown)))
    (:end-turn view)        :between-turns
    :else                   (keyword "action" (name (or (:active-player view) :none)))))
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `lein kaocha --focus bot.view-test`
Expected: PASS (4 Tests)

Hinweis für den Implementierer: Schlägt `phase-of-grundfaelle` fehl, weil `:end-turn` nach `take-credits` noch gesetzt ist (take-credits startet den Folgezug bereits — dann ist `:end-turn` wieder weg), die tatsächliche State-Reihenfolge im REPL prüfen und die `cond`-Reihenfolge anpassen — nicht den Test aufweichen.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/view.clj test/clj/bot/view_test.clj
git commit -m "feat(bot): zensierte Bot-View via game.core.diffs + Phasen-Erkennung"
```

---

### Task 5: Aufzählung legaler Aktionen + Prompt-Optionen

**Files:**
- Create: `src/clj/bot/legal.clj`
- Test: `test/clj/bot/legal_test.clj`

**Interfaces:**
- Consumes: `bot.view/view-for` (Tests); die Funktionen selbst nehmen NUR die View entgegen (Informations-Hygiene per Konstruktion — kein State-Parameter)
- Produces:
  - `bot.legal/action` — `[command args label] → {:command :args :label}`
  - `bot.legal/turn-actions` — `[view side] → Vektor` Aktionsphase-Optionen; bei 0 Klicks nur `end-turn`
  - `bot.legal/run-actions` — `[view side] → Vektor` Optionen während Run/Encounter (Corp: continue/rez/fire-subs; Runner: continue/jack-out/Breaker-Abilities)
  - `bot.legal/prompt-options` — `[view side] → Vektor` von `{:type :button/:number/:card ...}` für den vordersten Prompt

Command-Strings sind die aus `game.core.process-actions/commands` (process_actions.clj:67-114): `"credit"`, `"draw"`, `"play"`, `"advance"`, `"score"`, `"purge"`, `"trash-resource"`, `"remove-tag"`, `"run"`, `"ability"`, `"continue"`, `"jack-out"`, `"rez"`, `"unbroken-subroutines"`, `"end-turn"`.

- [ ] **Step 1: Failing Test schreiben**

`test/clj/bot/legal_test.clj`:

```clojure
(ns bot.legal-test
  (:require
   [bot.legal :as legal]
   [bot.view :as view]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.test-framework :refer :all]))

(defn- commands-of [actions] (set (map :command actions)))

(deftest corp-aktionsphase-basis
  (do-game
    (new-game {:corp {:hand ["Hedge Fund" "Ice Wall"]}})
    (let [actions (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (contains? (commands-of actions) "credit"))
      (is (contains? (commands-of actions) "draw"))
      (is (= 2 (count (filter #(= "play" (:command %)) actions)))
          "beide spielbaren Handkarten werden angeboten")
      (is (not (contains? (commands-of actions) "end-turn"))
          "end-turn nicht anbieten solange Klicks übrig sind")
      (is (every? #(and (string? (:label %)) (seq (:label %))) actions)))))

(deftest keine-klicks-nur-end-turn
  (do-game
    (new-game)
    (core/gain state :corp :click -3)
    (let [actions (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (= ["end-turn"] (mapv :command actions))))))

(deftest runner-bekommt-run-optionen-auf-alle-server
  (do-game
    (new-game {:corp {:hand ["PAD Campaign"]}})
    (play-from-hand state :corp "PAD Campaign" "New remote")
    (take-credits state :corp)
    (let [actions (legal/turn-actions (view/view-for state :runner) :runner)
          servers (set (map #(get-in % [:args :server])
                            (filter #(= "run" (:command %)) actions)))]
      (is (= #{"HQ" "R&D" "Archives" "Server 1"} servers)))))

(deftest score-nur-fuer-fertige-agenden
  (do-game
    (new-game {:corp {:hand ["Priority Requisition"]}})
    (play-from-hand state :corp "Priority Requisition" "New remote")
    (let [ohne (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (not (contains? (commands-of ohne) "score"))
          "Agenda ohne Advancements nicht scorebar"))
    (core/gain state :corp :click 10 :credit 10)
    (dotimes [_ 5]
      (click-advance state :corp (get-content state :remote1 0)))
    (let [mit (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (contains? (commands-of mit) "score")))))

(deftest prompt-optionen-fuer-button-prompt
  (do-game
    (new-game {:dont-start-game true})
    (let [options (legal/prompt-options (view/view-for state :corp) :corp)]
      (is (= #{"Keep" "Mulligan"} (set (map :label options))))
      (is (every? #(= :button (:type %)) options))
      (is (every? :uuid options) "Button-Optionen tragen die uuid für den choice-Command"))))

(deftest run-optionen-approach-und-encounter
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (take-credits state :corp)
    (run-on state "HQ")
    ;; Approach: Corp darf rezzen oder continue
    (let [corp-opts (legal/run-actions (view/view-for state :corp) :corp)]
      (is (contains? (commands-of corp-opts) "continue"))
      (is (contains? (commands-of corp-opts) "rez")))
    (let [runner-opts (legal/run-actions (view/view-for state :runner) :runner)]
      (is (contains? (commands-of runner-opts) "continue")))))
```

Benötigte Test-Framework-Symbole (`game.test-framework :refer :all` bringt sie mit): `do-game`, `new-game`, `play-from-hand`, `take-credits`, `run-on`, `click-advance`, `get-content`; `core/gain` kommt aus dem separaten Require `[game.core :as core]`.

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `lein kaocha --focus bot.legal-test`
Expected: FAIL (`bot.legal` nicht gefunden)

- [ ] **Step 3: Implementierung**

`src/clj/bot/legal.clj`:

```clojure
(ns bot.legal
  "Zählt legale Optionen ausschließlich aus der zensierten View auf (bot.view),
  nie aus dem rohen State. Die Engine bleibt Regelinstanz: hier wird nur
  angeboten, validiert wird in game.core.process-actions — abgelehnte Aktionen
  sind No-Ops und werden vom Runner per Retry aussortiert.")

(defn action [command args label]
  {:command command :args args :label label})

(defn- server-name
  "View-Zone-Keyword (:hq :rd :archives :remoteN) -> Servername für click-run."
  [zone-kw]
  (case zone-kw
    :hq "HQ"
    :rd "R&D"
    :archives "Archives"
    (str "Server " (subs (name zone-kw) (count "remote")))))

(defn- installed-corp-cards [view]
  (mapcat (fn [[_ srv]] (concat (:ices srv) (:content srv)))
          (get-in view [:corp :servers])))

(defn- runner-rig-cards [view]
  (let [rig (get-in view [:runner :rig])]
    (concat (:program rig) (:hardware rig) (:resource rig))))

(defn- hand-plays [view side]
  (for [c (get-in view [side :hand])
        :when (:playable c)]
    (action "play" {:card c} (str "play " (:title c)))))

(defn- ability-actions
  "Nutzbare Karten-Abilities (Server setzt :playable pro Ability, diffs.clj:72-92)."
  [view side]
  (let [cards (if (= side :corp)
                (installed-corp-cards view)
                (runner-rig-cards view))]
    (for [c cards
          ab (:abilities c)
          :when (and (:playable ab) (not (:dynamic ab)) (:index ab))]
      (action "ability" {:card c :ability (:index ab)}
              (str (or (:title c) "facedown card") ": " (:label ab))))))

(defn- scoreable? [c]
  (and (= "Agenda" (:type c))
       (>= (+ (:advance-counter c 0) (:extra-advance-counter c 0))
           (or (:current-advancement-requirement c) (:advancementcost c) Integer/MAX_VALUE))))

(defn- corp-click-actions [view]
  (let [corp (:corp view)
        credits (:credit corp 0)
        runner-tagged? (pos? (get-in view [:runner :tag :total] 0))
        installed (installed-corp-cards view)]
    (concat
     [(action "credit" nil "gain 1 credit")]
     (when (pos? (:deck-count corp 0))
       [(action "draw" nil "draw 1 card")])
     (hand-plays view :corp)
     (ability-actions view :corp)
     (when (pos? credits)
       (for [c installed :when (:advanceable c)]
         (action "advance" {:card c} (str "advance " (or (:title c) "facedown card")))))
     (for [c installed :when (scoreable? c)]
       (action "score" {:card c} (str "score " (:title c))))
     [(action "purge" nil "purge virus counters")]
     (when (and runner-tagged? (>= credits 2))
       [(action "trash-resource" nil "trash a runner resource")]))))

(defn- runner-click-actions [view]
  (let [runner (:runner view)
        credits (:credit runner 0)
        tags (get-in runner [:tag :total] 0)]
    (concat
     [(action "credit" nil "gain 1 credit")]
     (when (pos? (:deck-count runner 0))
       [(action "draw" nil "draw 1 card")])
     (hand-plays view :runner)
     (ability-actions view :runner)
     (for [zone (keys (get-in view [:corp :servers]))]
       (action "run" {:server (server-name zone)} (str "run " (server-name zone))))
     (when (and (pos? tags) (>= credits 2))
       [(action "remove-tag" nil "remove 1 tag")]))))

(defn turn-actions
  [view side]
  (let [clicks (get-in view [side :click] 0)]
    (if (pos? clicks)
      (vec (if (= side :corp)
             (corp-click-actions view)
             (runner-click-actions view)))
      [(action "end-turn" nil "end turn")])))

(defn- approached-ice
  "Das Ice an der aktuellen Run-Position, aus der View der jeweiligen Seite."
  [view]
  (when-let [run (:run view)]
    (let [server-kw (keyword (name (last (:server run))))
          ices (get-in view [:corp :servers server-kw :ices])
          pos (:position run)]
      (when (and pos (pos? pos) (<= pos (count ices)))
        (nth ices (dec pos))))))

(defn run-actions
  [view side]
  (let [run (:run view)
        encounter (:encounters view)
        current-ice (or (:ice encounter) (approached-ice view))]
    (vec
     (if (= side :corp)
       (concat
        [(action "continue" nil "no action (continue)")]
        (when (and current-ice (not (:rezzed current-ice)))
          [(action "rez" {:card current-ice} "rez current ice")])
        (when (and encounter (:rezzed current-ice)
                   (some #(not (:broken %)) (:subroutines current-ice)))
          [(action "unbroken-subroutines" {:card current-ice}
                   "fire unbroken subroutines")]))
       (concat
        [(action "continue" nil "continue run")]
        (when encounter (ability-actions view :runner))
        (when (= :movement (:phase run))
          [(action "jack-out" nil "jack out")]))))))

(defn- visible-cards
  "Alles, was side für Select-Prompts anklicken könnte (nur View-Inhalte)."
  [view side]
  (concat
   (get-in view [side :hand])
   (installed-corp-cards view)
   (runner-rig-cards view)
   (get-in view [:runner :rig :facedown])
   (get-in view [:corp :discard])
   (get-in view [:runner :discard])
   (get-in view [side :play-area])
   (get-in view [side :scored])))

(defn prompt-options
  [view side]
  (let [prompt (get-in view [side :prompt-state])
        choices (:choices prompt)]
    (cond
      (= :select (:prompt-type prompt))
      (vec
       (concat
        (for [c (visible-cards view side) :when (:cid c)]
          {:type :card :card c :label (or (:title c) "facedown card")})
        ;; manche Select-Prompts haben zusätzlich Buttons (z. B. "Done")
        (for [c (when (sequential? choices) choices)]
          {:type :button :uuid (:uuid c) :label (str (:value c))})))

      (= :trace (:prompt-type prompt))
      (mapv (fn [n] {:type :number :value n :label (str n)})
            (range 0 (inc (min (get-in view [side :credit] 0)
                               (if (number? choices) choices Integer/MAX_VALUE)))))

      (or (= :credit choices) (and (map? choices) (or (:number choices) (:counter choices))))
      (let [max-n (cond
                    (= :credit choices) (get-in view [side :credit] 0)
                    (:number choices) (:number choices)
                    :else (get-in view [side :credit] 0))]
        (mapv (fn [n] {:type :number :value n :label (str n)})
              (range 0 (inc max-n))))

      (sequential? choices)
      (mapv (fn [c] {:type :button
                     :uuid (:uuid c)
                     :label (str (or (get-in c [:value :title]) (:value c)))})
            choices)

      :else
      [])))
```

- [ ] **Step 4: Test laufen lassen — muss grün sein**

Run: `lein kaocha --focus bot.legal-test`
Expected: PASS (6 Tests)

Erwartbare Stolpersteine (im REPL gegen echte View-Daten prüfen, Tests nicht aufweichen):
- Key-Namen im Run-Summary: `:server` ist im Run-Map eine Zone (Vektor wie `[:servers :hq]`) — `approached-ice` entsprechend anpassen, falls das Format abweicht (run-keys, diffs.clj:429-447).
- `:tag`-Struktur in der View (`{:base .. :total ..}`) verifizieren.
- Ability-`:index` vs. Listenposition: `ability-keys` (diffs.clj:94-102) enthält `:index`; falls in der Praxis nil, stattdessen `map-indexed` über `(:abilities c)`.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/legal.clj test/clj/bot/legal_test.clj
git commit -m "feat(bot): Aufzählung legaler Aktionen und Prompt-Optionen aus der zensierten View"
```

---

### Task 6: Game Runner (Headless-Partie + Integrationstest)

**Files:**
- Create: `src/clj/bot/game_runner.clj`
- Test: `test/clj/bot/game_runner_test.clj`

**Interfaces:**
- Consumes: alles aus Task 1-5 (`bot.protocol/decide|on-prompt`, `bot.log/append-decision!`, `bot.cards/load-all-cards!|player-entry|demo-corp|demo-runner`, `bot.view/view-for|phase-of`, `bot.legal/turn-actions|run-actions|prompt-options|action`), `game.core.set-up/init-game`, `game.core/process-action`
- Produces:
  - `bot.game-runner/next-actor` — `[state] → [side kind] | nil` (public für Tests); kind ∈ `#{:prompt :run :phase-12 :start-turn :action}`
  - `bot.game-runner/run-game` — `[{:corp-bot :runner-bot :log-path :max-steps :corp-deck :runner-deck}] → {:winner :reason :turn :steps :completed?}`

**Prioritäts-Resolver** (Reihenfolge fest, Vorbild `run-continue-impl`, test_framework.clj:736-754 — Corp zuerst `continue`, dann Runner, außer `:no-action` ist schon gesetzt):
1. `:winner` gesetzt → nil (Partie zu Ende)
2. Beantwortbarer Prompt Corp, dann Runner (Prompt-Typ nicht `:waiting`/`:run`)
3. Run/Encounter aktiv → `:no-action` nicht gesetzt ⇒ Corp, sonst Runner
4. `:corp-phase-12` / `:runner-phase-12` ⇒ betreffende Seite (`end-phase-12`)
5. `:end-turn` gesetzt ⇒ Gegenseite des `:active-player` (`start-turn`)
6. sonst ⇒ `:active-player`, Aktionsphase

- [ ] **Step 1: Failing Unit-Test für next-actor schreiben**

`test/clj/bot/game_runner_test.clj` (erste Deftests):

```clojure
(ns bot.game-runner-test
  (:require
   [bot.game-runner :as gr]
   [bot.random :as random]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [game.test-framework :refer :all]))

(deftest next-actor-aktionsphase
  (do-game
    (new-game)
    (is (= [:corp :action] (gr/next-actor state)))
    (take-credits state :corp)
    (is (= [:runner :action] (gr/next-actor state)))))

(deftest next-actor-mulligan-prompt
  (do-game
    (new-game {:dont-start-game true})
    (is (= [:corp :prompt] (gr/next-actor state))
        "Mulligan-Prompt der Corp kommt zuerst")))
```

- [ ] **Step 2: Test laufen lassen — muss fehlschlagen**

Run: `lein kaocha --focus bot.game-runner-test`
Expected: FAIL (`bot.game-runner` nicht gefunden)

- [ ] **Step 3: Implementierung**

`src/clj/bot/game_runner.clj`:

```clojure
(ns bot.game-runner
  "Headless Runner: spielt eine komplette Partie zwischen zwei Bots.
  Regelauswertung läuft ausschließlich über game.core/process-action;
  dieser Namespace orchestriert nur, welche Seite wann gefragt wird.
  Roher @state wird hier NUR für Mechanik benutzt (Resolver, Prompt-eid,
  Progress-Fingerprint) — Bot-Input ist immer bot.view/view-for."
  (:require
   [bot.cards :as cards]
   [bot.legal :as legal]
   [bot.log :as blog]
   [bot.protocol :as bp]
   [bot.view :as view]
   [game.core :as core]
   [game.core.set-up :as setup]))

(defn- raw-prompt [state side]
  (-> @state side :prompt seq first))

(defn- actionable-prompt? [state side]
  (when-let [p (raw-prompt state side)]
    (not (contains? #{:waiting :run} (:prompt-type p)))))

(defn next-actor
  [state]
  (let [s @state]
    (cond
      (:winner s) nil
      (actionable-prompt? state :corp) [:corp :prompt]
      (actionable-prompt? state :runner) [:runner :prompt]

      (or (seq (:encounters s)) (:run s))
      (let [no-action (or (:no-action (peek (:encounters s)))
                          (:no-action (:run s)))]
        (if no-action [:runner :run] [:corp :run]))

      (:corp-phase-12 s) [:corp :phase-12]
      (:runner-phase-12 s) [:runner :phase-12]
      (:end-turn s) [(if (= (:active-player s) :corp) :runner :corp) :start-turn]
      :else [(:active-player s) :action])))

(defn- options-for [view side kind]
  (case kind
    :prompt     (legal/prompt-options view side)
    :run        (legal/run-actions view side)
    :phase-12   [(legal/action "end-phase-12" nil "end phase 1.2")]
    :start-turn [(legal/action "start-turn" nil "start turn")]
    :action     (legal/turn-actions view side)))

(defn- apply-choice!
  [state side kind chosen]
  (if (= kind :prompt)
    (let [eid (:eid (raw-prompt state side))]
      (case (:type chosen)
        :card   (core/process-action "select" state side {:card (:card chosen) :eid eid})
        :number (core/process-action "choice" state side {:choice (:value chosen) :eid eid})
        :button (core/process-action "choice" state side {:choice {:uuid (:uuid chosen)} :eid eid})))
    (core/process-action (:command chosen) state side (:args chosen))))

(defn- fingerprint
  "Kompakter Zustands-Abdruck, um No-Op-Aktionen zu erkennen."
  [state]
  (let [s @state]
    [(get-in s [:corp :click]) (get-in s [:runner :click])
     (get-in s [:corp :credit]) (get-in s [:runner :credit])
     (count (get-in s [:corp :prompt])) (count (get-in s [:runner :prompt]))
     (:eid (raw-prompt state :corp)) (:eid (raw-prompt state :runner))
     (:turn s) (:active-player s) (:end-turn s)
     (:corp-phase-12 s) (:runner-phase-12 s)
     (select-keys (:run s) [:phase :position :no-action :server])
     (count (:encounters s)) (:no-action (peek (:encounters s)))
     (count (:log s)) (:winner s)]))

(defn- step!
  "Eine Entscheidung: Bot fragen, anwenden, loggen. No-Ops: Option streichen,
  Bot erneut fragen. Wirft, wenn keine Option den State bewegt."
  [state side kind bot log-path step-no]
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
            _ (apply-choice! state side kind chosen)
            progressed? (not= before (fingerprint state))]
        (blog/append-decision! log-path
                               {:turn (:turn @state 0)
                                :phase (view/phase-of v)
                                :side side
                                :kind kind
                                :options (mapv :label options)
                                :choice (:label chosen)
                                :reason (:reason decision)
                                :no-op (not progressed?)})
        (when-not progressed?
          (recur (vec (remove #{chosen} options))))))))

(defn run-game
  [{:keys [corp-bot runner-bot log-path max-steps corp-deck runner-deck]
    :or {max-steps 5000
         corp-deck cards/demo-corp
         runner-deck cards/demo-runner}}]
  (cards/load-all-cards!)
  (let [state (setup/init-game
               {:gameid 1
                :format "casual"
                :players [(cards/player-entry "Corp" corp-deck)
                          (cards/player-entry "Runner" runner-deck)]})]
    (loop [steps 0]
      (if-let [[side kind] (when (< steps max-steps) (next-actor state))]
        (let [bot (if (= side :corp) corp-bot runner-bot)]
          (step! state side kind bot log-path steps)
          (recur (inc steps)))
        {:winner (:winner @state)
         :reason (:reason @state)
         :turn (:turn @state)
         :steps steps
         :completed? (some? (:winner @state))}))))
```

- [ ] **Step 4: Unit-Tests laufen lassen — müssen grün sein**

Run: `lein kaocha --focus bot.game-runner-test`
Expected: PASS (2 Tests)

- [ ] **Step 5: Failing Integrationstest ergänzen**

An `test/clj/bot/game_runner_test.clj` anhängen:

```clojure
(deftest random-vs-random-komplette-partie
  (let [log-file (io/file (System/getProperty "java.io.tmpdir")
                          (str "bot-game-" (System/currentTimeMillis) ".edn"))
        result (gr/run-game {:corp-bot (random/random-bot 1)
                             :runner-bot (random/random-bot 2)
                             :log-path (.getPath log-file)
                             :max-steps 3000})]
    (try
      (testing "Partie terminiert (Sieger oder Step-Cap)"
        (is (map? result))
        (is (<= (:steps result) 3000))
        (is (or (:completed? result) (= 3000 (:steps result)))))
      (testing "Log: eine valide EDN-Zeile pro Entscheidung, mit Pflicht-Keys"
        (let [lines (str/split-lines (slurp log-file))]
          (is (pos? (count lines)))
          (doseq [line (take 50 lines)]
            (let [e (edn/read-string line)]
              (is (every? #(contains? e %)
                          [:turn :phase :side :kind :options :choice :reason :no-op])
                  (str "Zeile unvollständig: " line))
              (is (every? string? (:options e)) "Optionen sind Labels, keine Karten-Maps")))))
      (finally (.delete log-file)))))
```

- [ ] **Step 6: Integrationstest laufen lassen, debuggen bis grün**

Run: `lein kaocha --focus bot.game-runner-test`
Expected: PASS (3 Tests)

Das ist der Schritt, in dem Lücken der Run-/Prompt-Orchestrierung sichtbar werden. Debugging-Leitfaden:
- `ex-info "Keine ausführbare Option übrig"` → im Exception-Data steht der Prompt; fehlenden Prompt-Typ in `bot.legal/prompt-options` ergänzen (Format in `src/clj/game/core/prompts.clj` bzw. docs/bot-architektur.md Frage 4 nachschlagen).
- Endlosschleife bis Step-Cap ohne Fortschritt → `fingerprint` um den fehlenden State-Aspekt erweitern (z. B. Counter auf Karten), NICHT den Cap erhöhen.
- Terminierung ist durch die 15-Karten-Decks abgesichert: Corp Mandatory Draw ⇒ nach ≤ ~15 Zügen deckt die Corp aus und verliert, falls vorher niemand gewinnt.

- [ ] **Step 7: Alle Bot-Tests + Lint-Sanity**

Run: `lein kaocha --focus bot.random-test --focus bot.log-test --focus bot.cards-test --focus bot.view-test --focus bot.legal-test --focus bot.game-runner-test`
Expected: PASS (alle)

- [ ] **Step 8: Commit**

```bash
git add src/clj/bot/game_runner.clj test/clj/bot/game_runner_test.clj
git commit -m "feat(bot): headless Game Runner — komplette Partie Random vs. Random mit EDN-Log"
```

---

## Verifikation am Ende (vor Merge/PR)

1. `lein kaocha --focus bot.random-test --focus bot.log-test --focus bot.cards-test --focus bot.view-test --focus bot.legal-test --focus bot.game-runner-test` — alles grün.
2. Volle Suite läuft weiter (`bin/test` bzw. `lein kaocha`) — es wurde kein Engine-Code angefasst, also dürfen keine bestehenden Tests brechen.
3. Manuelle Probe im REPL:
   ```clojure
   (require '[bot.game-runner :as gr] '[bot.random :as random])
   (gr/run-game {:corp-bot (random/random-bot 1)
                 :runner-bot (random/random-bot 2)
                 :log-path "logs/bot/demo-game.edn"})
   ```
   Ergebnis-Map prüfen, `logs/bot/demo-game.edn` stichprobenartig lesen (Begründungen, keine versteckte Information in Options-Labels).
