(ns vault.client.http-test
  (:require
    [clojure.test :refer :all]
    [vault.client.http :refer [http-client] :as h]
    [vault.core :as vault]))


(def example-url "https://vault.example.com")


(deftest http-client-instantiation
  (is (thrown? IllegalArgumentException
        (http-client nil)))
  (is (thrown? IllegalArgumentException
        (http-client :foo)))
  (is (instance? vault.client.http.HTTPClient (http-client example-url))))


(deftest http-read-checks
  (let [client (http-client example-url)]
    (is (thrown? IllegalArgumentException
          (vault/read-secret client nil))
        "should throw an exception on non-string path")
    (is (thrown? IllegalStateException
          (vault/read-secret client "secret/foo/bar"))
        "should throw an exception on unauthenticated client")))


(deftest app-role
  (let [api-endpoint (str example-url "/v1/auth/approle/login")
        client (http-client example-url)
        connection-attempt (atom nil)]
    (with-redefs [h/do-api-request
                  (fn [method url req]
                    (reset! connection-attempt url))
                  h/api-auth!
                  (fn [claim auth-ref response] nil)]
      (vault/authenticate! client :app-role {:secret-id "secret"
                                             :role-id "role-id"})
      (is (= @connection-attempt api-endpoint)
          (str "should attempt to auth with: " api-endpoint)))))


(deftest authenticate-via-k8s
  (testing "When a token file is available"
    (let [client (http-client example-url)
          api-requests (atom [])
          api-auths (atom [])]
      (with-redefs [h/do-api-request (fn [& args]
                                       (swap! api-requests conj args)
                                       :do-api-request-response)
                    h/api-auth! (fn [& args]
                                  (swap! api-auths conj args)
                                  :api-auth!-response)]
        (vault/authenticate! client :k8s {:jwt "fake-jwt-goes-here"
                                          :role "my-role"})
        (is (= [[:post
                 (str example-url "/v1/auth/kubernetes/login")
                 {:form-params {:jwt "fake-jwt-goes-here" :role "my-role"}
                  :content-type :json
                  :accept :json
                  :as :json}]]
               @api-requests))
        (is (= [[(str "Kubernetes auth role=my-role")
                 (:auth client)
                 :do-api-request-response]]
               @api-auths)))))
  (testing "When no jwt is specified"
    (let [client (http-client example-url)
          api-requests (atom [])
          api-auths (atom [])]
      (with-redefs [h/do-api-request (fn [& args]
                                       (swap! api-requests conj args))
                    h/api-auth! (fn [& args]
                                  (swap! api-auths conj args))]
        (is (thrown? IllegalArgumentException
              (vault/authenticate! client :k8s {:role "my-role"})))
        (is (empty? @api-requests))
        (is (empty? @api-auths)))))
  (testing "When no role is specified"
    (let [client (http-client example-url)
          api-requests (atom [])
          api-auths (atom [])]
      (with-redefs [h/do-api-request (fn [& args]
                                       (swap! api-requests conj args))
                    h/api-auth! (fn [& args]
                                  (swap! api-auths conj args))]
        (is (thrown? IllegalArgumentException
              (vault/authenticate! client :k8s {:jwt "fake-jwt-goes-here"})))
        (is (empty? @api-requests))
        (is (empty? @api-auths))))))
