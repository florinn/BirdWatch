(ns birdwatch.state.comm
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [birdwatch.stats.timeseries :as ts]
            [birdwatch.stats.wordcount :as wc]
            [birdwatch.state.search :as s]
            [birdwatch.state.initial :as i]
            [birdwatch.state.proc :as p]
            [cljs.core.async :as async :refer [<! put! pipe timeout chan sliding-buffer]]
            [cljs.core.match :refer-macros [match]]))

;;;; Channels processing namespace., here messages are taken from channels and processed.

(def qry-chan (chan))
(defn connect-qry-chan [c] (pipe qry-chan c))

(defn append-search-text [s app]
  (swap! app assoc :search-text (str (:search-text @app) " " s)))

(defn- stats-loop
  "Process messages from the stats channel and update application state accordingly."
  [stats-chan app]
  (go-loop []
           (let [[msg-type msg] (<! stats-chan)]
             (match [msg-type msg]
                    [:stats/users-count       n] (swap! app assoc :users-count n)
                    [:stats/total-tweet-count n] (swap! app assoc :total-tweet-count n))
             (recur))))

(defn- prev-chunks-loop
  "Take messages (vectors of tweets) from prev-chunks-chan, add each tweet to application
   state, then pause to give the event loop back to the application (otherwise, UI becomes
   unresponsive for a short while)."
  [prev-chunks-chan app]
  (go-loop []
           (let [chunk (<! prev-chunks-chan)]
             (doseq [t chunk] (p/add-tweet! t app))
             (<! (timeout 50))
             (recur))))

(defn- data-loop
  "Process messages from the data channel and process / add to application state.
   In the case of :tweet/prev-chunk messages: put! on separate channel individual items
   are handled with a lower priority."
  [data-chan app]
  (let [prev-chunks-chan (chan)]
    (prev-chunks-loop prev-chunks-chan app)
    (go-loop []
             (let [[msg-type msg] (<! data-chan)]
               (match [msg-type msg]
                      [:tweet/new             tweet] (p/add-tweet! tweet app)
                      [:tweet/missing-tweet   tweet] (p/add-to-tweets-map! app :tweets-map tweet)
                      [:tweet/prev-chunk prev-chunk] (do
                                                       (put! prev-chunks-chan prev-chunk)
                                                       (s/load-prev app qry-chan))
                      :else ())
               (recur)))))

(defn- cmd-loop
  "Process command messages, e.g. those that alter application state."
  [cmd-chan pub-chan app]
  (go-loop []
           (let [[msg-type msg] (<! cmd-chan)]
             (match [msg-type msg]
                    [:toggle-live           _] (swap! app update :live #(not %))
                    [:set-search-text    text] (swap! app assoc :search-text text)
                    [:set-current-page   page] (swap! app assoc :page page)
                    [:set-page-size         n] (swap! app assoc :n n)
                    [:start-search          _] (s/start-search app (i/initial-state) qry-chan)
                    [:set-sort-order by-order] (swap! app assoc :sorted by-order)
                    [:retrieve-missing id-str] (put! qry-chan [:cmd/missing {:id_str id-str}])
                    [:append-search-text text] (append-search-text text app)
                    [:words-cloud n] (put! pub-chan [msg-type (wc/get-words app n)])
                    [:words-bar   n] (put! pub-chan [msg-type (wc/get-words2 app n)])
                    [:ts-data     _] (put! pub-chan [msg-type (ts/ts-data app)])
                    :else ())
             (recur))))

(defn- broadcast-state
  "Broadcast state changes on the specified channel. Internally uses a sliding
   buffer of size one in order to not overwhelm the rest of the system with too
   frequent updates. The only one that matters next is the latest state anyway.
   It doesn't harm to drop older ones on the channel."
  [pub-channel app]
  (let [sliding-chan (chan (sliding-buffer 1))]
    (pipe sliding-chan pub-channel)
    (add-watch app :watcher
               (fn [_ _ _ new-state]
                 (put! sliding-chan [:app-state new-state])))))
