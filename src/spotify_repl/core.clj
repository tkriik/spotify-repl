(ns spotify-repl.core
  (:require [camel-snake-kebab.core :as csk]
            [cemerick.url :as url]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as string]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

(def config (-> "config.edn"
                io/resource
                slurp
                edn/read-string))

(defn authorize! []
  (let [{:keys [spotify-accounts-url browser-command client-id redirect-uri scope]} config
        query-params {:client_id client-id
                      :redirect_uri redirect-uri
                      :scope (string/join " " scope)
                      :response_type "code"}
        query-string (url/map->query query-params)
        url (format "%s/authorize?%s" spotify-accounts-url query-string)]
    (shell/sh browser-command url)))

(defn- print-httpkit-error [msg]
  (log/errorf "HTTP request failed: %s" msg))

(defn- print-authorization-error [{:keys [error error-description]}]
  (log/errorf "Authorization failed: %s (%s)\n" error-description error))

(defn- print-api-error [{:keys [error]}]
  (let [{:keys [message status]} error]
    (log/errorf "API call failed: %s (status = %d)\n" message status)))

(defn refresh-tokens! [code]
  (let [{:keys [spotify-accounts-url client-id client-secret redirect-uri]} config
        request-data {:code code
                      :client_id client-id
                      :client_secret client-secret
                      :redirect_uri redirect-uri
                      :grant_type "authorization_code"}
        url (format "%s/api/token" spotify-accounts-url)
        headers {"Content-Type" "application/x-www-form-urlencoded"}
        {:keys [status body error]} @(http/request {:method :post
                                                    :url url
                                                    :form-params request-data
                                                    :headers headers})
        response-data (json/parse-string-strict body csk/->kebab-case-keyword)]
    (cond (<= 200 status 299)
          (let [{:keys [access-token token-type refresh-token]} response-data]
            (io/make-parents "./tmp/xxx")
            (spit "./tmp/access_token" access-token)
            (spit "./tmp/token_type" token-type)
            (spit "./tmp/refresh_token" refresh-token))

          (some? error)
          (print-httpkit-error error)

          :default
          (let [error-object response-data]
            (print-authorization-error error-object)))))

(defn- authorization-header []
  (let [access-token (slurp "tmp/access_token")
        token-type (slurp "tmp/token_type")]
    (format "%s %s" token-type access-token)))

(defn- handle-api-response [{:keys [status body error]}]
  (cond (<= 200 status 299)
        (or (json/parse-string-strict body csk/->kebab-case-keyword)
            :ok)

        (some? error)
        (print-httpkit-error error)

        :default
        (let [error-object (json/parse-string-strict body csk/->kebab-case-keyword)]
          (print-api-error error-object))))

(defn get-followed-artists [& {:keys [after limit] :or {limit 50}}]
  (log/debugf "Querying followed artists %s with limit %d" (if after (format "after %s" after) "") limit)
  (let [{:keys [spotify-api-url]} config
        url (format "%s/v1/me/following" spotify-api-url)
        query-params (cond-> {:type "artist"
                              :limit limit}
                             after (assoc :after after))
        headers {"Authorization" (authorization-header)}]
    (handle-api-response @(http/request {:method :get
                                         :url url
                                         :query-params query-params
                                         :headers headers}))))

(defn get-all-followed-artists []
  (log/debugf "Querying all followed artists")
  (loop [{:keys [items cursors] :as response} (:artists (get-followed-artists))
         all-items []]
    (when response
      (if-let [after (:after cursors)]
        (recur (:artists (get-followed-artists :after after))
               (concat all-items items))
        (concat all-items items)))))

(defn follow-artists! [artist-ids]
  (let [{:keys [spotify-api-url]} config
        artist-id-batch (take 50 artist-ids)
        _ (log/debugf "Following %d artist(s) with ids: %s ... %s" (count artist-id-batch) (first artist-id-batch) (last artist-id-batch))
        url (format "%s/v1/me/following" spotify-api-url)
        query-params {:type "artist"}
        headers {"Authorization" (authorization-header)
                 "Content-Type" "application/json"}
        request-data {:ids artist-id-batch}
        request-body (json/generate-string request-data)
        response @(http/request {:method :put
                                 :url url
                                 :query-params query-params
                                 :headers headers
                                 :body request-body})]
    (when (handle-api-response response)
      (let [remaining-artist-ids (drop 50 artist-ids)]
        (when (seq remaining-artist-ids)
          (follow-artists! remaining-artist-ids))))))

(defn get-playlist [playlist-id]
  (log/debugf "Querying playlist with id: %s" playlist-id)
  (let [{:keys [spotify-api-url]} config
        url (format "%s/v1/playlists/%s" spotify-api-url playlist-id)
        headers {"Authorization" (authorization-header)}]
    (handle-api-response @(http/request {:method :get
                                         :url url
                                         :headers headers}))))

(defn get-playlists [& {:keys [offset limit] :or {offset 0 limit 50}}]
  (log/debugf "Querying playlists with offset %d and limit %d" offset limit)
  (let [{:keys [spotify-api-url]} config
        url (format "%s/v1/me/playlists" spotify-api-url)
        query-params {:offset offset
                      :limit limit}
        headers {"Authorization" (authorization-header)}]
    (handle-api-response @(http/request {:method :get
                                         :url url
                                         :query-params query-params
                                         :headers headers}))))

(defn get-all-playlists []
  (log/debugf "Querying all playlists")
  (loop [{:keys [items next offset limit] :as response} (get-playlists)
         all-items []]
    (when response
      (if next
        (recur (get-playlists :offset (+ offset limit))
               (concat all-items items))
        (concat all-items items)))))

(defn get-playlist-tracks [playlist-id & {:keys [offset limit] :or {offset 0 limit 100}}]
  (log/debugf "Querying tracks for playlist with id %s, offset %d and limit %d" playlist-id offset limit)
  (let [{:keys [spotify-api-url]} config
        url (format "%s/v1/playlists/%s/tracks" spotify-api-url playlist-id)
        query-params {:offset offset
                      :limit limit}
        headers {"Authorization" (authorization-header)}]
    (handle-api-response @(http/request {:method :get
                                         :url url
                                         :query-params query-params
                                         :headers headers}))))

(defn get-all-playlist-tracks [playlist-id]
  (log/debugf "Querying all tracks for playlist with id %s" playlist-id)
  (loop [{:keys [items next offset limit] :as response} (get-playlist-tracks playlist-id)
         all-items []]
    (when response
      (if next
        (recur (get-playlist-tracks playlist-id :offset (+ offset limit))
               (concat all-items items))
        (concat all-items items)))))

(defn follow-playlist-artists! [playlist-name]
  (let [playlists (get-all-playlists)
        playlist-id (->> playlists
                      (filter #(= playlist-name (:name %)))
                      first
                      :id)]
    (when playlist-id
      (let [playlist-tracks (get-all-playlist-tracks playlist-id)
            followed-artists (get-all-followed-artists)]
        (when (and playlist-tracks followed-artists)
          (let [playlist-track->artist-ids (fn [track]
                                             (->> track :track :artists (map :id)))
                playlist-artist-ids (->> playlist-tracks
                                         (map playlist-track->artist-ids)
                                         flatten
                                         set)
                followed-artist-ids (set (map :id followed-artists))
                non-followed-artists (vec (set/difference playlist-artist-ids followed-artist-ids))]
            (follow-artists! non-followed-artists)))))))