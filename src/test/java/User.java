import jakarta.persistence.*;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.ArrayList;
import java.util.List;
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

    @ManyToMany(fetch = FetchType.EAGER)
    private List<Friend> friends = new ArrayList<>();

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

    public List<Friend> getFriends() {
        return friends;
    }

    public void addFriend(Friend friend) {
        this.friends.add(friend);
    }

    @Override
    public String toString() {
        String friendList = "";
        for (Friend friend : friends) {
            System.out.println(friend);
            friendList += friend.getName() + ", ";
        }
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", friends=" + friendList +
                ", rank=" + rank +
                ", password='" + password + '\'' +
                '}';
    }
}
