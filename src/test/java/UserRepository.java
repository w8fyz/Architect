import sh.fyz.architect.repositories.GenericRelayRepository;

import java.util.UUID;

public class UserRepository extends GenericRelayRepository<User> {

    public UserRepository() {
        super(User.class);
    }

    public User findByUuid(UUID uuid) {
        return this.where("uuid", uuid);
    }
}
