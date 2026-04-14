package sh.fyz.architect.repositories;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class QueryBuilder<T> {

    public enum Operator {
        EQ, NEQ, GT, GTE, LT, LTE, LIKE, IN, NOT_IN, IS_NULL, IS_NOT_NULL
    }

    public enum SortOrder {
        ASC, DESC
    }

    public record Condition(String field, Operator operator, Object value) {}

    public record OrderBy(String field, SortOrder order) {}

    public record RawCondition(String hqlFragment, Map<String, Object> parameters) {}

    private final GenericRepository<T> repository;
    private final List<Condition> conditions = new ArrayList<>();
    private final List<RawCondition> rawConditions = new ArrayList<>();
    private final List<OrderBy> orderBys = new ArrayList<>();
    private int limit = -1;
    private int offset = 0;

    QueryBuilder(GenericRepository<T> repository) {
        this.repository = repository;
    }

    // --- WHERE clauses ---

    public QueryBuilder<T> where(String field, Object value) {
        conditions.add(new Condition(field, Operator.EQ, value));
        return this;
    }

    public QueryBuilder<T> where(String field, Operator operator, Object value) {
        conditions.add(new Condition(field, operator, value));
        return this;
    }

    public QueryBuilder<T> whereLike(String field, String pattern) {
        conditions.add(new Condition(field, Operator.LIKE, pattern));
        return this;
    }

    public QueryBuilder<T> whereIn(String field, Collection<?> values) {
        conditions.add(new Condition(field, Operator.IN, new ArrayList<>(values)));
        return this;
    }

    public QueryBuilder<T> whereNotIn(String field, Collection<?> values) {
        conditions.add(new Condition(field, Operator.NOT_IN, new ArrayList<>(values)));
        return this;
    }

    public QueryBuilder<T> whereNull(String field) {
        conditions.add(new Condition(field, Operator.IS_NULL, null));
        return this;
    }

    public QueryBuilder<T> whereNotNull(String field) {
        conditions.add(new Condition(field, Operator.IS_NOT_NULL, null));
        return this;
    }

    /**
     * Adds a raw HQL WHERE fragment with named parameters.
     * The fragment is AND-ed with other conditions.
     *
     * <pre>{@code
     * repository.query()
     *     .whereRaw("LOWER(name) LIKE :pattern", Map.of("pattern", "%test%"))
     *     .findAll();
     *
     * repository.query()
     *     .where("active", true)
     *     .whereRaw("price BETWEEN :min AND :max", Map.of("min", 10.0, "max", 50.0))
     *     .findAll();
     * }</pre>
     */
    public QueryBuilder<T> whereRaw(String hqlFragment, Map<String, Object> parameters) {
        rawConditions.add(new RawCondition(hqlFragment, parameters != null ? new HashMap<>(parameters) : Map.of()));
        return this;
    }

    /**
     * Adds a raw HQL WHERE fragment without parameters.
     *
     * <pre>{@code
     * repository.query()
     *     .whereRaw("active = true")
     *     .findAll();
     * }</pre>
     */
    public QueryBuilder<T> whereRaw(String hqlFragment) {
        rawConditions.add(new RawCondition(hqlFragment, Map.of()));
        return this;
    }

    // --- ORDER BY ---

    public QueryBuilder<T> orderBy(String field) {
        orderBys.add(new OrderBy(field, SortOrder.ASC));
        return this;
    }

    public QueryBuilder<T> orderBy(String field, SortOrder order) {
        orderBys.add(new OrderBy(field, order));
        return this;
    }

    // --- PAGINATION ---

    public QueryBuilder<T> limit(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Limit must be >= 0");
        this.limit = limit;
        return this;
    }

    public QueryBuilder<T> offset(int offset) {
        if (offset < 0) throw new IllegalArgumentException("Offset must be >= 0");
        this.offset = offset;
        return this;
    }

    // --- TERMINAL OPERATIONS (sync) ---

    public List<T> findAll() {
        return repository.executeQuery(this);
    }

    public T findFirst() {
        int previousLimit = this.limit;
        this.limit = 1;
        List<T> results = repository.executeQuery(this);
        this.limit = previousLimit;
        return results.isEmpty() ? null : results.get(0);
    }

    public long count() {
        return repository.executeCount(this);
    }

    public int delete() {
        return repository.executeDelete(this);
    }

    // --- TERMINAL OPERATIONS (async) ---

    public CompletableFuture<List<T>> findAllAsync() {
        return CompletableFuture.supplyAsync(this::findAll, repository.threadPool);
    }

    public CompletableFuture<T> findFirstAsync() {
        return CompletableFuture.supplyAsync(this::findFirst, repository.threadPool);
    }

    public CompletableFuture<Long> countAsync() {
        return CompletableFuture.supplyAsync(this::count, repository.threadPool);
    }

    public CompletableFuture<Integer> deleteAsync() {
        return CompletableFuture.supplyAsync(this::delete, repository.threadPool);
    }

    // --- PACKAGE-PRIVATE ACCESSORS ---

    List<Condition> getConditions() {
        return conditions;
    }

    List<RawCondition> getRawConditions() {
        return rawConditions;
    }

    List<OrderBy> getOrderBys() {
        return orderBys;
    }

    int getLimit() {
        return limit;
    }

    int getOffset() {
        return offset;
    }

    boolean hasRawConditions() {
        return !rawConditions.isEmpty();
    }
}
