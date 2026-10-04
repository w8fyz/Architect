package sh.fyz.architect.lazymodel;

import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

/** Lazy many-to-one: loaded as an uninitialized Hibernate proxy. */
@Entity
@Table(name = "lazy_members")
public class Member implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    private Team team;

    public Member() {}

    public Member(String name, Team team) {
        this.name = name;
        this.team = team;
    }

    @Override
    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Team getTeam() { return team; }
}
