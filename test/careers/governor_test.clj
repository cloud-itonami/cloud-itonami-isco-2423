(ns careers.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [careers.store :as store]
            [careers.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Machi Careers"})
    (store/register-candidate! st {:candidate-id "cand-1" :client-id "client-1"
                                   :name "A" :skills #{:clj :sql}
                                   :consents-to-referral true})
    (store/register-candidate! st {:candidate-id "cand-noconsent" :client-id "client-1"
                                   :name "B" :skills #{:clj :sql}
                                   :consents-to-referral false})
    (store/register-vacancy! st {:vacancy-id "vac-1" :client-id "client-1"
                                 :title "data clerk" :required-skills #{:sql}})
    (store/register-vacancy! st {:vacancy-id "vac-hard" :client-id "client-1"
                                 :title "platform eng" :required-skills #{:sql :k8s}})
    st))

(defn- placement [cand vac]
  {:op :propose-placement :effect :propose :candidate-id cand :vacancy-id vac
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-on-consented-qualified-placement
  (let [st (fresh-store)
        v (governor/check req {} (placement "cand-1" "vac-1") st)]
    (is (:ok? v))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (placement "cand-1" "vac-1") st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (placement "cand-1" "vac-1")
                                        :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest hard-on-incomplete-placement
  (let [st (fresh-store)
        v (governor/check req {} (placement "cand-1" nil) st)]
    (is (:hard? v))
    (is (some #(= :incomplete-placement (:rule %)) (:violations v)))))

(deftest hard-on-invented-candidate
  (let [st (fresh-store)
        v (governor/check req {} (placement "cand-ghost" "vac-1") st)]
    (is (:hard? v))
    (is (some #(= :unknown-candidate (:rule %)) (:violations v)))))

(deftest hard-on-invented-vacancy
  (let [st (fresh-store)
        v (governor/check req {} (placement "cand-1" "vac-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-vacancy (:rule %)) (:violations v)))))

(deftest hard-on-missing-consent
  (testing "a person's missing consent is not approvable — ask the person"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (placement "cand-noconsent" "vac-1")
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :no-consent (:rule %)) (:violations v))))))

(deftest hard-on-skills-gap
  (testing "set inclusion against registered skills — the remedy is
            evidence or training (isco-2424), not approving harder"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (placement "cand-1" "vac-hard")
                                          :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :skills-gap (:rule %)) (:violations v))))))

(deftest escalates-referral-send
  (let [st (fresh-store)
        v (governor/check req {} {:op :send-referral :effect :propose
                                  :candidate-id "cand-1" :vacancy-id "vac-1"
                                  :confidence 0.9 :stake :medium} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} {:op :log-vacancy :effect :propose
                                  :confidence 0.3 :stake :low} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
