package fr.freshperf.architect;

import fr.freshperf.architect.repositories.GenericCachedRepository;
import fr.freshperf.architect.repositories.GenericRelayRepository;
import fr.freshperf.architect.repositories.GenericRepository;
import fr.freshperf.models.User;

import java.util.function.Consumer;

public class UserRepository extends GenericRelayRepository<User> {
    public UserRepository(Class<User> type) {
        super(type);
    }

    public User findByName(String name) {
        return where("name", name);
    }

    public void findByNameAsync(String name, Consumer<User> action) {
        whereAsync("name", name, action, (user) -> {});
    }
}
