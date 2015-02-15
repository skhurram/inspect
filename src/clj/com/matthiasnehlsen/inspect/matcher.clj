(ns com.matthiasnehlsen.inspect.matcher
  (:gen-class)
  (:require
   [clojure.pprint :as pp]
   [clojure.tools.logging :as log]
   [clojure.core.match :as match :refer (match)]
   [taoensso.sente :as sente]
   [com.stuartsierra.component :as component]
   [clojure.core.async :as async :refer [chan <! >! put! tap untap-all timeout go-loop]]))

(defn user-id-fn
  "generates unique ID for request"
  [req]
  (let [uid (str (java.util.UUID/randomUUID))]
    (log/info "Connected:" (:remote-addr req) uid)
    uid))

(def client-maps (atom {}))
(def stats (atom {}))

(defn get-next-items
  "start listener for all event types, limited to the next n for each"
  [inspect-chan client-map full-event]
  (put! inspect-chan [:next (:client-uuid full-event) client-map]))

(defn initialize-inspector
  "start listener for all event types, limited to the next n for each"
  [params full-event chsk-send!]
  (let [uid (:uid params)
        n (:n params)
        client-map (into {} (map (fn [[t _]] [t n]) (seq @stats)))]
    (swap! client-maps assoc-in [uid] client-map)
    (chsk-send! uid [:info/client-map (get-in @client-maps [uid])])))

(defn close-connection
  "cleanup after connection was closed"
  [inspect-chan client-uuid]
  (log/info "Connection closed:" client-uuid)
  (put! inspect-chan [:close client-uuid]))

(defn send-stats
  "send set with known event types to connected UIs"
  [uids chsk-send!]
  (doseq [uid (:any @uids)]
    (chsk-send! uid [:info/stats @stats]))
  (reset! stats (into {} (map (fn [[t _]] [t 0]) @stats))))

(defn- make-handler
  "create event handler function for the websocket connection"
  [inspect-fn inspect-chan chsk-send! uids]
  (fn [full-event]
    (let [event (:event full-event)]
      (inspect-fn :ws/event-in full-event)
      (match event
             [:cmd/get-next-items params] (get-next-items inspect-chan params full-event)
             [:cmd/initialize params]     (initialize-inspector params full-event chsk-send!)
             [:cmd/get-stats params]      (chsk-send! (:uid params) [:info/stats @stats])
             [:chsk/ws-ping]              () ; currently just do nothing with ping (no logging either)
             [:chsk/uidport-open]         () ; user-id-fn already logs established connection
             [:chsk/uidport-close]        (close-connection inspect-chan (:client-uuid full-event))
             :else                        (log/debug "Unmatched event:" event)))))

(defn handle-event
  "handles events coming in through the inspect function, includes updating stats"
  [uids msg chsk-send!]
  (let [origin (:origin msg)]
    (swap! stats assoc origin (inc (get @stats origin 0)))
    (doseq [uid (:any @uids)]
      (when (pos? (get-in @client-maps [uid origin] 0))
        (chsk-send! uid [:info/msg (assoc msg :payload (with-out-str (pp/pprint (:payload msg))))])
        (swap! client-maps #(update-in % [uid origin] dec))
        (chsk-send! uid [:info/client-map (get-in @client-maps [uid])])))))

(defn event-loop
  "run loop, call chsk-send! with message on channel"
  [channel uids chsk-send!]
  (go-loop []
           (match (<! channel)
                  [:event ev] (handle-event uids ev chsk-send!)
                  [:close uid]  (swap! client-maps dissoc uid)
                  [:next uid client-map] (swap! client-maps assoc-in [uid] client-map)
                  [:send-stats] (send-stats uids chsk-send!))
           (recur)))

(defrecord Matcher [event-mult inspect-fn chsk-router]
  component/Lifecycle
  (start [component] (log/info "Starting Communicator Component")
         (let [inspect-chan (chan 1000)
               {:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn connected-uids]}
               (sente/make-channel-socket! {:user-id-fn user-id-fn})
               event-handler (make-handler inspect-fn inspect-chan send-fn connected-uids)
               chsk-router (sente/start-chsk-router! ch-recv event-handler)]
           (tap event-mult inspect-chan)
           (event-loop inspect-chan connected-uids send-fn)
           (go-loop []
                    (<! (timeout 10000))
                    (>! inspect-chan [:send-stats])
                    (recur))
           (assoc component
             :ajax-post-fn ajax-post-fn
             :ajax-get-or-ws-handshake-fn ajax-get-or-ws-handshake-fn
             :chsk-router chsk-router)))

  (stop [component] (log/info "Stopping Communicator Component")
        (chsk-router) ;; stops router loop
        (untap-all event-mult)
        (assoc component
          :chsk-router nil
          :ajax-post-fn nil
          :ajax-get-or-ws-handshake-fn nil)))

(defn new-matcher
  "create new component"
  [event-mult inspect-fn]
  (map->Matcher {:event-mult event-mult
                 :inspect-fn inspect-fn}))
