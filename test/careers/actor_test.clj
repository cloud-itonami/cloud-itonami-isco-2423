(ns careers.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [careers.actor :as actor]
            [careers.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Machi Careers"})
    (store/register-candidate! st {:candidate-id "cand-1" :client-id "client-1"
                                   :name "A" :skills #{:clj :sql}
                                   :consents-to-referral true})
    (store/register-candidate! st {:candidate-id "cand-noconsent" :client-id "client-1"
                                   :name "B" :skills #{:sql}
                                   :consents-to-referral false})
    (store/register-vacancy! st {:vacancy-id "vac-1" :client-id "client-1"
                                 :title "data clerk" :required-skills #{:sql}})
    st))

(deftest commits-a-consented-qualified-placement
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :propose-placement :stake :low
                 :candidate-id "cand-1" :vacancy-id "vac-1"}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-a-consentless-placement-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :propose-placement :stake :low
                 :candidate-id "cand-noconsent" :vacancy-id "vac-1"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-sends-referral-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :send-referral :stake :medium
                 :candidate-id "cand-1" :vacancy-id "vac-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
