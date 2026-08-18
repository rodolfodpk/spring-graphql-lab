package rdpk.model;

/** A supplier as reported by the upstream GraphQL service. */
public record Supplier(String name, double rating) {
}
