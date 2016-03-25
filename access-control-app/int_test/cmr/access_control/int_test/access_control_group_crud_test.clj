(ns cmr.access-control.int-test.access-control-group-crud-test
    (:require [clojure.test :refer :all]
              [clojure.string :as str]
              [cmr.mock-echo.client.echo-util :as e]
              [cmr.access-control.int-test.fixtures :as fixtures]
              [cmr.access-control.test.util :as u]))

(use-fixtures :once (fixtures/int-test-fixtures))

(use-fixtures :each
              (fixtures/reset-fixture {"prov1guid" "PROV1" "prov2guid" "PROV2"})
              (fixtures/grant-all-group-fixture ["prov1guid" "prov2guid"]))

;; TODO CMR-2134, CMR-2133 test creating groups without various permissions

(def field-maxes
  "A map of fields to their max lengths"
  {:name 100
   :description 255
   :legacy_guid 50})

(defn string-of-length
  "Creates a string of the specified length"
  [n]
  (str/join (repeat n "x")))

(deftest create-group-validation-test
  (let [valid-user-token (e/login (u/conn-context) "user1")
        valid-group (u/make-group)]

    (testing "Create group with invalid content type"
      (is (= {:status 400,
              :errors
              ["The mime types specified in the content-type header [application/xml] are not supported."]}
             (u/create-group valid-user-token valid-group {:http-options {:content-type :xml}}))))

    (testing "Create group with invalid JSON"
      (is (= {:status 400,
              :errors
              ["Invalid JSON: Unexpected character ('{' (code 123)): was expecting double-quote to start field name\n at  line: 1, column: 3]"]}
             (u/create-group valid-user-token valid-group {:http-options {:body "{{{"}}))))

    (testing "Missing field validations"
      (are [field]
        (= {:status 400
            :errors [(format "object has missing required properties ([\"%s\"])" (name field))]}
           (u/create-group valid-user-token (dissoc valid-group field)))

        :name :description))

    (testing "Minimum field length validations"
      (are [field]
        (= {:status 400
            :errors [(format "/%s string \"\" is too short (length: 0, required minimum: 1)"
                             (name field))]}
           (u/create-group valid-user-token (assoc valid-group field "")))

        :name :description :provider_id :legacy_guid))

    (testing "Maximum field length validations"
      (doseq [[field max-length] field-maxes]
        (let [long-value (string-of-length (inc max-length))]
          (is (= {:status 400
                  :errors [(format "/%s string \"%s\" is too long (length: %d, maximum allowed: %d)"
                                   (name field) long-value (inc max-length) max-length)]}
                 (u/create-group
                  valid-user-token
                  (assoc valid-group field long-value)))))))))

(deftest create-system-group-test
  (testing "Successful creation"
    (let [group (u/make-group)
          token (e/login (u/conn-context) "user1")
          {:keys [status concept_id revision_id]} (u/create-group token group)]
      (is (= 200 status))
      (is (re-matches #"AG\d+-CMR" concept_id) "Incorrect concept id for a system group")
      (is (= 1 revision_id))
      (u/assert-group-saved group "user1" concept_id revision_id)

      (testing "Creation with an already existing name"
        (testing "Is rejected for another system group"
          (is (= {:status 409
                  :errors [(format "A system group with name [%s] already exists with concept id [%s]."
                                   (:name group) concept_id)]}
                 (u/create-group token group))))

        (testing "Works for a different provider"
          (is (= 200 (:status (u/create-group token (assoc group :provider_id "PROV1")))))))

      (testing "Creation of previously deleted group"
        (u/delete-group token concept_id)
        (let [new-group (assoc group :legacy_guid "the legacy guid" :description "new description")
              response (u/create-group token new-group)]
          (is (= {:status 200 :concept_id concept_id :revision_id 3}
                 response))
          (u/assert-group-saved new-group "user1" concept_id 3))))

    (testing "Create group with fields at maximum length"
      (let [group (into {} (for [[field max-length] field-maxes]
                             [field (string-of-length max-length)]))]
        (is (= 200 (:status (u/create-group (e/login (u/conn-context) "user1") group)))))))

  (testing "Creation without optional fields is allowed"
    (let [group (dissoc (u/make-group {:name "name2"}) :legacy_guid)
          token (e/login (u/conn-context) "user1")
          {:keys [status concept_id revision_id]} (u/create-group token group)]
      (is (= 200 status))
      (is concept_id)
      (is (= 1 revision_id)))))

(deftest create-provider-group-test
  (testing "Successful creation"
    (let [group (u/make-group {:provider_id "PROV1"})
          token (e/login (u/conn-context) "user1")
          {:keys [status concept_id revision_id]} (u/create-group token group)]
      (is (= 200 status))
      (is (re-matches #"AG\d+-PROV1" concept_id) "Incorrect concept id for a provider group")
      (is (= 1 revision_id))
      (u/assert-group-saved group "user1" concept_id revision_id)

      (testing "Creation with an already existing name"
        (testing "Is rejected for the same provider"
          (is (= {:status 409
                  :errors [(format
                            "A provider group with name [%s] already exists with concept id [%s] for provider [PROV1]."
                            (:name group) concept_id)]}
                 (u/create-group token group))))

        (testing "Works for a different provider"
          (is (= 200 (:status (u/create-group token (assoc group :provider_id "PROV2")))))))))
  (testing "Creation for a non-existent provider"
    (is (= {:status 400
            :errors ["Provider with provider-id [NOT_EXIST] does not exist."]}
           (u/create-group (e/login (u/conn-context) "user1")
                           (u/make-group {:provider_id "NOT_EXIST"}))))))

(deftest get-group-test
  (let [group (u/make-group)
        token (e/login (u/conn-context) "user1")
        {:keys [concept_id]} (u/create-group token group)]
    (testing "Retrieve existing group"
      (is (= (assoc group :status 200 :num_members 0)
             (u/get-group token concept_id))))

    (testing "Retrieve unknown group"
      (is (= {:status 404
              :errors ["Group could not be found with concept id [AG100-CMR]"]}
             (u/get-group token "AG100-CMR"))))
    (testing "Retrieve group with bad concept-id"
      (is (= {:status 400
              :errors ["Concept-id [F100-CMR] is not valid."]}
             (u/get-group token "F100-CMR"))))
    (testing "Retrieve group with invalid parameters"
      (let [response (u/get-group token concept_id {"Echo-Token" "asdf" "bf2376tri7f" "true"})]
        (is (= {:status 400
                :errors #{"Parameter [Echo-Token] was not recognized." "Parameter [bf2376tri7f] was not recognized."}}
               (update-in response [:errors] set)))))
    (testing "Retrieve group with concept id for a different concept type"
      (is (= {:status 400
              :errors ["[C100-PROV1] is not a valid group concept id."]}
             (u/get-group token "C100-PROV1"))))
    (testing "Retrieve group with bad provider in concept id"
      (is (= {:status 404
              :errors ["Group could not be found with concept id [AG100-PROV3]"]}
             (u/get-group token "AG100-PROV3"))))
    (testing "Retrieve deleted group"
      (u/delete-group token concept_id)
      (is (= {:status 404
              :errors [(format "Group with concept id [%s] was deleted." concept_id)]}
             (u/get-group token concept_id))))))

(deftest delete-group-test
  (let [group1 (u/make-group)
        group2 (u/make-group {:name "Some other group"})
        token (e/login (u/conn-context) "user1")
        {:keys [concept_id revision_id]} (u/create-group token group1)
        group2-concept-id (:concept_id (u/create-group token group2))]
    (u/wait-until-indexed)
    (testing "Delete without token"
      (is (= {:status 401
              :errors ["Groups cannot be modified without a valid user token."]}
             (u/delete-group nil concept_id))))

    (testing "Delete success"
      (is (= 2 (:hits (u/search token nil))))
      (is (= {:status 200 :concept_id concept_id :revision_id 2}
             (u/delete-group token concept_id)))
      (u/wait-until-indexed)
      (u/assert-group-deleted group1 "user1" concept_id 2)
      (is (= [group2-concept-id] (map :concept_id (:items (u/search token nil))))))

    (testing "Delete group that was already deleted"
      (is (= {:status 404
              :errors [(format "Group with concept id [%s] was deleted." concept_id)]}
             (u/delete-group token concept_id))))

    (testing "Delete group that doesn't exist"
      (is (= {:status 404
              :errors ["Group could not be found with concept id [AG100-CMR]"]}
             (u/delete-group token "AG100-CMR"))))))

(deftest update-group-test
  (let [group (u/make-group)
        token (e/login (u/conn-context) "user1")
        {:keys [concept_id revision_id]} (u/create-group token group)]

    (let [updated-group (update-in group [:description] #(str % " updated"))
          token2 (e/login (u/conn-context) "user2")
          response (u/update-group token2 concept_id updated-group)]
      (is (= {:status 200 :concept_id concept_id :revision_id 2}
             response))
      (u/assert-group-saved updated-group "user2" concept_id 2))))

(deftest update-group-failure-test
  (let [group (u/make-group)
        token (e/login (u/conn-context) "user1")
        {:keys [concept_id revision_id]} (u/create-group token group)]

    (testing "Update group with invalid content type"
      (is (= {:status 400,
              :errors
              ["The mime types specified in the content-type header [application/xml] are not supported."]}
             (u/update-group token concept_id group {:http-options {:content-type :xml}}))))

    (testing "Update without token"
      (is (= {:status 401
              :errors ["Groups cannot be modified without a valid user token."]}
             (u/update-group nil concept_id group))))

    (testing "Fields that cannot be changed"
      (are [field human-name]
           (= {:status 400
               :errors [(format (str "%s cannot be modified. Attempted to change existing value"
                                     " [%s] to [updated]")
                                human-name
                                (get group field))]}
              (u/update-group token concept_id (assoc group field "updated")))
           :name "Name"
           :provider_id "Provider Id"
           :legacy_guid "Legacy Guid"))

    (testing "Updates applies JSON validations"
      (is (= {:status 400
              :errors ["/description string \"\" is too short (length: 0, required minimum: 1)"]}
             (u/update-group token concept_id (assoc group :description "")))))

    (testing "Update group that doesn't exist"
      (is (= {:status 404
              :errors ["Group could not be found with concept id [AG100-CMR]"]}
             (u/update-group token "AG100-CMR" group))))

    (testing "Update deleted group"
      (u/delete-group token concept_id)
      (is (= {:status 404
              :errors [(format "Group with concept id [%s] was deleted." concept_id)]}
             (u/update-group token concept_id group))))))
