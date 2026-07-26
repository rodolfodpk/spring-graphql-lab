# SGL Local Federation

Local Apollo Federation reference implementation with two synchronous Spring
Boot subgraphs:

- **Products** owns the catalog and runs on port `8081`.
- **Pricing** adds commercial fields and runs on port `8082`.
- **Apollo Router** exposes the federated client endpoint on port `4000`.

All data is deterministic and held in plain Java collections. There is no
database or cloud dependency.

## Repository layout

Keep the three repositories next to each other:

```text
sgl/
├── sgl-products/
├── sgl-pricing/
└── sgl-local/
```

Run all commands in this README from `sgl-local`.

## Prerequisites

- Java 21 or newer
- Docker Desktop, or another Docker installation with Compose v2
- Make
- curl

Confirm the main tools are available:

```sh
java -version
docker version
docker compose version
make --version
curl --version
```

The Maven wrapper and all required Maven versions are included in the
repositories.

## Start everything

```sh
cd sgl-local
make up
```

`make up` performs the complete startup sequence:

1. Builds and starts both Spring Boot subgraphs.
2. Waits for their health checks.
3. Exports the real Federation SDL from each running subgraph.
4. Checks the exported SDL and composed supergraph for stale changes.
5. Starts a fresh Apollo Router with the verified supergraph.
6. Waits for every service to become healthy.

Inspect the running containers:

```sh
docker compose ps
```

The expected endpoints are:

| Component | URL | Purpose |
| --- | --- | --- |
| Apollo Router | <http://localhost:4000/> | Federated GraphQL API and Apollo Sandbox |
| Products GraphiQL | <http://localhost:8081/graphiql> | Interactive Products subgraph UI |
| Products | <http://localhost:8081/graphql> | Products subgraph GraphQL endpoint |
| Products health | <http://localhost:8081/actuator/health> | Container health |
| Pricing GraphiQL | <http://localhost:8082/graphiql> | Interactive Pricing subgraph UI |
| Pricing | <http://localhost:8082/graphql> | Pricing subgraph GraphQL endpoint |
| Pricing health | <http://localhost:8082/actuator/health> | Container health |

## Test with Apollo Sandbox

Open <http://localhost:4000/> in a browser. Apollo Sandbox should load with the
Router endpoint already available. If it asks for an endpoint, enter:

```text
http://localhost:4000/
```

Use this UI for normal manual testing because it executes queries against the
composed graph. Spring GraphiQL is also enabled at
<http://localhost:8081/graphiql> and <http://localhost:8082/graphiql> for
subgraph-level diagnostics. A subgraph UI exposes only that service's schema;
cross-subgraph queries such as `products { price }` must go through the Router.

### Federated product query

This query starts in Products and asks Pricing to resolve `price`:

```graphql
query ProductsWithPrices {
  products {
    id
    name
    price
  }
}
```

Expected products include `p-100`, `p-200`, and `p-300`. The price of `p-100`
is returned as the JSON string `"99.90"`.

### Polymorphic catalog

`CatalogItem` is an interface implemented by physical and digital products.
Pricing extends that interface with `@interfaceObject`:

```graphql
query PolymorphicCatalog {
  catalog {
    id
    name
    __typename
    price
    priceLabel

    ... on Product {
      weightGrams
    }

    ... on DigitalProduct {
      downloadFormat
    }
  }
}
```

The result contains three `Product` values and one `DigitalProduct`. The
digital product has `downloadFormat: "PDF"` and `priceLabel: "Digital price"`.
Physical entries use `"Physical price"`.

`priceLabel` also demonstrates `@external` and `@requires`: Pricing receives
the Products-owned `category` field internally even though the client did not
request it.

### Quote calculation

```graphql
query ProductQuote {
  product(id: "p-100") {
    id
    name
    quote(input: { quantity: 2 }) {
      unitPrice
      quantity
      subtotal
    }
  }
}
```

The expected subtotal is `"199.80"`.

### Incremental delivery with `@defer`

```graphql
query DeferredCommercialData {
  catalog {
    id
    name
    __typename

    ... @defer(label: "commercial-data") {
      price
      priceLabel
    }
  }
}
```

The Router sends the basic catalog first and the pricing fields in incremental
multipart patches.

## Test from the command line

Run the built-in smoke query:

```sh
make smoke
```

Or send a query directly:

```sh
curl --silent \
  --header 'Content-Type: application/json' \
  --data '{"query":"{ products { id name price } }"}' \
  http://localhost:4000/
```

To inspect a deferred multipart response:

```sh
curl --no-buffer \
  --header 'Content-Type: application/json' \
  --header 'Accept: multipart/mixed;deferSpec=20220824, application/json' \
  --data '{"query":"{ catalog { id name ... @defer(label: \"commercial-data\") { price priceLabel } } }"}' \
  http://localhost:4000/
```

## Test error behavior

Invalid quote quantities return the stable `VALIDATION_ERROR` extension:

```graphql
query InvalidQuantity {
  product(id: "p-100") {
    quote(input: { quantity: 0 }) {
      subtotal
    }
  }
}
```

An unknown product returns `PRODUCT_NOT_FOUND`:

```graphql
query MissingProduct {
  product(id: "missing") {
    id
    name
  }
}
```

Subgraph error details are enabled for this local tutorial. Production Router
configurations should normally retain Apollo's default error redaction.

## Run the automated tests

Run all unit and subgraph integration tests:

```sh
make test
```

Run the seven end-to-end tests while the stack is running:

```sh
make e2e
```

The E2E suite covers federation, polymorphism, `@requires`, quotes, stable error
codes, restricted Actuator exposure, a Pricing outage and recovery, and
multipart `@defer`.

Run the complete clean verification workflow:

```sh
make verify
```

This builds and tests all three repositories, starts the stack, verifies
deterministic schema composition, runs the smoke and E2E suites, and shuts the
containers down automatically.

## Schema workflow

Regenerate the checked-in subgraph schemas and supergraph after an SDL change:

```sh
make compose
```

Check that the live schemas and two independent Rover compositions exactly
match the checked-in artifacts:

```sh
make compose-check
```

The Apollo Router and Rover run locally from pinned container images. Neither
an Apollo account nor a GraphOS API key is required.

## Stop and clean

Stop and remove the containers and Compose network:

```sh
make down
```

Remove Maven build output from all three repositories:

```sh
make clean
```

Restarting a service restores the same four catalog items and prices because
the state is immutable and in memory.

## Troubleshooting

Check container status and logs:

```sh
docker compose ps
docker compose logs products
docker compose logs pricing
docker compose logs router
```

If a port is already occupied, stop the process using `4000`, `8081`, or
`8082`, then run `make up` again.

If Docker reuses stale local state, perform a normal shutdown and restart:

```sh
make down
make up
```

The Router image is a minimal derivative of the pinned Apollo Router image. It
adds only a static BusyBox executable for the container's HTTP health check.
