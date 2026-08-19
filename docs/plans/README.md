# Plans

Working documents, one per milestone, kept for the reasoning behind decisions rather than as
reference material. The lab itself is documented in [`../CONCEPTS.md`](../CONCEPTS.md) and the
module READMEs — start there.

They live here rather than in the repository root so the root stays the lab, not its paperwork.

| Plan | Status |
| --- | --- |
| [2026-08-step0-federation-lab.md](2026-08-step0-federation-lab.md) | **Superseded.** The original single-stack design. Its "no WebFlux, no subscriptions" constraints no longer hold. |
| [2026-08-dual-stack.md](2026-08-dual-stack.md) | Delivered in `807288c`. Stack groups over a shared domain model. |
| [2026-08-test-contracts.md](2026-08-test-contracts.md) | Delivered in `155d8d2`. Each stack's unit tests assert its own execution contract. |
| [2026-08-inventory-subgraph.md](2026-08-inventory-subgraph.md) | Delivered in `aa9b35f`. **Still live:** its appendix records the change surface for wiring inventory into the federated graph, which has not been done. |

The inventory appendix is the one part worth reading before new work. Adding a third subgraph to
the composed graph touches nine hand-maintained places across six files, three of which fail
*silently* — the guards in `export-subgraphs.sh`, the `cmp` list in `compose-check.sh`, and the CI
cache paths.
