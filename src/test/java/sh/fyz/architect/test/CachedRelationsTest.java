package sh.fyz.architect.test;

import org.junit.jupiter.api.*;
import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.relationmodel.Owner;
import sh.fyz.architect.relationmodel.Pet;
import sh.fyz.architect.repositories.GenericCachedRepository;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GenericCachedRepository - Relations")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CachedRelationsTest {

    private Architect architect;
    private GenericCachedRepository<Owner> owners;
    private GenericCachedRepository<Pet> pets;

    @BeforeAll
    void setup() {
        architect = new Architect()
            .setReceiver(true)
            .setDatabaseCredentials(new DatabaseCredentials(
                new PostgreSQLAuth(
                    System.getenv().getOrDefault("DB_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("DB_PORT", "5440")),
                    System.getenv().getOrDefault("DB_NAME", "architect_test")),
                System.getenv().getOrDefault("DB_USER", "architect"),
                System.getenv().getOrDefault("DB_PASS", "architect"),
                4, 2, "create-drop"
            ))
            .setRedisCredentials(new RedisCredentials(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                System.getenv().getOrDefault("REDIS_PASS", "architect"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380")),
                2000, 10
            ));
        architect.addEntityClass(Owner.class);
        architect.addEntityClass(Pet.class);
        architect.start();

        owners = new GenericCachedRepository<>(Owner.class);
        pets = new GenericCachedRepository<>(Pet.class);
        architect.addRepositories(owners, pets);
    }

    @AfterAll
    void teardown() {
        if (architect != null) {
            architect.stop();
        }
    }

    @Test
    @DisplayName("Relations bidirectionnelles - relues depuis le cache sans boucle infinie")
    void testBidirectionalRelationsFromCache() {
        Owner owner = owners.save(new Owner("Alice"));
        pets.save(new Pet("Rex", owner));
        pets.save(new Pet("Tom", owner));

        // The owner was cached when created, before it had pets: evict it so the next read comes
        // from the database (pets loaded eagerly) and caches the full graph.
        RedisManager.get().delete("Owner:" + owner.getId());
        Owner fromDb = owners.findById(owner.getId());
        assertEquals(2, fromDb.getPets().size());

        // Second read is served by Redis: the collection and back-references are rebuilt from ids.
        Owner cached = owners.findById(owner.getId());
        assertNotNull(cached);
        assertEquals("Alice", cached.getName());
        assertEquals(Set.of("Rex", "Tom"), cached.getPets().stream().map(Pet::getName).collect(Collectors.toSet()));
        for (Pet pet : cached.getPets()) {
            assertNotNull(pet.getOwner());
            assertEquals(owner.getId(), pet.getOwner().getId());
        }

        Pet cachedPet = pets.query().where("name", "Rex").findFirst();
        assertNotNull(cachedPet);
        assertEquals("Alice", cachedPet.getOwner().getName());
        assertEquals(1, owners.all().size());
    }
}
