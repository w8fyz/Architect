package fr.freshperf.architect;

import fr.freshperf.architect.repositories.GenericRepository;
import fr.freshperf.models.User;

public class UserRepository extends GenericRepository<User> {
    public UserRepository(Class<User> type) {
        super(type);
    }

    public User findByName(String name) {
        return where("name", name);
    }
}
