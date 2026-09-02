(ns nr.new-game
  (:require
    [bot.difficulties :as bot-difficulties]
    [jinteki.utils :refer [str->int descriptions]]
    [jinteki.preconstructed :refer [all-matchups matchup-by-key]]
    [nr.appstate :refer [app-state]]
    [nr.auth :refer [authenticated] :as auth]
    [nr.translations :refer [tr tr-format tr-side tr-element tr-span]]
    [nr.utils :refer [cond-button focus-on-mount slug->format]]
    [nr.ws :as ws]
    [reagent.core :as r]))

(def new-game-keys
  [:allow-spectator
   :api-access
   :bot-game
   :description
   :difficulty
   :format
   :password
   :room
   :save-replay
   :side
   :singleton
   :spectatorhands
   :precon
   :gateway-type
   :open-decklists
   :replay-id
   :replay-timestamp
   :timer
   :title])

(defn create-game [state lobby-state options]
  (authenticated
    (fn [_]
      (cond
        (empty? (:title @state))
        (swap! state assoc :flash-message (tr [:lobby_title-error "Please fill a game title."]))
        (and (:protected @options)
             (empty? (:password @options)))
        (swap! state assoc :flash-message (tr [:lobby_password-error "Please fill a password."]))
        :else
        (let [new-game (select-keys (merge @state @options) new-game-keys)]
          (swap! lobby-state assoc :editing false)
          (ws/ws-send! [:lobby/create new-game]))))))

(defn button-bar [state lobby-state options]
  [:div.button-bar
   [cond-button [tr-span [:lobby_create "Create"]]
    (not (:block-game-creation @app-state))
    #(create-game state lobby-state options)
    (when (:block-game-creation @app-state)
      {:title (tr [:lobby_creation-paused "Game creation is currently paused for maintenance."])})]
   [:button {:type "button"
             :on-click #(do (.preventDefault %)
                            (swap! lobby-state assoc :editing false))}
    [tr-span [:lobby_cancel "Cancel"]]]])

(defn title-section [title-state]
  [:section
   [tr-element :h3 [:lobby_title "Title"]]
   [:input.game-title
    {:on-change #(reset! title-state (.. % -target -value))
     :value @title-state
     :data-i18n-key :lobby_title
     :placeholder (tr [:lobby_title "Title"])
     :maxLength "100"}]])

(defn- difficulty-label
  "Statischer tr-Aufruf pro Schwierigkeitsgrad — der Übersetzungs-Scanner
  (lein undefined-translations/unused-translations) braucht literale
  (tr [:key ...])-Aufrufe im Quelltext. WELCHE Grade überhaupt angeboten
  werden kommt trotzdem dynamisch aus bot.difficulties (siehe
  game-type-section), nicht aus dieser Funktion."
  [difficulty]
  (case difficulty
    "random" (tr [:lobby_bot-difficulty-random "Random"])
    "heuristic" (tr [:lobby_bot-difficulty-heuristic "Heuristic"])
    difficulty))

(defn game-type-section [state]
  [:section
   [tr-element :h3 [:lobby_game-type "Game type"]]
   (doall
     (for [[value tr-key label] [[nil :lobby_vs-player "Versus player"]
                                 ["vs-bot" :lobby_vs-bot "Versus bot"]
                                 ["bot-vs-bot" :lobby_bot-vs-bot "Bot vs. bot"]]]
       ^{:key (or value "human")}
       [:p
        [:label
         [:input
          {:type "radio"
           :name "bot-game"
           :checked (= (:bot-game @state) value)
           :on-change #(do (swap! state assoc :bot-game value)
                           (when value
                             ;; Bot-Decks sind System-Gateway-Starterdecks
                             (swap! state assoc :format "system-gateway")
                             (when (= "Any Side" (:side @state))
                               (swap! state assoc :side "Corp"))))}]
         (tr [tr-key label])]]))
   (when (:bot-game @state)
     (let [available (bot-difficulties/options-for-lobby (:bot-game @state) (:side @state))
           current (if (contains? (set available) (:difficulty @state))
                     (:difficulty @state)
                     (first available))]
       [:div
        [:p
         [:label (tr [:lobby_bot-deck "Bot deck"]) " "
          [:select {:value "gateway" :disabled true}
           [:option {:value "gateway"} "System Gateway Starter"]]]]
        [:p
         [:label (tr [:lobby_bot-difficulty "Bot difficulty"]) " "
          [:select {:value (or current "random")
                    :on-change #(swap! state assoc :difficulty (.. % -target -value))}
           (doall
             (for [d available]
               ^{:key d}
               [:option {:value d} (difficulty-label d)]))]]]
        (when (not (contains? (set available) "heuristic"))
          [:p.smaller {:style {:opacity 0.7}}
           (tr [:lobby_bot-difficulty-corp-only-hint
                "Heuristic is currently Corp-only; Runner bots always play Random."])])]))])

(defn side-section [side-state sides]
  [:section
   [tr-element :h3 [:lobby_side "Side"]]
   (doall
     (for [option sides]
       ^{:key option}
       [:p
        [:label [:input
                 {:type "radio"
                  :name "side"
                  :value option
                  :on-change #(reset! side-state (.. % -target -value))
                  :checked (= @side-state option)}]
         (tr-side option)]]))])

(defn singleton-only [options fmt-state]
  [:label
   [:input {:type "checkbox" :checked (:singleton @options)
            :on-change #(swap! options assoc :singleton (.. % -target -checked))}]
   [tr-span [:lobby_singleton "Singleton"]]])

(defn open-decklists [options]
  [:label
   [:input {:type "checkbox" :checked (:open-decklists @options)
            :on-change #(swap! options assoc :open-decklists (.. % -target -checked))}]
   [tr-span [:lobby_open-decklists "Open Decklists"]]])

(defn gateway-constructed-choice [fmt-state gateway-type]
  [:div
   {:style {:display (if (= @fmt-state "system-gateway") "block" "none")}}
   (doall
     (for [option ["Beginner" "Intermediate" "Constructed"]]
       ^{:key option}
       [:span [:label [:input
                       {:type "radio"
                        :name "gateway-type"
                        :value option
                        :on-change #(reset! gateway-type (.. % -target -value))
                        :checked (= @gateway-type option)}]
               [tr-span [:lobby_gateway-format option] {:format option}]
               "    "]]))])

(defn precon-choice [fmt-state precon]
  [:div
   {:style {:display (if (= @fmt-state "preconstructed") "block" "none")}}
   [:span (str "Decks:     " (tr (:tr-underline (matchup-by-key (keyword @precon)))))]
   [:div
    [:label "Match:    "]
    [:select.precon
     {:value (or @precon "worlds-2012-a")
      :on-change #(reset! precon (.. % -target -value))}
     (doall
       (for [matchup (sort all-matchups)]
         ^{:key (name matchup)}
         [:option {:value (name matchup)} (tr (:tr-inner (matchup-by-key matchup)))]))]]])

(defn format-section [fmt-state options gateway-type precon]
  [:section
   [tr-element :h3 [:lobby_default-game-format "Default game format"]]
   [:select.format
    {:value (or @fmt-state "standard")
     :on-change #(reset! fmt-state (.. % -target -value))}
    (doall
      (for [[k v] slug->format]
        ^{:key k}
        [:option {:value k} (tr-format v)]))]
   [singleton-only options fmt-state]
   [gateway-constructed-choice fmt-state gateway-type]
   [precon-choice fmt-state precon]
   [:div.infobox.blue-shade
    {:style {:display (if (= @fmt-state "quick-draft") "block" "none")}}
    [:p (tr [:lobby_quick-draft "Quickly draft a deck to play against your opponent, using a smaller deck size and lower than normal agenda-point total."])]]
   [:div.infobox.blue-shade
    {:style {:display (if (:singleton @options) "block" "none")}}
    [tr-element :p [:lobby_singleton-details "This will restrict decklists to only those which do not contain any duplicate cards. It is recommended you use the listed singleton-based identities."]]
    [tr-element :p [:lobby_singleton-example "1) Nova Initiumia: Catalyst & Impetus 2) Ampere: Cybernetics For Anyone"]]]])

(defn description-section [description-state]
  [:section
   [tr-element :h4 [:lobby_game-description "Game Description"]]
   [:select.description
    {:value (or @description-state :new-game_default)
     :on-change #(reset! description-state (.. % -target -value))}
    (doall (for [[k v] descriptions]
             ^{:key k}
             [:option {:value k
                       :data-i18n-key k}
              (tr [k v])]))]])

(defn allow-spectators [options]
  [:p
   [:label
    [:input {:type "checkbox" :checked (:allow-spectator @options)
             :on-change #(swap! options assoc :allow-spectator (.. % -target -checked))}]
    [tr-span [:lobby_spectators "Allow spectators"]]]])

(defn toggle-hidden-info [options]
  [:<>
   [:p
    [:label
     [:input {:type "checkbox" :checked (:spectatorhands @options)
              :on-change #(swap! options assoc :spectatorhands (.. % -target -checked))
              :disabled (not (:allow-spectator @options))}]
     [tr-span [:lobby_hidden "Make players' hidden information visible to spectators"]]]]
   [:div.infobox.blue-shade
    {:style {:display (if (:spectatorhands @options) "block" "none")}}
    [tr-element :p [:lobby_hidden-details "This will reveal both players' hidden information to ALL spectators of your game, including hand and face-down cards."]]
    [tr-element :p [:lobby_hidden-password "We recommend using a password to prevent strangers from spoiling the game."]]]])

(defn password-input [options]
  [:<>
   [:p
    [:label
     [:input {:type "checkbox" :checked (:protected @options)
              :on-change #(let [checked (.. % -target -checked)]
                            (swap! options assoc :protected checked)
                            (swap! options assoc :password
                                   (if checked
                                     (get-in @app-state [:options :default-password] "")
                                     "")))}]
     [tr-span [:lobby_password-protected "Password protected"]]]]
   (when (:protected @options)
     [:p
      [:input.game-title {:on-change #(swap! options assoc :password (.. % -target -value))
                          :ref focus-on-mount
                          :value (:password @options)
                          :data-i18n-key :lobby_password
                          :placeholder (tr [:lobby_password "Password"])
                          :maxLength "30"}]])])

(defn add-timer [options]
  [:<>
   (when-not (= "casual" (:room @options))
     [:p
      [:label
       [:input {:type "checkbox"
                :checked (:timed @options)
                :on-change #(let [checked (.. % -target -checked)]
                              (swap! options assoc :timed checked)
                              (swap! options assoc :timer (if checked 35 nil)))}]
       [tr-span [:lobby_timed-game "Start with timer"]]]])
   (when (:timed @options)
     [:p
      [:input.game-title {:on-change #(let [value (str->int (.. % -target -value))]
                                        (when-not (js/isNaN value)
                                          (swap! options assoc :timer value)))
                          :type "number"
                          :value (:timer @options)
                          :data-i18n-key :lobby_timer-length
                          :placeholder (tr [:lobby_timer-length "Timer length (minutes)"])}]])
   [:div.infobox.blue-shade
    {:style {:display (if (:timed @options) "block" "none")}}
    [tr-element :p [:lobby_timed-game-details "Timer is only for convenience: the game will not stop when timer runs out."]]]])

(defn save-replay [options]
  [:<>
   [:p
    [:label
     [:input {:type "checkbox"
              :checked (:save-replay @options)
              :on-change #(swap! options assoc :save-replay (.. % -target -checked))}]
     "🟢 " [tr-span [:lobby_save-replay "Save replay"]]]]
   [:div.infobox.blue-shade
    {:style {:display (if (:save-replay @options) "block" "none")}}
    [tr-element :p [:lobby_save-replay-details "This will save a replay file of this match with open information (e.g. open cards in hand). The file is available only after the game is finished."]]
    [tr-element :p [:lobby_save-replay-unshared "Only your latest 15 unshared games will be kept, so make sure to either download or share the match afterwards."]]
    [tr-element :p [:lobby_save-replay-beta "BETA Functionality: Be aware that we might need to reset the saved replays, so make sure to download games you want to keep. Also, please keep in mind that we might need to do future changes to the site that might make replays incompatible."]]]])

(defn api-access [options user]
  [:<>
   (let [has-keys (:has-api-keys @user false)]
     [:p
      [:label
       [:input {:disabled (not has-keys)
                :type "checkbox"
                :checked (:api-access @options)
                :on-change #(swap! options assoc :api-access (.. % -target -checked))}]
       [tr-span [:lobby_api-access "Allow API access to game information"]]
       (when (not has-keys)
         [:<> " " [tr-span [:lobby_api-requires-key "(Requires an API Key in Settings)"]]])]])
   [:div.infobox.blue-shade
    {:style {:display (if (:api-access @options) "block" "none")}}
    [tr-element :p [:lobby_api-access-details "This allows access to information about your game to 3rd party extensions. Requires an API Key to be created in Settings."]]]])

(defn options-section [options user]
  [:section
   [tr-element :h3 [:lobby_options "Options"]]
   [allow-spectators options]
   [toggle-hidden-info options]
   [open-decklists options]
   [password-input options]
   [add-timer options]
   [save-replay options]
   [api-access options user]])

(defn create-new-game [lobby-state user]
  (r/with-let [casual? (= "casual" (:room @lobby-state))
               default-password (get-in @app-state [:options :default-password] "")
               protected? (and casual? (get-in @app-state [:options :default-password-protect-casual]))
               state (r/atom {:flash-message ""
                              :format (or (get-in @app-state [:options :default-format]) "standard")
                              :room (:room @lobby-state)
                              :side "Any Side"
                              :bot-game nil
                              :difficulty "random"
                              :gateway-type "Beginner"
                              :precon "worlds-2012-a"
                              :description (when casual?
                                             (get-in @app-state [:options :default-game-description]))
                              :title (str (:username @user) "'s game")})
               ;; Haelt :difficulty konsistent mit der tatsaechlich angezeigten
               ;; Auswahl: game-type-section clamped nur die DARSTELLUNG, wenn
               ;; ein Side-/Bot-Game-Wechsel den bisherigen Wert ungueltig macht
               ;; (z.B. "heuristic" waehrend Bot=Corp gewaehlt, dann auf
               ;; Bot=Runner umgeschaltet) - ohne diesen Watch wuerde Create
               ;; trotzdem noch den alten, nicht mehr angezeigten Wert senden.
               _difficulty-guard
               (add-watch state ::difficulty-guard
                          (fn [_ _ old new]
                            (when (or (not= (:bot-game old) (:bot-game new))
                                      (not= (:side old) (:side new)))
                              (let [available (bot-difficulties/options-for-lobby
                                                (:bot-game new) (:side new))]
                                (when (and (seq available)
                                           (not (contains? (set available) (:difficulty new))))
                                  (swap! state assoc :difficulty (first available)))))))
               options (r/atom {:allow-spectator true
                                :api-access false
                                :password (when protected? default-password)
                                :protected protected?
                                :save-replay (if casual?
                                               (get-in @app-state [:options :default-save-replay])
                                               true)
                                :singleton false
                                :spectatorhands false
                                :open-decklists false
                                :timed false
                                :timer nil})
               title (r/cursor state [:title])
               side (r/cursor state [:side])
               bot-game (r/cursor state [:bot-game])
               precon (r/cursor state [:precon])
               gateway-type (r/cursor state [:gateway-type])
               fmt (r/cursor state [:format])
               description (r/cursor state [:description])
               flash-message (r/cursor state [:flash-message])]
    (fn [lobby-state user]
      [:div
       [button-bar state lobby-state options]
       (when-let [message @flash-message]
         [:p.flash-message message])
       (when (:block-game-creation @app-state)
         [:div.infobox.blue-shade
          [:p
           {:style {:margin "10px 5px 10px 0px"}}
           (tr [:lobby_creation-paused "Game creation is currently paused for maintenance."])]])
       [:div.content
        [title-section title]
        [game-type-section state]
        (when-not (= "bot-vs-bot" (:bot-game @state))
          [side-section side (if (= "vs-bot" (:bot-game @state))
                               ["Corp" "Runner"]
                               ["Any Side" "Corp" "Runner"])])
        [format-section fmt options gateway-type precon]
        [description-section description]
        [options-section options user]]])))
