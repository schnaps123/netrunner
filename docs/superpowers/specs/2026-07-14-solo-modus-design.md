# Solo-Modus: Spiel vs. Bot in der Web-Lobby

Datum: 2026-07-14
Status: abgenommen (Design-Review mit User, 3 Abschnitte)

## Ziel

In der Lobby ist ein Spiel „vs. Bot" erstellbar (Seitenwahl Corp/Runner, Bot-Deck,
Schwierigkeitsgrad — vorerst nur „Random"). Der Bot besetzt serverseitig den zweiten
Seat und reagiert auf dieselben Engine-Zustandsänderungen wie ein menschlicher Client,
mit 1–2 s künstlicher Bedenkzeit. Multiplayer-Funktionalität bleibt unverändert.
Bot-Begründungen erscheinen NICHT im Spiel-Log, sondern werden pro Partie in eine
Datei geschrieben. Bonus: Bot-vs-Bot-Spiele sind erstellbar und als Spectator live
beobachtbar.

## Architekturentscheidung

Gewählt: **Hook nach jeder State-Änderung in `web.game`** (statt `add-watch` auf dem
State-Atom oder eines virtuellen Websocket-Clients). Begründung: deterministisch,
serialisiert über den bestehenden Game-Thread-Pool, verwendet die komplette
`bot.game-runner`-Logik wieder, ein Codepfad für vs-Bot und Bot-vs-Bot. Der Bot sieht
weiterhin nur die zensierte View (`bot.view/view-for`); die Engine bleibt einzige
Regelinstanz (Projektregel).

Verworfen: `add-watch` (feuert mehrfach mitten in Aktionen, Race mit Diff-Versand);
virtueller WS-Client (dupliziert View-Logik, die `bot.view` serverseitig schon löst).

## Abschnitt 1: Lobby & Datenmodell

Neue Lobby-Optionen (`:lobby/create` ?data, serverseitig validiert):

- `:bot-game` — `"vs-bot"` oder `"bot-vs-bot"`; nil = normale Partie (Multiplayer
  unberührt).
- `:side` — bei vs-bot nur `"Corp"`/`"Runner"` (Bot nimmt die Gegenseite).
- `:bot-deck` — Key in ein Registry in `bot.cards`; vorerst nur die eingebauten
  System-Gateway-Starterdecks (`gateway-corp`/`gateway-runner`), automatisch nach
  Seite gewählt.
- `:difficulty` — `"random"` (einziger Wert vorerst; Registry für spätere Grade).

`create-new-lobby`-Erweiterung:

- vs-bot: Ersteller bleibt Player 1 (first-player), Bot-Player wird als zweiter
  Eintrag angehängt: `{:user {:username "Bot (Random)"} :uid nil :side <Gegenseite>
  :bot true :deck <bot.cards-Deck>}`. `:uid nil` ist im bestehenden Sende-Code safe
  (`doseq :when (some? uid)`).
- bot-vs-bot: beide Player sind Bots, der Ersteller landet als Spectator,
  `:allow-spectator true` wird erzwungen. Die Partie startet automatisch beim
  Erstellen (kein Start-Button; `first-player?` würde an der Bot-uid scheitern).
- vs-bot: Start wie gewohnt über den Start-Button des Erstellers. Bot-Deck ist ab
  Erstellung gesetzt; der Mensch wählt sein Deck normal über `:lobby/deck`.

Schutz bestehender Flows:

- `:lobby/join`: Bot-Lobbys sind für fremde Spieler nicht joinbar. `:lobby/watch`
  (Spectator) bleibt erlaubt.
- Verlässt der Mensch die Partie (Leave/Disconnect): Lobby gilt als leer, wenn kein
  menschlicher Player mehr da ist → `close-lobby!`; der Bot-Loop wird gestoppt.
- User-/Deck-Stats (`update-deck-stats`, `update-game-stats`, `push-stats-update`)
  werden für Bot-Partien übersprungen (Bot-User hat kein `:_id`, würde mit nil-Id
  in die users-Collection schreiben).
- `game-started` und `game-finished` laufen für Bot-Partien NORMAL: sie brauchen
  kein User-`:_id` (nur `[:username :emailhash]` via `select-keys`) und tragen die
  Replay-Aufzeichnung — `:history` füllt sich über `update-and-send-diffs!`, durch
  das auch alle Bot-Aktionen laufen. `save-replay` bleibt damit für Bot-Spiele
  voll funktionsfähig; Bot-Partien erscheinen in der Spielhistorie des Menschen.

## Abschnitt 2: Bot-Seat-Laufzeit (`src/clj/bot/seat.clj`, neu)

Hook-Mechanik gegen zyklische Abhängigkeit: `web.game` bekommt ein Hook-Atom
`bot-notify-fn` (default no-op). `bot.seat` registriert sich beim Systemstart
(`web.system`). Der Hook wird aufgerufen: nach `try-start-game`, nach jedem
`update-and-send-diffs!`-Pfad in `:game/action`, nach Rejoin.

Ablauf `notify!`:

1. Lobby kein Bot-Spiel oder Partie vorbei → return.
2. Per-Game-Guard (Atom `:bot-thinking?` in der Lobby-Map): läuft schon ein
   Bot-Loop → return.
3. Sonst Future auf eigenem Thread (NICHT dem Game-Thread — der Sleep würde sonst
   den geteilten Lobby-Pool blockieren):
   - Loop: `bot.game-runner/next-actor` prüfen. Ist die Bot-Seite dran →
     `Thread/sleep (1000 + rand 1000)` (Bedenkzeit als dynamische Var `*think-ms*`,
     damit Tests sie auf 0 binden können), dann EINE Entscheidung auf dem
     Game-Thread der Lobby ausführen (serialisiert mit menschlichen Aktionen),
     Diffs via `update-and-send-diffs!` an die Clients. Weiter, bis ein Mensch dran
     ist oder die Partie vorbei ist.
   - Guard zurücksetzen, danach einmal re-checken (Race: Mensch hat während des
     Zurücksetzens gehandelt).

Entscheidungslogik wird wiederverwendet: die `step!`-Innereien aus
`bot.game-runner` (options-for, apply-choice!, fingerprint, No-Op-Retry) werden in
eine gemeinsame Funktion extrahiert (z.B. `decide-one!`), die Headless-Sim und
Web-Seat beide nutzen. Einziger Unterschied: der Web-Seat wickelt die
State-Mutation in `update-and-send-diffs!`, die Sim ruft `process-action` direkt.
Bot-Input ist immer die zensierte View.

Bedenkzeit: 1–2 s zufällig pro Entscheidung, identisch für vs-bot und bot-vs-bot.
Prompt-Ketten (Mulligan, Selects) laufen sichtbar Schritt für Schritt.

Fehlerbehandlung (Retry, dann concede):

- No-Op erkannt → Option streichen, Bot erneut fragen (wie in der Sim).
- Optionen leer oder Exception → Fehler + Stacktrace ins Decision-Log, dann
  `main/handle-concede` für die Bot-Seite via `update-and-send-diffs!` → Partie
  endet regulär mit Engine-Standard-Systemmeldung.
- bot-vs-bot: gleiches Verhalten, eine Seite concedet.

Bot-Instanzen: pro Partie bei Spielstart erzeugt (`bot.random/random-bot` mit
zufälligem Seed), in der Lobby-Map unter `:bots {:corp <bot> :runner <bot>}`.
Difficulty-Registry: Map `"random"` → Factory-Fn, erweiterbar.

## Abschnitt 3: Frontend, Logging, Tests

Frontend (`nr/new_game.cljs`, `nr/lobby.cljs`/`game_row.cljs`, `pending_game.cljs`):

- Create-Formular: Spieltyp-Radio „Gegen Spieler" (Default, alles wie bisher) /
  „vs. Bot" / „Bot vs. Bot".
- „vs. Bot": Seitenwahl nur Corp/Runner (kein „Any Side"), Dropdown Bot-Deck
  (vorerst ein Eintrag „System Gateway Starter", automatisch Gegenseite), Dropdown
  Schwierigkeit (nur „Random"). Format wird auf `system-gateway` gesetzt.
- „Bot vs. Bot": nur Titel + Bot-Deck/Schwierigkeit; nach Create landet der
  Ersteller direkt als Spectator im laufenden Spiel (Server schickt `:game/start`).
- Lobby-Liste: Bot-Spiele erscheinen normal (Bot-Player „Bot (Random)");
  Join-Button für Fremde ausgeblendet (Server lehnt ohnehin ab), Watch bleibt.
- Gameboard: keine Änderung — Bot ist normaler Player im State; `:game/typing`
  etc. sind wegen nil uid no-ops.

Decision-Log (Pflicht laut Projektregeln):

- `bot.log/append-decision!` wiederverwendet: eine EDN-Datei pro Partie unter
  `logs/bot-games/<gameid>.edn` (im Container `/usr/src/app/logs/`, bewusst nicht
  gemountet).
- Jede Entscheidung: Turn, Phase, Seite, Kind, Optionen, gewählte Aktion,
  `:reason`, No-Op-Flags, Schwierigkeit. Erscheint NICHT im Spiel-Log.
- Fehler-/Concede-Ereignisse ebenfalls dorthin.

Tests (`test/clj/bot/seat_test.clj`):

- Lobby-Erzeugung vs-bot und bot-vs-bot (Bot-Player korrekt, Auto-Start).
- notify!-Loop mit `*think-ms*` = 0: Partie läuft bis zum Ende ohne Hänger.
- Concede-Pfad bei künstlich leeren Optionen.
- Stats-Skip (nur User-/Deck-Stats) und `close-lobby!` bei Mensch-Leave.
- Replay: `game-logs`-Record existiert und enthält `:replay` nach Bot-Partie mit
  aktiviertem `save-replay`.
- Bestehende Sim-Tests bleiben grün (`decide-one!`-Extraktion ist reines Refactor).
- Vor Commit: relevante Test-Namespaces + `npm run cljs:build`.

## Bewusst NICHT im Scope (YAGNI)

- Bot-Rejoin nach Serverneustart.
- Weitere Schwierigkeitsgrade (Registry ist vorbereitet).
- Bot-Deck-Upload / eigene Decks für den Bot.

## Offene Punkte / geklärte Entscheidungen

- Bot-Decks: nur eingebaute Starterdecks (User-Entscheidung).
- Replay: bleibt für Bot-Spiele erhalten; nur User-/Deck-Stats werden geskippt
  (User-Entscheidung, 2026-07-14).
- Bot-Fehler: Retry (No-Op-Streichung), dann concede (User-Entscheidung).
- Bedenkzeit: 1–2 s auch bei bot-vs-bot (User-Entscheidung).
- Integrationsansatz: Hook in `web.game` (User-Entscheidung).

## Vollabnahme & Smoke-Test (2026-09-01, vor Merge nach master)

Automatisiert, alles grün:
- `bin/test` (kaocha): 3810 Tests, 124303 Assertions, 0 Failures.
- `bin/bot-sim 200`: 200/200 abgeschlossen, 0 hängende Prompts, 0 Step-Cap-Abbrüche,
  0 Exceptions (Siege Corp 125 / Runner 75).
- `npm run cljs:build`: 0 Warnings.

Manueller Smoke-Test nach Server-Neustart (aktueller Branch-Stand inkl. Undo- und
Timing-Fixes), alle Fälle bestanden:
- Die drei alten Repro-Fälle: Red-Team-Ability-Run, Breaker-Fenster (Pump + Break),
  Subroutinen feuern.
- `/undo-click` mehrfach, auch mitten im Encounter.
- Bot-vs-Bot als Spectator.
- Multiplayer-Regression mit zwei Menschen inkl. Rez/Encounter.
- Concede.

Ergebnis: `feat/solo-modus` per Fast-Forward nach lokal `master` gemergt, als Branch
auf origin gepusht, PR gegen origin/master eröffnet (origin/master hatte eigene
Upstream-Sync-Historie — kein Force-Push, siehe PR).

### Backlog (aus dem Smoke-Test, nicht blockierend)

- Spiel-Log: bei „trashes due to net damage" fehlt bei verdeckten Karten der
  Kartenname.
- Bot-vs-Bot-Spectator: beide Seiten heißen „Bot (Random)" — als Zuschauer schwer
  zu unterscheiden.
