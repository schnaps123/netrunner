# Heuristik-Corp: Verteidigungspriorität nach Wert × Durchlässigkeit

Datum: 2026-09-02
Status: Design-Entwurf, NICHT umgesetzt — reine Planung, siehe User-Anweisung
"erst als Design vorschlagen, noch nicht bauen".

## Ziel

Löst die aktuelle Verteidigungspriorität (Regel 1: feste Basis-Reihenfolge
`[:hq :rd :archives]` + reaktiver Breach-Zähler, siehe
[[heuristic-corp-bot-design]]) ab durch eine Priorisierung nach **erwartetem
Schaden mal Durchlässigkeit**: der Bot soll von Anfang an wissen, dass ein
R&D voller Agenden gefährlicher ist als eine leere HQ — nicht erst reagieren,
nachdem der Runner dreimal durchgekommen ist.

Der Breach-Zähler (`recent-breach-count`, siehe heutiger Fix) bleibt
bestehen, wird aber vom Hauptmechanismus zu einem zusätzlichen
Gewichtungsfaktor herabgestuft.

## Server-Wert (aus legitimem Corp-Wissen)

Alle Werte kommen aus der eigenen View (`get-in view [:corp ...]`) plus der
bekannten eigenen Decklist (`bot.cards/gateway-corp` — der Bot spielt
nachweislich nur dieses eine Deck, etablierte Vereinfachung im Code, siehe
`MULLIGAN-ECON-CARDS`). Keine Schummelei: die Corp kennt ihre eigene Hand,
ihr Archiv, ihre Remotes, und kann die Agenda-Dichte im Rest-Deck aus der
eigenen Decklist minus aller bereits anderswo sichtbaren Agenden ausrechnen
— das macht jeder gute menschliche Spieler im Kopf.

### Trash-weight-Konstanten (Regelkorrektur 2026-09-02)

Beim Zugriff auf HQ/R&D kann der Runner nur Karten trashen, die
Trash-Kosten haben — also **Assets und Upgrades**. Ice und Operations
lassen sich nicht wegtrashen, sie werden nur gesehen (tragen nur zum
Info-Term bei, nicht zum Trash-Term). Damit:

```
trash-weight(Asset)     = 2.0
trash-weight(Upgrade)   = 1.5
trash-weight(ICE)       = 0     (nur Info-Term)
trash-weight(Operation) = 0     (nur Info-Term)
```

Ergebnis: eine Hand voll Ice und Hedge Funds ist für den Runner kaum
lohnend (HQ-Wert bleibt niedrig, nur der kleine Info-Term bleibt übrig);
eine Hand mit Regolith Mining License und Nico Campaign ist es sehr wohl.

### HQ-Wert

```
agenda-term(HQ) = agenda-points-in-hand / hand-size
trash-term(HQ)  = Σ trash-weight(card) für nicht-Agenda-Karten in Hand / hand-size
info-term(HQ)   = HQ-BASE-INFO-VALUE   (konstant > 0, wenn hand-size > 0, sonst 0)

value(HQ) = agenda-term + trash-term + info-term
```

### R&D-Wert (gleiche Struktur, auf Rest-Deck projiziert)

```
remaining-agendas(R&D) = (Agenda-Kopien in gateway-corp) - (sichtbare Agenda-Kopien überall:
                          Hand + Archives + Score-Areas + alle Remote-Contents)
remaining-cards(R&D)   = (:corp :deck-count) aus der View
remaining-trash-pool   = Σ trash-weight(Kartentyp) über alle Decklist-Karten, die noch NICHT
                          irgendwo sichtbar sind (nur Asset/Upgrade zählen, siehe oben)

agenda-term(R&D) = remaining-agendas / remaining-cards
trash-term(R&D)  = remaining-trash-pool / remaining-cards
info-term(R&D)   = RD-BASE-INFO-VALUE   (kleiner als HQ-BASE-INFO-VALUE)

value(R&D) = agenda-term + trash-term + info-term
```

Reine Mengenrechnung gegen die bekannte, fixe `gateway-corp`-Liste — kein
Wahrscheinlichkeitsmodell nötig.

### Archives-Wert

Voll öffentlich (Discard ist für beide Seiten sichtbar), kein
Herleitungsbedarf:

```
value(Archives)     = agenda-points-in-archives + Σ trash-weight(card) für Asset/Upgrade-Inhalt
info-term(Archives) = 0   (nichts Neues zu erfahren, ist schon öffentlich)
```

Meist ≈ 0, außer eine Agenda landete durch Milling/Discard dort — dann
schnellt der Wert hoch.

### Remote-Wert

Ebenfalls voll bekannt (eigener Server):

```
value(remote) = agenda-points-in-remote + Σ trash-weight(card) für installierten, nicht-Agenda-Content
info-term(remote) = REMOTE-INFO-VALUE, wenn Content unrezzt ist (Runner kennt Identität noch nicht), sonst 0
```

### Info-Konstanten

`HQ-BASE-INFO-VALUE` / `RD-BASE-INFO-VALUE` / `REMOTE-INFO-VALUE` sind
klein und frei justierbar, keine tiefere Herleitung. Rangfolge genügt:
**HQ > R&D > Remote-unrezzt**.

## Durchlässigkeit (Permeability, 0..1)

Baut auf bestehendem `bot.eval/servers-threat` (Typ-Matching + echte
Break-Kosten sind da bereits drin), normiert auf einen Score statt reinem
Kostenwert:

```
runner-budget(zone) = runner-credit + runner-run-credit + ASSUMED-RUNNER-INCOME-PER-TURN
permeability(zone)  = clamp(runner-budget / max(1, estimated-cost(zone)), 0, 1)
```

## Kombination + Breach-Gewichtung

```
reinforcement-score(zone) = value(zone) × permeability(zone) × (1 + BREACH-WEIGHT × recent-breach-count(zone))
```

`BREACH-WEIGHT` klein (z.B. 0.15). Das `central-tax-too-low?`-Gate
(`MIN-CENTRAL-TAX-CREDITS`) bleibt als Ja/Nein-Schwelle bestehen, ob
überhaupt reinforced wird; `reinforcement-score` entscheidet nur noch,
**welcher** Server unter den noch-nicht-ausreichend-taxierten gewinnt.

## Benchmark-Methodik — Warnung

**Verteidigungsparameter dürfen NICHT gegen den Random-Runner-Benchmark
getunt werden.** Der Zufalls-Runner bestraft schlechte Verteidigung nicht:
er bricht Subroutinen nicht gezielt, jackt nicht strategisch aus, nutzt
Breaker nicht typgenau ein. Ein Parameter, der im 200-Partien-Benchmark
gegen ihn gut aussieht, kann trotzdem eine schlechte Verteidigung gegen
einen echten (oder heuristischen) Runner sein — die Optimierung würde in
die falsche Richtung ziehen. Der Random-Benchmark taugt für: grobe
Regressionserkennung (Crashes, hängende Partien, Sieganteil-Einbrüche
gegenüber der eigenen Historie), NICHT für Feinabstimmung von
Taxierungsschwellen o.ä. Belastbare Bewertung von Verteidigungsqualität
braucht entweder einen Gegner, der Lücken tatsächlich ausnutzt (Heuristik-
Runner, siehe Schritt 7b in [[heuristic-corp-bot-design]]), oder Bewertung
am Spielbrett durch einen Menschen.

**Statistisches Rauschen bei 200 Partien (2026-09-02, aus dem Regel-2.5-
Vergleich beobachtet):** die Zufallsstreuung der Agenda-Siegzahl liegt bei
200 Partien in der Größenordnung von **±5** — Unterschiede unterhalb von
etwa zehn Siegen zwischen zwei Konfigurationen sind NICHT belastbar
interpretierbar, auch wenn sie auf den ersten Blick nach einem Trend
aussehen. Für feinere Vergleiche (z.B. "hat Parameter X um 3 Siege
geholfen?") reicht ein einzelner 200-Partien-Lauf nicht — nötig ist
entweder deutlich mehr Partien (Rauschen sinkt mit 1/√n) oder ein
Paarvergleich mit IDENTISCHEN Seeds in beiden Konfigurationen (dieselbe
Partie A/B getestet, Differenz statt Rohzahl vergleichen — eliminiert einen
Großteil der Zufallsstreuung, weil beide Läufe an denselben Stellen Glück/
Pech haben). Eine Regel, die sachlich begründet ist (z.B. Regel 2.5:
gezieltes Graben nach der einzigen fehlenden Scoring-Fenster-Zutat ist
eindeutig richtig), bleibt darum auch dann drin, wenn ein einzelner
200-Partien-Lauf keine eindeutige Verbesserung zeigt — der Benchmark
bestätigt oder widerlegt solche Regeln nicht zuverlässig, er dient nur der
groben Regressionserkennung (s.o.).

## Nächste Phase (unmittelbar nach diesem Wertmodell, nicht optional)

**Status 2026-09-02: umgesetzt** (`bot.eval/has-etr-subroutine?` +
`NO-ETR-DISCOUNT`, siehe `test/clj/bot/eval_test.clj`) — direkt gebaut,
nicht nur designt, weil zusammen mit der Archives-Ausnahme (s.u.) ein
konkreter Zielkonflikt aus dem Spielbrett-Feedback aufgelöst werden musste.

**ETR-Klassifikation für Durchlässigkeit — vermutlich wirkungsvoller als
das gesamte Wertmodell oben.** Nicht die volle Subroutine-Textanalyse ist
entscheidend, sondern eine einzige binäre Eigenschaft pro Ice: **hat es
überhaupt eine "End the run"-Subroutine?**

Beispiel aus dem Gateway-Pool: **Tithe hat keine ETR-Subroutine** — der
Runner kann es für 1 Netzschaden einfach durchlaufen, es taxiert praktisch
nicht. Ein Bot, der glaubt, drei Tithe würden R&D verteidigen (aktueller
Stand nach dem heutigen `MIN-CENTRAL-TAX-CREDITS`-Fix: ja, genau dieser
Fall — drei Tithe kosten in Summe zwar >= 5 Credits geschätzt, aber der
Runner tankt den Schaden statt zu zahlen), liegt fundamental falsch.

Diese Klassifikation verändert die Durchlässigkeitsschätzung stärker als
alles andere in diesem Dokument: ein Ice ohne ETR-Sub sollte in
`permeability` nahe 1 bleiben, UNABHÄNGIG von seinen Break-Kosten, sobald
der Runner genug Kartenpuffer hat, den Schaden zu tanken (grobe Schwelle:
Schaden < verbleibende Handkarten, ansonsten Netzschaden-Risiko real).
Statisch aus `card-def`/`:subroutines` lesbar (gedruckte Karteninfo,
gleiches Introspektions-Muster wie `best-breach-cost` in `bot.eval`) — kein
neuer Informations-Leak.

Absichtlich als eigene Phase, nicht Teil dieses Wertmodell-Designs: zwei
unabhängig validierbare Änderungen sollen getrennt gebaut und benchmarkt
werden, sonst lässt sich hinterher nicht zuordnen, welche Änderung welchen
Effekt hatte.

**Zusammen mit der ETR-Klassifikation umgesetzt (2026-09-02): Archives-
Ausnahme.** Löst denselben Zielkonflikt von der anderen Seite: ein Zugriff
auf ein agendafreies Archiv kostet die Corp nichts (echte Netrunner-Regel),
Archives fällt darum aus der Pflichttaxierung (`central-tax-too-low?`)
heraus, solange es keine Agenda trägt (`archives-has-agenda?`, prüft
`[:corp :discard]`). Gibt frühe Klicks zurück, ohne echte Verteidigung zu
schwächen — Gegenstück zur ETR-Klassifikation, die dieselben Klicks
zurückgewinnt, indem sie Ice-Overinvestment vermeidet statt ein drittes
Pflichtziel zu streichen.

## Nicht-Ziele (Wertmodell-Abschnitt oben)

- Wert×Durchlässigkeit-Kombination selbst (Kombination + Breach-Gewichtung)
  — weiterhin reine Planung, nicht umgesetzt.
- Keine volle Subroutine-Effekt-Analyse (Tag vs. Trash-Programm vs.
  sonstiges) über das binäre ETR-Merkmal hinaus (das IST umgesetzt, s.o.).

---

# Erweiterung: Zielmodell statt Prioritätenliste (Scoring-Fenster)

Datum: 2026-09-02 (Ergänzung)
Status: Design-Entwurf, NICHT umgesetzt — reine Planung.

Ersetzt langfristig die reine Prioritätenliste (`decide`s `or`-Kette:
Regel 1 → 2 → 3 → 4 → 5 → 6) durch ein Zielmodell: der Bot bewertet, welche
Voraussetzung fürs Scoren gerade fehlt, und arbeitet gezielt daran, statt
nur die nächste passende Regel in einer festen Liste abzuarbeiten.

## Scoring-Fenster

Ein Scoring-Fenster liegt vor, wenn drei Dinge gleichzeitig gelten:

```
credit-ready?(zone) = corp-credit >= rez-cost-to-open(zone)
tax-ready?(zone)    = estimated-cost(zone) > runner-affordable-budget
agenda-ready?       = agenda-in-hand?
```

`rez-cost-to-open(zone)` = Summe der Rez-Kosten aller noch unrezzten Ice
auf `zone` (konservative Annahme: worst case, der Runner löst in einem
einzigen Run alle aus — genauer wäre "teuerstes einzelnes unrezztes Ice",
offene Frage s.u.). `tax-ready?` ist strukturell `safe-for-commitment?`,
nur nicht mehr an einen einzelnen Boolean gebunden, sondern als eigene
Dimension geführt. `agenda-ready?` = `agenda-in-hand?` (siehe bestehender
Code).

**Ein Zug ohne Agenda in der Hand ist kein Leerlauf, sondern Vorbereitung**
— `agenda-ready?` fehlt, aber `credit-ready?`/`tax-ready?` lassen sich
schon jetzt vorbereiten (Ice bauen, Econ aufbauen), damit das Fenster
sofort entsteht, sobald eine Agenda kommt.

```
scoring-window-gap(zone) = the set of {:credits, :tax, :agenda} that is currently false
```

Der Bot ermittelt die fehlende(n) Dimension(en) und arbeitet an der
höchstpriorisierten. **Offene Frage:** welche Reihenfolge, wenn mehrere
gleichzeitig fehlen (häufig früh im Spiel)? Zwei plausible Kandidaten ohne
klaren Sieger:
- Agenda zuerst (ohne sie ist alles andere ohnehin wertlos — passt zu
  "Vorbereitung ohne Agenda ist trotzdem sinnvoll", aber die Frage ist, WAS
  man vorbereitet, wenn man nicht weiß, ob/wann die Agenda kommt)
- Tax zuerst (längste Vorlaufzeit — Ice bauen dauert mehrere Züge, Credits/
  Agenden kann man kurzfristiger nachholen)

## Econ als Fundament

Rangfolge: dauerhafte Einkommensquellen (Assets mit wiederholt nutzbarer
Ability, z.B. Regolith Mining License) vor einmaligen Operationen (Hedge
Fund) vor dem generischen Credit-Klick (schlechteste Option, nur wenn
nichts Besseres verfügbar/bezahlbar). **Das entspricht weitgehend der
bestehenden Regel-5.1–5.6-Reihenfolge** (Asset installieren → Asset rezzen
→ Klick-Ability → Operation spielen → Ziehen → genereller Klick) — keine
grundlegend neue Erkenntnis, aber die Begründung ändert sich: Credits sind
kein Selbstzweck, sondern müssen im Moment des Bedarfs (Rez-Fenster
während eines Runs) verfügbar sein. Das verbindet Econ direkt mit
`credit-ready?(zone)` oben — der Bot sollte nicht "möglichst viele
Credits" anstreben, sondern "genug Credits für den nächsten erwarteten
Rez-Bedarf", was **keine neue Regel ist, sondern eine neue Zielgröße, gegen
die die bestehenden Econ-Regeln laufen**.

## Taxierung statt Prävention im Spätspiel

Irgendwann hat der Runner genug Geld und Breaker, dass `tax-ready?(zone)`
für KEINEN Server mehr erreichbar ist ("überall reinkommen"). Ab da
verschiebt sich das Ziel: nicht mehr draußen halten, sondern jeden Run so
teuer machen, dass unmittelbar danach kein zweiter mehr bezahlbar ist. Das
Fenster entsteht dann durch die Armut des Runners NACH einem Run, nicht
durch eine unüberwindbare Mauer VOR dem Run.

```
late-game-tax-mode?(zone) = runner-budget "weit über" jedem realistisch erreichbaren estimated-cost(zone)
tax-creates-poverty?(zone) = (runner-credit - estimated-cost(zone)) < SECOND-RUN-THRESHOLD
```

**Offene Fragen:**
- Wie wird `late-game-tax-mode?` konkret erkannt (welcher Schwellenwert,
  welches Verhältnis runner-budget zu maximal erreichbarem
  estimated-cost)?
- Wie groß ist `SECOND-RUN-THRESHOLD` (Mindest-Credits für einen zweiten
  sinnvollen Run — Näherung über die billigste Ice-Durchbruchskosten
  anderswo, oder fixe Konstante)?
- Gilt das pro Server einzeln, oder muss der Bot GLOBAL über alle Server
  hinweg denken (ein Run auf Server A verarmt den Runner auch für Server
  B)?

## Ködern als eigenes Werkzeug

Nicht-Agenden im Scoring-Remote installieren und advancen, die für den
Runner wie eine Agenda AUSSEHEN (gleiche verdeckte, advancierbare
Kartensignatur — der Runner kann ohne Zugriff nicht unterscheiden). Im
Gateway-Pool vor allem **Urtica Cipher**: bestraft Zugriff hart (Credits +
Karten). Ziel: der Runner verausgabt sich an der Falle und ist danach zu
arm/kartenlos, die echte Agenda zu erreichen.

```
bait-value ≈ P(runner greift Köder an) × (Kosten des Zugriffs für den Runner + Urtica-Cipher-Bestrafungswert)
             - Kosten der Corp, den Köder zu installieren/advancen
```

Ohne echtes Wahrscheinlichkeitsmodell vereinfacht als Auslöse-Heuristik:

```
should-bait? = agenda-in-hand? AND (NOT tax-ready?(scoring-zone)) AND (kein besseres Ice installierbar) AND corp-credit >= URTICA-CIPHER-BUFFER
```

d.h. Ködern als bewusste Handlung, wenn die echte Agenda noch nicht sicher
platzierbar ist UND sonst nichts Produktiveres mit den verfügbaren
Ressourcen passiert — kein Ersatz fürs eigentliche Scoring-Fenster, sondern
Zeit-/Ressourcen-Umleitung während der Wartezeit, die den Runner aktiv
schwächt statt nur zu warten.

**Offene Fragen (am unsichersten in diesem ganzen Dokument):**
- Köder im SELBEN Remote wie die echte Agenda platzieren (riskant: ein
  zweiter Zugriff im selben Run könnte die echte Agenda treffen) oder in
  einem SEPARATEN Decoy-Remote (sicherer, kostet aber zusätzliches
  Tempo/Ice für einen ganzen weiteren Server)? Menschliche Netrunner-
  Spieler sind sich hier selbst uneinig — keine offensichtlich richtige
  Antwort.
- Braucht es einen festen `BAIT-VALUE`-Konstanten pro Karte (analog
  `trash-weight`, gateway-spezifisch: Urtica Cipher bekäme einen hohen
  Wert, andere potenzielle Köder niedrigere), oder reicht ein einziger
  globaler Wert für v1?
- Wie erkennt der Bot, dass eine Köder-Investition sich NICHT gelohnt hat
  (Runner ignoriert den Remote komplett) und wann er abbrechen/umlenken
  sollte?

## Nicht-Ziele (diese Erweiterung)

- Keine Implementierung — reine Planung, wie der Rest dieses Dokuments.
- Kein vollständiges Wahrscheinlichkeitsmodell für `bait-value` — nur eine
  grobe Auslöse-Heuristik.
- Keine Aussage, ob dieses Zielmodell die bestehende `or`-Ketten-Struktur
  komplett ersetzt oder als zusätzliche Metaebene darüber läuft — das ist
  selbst eine offene Architekturfrage, die vor der Umsetzung geklärt werden
  müsste.

---

# Erweiterung: Mehrere Remotes statt Single-Remote-Fokus

Datum: 2026-09-03 (Ergänzung)
Status: Design-Entwurf, NICHT umgesetzt — reine Planung, erst nach den vier
Bugfixes aus der zweiten gespielten Partie (Pflicht-Abwurf, Breite-vor-Tiefe,
Remote-Priorität, Geduldsgrenze — siehe Commit-Historie 2026-09-02/03).

## Warum der bisherige Single-Remote-Verzicht eine echte Schwäche ist

`scoring-remote-zone` geht seit v1 durchgängig von GENAU EINEM relevanten
Remote aus (Design-Spec Teil 2, YAGNI). Das war für den ersten funktionierenden
Bot die richtige Vereinfachung, ist aber keine harmlose — sie kostet an vier
unabhängigen Stellen echte Spielstärke:

1. **Econ-Assets stehen nackt.** Regel 5.1 installiert Econ-Assets (Regolith
   Mining License, Nico Campaign) in einen NEUEN, ungeschützten Remote (siehe
   `try-install-econ-asset`-Reason: "ungeschützter neuer Remote -- trivial
   trashbar, kein Schutz geplant" — offen dokumentierter Backlog-Punkt seit
   dem ersten Spielpartie-Fixbatch). Ein eigener, ge-icter Econ-Remote löst
   das direkt, statt einen separaten Schutzmechanismus zu bauen.
2. **Der Runner taxiert nur über Credits, nicht über Zeit.** Mit einem
   einzigen Remote genügt EIN Run, um zu wissen, ob dort etwas Wertvolles
   liegt. Mehrere Remotes zwingen ihn, jeden einzeln zu prüfen — jeder
   Check kostet einen Klick, unabhängig vom Ice dahinter. Das ist Taxierung
   über Zeit/Klicks, eine Dimension, die die aktuelle Credit-zentrierte
   `tax-sufficient?`-Bewertung gar nicht erfasst.
3. **Die Unsicherheit selbst schützt.** Bei einem Remote weiß der Runner:
   "wenn dort was liegt, ist es dort." Bei mehreren muss er raten, WELCHER
   die Agenda trägt — das verbindet sich direkt mit dem oben dokumentierten
   Köder-Konzept (Urtica Cipher in einem Zweit-Remote: die Unsicherheit
   *ist* der Köder-Mechanismus, kein Add-on).
4. **Keine Parallelität.** Aktuell arbeitet der Bot zwangsläufig sequenziell:
   erst den einen Remote fertig bauen/absichern, dann Econ, weil beides um
   denselben Remote konkurriert. Mit getrennten Remotes kann die Corp einen
   Econ-Asset-Server laufen lassen UND gleichzeitig den Scoring-Remote
   vorbereiten — echtes Netrunner-Spiel tut genau das.

## Remote-Rollen

Drei Rollen, jede mit eigenem Ice-Bedarf und eigener Fertigstellungs-Logik:

- **Scoring-Remote** (bisherige alleinige Rolle): trägt die zu scorende
  Agenda. Ice-Bedarf: bis MAX-ICE-PER-SCORING-REMOTE (unverändert, 3),
  Sicherheitsschwelle über `safe-for-commitment?`/Geduldsgrenze wie bisher.
  Genau EINER gleichzeitig aktiv (die eigentliche Agenda wird nicht auf
  mehrere Remotes verteilt).
- **Econ-Remote**: trägt installierte Econ-Assets (ECON-ASSET-CARDS). Ice-
  Bedarf früh **ein bis zwei** Ice — genug, dass ein spontaner Runner-Klick
  nicht kostenlos durchkommt, aber deutlich weniger als der Scoring-Remote,
  weil der erwartete Schaden geringer ist (ein getrashtes Econ-Asset kostet
  Tempo, keine Partie). Mehrere Econ-Assets können denselben Econ-Remote
  teilen (Analogie zu Zentralservern: ein Server, mehrere Karten-Inhalte),
  bevor ein zweiter Econ-Remote gerechtfertigt ist.
- **Köder-Remote** (siehe Köder-Abschnitt oben): trägt eine advancierbare
  Nicht-Agenda mit Bestrafungseffekt (Urtica Cipher). Ice-Bedarf ebenfalls
  früh ein bis zwei — genug, dass der Köder nicht trivial wirkt, aber ohne
  Ice-Investment auf Scoring-Remote-Niveau (der Ertrag ist Ablenkung, nicht
  Punkte). Nur relevant, sobald das Ködern-Werkzeug selbst gebaut wird
  (siehe oben, eigene offene Fragen).

## Wann lohnt sich ein weiterer Remote statt Verstärkung des bestehenden?

Analog zur Breite-vor-Tiefe-Korrektur bei Zentralservern (siehe oben), aber
zwischen ROLLEN statt zwischen Zentralservern:

```
neuer-remote-fuer-rolle?(rolle) =
  KEIN bestehender Remote dieser Rolle existiert
  UND ein Auslöser für diese Rolle liegt vor
    (Scoring: agenda-in-hand? ODER agendas-remaining-in-deck?, wie bisher)
    (Econ: eine Econ-Asset-Karte in der Hand, noch kein Econ-Remote)
    (Köder: should-bait? aus dem Köder-Abschnitt oben)
```

Verstärkung eines BESTEHENDEN Remotes (weiteres Ice) bleibt der Standardfall,
solange dessen Rolle noch nicht abgeschlossen ist (Scoring: unsicher, unter
der Obergrenze; Econ/Köder: unter deren jeweiliger, niedrigerer Obergrenze).
Ein neuer Remote entsteht nur, wenn eine ROLLE noch KEINEN Remote hat — nicht,
weil ein bestehender Remote irgendwann "voll genug" ist. Das verhindert, dass
der Bot beliebig viele Remotes eröffnet, ohne dass jede Eröffnung durch einen
konkreten Bedarf gedeckt ist (dieselbe Breite-vor-Tiefe-Disziplin wie bei
Zentralservern, nur auf Rollen statt auf feste Server angewendet).

## Auswirkung auf bestehenden Code

- **`scoring-remote-zone`** müsste zu einer Rollen-Zuordnung werden
  (`{zone rolle}` oder drei eigene Funktionen `scoring-remote-zone`/
  `econ-remote-zone`/`bait-remote-zone`) statt einer einzelnen Zone. Jeder
  Aufrufer, der aktuell `scoring-remote-zone` für "DEN Remote" hält (u.a.
  `try-score-line`, `agenda-install-target`, `select-seamless-target`,
  `track-remote-stall!`), muss auf die Scoring-Rolle spezifisch verweisen —
  faktisch unverändert, nur umbenannt/präzisiert.
- **Regel 2 (`ice-install-target`/`remote-ice-target`)** müsste die
  Breite-vor-Tiefe-Reihenfolge (siehe oben, Zentralserver-Korrektur) auf
  Remote-ROLLEN erweitern: erst jede benötigte Rolle ihren ersten Remote,
  dann Nachverstärkung — analog zu `central-zero-ice-needing`/
  `central-depth-needing`, aber über Rollen statt über die feste
  `[:hq :rd :archives]`-Liste iteriert.
- **Regel 3 (`try-install-agenda`)** bliebe inhaltlich unverändert (installiert
  weiterhin ausschließlich in die Scoring-Rolle), müsste aber die
  Econ-Install-Logik (aktuell `try-install-econ-asset` mit hartem
  `"New remote"`-Ziel) durch eine rollen-bewusste Zielwahl ersetzen, die
  einen BESTEHENDEN Econ-Remote wiederverwendet, statt bei jedem Econ-Asset
  einen weiteren neuen Remote zu eröffnen.
- **`select-target-server`** (Server-Wahl-Prompt-Routing) müsste für Assets
  zwischen "neuer Econ-Remote" und "bestehender Econ-Remote" unterscheiden,
  nicht mehr pauschal `"New remote"` zurückgeben.

## Nicht-Ziele (diese Erweiterung)

- Keine Implementierung — reine Planung wie der Rest dieses Dokuments.
- Keine Festlegung der genauen Econ-/Köder-Ice-Obergrenzen-Konstanten (nur
  die Größenordnung "ein bis zwei" ist hier skizziert).
- Kein vollständiges Modell für "wie viele Remotes maximal gleichzeitig" —
  in der Praxis vermutlich durch Klick-/Credit-Knappheit natürlich begrenzt,
  aber nicht explizit hergeleitet.
