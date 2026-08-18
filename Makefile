SHELL := /bin/sh

# Thin delegator. Every target is defined in supergraph/Makefile, which stays authoritative --
# its scripts, compose file and Rover volumes are all relative to that directory. This exists so
# the repository root, which already owns pom.xml, mvnw and .dockerignore, is also a working
# entry point for the build.
#
#   make verify-all
#   make up STACK=servlet
#
# STACK is forwarded rather than defaulted here, so the child Makefile remains the single place
# that validates it and rejects a typo.

STACK ?= reactive

TARGETS := test build subgraphs export-schemas compose compose-check \
           up down e2e smoke verify verify-all clean

.DEFAULT_GOAL := help
.PHONY: help $(TARGETS)

help:
	@printf '%s\n' 'Delegates to supergraph/Makefile. Targets:'
	@printf '  %s\n' $(TARGETS)
	@printf '%s\n' 'Add STACK=reactive|servlet to pick a stack (default reactive).'

$(TARGETS):
	@$(MAKE) --no-print-directory -C supergraph $@ STACK=$(STACK)
