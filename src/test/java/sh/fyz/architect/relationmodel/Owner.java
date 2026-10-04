package sh.fyz.architect.relationmodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.HashSet;
import java.util.Set;

/** Bidirectional one-to-many: Owner → Pet → Owner is a cycle when rebuilt from the cache. */
@Entity
@Table(name = "rel_owners")
public class Owner implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "owner", fetch = FetchType.EAGER)
    private Set<Pet> pets = new HashSet<>();

    public Owner() {}

    public Owner(String name) {
        this.name = name;
    }

    @Override
    public Long getId() { return id; }
    public String getName() { return name; }
    public Set<Pet> getPets() { return pets; }
}
