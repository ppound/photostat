package com.photostat.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.photostat.models.ImageMetadata;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Result;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.AggregationRange;
import org.opensearch.client.opensearch._types.aggregations.CalendarInterval;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.RangeQuery;
import org.opensearch.client.opensearch.core.*;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for interacting with OpenSearch.
 */
public class OpenSearchService {

    private static OpenSearchService instance;
    private volatile OpenSearchClient client;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final LoggingService logger;
    private volatile boolean connected = false;

    private OpenSearchService() {
        this.configService = ConfigService.getInstance();
        this.logger = LoggingService.getInstance();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static synchronized OpenSearchService getInstance() {
        if (instance == null) {
            instance = new OpenSearchService();
        }
        return instance;
    }

    /**
     * Connect to OpenSearch using configuration settings.
     */
    public void connect() throws Exception {
        String host = configService.getOpenSearchHost();
        int port = configService.getOpenSearchPort();
        boolean ssl = configService.isOpenSearchSsl();
        String username = configService.getOpenSearchUsername();
        String password = configService.getOpenSearchPassword();

        connect(host, port, ssl, username, password);
    }

    /**
     * Connect to OpenSearch with explicit parameters.
     */
    public synchronized void connect(String host, int port, boolean ssl, String username, String password) throws Exception {
        String scheme = ssl ? "https" : "http";
        HttpHost httpHost = new HttpHost(scheme, host, port);

        ApacheHttpClient5TransportBuilder transportBuilder = ApacheHttpClient5TransportBuilder.builder(httpHost);

        // Configure SSL if needed
        if (ssl) {
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(null, (chains, authType) -> true)  // Trust all certs (for dev)
                    .build();

            PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
                    .setTlsStrategy(ClientTlsStrategyBuilder.create()
                            .setSslContext(sslContext)
                            .build())
                    .build();

            transportBuilder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setConnectionManager(connectionManager));
        }

        // Configure authentication if provided
        if (username != null && !username.isEmpty()) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    new AuthScope(httpHost),
                    new UsernamePasswordCredentials(username, password.toCharArray())
            );

            transportBuilder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        // Configure JSON mapper
        transportBuilder.setMapper(new JacksonJsonpMapper(objectMapper));

        client = new OpenSearchClient(transportBuilder.build());
        connected = true;
    }

    /**
     * Check if connected to OpenSearch.
     */
    public synchronized boolean isConnected() {
        if (!connected || client == null) {
            return false;
        }
        try {
            client.info();
            return true;
        } catch (Exception e) {
            connected = false;
            return false;
        }
    }

    /**
     * Test connection to OpenSearch.
     */
    public synchronized boolean testConnection() {
        try {
            if (client == null) {
                connect();
            }
            client.info();
            return true;
        } catch (Exception e) {
            System.err.println("Connection test failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create the index with proper mappings if it doesn't exist.
     */
    public void createIndexIfNotExists() throws IOException {
        String indexName = configService.getIndexName();

        ExistsRequest existsRequest = new ExistsRequest.Builder().index(indexName).build();
        boolean exists = client.indices().exists(existsRequest).value();

        if (!exists) {
            Map<String, Property> properties = new HashMap<>();

            // File properties
            properties.put("file_path", Property.of(p -> p.keyword(k -> k)));
            properties.put("file_name", Property.of(p -> p.text(t -> t)));
            properties.put("file_size", Property.of(p -> p.long_(l -> l)));
            properties.put("file_type", Property.of(p -> p.keyword(k -> k)));

            // Camera properties
            properties.put("camera_make", Property.of(p -> p.keyword(k -> k)));
            properties.put("camera_model", Property.of(p -> p.keyword(k -> k)));
            properties.put("lens_model", Property.of(p -> p.keyword(k -> k)));

            // Date properties
            properties.put("date_taken", Property.of(p -> p.date(d -> d)));
            properties.put("date_indexed", Property.of(p -> p.date(d -> d)));

            // Exposure properties
            properties.put("iso", Property.of(p -> p.integer(i -> i)));
            properties.put("aperture", Property.of(p -> p.float_(f -> f)));
            properties.put("shutter_speed", Property.of(p -> p.keyword(k -> k)));
            properties.put("focal_length", Property.of(p -> p.float_(f -> f)));

            // Image dimensions
            properties.put("image_width", Property.of(p -> p.integer(i -> i)));
            properties.put("image_height", Property.of(p -> p.integer(i -> i)));
            properties.put("orientation", Property.of(p -> p.keyword(k -> k)));

            // GPS properties
            properties.put("gps_location", Property.of(p -> p.geoPoint(g -> g)));
            properties.put("gps_latitude", Property.of(p -> p.float_(f -> f)));
            properties.put("gps_longitude", Property.of(p -> p.float_(f -> f)));

            // Other metadata
            properties.put("artist", Property.of(p -> p.text(t -> t)));
            properties.put("copyright", Property.of(p -> p.text(t -> t)));
            properties.put("software", Property.of(p -> p.keyword(k -> k)));

            // Custom user-defined metadata
            properties.put("persons", Property.of(p -> p.keyword(k -> k)));
            properties.put("place", Property.of(p -> p.keyword(k -> k)));
            properties.put("tags", Property.of(p -> p.keyword(k -> k)));
            properties.put("rating", Property.of(p -> p.keyword(k -> k)));

            // Hash fields for duplicate detection
            properties.put("content_hash", Property.of(p -> p.keyword(k -> k)));
            properties.put("perceptual_hash", Property.of(p -> p.keyword(k -> k)));

            // Raw EXIF data (not indexed)
            properties.put("all_exif", Property.of(p -> p.object(o -> o.enabled(false))));

            TypeMapping mapping = new TypeMapping.Builder().properties(properties).build();

            CreateIndexRequest createRequest = new CreateIndexRequest.Builder()
                    .index(indexName)
                    .mappings(mapping)
                    .build();

            client.indices().create(createRequest);
            System.out.println("Created index: " + indexName);
        }
    }

    /**
     * Index a single image metadata document.
     */
    public void indexDocument(ImageMetadata metadata) throws IOException {
        String indexName = configService.getIndexName();
        String docId = generateDocumentId(metadata.getFilePath());

        logger.debug("OpenSearchService", "Indexing document: " + metadata.getFilePath() + " with ID: " + docId);

        IndexRequest<ImageMetadata> request = new IndexRequest.Builder<ImageMetadata>()
                .index(indexName)
                .id(docId)
                .document(metadata)
                .build();

        client.index(request);
        logger.debug("OpenSearchService", "Document indexed successfully: " + metadata.getFilePath());
    }

    /**
     * Update an existing document (re-indexes with new data).
     */
    public void updateDocument(ImageMetadata metadata) throws IOException {
        logger.info("OpenSearchService", "Updating document: " + metadata.getFilePath());
        logger.debug("OpenSearchService", "Persons: " + metadata.getPersonsString());
        logger.debug("OpenSearchService", "Place: " + metadata.getPlace());
        logger.debug("OpenSearchService", "Tags: " + metadata.getTagsString());
        logger.debug("OpenSearchService", "Rating: " + metadata.getRating());

        try {
            indexDocument(metadata);

            // Refresh the index to make the update visible immediately
            String indexName = configService.getIndexName();
            client.indices().refresh(r -> r.index(indexName));
            logger.info("OpenSearchService", "Document updated and index refreshed: " + metadata.getFilePath());
        } catch (Exception e) {
            logger.error("OpenSearchService", "Failed to update document: " + metadata.getFilePath(), e);
            throw e;
        }
    }

    /**
     * Get a document by file path.
     */
    public ImageMetadata getDocumentByPath(String filePath) throws IOException {
        String indexName = configService.getIndexName();
        String docId = generateDocumentId(filePath);

        GetRequest request = new GetRequest.Builder()
                .index(indexName)
                .id(docId)
                .build();

        GetResponse<ImageMetadata> response = client.get(request, ImageMetadata.class);
        if (response.found() && response.source() != null) {
            return response.source();
        }
        return null;
    }

    /**
     * Bulk index multiple documents.
     */
    public int bulkIndex(List<ImageMetadata> documents) throws IOException {
        if (documents.isEmpty()) {
            return 0;
        }

        String indexName = configService.getIndexName();
        List<BulkOperation> operations = new ArrayList<>();

        for (ImageMetadata metadata : documents) {
            String docId = generateDocumentId(metadata.getFilePath());
            operations.add(new BulkOperation.Builder()
                    .index(new IndexOperation.Builder<ImageMetadata>()
                            .index(indexName)
                            .id(docId)
                            .document(metadata)
                            .build())
                    .build());
        }

        BulkRequest bulkRequest = new BulkRequest.Builder().operations(operations).build();
        BulkResponse response = client.bulk(bulkRequest);

        if (response.errors()) {
            response.items().forEach(item -> {
                if (item.error() != null) {
                    System.err.println("Bulk index error: " + item.error().reason());
                }
            });
        }

        return documents.size() - (int) response.items().stream()
                .filter(item -> item.error() != null)
                .count();
    }

    /**
     * Search for images with optional filters and text query.
     */
    public SearchResult search(String queryText, Map<String, Object> filters, int from, int size) throws IOException {
        String indexName = configService.getIndexName();
        logger.debug("OpenSearchService", "Search called - query: '" + queryText + "', from: " + from + ", size: " + size);
        logger.debug("OpenSearchService", "Filters: " + (filters != null ? filters.toString() : "none"));

        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // Add text search if provided
        if (queryText != null && !queryText.trim().isEmpty()) {
            boolQuery.must(Query.of(q -> q.multiMatch(mm -> mm
                    .query(queryText)
                    .fields("file_name", "camera_make", "camera_model", "lens_model", "artist", "copyright", "persons", "place", "tags")
            )));
        }

        // Add filters
        if (filters != null) {
            addFilters(boolQuery, filters);
        }

        // Build search request with aggregations
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(indexName)
                .query(Query.of(q -> q.bool(boolQuery.build())))
                .from(from)
                .size(size)
                .sort(s -> s.field(f -> f.field("date_taken").order(SortOrder.Desc)));

        // Add aggregations for facets
        searchBuilder.aggregations("camera_make", Aggregation.of(a -> a.terms(t -> t.field("camera_make").size(20))));
        searchBuilder.aggregations("camera_model", Aggregation.of(a -> a.terms(t -> t.field("camera_model").size(50))));
        searchBuilder.aggregations("lens_model", Aggregation.of(a -> a.terms(t -> t.field("lens_model").size(50))));
        searchBuilder.aggregations("file_type", Aggregation.of(a -> a.terms(t -> t.field("file_type").size(20))));
        searchBuilder.aggregations("software", Aggregation.of(a -> a.terms(t -> t.field("software").size(30))));

        // Custom metadata aggregations
        searchBuilder.aggregations("persons", Aggregation.of(a -> a.terms(t -> t.field("persons").size(50))));
        searchBuilder.aggregations("place", Aggregation.of(a -> a.terms(t -> t.field("place").size(50))));
        searchBuilder.aggregations("tags", Aggregation.of(a -> a.terms(t -> t.field("tags").size(50))));
        searchBuilder.aggregations("rating", Aggregation.of(a -> a.terms(t -> t.field("rating").size(10))));

        // ISO ranges aggregation
        List<AggregationRange> isoRanges = Arrays.asList(
                new AggregationRange.Builder().key("ISO 100-200").from("100").to("201").build(),
                new AggregationRange.Builder().key("ISO 200-400").from("200").to("401").build(),
                new AggregationRange.Builder().key("ISO 400-800").from("400").to("801").build(),
                new AggregationRange.Builder().key("ISO 800-1600").from("800").to("1601").build(),
                new AggregationRange.Builder().key("ISO 1600-3200").from("1600").to("3201").build(),
                new AggregationRange.Builder().key("ISO 3200+").from("3200").build()
        );
        searchBuilder.aggregations("iso_ranges", Aggregation.of(a -> a.range(r -> r.field("iso").ranges(isoRanges))));

        // Year aggregation
        searchBuilder.aggregations("year", Aggregation.of(a -> a.dateHistogram(dh -> dh
                .field("date_taken")
                .calendarInterval(CalendarInterval.Year))));

        try {
            SearchResponse<ImageMetadata> response = client.search(searchBuilder.build(), ImageMetadata.class);

            // Extract results
            List<ImageMetadata> results = response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            long total = response.hits().total() != null ? response.hits().total().value() : 0;

            logger.debug("OpenSearchService", "Search returned " + results.size() + " results, total: " + total);

            // Extract aggregations
            Map<String, Map<String, Long>> aggregations = extractAggregations(response);

            return new SearchResult(results, total, aggregations);
        } catch (Exception e) {
            logger.error("OpenSearchService", "Search failed", e);
            throw e;
        }
    }

    private void addFilters(BoolQuery.Builder boolQuery, Map<String, Object> filters) {
        for (Map.Entry<String, Object> entry : filters.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();

            if (value == null) continue;

            if (value instanceof String && !((String) value).isEmpty()) {
                boolQuery.filter(Query.of(q -> q.term(t -> t
                        .field(field)
                        .value(FieldValue.of((String) value)))));
            } else if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> values = (List<String>) value;
                // Use AND logic - each selected value must be present
                for (String v : values) {
                    if (v != null && !v.isEmpty()) {
                        boolQuery.filter(Query.of(q -> q.term(t -> t
                                .field(field)
                                .value(FieldValue.of(v)))));
                    }
                }
            } else if (field.endsWith("_min") || field.endsWith("_max")) {
                // Range filter
                String actualField = field.replace("_min", "").replace("_max", "");
                RangeQuery.Builder rangeBuilder = new RangeQuery.Builder().field(actualField);
                if (field.endsWith("_min")) {
                    rangeBuilder.gte(JsonData.of(value.toString()));
                } else {
                    rangeBuilder.lte(JsonData.of(value.toString()));
                }
                boolQuery.filter(Query.of(q -> q.range(rangeBuilder.build())));
            } else if (field.equals("date_from") || field.equals("date_to")) {
                String dateField = "date_taken";
                RangeQuery.Builder rangeBuilder = new RangeQuery.Builder().field(dateField);
                if (field.equals("date_from")) {
                    rangeBuilder.gte(JsonData.of(formatDate(value)));
                } else {
                    rangeBuilder.lte(JsonData.of(formatDate(value)));
                }
                boolQuery.filter(Query.of(q -> q.range(rangeBuilder.build())));
            }
        }
    }

    private String formatDate(Object date) {
        if (date instanceof LocalDateTime) {
            return ((LocalDateTime) date).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        return date.toString();
    }

    private Map<String, Map<String, Long>> extractAggregations(SearchResponse<ImageMetadata> response) {
        Map<String, Map<String, Long>> result = new HashMap<>();

        if (response.aggregations() == null) return result;

        response.aggregations().forEach((name, agg) -> {
            Map<String, Long> buckets = new LinkedHashMap<>();

            if (agg.isSterms()) {
                agg.sterms().buckets().array().forEach(bucket -> {
                    buckets.put(bucket.key(), bucket.docCount());
                });
            } else if (agg.isLterms()) {
                agg.lterms().buckets().array().forEach(bucket -> {
                    buckets.put(String.valueOf(bucket.key()), bucket.docCount());
                });
            } else if (agg.isDateHistogram()) {
                agg.dateHistogram().buckets().array().forEach(bucket -> {
                    buckets.put(bucket.keyAsString(), bucket.docCount());
                });
            } else if (agg.isRange()) {
                agg.range().buckets().array().forEach(bucket -> {
                    if (bucket.docCount() > 0) {
                        buckets.put(bucket.key(), bucket.docCount());
                    }
                });
            } else if (agg.isHistogram()) {
                agg.histogram().buckets().array().forEach(bucket -> {
                    if (bucket.docCount() > 0) {
                        buckets.put(String.valueOf(bucket.key()), bucket.docCount());
                    }
                });
            }

            if (!buckets.isEmpty()) {
                result.put(name, buckets);
            }
        });

        return result;
    }

    /**
     * Delete all documents from a specific directory.
     */
    public long deleteByDirectory(String directoryPath) throws IOException {
        String indexName = configService.getIndexName();

        DeleteByQueryRequest request = new DeleteByQueryRequest.Builder()
                .index(indexName)
                .query(Query.of(q -> q.prefix(p -> p
                        .field("file_path")
                        .value(directoryPath))))
                .build();

        DeleteByQueryResponse response = client.deleteByQuery(request);
        return response.deleted() != null ? response.deleted() : 0;
    }

    /**
     * Delete a single document by file path.
     */
    public boolean deleteDocument(String filePath) throws IOException {
        String indexName = configService.getIndexName();
        String docId = generateDocumentId(filePath);

        logger.info("OpenSearchService", "Deleting document: " + filePath);

        DeleteRequest request = new DeleteRequest.Builder()
                .index(indexName)
                .id(docId)
                .build();

        DeleteResponse response = client.delete(request);
        boolean success = response.result() == Result.Deleted;

        if (success) {
            logger.info("OpenSearchService", "Document deleted successfully");
        } else {
            logger.warn("OpenSearchService", "Document not found or not deleted: " + response.result());
        }

        return success;
    }

    /**
     * Check if a file is already indexed.
     */
    public boolean isFileIndexed(String filePath) throws IOException {
        String indexName = configService.getIndexName();
        String docId = generateDocumentId(filePath);

        try {
            GetRequest request = new GetRequest.Builder()
                    .index(indexName)
                    .id(docId)
                    .build();
            GetResponse<ImageMetadata> response = client.get(request, ImageMetadata.class);
            return response.found();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get total document count in the index.
     */
    public long getDocumentCount() throws IOException {
        String indexName = configService.getIndexName();

        CountRequest request = new CountRequest.Builder()
                .index(indexName)
                .build();

        CountResponse response = client.count(request);
        return response.count();
    }

    /**
     * Get aggregation data for charts.
     */
    public Map<String, Map<String, Long>> getChartData() throws IOException {
        String indexName = configService.getIndexName();

        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(indexName)
                .size(0);  // We only want aggregations

        // Camera makes
        searchBuilder.aggregations("camera_make", Aggregation.of(a -> a.terms(t -> t.field("camera_make").size(20))));

        // Camera models
        searchBuilder.aggregations("camera_model", Aggregation.of(a -> a.terms(t -> t.field("camera_model").size(20))));

        // Lens models
        searchBuilder.aggregations("lens_model", Aggregation.of(a -> a.terms(t -> t.field("lens_model").size(20))));

        // File types
        searchBuilder.aggregations("file_type", Aggregation.of(a -> a.terms(t -> t.field("file_type").size(20))));

        // Software
        searchBuilder.aggregations("software", Aggregation.of(a -> a.terms(t -> t.field("software").size(30))));

        // Custom metadata
        searchBuilder.aggregations("persons", Aggregation.of(a -> a.terms(t -> t.field("persons").size(30))));
        searchBuilder.aggregations("place", Aggregation.of(a -> a.terms(t -> t.field("place").size(30))));
        searchBuilder.aggregations("tags", Aggregation.of(a -> a.terms(t -> t.field("tags").size(30))));

        // Monthly timeline
        searchBuilder.aggregations("monthly", Aggregation.of(a -> a.dateHistogram(dh -> dh
                .field("date_taken")
                .calendarInterval(CalendarInterval.Month))));

        // ISO distribution
        searchBuilder.aggregations("iso", Aggregation.of(a -> a.terms(t -> t.field("iso").size(50))));

        // Aperture distribution
        searchBuilder.aggregations("aperture", Aggregation.of(a -> a.histogram(h -> h
                .field("aperture")
                .interval(1.0))));

        // Focal length distribution
        searchBuilder.aggregations("focal_length", Aggregation.of(a -> a.histogram(h -> h
                .field("focal_length")
                .interval(25.0))));

        SearchResponse<ImageMetadata> response = client.search(searchBuilder.build(), ImageMetadata.class);

        return extractAggregations(response);
    }

    /**
     * Search for images within a date range, sorted by date descending.
     */
    public List<ImageMetadata> searchByDateRange(String fromDate, String toDate, int from, int size) throws IOException {
        Map<String, Object> filters = new HashMap<>();
        // Must pass LocalDateTime so addFilters hits the date_from/date_to branch
        // (String values would match the term filter branch instead)
        java.time.LocalDate from_date = java.time.LocalDate.parse(fromDate);
        java.time.LocalDate to_date = java.time.LocalDate.parse(toDate);
        filters.put("date_from", from_date.atStartOfDay());
        filters.put("date_to", to_date.atTime(23, 59, 59));
        SearchResult result = search(null, filters, from, size);
        return result.getResults();
    }

    /**
     * Generate a consistent document ID from file path.
     * Normalizes path to handle case-insensitivity on Windows.
     */
    private String generateDocumentId(String filePath) {
        // Normalize the path for consistent ID generation
        String normalizedPath = normalizePath(filePath);
        return Base64.getEncoder().encodeToString(normalizedPath.getBytes())
                .replace("/", "_")
                .replace("+", "-");
    }

    /**
     * Normalize a file path for consistent comparison and ID generation.
     * On Windows, converts to lowercase since the filesystem is case-insensitive.
     */
    private String normalizePath(String filePath) {
        try {
            // Use Path to normalize the path structure (resolve . and ..)
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            String normalized = path.toString();

            // On Windows, convert to lowercase for case-insensitive matching
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                normalized = normalized.toLowerCase();
            }
            return normalized;
        } catch (Exception e) {
            // Fallback: just return the original path
            return filePath;
        }
    }

    /**
     * Returns all indexed file paths using search_after pagination.
     * Unlike search(), this has no 10,000 result limit and skips aggregations.
     */
    public List<String> searchAllFilePaths(int batchSize) throws IOException {
        String indexName = configService.getIndexName();
        List<String> allPaths = new ArrayList<>();
        List<String> searchAfter = null;

        while (true) {
            SearchRequest.Builder builder = new SearchRequest.Builder()
                    .index(indexName)
                    .query(Query.of(q -> q.matchAll(m -> m)))
                    .size(batchSize)
                    .sort(s -> s.field(f -> f.field("file_path").order(SortOrder.Asc)));

            if (searchAfter != null) {
                builder.searchAfter(searchAfter);
            }

            SearchResponse<ImageMetadata> response = client.search(builder.build(), ImageMetadata.class);
            List<Hit<ImageMetadata>> hits = response.hits().hits();

            if (hits.isEmpty()) {
                break;
            }

            for (Hit<ImageMetadata> hit : hits) {
                if (hit.source() != null) {
                    allPaths.add(hit.source().getFilePath());
                }
            }

            // Use the sort values from the last hit for the next page
            searchAfter = hits.get(hits.size() - 1).sort();
        }

        return allPaths;
    }

    /**
     * Find duplicate images by grouping on a hash field.
     * Uses terms aggregation with min_doc_count: 2 to find groups sharing the same hash.
     *
     * @param hashField "content_hash" for exact duplicates, "perceptual_hash" for visual duplicates
     * @return list of duplicate groups, each containing the hash and matching images
     */
    public List<DuplicateGroup> findDuplicates(String hashField) throws IOException {
        String indexName = configService.getIndexName();
        List<DuplicateGroup> groups = new ArrayList<>();

        // Terms aggregation to find hashes that appear more than once
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(indexName)
                .size(0)
                .query(Query.of(q -> q.exists(e -> e.field(hashField))))
                .aggregations("duplicate_hashes", Aggregation.of(a -> a
                        .terms(t -> t
                                .field(hashField)
                                .size(1000)
                                .minDocCount(2))));

        SearchResponse<ImageMetadata> response = client.search(searchBuilder.build(), ImageMetadata.class);

        // Extract duplicate hash values
        List<String> duplicateHashes = new ArrayList<>();
        if (response.aggregations() != null && response.aggregations().containsKey("duplicate_hashes")) {
            var agg = response.aggregations().get("duplicate_hashes");
            if (agg.isSterms()) {
                agg.sterms().buckets().array().forEach(bucket -> {
                    duplicateHashes.add(bucket.key());
                });
            }
        }

        // For each duplicate hash, fetch the matching documents
        for (String hash : duplicateHashes) {
            SearchRequest.Builder docSearch = new SearchRequest.Builder()
                    .index(indexName)
                    .size(100)
                    .query(Query.of(q -> q.term(t -> t
                            .field(hashField)
                            .value(FieldValue.of(hash)))))
                    .sort(s -> s.field(f -> f.field("file_size").order(SortOrder.Desc)));

            SearchResponse<ImageMetadata> docResponse = client.search(docSearch.build(), ImageMetadata.class);

            List<ImageMetadata> images = docResponse.hits().hits().stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (images.size() >= 2) {
                groups.add(new DuplicateGroup(hash, images));
            }
        }

        return groups;
    }

    /**
     * A group of duplicate images sharing the same hash.
     */
    public static class DuplicateGroup {
        private final String hash;
        private final List<ImageMetadata> images;

        public DuplicateGroup(String hash, List<ImageMetadata> images) {
            this.hash = hash;
            this.images = images;
        }

        public String getHash() {
            return hash;
        }

        public List<ImageMetadata> getImages() {
            return images;
        }

        public int getCount() {
            return images.size();
        }

        public long getTotalSize() {
            return images.stream()
                    .filter(img -> img.getFileSize() != null)
                    .mapToLong(ImageMetadata::getFileSize)
                    .sum();
        }

        /**
         * Get the reclaimable size (total minus the largest file, which is the "original").
         */
        public long getReclaimableSize() {
            if (images.size() < 2) return 0;
            long total = getTotalSize();
            long largest = images.stream()
                    .filter(img -> img.getFileSize() != null)
                    .mapToLong(ImageMetadata::getFileSize)
                    .max()
                    .orElse(0);
            return total - largest;
        }
    }

    /**
     * Data class for GPS cluster aggregation results.
     */
    public static class GpsClusterData {
        private final double latitude;
        private final double longitude;
        private final long count;
        private final String tileKey;

        public GpsClusterData(double latitude, double longitude, long count, String tileKey) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.count = count;
            this.tileKey = tileKey;
        }

        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public long getCount() { return count; }
        public String getTileKey() { return tileKey; }
    }

    /**
     * Data class for individual GPS image results.
     */
    public static class GpsImageData {
        private final String filePath;
        private final String fileName;
        private final double latitude;
        private final double longitude;
        private final String dateTaken;
        private final String cameraModel;

        public GpsImageData(String filePath, String fileName, double latitude, double longitude,
                            String dateTaken, String cameraModel) {
            this.filePath = filePath;
            this.fileName = fileName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.dateTaken = dateTaken;
            this.cameraModel = cameraModel;
        }

        public String getFilePath() { return filePath; }
        public String getFileName() { return fileName; }
        public double getLatitude() { return latitude; }
        public double getLongitude() { return longitude; }
        public String getDateTaken() { return dateTaken; }
        public String getCameraModel() { return cameraModel; }
    }

    /**
     * Search for GPS clusters using geotile_grid aggregation.
     * Returns cluster centers with photo counts for map display.
     */
    public List<GpsClusterData> searchGpsClusters(int precision, double[] bounds) throws IOException {
        String indexName = configService.getIndexName();
        List<GpsClusterData> clusters = new ArrayList<>();

        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(indexName)
                .size(0);

        // Build query with optional bounding box filter
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();
        boolQuery.must(Query.of(q -> q.exists(e -> e.field("gps_latitude"))));

        if (bounds != null && bounds.length == 4) {
            // bounds: [south, west, north, east]
            boolQuery.filter(Query.of(q -> q.geoBoundingBox(gb -> gb
                    .field("gps_location")
                    .boundingBox(bb -> bb.tlbr(tlbr -> tlbr
                            .topLeft(tl -> tl.latlon(ll -> ll.lat(bounds[2]).lon(bounds[1])))
                            .bottomRight(br -> br.latlon(ll -> ll.lat(bounds[0]).lon(bounds[3]))))))));
        }

        searchBuilder.query(Query.of(q -> q.bool(boolQuery.build())));

        // Geotile grid aggregation with geo_centroid sub-aggregation for accurate positioning
        final int geoPrecision = precision;
        searchBuilder.aggregations("geo_clusters", Aggregation.of(a -> a
                .geotileGrid(g -> g.field("gps_location").precision(geoPrecision).size(500))
                .aggregations("centroid", Aggregation.of(sa -> sa
                        .geoCentroid(gc -> gc.field("gps_location"))))));

        SearchResponse<ImageMetadata> response = client.search(searchBuilder.build(), ImageMetadata.class);

        if (response.aggregations() != null && response.aggregations().containsKey("geo_clusters")) {
            var agg = response.aggregations().get("geo_clusters");
            agg._get()._toAggregate().geotileGrid().buckets().array().forEach(bucket -> {
                String key = bucket.key();
                long count = bucket.docCount();

                // Use actual centroid of images in this tile (not the tile's geometric center)
                double lat, lon;
                var centroidAgg = bucket.aggregations().get("centroid");
                if (centroidAgg != null && centroidAgg.geoCentroid().location() != null) {
                    var location = centroidAgg.geoCentroid().location();
                    lat = location.latlon().lat();
                    lon = location.latlon().lon();
                } else {
                    double[] center = geotileToLatLon(key);
                    lat = center[0];
                    lon = center[1];
                }
                clusters.add(new GpsClusterData(lat, lon, count, key));
            });
        }

        return clusters;
    }

    /**
     * Search for individual GPS images within a bounding box.
     */
    public List<GpsImageData> searchGpsImages(double[] bounds, int limit) throws IOException {
        String indexName = configService.getIndexName();
        List<GpsImageData> images = new ArrayList<>();

        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(indexName)
                .size(limit);

        // Bounding box query
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();
        boolQuery.must(Query.of(q -> q.exists(e -> e.field("gps_latitude"))));

        if (bounds != null && bounds.length == 4) {
            boolQuery.filter(Query.of(q -> q.geoBoundingBox(gb -> gb
                    .field("gps_location")
                    .boundingBox(bb -> bb.tlbr(tlbr -> tlbr
                            .topLeft(tl -> tl.latlon(ll -> ll.lat(bounds[2]).lon(bounds[1])))
                            .bottomRight(br -> br.latlon(ll -> ll.lat(bounds[0]).lon(bounds[3]))))))));
        }

        searchBuilder.query(Query.of(q -> q.bool(boolQuery.build())));

        // Only fetch needed fields
        searchBuilder.source(s -> s.filter(f -> f.includes(
                "file_path", "file_name", "gps_latitude", "gps_longitude", "date_taken", "camera_model")));

        SearchResponse<ImageMetadata> response = client.search(searchBuilder.build(), ImageMetadata.class);

        for (Hit<ImageMetadata> hit : response.hits().hits()) {
            ImageMetadata src = hit.source();
            if (src != null && src.getGpsLatitude() != null && src.getGpsLongitude() != null) {
                images.add(new GpsImageData(
                        src.getFilePath(),
                        src.getFileName(),
                        src.getGpsLatitude(),
                        src.getGpsLongitude(),
                        src.getDateTaken() != null ? src.getDateTaken().toString() : null,
                        src.getCameraModel()
                ));
            }
        }

        return images;
    }

    /**
     * Count total images with GPS coordinates.
     */
    public long countGpsImages() throws IOException {
        String indexName = configService.getIndexName();

        CountRequest request = new CountRequest.Builder()
                .index(indexName)
                .query(Query.of(q -> q.exists(e -> e.field("gps_latitude"))))
                .build();

        CountResponse response = client.count(request);
        return response.count();
    }

    /**
     * Convert a geotile key ("zoom/x/y") to lat/lon center coordinates.
     */
    private double[] geotileToLatLon(String tileKey) {
        String[] parts = tileKey.split("/");
        int zoom = Integer.parseInt(parts[0]);
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);

        int n = 1 << zoom;
        // Center of tile: add 0.5 to get center
        double lonDeg = (x + 0.5) / n * 360.0 - 180.0;
        double latRad = Math.atan(Math.sinh(Math.PI * (1 - 2.0 * (y + 0.5) / n)));
        double latDeg = Math.toDegrees(latRad);

        return new double[]{latDeg, lonDeg};
    }

    /**
     * Search result container class.
     */
    public static class SearchResult {
        private final List<ImageMetadata> results;
        private final long total;
        private final Map<String, Map<String, Long>> aggregations;

        public SearchResult(List<ImageMetadata> results, long total, Map<String, Map<String, Long>> aggregations) {
            this.results = results;
            this.total = total;
            this.aggregations = aggregations;
        }

        public List<ImageMetadata> getResults() {
            return results;
        }

        public long getTotal() {
            return total;
        }

        public Map<String, Map<String, Long>> getAggregations() {
            return aggregations;
        }
    }
}
