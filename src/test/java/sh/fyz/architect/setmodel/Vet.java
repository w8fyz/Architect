package sh.fyz.architect.setmodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.UUID;

@Entity
@Table(name = "set_vets")
public class Vet implements IdentifiableEntity {

    @Id
    private UUID id;

    private String name;

    public Vet() {}

    public Vet(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public UUID getId() { return id; }
}
