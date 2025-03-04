import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.UUID;


@Entity
@Table(name = "users")
public class User implements IdentifiableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Override
    public Long getId() {
        return id;
    }

    @Column(name = "username")
    private String username;

    @Column(name = "password")
    private String password;

    @Column(name = "uuid")
    private UUID uuid;

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

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }
}
