(ns cmr.graph.data.import
  "Functions for importing data into neo4j."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.data.csv :as csv]
   [cmr.graph.data.statement :as statement]
   [digest :as digest]))

(def json-collections-filename
  "data/all_public_collections_from_es.json")

(def test-file
  "data/testfile.json")

(def collection-csv-file
  "data/collections.csv")

(def collection-url-csv-file
  "data/collection_and_urls.csv")

(def collection-data-center-csv-file
  "data/collection_and_data_centers.csv")

(def url-fields
  "List of fields we are interested in parsing from a given URL."
  [:type :url])

(def relevant-fields
  "List of fields to parse from a collection record."
  [:concept-id :provider-id :entry-id :related-urls :data-center :version-id :metadata-format])

(defn parse-url-into-nodes
  "Parses a single URL field into all the nodes we want to create for the URL."
  [url]
  (select-keys (json/parse-string url true) url-fields))

(defn md5-leo
  "When a hash just isn't good enough."
  [value]
  (str "A" (digest/md5 value)))

(defn prepare-collection-for-import
  "Returns only the relevant JSON fields from the provided collection record for import into neo4j."
  [collection]
  (update (select-keys (:fields collection) relevant-fields)
          :related-urls
          (fn [urls]
            (mapv parse-url-into-nodes urls))))

(defn read-json-file
  "Reads a JSON file into memory"
  [filename]
  (json/parse-string (slurp (io/resource filename)) true))

(def collection-columns
  "Columns in the collections CSV file."
  ["MD5Leo" "ConceptId" "ProviderId" "VersionId" "MetadataFormat"])

(defn collection->row
  "Returns a row to write to the collections CSV file for a given collection."
  [collection]
  (let [{:keys [provider-id concept-id version-id metadata-format]} collection]
    [(md5-leo (first concept-id))
     (first concept-id)
     (first provider-id)
     (first version-id)
     (first metadata-format)]))

(defn write-collection-csv
  "Creates the collection csv file"
  [collections output-filename]
  (with-open [csv-file (io/writer output-filename)]
    (csv/write-csv csv-file [collection-columns])
    (csv/write-csv csv-file (mapv collection->row collections))))

(defn construct-collection-url-row
  "Creates a collection URL row for a relationship CSV file."
  [collection url]
  [(md5-leo (first (:concept-id collection)))
   (:url url)
   (:type url)])

(defn construct-collection-data-center-row
  "Creates a collection data center row for a relationship CSV file."
  [collection data-center]
  [(md5-leo (first (:concept-id collection)))
   data-center])

(defn write-collection-url-relationship-csv
  "Creates the collection<->url relationship csv file."
  [collections output-filename]
  (let [rows (doall
              (for [collection collections
                    url (:related-urls collection)]
                (construct-collection-url-row collection url)))]
    (with-open [csv-file (io/writer output-filename)]
      (csv/write-csv csv-file [["CollectionMD5Leo" "URL" "URLType"]])
      (csv/write-csv csv-file rows))))

(defn write-collection-data-center-relationship-csv
  "Creates the collection<->data centers relationship csv file."
  [collections output-filename]
  (let [rows (doall
              (for [collection collections
                    data-center (:data-center collection)]
                (construct-collection-data-center-row collection data-center)))]
    (with-open [csv-file (io/writer output-filename)]
      (csv/write-csv csv-file [["CollectionMD5Leo" "DataCenter"]])
      (csv/write-csv csv-file rows))))



(comment
 (prepare-collection-for-import (first (:hits (:hits (read-json-file json-collections-filename)))))
 (mapv prepare-collection-for-import (:hits (:hits (read-json-file test-file))))
 (prepare-collection-for-import (first (:hits (:hits (read-json-file json-collections-filename)))))

 (write-collection-csv (mapv prepare-collection-for-import (:hits (:hits (read-json-file json-collections-filename))))
                       (str "resources/" collection-csv-file))

 (write-collection-url-relationship-csv (mapv prepare-collection-for-import (:hits (:hits (read-json-file json-collections-filename))))
                                        (str "resources/" collection-url-csv-file))

 (write-collection-data-center-relationship-csv (mapv prepare-collection-for-import (:hits (:hits (read-json-file json-collections-filename))))
                                                (str "resources/" collection-data-center-csv-file))

 (mapv prepare-collection-for-import (:hits (:hits (read-json-file test-file))))
 (println
  (statement/neo4j-statements (mapv prepare-collection-for-import (:hits (:hits (read-json-file test-file)))))))
