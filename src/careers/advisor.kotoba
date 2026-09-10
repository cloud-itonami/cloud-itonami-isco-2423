(ns careers.advisor
  "CareersAdvisor — proposes a careers operation (placement match,
  referral send, vacancy log) for a registered practice client.
  Swappable mock/llm; the advisor ONLY proposes — `careers.governor`
  checks consent and skills-set inclusion independently. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :propose-placement|:send-referral|:log-vacancy
               :effect :propose :candidate-id str :vacancy-id str
               :stake kw :confidence n :rationale str}"
  ;; clojure.edn, not clojure.core/read-string: this parses untrusted
  ;; advisor output, and the core reader executes #=(...) at read time.
  (:require [clojure.edn :as edn]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake candidate-id vacancy-id] :as request}]
  {:op op
   :effect :propose
   :candidate-id candidate-id
   :vacancy-id vacancy-id
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a careers advisor. Given a request, propose an :op, the
   cited :candidate-id and :vacancy-id, an honest :confidence and a
   :stake. Never propose a candidate without recorded consent, and
   never overstate a skills match — the governor checks both.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
