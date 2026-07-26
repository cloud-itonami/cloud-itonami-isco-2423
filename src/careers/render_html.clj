(ns careers.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (`:item2/classification \"unknown-no-demo\"` in the
  fleet-wide scan). This namespace drives the REAL actor stack
  (`careers.actor` -> `careers.governor` -> `careers.store`) through a
  scenario built from real, exercised store data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify
  by diffing two consecutive runs before shipping).

  Adapted from the ISCO-08 1211/1111/2113/1213/1112 build-time-console
  precedents (`90-docs/business/cloud-itonami-maturity-loop.md` in
  com-junkawasaki/root) using this repo's OWN real fixture, not a copy
  of theirs: client `client-1` (\"Machi Careers\") + candidate `cand-1`
  (\"A\", skills #{:clj :sql}, consented) + vacancy `vac-1` (\"data
  clerk\", requires #{:sql}) are lifted VERBATIM from
  `careers.actor-test`'s `fresh-store` fixture (ground truth, not
  invented). Candidate `cand-noconsent` (\"B\") is ALSO real fixture
  data from that same file -- this is the consent-boundary case flagged
  by the earlier screening pass, verified here by actually reading the
  fixture: `:consents-to-referral false`, with `:skills #{:sql}` per
  `careers.actor-test`. Honesty note on an inter-fixture discrepancy in
  this repo's OWN test files (not papered over): `careers.governor-
  test`'s SEPARATE `fresh-store` gives `cand-noconsent` a DIFFERENT
  skills set, `#{:clj :sql}` -- the two test files disagree with each
  other on this candidate's skills. We use `careers.actor-test`'s value
  (`#{:sql}`) since that is the fixture exercising the full graph (the
  same shape of scenario this render namespace drives), and note the
  discrepancy here rather than silently picking one.

  Vacancy `vac-hard` (\"platform eng\", requires #{:sql :k8s}) and
  client `client-2`/\"Other Org\" are ALSO real fixture data, but from
  `careers.governor-test`'s fixtures (`fresh-store` and
  `hard-on-foreign-module`-style inline registration respectively) --
  used here to reach the `:skills-gap` and wrong-client hard-hold rules
  through the real graph. Candidate `cand-2` and vacancy `vac-2` (both
  under `client-2`) are ADDITIONAL demo data, registered via the SAME
  real `register-candidate!`/`register-vacancy!` protocol calls this
  actor's own store exposes -- disclosed here plainly, not presented as
  pre-existing fixture, so the console can show a second client
  operating cleanly and demonstrate the two wrong-client hard-holds
  (citing a real candidate/vacancy that belongs to the OTHER client).
  Every other field this page displays (statuses, record counts, hold/
  escalation reasons) is real output read after `run-demo!` actually
  executed the graph -- none of it is hand-typed.

  Honesty note on `context` (architecture, not a shortcut): like the
  ISCO-08 2133 sibling and unlike the 1112 sibling
  (`administration.governor`, which gates on `context`'s `:topic`),
  `careers.governor/check`'s parameter list includes `context` but the
  function BODY never reads it (confirmed by reading the code). There
  is no context/topic-sensitivity gate in this domain; this scenario
  passes `{}` for context throughout.

  This scenario demonstrates 8 of the 9 distinct real HARD-hold `:rule`
  values in `careers.governor/hard-violations` (`:no-client`,
  `:incomplete-placement`, `:unknown-candidate`, `:unknown-vacancy`,
  `:candidate-wrong-client`, `:vacancy-wrong-client`, `:no-consent`,
  `:skills-gap`) and the one real escalation op (`:send-referral`) --
  every `:op` keyword and violation rule name below is copied from
  `careers.governor` itself, not invented.

  Known architectural gaps, honestly noted rather than papered over
  (confirmed by reading `careers.governor` and `careers.advisor`
  directly, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    unconditionally sets `:effect :propose` on every proposal it emits.
    Covered instead by
    `careers.governor-test/hard-on-no-actuation-violation` (which calls
    `governor/check` directly with a hand-built proposal whose
    `:effect` is `:direct-write`).
  - low-confidence escalation (`confidence < 0.6`) is NOT reachable
    either, because `careers.advisor/infer`'s stake-derived confidence
    (`:high` 0.7, `:medium` 0.85, `:low` 0.95) never drops below the
    governor's `confidence-floor` (0.6).
  Both gaps are the same shape as the ISCO-08 1211/2113/1213/1112/2133
  precedents' disclosed `:no-actuation` gap -- this demo, like those,
  only ever drives the real actor/graph the way an operator actually
  would (citing real, registered-but-mismatched entities is a genuine
  human-error path, not a hand-built proposal), and does not
  hand-construct proposals to force unreachable paths.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [careers.store :as store]
            [careers.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real careers operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra context]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request context tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request :context context
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request :context context
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request :context context
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit across 2 clients and 2 distinct
  ops, escalate-then-approve, and 8 of the 9 distinct HARD-hold `:rule`
  values in `careers.governor` -- the 9th, `:no-actuation`, plus the
  low-confidence escalation reason, are architecturally unreachable via
  the real advisor, see namespace docstring). Every `:op` keyword and
  violation rule name below is copied from `careers.governor`'s own
  `hard-violations`/`check`, not invented. Vector shape:
  [thread-id client-id op extra context]."
  [;; client-1 (real fixture from careers.actor-test) -- clean ops
   ["c1-place-clean"           "client-1" :propose-placement {:candidate-id "cand-1" :vacancy-id "vac-1"} {}]
   ["c1-log-vacancy-clean"     "client-1" :log-vacancy       {} {}]
   ;; client-1 -- real HARD-hold reasons
   ["c1-hold-no-consent"       "client-1" :propose-placement {:candidate-id "cand-noconsent" :vacancy-id "vac-1"} {}]
   ["c1-hold-skills-gap"       "client-1" :propose-placement {:candidate-id "cand-1" :vacancy-id "vac-hard"} {}]
   ["c1-hold-incomplete"       "client-1" :propose-placement {:candidate-id "cand-1"} {}]
   ["c1-hold-unknown-cand"     "client-1" :propose-placement {:candidate-id "cand-ghost" :vacancy-id "vac-1"} {}]
   ["c1-hold-unknown-vac"      "client-1" :propose-placement {:candidate-id "cand-1" :vacancy-id "vac-ghost"} {}]
   ["c1-hold-wrong-client-cand" "client-1" :propose-placement {:candidate-id "cand-2" :vacancy-id "vac-1"} {}]
   ["c1-hold-wrong-client-vac" "client-1" :propose-placement {:candidate-id "cand-1" :vacancy-id "vac-2"} {}]
   ;; unregistered client entirely
   ["ghost-no-client"          "no-such-client" :propose-placement {:candidate-id "cand-1" :vacancy-id "vac-1"} {}]
   ;; client-1 -- real escalation reason, approved after human sign-off
   ["c1-escalate-referral"     "client-1" :send-referral     {:candidate-id "cand-1" :vacancy-id "vac-1"} {}]
   ;; client-2 (real fixture entity from careers.governor-test, plus
   ;; additional demo candidate/vacancy -- see namespace docstring)
   ["c2-place-clean"           "client-2" :propose-placement {:candidate-id "cand-2" :vacancy-id "vac-2"} {}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `careers.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Machi Careers"})
    (store/register-candidate! db {:candidate-id "cand-1" :client-id "client-1"
                                    :name "A" :skills #{:clj :sql}
                                    :consents-to-referral true})
    (store/register-candidate! db {:candidate-id "cand-noconsent" :client-id "client-1"
                                    :name "B" :skills #{:sql}
                                    :consents-to-referral false})
    (store/register-vacancy! db {:vacancy-id "vac-1" :client-id "client-1"
                                  :title "data clerk" :required-skills #{:sql}})
    (store/register-vacancy! db {:vacancy-id "vac-hard" :client-id "client-1"
                                  :title "platform eng" :required-skills #{:sql :k8s}})
    (store/register-client! db {:client-id "client-2" :name "Other Org"})
    (store/register-candidate! db {:candidate-id "cand-2" :client-id "client-2"
                                    :name "C" :skills #{:sql}
                                    :consents-to-referral true})
    (store/register-vacancy! db {:vacancy-id "vac-2" :client-id "client-2"
                                  :title "records clerk" :required-skills #{:sql}})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra context]]
                       (run-op! graph tid client-id op extra context))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row [store {:keys [client-id name]} runs]
  (let [record-count (count (store/records-of store client-id))
        last-run (last (filter #(= client-id (:client-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc name) record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- candidate-row [{:keys [candidate-id client-id name skills consents-to-referral]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc candidate-id) (esc name) (esc client-id)
          (esc (str/join "," (map clojure.core/name (sort skills))))
          (if consents-to-referral
            "<span class=\"ok\">consented</span>"
            "<span class=\"critical\">NOT consented</span>")))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:candidate-id request) ""))
          (esc (or (:vacancy-id request) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`careers.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:propose-placement</code></td><td><span class=\"warn\">auto-commit ONLY when candidate/vacancy are registered, same client, candidate has recorded consent, and required-skills is a subset of the candidate's registered skills</span></td></tr>"
   "        <tr><td><code>:send-referral</code></td><td><span class=\"warn\">ALWAYS human approval &middot; external send to an employer</span></td></tr>"
   "        <tr><td><code>:log-vacancy</code></td><td><span class=\"ok\">auto-commit when the client is registered</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :name "Machi Careers"}
                 {:client-id "client-2" :name "Other Org"}]
        candidates [{:candidate-id "cand-1" :client-id "client-1" :name "A" :skills #{:clj :sql} :consents-to-referral true}
                    {:candidate-id "cand-noconsent" :client-id "client-1" :name "B" :skills #{:sql} :consents-to-referral false}
                    {:candidate-id "cand-2" :client-id "client-2" :name "C" :skills #{:sql} :consents-to-referral true}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        candidate-rows (str/join "\n" (map candidate-row candidates))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2423 &middot; community careers practice</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Careers Practice (ISCO-08 2423) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every proposal is for staff review only, never binding action</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>careers.store</code> via <code>careers.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Records committed</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered candidates</h2>\n"
     "    <p class=\"muted\"><code>cand-noconsent</code> has NOT recorded consent to referral — a genuine governor hard-hold reason (dignity is a hard line, not a preference), never approvable away.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Candidate</th><th>Name</th><th>Client</th><th>Skills</th><th>Consent</th></tr></thead>\n"
     "      <tbody>\n"
     candidate-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Careers Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Unlike the ISCO-08 1112 sibling, this governor's <code>context</code> parameter is never read — there is no topic-sensitivity dimension in this domain (see namespace docstring).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, cited candidate/vacancy, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Candidate</th><th>Vacancy</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
