# spotify-repl

Random Clojure functions for personal Spotify management

## Dependencies

 * Leiningen
 * Spotify account

## Setup

 1. Register a dummy application (eg. `spotify-repl`) as shown [here](https://developer.spotify.com/documentation/general/guides/app-settings/)
 1. Create a `resources/config.edn` with `:client-id` and `:client-secret` set accordingly. See `resources/config-template.edn` for reference
 1. Run `lein repl`
 1. Run `(authorize!)` and give access via your browser
 1. Copy the `code` query parameter from the example site you're redirected to
 1. Run `(refresh-tokens! "...")` with the code you copied
 1. Now you should be able to use the rest of the API routines

## Use cases

### Follow all artists in a playlist of yours

One of my [playlists](https://open.spotify.com/playlist/2UepWXlNe4Ph43oXdvDrnk)
had over 1400 songs and I wanted to follow all the artists in it.
I wrote the `follow-playlist-artists!` routine to do it for me.
