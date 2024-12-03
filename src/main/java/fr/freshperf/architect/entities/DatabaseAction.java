package fr.freshperf.architect.entities;

public class DatabaseAction<T> {

    private T entity;
    private int type;

    public DatabaseAction(T entity) {
        this.entity = entity;
        this.type = Type.NONE;
    }

    public DatabaseAction() {
    }


    public DatabaseAction(T entity, int type) {
        this.entity = entity;
        this.type = type;
    }

    public T getEntity() {
        return entity;
    }

    public int getType() {
        return type;
    }

    public interface Type {
        public static final int SAVE = 1;
        public static final int DELETE = 3;
        public static final int NONE = 0;
    }

}
