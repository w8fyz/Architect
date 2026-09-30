package sh.fyz.architect.setmodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.HashSet;
import java.util.Set;

/** Holds a {@code HashSet} of entities whose hashCode depends on one of their relations. */
@Entity
@Table(name = "set_keepers")
public class Keeper implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "keeper", fetch = FetchType.EAGER)
    private Set<Animal> animals = new HashSet<>();

    public Keeper() {}

    public Keeper(String name) {
        this.name = name;
    }

    @Override
    public Long getId() { return id; }
    public Set<Animal> getAnimals() { return animals; }
}
