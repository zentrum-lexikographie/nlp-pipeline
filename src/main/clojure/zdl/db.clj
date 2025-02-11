(ns zdl.db
  (:require
   [next.jdbc :as jdbc])
  (:import
   (com.pgvector PGvector)))

(def db
  {:dbtype   "postgresql"
   :host     "localhost"
   :dbname   "nlp"
   :user     "nlp"
   :password "nlp"})

(defmacro with-transaction
  [[tx :as bindings] & body]
  `(jdbc/with-transaction ~bindings
     (PGvector/registerTypes ~tx)
     ~@body))

(comment
  (class (float 1))
  (with-transaction [t db]
    (jdbc/execute! t ["INSERT INTO items (embedding) VALUES (?)"
                       (PGvector. (float-array [1 1 1]))]))


  (jdbc/with-transaction [tx db]
    (PGvector/registerTypes tx)
    (jdbc/execute! tx ["SELECT * FROM items ORDER BY embedding <-> ? LIMIT 5"
                       (PGvector. (float-array [1 1 1]))]))

  (jdbc/execute! db ["CREATE TABLE items (id bigserial PRIMARY KEY, embedding vector(3))"])
  (jdbc/execute! db ["CREATE EXTENSION IF NOT EXISTS vector"]))
