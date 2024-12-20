package sh.fyz.architect.repositories;

import sh.fyz.architect.cache.EntityChannelPubSub;
import sh.fyz.architect.entities.DatabaseAction;
import sh.fyz.architect.entities.IdentifiableEntity;

import java.util.List;

public class GenericRelayRepository<T extends IdentifiableEntity> extends GenericCachedRepository<T> {

    private EntityChannelPubSub<T> channelPubSub;

    public GenericRelayRepository(Class<T> type) {
        super(type);
        try {
            channelPubSub = new EntityChannelPubSub<>(new DatabaseAction<T>(type.getDeclaredConstructor().newInstance()));
            channelPubSub.subscribe();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public T save(T entity) {
        channelPubSub.publish(new DatabaseAction<>(entity, DatabaseAction.Type.SAVE));
        try {
            return super.save(entity);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return entity;
    }

    @Override
    public T findById(Long id) {
        try {
            return super.findById(id);
        } catch (Exception e) {
            return null;
        }
    }
    @Override
    public void delete(T entity) {
        channelPubSub.publish(new DatabaseAction<>(entity, DatabaseAction.Type.DELETE));
        try {
            super.delete(entity);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<T> all() {
        try {
            super.all();
        } catch (Exception ignored) {}
        return null;
    }
}
