# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository

Fork of [mtgred/netrunner](https://github.com/mtgred/netrunner) — jinteki.net, a browser implementation of the Android: Netrunner card game. This is its own git repo (separate remotes from the parent OneDrive playground repo it lives inside; commits here never touch the parent repo). Clojure/ClojureScript, Ring/http-kit backend, Reagent frontend, MongoDB storage.

## Setup

Requires Leiningen, Node.js, MongoDB, Java 21+.

```
lein fetch [--no-card-images]     # populate card DB from NetrunnerDB
lein create-indexes                # create Mongo indexes
npm ci                             # frontend deps
npm run css:build                  # compile stylus -> resources/public/css/netrunner.css
npm run cljs:build                 # compile ClojureScript (shadow-cljs compile app)
lein repl                          # launch webserver + REPL -> http://localhost:1042/
```

Docker alternative: `bin/up` (first run), then `bin/database-seed`. `bin/repl` connects to the containerized REPL (nrepl port 44867). `bin/bash` / `bin/lein` run commands inside the container.

## Tests

Tests run via `kaocha` (Clojure) — 3rd-party lib deps not present in plain Clojure test runners. Outside Docker, use the `kaocha` lein alias; inside Docker use the `bin/test*` wrappers, which do the same thing.

```
bin/test                                              # full suite
bin/test-focus game.cards.agendas-test                # one test namespace
bin/test-focus game.cards.agendas-test/fifteen-minutes # one test
# without docker:
lein kaocha
lein kaocha --focus game.cards.agendas-test
```

CI (`.github/workflows/ci.yml`) separately compiles stylus, compiles ClojureScript in release mode (`npm run cljs:release`), and runs `lein eftest` for the Clojure test suite — keep both `npm run cljs:build`/`release` and the test suite green before considering a change done.

## Architecture

Three source trees under `src/`, split by which runtime they execute in:

- **`src/clj/game/`** — the game engine (server-side, pure Clojure). This is the rules implementation.
  - `game/core/` — engine internals: turn structure, state (`state.clj`), the effect/event system (`engine.clj`, `effects.clj`, `events.clj`), action processing (`process_actions.clj`, `actions.clj`), and per-mechanic modules (`runs.clj`, `access.clj`, `damage.clj`, `tags.clj`, `trace.clj`, `psi.clj`, etc). `card_defs.clj` exposes the `defcard-impl` multimethod that all card implementations hook into.
  - `game/cards/` — one file per card type (`agendas.clj`, `ice.clj`, `programs.clj`, `identities.clj`, ...). Each card is registered with the `defcard` macro from `game.core.def-helpers`, e.g. `(defcard "Card Title" {...ability map...})`.
- **`src/clj/web/`** — the server: HTTP/websocket API (`api.clj`, `ws.clj`, `game_api.clj`), auth, lobby, chat, deck storage, MongoDB access (`mongodb.clj`), NetrunnerDB sync (`nrdb.clj`). Entry point is `web.core` (see `:main` in `project.clj`); dev REPL boots via `web.dev` (`(go)` on REPL start).
- **`src/cljc/`** — code shared between server and client: `jinteki/` has cross-cutting logic (card data model, i18n via Fluent, deck validation in `validator.cljc`, utils); `game/replay.cljc` is shared replay logic.
- **`src/cljs/nr/`** — the Reagent frontend (lobby, deckbuilder, card browser, chat, the live gameboard under `nr/gameboard/`). Built with shadow-cljs (`shadow-cljs.edn`, build id `:app`); `src/cljs/dev` / `src/cljs/prod` are the dev/release entrypoints.
- **`src/css`** — Stylus source, compiled via the `css:*` npm scripts (not part of the ClojureScript build).
- **`src/clj/bot/`** — Solo-Bot-Module (dieses Projekt). Baut auf der Engine auf, nicht in ihr — siehe [Projektregeln](#projektregeln).

Tests mirror source layout 1:1 under `test/clj`, `test/cljc`, `test/cljs` (e.g. `src/clj/game/cards/agendas.clj` -> `test/clj/game/cards/agendas_test.clj` — see `.projections.json` for the alternate-file mapping). Game engine tests use the `do-game` / `new-game` DSL from `game.test-framework` (`test/clj/game/test_framework.clj`) to script a game and assert on state; look at existing tests in `test/clj/game/cards/` before writing new ones.

## Conventions

- Clojure code loosely follows the [Clojure Style Guide](https://github.com/bbatsov/clojure-style-guide).
- `.clj-kondo/config.edn` defines lint-as mappings for this repo's macros (`defcard`, `req`, `msg`, `effect`, `wait-for`, `do-game`, etc) — respect these when writing new macros or the linter will misreport.
- One bug/feature per GitHub issue; PRs should have a clear title/description. If a change is about a specific card's ruling, check the [card implementation status sheet](https://docs.google.com/spreadsheets/d/1ICv19cNjSaW9C-DoEEGH3iFt09PBTob4CAutGex0gnE/pubhtml) linked from the README first.
- Don't add IDE project files to the repo `.gitignore` — put them in your local/global gitignore instead.
- Translation strings live under `jinteki/i18n` (Fluent `.ftl` format); the `missing-translations`, `undefined-translations`, and `unused-translations` lein aliases check consistency between code and the `en` locale.

## Projektregeln

Dieses Projekt baut einen Single-Player-Modus mit Bot-Gegner (mehrere Schwierigkeitsgrade) NEBEN der bestehenden Engine, nicht in ihr.

- **Tests**: `bin/test` für die volle Suite, `bin/test-focus <namespace>` für eine einzelne Testdatei/einzelnen Test (z.B. `bin/test-focus game.cards.agendas-test`).
- **Nach jeder Änderung an Engine-Code** (`src/clj/game/core/`, `src/clj/game/cards/`) muss die betroffene Test-Datei laufen (`bin/test-focus <namespace>`), bevor die Änderung als erledigt gilt.
- **Vor jedem Commit** müssen alle relevanten Test-Namespaces laufen (nicht nur die zuletzt geänderte Datei) — bei Zweifel `bin/test` (volle Suite).
- **`src/clj/game/core/` und `src/clj/game/cards/` (Regelkern) dürfen nur in Absprache geändert werden.** Unser Bot-Projekt ist ein Konsument der Engine, kein Teil davon. Änderungen am Regelkern sind nicht Teil des normalen Workflows — vor jeder Änderung an diesen Pfaden explizit beim User nachfragen/bestätigen lassen, auch wenn ein Bugfix dort naheliegend erscheint. Stattdessen nach Erweiterungspunkten in der Engine suchen (State lesen, Aktionen über bestehende APIs auslösen).
- **Neue Bot-/Solo-Module** entstehen unter `src/clj/bot/` (neu anzulegen). Keine Bot-Logik in `game/core` oder `game/cards` einmischen.
- **Architekturprinzip**: Die Engine (`game/core`) bleibt einzige Regelinstanz — der Bot trifft Entscheidungen, aber Regelauswertung/State-Mutation läuft ausschließlich über die Engine-APIs, nie durch eigene Nachbildung von Spielregeln im Bot-Code.
- **Jede Bot-Entscheidung wird mit Begründung geloggt** (welche Aktion, warum/welche Heuristik, welcher Schwierigkeitsgrad) — Pflicht für jede neue Bot-Entscheidungsfunktion, nicht optional/nachträglich.
