#!/usr/bin/env nbb
;; youtube-set-privacy — 既に上がっている Shorts の privacyStatus を変える。
;;
;; 2026-08-11 に tools/youtube_set_privacy.py から移植。
;;
;; **RULES.md が public 化を明示承認の段としているので、これは uploader の
;; フラグではなく別の道具**で、`--confirm` 無しでは何も変えない。
;;
;; 「status part を丸ごと置き換えない」ための read-modify-write は
;; kotoba-lang/com-youtube の videos/privacy-body が持つ（そこに移した）。
;; 素の {"privacyStatus":"public"} を PUT すると selfDeclaredMadeForKids /
;; embeddable / license が API 既定に戻る —— 子供向け channel では誰も
;; 頼んでいないコンプライアンス変更になる。
;;
;;   nbb tools/youtube-set-privacy.cljs --authorized-user <token> \
;;       --ledger shorts/youtube-uploads-ep02-05.edn --privacy public [--confirm]

(ns youtube-set-privacy
  (:require [kotoba.lang.text :as str]
            [yt.common :as c]
            [youtube.channels :as channels]
            [youtube.client :as client]
            [youtube.videos :as videos]))

(def token-path (c/require-arg "--authorized-user"))
(def ledger-path (c/require-arg "--ledger"))
(def privacy (c/require-arg "--privacy"))
(def confirm? (c/flag? "--confirm"))

(def rel (c/releases "shorts/releases.edn"))

(defn -main []
  (when-not (#{"public" "unlisted" "private"} privacy)
    (c/die! "--privacy must be one of public / unlisted / private"))
  (let [{:keys [channel uploads]} (c/ledger ledger-path)
        entries (sort-by key uploads)]
    (when (empty? entries) (c/die! (str "no uploads in " ledger-path)))

    (when-not confirm?
      (println (str "\nDRY RUN — would set " (count entries) " video(s) to '" privacy "':"))
      (doseq [[[ep lang] e] entries]
        (println (str "  ep" ep " " lang "  https://youtu.be/" (:upload/video-id e)
                      "  (now: " (or (:upload/privacy e) "?") ")")))
      (println "\nRe-run with --confirm to apply.")
      (js/process.exit 0))

    (let [http-fn (c/curl-http-fn)
          opts {:http-fn http-fn}
          token (client/refresh-access-token! (c/load-credentials token-path) opts)
          expected (get-in rel [:defaults :defaults/channel-id])
          verified (channels/assert-channel! token expected opts)
          _ (println (str "channel verified: " (:title verified) " (" (:id verified) ")"))
          ids (mapv (fn [[_ e]] (:upload/video-id e)) entries)
          ;; 現在の status をまとめて読む。書き戻しで消さないため。
          current (videos/list-status! token ids opts)
          state (atom {:channel (assoc channel :channel/default-privacy privacy) :uploads uploads})
          failures (atom 0)]
      (doseq [[[ep lang] e] entries
              :let [vid (:upload/video-id e)
                    st (get current vid)]]
        (cond
          (nil? st)
          (do (swap! failures inc)
              (println (str "ep" ep " " lang ": FAILED — video not found (" vid ")")))
          :else
          (try
            (videos/set-privacy! token vid st privacy opts)
            (swap! state assoc-in [:uploads [ep lang] :upload/privacy] privacy)
            (c/write-ledger! ledger-path @state)
            (println (str "ep" ep " " lang ": " privacy " -> https://youtu.be/" vid))
            (catch :default ex
              (swap! failures inc)
              (println (str "ep" ep " " lang ": FAILED — " (ex-message ex)))))))
      (println (str "done. failures=" @failures))
      (js/process.exit (if (pos? @failures) 1 0)))))

(-main)
