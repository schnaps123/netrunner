(ns bot.sim
  "Simulations-Runner: spielt n Partien Random-Bot vs. Random-Bot und meldet
  am Ende abgeschlossene Partien, Abbrüche (Exceptions), hängende Prompts,
  Step-Cap-Abbrüche, die durchschnittliche Zuganzahl sowie die Seeds aller
  fehlgeschlagenen Partien (Reproduktion: gleiche Seeds -> gleiche Partie,
  weil shuffle/rand* pro Partie auf eine seedbare RNG umgebogen werden).

  Aufruf über bin/bot-sim, z.B.:
    bin/bot-sim 50
    bin/bot-sim 5 --seed 1000 --corp-deck decks/mein-corp.edn"
  (:require
   [bot.cards :as cards]
   [bot.game-runner :as game-runner]
   [bot.random :as random]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]))

(defn- seeded-shuffle-fns
  "Ersatz für clojure.core/shuffle & rand* auf Basis EINER seedbaren RNG.
  Nur damit ist eine Partie über ihren Seed reproduzierbar: die Engine mischt
  Decks über clojure.core/shuffle (game.core.shuffling, set-up), das sonst an
  einer unseedbaren globalen RNG hängt."
  [^java.util.Random rng]
  {#'clojure.core/shuffle  (fn [coll]
                             (let [al (java.util.ArrayList. ^java.util.Collection (vec coll))]
                               (java.util.Collections/shuffle al rng)
                               (vec al)))
   #'clojure.core/rand     (fn ([] (.nextDouble rng))
                             ([n] (* n (.nextDouble rng))))
   #'clojure.core/rand-int (fn [n] (.nextInt rng (int n)))
   #'clojure.core/rand-nth (fn [coll]
                             (let [v (vec coll)]
                               (nth v (.nextInt rng (count v)))))})

(defn run-one
  "Eine Partie mit festem Seed. Ergebnis-Map:
  {:seed :outcome (:completed | :step-cap | :stuck-prompt | :exception)
   :turns :steps :winner :error :error-data}"
  [{:keys [seed corp-deck runner-deck max-steps log-path]}]
  (when log-path
    (io/delete-file (io/file log-path) true))
  (let [rng (java.util.Random. (long seed))]
    (try
      (let [result (with-redefs-fn (seeded-shuffle-fns rng)
                     #(game-runner/run-game
                       {:corp-bot (random/random-bot (* 2 seed))
                        :runner-bot (random/random-bot (inc (* 2 seed)))
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

(defn- avg [xs]
  (when (seq xs)
    (/ (double (reduce + xs)) (count xs))))

(defn- fmt [x]
  (if x (format "%.1f" (double x)) "-"))

(defn report
  "Formatiert die Ergebnisliste als Abschlussreport (String)."
  [results]
  (let [by-outcome (group-by :outcome results)
        completed (:completed by-outcome [])
        failed (remove #(= :completed (:outcome %)) results)
        lines [(str "=== bot-sim Report ===")
               (format "Partien gesamt:     %d" (count results))
               (format "abgeschlossen:      %d" (count completed))
               (format "hängende Prompts:   %d" (count (:stuck-prompt by-outcome)))
               (format "Step-Cap-Abbrüche:  %d" (count (:step-cap by-outcome)))
               (format "Exceptions:         %d" (count (:exception by-outcome)))
               (format "Ø Züge (abgeschl.): %s" (fmt (avg (keep :turns completed))))
               (format "Ø Steps (abgeschl.): %s" (fmt (avg (keep :steps completed))))]
        winners (frequencies (keep :winner completed))
        lines (conj lines (str "Siege: " (or (not-empty winners) "-")))]
    (str/join
     \newline
     (concat
      lines
      (when (seq failed)
        (cons (str "fehlgeschlagene Seeds: "
                   (str/join ", " (map #(str (:seed %) " (" (name (:outcome %)) ")") failed)))
              (for [f failed
                    :when (:error f)]
                (format "  Seed %d: %s%s" (:seed f) (:error f)
                        (if-let [d (:error-data f)] (str " | " (pr-str d)) "")))))))))

(defn- load-deck [path]
  (edn/read-string (slurp path)))

(def cli-options
  [["-n" "--games N" "Anzahl Partien"
    :default 5 :parse-fn #(Long/parseLong %)]
   ["-s" "--seed BASE" "Basis-Seed; Partie i läuft mit Seed BASE+i"
    :default 42 :parse-fn #(Long/parseLong %)]
   [nil "--corp-deck FILE" "EDN-Datei {:identity \"...\" :cards [[\"Titel\" Anzahl] ...]} (Default: System-Gateway-Corp-Starterdeck)"]
   [nil "--runner-deck FILE" "dito für den Runner (Default: System-Gateway-Runner-Starterdeck)"]
   [nil "--max-steps N" "Step-Cap pro Partie"
    :default 5000 :parse-fn #(Long/parseLong %)]
   [nil "--log-dir DIR" "Verzeichnis für Decision-Logs (eine EDN-Datei pro Seed)"
    :default "logs/bot-sim"]
   ["-h" "--help"]])

(defn run-sim
  "Spielt n Partien und liefert {:results [...] :report String}."
  [{:keys [games seed corp-deck runner-deck max-steps log-dir]}]
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
                                         :log-path (str log-dir "/game-" game-seed ".edn")})]
                         (println "  ->" (name (:outcome r))
                                  (str (when (:turns r) (str "Züge=" (:turns r)))
                                       (when (:winner r) (str " Sieger=" (name (:winner r))))
                                       (when (:error r) (str " " (:error r)))))
                         r))))]
    {:results results
     :report (report results)}))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        ;; erlaubt auch `bin/bot-sim 50` ohne -n
        options (if-let [n (first arguments)]
                  (assoc options :games (Long/parseLong n))
                  options)]
    (cond
      (:help options) (do (println "bin/bot-sim [n] [Optionen]") (println summary) (System/exit 0))
      errors (do (doseq [e errors] (println e)) (System/exit 2)))
    (let [{:keys [results report]} (run-sim options)]
      (println)
      (println report)
      (System/exit (if (every? #(= :completed (:outcome %)) results) 0 1)))))
