package sh.fyz.architect.lazymodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.ArrayList;
import java.util.List;

/** One-to-many left lazy (the JPA default): nothing loads it unless asked to. */
@Entity
@Table(name = "lazy_teams")
public class Team implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "team")
    private List<Member> members = new ArrayList<>();

    public Team() {}

    public Team(String name) {
        this.name = name;
    }

    @Override
    public Long getId() { return id; }
    public List<Member> getMembers() { return members; }
}
