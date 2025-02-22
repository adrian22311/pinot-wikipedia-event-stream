package io.example.clients;

import com.google.gson.Gson;
import io.example.schema.KafkaEvent;
import io.example.schema.category.CategoryChange;
import io.example.schema.page.RecentChange;
import org.fastily.jwiki.core.Wiki;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.messaging.Source;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This processor receives Wikimedia change events that describe recent changes as they are happening live on Wikipedia.
 *
 * @author Kenny Bastani
 */
@Component
public class RecentChangeProcessor {
    private static Logger logger = LoggerFactory.getLogger(RecentChangeProcessor.class);
    private static final String STREAM_HOST = "https://stream.wikimedia.org";
    private static final String STREAM_URI = "/v2/stream/recentchange";
    private FluxProcessor<CategoryChange, CategoryChange> categoryProcessor;
    private FluxSink<CategoryChange> categorySink;
    private final Source messageBroker;
    private boolean running = false;
    private final Gson gson = new Gson();
    private final Wiki wiki = new Wiki.Builder().build();
    private String offset;
    private KafkaEvent[] lastEventId;

    public RecentChangeProcessor(Source messageBroker) {
        this.messageBroker = messageBroker;
        this.offset = new Date().toInstant().atOffset(ZoneOffset.UTC).toString();
    }

    /**
     * Starts the recent change processor and begins to process and emit events that decorate recent changes
     * happening on Wikipedia with the page's joined categories. This allows us to create multiple change events
     * that are focused on category changes. The goal here is to understand how categories on Wikipedia are changing
     * in real-time using Apache Pinot and Kafka.
     */
    public void start() {
        if (!running) {
            running = true;

            // Initialize the join processor which will decorate recent change events with their joined category
            this.categoryProcessor = DirectProcessor.<CategoryChange>create().serialize();
            this.categorySink = categoryProcessor.sink();

            // Fetch the reactive change stream that is ready for subscription
            Flux<RecentChange> changeStream = getRecentChangeStream();

            // When an error occurs, the stream client should re-establish the connection to the SSE API
            changeStream.onErrorResume(throwable -> getRecentChangeStream())
                    .retryBackoff(Integer.MAX_VALUE, Duration.ofMillis(300))
                    .subscribe();

            // Subscriptions are non-blocking, so here we start a second subscription that is listening for joins
            // that are being emitted from the first subscription, before sinking them into Kafka
            categoryProcessor.log()
                    .doOnError(throwable -> logger.error("Error when sending events to Kafka", throwable))
                    .retryBackoff(Integer.MAX_VALUE, Duration.ofMillis(300))
                    .subscribe(this::processEvent);
        } else {
            throw new RuntimeException("The wiki stream replicator is already running...");
        }
    }

    /**
     * Fetches a reactive stream of recent changes that are happening live on Wikipedia. Logs errors if there is an
     * issue connecting to the Wikimedia change stream API. Filters out all changes that are not applicable to
     * being joined with categories, and also filters to only edits and english version of Wikipedia. Finally, it
     * joins multiple categories for each change and emits new events to be processed by a different reactive stream.
     *
     * @return a Flux of {@link RecentChange}
     */
    private Flux<RecentChange> getRecentChangeStream() {
        // Get the reactive SSE stream for recent Wikipedia changes
        Flux<ServerSentEvent<String>> eventStream = getWikiStreamClient();

        return eventStream.onErrorResume(throwable -> getWikiStreamClient())
                .timeout(Duration.ofMillis(5000L))
                .retryBackoff(Integer.MAX_VALUE, Duration.ofMillis(300))
                .map(event -> {
                    lastEventId = gson.fromJson(event.id(), KafkaEvent[].class);
                    return gson.fromJson(event.data(), RecentChange.class);
                })
                .filter(this::applyFilter)
                .doOnNext(this::joinCategories)
                .doOnError(throwable -> logger.error("Error receiving SSE", throwable));
    }

    /**
     * Filters out Wikipedia changes that aren't english and excludes any changes that are not edits to articles.
     *
     * @param recentChange is the recent change event from Wikipedia
     * @return a boolean value indicating whether or not to filter out the recent change
     */
    private boolean applyFilter(RecentChange recentChange) {
        return "enwiki".equals(recentChange.getWiki()) &&
                "edit".equals(recentChange.getType());
    }

    /**
     * This method decorates a {@link RecentChange} event from Wikipedia with categories that are fetched from
     * Wikipedia's API. The article's categories are fetched using the edited page's title. Since there are many
     * categories for a single change, the original change event is cloned and decorated with a category. Finally,
     * the new decorated change event is emitted to a new subscriber that will persist it to Kafka.
     *
     * @param recentChange is the {@link RecentChange} that should be joined with its categories
     */
    private void joinCategories(RecentChange recentChange) {
        this.offset = recentChange.getMeta().getDt().toInstant().toString();
        List<String> categories = wiki.getCategoriesOnPage(recentChange.getTitle())
                .stream()
                // .map(category -> CategoryChange.create(recentChange, category))
                // .reduce(new ArrayList<CounterChain>(), (a,b) -> { b.setStartCounter(a.getEndCounter()); return b; })
                // .forEach(categorySink::next);
                .collect(Collectors.toList());

        categorySink.next(CategoryChange.create(recentChange, categories));
    }

    /**
     * Processes a {@link RecentChange} event that has been joined together with a category and persists it to a
     * Kafka topic. This Kafka topic will be subscribed to by Apache Pinot, which will ingest the rows into a
     * real-time table that can be queried using SQL.
     *
     * @param categoryChange is the {@link RecentChange} from Wikipedia joined with a category.
     */
    private void processEvent(CategoryChange categoryChange) {
        try {
            messageBroker.output().send(MessageBuilder.withPayload(categoryChange).build());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        if (!running)
            throw new RuntimeException("The wiki stream replicator was stopped");
    }

    /**
     * Fetches a reactive stream that processes server-sent events from the Wikimedia event platform.
     *
     * @return a Flux of {@link ServerSentEvent<String>} that will be converted to {@link RecentChange}
     */
    private Flux<ServerSentEvent<String>> getWikiStreamClient() {
        WebClient client = WebClient.create(STREAM_HOST);

        // This is a work around for type erasure in the JDK and explicitly types the generic for runtime
        ParameterizedTypeReference<ServerSentEvent<String>> type = new ParameterizedTypeReference<>() {
        };

        // Fetches a reactive stream that processes server-sent events from the Wikimedia event platform
        if(lastEventId == null) {
            return client.get().uri(String.format(STREAM_URI + "?since=%s", offset)).retrieve()
                    .bodyToFlux(type);
        } else {
            return client.get().uri(STREAM_URI)
                    .header("Last-Event-Id", gson.toJson(lastEventId)).retrieve()
                    .bodyToFlux(type);
        }

    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

}
