(ns careers.store
  "SSoT for the ISCO-08 2423 community careers actor (itonami actor
  pattern, ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4311's bookkeeping.store. This is the actor that
  feeds the 7810 labour-exchange lane (ADR-2607121000 P2): placements
  are how liberated work transmits into continued income
  (ADR-2607122100 R4 loop).

  Domain:

    client     — the careers practice's registered client organization
                 (:client-id, :name)
    candidate  — a registered person {:candidate-id :client-id :name
                 :skills #{kw} :consents-to-referral bool}. NEVER
                 referred without their recorded consent.
    vacancy    — a registered opening {:vacancy-id :client-id :title
                 :required-skills #{kw}}.
    record     — a committed operating record (placement proposal,
                 sent referral, logged vacancy) — written ONLY via
                 commit-record!.
    ledger     — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (candidate [s candidate-id])
  (vacancy [s vacancy-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-candidate! [s c])
  (register-vacancy! [s v])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (candidate [_ candidate-id] (get-in @a [:candidates candidate-id]))
  (vacancy [_ vacancy-id] (get-in @a [:vacancies vacancy-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-candidate! [s c]
    (swap! a assoc-in [:candidates (:candidate-id c)] c) s)
  (register-vacancy! [s v]
    (swap! a assoc-in [:vacancies (:vacancy-id v)] v) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :candidates {} :vacancies {}
                                    :records [] :ledger []}
                                   seed)))))
