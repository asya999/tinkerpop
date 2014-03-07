package com.tinkerpop.gremlin.structure.util.batch;

import com.tinkerpop.gremlin.process.Holder;
import com.tinkerpop.gremlin.process.Traversal;
import com.tinkerpop.gremlin.process.computer.GraphComputer;
import com.tinkerpop.gremlin.process.graph.GraphTraversal;
import com.tinkerpop.gremlin.structure.AnnotatedValue;
import com.tinkerpop.gremlin.structure.Direction;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Property;
import com.tinkerpop.gremlin.structure.Transaction;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.util.ElementHelper;
import com.tinkerpop.gremlin.structure.util.batch.cache.VertexCache;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * BatchGraph is a wrapper that enables batch loading of a large number of edges and vertices by chunking the entire
 * load into smaller batches and maintaining a memory-efficient vertex cache so that the entire transactional state can
 * be flushed after each chunk is loaded.
 * <br />
 * BatchGraph is ONLY meant for loading data and does not support any retrieval or removal operations.
 * That is, BatchGraph only supports the following methods:
 * - {@link #addVertex(Object...)} for adding vertices
 * - {@link Vertex#addEdge(String, com.tinkerpop.gremlin.structure.Vertex, Object...)} for adding edges
 * - {@link #v(Object)} to be used when adding edges
 * - Property getter, setter and removal methods for vertices and edges.
 * <br />
 * An important limitation of BatchGraph is that edge properties can only be set immediately after the edge has been added.
 * If other vertices or edges have been created in the meantime, setting, getting or removing properties will throw
 * exceptions. This is done to avoid caching of edges which would require a great amount of memory.
 * <br />
 * BatchGraph can also automatically set the provided element ids as properties on the respective element. Use
 * {@link #setVertexIdKey(String)} and {@link #setEdgeIdKey(String)} to set the keys for the vertex and edge properties
 * respectively. This allows to make the loaded baseGraph compatible for later operation with
 * {@link com.tinkerpop.gremlin.structure.strategy.IdGraphStrategy}.
 *
 * @author Stephen Mallette (http://stephen.genoprime.com)
 * @author Matthias Broecheler (http://www.matthiasb.com)
 */
public class BatchGraph<T extends Graph> implements Graph {
    /**
     * Default buffer size
     */
    public static final long DEFAULT_BUFFER_SIZE = 100000;


    private final T baseGraph;

    private String vertexIdKey = null;
    private String edgeIdKey = null;
    private boolean loadingFromScratch = true;

    private final VertexCache cache;

    private long bufferSize = DEFAULT_BUFFER_SIZE;
    private long remainingBufferSize;

    private BatchEdge currentEdge = null;
    private Edge currentEdgeCached = null;

    private Object previousOutVertexId = null;

    /**
     * Constructs a BatchGraph wrapping the provided baseGraph, using the specified buffer size and expecting vertex ids of
     * the specified IdType. Supplying vertex ids which do not match this type will throw exceptions.
     *
     * @param graph      Graph to be wrapped
     * @param type       Type of vertex id expected. This information is used to optimize the vertex cache memory footprint.
     * @param bufferSize Defines the number of vertices and edges loaded before starting a new transaction. The larger this value, the more memory is required but the faster the loading process.
     */
    public BatchGraph(final T graph, final VertexIDType type, final long bufferSize) {
        if (graph == null) throw new IllegalArgumentException("Graph may not be null");
        if (type == null) throw new IllegalArgumentException("Type may not be null");
        if (bufferSize <= 0) throw new IllegalArgumentException("BufferSize must be positive");
        this.baseGraph = graph;
        this.bufferSize = bufferSize;

        vertexIdKey = null;
        edgeIdKey = null;

        cache = type.getVertexCache();

        remainingBufferSize = this.bufferSize;
    }

    /**
     * Constructs a BatchGraph wrapping the provided baseGraph.
     *
     * @param graph Graph to be wrapped
     * @param bufferSize Defines the number of vertices and edges loaded before starting a new transaction. The larger this value, the more memory is required but the faster the loading process.
     */
    public BatchGraph(final T graph, final long bufferSize) {
        this(graph, VertexIDType.OBJECT, bufferSize);
    }

    /**
     * Constructs a BatchGraph wrapping the provided baseGraph.
     *
     * @param graph Graph to be wrapped
     */
    public BatchGraph(final T graph) {
        this(graph, VertexIDType.OBJECT, DEFAULT_BUFFER_SIZE);
    }

    // todo: need static constructors with writethroughgraph in tp2???

    /**
     * Sets the key to be used when setting the vertex id as a property on the respective vertex.
     * If the key is null, then no property will be set.
     *
     * @param key Key to be used.
     */
    public void setVertexIdKey(final String key) {
        if (!loadingFromScratch && key == null && !baseGraph.getFeatures().vertex().supportsUserSuppliedIds())
            throw new IllegalStateException("Cannot set vertex id key to null when not loading from scratch while ids are ignored.");
        this.vertexIdKey = key;
    }

    /**
     * Returns the key used to set the id on the vertices or null if such has not been set
     * via {@link #setVertexIdKey(String)}
     *
     * @return The key used to set the id on the vertices or null if such has not been set
     *         via {@link #setVertexIdKey(String)}
     */
    public String getVertexIdKey() {
        return vertexIdKey;
    }

    /**
     * Sets the key to be used when setting the edge id as a property on the respective edge.
     * If the key is null, then no property will be set.
     *
     * @param key Key to be used.
     */
    public void setEdgeIdKey(final String key) {
        this.edgeIdKey = key;
    }

    /**
     * Returns the key used to set the id on the edges or null if such has not been set
     * via {@link #setEdgeIdKey(String)}
     *
     * @return The key used to set the id on the edges or null if such has not been set
     *         via {@link #setEdgeIdKey(String)}
     */
    public String getEdgeIdKey() {
        return edgeIdKey;
    }

    /**
     * Sets whether the graph loaded through this instance of {@link BatchGraph} is loaded from scratch
     * (i.e. the wrapped graph is initially empty) or whether graph is loaded incrementally into an
     * existing graph.
     * <p/>
     * In the former case, BatchGraph does not need to check for the existence of vertices with the wrapped
     * graph but only needs to consult its own cache which can be significantly faster. In the latter case,
     * the cache is checked first but an additional check against the wrapped graph may be necessary if
     * the vertex does not exist.
     * <p/>
     * By default, BatchGraph assumes that the data is loaded from scratch.
     * <p/>
     * When setting loading from scratch to false, a vertex id key must be specified first using
     * {@link #setVertexIdKey(String)} - otherwise an exception is thrown.
     *
     * @param fromScratch
     */
    public void setLoadingFromScratch(boolean fromScratch) {
        if (fromScratch == false && vertexIdKey == null && !baseGraph.getFeatures().vertex().supportsUserSuppliedIds())
            throw new IllegalStateException("Vertex id key is required to query existing vertices in wrapped graph.");
        loadingFromScratch = fromScratch;
    }

    /**
     * Whether this BatchGraph is loading data from scratch or incrementally into an existing graph.
     * <p/>
     * By default, this returns true.
     *
     * @return Whether this BatchGraph is loading data from scratch or incrementally into an existing graph.
     * @see #setLoadingFromScratch(boolean)
     */
    public boolean isLoadingFromScratch() {
        return loadingFromScratch;
    }

    private void nextElement() {
        currentEdge = null;
        currentEdgeCached = null;
        if (remainingBufferSize <= 0) {
            if (baseGraph.getFeatures().graph().supportsTransactions()) baseGraph.tx().commit();
            cache.newTransaction();
            remainingBufferSize = bufferSize;
        }
        remainingBufferSize--;
    }

    private Vertex retrieveFromCache(final Object externalID) {
        final Object internal = cache.getEntry(externalID);
        if (internal instanceof Vertex) {
            return (Vertex) internal;
        } else if (internal != null) { //its an internal id
            final Vertex v = baseGraph.v(internal);
            cache.set(v, externalID);
            return v;
        } else return null;
    }

    private Vertex getCachedVertex(final Object externalID) {
        final Vertex v = retrieveFromCache(externalID);
        if (v == null) throw new IllegalArgumentException("Vertex for given ID cannot be found: " + externalID);
        return v;
    }

    // todo: worth having a addVertex(id) overload for batching?

    @Override
    public Vertex addVertex(final Object... keyValues) {
        final Object id = ElementHelper.getIdValue(keyValues).orElse(null);
        if (null == id) throw new IllegalArgumentException("Vertex id value cannot be null");
        if (retrieveFromCache(id) != null) throw new IllegalArgumentException("Vertex id already exists");
        nextElement();

        // todo: make this more efficient
        Vertex v;
        if (baseGraph.getFeatures().vertex().supportsUserSuppliedIds())
            v = baseGraph.addVertex(keyValues);
        else
            v = baseGraph.addVertex();

        if (vertexIdKey != null)
            v.setProperty(vertexIdKey, id);

        cache.set(v, id);
        final BatchVertex newVertex = new BatchVertex(id);

        v.setProperties(keyValues);

        return newVertex;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * If the input data are sorted, then out vertex will be repeated for several edges in a row.
     * In this case, bypass cache and instead immediately return a new vertex using the known id.
     * This gives a modest performance boost, especially when the cache is large or there are
     * on average many edges per vertex.
     */
    @Override
    public Vertex v(final Object id) {
        if ((previousOutVertexId != null) && (previousOutVertexId.equals(id))) {
            return new BatchVertex(previousOutVertexId);
        } else {

            Vertex v = retrieveFromCache(id);
            if (null == v) {
                if (loadingFromScratch) return null;
                else {
                    if (!baseGraph.getFeatures().vertex().supportsUserSuppliedIds()) {
                        assert vertexIdKey != null;
                        final Iterator<Vertex> iter = baseGraph.V().has(vertexIdKey, id);
                        if (!iter.hasNext()) return null;
                        v = iter.next();
                        if (iter.hasNext())
                            throw new IllegalArgumentException("There are multiple vertices with the provided id in the database: " + id);
                    } else {
                        v = baseGraph.v(id);
                        if (null == v) return null;
                    }
                    cache.set(v, id);
                }
            }
            return new BatchVertex(id);
        }
    }

    @Override
    public Edge e(final Object id) {
        throw retrievalNotSupported();
    }

    @Override
    public GraphTraversal<Vertex, Vertex> V() {
        throw retrievalNotSupported();
    }

    @Override
    public GraphTraversal<Edge, Edge> E() {
        throw retrievalNotSupported();
    }

    @Override
    public <T extends Traversal> T traversal(final Class<T> traversalClass) {
        throw retrievalNotSupported();
    }

    @Override
    public GraphComputer compute() {
        throw Exceptions.graphComputerNotSupported();
    }

    @Override
    public Transaction tx() {
        // todo: can we have just one of these
        return new BatchTransaction();
    }

    @Override
    public <M extends Memory> M memory() {
        throw Exceptions.memoryNotSupported();
    }

    @Override
    public Features getFeatures() {
        return null;  // todo: what are the features of batchgraph
    }

    @Override
    public void close() throws Exception {
        // todo: necessary to commit() or is that handled by tx close properly?
        baseGraph.tx().commit();
        baseGraph.tx().close();
        currentEdge = null;
        currentEdgeCached = null;
    }

    private class BatchTransaction implements Transaction {
        private final boolean supportsTx = baseGraph.getFeatures().graph().supportsTransactions();

        @Override
        public Transaction onClose(final Consumer<Transaction> consumer) {
            throw new UnsupportedOperationException("Transaction behavior cannot be altered in batch mode");
        }

        @Override
        public Transaction onReadWrite(final Consumer<Transaction> consumer) {
            throw new UnsupportedOperationException("Transaction behavior cannot be altered in batch mode");
        }

        @Override
        public void close() {
            if (supportsTx) baseGraph.tx().close();
        }

        @Override
        public void readWrite() {
            if (supportsTx) baseGraph.tx().readWrite();
        }

        @Override
        public boolean isOpen() {
            return !supportsTx || baseGraph.tx().isOpen();
        }

        @Override
        public <G extends Graph> G create() {
            throw new UnsupportedOperationException("Cannot start threaded transaction during batch loading");
        }

        @Override
        public <G extends Graph, R> Workload<G, R> submit(final Function<G, R> work) {
            throw new UnsupportedOperationException("Cannot submit a workload during batch loading");
        }

        @Override
        public void rollback() {
            throw new UnsupportedOperationException("Cannot issue a rollback during batch loading");
        }

        @Override
        public void commit() {
            currentEdge = null;
            currentEdgeCached = null;
            remainingBufferSize = 0;

            if (supportsTx) baseGraph.tx().commit();
        }

        @Override
        public void open() {
            if (supportsTx) baseGraph.tx().open();
        }
    }

    private class BatchVertex implements Vertex {

        private final Object externalID;

        BatchVertex(final Object id) {
            if (id == null) throw new IllegalArgumentException("External id may not be null");
            externalID = id;
        }

        @Override
        public Edge addEdge(final String label, final Vertex inVertex, final Object... keyValues) {
            if (!BatchVertex.class.isInstance(inVertex))
                throw new IllegalArgumentException("Given element was not created in this baseGraph");
            nextElement();

            // todo: make this all more efficient. remove id from key/values
            final Vertex ov = getCachedVertex(externalID);
            final Vertex iv = getCachedVertex(inVertex.getId());

            previousOutVertexId = externalID;  //keep track of the previous out vertex id

            final Object id = ElementHelper.getIdValue(keyValues).orElse(null);
            if (baseGraph.getFeatures().edge().supportsUserSuppliedIds())
                currentEdgeCached = ov.addEdge(label, iv, keyValues);
            else {
                currentEdgeCached = ov.addEdge(label, iv);
                currentEdgeCached.setProperties(keyValues);
            }

            if (edgeIdKey != null && id != null) {
                currentEdgeCached.setProperty(edgeIdKey, id);
            }

            currentEdge = new BatchEdge();

            return currentEdge;
        }

        @Override
        public Object getId() {
            return this.externalID;
        }

        @Override
        public String getLabel() {
            return getCachedVertex(externalID).getLabel();
        }

        @Override
        public void remove() {
            throw removalNotSupported();
        }

        @Override
        public Set<String> getPropertyKeys() {
            return getCachedVertex(externalID).getPropertyKeys();
        }

        @Override
        public Map<String, Property> getProperties() {
            return getCachedVertex(externalID).getProperties();
        }

        @Override
        public <V> Property<V> getProperty(final String key) {
            return getCachedVertex(externalID).getProperty(key);
        }

        @Override
        public <V> void setProperty(final String key, final V value) {
            getCachedVertex(externalID).setProperty(key, value);
        }

        @Override
        public void setProperties(final Object... keyValues) {
            getCachedVertex(externalID).setProperties(keyValues);
        }

        @Override
        public <V> V getValue(final String key) throws NoSuchElementException {
            return getCachedVertex(externalID).getValue(key);
        }

        @Override
        public <E2> GraphTraversal<Vertex, AnnotatedValue<E2>> annotatedValues(String propertyKey) {
            throw retrievalNotSupported();
        }

        @Override
        public <E2> GraphTraversal<Vertex, Property<E2>> property(String propertyKey) {
            throw retrievalNotSupported();
        }

        @Override
        public <E2> GraphTraversal<Vertex, E2> value(String propertyKey) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> with(Object... variableValues) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> sideEffect(Consumer<Holder<Vertex>> consumer) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> out(int branchFactor, String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> in(int branchFactor, String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> both(int branchFactor, String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Edge> outE(int branchFactor, String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Edge> inE(int branchFactor, String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Edge> bothE(int branchFactor, String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> out(String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> in(String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> both(String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Edge> outE(String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Edge> inE(String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Edge> bothE(String... labels) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> start() {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> as(String as) {
            throw retrievalNotSupported();
        }

        @Override
        public GraphTraversal<Vertex, Vertex> identity() {
            throw retrievalNotSupported();
        }
    }

    private class BatchEdge implements Edge {

        @Override
        public Vertex getVertex(final Direction direction) throws IllegalArgumentException {
            return getWrappedEdge().getVertex(direction);
        }

        @Override
        public Object getId() {
            return getWrappedEdge().getLabel();
        }

        @Override
        public String getLabel() {
            return getWrappedEdge().getLabel();
        }

        @Override
        public void remove() {
            throw removalNotSupported();
        }

        @Override
        public Map<String, Property> getProperties() {
            return getWrappedEdge().getProperties();
        }

        @Override
        public <V> Property<V> getProperty(final String key) {
            return getWrappedEdge().getProperty(key);
        }

        @Override
        public <V> void setProperty(final String key, final V value) {
            getWrappedEdge().setProperty(key, value);
        }

        @Override
        public Set<String> getPropertyKeys() {
            return getWrappedEdge().getPropertyKeys();
        }

        @Override
        public void setProperties(final Object... keyValues) {
            getWrappedEdge().setProperties(keyValues);
        }

        @Override
        public <V> V getValue(final String key) throws NoSuchElementException {
            return getWrappedEdge().getValue(key);
        }

        private Edge getWrappedEdge() {
            if (this != currentEdge) {
                throw new UnsupportedOperationException("This edge is no longer in scope");
            }
            return currentEdgeCached;
        }
    }

    private static UnsupportedOperationException retrievalNotSupported() {
        return new UnsupportedOperationException("Retrieval operations are not supported during batch loading");
    }

    private static UnsupportedOperationException removalNotSupported() {
        return new UnsupportedOperationException("Removal operations are not supported during batch loading");
    }
}
