package sh.fyz.architect.cachemodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixture for {@code CacheConsistencyTest}: a versioned entity with the field types the Redis
 * cache has to round-trip (enum, java.time, BigDecimal, typed collection, nullable wrapper).
 * In its own package because {@code SessionManager} scans the packages of the registered
 * entities.
 */
@Entity
@Table(name = "cache_gadgets")
public class Gadget implements IdentifiableEntity {

    public enum Kind { SMALL, LARGE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    private String name;

    private String category;

    private Integer qty;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private LocalDateTime createdAt;

    @Column(precision = 20, scale = 2)
    private BigDecimal amount;

    /** A basic collection, stored by PostgreSQL as an array column. */
    private List<Double> scores;

    public Gadget() {}

    public Gadget(String name, String category, Integer qty, Kind kind) {
        this.name = name;
        this.category = category;
        this.qty = qty;
        this.kind = kind;
        this.createdAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        this.amount = new BigDecimal("123456789012345678.91");
        this.scores = new ArrayList<>(List.of(1.5, 2.25));
    }

    @Override
    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public Integer getQty() { return qty; }
    public void setQty(Integer qty) { this.qty = qty; }
    public Kind getKind() { return kind; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public BigDecimal getAmount() { return amount; }
    public List<Double> getScores() { return scores; }
}
