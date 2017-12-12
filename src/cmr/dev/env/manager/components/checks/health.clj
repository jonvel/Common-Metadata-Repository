(ns cmr.dev.env.manager.components.checks.health
  (:require
    [cmr.dev.env.manager.components.docker :as docker])
  (:import
    (cmr.dev.env.manager.components.docker DockerRunner)))

(defprotocol Healthful
  (get-status [this]
    "Performs a health check on a given component."))

(extend DockerRunner
        Healthful
        docker/healthful-behaviour)
