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
        if (!RedisManager.get().isReceiver()) {
            channelPubSub.publish(new DatabaseAction<>(entity, DatabaseAction.Type.DELETE));
        }
        super.delete(entity);
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
