package sh.fyz.architect.relationmodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

@Entity
@Table(name = "rel_pets")
public class Pet implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    private Owner owner;

    public Pet() {}

    public Pet(String name, Owner owner) {
        this.name = name;
        this.owner = owner;
    }

    @Override
    public Long getId() { return id; }
    public String getName() { return name; }
    public Owner getOwner() { return owner; }
}
