import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.UUID;


@Entity
@Table(name = "friends")
public class Friend implements IdentifiableEntity {

    @Id
    private UUID id;

    @Override
    public UUID getId() {
        return id;
    }

    @Column(name = "name")
    private String name;

    @Column(name = "level")
    private int level;

    public Friend() {
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }


    @Override
    public String toString() {
        return "Friend{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", level=" + level +
                '}';
    }
}
