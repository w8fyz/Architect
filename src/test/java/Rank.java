import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.UUID;


@Entity
@Table(name = "ranks")
public class Rank implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public Long getId() {
        return id;
    }

    @Column(name = "name")
    private String name;

    @Column(name = "power")
    private int power;

    public Rank() {
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

    public int getPower() {
        return power;
    }

    public void setPower(int power) {
        this.power = power;
    }

    @Override
    public String toString() {
        return "Rank{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", power=" + power +
                '}';
    }
}
