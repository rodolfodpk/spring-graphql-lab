SHELL := /bin/sh

.PHONY: help test build subgraphs export-schemas compose compose-check up down e2e smoke verify clean

help:
	@printf '%s\n' 'make test compose up down e2e verify'

test:
	cd ../products-subgraph && ./mvnw verify
	cd ../pricing-subgraph && ./mvnw verify
	./mvnw test

build:
	docker compose build products pricing

subgraphs:
	docker compose up -d --build products pricing
	./scripts/wait-for-healthy.sh

export-schemas: subgraphs
	./scripts/export-subgraphs.sh schemas

compose: subgraphs
	./scripts/compose-supergraph.sh

compose-check: subgraphs
	./scripts/compose-check.sh

up: subgraphs compose-check
	docker compose up -d --force-recreate router
	./scripts/wait-for-healthy.sh

down:
	docker compose --profile tooling down --remove-orphans

e2e:
	./mvnw verify

smoke:
	./scripts/smoke-test.sh

verify:
	@set -e; \
	trap '$(MAKE) down' EXIT HUP INT TERM; \
	$(MAKE) test; \
	$(MAKE) up; \
	$(MAKE) smoke; \
	$(MAKE) e2e

clean:
	cd ../products-subgraph && ./mvnw clean
	cd ../pricing-subgraph && ./mvnw clean
	./mvnw clean
