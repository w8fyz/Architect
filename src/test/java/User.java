import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.UUID;


@Entity
@Table(name = "users")
public class User implements IdentifiableEntity {

    @Id
    private UUID id;

    @Override
    public UUID getId() {
        return id;
    }

    @Column(name = "username")
    private String username;


    @ManyToOne
    @JoinColumn(name = "rank_id")
    private Rank rank;

    @Column(name = "password")
    private String password;

    public User() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setUuid(UUID uuid) {
        this.id = uuid;
    }

    public Rank getRank() {
        return rank;
    }

    public void setRank(Rank rank) {
        this.rank = rank;
    }
    
}
