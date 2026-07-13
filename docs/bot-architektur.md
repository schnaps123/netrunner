# Bot-Architektur: Grundlagen aus dem bestehenden Code

Analyse des Regelkerns/Web-Layers als Grundlage für das Bot-Projekt (`src/clj/bot/`, siehe [Projektregeln](../CLAUDE.md#projektregeln)). Kein Code geändert — nur Recherche mit Datei-/Funktionsverweisen.

## 1. Wo liegt der Spielzustand und wie ist er strukturiert?

Der komplette Spielzustand ist ein einziger Clojure-`Atom`, der auf ein `State`-Record zeigt, definiert in `src/clj/game/core/state.clj:3-52` (`defrecord State`). Erzeugt wird er über `new-state` (`state.clj:61-80`) und in vollem Umfang initialisiert über `init-game` in `src/clj/game/core/set_up.clj:153`.

Zentrale Top-Level-Keys des State-Records (`state.clj:4-52`):

- `:corp`, `:runner` — je ein Player-Map mit Zonen (`:hand`, `:deck`, `:discard`, `:rig`/`:servers` etc.), `:credit`, `:click`, `:prompt` (Prompt-Queue, siehe Frage 4), `:prompt-state`, `:user`.
- `:active-player`, `:turn`, `:corp-phase-12`/`:runner-phase-12` — Turn-/Phasen-Tracking.
- `:run` — aktiver Run (nil wenn keiner läuft), `:encounters` — aktive Ice-Begegnungen.
- `:log` — `{:public [] :corp [] :runner []}`, Chat-/System-Log.
- `:eid`, `:rid` — Zähler für Event-/Remote-IDs (`make-rid`, `state.clj:54-59`).
- `:psi`, `:trace`, `:mark` — Zustand laufender Sub-Mechaniken (Psi-Games, Traces, Mark-Identität).
- `:effects`, `:events`, `:queued-events`, `:turn-events` — das Effekt-/Event-System (siehe `game.core.engine`, `game.core.effects`, `game.core.events`).
- `:winner`, `:loser`, `:winning-user` etc. — Spielende.

Zugriff immer über `@state` (Deref) bzw. `(:corp @state)` usw.; Mutation ausschließlich über `swap!`/`reset!` im Engine-Code — nie direkt von außen manipulieren. Für den Bot relevant: `(:corp @state)` / `(:runner @state)` liefern die vollständige, nicht zensierte Sicht (Server-Perspektive). Was der jeweilige Spieler *sehen darf*, wird separat in `game.core.diffs` berechnet (siehe Frage 5) — für einen Bot, der "fair" spielen soll, ist das der relevante Filter, nicht der Roh-State.

## 2. Wie startet die Testsuite eine Partie ohne Webserver?

Alles in `test/clj/game/test_framework.clj` — das ist auch die Grundlage für Bot-Testing/Simulation ohne `web/`-Server oder MongoDB:

- **`new-game`** (`test_framework.clj:374-423`) — baut Decks (`make-decks`), ruft `core/init-game` (= `game.core.set-up/init-game`) direkt auf mit einer Params-Map `{:gameid :format :players [...]}`, klickt automatisch "Keep" (kein Mulligan) und startet den Corp-Zug. Gibt den State-Atom zurück. Signatur: `(new-game)` oder `(new-game {:corp {:deck [...] :hand [...]} :runner {...} :start-as :runner ...})`.
- **`do-game`** (`test_framework.clj:1080-1116`) — Makro, das lokale Hilfsfunktionen bindet (`get-corp`, `get-runner`, `get-run`, `refresh`, `prompt-map`, `prompt-type`, `prompt-buttons`, `prompt-titles`, `hand-size`) und den Body ausführt. Üblicher Aufruf: `(do-game (new-game {...}) (play-from-hand state :corp "Hedge Fund") ...)`.
- **Aktionen auslösen**: alle Helper (`click-credit`, `click-draw`, `play-from-hand`, `click-prompt`, `click-card`, `card-ability`, `run-on!` etc., siehe restliche Datei) rufen letztlich `core/process-action` auf (Frage 3) — das ist derselbe Pfad wie der echte Server, nur ohne Websocket/HTTP dazwischen.
- **Kartendaten laden**: `load-cards`/`load-all-cards` (`test_framework.clj:24-47`) lesen `data/cards.edn` direkt von Disk und `require`n alle `game.cards.*`-Namespaces — kein Netzwerk, keine DB.

Für den Bot heißt das konkret: `game.test-framework` (bzw. die zugrunde liegenden `game.core`-Funktionen `init-game` + `process-action`) sind der Weg, um Partien in-process zu simulieren (z. B. für Bot-Training/Selbstspiel), ganz ohne `web.*`/Mongo. `test_framework.clj` ist zwar unter `test/` (Testpfad, siehe `project.clj:9`), aber technisch ein normaler Namespace — für eigenen Code direkt `game.core/init-game` und `game.core/process-action` verwenden ist der sauberere, produktionsnahe Weg (kein Test-Only-Gepäck wie `is'`-Assertions).

## 3. Wie schickt ein Spieler Aktionen an die Engine? (Websocket-Kommandos)

Kette vom Client bis zur Engine:

1. **Client** (`src/cljs/nr/gameboard/actions.cljs:94` `send-command`) — verpackt Kommando+Args und sendet per `ws/ws-send!` eine `[:game/action {:gameid ... :command ... :args ...}]`-Message (siehe Aufrufe in `src/cljs/nr/gameboard/board.cljs`, z. B. Zeile 176 `send-command "ability" ...`, Zeile 1974 `send-command "run" ...`, Zeile 1990 `send-command "draw"`).
2. **Server-Websocket-Handler**: `src/clj/web/game.clj:285` — `(defmethod ws/-msg-handler :game/action game--action [...])`. Holt die Lobby/State per `gameid`, prüft Spieler/Spectator, ruft dann `main/handle-action`.
3. **`game.main/handle-action`** (`src/clj/game/main.clj:13-17`) — dünner Wrapper: ruft `core/process-action`, setzt bei Erfolg eine neue Action-ID (`set-action-id`, `main.clj:8-11`, für Client-Lock/Deduplizierung).
4. **`game.core.process-actions/process-action`** (`src/clj/game/core/process_actions.clj:116-121`) — die eigentliche Dispatch-Stelle: schlägt den String-Command in der Map `commands` (`process_actions.clj:67-114`) nach (z. B. `"credit"` → `click-credit`, `"run"` → `click-run`, `"play"` → `play`, `"ability"` → `play-ability`, `"choice"` → `resolve-prompt`, `"select"` → `select`, `"rez"`, `"score"`, `"jack-out"`, `"end-turn"`, `"purge"`, `"subroutine"` → `play-subroutine`, uvm.), ruft die passende Funktion mit `(state side args)` auf und danach `checkpoint+clean-up` (Effekt-Checkpoint, Run-Ende-Prüfung).
5. **Antwort an Client**: `web/game.clj` berechnet nach der Aktion einen State-Diff pro Spieler (`game.core.diffs`) und sendet ihn zurück (`update-and-send-diffs!`); der Client wendet ihn per `differ/patch` an (`nr/gameboard/actions.cljs:50-62` `handle-diff!`).

Für den Bot: Der Bot braucht keinen Websocket. Er kann direkt `game.core/process-action` (bzw. dessen öffentliches Alias in `game.core`) mit `(command state side args)` aufrufen — exakt der gleiche Code-Pfad, den der Server nach dem Websocket-Parsing benutzt. Die vollständige Liste gültiger `command`-Strings steht in `process_actions.clj:67-114`.

Ergänzend existiert eine separate REST-API (`docs/jinteki-GameAPI-1.0.0.yaml`) für *lesenden* Zugriff auf Board-Infos per API-Key — kein Aktionskanal, nur Read-only-Snapshot, für den Bot nicht der relevante Pfad (der Bot läuft ja im selben Prozess/State).

## 4. Wie funktionieren Prompts?

Alle Prompts landen in `(get-in @state [side :prompt])`, einer Queue (Vector), verwaltet über `add-to-prompt-queue`/`remove-from-prompt-queue` in `src/clj/game/core/prompt_state.clj:10-21`. `set-prompt-state` spiegelt den vordersten Eintrag zusätzlich nach `[side :prompt-state]` (Convenience für den Diff-Layer). Das erzeugte Prompt-Map hat u. a. `:eid`, `:msg`, `:choices`, `:effect` (Callback bei Auflösung), `:card`, `:prompt-type`.

Zentrale Erzeuger-Funktionen, alle in `src/clj/game/core/prompts.clj`:

- **`show-prompt`** (`prompts.clj:30-68`) — Basisfunktion, durch die *alle* Prompts laufen (auch die spezialisierten unten rufen sie intern bzw. bauen dasselbe Prompt-Format nach). Nimmt `choices` (Liste, `:credit`, `:counter`, `:number`, `:card-title` oder Keyword) und einen Callback `f`, der bei Auflösung mit der gewählten `choice` aufgerufen wird.
- **`show-select`** (`prompts.clj:164`) / **`resolve-select`** (`prompts.clj:125`) — "Klicke eine Karte auf dem Tisch an"-Prompts (`:prompt-type :select`, `prompts.clj:231`), z. B. für Trash-Ziele, Zielauswahl bei Abilities.
- **`show-trace-prompt`** (`prompts.clj:87`) — Trace-Prompts (`:prompt-type :trace`, `prompts.clj:100`); Logik in `src/clj/game/core/trace.clj` (`resolve-trace`, `trace.clj:25-`), vergleicht `corp-strength` vs. `runner-strength` (Base+Bonus+Boost vs. Link+Boost) und feuert `:successful`/`:unsuccessful`-Ability.
- **Psi-Games**: `src/clj/game/core/psi.clj` — `psi-game` (`psi.clj:46-62`) zeigt beiden Spielern gleichzeitig einen Bet-Prompt (`:prompt-type :psi`, `psi.clj:62`, via `show-prompt-with-dice`), `resolve-psi` (`psi.clj:19-44`) vergleicht die verdeckten Gebote und löst `:equal`/`:not-equal`-Ability aus.
- **Ja/Nein-Entscheidungen**: `src/clj/game/core/optional.clj` — `optional-ability` (`optional.clj:13-48`), zeigt Yes/No-Prompt (unterstützt `:autoresolve`, s. `set-autoresolve`/`get-autoresolve`, `optional.clj:71-89`).
- **Reihenfolge-/Mehrfachauswahl**: `src/clj/game/core/choose_one.clj` — `choose-one-helper` (`choose_one.clj:9-`), baut aus einer Liste `{:option ... :req ... :cost ... :ability ...}` einen Prompt mit mehreren benannten Optionen (inkl. optionalem "Done"), zahlbarkeitsgeprüft über `payable?`.
- **Wartender Gegner**: `show-wait-prompt`/`clear-wait-prompt` (`prompts.clj:238-251`) — zeigt der Gegenseite `:prompt-type :waiting`, während der aktive Spieler entscheidet (auch automatisch von `show-prompt` erzeugt, wenn `:waiting-prompt` gesetzt ist, `prompts.clj:58-67`).
- **Run-Prompts**: `show-run-prompts`/`clear-run-prompts` (`prompts.clj:252-265`), `:prompt-type :run` (`prompts.clj:256-257`) — Fenster während eines laufenden Runs (Jack-out-Option etc.).

Bekannte `:prompt-type`-Werte im Code (nicht abschließend, aber vollständig für Kern-Mechaniken — grep nach `:prompt-type :` im Repo für Aktualität): `:select`, `:trace`, `:psi`, `:run`, `:waiting`, `:draft` (`game.core.quick_draft`), `:mulligan` (`game.core.set_up:76`), `:card-title` (`game.core.engine:451`), `:show-discard` (`game.core.engine:423`), `:bogus` (Karten-eigene Fake-Prompts, z. B. `game.cards.agendas`, `game.cards.assets`), sowie der Default `:other` (`prompts.clj:48`) für generische Menü-Prompts ohne Sondertyp.

**Auflösung**: Der Client schickt bei jeder Prompt-Interaktion `send-command "choice" {:eid ... :choice ...}` (Text-/Nummer-/Credit-Prompts) oder `send-command "select" {:card ... :eid ...}` (Select-Prompts) — siehe `board.cljs:207,211`. Server-seitig landet das über `process-action` bei `resolve-prompt` bzw. `select` (`process_actions.clj:72,103`), was den `:effect`-Callback des vordersten Prompt-Eintrags aufruft und ihn aus der Queue entfernt.

Für den Bot: Ein Bot muss `(get-in @state [side :prompt])` (bzw. `[side :prompt-state]` für den vordersten Eintrag) lesen, `:prompt-type` und `:choices` auswerten und dann exakt dieselben `process-action`-Commands (`"choice"`, `"select"`) senden wie der echte Client.

## 5. Wie ermittelt das Frontend, welche Aktionen/Buttons gerade legal sind?

Zweistufig — **Server berechnet die Autorität, Client cached/spiegelt sie nur**:

- **Server (Autorität)**: `src/clj/game/core/diffs.clj` — bevor der State als Diff an den Client geschickt wird, werden Karten/Fähigkeiten mit einem `:playable`-Flag anreichert:
  - `playable?` (`diffs.clj:20-59`) — prüft für eine Karte in der Hand, ob sie überhaupt spielbar/installierbar ist: richtige Seite, kein Phase-1.2-Block, und je nach Kartentyp (`agenda?`/`asset?`/`ice?`/`upgrade?` → `corp-can-pay-and-install?`; `hardware?`/`program?`/`resource?` → `runner-can-pay-and-install?` und kein aktiver Run; `event?`/`operation?` → `can-play-instant?`). Ergebnis landet als `(assoc card :playable true)`.
  - `flashback-playable?` (`diffs.clj:61-65`) — dasselbe für Flashback-Karten aus dem Discard.
  - `ability-playable?` (`diffs.clj:72-92`) — pro Ability einer Karte: `active?`/`autoresolve`, nicht disabled, keine Action während eines Runs, `can-pay?` und `can-trigger?` (aus `game.core.engine`). Ergebnis via `ability-summary`/`abilities-summary` (`diffs.clj:104-112`) in den State-Diff eingebettet.
  - Diese Flags (`:playable`, `:flashback-playable`, `:playable-as-if-in-hand`) sind das, was tatsächlich über den Websocket-Diff beim Client ankommt (`ability-keys`, `diffs.clj:94-102`, enthält `:playable`).
- **Client (Anzeige/Heuristik)**: `src/cljs/nr/gameboard/board.cljs` liest diese Flags nur noch aus, berechnet aber zusätzlich eine eigene, rein UI-seitige Liste möglicher *Karten-Kontextmenü-Aktionen* (nicht: ob spielbar) in `action-list` (`board.cljs:89-121`, z. B. `"advance"`, `"score"`, `"trash"`, `"rez"`, `"derez"` je nach `type`/`zone`/`rezzed`/`advanceable`/Advancement-Counter-Vergleich). `playable?` auf Client-Seite (`board.cljs:131-134`) liest lediglich das vom Server gesetzte `:playable`-Flag der jeweiligen Ability. `handle-abilities` (`board.cljs:136-`) kombiniert `action-list` + Karten-Abilities, um zu entscheiden, ob ein Klick direkt eine Aktion sendet oder ein Kartenmenü öffnet.

Wichtig für den Bot: Die Client-`action-list` in `board.cljs` ist **UI-Bequemlichkeit, keine Regelquelle** — sie dupliziert teils Bedingungen, die der Server ohnehin über `process-action` durchsetzt (illegale Commands werden dort effektlos/mit Fehler abgewiesen, s. `process_actions.clj:116-121`, `command-parser`/`should-process-command?`, `process_actions.clj:47-55`). Für einen Bot ist die belastbare Quelle für "was ist gerade spielbar" das Server-seitige `:playable` aus `game.core.diffs` (bzw. bei State-in-process-Zugriff direkt `playable?`/`ability-playable?` aus `diffs.clj`), nicht der Client-Code in `board.cljs`.
