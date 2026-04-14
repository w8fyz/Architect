package sh.fyz.architect.entities;

public class DatabaseAction<T> {

    private T entity;
    private String className;
    private Type type;

    public DatabaseAction(T entity) {
        this.entity = entity;
        this.type = Type.NONE;
    }

    public DatabaseAction() {
    }

    public DatabaseAction(T entity, Type type) {
        this.entity = entity;
        this.className = entity.getClass().getSimpleName();
        this.type = type;
    }

    public T getEntity() {
        return entity;
    }

    public String getClassName() {
        return className;
    }

    public Type getType() {
        return type;
    }

    public enum Type {
        NONE,
        SAVE,
        DELETE
    }
}
