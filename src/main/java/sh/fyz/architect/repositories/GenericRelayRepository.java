package sh.fyz.architect.repositories;

import sh.fyz.architect.cache.EntityChannelPubSub;
import sh.fyz.architect.cache.RedisManager;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.List;
import java.util.logging.Logger;

public class GenericRelayRepository<T extends IdentifiableEntity> extends GenericCachedRepository<T> {

    private static final Logger LOG = Logger.getLogger(GenericRelayRepository.class.getName());

    private EntityChannelPubSub<T> channelPubSub;

    public GenericRelayRepository(Class<T> type) {
        super(type);
        try {
            channelPubSub = new EntityChannelPubSub<>(type);
            channelPubSub.subscribe();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize relay repository for " + type.getSimpleName(), e);
        }
    }

    @Override
    public T save(T entity) {
        if (!RedisManager.get().isReceiver()) {
            // Checked before publishing: once published, the receiver would create the entity
            // even though this call fails, and a caller retrying it would create duplicates.
            if (entity.getId() == null) {
                throw new UnsupportedOperationException(
                    "Cannot create new entities (null ID) on a non-receiver instance. " +
                    "New entities must be created on the receiver."
                );
            }
            channelPubSub.publish(new DatabaseAction<>(entity, DatabaseAction.Type.SAVE));
        }
        return super.save(entity);
    }

    @Override
    public T findById(Object id) {
        try {
            return super.findById(id);
        } catch (Exception e) {
            LOG.warning("Failed to find entity by ID in relay repository: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(T entity) {
        if (RedisManager.get().isReceiver()) {
            super.delete(entity);
            return;
        }
        // The receiver deletes the row; this instance only drops its cached copy. Deleting from
        // the database here too would fail on a Redis-only instance after the delete was
        // already relayed.
        channelPubSub.publish(new DatabaseAction<>(entity, DatabaseAction.Type.DELETE));
        evictFromCache(entity.getId());
    }

    @Override
    public List<T> all() {
        try {
            return super.all();
        } catch (Exception e) {
            LOG.warning("Failed to list all entities in relay repository: " + e.getMessage());
            return List.of();
        }
    }
}
