(ns bot.difficulties
  "Bot-Schwierigkeitsgrad-Registry: reine Metadaten (Label-i18n-Key, welche
  Seiten unterstuetzt), geteilt zwischen Server (bot.roster) und Lobby-UI
  (nr.new-game). Einzige Quelle fuer 'welche Grade gibt es / fuer welche
  Seite taugen sie' — die Lobby-UI soll das nicht hartcodiert duplizieren.
  Bewusst OHNE Bot-Instanzen/Factories (die brauchen JVM-seitige Deps wie
  java.util.Random, bot.heuristic-corp) — nur Daten, kompilierbar fuer
  Client (cljs) UND Server (clj).")

(def registry
  "Reihenfolge = Anzeigereihenfolge in der Lobby-UI. Jeder Eintrag:
  [difficulty-key {:label-key <i18n-Fluent-Key> :sides #{:corp ...}}]."
  [["random" {:label-key :lobby_bot-difficulty-random
              :sides #{:corp :runner}}]
   ["heuristic" {:label-key :lobby_bot-difficulty-heuristic
                 ;; Schritt 7a: nur Corp-Playbook existiert bisher
                 ;; (src/clj/bot/heuristic_corp.clj). Kein Runner-Pendant.
                 :sides #{:corp}}]])

(def ^:private by-key (into {} registry))

(defn known? [difficulty]
  (contains? by-key difficulty))

(defn available-for-side? [difficulty side]
  (contains? (get-in by-key [difficulty :sides] #{}) side))

(defn label-key [difficulty]
  (get-in by-key [difficulty :label-key]))

(defn for-side
  "Difficulty-Keys, die fuer `side` verfuegbar sind, in Registry-Reihenfolge."
  [side]
  (->> registry
       (filter (fn [[_ meta]] (contains? (:sides meta) side)))
       (mapv first)))

(defn for-sides
  "Difficulty-Keys, die fuer ALLE `sides` gleichzeitig verfuegbar sind
  (bot-vs-bot: ein Grad muss fuer Corp UND Runner passen)."
  [sides]
  (->> registry
       (filter (fn [[_ meta]] (every? #(contains? (:sides meta) %) sides)))
       (mapv first)))

(defn bot-sides
  "Welche Seite(n) sind bot-gesteuert, gegeben den Lobby-Formularzustand
  `bot-game` (\"vs-bot\"|\"bot-vs-bot\"|nil) und `side` (\"Corp\"|\"Runner\"|
  \"Any Side\"|nil) — dieselbe Normalisierung wie serverseitig
  (web.lobby/apply-bot-setup: alles außer \"Runner\" wird zu Mensch=Corp).
  [] wenn keine Seite bot-gesteuert ist (kein Bot-Spiel)."
  [bot-game side]
  (case bot-game
    "vs-bot" [(if (= side "Runner") :corp :runner)]
    "bot-vs-bot" [:corp :runner]
    []))

(defn options-for-lobby
  "Difficulty-Keys, die fuer die aktuell bot-gesteuerte(n) Seite(n) dieser
  Lobby-Formular-Kombination passen — [] ohne Bot-Seite. Einzige Quelle fuer
  die Dropdown-Optionen der Lobby-UI (kein Hardcoding von \"random\" als
  einziger Option mehr)."
  [bot-game side]
  (let [sides (bot-sides bot-game side)]
    (if (seq sides) (for-sides sides) [])))
