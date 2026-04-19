package sh.fyz.architect.examples;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

@Entity
@Table(name = "demo_products")
public class Product implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "category")
    private String category;

    @Column(name = "price")
    private double price;

    @Column(name = "stock")
    private int stock;

    @Column(name = "active")
    private boolean active;

    @Column(name = "description")
    private String description;

    public Product() {}

    public Product(String name, String category, double price, int stock, boolean active) {
        this.name = name;
        this.category = category;
        this.price = price;
        this.stock = stock;
        this.active = active;
    }

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return "Product{id=" + id + ", name='" + name + "', category='" + category +
               "', price=" + price + ", stock=" + stock + ", active=" + active + "}";
    }
}
