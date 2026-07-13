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
