package rdpk.model;

/**
 * One warehouse row. {@code restockEta} is nullable: an item that is in stock has nothing to
 * restock, which is why the GraphQL field is declared without a bang.
 */
public record Stock(String productId, int stockLevel, String restockEta) {
}
