package sh.fyz.architect.test;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import redis.clients.jedis.BuilderFactory;
import redis.clients.jedis.CommandArguments;
import redis.clients.jedis.CommandObject;
import redis.clients.jedis.Protocol;
import sh.fyz.architect.Architect;
import sh.fyz.architect.cache.EntityChannelPubSub;
import sh.fyz.architect.cache.RedisCredentials;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.cachemodel.Gadget;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;
import sh.fyz.architect.lazymodel.Member;
import sh.fyz.architect.lazymodel.Team;
import sh.fyz.architect.persistent.DatabaseCredentials;
import sh.fyz.architect.persistent.sql.provider.PostgreSQLAuth;
import sh.fyz.architect.relationmodel.Owner;
import sh.fyz.architect.relationmodel.Pet;
import sh.fyz.architect.repositories.GenericCachedRepository;
import sh.fyz.architect.repositories.GenericRelayRepository;
import sh.fyz.architect.repositories.GenericRepository;
import sh.fyz.architect.setmodel.Animal;
import sh.fyz.architect.setmodel.Keeper;
import sh.fyz.architect.setmodel.Vet;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Writes queued by a receiver's cached repository must reach the database, and the cache must
 * not keep serving a state the database does not have.
 */
@DisplayName("GenericCachedRepository - integrite des ecritures differees")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CacheIntegrityTest {

    private Architect architect;
    private GenericCachedRepository<Gadget> cached;
    private GenericRepository<Gadget> db;

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
                4, "create-drop"
            ))
            .setRedisCredentials(new RedisCredentials(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                System.getenv().getOrDefault("REDIS_PASS", "architect"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380")),
                2000, 10
            ));
        architect.addEntityClass(Gadget.class);
        architect.addEntityClass(Owner.class);
        architect.addEntityClass(Pet.class);
        architect.addEntityClass(Team.class);
        architect.addEntityClass(Member.class);
        architect.addEntityClass(Keeper.class);
        architect.addEntityClass(Animal.class);
        architect.addEntityClass(Vet.class);
        architect.start();

        cached = new GenericCachedRepository<>(Gadget.class);
        db = new GenericRepository<>(Gadget.class);
    }

    @AfterAll
    void teardown() {
        if (architect != null) {
            architect.stop();
        }
    }

    @BeforeEach
    void clean() {
        for (Gadget g : db.all()) {
            db.delete(g);
        }
        for (String key : RedisManager.get().getRedisClient().keys("architect:*")) {
            RedisManager.get().getRedisClient().del(key);
        }
    }

    private boolean inRedis(String key) {
        return RedisManager.get().getRedisClient().exists("architect:" + key);
    }

    /**
     * Only flushed by the thread that created it: the background flush worker skips it, so a
     * test decides exactly when queued writes reach the database.
     */
    private static class TestFlushedRepository<T extends IdentifiableEntity> extends GenericCachedRepository<T> {
        private final Thread owner = Thread.currentThread();

        TestFlushedRepository(Class<T> type) {
            super(type);
        }

        @Override
        public void flushUpdates() {
            if (Thread.currentThread() == owner) {
                super.flushUpdates();
            }
        }
    }

    @Test
    @DisplayName("delete : une lecture avant le flush ne remet pas l'entite supprimee en cache")
    void testDeletedEntityDoesNotComeBack() {
        TestFlushedRepository<Gadget> repo = new TestFlushedRepository<>(Gadget.class);
        Gadget g = db.save(new Gadget("doomed", "x", 1, Gadget.Kind.SMALL));
        repo.delete(g);
        // Read before the queued delete reaches the database: the row is still there.
        assertNotNull(repo.findById(g.getId()));
        repo.flushUpdates();

        assertNull(db.findById(g.getId()));
        assertFalse(inRedis("Gadget:" + g.getId()), "the deleted row must leave the cache");
        assertNull(repo.findById(g.getId()));
    }

    @Test
    @DisplayName("violation de cle etrangere : l'ecriture est retentee, pas perdue")
    void testForeignKeyViolationIsRetried() {
        // Lazy on both sides: nothing loads the members, so the database itself refuses the
        // parent's delete (foreign-key violation) rather than Hibernate.
        Team team = new GenericRepository<>(Team.class).save(new Team("Blue"));
        Member member = new GenericRepository<>(Member.class).save(new Member("Kit", team));
        TestFlushedRepository<Team> teams = new TestFlushedRepository<>(Team.class);
        TestFlushedRepository<Member> members = new TestFlushedRepository<>(Member.class);

        // The parent's delete reaches the database before its child's: refused for now.
        teams.delete(teams.findById(team.getId()));
        teams.flushUpdates();
        assertNotNull(new GenericRepository<>(Team.class).findById(team.getId()));

        members.delete(members.findById(member.getId()));
        members.flushUpdates();
        teams.flushUpdates();
        assertNull(new GenericRepository<>(Member.class).findById(member.getId()));
        assertNull(new GenericRepository<>(Team.class).findById(team.getId()),
                "the parent's delete must be applied once its child is gone");
    }

    @Test
    @DisplayName("une ecriture refusee par la base ne bloque pas les suivantes")
    void testRejectedWriteDoesNotBlockTheQueue() {
        Gadget bad = db.save(new Gadget("bad", "x", 1, Gadget.Kind.SMALL));
        Gadget good = db.save(new Gadget("good", "x", 1, Gadget.Kind.SMALL));

        Gadget badCopy = cached.findById(bad.getId());
        badCopy.setName("n".repeat(300)); // longer than the varchar(255) column
        cached.save(badCopy);

        Gadget goodCopy = cached.findById(good.getId());
        goodCopy.setQty(42);
        cached.save(goodCopy);

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertEquals(42, db.findById(good.getId()).getQty()));
        // The cache gets the row's state back, and the row does not vanish from the cache that
        // all() and query() read as the whole table.
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertEquals(Set.of("bad", "good"),
                    cached.all().stream().map(Gadget::getName).collect(Collectors.toSet())));
    }

    @Test
    @DisplayName("query().delete() tient compte des ecritures encore en file")
    void testQueryDeleteSeesQueuedWrites() {
        TestFlushedRepository<Gadget> repo = new TestFlushedRepository<>(Gadget.class);
        Gadget g = db.save(new Gadget("pending", "old", 1, Gadget.Kind.SMALL));
        Gadget copy = repo.findById(g.getId());
        copy.setQty(77);
        repo.save(copy); // still queued: the database row has qty 1

        int deleted = repo.query().where("qty", 77).delete();

        assertEquals(1, deleted);
        repo.flushUpdates();
        assertNull(db.findById(g.getId()), "the row matched in the cache must be deleted");
    }

    @Test
    @DisplayName("limit(0) ne renvoie aucune ligne")
    void testLimitZero() {
        db.save(new Gadget("a", "x", 1, Gadget.Kind.SMALL));
        assertTrue(db.query().limit(0).findAll().isEmpty());
        assertTrue(cached.query().limit(0).findAll().isEmpty());
        assertNull(db.query().limit(0).findFirst());
        assertNull(cached.query().limit(0).findFirst());
    }

    @Test
    @DisplayName("query().delete() refuse tant qu'une ecriture attend d'etre retentee")
    void testQueryDeleteWaitsForRetriedWrites() throws Exception {
        GenericRepository<Owner> owners = new GenericRepository<>(Owner.class);
        Owner owner = owners.save(new Owner("Kept"));
        Owner gone = owners.save(new Owner("Gone"));
        Pet pet = new GenericRepository<>(Pet.class).save(new Pet("Orphan", owner));
        owners.delete(gone);

        TestFlushedRepository<Pet> pets = new TestFlushedRepository<>(Pet.class);
        Pet copy = pets.findById(pet.getId());
        Field ownerField = Pet.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        ownerField.set(copy, gone); // its row no longer exists
        pets.save(copy);
        pets.flushUpdates();

        assertThrows(IllegalStateException.class, () -> pets.query().where("name", "Orphan").delete());

        // Retried a bounded number of times, then dropped: the queue must not stay blocked.
        for (int i = 0; i < 30; i++) {
            pets.flushUpdates();
        }
        assertEquals(owner.getId(), pets.findById(pet.getId()).getOwner().getId(),
                "the cache gets the row's state back");
        assertEquals(1, pets.query().where("name", "Orphan").delete());
    }

    @Test
    @DisplayName("relations absentes du cache : relues en base, pas perdues")
    void testRelationsMissingFromCacheAreLoaded() {
        GenericRepository<Owner> owners = new GenericRepository<>(Owner.class);
        GenericCachedRepository<Pet> pets = new GenericCachedRepository<>(Pet.class);

        Owner owner = owners.save(new Owner("Bob"));
        Pet pet = new GenericRepository<>(Pet.class).save(new Pet("Rex", owner));

        pets.findById(pet.getId()); // database read, cached with owner_id
        assertFalse(inRedis("Owner:" + owner.getId()), "the owner is not cached");

        Pet fromCache = pets.findById(pet.getId());
        assertNotNull(fromCache.getOwner(), "the owner must be loaded from the database");
        assertEquals(owner.getId(), fromCache.getOwner().getId());

        fromCache.setName("Rex II");
        pets.save(fromCache);
        pets.flushUpdates();

        Pet stored = new GenericRepository<>(Pet.class).findById(pet.getId());
        assertEquals("Rex II", stored.getName());
        assertNotNull(stored.getOwner(), "saving a cached entity must not clear its foreign key");
        assertEquals(owner.getId(), stored.getOwner().getId());

        // A collection element missing from Redis: read from the database, not left out.
        GenericCachedRepository<Owner> cachedOwners = new GenericCachedRepository<>(Owner.class);
        cachedOwners.findById(owner.getId()); // database read, cached with pets_ids
        RedisManager.get().delete("Pet:" + pet.getId());
        Owner fromCacheOwner = cachedOwners.findById(owner.getId());
        assertEquals(1, fromCacheOwner.getPets().size());
        assertEquals("Rex II", fromCacheOwner.getPets().iterator().next().getName());
    }

    @Test
    @DisplayName("Set rebati du cache : chaque element y est retrouve, quel que soit le point d'entree")
    void testCachedSetElementsAreFound() {
        // Animal.hashCode depends on its vet, which is not cached: read from the database last.
        Vet vet = new GenericRepository<>(Vet.class).save(new Vet(UUID.randomUUID(), "Doc"));
        Keeper keeper = new GenericRepository<>(Keeper.class).save(new Keeper("Kim"));
        GenericRepository<Animal> animalRows = new GenericRepository<>(Animal.class);
        Animal rex = animalRows.save(new Animal("Rex", keeper, vet));
        animalRows.save(new Animal("Tom", keeper, vet));

        GenericCachedRepository<Keeper> keepers = new GenericCachedRepository<>(Keeper.class);
        GenericCachedRepository<Animal> animals = new GenericCachedRepository<>(Animal.class);
        assertTrue(inRedis("Keeper:" + keeper.getId()) && inRedis("Animal:" + rex.getId()));
        assertFalse(inRedis("Vet:" + vet.getId()));

        Set<Animal> fromKeeper = keepers.findById(keeper.getId()).getAnimals();
        assertEquals(2, fromKeeper.size());
        for (Animal animal : fromKeeper) {
            assertTrue(fromKeeper.contains(animal));
        }
        // Entered from an element: it is still being rebuilt when its keeper's set is filled.
        Animal fromAnimal = animals.findById(rex.getId());
        assertTrue(fromAnimal.getKeeper().getAnimals().contains(fromAnimal));
    }

    @Test
    @DisplayName("associations LAZY : mises en cache sans erreur, cle etrangere conservee")
    void testLazyAssociations() {
        Team team = new GenericRepository<>(Team.class).save(new Team("T"));
        Member member = new GenericRepository<>(Member.class).save(new Member("M", team));

        // Reading from the database used to throw on the uninitialized lazy collection.
        GenericCachedRepository<Team> teams = new GenericCachedRepository<>(Team.class);
        Team cachedTeam = teams.findById(team.getId());
        assertEquals(1, cachedTeam.getMembers().size());

        GenericCachedRepository<Member> members = new GenericCachedRepository<>(Member.class);
        Member cachedMember = members.findById(member.getId());
        assertNotNull(cachedMember.getTeam(), "a lazy @ManyToOne proxy must be cached with its id");
        assertEquals(team.getId(), cachedMember.getTeam().getId());

        cachedMember.setName("M2");
        members.save(cachedMember);
        members.flushUpdates();
        Member stored = new GenericRepository<>(Member.class).findById(member.getId());
        assertEquals("M2", stored.getName());
        assertEquals(team.getId(), stored.getTeam().getId(), "saving a cached entity must not clear its foreign key");
    }

    @Test
    @DisplayName("relais @Version : les sauvegardes relayees successives atteignent la base")
    void testRelayedVersionedSaves() {
        // Subscribes this receiver to the Gadget channel, asynchronously.
        new GenericRelayRepository<>(Gadget.class);
        EntityChannelPubSub<Gadget> channel = new EntityChannelPubSub<>(Gadget.class);
        String channelName = "database-action:Gadget";
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> RedisManager.get().getRedisClient()
                .executeCommand(new CommandObject<>(
                        new CommandArguments(Protocol.Command.PUBSUB).add("NUMSUB").add(channelName),
                        BuilderFactory.PUBSUB_NUMSUB_MAP))
                .getOrDefault(channelName, 0L) > 0);

        Gadget g = db.save(new Gadget("relayed", "x", 1, Gadget.Kind.SMALL));
        // A non-receiver keeps relaying the copy it read, whose version never moves.
        Gadget copy = db.findById(g.getId());
        for (int qty = 5; qty <= 7; qty++) {
            copy.setQty(qty);
            channel.publish(new DatabaseAction<>(copy, DatabaseAction.Type.SAVE));
            int expected = qty;
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertEquals(expected, db.findById(g.getId()).getQty()));
        }
    }
}
