(ns careers.governor
  "CareersGovernor — the independent safety/traceability layer for the
  ISCO-08 2423 community careers actor (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Careers-specific
  twists: a placement must respect the candidate's RECORDED CONSENT
  (dignity is a hard line, not a preference), and the skills match is
  checked DETERMINISTICALLY as set inclusion against the registered
  records — the advisor's enthusiasm about a fit is never trusted.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the practice's client must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. real people, real jobs — a :propose-placement must cite a
                           REGISTERED candidate and vacancy belonging
                           to this client (no invented people, no
                           invented openings).
    4. consent           — the candidate's :consents-to-referral must
                           be true. A human approver cannot approve
                           away a person's missing consent; the remedy
                           is asking the person.
    5. skills basis      — the vacancy's :required-skills must be a
                           subset of the candidate's REGISTERED
                           :skills. The remedy for a gap is updating
                           the skill record with evidence (or
                           training, see isco-2424), not approving
                           harder.
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :send-referral (external-send to an employer).
    7. low confidence (< `confidence-floor`)."
  (:require [clojure.set :as set]
            [careers.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:send-referral})

(defn- hard-violations [{:keys [request proposal]} client-record cand vac]
  (let [{:keys [op candidate-id vacancy-id]} proposal
        placing? (= :propose-placement op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and placing? (or (nil? candidate-id) (nil? vacancy-id)))
      (conj {:rule :incomplete-placement :detail "candidate と vacancy の両方の引用が必須"})

      (and placing? candidate-id (nil? cand))
      (conj {:rule :unknown-candidate :detail (str "未登録 candidate: " candidate-id)})

      (and placing? vacancy-id (nil? vac))
      (conj {:rule :unknown-vacancy :detail (str "未登録 vacancy: " vacancy-id)})

      (and placing? cand (not= (:client-id cand) (:client-id request)))
      (conj {:rule :candidate-wrong-client :detail "candidate が別 client のもの"})

      (and placing? vac (not= (:client-id vac) (:client-id request)))
      (conj {:rule :vacancy-wrong-client :detail "vacancy が別 client のもの"})

      (and placing? cand (not (true? (:consents-to-referral cand))))
      (conj {:rule :no-consent
             :detail "本人の紹介同意が記録されていない（同意の欠如は承認で越えられない — 本人に聞くこと）"})

      (and placing? cand vac
           (not (set/subset? (set (:required-skills vac)) (set (:skills cand)))))
      (conj {:rule :skills-gap
             :detail (str "不足スキル: "
                          (vec (set/difference (set (:required-skills vac))
                                               (set (:skills cand))))
                          "（是正はスキル記録の更新か研修 — 承認の強行ではない）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `careers.store/Store`. Pure — never mutates the
  store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        cand (some->> (:candidate-id proposal) (store/candidate store))
        vac (some->> (:vacancy-id proposal) (store/vacancy store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record cand vac)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
