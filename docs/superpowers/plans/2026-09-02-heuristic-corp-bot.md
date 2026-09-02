# Heuristik-Corp-Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zweite Corp-Schwierigkeitsstufe `"heuristic"` (regelbasiertes Playbook), aufbauend auf einer korrigierten `bot.eval`-Bedrohungsschätzung (Breaker-Typ-Matching + echte Break-Kosten statt Stärke-Delta-Proxy).

**Architecture:** `bot.eval` bekommt eine view-basierte öffentliche API und echte Durchbruchskosten-Berechnung (liest statische, gedruckte Karten-Ability-Daten via `card-def`, nie rohen `@state`). `bot.legal` bekommt eine generelle Rez-Option außerhalb von Runs. Ein neuer Namespace `bot.heuristic-corp` implementiert `bot.protocol/Bot` rein aus `view` + `legal-actions`/`prompt-options` heraus (keine Fabrikation eigener Aktionen — nur Auswahl aus den angebotenen Optionen), mit einem eingebetteten `bot.random`-Bot als Fallback für alles, was das Playbook nicht abdeckt.

**Tech Stack:** Clojure, kaocha (Tests), bestehende `bot.*`-Infrastruktur (`bot.view`, `bot.legal`, `bot.protocol`, `bot.game-runner`, `bot.roster`, `bot.sim`), `game.test-framework` (`do-game`-DSL).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-09-01-heuristic-corp-bot-design.md` — jede Abweichung unten ist dort vermerkt (Rez-Entscheidung wurde beim Planen vereinfacht, siehe dortiger Korrektur-Absatz).
- Projektregel: `src/clj/game/core/` und `src/clj/game/cards/` (Regelkern) werden in diesem Plan NICHT verändert. Alle Änderungen liegen unter `src/clj/bot/`.
- Projektregel: Bots sehen nie rohen `@state`, nur `bot.view/view-for`-Views. `bot.eval`s neue Kosten-Introspektion liest `game.core.card-defs/card-def` nur für Karten, deren Titel in der View bereits öffentlich ist (nie für private/verdeckte Karten).
- Projektregel: jede `decide`/`on-prompt`-Rückgabe trägt ein `:reason`, das die gegriffene Playbook-Regel und die ausschlaggebenden Zahlen benennt (Decision-Log-Pflicht).
- Nach jeder Änderung an einer Datei: die zugehörige Testdatei laufen lassen (`lein kaocha --focus <ns>`, alternativ `bin/test-focus <ns>` im Docker-Setup). Vor dem letzten Commit dieses Plans: volle Suite (`lein kaocha` / `bin/test`).
- Commit-Message-Präfixe: `test:` für Testdateien, `feat:` für Implementierung, ein Commit pro Task (TDD: Test schreiben → rot verifizieren → implementieren → grün verifizieren → committen).
- Erfolgsschwelle (aus der Spec, Task 12): Heuristik-Corp gegen Random-Runner ≥ 140/200 Siege.

---

## Task 1: `bot.eval` — Breaker-Typ-Matching

**Files:**
- Modify: `src/clj/bot/eval.clj`
- Test: `test/clj/bot/eval_test.clj`

**Interfaces:**
- Consumes: nichts Neues (nur `bot.view/view-for`-Views, wie bisher).
- Produces: `bot.eval/evaluate` liefert weiterhin dieselbe Struktur, aber `[:servers <zone> :estimated-cost]` ist jetzt typgenau (Breaker muss zum Ice-Subtyp passen, nicht mehr global stärkster Breaker). Interne (private) neue Helfer: `ice-subtypes`, `matches-ice-type?`, `installed-breakers`, `matching-breaker-strength` — werden in Task 2 weiterverwendet.

- [ ] **Step 1: Failing test schreiben**

Füge in `test/clj/bot/eval_test.clj` folgenden Test ans Dateiende an:

```clojure
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
```

- [ ] **Step 2: Test ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.eval-test`
Expected: FAIL bei `breaker-typ-mismatch-senkt-bedrohung-nicht` — `mit-falschem-typ` ist KLEINER als `ohne-breaker` (Carmen senkt die Bedrohung fälschlich, weil der alte Code den global stärksten Breaker nimmt).

- [ ] **Step 3: Implementierung**

In `src/clj/bot/eval.clj`:

1. Require erweitern:

```clojure
  (:require
   [bot.view :as view]
   [game.core.card-defs :refer [card-def]]))
```

2. Direkt nach `(defn- icebreaker? [program] ...)` folgende neue private Funktionen einfügen:

```clojure
(defn- ice-subtypes
  "Öffentlich sichtbare Subtypen dieses Ice — leer, wenn unbekannt (siehe
  ice-known? weiter unten)."
  [ice]
  (set (:subtypes ice)))

(defn- matches-ice-type?
  [breaks ice-subtypes]
  (or (contains? breaks "All")
      (boolean (some breaks ice-subtypes))))

(defn- installed-breakers [runner-view]
  (filter icebreaker? (get-in runner-view [:rig :program])))

(defn- matching-breaker-strength
  "Stärkster installierter Icebreaker, dessen statisch gelesener Subtyp
  (card-def — öffentliche, gedruckte Karteninfo, identisch für jede Kopie
  einer Karte) zu `ice-subtypes` passt. Kein Typ-Match ⇒ 0, selbst wenn ein
  ANDERER Breaker installiert ist (Fix des v1-Bugs: vorher zählte der
  global stärkste Breaker unabhängig vom Ice-Typ, siehe
  bot-eval-v1-backlog)."
  [runner-view ice-subtypes]
  (->> (installed-breakers runner-view)
       (filter (fn [c]
                 (some #(and (:break %) (matches-ice-type? (:breaks %) ice-subtypes))
                       (:abilities (card-def c)))))
       (map #(or (:current-strength %) (:strength %) 0))
       (apply max 0)))
```

3. `best-breaker-strength`s Docstring präzisieren (Code unverändert, nur der Kommentar — er ist jetzt der Fallback für unbekannten Ice-Typ):

```clojure
(defn- best-breaker-strength
  "Stärkster installierter Icebreaker des Runners, 0 falls keiner installiert.
  Fallback-Wert für Ice mit unbekanntem Subtyp (unrezztes gegnerisches Ice —
  dort kann ohnehin nicht typgenau gematcht werden, siehe
  matching-breaker-strength)."
  [runner-view]
  (->> (get-in runner-view [:rig :program])
       (filter icebreaker?)
       (map #(or (:current-strength %) (:strength %) 0))
       (apply max 0)))
```

4. `ice-threat`, `server-threat`, `servers-threat` ersetzen (die View des Runners muss jetzt bis zu `ice-threat` durchgereicht werden):

```clojure
(defn- ice-threat
  "Geschätzte Kosten, dieses eine Ice zu überwinden, aus Sicht der Seite, die
  `ice` sieht. Bereits rezztes Ice ist bezahlt (kein Abschlag); noch nicht
  rezztes Ice ist nur eine potenzielle Bedrohung — kann die Corp die (echten
  oder geschätzten) Rez-Kosten mit ihren aktuell sichtbaren Credits nicht
  aufbringen, wird die Rohbedrohung mit UNAFFORDABLE-ICE-DISCOUNT abgewertet.
  Ist der Ice-Subtyp bekannt, wird der stärkste TYPGLEICHE Breaker verwendet
  (matching-breaker-strength) statt des global stärksten."
  [ice corp-credit runner-view best-breaker-strength]
  (let [[strength rez-cost] (ice-strength+cost ice)
        subtypes (ice-subtypes ice)
        effective-breaker-strength (if (seq subtypes)
                                      (matching-breaker-strength runner-view subtypes)
                                      best-breaker-strength)
        raw (raw-ice-cost strength effective-breaker-strength)]
    (if (or (:rezzed ice) (>= corp-credit (or rez-cost 0)))
      raw
      (* raw UNAFFORDABLE-ICE-DISCOUNT))))

(defn- server-threat
  [server-view corp-credit runner-credit runner-view best-breaker-strength]
  (let [ices (:ices server-view)
        estimated-cost (reduce + 0 (map #(ice-threat % corp-credit runner-view best-breaker-strength) ices))]
    {:ice-count (count ices)
     :rezzed-count (count (filter :rezzed ices))
     :ice-strength (reduce + 0 (map #(first (ice-strength+cost %)) ices))
     :estimated-cost estimated-cost
     :runner-can-afford? (<= estimated-cost runner-credit)}))

(defn- servers-threat
  "Pro Corp-Server eine Bedrohungsschätzung: kommt der Runner vermutlich
  durch, und was kostet es ihn? Nutzt nur öffentlich sichtbare Felder der
  View (Credits beider Seiten, installierte Icebreaker, Ice-Status)."
  [v]
  (let [corp-credit (get-in v [:corp :credit] 0)
        runner-view (get v :runner)
        runner-credit (+ (:credit runner-view 0) (:run-credit runner-view 0))
        best-breaker (best-breaker-strength runner-view)]
    (into {}
          (map (fn [[server-kw sv]]
                 [server-kw (server-threat sv corp-credit runner-credit runner-view best-breaker)]))
          (get-in v [:corp :servers]))))
```

`evaluate` selbst bleibt unverändert (ruft weiterhin `(servers-threat v)` mit derselben Signatur auf).

- [ ] **Step 4: Test ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.eval-test`
Expected: PASS, alle Tests (inkl. der bestehenden 9) grün.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/eval.clj test/clj/bot/eval_test.clj
git commit -m "$(cat <<'EOF'
fix(bot): eval.clj — Breaker-Typ-Matching statt global staerkster Breaker

Server-Bedrohungsschaetzung matcht Icebreaker jetzt gegen den echten
Ice-Subtyp (Fracter/Decoder/Killer vs. Barrier/Code Gate/Sentry, via
statischer card-def-Introspektion). Ein falsch-typisierter Breaker senkt
die Bedrohung nicht mehr faelschlich. Erster Teil des bot-eval-v1-Backlogs.
EOF
)"
```

---

## Task 2: `bot.eval` — Echte Break-/Pump-Kosten

**Files:**
- Modify: `src/clj/bot/eval.clj`
- Test: `test/clj/bot/eval_test.clj`

**Interfaces:**
- Consumes: `ice-subtypes`, `matches-ice-type?`, `installed-breakers`, `matching-breaker-strength` aus Task 1 (unveraendert).
- Produces: `[:servers <zone> :estimated-cost]` ist jetzt eine echte Credit-Schätzung (Pump- + Break-Kosten aus den gedruckten Ability-Daten) statt eines Stärke-Delta-Proxys, wann immer eine reine Credit-Kosten-Ability existiert; sonst Fallback auf die Task-1-Schätzung.

- [ ] **Step 1: Failing tests schreiben**

Ans Ende von `test/clj/bot/eval_test.clj` anfügen:

```clojure
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
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.eval-test`
Expected: FAIL bei allen vier neuen Tests (`echte-break-kosten-*`, `exotische-break-kosten-*`) — die Schätzung nutzt noch den Stärke-Delta-Proxy, nicht die echten Break-Kosten (z.B. Corroder vs. Ice Wall liefert aktuell `max(1, 1-2+1)=1` zufällig richtig, aber Battlement/Bastion liefern falsche Werte, da Subroutine-Anzahl und Pump-Kosten ignoriert werden).

- [ ] **Step 3: Implementierung**

In `src/clj/bot/eval.clj`, direkt nach `matching-breaker-strength` (vor `best-breaker-strength`) folgende neuen Funktionen einfügen:

```clojure
(defn- credit-cost
  "Summe reiner Credit-Kosten aus einem break-sub/strength-pump-Kostenvektor
  (Vektor von game.core.payment/->c-Maps), oder nil, wenn eine Komponente
  kein reiner Credit-Betrag ist (X-Cost, Virus-/Power-Counter, Trash, ...) —
  dann ist die Ability für eine Kosten-Schätzung nicht nutzbar (dokumentierte
  Vereinfachung, siehe Design-Spec Teil 1, Punkt 6)."
  [cost]
  (when (and (seq cost)
             (every? #(and (= :credit (:cost/type %)) (number? (:cost/amount %))) cost))
    (reduce + 0 (map :cost/amount cost))))

(defn- min-or-nil [xs]
  (when (seq xs) (apply min xs)))

(defn- pump-abilities [card]
  (filter :pump (:abilities (card-def card))))

(defn- break-abilities [card ice-subtypes]
  (filter #(and (:break %) (matches-ice-type? (:breaks %) ice-subtypes))
          (:abilities (card-def card))))

(defn- pump-cost-for-ice
  "Credits, um `card` mindestens `needed` zusätzliche Stärke zu geben — 0,
  wenn keine Stärke fehlt, nil ohne nutzbare (reine Credit-)Pump-Ability."
  [card needed]
  (if (<= needed 0)
    0
    (min-or-nil
     (keep (fn [ab]
             (let [per-use (:pump ab)
                   cost (credit-cost (:cost ab))]
               (when (and cost (number? per-use) (pos? per-use))
                 (* cost (long (Math/ceil (/ (double needed) per-use)))))))
           (pump-abilities card)))))

(defn- break-cost-for-ice
  "Credits, um alle `subs-count` Subroutinen dieses Ice mit `card` zu
  brechen — 0 ohne Subroutinen, nil ohne passende (reine Credit-)Break-
  Ability. `:break 0` bedeutet 'beliebig viele Subs in einer Zahlung'."
  [card ice-subtypes subs-count]
  (if (zero? subs-count)
    0
    (min-or-nil
     (keep (fn [ab]
             (let [n (:break ab)
                   per-use (if (pos? n) n subs-count)
                   cost (credit-cost (:break-cost ab))]
               (when cost
                 (* cost (long (Math/ceil (/ (double subs-count) per-use)))))))
           (break-abilities card ice-subtypes)))))

(defn- breaker-cost-for-ice
  [card ice-strength ice-subtypes subs-count]
  (let [breaker-strength (or (:current-strength card) (:strength card) 0)
        needed (max 0 (- ice-strength breaker-strength))
        pump (pump-cost-for-ice card needed)]
    (when pump
      (when-let [break (break-cost-for-ice card ice-subtypes subs-count)]
        (+ pump break)))))

(defn- best-breach-cost
  "Echte, minimale Credit-Kosten über alle installierten Icebreaker hinweg,
  dieses Ice vollständig zu durchbrechen — nil, wenn kein installierter
  Breaker mit reinen Credit-Kosten passt (Fallback: raw-ice-cost, siehe
  ice-threat)."
  [runner-view ice-strength ice-subtypes subs-count]
  (min-or-nil
   (keep #(breaker-cost-for-ice % ice-strength ice-subtypes subs-count)
         (installed-breakers runner-view))))
```

Dann `ice-threat` (aus Task 1) durch diese Version ersetzen (fügt die `real`-Berechnung hinzu):

```clojure
(defn- ice-threat
  "Geschätzte Kosten, dieses eine Ice zu überwinden, aus Sicht der Seite, die
  `ice` sieht. Bereits rezztes Ice ist bezahlt (kein Abschlag); noch nicht
  rezztes Ice ist nur eine potenzielle Bedrohung — kann die Corp die (echten
  oder geschätzten) Rez-Kosten mit ihren aktuell sichtbaren Credits nicht
  aufbringen, wird die Rohbedrohung mit UNAFFORDABLE-ICE-DISCOUNT abgewertet.
  Bekannter Ice-Subtyp: erst echte Credit-Kosten versuchen
  (best-breach-cost), sonst Stärke-Delta-Fallback mit typgenauem Breaker
  (matching-breaker-strength)."
  [ice corp-credit runner-view best-breaker-strength]
  (let [[strength rez-cost] (ice-strength+cost ice)
        subtypes (ice-subtypes ice)
        subs-count (count (:subroutines ice))
        effective-breaker-strength (if (seq subtypes)
                                      (matching-breaker-strength runner-view subtypes)
                                      best-breaker-strength)
        real (when (seq subtypes)
               (best-breach-cost runner-view strength subtypes subs-count))
        raw (or real (raw-ice-cost strength effective-breaker-strength))]
    (if (or (:rezzed ice) (>= corp-credit (or rez-cost 0)))
      raw
      (* raw UNAFFORDABLE-ICE-DISCOUNT))))
```

`server-threat`/`servers-threat` bleiben aus Task 1 unverändert.

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.eval-test`
Expected: PASS, alle Tests (13 total) grün.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/eval.clj test/clj/bot/eval_test.clj
git commit -m "$(cat <<'EOF'
fix(bot): eval.clj — echte Break-/Pump-Kosten statt Staerke-Delta-Proxy

Server-Bedrohungsschaetzung liest jetzt Pump- und Break-Kosten direkt aus
den statischen (gedruckten) Ability-Daten der installierten Icebreaker und
skaliert korrekt mit Subroutine-Anzahl. Nicht-Credit-Kosten (X-Cost,
Virus-/Power-Counter) fallen sauber auf die Staerke-Delta-Schaetzung
zurueck, kein Crash. Zweiter/letzter Teil des bot-eval-v1-Backlogs.
EOF
)"
```

---

## Task 3: `bot.eval` — view-basierte öffentliche API

**Files:**
- Modify: `src/clj/bot/eval.clj`
- Test: `test/clj/bot/eval_test.clj`

**Interfaces:**
- Consumes: `servers-threat` (privat, Task 1/2), `evaluate` (öffentlich, unverändert nutzbar).
- Produces: `bot.eval/servers-threat` (jetzt ÖFFENTLICH, nimmt eine `view` direkt) und `bot.eval/evaluate-view` (öffentlich, `[view side] -> Bewertungs-Map`) — der Einstiegspunkt für `bot.heuristic-corp` (Task 5+), das nur eine View besitzt, nie `state`.

- [ ] **Step 1: Failing test schreiben**

Ans Ende von `test/clj/bot/eval_test.clj` anfügen:

```clojure
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
```

Am Kopf von `test/clj/bot/eval_test.clj` das Require um `bot.view` ergänzen:

```clojure
(ns bot.eval-test
  (:require
   [bot.eval :as beval]
   [bot.view :as bview]
   [clojure.test :refer :all]
   [game.core :as core]
   [game.test-framework :refer :all]))
```

- [ ] **Step 2: Test ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.eval-test`
Expected: FAIL — `bot.eval/evaluate-view` existiert noch nicht (`Unable to resolve symbol`), `servers-threat` ist noch privat (`Unable to resolve var: bot.eval/servers-threat`).

- [ ] **Step 3: Implementierung**

In `src/clj/bot/eval.clj`: `(defn- servers-threat ...)` → `(defn servers-threat ...)` (nur das `-` entfernen, Docstring ergänzen):

```clojure
(defn servers-threat
  "Pro Corp-Server eine Bedrohungsschätzung: kommt der Runner vermutlich
  durch, und was kostet es ihn? Nutzt nur öffentlich sichtbare Felder der
  View (Credits beider Seiten, installierte Icebreaker, Ice-Status).
  Öffentlich: Bot-Konsumenten wie bot.heuristic-corp bekommen nur eine View,
  nie rohen @state (Projektregel Informations-Hygiene) — sie greifen direkt
  hierauf zu, statt evaluate/2 zu nutzen, das intern state anfordert."
  [v]
  ...) ;; Body unverändert aus Task 1
```

Dann `evaluate` durch diese Version ersetzen (Body wandert nach `evaluate-view`, `evaluate` wird ein Wrapper):

```clojure
(defn evaluate-view
  "Wie `evaluate`, nimmt aber eine bereits berechnete View statt `state` —
  der Einstiegspunkt für Bot-Konsumenten, die nur eine View besitzen (siehe
  servers-threat-Docstring)."
  [v side]
  (let [opp (opponent side)
        own (get v side)
        their (get v opp)
        credit-diff (- (:credit own 0) (:credit their 0))
        agenda-diff (- (:agenda-point own 0) (:agenda-point their 0))
        card-advantage (- (card-count own) (card-count their))
        click-diff (- (:click own 0) (:click their 0))
        servers (servers-threat v)
        threat-level (reduce + 0 (map :estimated-cost (vals servers)))
        threat-term (if (= side :corp) threat-level (- threat-level))
        score (+ credit-diff
                 (* AGENDA-WEIGHT agenda-diff)
                 card-advantage
                 click-diff
                 threat-term)]
    {:credit-diff credit-diff
     :agenda-diff agenda-diff
     :card-advantage card-advantage
     :click-diff click-diff
     :servers servers
     :threat-level threat-level
     :score score}))

(defn evaluate
  "Bewertet `state` aus Sicht von `side` (:corp oder :runner) — dünner
  Wrapper um evaluate-view (siehe dort), berechnet nur die View. Symmetrisch:
  (evaluate state :corp) und (evaluate state :runner) summieren sich in
  ihrem :score zu 0."
  [state side]
  (evaluate-view (view/view-for state side) side))
```

- [ ] **Step 4: Test ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.eval-test`
Expected: PASS, alle Tests (15 total) grün.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/eval.clj test/clj/bot/eval_test.clj
git commit -m "$(cat <<'EOF'
refactor(bot): eval.clj — view-basierte oeffentliche API fuer Bot-Konsumenten

servers-threat oeffentlich gemacht, evaluate-view (view direkt statt state)
ergaenzt; evaluate/2 ist jetzt ein duenner Wrapper darum. Bereitet
bot.heuristic-corp vor, das nur eine View besitzt, nie rohen state.
EOF
)"
```

---

## Task 4: `bot.legal` — Rez außerhalb von Runs + öffentliches `server-name`

**Files:**
- Modify: `src/clj/bot/legal.clj:10-17` (server-name), `src/clj/bot/legal.clj:70-88` (corp-click-actions)
- Test: `test/clj/bot/legal_test.clj`

**Interfaces:**
- Produces: `bot.legal/server-name` (öffentlich, `zone-kw -> "HQ"|"R&D"|"Archives"|"Server N"`), `bot.legal/turn-actions` bietet jetzt zusätzlich `"rez"`-Aktionen für jede unrezzte installierte Corp-Karte während der normalen Aktionsphase (nicht nur während Runs).

- [ ] **Step 1: Failing tests schreiben**

Ans Ende von `test/clj/bot/legal_test.clj` anfügen:

```clojure
(deftest rez-ausserhalb-eines-runs-anbieten
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (let [actions (legal/turn-actions (view/view-for state :corp) :corp)
          rez-actions (filter #(= "rez" (:command %)) actions)]
      (is (= 1 (count rez-actions)))
      (is (= "Ice Wall" (get-in (first rez-actions) [:args :card :title]))))))

(deftest rezztes-ice-bekommt-keine-rez-option-mehr
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (let [actions (legal/turn-actions (view/view-for state :corp) :corp)]
      (is (not (contains? (commands-of actions) "rez"))))))

(deftest server-name-ist-oeffentlich
  (is (= "HQ" (legal/server-name :hq)))
  (is (= "Server 3" (legal/server-name :remote3))))
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.legal-test`
Expected: FAIL bei `rez-ausserhalb-eines-runs-anbieten` (keine `"rez"`-Aktion angeboten) und `server-name-ist-oeffentlich` (`Unable to resolve var: legal/server-name`, da noch `defn-`).

- [ ] **Step 3: Implementierung**

In `src/clj/bot/legal.clj`:

1. `server-name` öffentlich machen (Zeile ~10):

```clojure
(defn server-name
  "View-Zone-Keyword (:hq :rd :archives :remoteN) -> Servername für click-run."
  [zone-kw]
  (case zone-kw
    :hq "HQ"
    :rd "R&D"
    :archives "Archives"
    (str "Server " (subs (name zone-kw) (count "remote")))))
```

2. In `corp-click-actions` nach der `ability-actions`-Zeile eine Rez-Option ergänzen:

```clojure
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
     (for [c installed :when (not (:rezzed c))]
       (action "rez" {:card c} (str "rez " (or (:title c) "facedown card"))))
     (when (pos? credits)
       (for [c installed :when (advanceable? c)]
         (action "advance" {:card c} (str "advance " (or (:title c) "facedown card")))))
     (for [c installed :when (scoreable? c)]
       (action "score" {:card c} (str "score " (:title c))))
     [(action "purge" nil "purge virus counters")]
     (when (and runner-tagged? (>= credits 2))
       [(action "trash-resource" nil "trash a runner resource")]))))
```

(Keine Credit-Vorprüfung nötig — unbezahlbare Rez-Versuche werden von der Engine als No-Op behandelt, gleiches Muster wie `advance`/`score`.)

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.legal-test`
Expected: PASS, alle Tests (11 total) grün.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/legal.clj test/clj/bot/legal_test.clj
git commit -m "$(cat <<'EOF'
feat(bot): legal.clj — Rez ausserhalb von Runs anbieten, server-name oeffentlich

Corp kann installierte, unrezzte Karten jetzt auch waehrend der normalen
Aktionsphase rezzen (bisher nur als Teil von run-actions waehrend eines
Runs) -- Voraussetzung fuer die Econ-Asset-Rez-Regel des Heuristik-Bots.
server-name wird oeffentlich, damit Bot-Konsumenten Zone-Keywords in
Servernamen uebersetzen koennen.
EOF
)"
```

---

## Task 5: `bot.heuristic-corp` — Skeleton, Mulligan-Heuristik, Roster

**Files:**
- Create: `src/clj/bot/heuristic_corp.clj`
- Create: `test/clj/bot/heuristic_corp_test.clj`
- Modify: `src/clj/bot/roster.clj`
- Modify: `test/clj/bot/roster_test.clj`

**Interfaces:**
- Consumes: `bot.protocol/Bot`, `bot.random/random-bot`, `bot.legal`, `bot.eval` (noch ungenutzt, erst ab Task 6).
- Produces: `bot.heuristic-corp/heuristic-corp-bot` (Konstruktor, `[seed] -> HeuristicCorpBot`), registriert unter `bot.roster/difficulties` als `"heuristic"`.

- [ ] **Step 1: Failing tests schreiben**

`test/clj/bot/heuristic_corp_test.clj` neu anlegen:

```clojure
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

(deftest decide-delegiert-noch-vollstaendig-an-random-v1-skeleton
  (do-game
    (new-game)
    (let [bot (hc/heuristic-corp-bot 1)
          v (view/view-for state :corp)
          actions (legal/turn-actions v :corp)
          decision (bp/decide bot v actions)]
      (is (some #{(:action decision)} actions))
      (is (str/includes? (:reason decision) "random-bot")))))

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
```

Ans Ende von `test/clj/bot/roster_test.clj` in `difficulty-registry` (bzw. als neuer Test) ergänzen:

```clojure
(deftest heuristic-difficulty-registriert
  (is (roster/difficulty? "heuristic"))
  (is (satisfies? bp/Bot (roster/make-bot "heuristic")))
  (is (= "Bot (Heuristic)" (roster/bot-username "heuristic"))))
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: FAIL — Namespace `bot.heuristic-corp` existiert nicht.

Run: `lein kaocha --focus bot.roster-test`
Expected: FAIL bei `heuristic-difficulty-registriert` — `"heuristic"` ist nicht registriert.

- [ ] **Step 3: Implementierung**

`src/clj/bot/heuristic_corp.clj` neu anlegen:

```clojure
(ns bot.heuristic-corp
  "Schwierigkeitsgrad 'heuristic': regelbasiertes Corp-Playbook, siehe
  Design-Spec docs/superpowers/specs/2026-09-01-heuristic-corp-bot-design.md.
  Baut auf bot.eval (Bedrohungsschätzung) und bot.legal (angebotene
  Aktionen/Prompt-Optionen) auf. Wählt ausschließlich aus den von
  legal-actions/prompt-options gelieferten Optionen (bot.protocol-Vertrag) —
  keine eigene Options-Fabrikation, keine Regel-Nachbildung. Ein
  eingebetteter bot.random-Bot beantwortet alles, was das Playbook (noch)
  nicht abdeckt."
  (:require
   [bot.eval :as eval]
   [bot.legal :as legal]
   [bot.protocol :as bp]
   [bot.random :as random]))

;; --- Konstanten (justierbar) ---

(def MULLIGAN-ECON-CARDS
  "Fixe, gateway-corp-spezifische Econ-Liste für die Mulligan-Heuristik
  (siehe Design-Spec Teil 2 — YAGNI, analog zur Seamless-Launch-Erkennung
  in einer späteren Regel: der Bot spielt ausschließlich das
  System-Gateway-Starterdeck)."
  #{"Hedge Fund" "Government Subsidy" "Nico Campaign" "Regolith Mining License"})

;; --- Mulligan ---

(defn- mulligan-decision
  [view options]
  (let [hand (get-in view [:corp :hand])
        ice-count (count (filter #(= "ICE" (:type %)) hand))
        econ-count (count (filter #(contains? MULLIGAN-ECON-CARDS (:title %)) hand))
        label (if (and (zero? ice-count) (zero? econ-count)) "Mulligan" "Keep")]
    {:option (first (filter #(= label (:label %)) options))
     :reason (str "heuristic-corp: Mulligan-Check -> Ice=" ice-count " Econ=" econ-count " -> " label)}))

;; --- Bot ---

(defrecord HeuristicCorpBot [random-delegate]
  bp/Bot
  (decide [_ view legal-actions]
    (bp/decide random-delegate view legal-actions))
  (on-prompt [_ view prompt options]
    (if (= :mulligan (:prompt-type prompt))
      (mulligan-decision view options)
      (bp/on-prompt random-delegate view prompt options))))

(defn heuristic-corp-bot
  "Baut einen seedbaren Heuristik-Corp-Bot. `seed` steuert nur den
  eingebetteten Random-Delegate (für Prompts/Fallbacks ohne eigene Regel) —
  das Playbook selbst ist deterministisch."
  [seed]
  (->HeuristicCorpBot (random/random-bot seed)))
```

In `src/clj/bot/roster.clj`:

```clojure
(ns bot.roster
  "Difficulty-Registry + Bot-Player-Konstruktion für Web-Lobbys.
  Bewusst OHNE web.*-Abhängigkeiten: web.lobby konsumiert diesen Namespace,
  die Web-Laufzeit (bot.seat) liegt eine Schicht darüber."
  (:require
   [bot.cards :as cards]
   [bot.heuristic-corp :as heuristic-corp]
   [bot.random :as random]
   [clojure.string :as str]))

(def difficulties
  "Registry Schwierigkeitsgrad -> Factory (0-arity, liefert bot.protocol/Bot)."
  {"random" #(random/random-bot (.nextLong (java.util.Random.)))
   "heuristic" #(heuristic-corp/heuristic-corp-bot (.nextLong (java.util.Random.)))})
```

(Rest von `roster.clj` unverändert.)

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: PASS (5 Tests).

Run: `lein kaocha --focus bot.roster-test`
Expected: PASS (4 Tests).

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/heuristic_corp.clj test/clj/bot/heuristic_corp_test.clj \
        src/clj/bot/roster.clj test/clj/bot/roster_test.clj
git commit -m "$(cat <<'EOF'
feat(bot): heuristic-corp Skeleton — Mulligan-Heuristik, Random-Delegation

Neuer Schwierigkeitsgrad 'heuristic': decide delegiert vorerst komplett an
einen eingebetteten Random-Bot, on-prompt beantwortet den Mulligan-Prompt
selbst (Hand ohne Ice und ohne Econ -> Mulligan), alle anderen Prompts
delegieren ebenfalls. In bot.roster registriert.
EOF
)"
```

---

## Task 6: Regel 1+2 — Ice installieren (Zentralserver + Scoring-Remote)

**Files:**
- Modify: `src/clj/bot/heuristic_corp.clj`
- Test: `test/clj/bot/heuristic_corp_test.clj`

**Interfaces:**
- Consumes: `bot.game-runner/decide-one!` (öffentlich, für Integrationstests, die eine echte Aktion+Folge-Prompt End-to-End treiben).
- Produces: `HeuristicCorpBot/decide` installiert jetzt Ice in ungeschützte Zentralserver (Priorität HQ > R&D > Archives) oder in eine Scoring-Remote (neu oder bestehend); `on-prompt` beantwortet den dabei entstehenden Server-Wahl-Prompt korrekt.

- [ ] **Step 1: Failing tests schreiben**

Ans Ende von `test/clj/bot/heuristic_corp_test.clj` anfügen (`bot.game-runner`/`game.core` sind bereits seit Task 5 requiret):

```clojure
(deftest regel-1-zentralserver-icen-prioritaet-hq
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (let [bot (hc/heuristic-corp-bot 1)]
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
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: FAIL bei allen drei neuen Tests — `decide` delegiert noch vollständig an `random-delegate`, installiert also nicht gezielt Ice in HQ/Remote.

- [ ] **Step 3: Implementierung**

In `src/clj/bot/heuristic_corp.clj`, `MULLIGAN-ECON-CARDS` um folgende Konstante und View-Helper ergänzen (direkt danach einfügen):

```clojure
(def CENTRAL-ICE-PRIORITY
  "Reihenfolge, in der ungeschützte Zentralserver geict werden (Regel 1)."
  [:hq :rd :archives])

(def ASSUMED-RUNNER-INCOME-PER-TURN
  "Sicherheitspuffer in Credits: wird auf den sichtbaren Runner-Credit-Stand
  aufgeschlagen, bevor ein Server als 'sicher genug' für Agenda-Install/
  -Advance gilt (der Runner verdient VOR seinem nächsten Zug noch Geld —
  siehe Design-Spec Teil 2)."
  4)

;; --- View-Helper ---

(defn- corp-servers [view] (get-in view [:corp :servers]))
(defn- server-ices [view zone] (:ices (get (corp-servers view) zone)))
(defn- server-content [view zone] (:content (get (corp-servers view) zone)))
(defn- remote-zone? [zone] (not (contains? #{:hq :rd :archives} zone)))
(defn- agenda-card? [c] (= "Agenda" (:type c)))
(defn- runner-credit [view]
  (+ (get-in view [:runner :credit] 0) (get-in view [:runner :run-credit] 0)))

(defn- central-needing-ice [view]
  (first (filter #(empty? (server-ices view %)) CENTRAL-ICE-PRIORITY)))

(defn- scoring-remote-zone
  "Auswahl-Heuristik: erst Remote mit Agenda drin, sonst Remote mit >=1 Ice
  ohne Agenda, sonst nil (Design-Spec Teil 2 — Single-Remote-Fokus, YAGNI)."
  [view]
  (let [remotes (filter (fn [[z _]] (remote-zone? z)) (corp-servers view))]
    (or (ffirst (filter (fn [[_ sv]] (some agenda-card? (:content sv))) remotes))
        (ffirst (filter (fn [[_ sv]] (and (seq (:ices sv)) (not-any? agenda-card? (:content sv)))) remotes)))))

(defn- remote-has-agenda? [view zone]
  (boolean (some agenda-card? (server-content view zone))))

(defn- server-threat-for [view zone]
  (get (eval/servers-threat view) zone))

(defn- safe-for-commitment?
  "Ist `zone` (mit Sicherheitspuffer!) für eine mehrzügige Verpflichtung
  (Agenda-Install/-Advance) sicher genug? Siehe ASSUMED-RUNNER-INCOME-PER-
  TURN — ein Vergleich gegen den AKTUELLEN Runner-Credit-Stand würde die
  Bedrohung systematisch unterschätzen (der Runner verdient nach dem
  Corp-Zug noch Geld)."
  [view zone]
  (boolean
   (when-let [threat (server-threat-for view zone)]
     (> (:estimated-cost threat) (+ (runner-credit view) ASSUMED-RUNNER-INCOME-PER-TURN)))))

(defn- ice-install-target
  "Wohin als nächstes Ice installiert werden soll: Zone-Keyword eines
  Zentralservers, Zone-Keyword eines bestehenden, noch unsicheren Scoring-
  Remotes, `:new-remote` für einen frischen Remote, oder nil (kein
  Ice-Install nötig)."
  [view]
  (or (central-needing-ice view)
      (let [zone (scoring-remote-zone view)]
        (cond
          (nil? zone) :new-remote
          (and (not (remote-has-agenda? view zone))
               (not (safe-for-commitment? view zone)))
          zone))))

(defn- server-label [target]
  (if (= :new-remote target) "New remote" (legal/server-name target)))

;; --- legal-actions-Helper ---

(defn- first-play-of-type [legal-actions type-str]
  (first (filter #(and (= "play" (:command %)) (= type-str (get-in % [:args :card :type])))
                 legal-actions)))

;; --- Regel 1+2: Ice installieren ---

(defn- try-install-ice
  [view legal-actions]
  (when-let [target (ice-install-target view)]
    (when-let [act (first-play-of-type legal-actions "ICE")]
      {:action act
       :reason (str "heuristic-corp: Regel "
                    (if (keyword? target) "2 (Scoring-Remote aufbauen)" "1 (Zentralserver icen)")
                    " -> " (server-label target) ", installiere " (get-in act [:args :card :title]))})))

;; --- Prompt-Routing: Server-Wahl ---

(defn- select-target-server
  "Server-Name-String für den Install-Prompt von `card` — nil, wenn `card`
  gerade nicht Teil einer aktiven Ice-Install-Entscheidung ist."
  [view card]
  (when (= "ICE" (:type card))
    (some-> (ice-install-target view) server-label)))

(defn- choose-by-label [options label reason]
  (when-let [opt (first (filter #(= label (:label %)) options))]
    {:option opt :reason reason}))
```

Dann den `HeuristicCorpBot`-Record vollständig ersetzen:

```clojure
(defrecord HeuristicCorpBot [random-delegate]
  bp/Bot
  (decide [_ view legal-actions]
    (or (try-install-ice view legal-actions)
        (bp/decide random-delegate view legal-actions)))
  (on-prompt [_ view prompt options]
    (if (= :mulligan (:prompt-type prompt))
      (mulligan-decision view options)
      (or (when-let [label (select-target-server view (:card prompt))]
            (choose-by-label options label
                              (str "heuristic-corp: Server-Wahl fuer " (:title (:card prompt)) " -> " label)))
          (bp/on-prompt random-delegate view prompt options)))))
```

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: PASS (8 Tests).

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/heuristic_corp.clj test/clj/bot/heuristic_corp_test.clj
git commit -m "$(cat <<'EOF'
feat(bot): heuristic-corp Regel 1+2 — Zentralserver icen, Scoring-Remote aufbauen

decide installiert Ice priorisiert in ungeschuetzte Zentralserver (HQ >
R&D > Archives), danach in eine (neue oder bestehende, noch unsichere)
Scoring-Remote. on-prompt beantwortet den dabei entstehenden
Server-Wahl-Prompt anhand derselben Zielauswahl-Funktion (stateless
Neuberechnung statt geteiltem Zustand zwischen decide und on-prompt).
EOF
)"
```

---

## Task 7: Regel 3 — Agenda platzieren (mit Sicherheitspuffer)

**Files:**
- Modify: `src/clj/bot/heuristic_corp.clj`
- Test: `test/clj/bot/heuristic_corp_test.clj`

**Interfaces:**
- Produces: `HeuristicCorpBot/decide` installiert eine Agenda in die Scoring-Remote, wenn diese (mit Puffer!) sicher genug ist; `on-prompt` beantwortet auch den dabei entstehenden Server-Wahl-Prompt.

- [ ] **Step 1: Failing tests schreiben**

Ans Ende von `test/clj/bot/heuristic_corp_test.clj` anfügen:

```clojure
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
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: FAIL bei `regel-3-agenda-platzieren-wenn-sicher` (Agenda wird nicht installiert, `decide` delegiert stattdessen an random oder greift fälschlich Regel 2).

- [ ] **Step 3: Implementierung**

In `src/clj/bot/heuristic_corp.clj` nach `try-install-ice` folgende Funktion ergänzen:

```clojure
(defn- agenda-install-target
  [view]
  (when-let [zone (scoring-remote-zone view)]
    (when (and (seq (server-ices view zone))
               (not (remote-has-agenda? view zone))
               (safe-for-commitment? view zone))
      zone)))

;; --- Regel 3: Agenda platzieren ---

(defn- try-install-agenda
  [view legal-actions]
  (when-let [zone (agenda-install-target view)]
    (when-let [act (first-play-of-type legal-actions "Agenda")]
      (let [threat (server-threat-for view zone)]
        {:action act
         :reason (str "heuristic-corp: Regel 3 (Agenda platzieren) -> " (server-label zone)
                      " estimated-cost=" (:estimated-cost threat)
                      " <= runner-credit(" (runner-credit view) ")+buffer("
                      ASSUMED-RUNNER-INCOME-PER-TURN ") -> sicher, installiere "
                      (get-in act [:args :card :title]))}))))
```

`select-target-server` erweitern, damit es auch Agenda-Installs routet:

```clojure
(defn- select-target-server
  "Server-Name-String für den Install-Prompt von `card` — nil, wenn `card`
  gerade nicht Teil einer aktiven Install-Entscheidung ist."
  [view card]
  (case (:type card)
    "ICE" (some-> (ice-install-target view) server-label)
    "Agenda" (some-> (agenda-install-target view) server-label)
    nil))
```

`decide` im `HeuristicCorpBot`-Record erweitern:

```clojure
  (decide [_ view legal-actions]
    (or (try-install-ice view legal-actions)
        (try-install-agenda view legal-actions)
        (bp/decide random-delegate view legal-actions)))
```

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: PASS (10 Tests).

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/heuristic_corp.clj test/clj/bot/heuristic_corp_test.clj
git commit -m "$(cat <<'EOF'
feat(bot): heuristic-corp Regel 3 — Agenda platzieren mit Sicherheitspuffer

decide installiert eine Agenda nur in eine Scoring-Remote, deren
Bedrohungsschaetzung selbst mit ASSUMED-RUNNER-INCOME-PER-TURN Puffer auf
den Runner-Credit-Stand noch als sicher gilt -- sonst legt Regel 2
weiteres Ice nach statt die Agenda zu verschenken.
EOF
)"
```

---

## Task 8: Regel 4 — Scoren (Score-Linie + normal advancen)

**Files:**
- Modify: `src/clj/bot/heuristic_corp.clj`
- Test: `test/clj/bot/heuristic_corp_test.clj`

**Interfaces:**
- Produces: `HeuristicCorpBot/decide` scort eine fertig advancte Agenda sofort, nutzt "Seamless Launch" für eine abschließbare Score-Linie wenn vorhanden, sonst advanct normal, solange der Server sicher bleibt; `on-prompt` beantwortet den Seamless-Launch-Zielauswahl-Prompt.

- [ ] **Step 1: Failing tests schreiben**

Ans Ende von `test/clj/bot/heuristic_corp_test.clj` anfügen:

```clojure
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
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: FAIL bei allen drei neuen Tests — `decide` kennt Regel 4 noch nicht, delegiert an random.

- [ ] **Step 3: Implementierung**

In `src/clj/bot/heuristic_corp.clj` nach `try-install-agenda` folgende Funktionen ergänzen:

```clojure
(defn- find-legal [legal-actions command pred]
  (first (filter #(and (= command (:command %)) (pred %)) legal-actions)))

(defn- remaining-advancement [agenda]
  (- (or (:current-advancement-requirement agenda) 0)
     (+ (:advance-counter agenda 0) (:extra-advance-counter agenda 0))))

;; --- Regel 4: Scoren ---

(defn- try-score-line
  [view legal-actions]
  (when-let [zone (scoring-remote-zone view)]
    (when (remote-has-agenda? view zone)
      (let [agenda (first (filter agenda-card? (server-content view zone)))
            clicks (get-in view [:corp :click] 0)
            remaining (remaining-advancement agenda)
            score-act (find-legal legal-actions "score" #(= (:title agenda) (get-in % [:args :card :title])))
            advance-act (find-legal legal-actions "advance" #(= (:title agenda) (get-in % [:args :card :title])))
            seamless-act (first-play-of-type legal-actions "Operation")]
        (cond
          score-act
          {:action score-act
           :reason (str "heuristic-corp: Regel 4 (Scoren) -> " (:title agenda) " ist fertig advanced, score")}

          (not (safe-for-commitment? view zone))
          nil

          (and (<= remaining (+ clicks 2))
               seamless-act
               (= "Seamless Launch" (get-in seamless-act [:args :card :title])))
          {:action seamless-act
           :reason (str "heuristic-corp: Regel 4 (Score-Linie) -> Seamless Launch auf "
                        (:title agenda) ", Restadvancement=" remaining " <= Klicks(" clicks ")+2")}

          advance-act
          {:action advance-act
           :reason (str "heuristic-corp: Regel 4 (weiter advancen) -> " (:title agenda)
                        " Restadvancement=" remaining)})))))

;; --- Prompt-Routing: Seamless-Launch-Ziel ---

(defn- select-seamless-target
  [view options]
  (when-let [zone (scoring-remote-zone view)]
    (when-let [agenda (first (filter agenda-card? (server-content view zone)))]
      (first (filter #(and (= :card (:type %)) (= (:cid agenda) (get-in % [:card :cid]))) options)))))
```

Hinweis: `first-play-of-type legal-actions "Operation"` liefert die erste spielbare Operation überhaupt — das reicht hier NUR als Existenzcheck kombiniert mit dem Titel-Vergleich `(= "Seamless Launch" ...)` direkt danach; ist die erste spielbare Operation eine ANDERE als Seamless Launch (z.B. Hedge Fund), greift die `and`-Bedingung nicht und der Zweig scheitert korrekt (fällt zu `advance-act` durch). Für den Fall, dass Seamless Launch UND eine andere Operation gleichzeitig spielbar sind, aber Seamless Launch nicht die erste ist, wird dies in Task 9 (wo `try-econ` sowieso alle Operationen separat behandelt) nicht weiter verfeinert — dokumentierte v1-Grenze (Deck hat ohnehin nur eine Handvoll Operationen).

`decide` erweitern:

```clojure
  (decide [_ view legal-actions]
    (or (try-install-ice view legal-actions)
        (try-install-agenda view legal-actions)
        (try-score-line view legal-actions)
        (bp/decide random-delegate view legal-actions)))
```

`on-prompt` erweitern (Seamless-Launch-Ziel-Routing nach der Server-Wahl-Routing einfügen):

```clojure
  (on-prompt [_ view prompt options]
    (if (= :mulligan (:prompt-type prompt))
      (mulligan-decision view options)
      (or (when-let [label (select-target-server view (:card prompt))]
            (choose-by-label options label
                              (str "heuristic-corp: Server-Wahl fuer " (:title (:card prompt)) " -> " label)))
          (when (= :select (:prompt-type prompt))
            (when-let [opt (select-seamless-target view options)]
              {:option opt :reason (str "heuristic-corp: Seamless-Launch-Ziel -> " (:label opt))}))
          (bp/on-prompt random-delegate view prompt options))))
```

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: PASS (13 Tests).

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/heuristic_corp.clj test/clj/bot/heuristic_corp_test.clj
git commit -m "$(cat <<'EOF'
feat(bot): heuristic-corp Regel 4 — Scoren, Seamless-Launch-Score-Linie

decide scort eine fertig advancte Agenda sofort, erkennt eine abschliessbare
Seamless-Launch-Score-Linie (Restadvancement <= Klicks+2) und spielt sie,
sonst advanct normal solange der Server sicher bleibt. on-prompt zielt
Seamless Launch gezielt auf die Scoring-Remote-Agenda.
EOF
)"
```

---

## Task 9: Regel 5 — Econ + finaler Fallback

**Files:**
- Modify: `src/clj/bot/heuristic_corp.clj`
- Test: `test/clj/bot/heuristic_corp_test.clj`

**Interfaces:**
- Produces: `HeuristicCorpBot/decide` deckt jetzt das komplette Playbook (Regeln 1–6) ab: Econ-Asset installieren → rezzen → Klick-für-Credits-Ability → Econ-Operation spielen → Credit-Klick → Fallback (erste Option), nur noch für tatsächlich nicht abgedeckte Prompts (nicht mehr für `decide`) wird an `random-delegate` delegiert.

- [ ] **Step 1: Failing tests schreiben**

Ans Ende von `test/clj/bot/heuristic_corp_test.clj` anfügen:

```clojure
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
    (new-game {:corp {:hand ["Hedge Fund"] :credits 0}})
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
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: FAIL bei `regel-5-1`, `regel-5-2`, `regel-5-3`, `regel-5-4` (kein Econ-Playbook, `decide` delegiert an random — bei `regel-5-5` ist der Ausgang zufällig abhängig vom Random-Seed, ebenfalls potenziell FAIL).

- [ ] **Step 3: Implementierung**

In `src/clj/bot/heuristic_corp.clj` `MULLIGAN-ECON-CARDS` um zwei weitere Konstanten ergänzen (direkt danach):

```clojure
(def ECON-ASSET-CARDS
  #{"Regolith Mining License" "Nico Campaign"})

(def ECON-CLICK-ABILITY-CARDS
  "Econ-Assets mit einer manuellen Klick-Ability, die einem generischen
  1-Credit-Klick vorgezogen wird (Regel 5.3)."
  #{"Regolith Mining License"})

(def ECON-OPERATION-CARDS
  #{"Hedge Fund" "Government Subsidy"})
```

`first-play-of-type` um eine zweite Variante ergänzen (danach einfügen):

```clojure
(defn- first-play-of-titles [legal-actions titles]
  (first (filter #(and (= "play" (:command %)) (contains? titles (get-in % [:args :card :title])))
                 legal-actions)))
```

Nach `select-seamless-target` (Ende der Datei vor dem Record) folgenden Block ergänzen:

```clojure
;; --- Regel 5: Econ ---

(defn- try-install-econ-asset
  [_view legal-actions]
  (when-let [act (first-play-of-titles legal-actions ECON-ASSET-CARDS)]
    {:action act
     :reason (str "heuristic-corp: Regel 5.1 (Econ-Asset installieren) -> "
                  (get-in act [:args :card :title]))}))

(defn- installed-unrezzed-econ-asset [view]
  (->> (vals (corp-servers view))
       (mapcat :content)
       (filter #(and (contains? ECON-ASSET-CARDS (:title %)) (not (:rezzed %))))
       first))

(defn- try-rez-econ-asset
  [view legal-actions]
  (when-let [asset (installed-unrezzed-econ-asset view)]
    (when-let [act (find-legal legal-actions "rez" #(= (:cid asset) (get-in % [:args :card :cid])))]
      {:action act :reason (str "heuristic-corp: Regel 5.2 (Econ-Asset rezzen) -> " (:title asset))})))

(defn- try-econ-click-ability
  [_view legal-actions]
  (when-let [act (find-legal legal-actions "ability"
                             #(contains? ECON-CLICK-ABILITY-CARDS (get-in % [:args :card :title])))]
    {:action act :reason (str "heuristic-corp: Regel 5.3 (Klick-fuer-Credits) -> " (:label act))}))

(defn- try-play-econ-operation
  [_view legal-actions]
  (when-let [act (first-play-of-titles legal-actions ECON-OPERATION-CARDS)]
    {:action act :reason (str "heuristic-corp: Regel 5.4 (Econ-Operation spielen) -> "
                              (get-in act [:args :card :title]))}))

(defn- try-econ
  [view legal-actions]
  (or (try-install-econ-asset view legal-actions)
      (try-rez-econ-asset view legal-actions)
      (try-econ-click-ability view legal-actions)
      (try-play-econ-operation view legal-actions)
      (when-let [act (find-legal legal-actions "credit" (constantly true))]
        {:action act :reason "heuristic-corp: Regel 5.5 (Klick fuer Credit)"})))
```

`select-target-server` (aus Task 6/7) um einen dritten Fall erweitern — ohne das würde der Server-Wahl-Prompt für einen Econ-Asset-Install an `random-delegate` durchgereicht, der (sobald irgendwann bereits ein Remote existiert) den Asset zufällig in die Scoring-Remote statt in einen frischen Remote setzen könnte:

```clojure
(defn- select-target-server
  "Server-Name-String für den Install-Prompt von `card` — nil, wenn `card`
  gerade nicht Teil einer aktiven Install-Entscheidung ist."
  [view card]
  (case (:type card)
    "ICE" (some-> (ice-install-target view) server-label)
    "Agenda" (some-> (agenda-install-target view) server-label)
    "Asset" (when (contains? ECON-ASSET-CARDS (:title card)) "New remote")
    nil))
```

`decide` final zusammenführen (Regel 6 = letzte Option von `legal-actions` als Fallback, statt weiter an random zu delegieren — `try-econ` deckt jeden Fall ab, in dem noch Klicks übrig sind, da `"credit"` immer legal ist solange Klicks da sind; bleiben 0 Klicks, ist `legal-actions` ohnehin nur `[end-turn]`):

```clojure
  (decide [_ view legal-actions]
    (or (try-install-ice view legal-actions)
        (try-install-agenda view legal-actions)
        (try-score-line view legal-actions)
        (try-econ view legal-actions)
        {:action (first legal-actions)
         :reason "heuristic-corp: Regel 6 (Fallback) -> keine Regel griff, erste Option"}))
```

`random-delegate` wird jetzt nur noch von `on-prompt` genutzt (nicht mehr von `decide`) — das Feld bleibt im Record, keine Änderung an `on-prompt` in diesem Task nötig.

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: PASS (18 Tests). Bei `regel-5-4-econ-operation-spielen`: falls der tatsächliche Hedge-Fund-Nettoertrag von der Testerwartung abweicht (Karten-Balance kann sich geändert haben), den Test anhand des tatsächlichen `lein kaocha`-Fehlschlags auf den realen Wert korrigieren, bevor fortgefahren wird — Hauptaussage des Tests ("Operation wurde gespielt, Hand leer") bleibt unabhängig vom exakten Credit-Betrag gültig.

- [ ] **Step 5: Commit**

```bash
git add src/clj/bot/heuristic_corp.clj test/clj/bot/heuristic_corp_test.clj
git commit -m "$(cat <<'EOF'
feat(bot): heuristic-corp Regel 5 — Econ, kompletter decide-Dispatch

Econ-Playbook: Asset installieren -> rezzen -> Klick-fuer-Credits-Ability
(Regolith) -> Econ-Operation spielen -> generischer Credit-Klick. decide
deckt jetzt Regeln 1-6 komplett selbst ab (Regel 6 = letzte angebotene
Option als Fallback); random-delegate wird nur noch fuer Prompts genutzt,
die keine der Playbook-Regeln beantwortet.
EOF
)"
```

---

## Task 10: Rez-Entscheidung im Run-Fenster

**Files:**
- Modify: `src/clj/bot/heuristic_corp.clj`
- Test: `test/clj/bot/heuristic_corp_test.clj`

**Interfaces:**
- Produces: `HeuristicCorpBot/decide` rezzt während eines Runs das gerade angegangene Ice, wenn bezahlbar (siehe Spec-Korrektur zur Rez-Entscheidung).

- [ ] **Step 1: Failing tests schreiben**

Ans Ende von `test/clj/bot/heuristic_corp_test.clj` anfügen:

```clojure
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
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (swap! state assoc-in [:corp :credit] 0)
    (take-credits state :corp)
    (run-on state "HQ")
    (let [bot (hc/heuristic-corp-bot 1)]
      (game-runner/decide-one! {:state state :side :corp :kind :run :bot bot})
      (is (not (rezzed? (get-ice state :hq 0)))))))
```

- [ ] **Step 2: Tests ausführen, Fehlschlag verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: FAIL bei `rez-waehrend-eines-runs-wenn-bezahlbar` — `decide` kennt `(:run view)` noch nicht als eigenen Fall, `try-install-ice`/`try-install-agenda`/`try-score-line`/`try-econ` greifen während eines Runs nicht (keine passenden `"play"`/`"score"`/`"advance"`/`"credit"`-Optionen in `run-actions`), also delegiert `decide` faktisch an `random-delegate` → nicht deterministisch rezzt.

- [ ] **Step 3: Implementierung**

In `src/clj/bot/heuristic_corp.clj` vor dem `HeuristicCorpBot`-Record folgende Funktion ergänzen:

```clojure
;; --- Rez-Entscheidung im Run-Fenster ---

(defn- rez-decision
  "Rez lohnt sich immer, wenn bezahlbar: unrezztes Ice schützt nichts (nur
  rezztes Ice feuert Subroutinen), ein bereits laufender Run bietet keinen
  Vorteil durch Zurückhalten (kein Bluffing-Repertoire in v1 — siehe
  Design-Spec, Korrektur-Absatz zur Rez-Entscheidung: ein Vorher/Nachher-
  Vergleich über bot.eval aus Corp-Sicht wäre degeneriert, weil die Corp
  ihre eigenen Ice-Werte immer kennt)."
  [view legal-actions]
  (if-let [act (find-legal legal-actions "rez" (constantly true))]
    (let [ice (get-in act [:args :card])
          rez-cost (or (:cost ice) 0)
          corp-credit (get-in view [:corp :credit] 0)]
      (if (<= rez-cost corp-credit)
        {:action act
         :reason (str "heuristic-corp: Rez -> " (:title ice) " rez-cost=" rez-cost
                      " <= corp-credit(" corp-credit "), Subroutinen sollen wirken")}
        {:action (find-legal legal-actions "continue" (constantly true))
         :reason (str "heuristic-corp: kein Rez -> " (:title ice) " rez-cost=" rez-cost
                      " > corp-credit(" corp-credit ")")}))
    {:action (first legal-actions)
     :reason "heuristic-corp: Rez-Fenster ohne Rez-Option, erste angebotene Option"}))
```

`decide` erweitern (Run-Fall zuerst prüfen, danach unverändert das Playbook aus Task 9):

```clojure
  (decide [_ view legal-actions]
    (if (:run view)
      (rez-decision view legal-actions)
      (or (try-install-ice view legal-actions)
          (try-install-agenda view legal-actions)
          (try-score-line view legal-actions)
          (try-econ view legal-actions)
          {:action (first legal-actions)
           :reason "heuristic-corp: Regel 6 (Fallback) -> keine Regel griff, erste Option"})))
```

- [ ] **Step 4: Tests ausführen, Erfolg verifizieren**

Run: `lein kaocha --focus bot.heuristic-corp-test`
Expected: PASS (20 Tests).

- [ ] **Step 5: Volle Bot-Suite laufen lassen**

Run: `lein kaocha --focus bot.eval-test && lein kaocha --focus bot.legal-test && lein kaocha --focus bot.heuristic-corp-test && lein kaocha --focus bot.roster-test`
Expected: alle PASS.

- [ ] **Step 6: Commit**

```bash
git add src/clj/bot/heuristic_corp.clj test/clj/bot/heuristic_corp_test.clj
git commit -m "$(cat <<'EOF'
feat(bot): heuristic-corp Rez-Entscheidung — rezzen wenn bezahlbar

Waehrend eines Runs rezzt der Bot das angegangene Ice, sobald er es sich
leisten kann (sonst 'continue'). Playbook fuer Schritt 7a damit komplett:
Regel 1-6 (Klickphase) + Rez-Fenster (Run-Phase).
EOF
)"
```

---

## Task 11: `bot.sim` — Schwierigkeitsgrad je Seite wählbar

**Files:**
- Modify: `src/clj/bot/sim.clj`
- Test: kein separater Test — `bot.sim` hat aktuell keine eigene Testdatei; Verifikation erfolgt in Task 12 über einen echten Lauf (CLI-Tool, kein Unit-Test-Kandidat).

**Interfaces:**
- Consumes: `bot.roster/make-bot` (existiert bereits), `bot.random/random-bot` (existiert bereits).
- Produces: `bin/bot-sim` akzeptiert `--corp-bot random|heuristic` und `--runner-bot random|heuristic` (Default beide `random`, unverändertes Verhalten ohne die neuen Flags).

- [ ] **Step 1: Implementierung**

In `src/clj/bot/sim.clj`:

1. Require um `bot.roster` ergänzen:

```clojure
  (:require
   [bot.cards :as cards]
   [bot.game-runner :as game-runner]
   [bot.random :as random]
   [bot.roster :as roster]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))
```

2. `run-one` so ändern, dass es `corp-bot`/`runner-bot`-Difficulty-Strings statt fest verdrahteter `random/random-bot`-Aufrufe nimmt. `bot.roster/difficulties`s Random-Factory ist NICHT seedbar (`(.nextLong (java.util.Random.))`) — für den Sim-Runner bleibt Determinismus über den Partie-Seed Pflicht (siehe Docstring des Namespace), deshalb wird `"random"` weiterhin direkt seedbar über `bot.random/random-bot` gebaut, nur `"heuristic"` geht über einen neuen, ebenfalls seedbaren Konstruktor:

```clojure
(defn- bot-for [difficulty seed]
  (case difficulty
    "heuristic" (bot.heuristic-corp/heuristic-corp-bot seed)
    (random/random-bot seed)))
```

Require entsprechend um `[bot.heuristic-corp :as heuristic-corp]` ergänzen und `bot-for` darüber ansprechen (`heuristic-corp/heuristic-corp-bot` statt vollqualifiziert):

```clojure
(defn- bot-for [difficulty seed]
  (case difficulty
    "heuristic" (heuristic-corp/heuristic-corp-bot seed)
    (random/random-bot seed)))
```

3. `run-one` anpassen:

```clojure
(defn run-one
  "Eine Partie mit festem Seed. Ergebnis-Map:
  {:seed :outcome (:completed | :step-cap | :stuck-prompt | :exception)
   :turns :steps :winner :error :error-data}"
  [{:keys [seed corp-deck runner-deck max-steps log-path corp-difficulty runner-difficulty]
    :or {corp-difficulty "random" runner-difficulty "random"}}]
  (when log-path
    (io/delete-file (io/file log-path) true))
  (let [rng (java.util.Random. (long seed))]
    (try
      (let [result (with-redefs-fn (seeded-shuffle-fns rng)
                     #(game-runner/run-game
                       {:corp-bot (bot-for corp-difficulty (* 2 seed))
                        :runner-bot (bot-for runner-difficulty (inc (* 2 seed)))
                        :corp-deck corp-deck
                        :runner-deck runner-deck
                        :max-steps max-steps
                        :log-path log-path}))]
        {:seed seed
         :outcome (if (:completed? result) :completed :step-cap)
         :turns (:turn result)
         :steps (:steps result)
         :winner (:winner result)})
      (catch clojure.lang.ExceptionInfo e
        {:seed seed
         :outcome (if (str/includes? (ex-message e) "Keine ausführbare Option")
                    :stuck-prompt
                    :exception)
         :error (ex-message e)
         :error-data (ex-data e)})
      (catch Throwable t
        {:seed seed
         :outcome :exception
         :error (str (.getName (class t)) ": " (.getMessage t))}))))
```

4. `cli-options` um die zwei neuen Flags ergänzen (nach `--seed`):

```clojure
(def cli-options
  [["-n" "--games N" "Anzahl Partien"
    :default 5 :parse-fn #(Long/parseLong %)]
   ["-s" "--seed BASE" "Basis-Seed; Partie i läuft mit Seed BASE+i"
    :default 42 :parse-fn #(Long/parseLong %)]
   [nil "--corp-bot DIFFICULTY" "Schwierigkeitsgrad der Corp (random|heuristic)"
    :default "random" :validate [roster/difficulty? "unbekannter Schwierigkeitsgrad"]]
   [nil "--runner-bot DIFFICULTY" "Schwierigkeitsgrad des Runners (random|heuristic)"
    :default "random" :validate [roster/difficulty? "unbekannter Schwierigkeitsgrad"]]
   [nil "--corp-deck FILE" "EDN-Datei {:identity \"...\" :cards [[\"Titel\" Anzahl] ...]} (Default: System-Gateway-Corp-Starterdeck)"]
   [nil "--runner-deck FILE" "dito für den Runner (Default: System-Gateway-Runner-Starterdeck)"]
   [nil "--max-steps N" "Step-Cap pro Partie"
    :default 5000 :parse-fn #(Long/parseLong %)]
   [nil "--log-dir DIR" "Verzeichnis für Decision-Logs (eine EDN-Datei pro Seed)"
    :default "logs/bot-sim"]
   ["-h" "--help"]])
```

5. `run-sim` so anpassen, dass es die zwei neuen Optionen an `run-one` durchreicht:

```clojure
(defn run-sim
  "Spielt n Partien und liefert {:results [...] :report String}."
  [{:keys [games seed corp-deck runner-deck max-steps log-dir corp-bot runner-bot]}]
  (cards/load-all-cards!)
  (let [corp-deck (if corp-deck (load-deck corp-deck) cards/gateway-corp)
        runner-deck (if runner-deck (load-deck runner-deck) cards/gateway-runner)
        results (vec
                 (for [i (range games)
                       :let [game-seed (+ seed i)]]
                   (do (println (format "Partie %d/%d (Seed %d) ..." (inc i) games game-seed))
                       (let [r (run-one {:seed game-seed
                                         :corp-deck corp-deck
                                         :runner-deck runner-deck
                                         :max-steps max-steps
                                         :log-path (str log-dir "/game-" game-seed ".edn")
                                         :corp-difficulty (or corp-bot "random")
                                         :runner-difficulty (or runner-bot "random")})]
                         (println "  ->" (name (:outcome r))
                                  (str (when (:turns r) (str "Züge=" (:turns r)))
                                       (when (:winner r) (str " Sieger=" (name (:winner r))))
                                       (when (:error r) (str " " (:error r)))))
                         r))))]
    {:results results
     :report (report results)}))
```

(Rest der Datei — `report`, `load-deck`, `-main` — bleibt unverändert; `-main` reicht `options` bereits vollständig an `run-sim` durch, `:corp-bot`/`:runner-bot` kommen automatisch über `parse-opts` mit.)

- [ ] **Step 2: Rauchtest per REPL/CLI**

Run: `lein run -m bot.sim 2 --corp-bot heuristic --runner-bot random --seed 9001`
Expected: Exit-Code 0 (oder zumindest kein sofortiger Absturz/`ClassCastException`), Report zeigt 2 abgeschlossene Partien und `Siege: {...}` mit Einträgen für `:corp`/`:runner`.

Run: `lein run -m bot.sim 2` (ohne die neuen Flags, altes Verhalten)
Expected: unverändert lauffähig (beide Seiten Random), gleiches Verhalten wie vor diesem Task.

- [ ] **Step 3: Commit**

```bash
git add src/clj/bot/sim.clj
git commit -m "$(cat <<'EOF'
feat(bot): sim.clj — Schwierigkeitsgrad je Seite waehlbar (--corp-bot/--runner-bot)

bin/bot-sim kann jetzt Heuristik- gegen Random-Bot antreten lassen (Default
weiterhin random vs. random, unveraendertes Verhalten ohne die neuen
Flags). Voraussetzung fuer den 200-Partien-Vergleich aus der Design-Spec.
EOF
)"
```

---

## Task 12: 200-Partien-Benchmark verifizieren

**Files:** keine Code-Änderung — Verifikationsschritt.

**Interfaces:** nutzt `bin/bot-sim` aus Task 11.

- [ ] **Step 1: Vollen Sim-Lauf ausführen**

Run: `bin/bot-sim 200 --corp-bot heuristic --runner-bot random --seed 1 --log-dir logs/bot-sim/heuristic-vs-random`

(Bei fehlendem `bin/lein`/Docker-Setup: `lein run -m bot.sim 200 --corp-bot heuristic --runner-bot random --seed 1 --log-dir logs/bot-sim/heuristic-vs-random`.)

Erwartete Laufzeit: abhängig von Maschine, ggf. mehrere Minuten (200 vollständige Partien) — bei Bedarf `timeout` entsprechend hoch setzen bzw. im Hintergrund laufen lassen.

- [ ] **Step 2: Report gegen die Erfolgsschwelle prüfen**

Aus dem Report (`Siege: {...}`) die Anzahl `:corp`-Siege ablesen.

Erfolgskriterium (aus der Spec, "Ziel"-Abschnitt): **≥ 140/200 Corp-Siege**.

- Erreicht: Ergebnis in einer Zeile in `docs/superpowers/specs/2026-09-01-heuristic-corp-bot-design.md` unter "Ziel" ergänzen (z.B. "Ergebnis 2026-XX-XX: 156/200"), committen.
- Nicht erreicht: NICHT als abgeschlossen melden. Stattdessen: `logs/bot-sim/heuristic-vs-random/game-*.edn` der verlorenen Partien stichprobenartig durchsehen (jede Zeile ist ein Decision-Log-Eintrag mit `:reason`), die häufigste Fehlentscheidung identifizieren, dem User berichten und gemeinsam entscheiden, ob ein Playbook-Parameter (z.B. `ASSUMED-RUNNER-INCOME-PER-TURN`) nachjustiert wird oder ein neuer Plan-Task nötig ist. Kein Playbook-Rewrite ohne Rücksprache — die Regeln selbst sind Spec-approved.

- [ ] **Step 3: Ergebnis dokumentieren (nur bei Erfolg) und committen**

```bash
git add docs/superpowers/specs/2026-09-01-heuristic-corp-bot-design.md
git commit -m "$(cat <<'EOF'
docs(bot): 200-Partien-Benchmark-Ergebnis Heuristik-Corp vs. Random-Runner

Ergebnis in der Design-Spec dokumentiert (Ziel: >= 140/200, Baseline
Random-Corp 121/200).
EOF
)"
```

---

## Self-Review (durchgeführt beim Schreiben dieses Plans)

**Spec-Abdeckung:**
- Teil 1 (Breaker-Matching, echte Break-Kosten) → Task 1, 2. ✓
- View-basierte API-Notwendigkeit (nicht explizit in der Spec, aber zwingende Voraussetzung für Teil 2) → Task 3. ✓
- Regel 1 (Zentralserver icen) → Task 6. ✓
- Regel 2 (Scoring-Remote aufbauen, inkl. "weiteres Ice bei unsicherem Remote ohne Agenda") → Task 6. ✓
- Regel 3 (Agenda platzieren, Sicherheitspuffer) → Task 7. ✓
- Regel 4 (Scoren, Score-Linie via Seamless Launch) → Task 8. ✓
- Regel 5 (Econ: Asset install/rez/Klick-Ability/Operation/Credit-Klick) → Task 9. ✓
- Regel 6 (Fallback) → Task 9 (Teil des `decide`-Zusammenschlusses). ✓
- Rez-Entscheidung → Task 10 (mit dokumentierter Spec-Korrektur). ✓
- on-prompt-Verhalten (Mulligan-Heuristik + Delegation) → Task 5. ✓
- Decision-Log-Pflicht → jede Regel-Funktion in Task 6–10 trägt `:reason`. ✓
- Testing-Abschnitt (Playbook-Tests + Sicherheitspuffer-Test + Mulligan-Tests + 200-Partien-Lauf) → Task 6–10 (Unit-/Integrationstests), Task 12 (Benchmark). ✓
- Nicht-Ziele (kein Multi-Remote, kein Notverkauf, kein generischer Fast-Advance-Solver, grober Fallback bei exotischen Kosten) → bewusst NICHT implementiert, in den jeweiligen Tasks (2, 6, 8) durch Kommentare/Docstrings referenziert. ✓
- `bot.legal`-Erweiterung (Rez außerhalb von Runs, `server-name` öffentlich) war in der Spec nicht explizit benannt, aber zwingend für Regel 5.2 nötig (in der Spec als "Umsetzungsdetail im Implementierungsplan" vorgesehen) → Task 4. ✓
- `bot.sim`-CLI-Erweiterung war in der Spec implizit über den Testing-Abschnitt gefordert ("bot.sim-Lauf") → Task 11. ✓

**Platzhalter-Scan:** keine TBD/TODO, jeder Code-Block ist vollständig, jeder Testschritt zeigt den tatsächlichen Testcode.

**Typkonsistenz:** `ice-install-target`/`agenda-install-target`/`select-target-server`/`select-seamless-target`/`try-*`/`rez-decision` werden in Task 6–10 konsistent benannt und wiederverwendet (keine Umbenennung zwischen Tasks). `bot.eval/servers-threat` (öffentlich seit Task 3) wird ab Task 6 in `server-threat-for` konsumiert — Signatur `[view]` durchgängig gleich.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-02-heuristic-corp-bot.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
