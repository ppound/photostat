package com.photostat.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Singleton cache of known metadata values (persons, places, tags) from OpenSearch aggregations.
 * Used to provide autocomplete suggestions in DetailPanel and SlideshowStage.
 */
public class MetadataSuggestionsCache {

    private static MetadataSuggestionsCache instance;

    private final OpenSearchService openSearchService;
    private final LoggingService logger;

    private List<String> persons = Collections.emptyList();
    private List<String> places = Collections.emptyList();
    private List<String> tags = Collections.emptyList();
    private volatile boolean loaded = false;

    private MetadataSuggestionsCache() {
        this.openSearchService = OpenSearchService.getInstance();
        this.logger = LoggingService.getInstance();
    }

    public static synchronized MetadataSuggestionsCache getInstance() {
        if (instance == null) {
            instance = new MetadataSuggestionsCache();
        }
        return instance;
    }

    /**
     * Refresh the cache by fetching aggregations from OpenSearch.
     * Safe to call from any thread.
     */
    public void refresh() {
        if (!openSearchService.isConnected()) {
            return;
        }

        try {
            Map<String, Map<String, Long>> chartData = openSearchService.getChartData();

            if (chartData.containsKey("persons")) {
                persons = new ArrayList<>(chartData.get("persons").keySet());
                Collections.sort(persons, String.CASE_INSENSITIVE_ORDER);
            }
            if (chartData.containsKey("place")) {
                places = new ArrayList<>(chartData.get("place").keySet());
                Collections.sort(places, String.CASE_INSENSITIVE_ORDER);
            }
            if (chartData.containsKey("tags")) {
                tags = new ArrayList<>(chartData.get("tags").keySet());
                Collections.sort(tags, String.CASE_INSENSITIVE_ORDER);
            }

            loaded = true;
            logger.debug("MetadataSuggestionsCache", "Refreshed: " +
                    persons.size() + " persons, " + places.size() + " places, " + tags.size() + " tags");
        } catch (Exception e) {
            logger.error("MetadataSuggestionsCache", "Failed to refresh suggestions", e);
        }
    }

    /**
     * Ensure the cache has been loaded at least once. Calls refresh() if not yet loaded.
     */
    public void ensureLoaded() {
        if (!loaded) {
            refresh();
        }
    }

    public List<String> getPersons() {
        ensureLoaded();
        return persons;
    }

    public List<String> getPlaces() {
        ensureLoaded();
        return places;
    }

    public List<String> getTags() {
        ensureLoaded();
        return tags;
    }
}
