# Heuristik-Corp-Bot (Schwierigkeitsgrad "heuristic")

Datum: 2026-09-01
Status: Entwurf (Design-Review mit User)

## Ziel

Zweite Corp-Schwierigkeitsstufe neben `bot.random`: ein regelbasierter
Heuristik-Bot mit priorisiertem Playbook (Zentralserver icen → Scoring-Remote
aufbauen → Agenda scoren wenn sicher → sonst Econ). Baut auf `bot.eval`
(Zustands-/Bedrohungsbewertung) und dem bestehenden Bot-Protokoll
(`bot.protocol/Bot`, `bot.legal`, `bot.view`) auf — keine neue
Regel-Nachbildung, nur Entscheidungslogik über die vorhandene Engine-API
(Projektregel).

Messbares Ziel: Heuristik-Corp gegen Random-Runner gewinnt über 200
Sim-Partien **≥ 140/200 (70 %)**, klar über der Baseline Random-Corp vs.
Random-Runner (121/200 = 60,5 %). Schwelle fest in dieser Spec, nicht erst
im Plan — sonst ist am Ende nicht objektiv entscheidbar, ob das Playbook
etwas gebracht hat.

Voraussetzung (zuerst umzusetzen, siehe [[bot-eval-v1-backlog]]): `bot.eval`s
Server-Bedrohungsschätzung nutzt aktuell (a) den global stärksten
installierten Icebreaker ohne Typ-Matching und (b) einen groben
Stärke-Delta-Proxy statt echter Break-Kosten. Beides muss vor dem
Playbook-Bau gefixt werden, weil Schritt 3/4 des Playbooks direkt auf einer
belastbaren Kosten-Schätzung aufbauen.

## Teil 1: `bot.eval` — echte Durchbruchskosten pro Server

### Problem

`best-breaker-strength` nimmt den stärksten installierten Icebreaker,
unabhängig vom Ice-Typ. `raw-ice-cost` ist ein Stärke-Delta-Proxy
(`max 1 (inc (- ice-strength breaker-strength))`), keine echten Credits.
Für eine Spielentscheidung ("lohnt sich Install/Advance hier") reicht das
nicht — ein Fracter gegen Sentry-Ice zählt aktuell fälschlich als Schutz.

### Lösung: Matching + statische Kosten-Introspektion

Ice-Subtyp und Icebreaker-Subtyp sind bereits Teil der zensierten View
(`:subtypes`, Teil von `card-keys` in `game.core.diffs`) — kein neuer
Informations-Leak nötig für Schritt (a).

Für Schritt (b) (echte Credit-Kosten) reicht die View nicht: die
Ability-Zusammenfassung (`ability-keys` in `game.core.diffs`) reduziert eine
Ability auf `:label`/`:playable`/... — `:break-cost`, `:break`, `:breaks`,
`:pump` werden herausgefiltert. Diese Werte sind aber **statische, gedruckte
Karteninformation** (identisch für jede Kopie einer Karte, genau das, was auf
der Karte steht) — kein verstecktes Laufzeit-Wissen. Sie werden über
`game.core.card-defs/card-def` gelesen, aufgerufen mit `{:title (:title c)}`
für Karten, deren Titel in der View bereits öffentlich sichtbar ist
(installierte eigene/gegnerische Karten sind immer öffentlich; unrezztes
gegnerisches Ice bleibt wie bisher beim `UNKNOWN-ICE-*`-Default, weil dort
schon der Titel fehlt). `card-def` liefert nur Daten (Maps/Vektoren), es wird
nichts ausgeführt oder simuliert — reine Introspektion, keine
Regel-Nachbildung.

Neue Funktion `breach-cost` (Ersatz für `raw-ice-cost` bei bekanntem Ice):

Für ein bekanntes Ice mit sichtbarem Subtyp (`Barrier`/`Code Gate`/`Sentry`/
ggf. andere) und sichtbarer Subroutine-Anzahl (`count (:subroutines ice)`):

1. Für jeden installierten Icebreaker: lies dessen statische `:abilities`
   via `card-def`. Filtere Break-Abilities, deren `:breaks`-Set den
   Ice-Subtyp enthält (oder `#{"All"}`).
2. Breaker ohne passende Break-Ability scheidet für dieses Ice aus
   (kandidiert nicht).
3. Für Kandidaten: fehlt Stärke (`ice-strength > breaker-strength`), such
   die günstigste Pump-Ability (`:pump`-Betrag, `ceil(diff / pump) *
   pump-cost`). Keine nutzbare Pump-Ability bei Stärke-Rückstand ⇒ Kandidat
   scheidet aus.
4. Break-Kosten: `ceil(subs-count / break-n) * break-cost` (`break-n = 0`
   bedeutet "beliebig viele in einer Zahlung" ⇒ 1 Zahlung reicht).
5. Gesamtkosten = Pump-Kosten + Break-Kosten. Minimum über alle Kandidaten
   ist die Schätzung für dieses Ice.
6. Kein Kandidat, ODER eine beteiligte Kostenkomponente ist kein reiner
   `:credit`-Betrag (X-Cost, Virus-Counter, dynamischer `:pump-bonus` wie bei
   Unity) ⇒ Fallback auf die bisherige Stärke-Delta-Schätzung
   (`raw-ice-cost`) für dieses Ice. Kein Crash, dokumentierte Vereinfachung
   (die vier Gateway-Runner-Starter-Breaker Cleaver/Mayfly/Unity/Carmen
   decken den Normalfall rein kreditbasiert ab — geprüft).

`server-threat`/`servers-threat` bleiben strukturell gleich (Summe über
`ices`), nur der Kern-Kostenrechner pro Ice wird ersetzt. Bestehende
`ice-known?`/`UNKNOWN-ICE-*`/`UNAFFORDABLE-ICE-DISCOUNT`-Logik für unrezztes
gegnerisches Ice bleibt unverändert (dort betrifft das Problem gar nicht,
weil dort ohnehin nur geraten wird).

Bestehende Tests in `eval_test.clj` müssen weiter grün bleiben (sie prüfen
Ice Wall — 0 Subroutinen? Nein, Ice Wall hat 1 Subroutine "End the run",
Barrier-Typ — die Tests installieren Corroder, der Barrier NICHT bricht
(Corroder ist Fracter → passt zu Barrier tatsächlich). Kein
Testkonflikt erwartet, wird in der Umsetzung verifiziert).

## Teil 2: Heuristik-Corp-Playbook

Neuer Namespace `src/clj/bot/heuristic_corp.clj`, `defrecord` analog
`bot.random/RandomBot`, implementiert `bot.protocol/Bot`. Registrierung in
`bot.roster/difficulties` unter `"heuristic"`.

### Konstanten (justierbar, analog `bot.eval`)

- `CENTRAL-ICE-PRIORITY` — Reihenfolge HQ > R&D > Archives für Schritt 1.
- `ASSUMED-RUNNER-INCOME-PER-TURN` — Sicherheitspuffer in Credits (Default
  4), der auf den sichtbaren Runner-Credit-Stand aufgeschlagen wird, bevor
  ein Server als "sicher genug" für Install/Advance gilt. Begründung: der
  Runner ist nach dem Corp-Zug am Zug und verdient dort noch Geld — ein
  Vergleich gegen den AKTUELLEN Credit-Stand würde die Bedrohung
  systematisch unterschätzen und Agenden in Remotes jammen, die der Runner
  im Folgezug bequem knackt.

`safe-for-commitment? [server-threat runner-credit]` — eigene Helper-Fn im
neuen Namespace (nicht in `bot.eval`, das bleibt ein reiner, pufferloser
Realtime-Snapshot für alle Konsumenten): `(> (:estimated-cost server-threat)
(+ runner-credit ASSUMED-RUNNER-INCOME-PER-TURN))`. Diese Fn ersetzt die
direkte Nutzung von `:runner-can-afford?` aus `bot.eval` überall dort, wo der
Bot eine MEHRZÜGIGE Verpflichtung eingeht (Agenda-Install, Advance) — nicht
für die reine Zustandsbewertung.

### Playbook (in Prioritätsreihenfolge, pro `decide`-Aufruf neu ausgewertet)

1. **Zentralserver icen**: existiert ein Zentralserver (HQ/R&D/Archives)
   ohne Ice UND liegt eine Ice-Karte in der Hand → installiere sie dort
   (Priorität nach `CENTRAL-ICE-PRIORITY`).
2. **Scoring-Remote aufbauen**: "Scoring-Remote" = erst Remote mit Agenda
   drin, sonst Remote mit ≥1 Ice ohne Agenda, sonst keiner (dann wird beim
   Icen ein NEUER Remote eröffnet). Single-Remote-Fokus (YAGNI, kein
   Multi-Remote-Sequencing in v1). Trifft zu, wenn entweder (a) noch kein
   Remote mit Ice existiert, ODER (b) der bestehende Scoring-Remote noch
   keine Agenda trägt UND `safe-for-commitment?` dafür noch falsch ist (mit
   Puffer noch nicht sicher genug) → installiere weiteres Ice dort (bzw.
   eröffne den Remote im Fall (a)).
3. **Agenda platzieren**: Scoring-Remote hat ≥1 Ice, noch keine Agenda, UND
   `safe-for-commitment?` dafür ist wahr (mit Puffer!) UND eine Agenda liegt
   in der Hand → installiere sie dort. (Ist der Puffer-Check falsch, greift
   bereits Schritt 2 Fall (b) statt dieser Regel.)
4. **Scoren**: Agenda liegt in der Scoring-Remote.
   - Prüfe zuerst eine **abschließbare Score-Linie diesen Zug**: benötigter
     Restadvancement ≤ (verbleibende Klicks als Advance) + (2, falls
     "Seamless Launch" in der Hand UND die Agenda nicht in diesem Zug
     installiert wurde — `place-advancement-counter` verlangt eine Karte,
     die nicht `:this-turn` installiert ist). Existiert eine solche Linie →
     durchziehen (Seamless Launch spielen falls Teil der Linie, dann
     advancen, dann scoren sobald `current-advancement-requirement`
     erreicht ist).
   - Sonst, falls weiterhin `safe-for-commitment?` wahr → normal weiter
     advancen (ein Klick pro `decide`-Aufruf).
   - Sonst (Remote nicht mehr sicher genug) → nicht weiter advancen, fällt
     durch zu Schritt 5 (Econ) für diesen `decide`-Aufruf. Kein Notverkauf/
     Trash der Agenda in v1 (YAGNI — Restrisiko wird in Erwägungen
     dokumentiert, kein Blocker für den Sim-Vergleich).
5. **Econ** (Reihenfolge):
   1. Uninstallierter Econ-Asset (Regolith Mining License / Nico Campaign)
      in der Hand UND bezahlbar → installieren.
   2. Installierter, unrezzter Econ-Asset UND bezahlbar → rezzen.
   3. Beste verfügbare "Klick-für-Credits"-Ability unter den legalen
      Aktionen (z. B. Regolith-Take-3-Ability) → nutzen, bevorzugt vor dem
      generischen 1-Credit-Klick (Vergleich über den Label-Text ist zu
      fragil; stattdessen: Ability-Aktionen aus `installed-corp-cards`
      filtern auf `:cost`-freie/click-only Actions mit erkennbarem
      Credit-Ertrag — Umsetzungsdetail im Implementierungsplan).
   4. Econ-Operation aus der Hand spielbar (z. B. Hedge Fund) → spielen.
   5. Sonst → `credit`-Klick (Basis-Aktion, immer legal).
6. **Fallback**: keine der Regeln 1–5 trifft zu (z. B. keine Klicks mehr) →
   `end-turn`, analog `bot.legal/turn-actions`.

### Rez-Entscheidung (unabhängig von der Klick-Reihenfolge oben)

Korrektur gegenüber dem ursprünglichen Entwurf (gefunden beim Schreiben des
Implementierungsplans): ein Vorher/Nachher-Vergleich über
`bot.eval/servers-threat` aus der Sicht der Corp selbst ist degeneriert —
die Corp kennt die echten Werte ihres eigenen Ice immer, mit oder ohne Rez
(`ice-known?` ist für die Corp auf eigene Karten immer wahr). Der
`UNAFFORDABLE-ICE-DISCOUNT`-Abschlag in `bot.eval` gilt nur, wenn Rez-Kosten
UND Rez-Status unbekannt sind — das betrifft ausschließlich die Sicht des
RUNNERS auf gegnerisches Ice, nie die Sicht der Corp auf ihr eigenes. Ein
Vorher/Nachher-`evaluate`-Aufruf aus Corp-Sicht liefert deshalb immer
`Differenz = 0` und wäre kein sinnvolles Kriterium.

Stattdessen (äquivalent zum eigentlichen Ziel "rez lohnt sich, wenn
bezahlbar"): Bei jedem `decide`-Aufruf während eines Runs, bei dem die Corp
am Zug ist und aktuelles Ice unrezzt ist (`bot.legal/run-actions` bietet
dann `"rez"` als legale Aktion an) → rezzen, wenn `(:cost ice) <=
corp-credit`. Begründung: unrezztes Ice schützt nichts (Subroutinen feuern
nur, wenn rezzt), ein bereits laufender Run gegen diesen Server bietet keinen
Vorteil durch Zurückhalten — das Affordability-Fenster JETZT zu nutzen ist
nie schlechter als warten (kein Bluffing-Repertoire in v1, siehe
Nicht-Ziele). Nicht bezahlbar → `"continue"`.

### `on-prompt`-Verhalten

Das Bot-Protokoll (`bot.protocol/Bot`) verlangt neben `decide` auch
`on-prompt` (Mulligan, Discard-Auswahl, Select-/Trace-/Titel-Prompts etc.).
Für v1 bekommt NUR der Mulligan-Prompt eine eigene Heuristik — alle anderen
Prompts delegieren an die bestehende `bot.random`-Logik (uniform zufällige
Auswahl aus den legalen Optionen). `HeuristicCorpBot` hält dafür intern
einen eingebetteten `random-bot` (Komposition, kein Re-Implementieren der
Auswahllogik) und ruft dessen `on-prompt` auf, wenn `(:prompt-type prompt)`
nicht `:mulligan` ist.

Mulligan-Erkennung: `(= :mulligan (:prompt-type prompt))` (siehe
`game.core.set-up/keep-hand+mulligan`, Optionen sind Buttons mit `:label`
`"Keep"`/`"Mulligan"`). Heuristik: Hand enthält weder eine Ice-Karte
(`:type "ICE"`) noch eine Econ-Karte aus einer festen, gateway-corp-
spezifischen Liste (`MULLIGAN-ECON-CARDS` = `#{"Hedge Fund"
"Government Subsidy" "Nico Campaign" "Regolith Mining License"}` — alle
vier sind Teil des fixen Starterdecks, siehe `bot.cards/gateway-corp`,
YAGNI wie bei der Seamless-Launch-Erkennung) → `"Mulligan"` wählen; sonst
`"Keep"`. `:reason` benennt die gezählten Ice-/Econ-Karten in der
Starthand.

### Decision-Log (Projektregel)

Jede `decide`/`on-prompt`-Rückgabe trägt `:reason` mit: welche Playbook-Regel
gegriffen hat (Nummer + Kurzname), die dafür ausschlaggebenden Zahlen
(`:estimated-cost`, `:runner-credit`, Puffer, Restadvancement o.ä.) — analog
zum Format in `bot.random` (`"random-bot: uniform zufällig, 1 von N"`), nur
inhaltlich reichhaltiger, z. B. `"heuristic-corp: Regel 3 (Agenda platzieren)
— Server remote1 estimated-cost=6 > runner-credit(4)+buffer(4)=8? nein →
sicher, installiere Priority Requisition"`.

## Testing

- `bot.eval`-Erweiterung: neue Tests in `test/clj/bot/eval_test.clj`
  (Breaker-Typ-Mismatch senkt Bedrohung NICHT, echte Break-Kosten
  unterscheiden sich je nach Sub-Anzahl/Pump-Bedarf, Fallback bei
  Nicht-Credit-Kosten crasht nicht).
- Neuer `test/clj/bot/heuristic_corp_test.clj`: pro Playbook-Regel ein
  `do-game`-Test (Regel greift bei erfüllten Vorbedingungen, greift NICHT
  bei fehlenden), plus der Sicherheitspuffer-Test (Agenda wird NICHT
  installiert, wenn `estimated-cost` zwischen aktuellem Runner-Credit und
  Runner-Credit+Puffer liegt) UND ein Mulligan-Test je Fall (leere Hand ohne
  Ice/Econ → Mulligan; Hand mit Ice ODER Econ → Keep).
- Abschluss: `bot.sim`-Lauf (200 Partien Heuristik-Corp vs. Random-Runner),
  Erfolgskriterium ist die feste Schwelle aus "Ziel" oben (**≥ 140/200**
  Corp-Siege).

## Nicht-Ziele (YAGNI, v1)

- Kein Multi-Remote-Sequencing, keine Trap-/Ambush-Karten-Logik, kein
  Runner-Deck-spezifisches Bluffing über mehrere Server.
- Kein Notverkauf/Trash einer nicht mehr sicheren Agenda.
- Kein generischer Fast-Advance-Solver — nur die eine im Deck vorhandene
  Seamless-Launch-Linie wird erkannt, kein Suchen nach anderen Kombos.
- `bot.eval`-Fallback für exotische Break-Kosten (X-Cost, Virus-Counter,
  dynamische Pump-Boni) bleibt die grobe Stärke-Delta-Schätzung — kein
  vollständiger Kosten-Resolver für jede Karte im Pool.
