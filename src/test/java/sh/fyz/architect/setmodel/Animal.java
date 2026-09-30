package sh.fyz.architect.setmodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.Objects;

@Entity
@Table(name = "set_animals")
public class Animal implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    private Keeper keeper;

    @ManyToOne(fetch = FetchType.EAGER)
    private Vet vet;

    public Animal() {}

    public Animal(String name, Keeper keeper, Vet vet) {
        this.name = name;
        this.keeper = keeper;
        this.vet = vet;
    }

    @Override
    public Long getId() { return id; }
    public Keeper getKeeper() { return keeper; }

    // Value-based and depending on a relation, like a natural key.
    @Override
    public boolean equals(Object o) {
        return o instanceof Animal a && Objects.equals(name, a.name) && Objects.equals(vetId(), a.vetId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, vetId());
    }

    private Object vetId() {
        return vet == null ? null : vet.getId();
    }
}
