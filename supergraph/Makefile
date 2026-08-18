SHELL := /bin/sh

# Which stack the subgraph images are built from. Compose reads this to pick a Dockerfile, and the
# Maven targets below to pick a module path. The explicit error matters: with a plain ifeq/else a
# typo would silently fall through to reactive and report a green run for a stack never built.
STACK ?= reactive
ifeq ($(STACK),reactive)
else ifeq ($(STACK),servlet)
else
$(error STACK must be 'reactive' or 'servlet', got '$(STACK)')
endif
export STACK

# Every Maven invocation goes through the root wrapper. The nested wrappers are gone, because a
# standalone module build cannot resolve lab-model.
MVN := cd .. && ./mvnw

.PHONY: help test build subgraphs export-schemas compose compose-check up down e2e smoke verify verify-all clean

help:
	@printf '%s\n' 'Targets: test build subgraphs export-schemas compose compose-check'
	@printf '%s\n' '         up smoke e2e verify verify-all down clean'
	@printf '%s\n' 'All accept STACK=reactive|servlet (default reactive).'
	@printf '%s\n' 'See README.md for what each one does.'

test:
	$(MVN) -pl model,$(STACK)/products-subgraph,$(STACK)/pricing-subgraph -am verify
	$(MVN) -pl supergraph test

build:
	docker compose build products pricing

subgraphs:
	docker compose up -d --build --force-recreate products pricing
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
	$(MVN) -pl supergraph verify

smoke:
	./scripts/smoke-test.sh

verify:
	@set -e; \
	trap '$(MAKE) down' EXIT HUP INT TERM; \
	$(MAKE) test; \
	$(MAKE) up; \
	$(MAKE) smoke; \
	$(MAKE) e2e

# Teardown before each stack, not merely between them. The two stacks share service names, host
# ports and image tags, so a leftover container from an aborted run is the likeliest route to a
# false green.
verify-all:
	@set -e; \
	$(MAKE) down STACK=reactive; $(MAKE) verify STACK=reactive; \
	$(MAKE) down STACK=servlet;  $(MAKE) verify STACK=servlet

clean:
	$(MVN) clean
