package sh.fyz.architect.diffmodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

/**
 * Entity fixtures for {@code SchemaDiffTest}, deliberately outside {@code sh.fyz.architect.test}.
 *
 * <p>{@code SessionManager.scanEntities} registers every {@code @Entity} under the packages of the
 * classes it was given, subpackages included. Leaving these next to the other tests' entities made
 * them part of <em>those</em> tests' schemas, which broke them in ways that pointed nowhere near
 * the real cause. They also cannot coexist in one mapping — three of them intentionally map the
 * same table to model successive versions of it — so they must never be scanned. The diff engine
 * takes its entity classes as an explicit parameter, so they are only ever passed by hand.</p>
 */
public final class DiffFixtures {

    private DiffFixtures() {}

    public enum Status { DRAFT, ACTIVE }

    public enum StatusExtended { DRAFT, ACTIVE, ARCHIVED }

    @Entity
    @Table(name = "diff_item")
    public static class ItemV1 implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "label", length = 64) public String label;
        @Override public Object getId() { return id; }
    }

    /** {@link ItemV1} plus two columns. */
    @Entity
    @Table(name = "diff_item")
    public static class ItemV2 implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "label", length = 64) public String label;
        @Column(name = "price_cents") public long priceCents;
        @Column(name = "note") public String note;
        @Override public Object getId() { return id; }
    }

    /** {@link ItemV1} with {@code label} renamed to {@code title}. */
    @Entity
    @Table(name = "diff_item")
    public static class ItemRenamed implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "title", length = 64) public String title;
        @Override public Object getId() { return id; }
    }

    /** Mixed-case table and column names: quoted identifiers keep their case. */
    @Entity
    @Table(name = "DiffCamel")
    public static class CamelCaseItem implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String displayName;
        @Override public Object getId() { return id; }
    }

    /** {@link CamelCaseItem} without {@code displayName}. */
    @Entity
    @Table(name = "DiffCamel")
    public static class CamelCaseItemTrimmed implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Override public Object getId() { return id; }
    }

    @Entity
    @Table(name = "diff_other")
    public static class OtherTable implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "name") public String name;
        @Override public Object getId() { return id; }
    }

    @Entity
    @Table(name = "diff_enum_holder")
    public static class EnumHolderV1 implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "status") @Enumerated(EnumType.STRING) public Status status;
        @Override public Object getId() { return id; }
    }

    @Entity
    @Table(name = "diff_quirks")
    public static class QuirkTypes implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        /** columnDefinition types arrive quoted under GLOBALLY_QUOTED_IDENTIFIERS. */
        @Column(name = "body", columnDefinition = "text") public String body;
        /** Hibernate writes double as float(53); PostgreSQL reports float8. */
        @Column(name = "ratio") public double ratio;
        /** Arrays: JDBC reports the internal _varchar; DDL says varchar(255) array. */
        @Column(name = "tags") public String[] tags;
        @Override public Object getId() { return id; }
    }

    /** {@link ItemV1} with a shorter label: shrinking a column can truncate data. */
    @Entity
    @Table(name = "diff_item")
    public static class ItemNarrowed implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "label", length = 32) public String label;
        @Override public Object getId() { return id; }
    }

    /** ORDINAL enum: PostgreSQL stores its CHECK as a range, not a value list. */
    @Entity
    @Table(name = "diff_ordinal_holder")
    public static class OrdinalHolderV1 implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "status") @Enumerated(EnumType.ORDINAL) public Status status;
        @Override public Object getId() { return id; }
    }

    /** {@link OrdinalHolderV1} with one more enum constant. */
    @Entity
    @Table(name = "diff_ordinal_holder")
    public static class OrdinalHolderV2 implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "status") @Enumerated(EnumType.ORDINAL) public StatusExtended status;
        @Override public Object getId() { return id; }
    }

    /** {@link EnumHolderV1} with one more enum constant. */
    @Entity
    @Table(name = "diff_enum_holder")
    public static class EnumHolderV2 implements IdentifiableEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "status") @Enumerated(EnumType.STRING) public StatusExtended status;
        @Override public Object getId() { return id; }
    }
}
