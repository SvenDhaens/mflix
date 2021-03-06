package mflix.api.daos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BsonField;
import com.mongodb.client.model.BucketOptions;
import com.mongodb.client.model.Facet;
import com.mongodb.client.model.Field;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Variable;
import org.bson.BsonArray;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.text;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Projections.metaTextScore;

@Component
public class MovieDao extends AbstractMFlixDao {

    public static String MOVIES_COLLECTION = "movies";

    private MongoCollection<Document> moviesCollection;
    private static Logger log = LoggerFactory.getLogger(MovieDao.class.getName());

    @Autowired
    public MovieDao(
            MongoClient mongoClient, @Value("${spring.mongodb.database}") String databaseName) {
        super(mongoClient, databaseName);
        moviesCollection = db.getCollection(MOVIES_COLLECTION);
    }

    @SuppressWarnings("unchecked")
    private Bson buildLookupStage() {
        return null;
    }

    /**
     * movieId needs to be a hexadecimal string value. Otherwise it won't be possible to translate to
     * an ObjectID
     *
     * @param movieId - Movie object identifier
     * @return true if valid movieId.
     */
    private boolean validIdValue(String movieId) {

        try {
            return ObjectId.isValid(movieId);
        } catch (IllegalArgumentException ex) {
            this.log.error("Not a valid movieId: {}", movieId);
            return false;
        }
        //TODO> Ticket: Handling Errors - implement a way to catch a
        //any potential exceptions thrown while validating a movie id.
        //Check out this method's use in the method that follows.
    }

    /**
     * Gets a movie object from the database.
     *
     * @param movieId - Movie identifier string.
     * @return Document object or null.
     */
    @SuppressWarnings("UnnecessaryLocalVariable")
    public Document getMovie(String movieId) {
        if (!validIdValue(movieId)) {
            return null;
        }
        // match stage to find movie
        Bson match = Aggregates.match(eq("_id", new ObjectId(movieId)));

        List<Variable<ObjectId>> let = new ArrayList<>();
        let.add(new Variable("the_movie_id", "$_id"));

        Bson expr = Filters.expr(new Document("$eq", Arrays.asList("$movie_id","$$the_movie_id")));
        Bson matchLetId = Aggregates.match(expr);
        Bson sort = Aggregates.sort(Sorts.descending("date"));

        List<Bson> lookUpPipeline = new ArrayList<>();
        lookUpPipeline.add(matchLetId);
        lookUpPipeline.add(sort);

        Bson lookup = Aggregates.lookup("comments", let, lookUpPipeline, "comments");
        //lookup example
        /*
        db.orders.aggregate([
                        {
                                $lookup:
                {
                    from: "warehouses",
                            let: { order_item: "$item", order_qty: "$ordered" },
                    pipeline: [
                    { $match:
                        { $expr:
                            {
                                $and:
                                [
                                    { $eq: [ "$stock_item",  "$$order_item" ] },
                                    { $gte: [ "$instock", "$$order_qty" ] }
                                ]
                            }
                        }
                    },
                    { $project: { stock_item: 0, _id: 0 } }
                   ],
                    as: "stockdata"
                }
            }
        ])
        */
        List<Bson> aggregatePipeline = new ArrayList<>();
        aggregatePipeline.add(match);
        aggregatePipeline.add(lookup);

        Document movie = moviesCollection.aggregate(aggregatePipeline).first();

        return movie;
    }

    /**
     * Returns all movies within the defined limit and skip values using a default descending sort key
     * `tomatoes.viewer.numReviews`
     *
     * @param limit - max number of returned documents.
     * @param skip  - number of documents to be skipped.
     * @return list of documents.
     */
    @SuppressWarnings("UnnecessaryLocalVariable")
    public List<Document> getMovies(int limit, int skip) {
        String defaultSortKey = "tomatoes.viewer.numReviews";
        List<Document> movies =
                new ArrayList<>(getMovies(limit, skip, Sorts.descending(defaultSortKey)));
        return movies;
    }

    /**
     * Finds a limited amount of movies documents, for a given sort order.
     *
     * @param limit - max number of documents to be returned.
     * @param skip  - number of documents to be skipped.
     * @param sort  - result sorting criteria.
     * @return list of documents that sorted by the defined sort criteria.
     */
    public List<Document> getMovies(int limit, int skip, Bson sort) {

        List<Document> movies = new ArrayList<>();

        moviesCollection
                .find()
                .limit(limit)
                .skip(skip)
                .sort(sort)
                .iterator()
                .forEachRemaining(movies::add);

        return movies;
    }

    /**
     * For a given a country, return all the movies that match that country.
     *
     * @param country - Country string value to be matched.
     * @return List of matching Document objects.
     */
    public List<Document> getMoviesByCountry(String... country) {
        Bson queryFilter = in("countries", country);
        Bson includeTitle = include("title");
//        Bson titleNoId = Projections.fields(includeTitle,excludeId());

        List<Document> movies = new ArrayList<>();

        moviesCollection.find(queryFilter)
                .projection(includeTitle)
//                .projection(titleNoId)
                .into(movies);

        return movies;
    }

    /**
     * This method will execute the following mongo shell query: db.movies.find({"$text": { "$search":
     * `keywords` }}, {"score": {"$meta": "textScore"}}).sort({"score": {"$meta": "textScore"}})
     *
     * @param limit    - integer value of number of documents to be limited to.
     * @param skip     - number of documents to be skipped.
     * @param keywords - text matching keywords or terms
     * @return List of query matching Document objects
     */
    public List<Document> getMoviesByText(int limit, int skip, String keywords) {
        Bson textFilter = text(keywords);
        Bson projection = metaTextScore("score");
        Bson sort = Sorts.metaTextScore("score");
        List<Document> movies = new ArrayList<>();
        moviesCollection
                .find(textFilter)
                .projection(projection)
                .sort(sort)
                .skip(skip)
                .limit(limit)
                .iterator()
                .forEachRemaining(movies::add);
        return movies;
    }

    /**
     * Finds all movies that contain any of the `casts` members, sorted in descending by the `sortKey`
     * field.
     *
     * @param sortKey - sort key.
     * @param limit   - number of documents to be returned.
     * @param skip    - number of documents to be skipped.
     * @param cast    - cast selector.
     * @return List of documents sorted by sortKey that match the cast selector.
     */
    public List<Document> getMoviesByCast(String sortKey, int limit, int skip, String... cast) {
        Bson castFilter = Filters.in("cast", cast);
        Bson sort = Sorts.descending(sortKey);

        List<Document> movies = new ArrayList<>();
        moviesCollection
                .find(castFilter)
                .sort(sort)
                .limit(limit)
                .skip(skip)
                .iterator()
                .forEachRemaining(movies::add);
        return movies;
    }

    /**
     * Finds all movies that match the provide `genres`, sorted descending by the `sortKey` field.
     *
     * @param sortKey - sorting key string.
     * @param limit   - number of documents to be returned.
     * @param skip    - number of documents to be skipped
     * @param genres  - genres matching string vargs.
     * @return List of matching Document objects.
     */
    public List<Document> getMoviesByGenre(String sortKey, int limit, int skip, String... genres) {
        // query filter
        Bson castFilter = in("genres", genres);
        // sort key
        Bson sort = Sorts.descending(sortKey);
        List<Document> movies = new ArrayList<>();
        // TODO > Ticket: Paging - implement the necessary cursor methods to support simple
        // pagination like skip and limit in the code below
        moviesCollection.find(castFilter).sort(sort)
                .skip(skip)
                .limit(limit)
                .iterator()
                .forEachRemaining(movies::add);
        return movies;
    }

    private ArrayList<Integer> runtimeBoundaries() {
        ArrayList<Integer> runtimeBoundaries = new ArrayList<>();
        runtimeBoundaries.add(0);
        runtimeBoundaries.add(60);
        runtimeBoundaries.add(90);
        runtimeBoundaries.add(120);
        runtimeBoundaries.add(180);
        return runtimeBoundaries;
    }

    private ArrayList<Integer> ratingBoundaries() {
        ArrayList<Integer> ratingBoundaries = new ArrayList<>();
        ratingBoundaries.add(0);
        ratingBoundaries.add(50);
        ratingBoundaries.add(70);
        ratingBoundaries.add(90);
        ratingBoundaries.add(100);
        return ratingBoundaries;
    }

    /**
     * This method is the java implementation of the following mongo shell aggregation pipeline {
     * "$bucket": { "groupBy": "$runtime", "boundaries": [0, 60, 90, 120, 180], "default": "other",
     * "output": { "count": {"$sum": 1} } } }
     */
    private Bson buildRuntimeBucketStage() {

        BucketOptions bucketOptions = new BucketOptions();
        bucketOptions.defaultBucket("other");
        BsonField count = new BsonField("count", new Document("$sum", 1));
        bucketOptions.output(count);
        return Aggregates.bucket("$runtime", runtimeBoundaries(), bucketOptions);
    }

    /*
    This method is the java implementation of the following mongo shell aggregation pipeline
    {
     "$bucket": {
       "groupBy": "$metacritic",
       "boundaries": [0, 50, 70, 90, 100],
       "default": "other",
       "output": {
       "count": {"$sum": 1}
       }
      }
     }
     */
    private Bson buildRatingBucketStage() {
        BucketOptions bucketOptions = new BucketOptions();
        bucketOptions.defaultBucket("other");
        BsonField count = new BsonField("count", new Document("$sum", 1));
        bucketOptions.output(count);
        return Aggregates.bucket("$metacritic", ratingBoundaries(), bucketOptions);
    }

    /**
     * This method is the java implementation of the following mongo shell aggregation pipeline
     * pipeline.aggregate([ {$match: {cast: {$in: ... }}}, {$sort: {tomatoes.viewer.numReviews: -1}},
     * {$skip: ... }, {$limit: ... }, {$facet:{ runtime: {$bucket: ...}, rating: {$bucket: ...},
     * movies: {$addFields: ...}, }} ])
     */
    public List<Document> getMoviesCastFaceted(int limit, int skip, String... cast) {
        List<Document> movies = new ArrayList<>();
        String sortKey = "tomatoes.viewer.numReviews";
        Bson skipStage = Aggregates.skip(skip);
        Bson matchStage = Aggregates.match(in("cast", cast));
        Bson sortStage = Aggregates.sort(Sorts.descending(sortKey));
        Bson limitStage = Aggregates.limit(limit);
        Bson facetStage = buildFacetStage();
        // Using a LinkedList to ensure insertion order
        List<Bson> pipeline = new LinkedList<>();

        // TODO > Ticket: Faceted Search - build the aggregation pipeline by adding all stages in the
        // correct order
        // Your job is to order the stages correctly in the pipeline.
        // Starting with the `matchStage` add the remaining stages.
        pipeline.add(matchStage);
        pipeline.add(sortStage);
        pipeline.add(skipStage);
        pipeline.add(limitStage);
        pipeline.add(facetStage);

        moviesCollection.aggregate(pipeline).iterator().forEachRemaining(movies::add);
        return movies;
    }

    /**
     * This method is the java implementation of the following mongo shell aggregation pipeline
     * pipeline.aggregate([ ..., {$facet:{ runtime: {$bucket: ...}, rating: {$bucket: ...}, movies:
     * {$addFields: ...}, }} ])
     *
     * @return Bson defining the $facet stage.
     */
    private Bson buildFacetStage() {

        return Aggregates.facet(
                new Facet("runtime", buildRuntimeBucketStage()),
                new Facet("rating", buildRatingBucketStage()),
                new Facet("movies", Aggregates.addFields(new Field("title", "$title"))));
    }

    /**
     * Counts the total amount of documents in the `movies` collection
     *
     * @return number of documents in the movies collection.
     */
    public long getMoviesCount() {
        return this.moviesCollection.countDocuments();
    }

    /**
     * Counts the number of documents matched by this text query
     *
     * @param keywords - set of keywords that match the query
     * @return number of matching documents.
     */
    public long getTextSearchCount(String keywords) {
        return this.moviesCollection.countDocuments(text(keywords));
    }

    /**
     * Counts the number of documents matched by this cast elements
     *
     * @param cast - cast string vargs.
     * @return number of matching documents.
     */
    public long getCastSearchCount(String... cast) {
        return this.moviesCollection.countDocuments(in("cast", cast));
    }

    /**
     * Counts the number of documents match genres filter.
     *
     * @param genres - genres string vargs.
     * @return number of matching documents.
     */
    public long getGenresSearchCount(String... genres) {
        return this.moviesCollection.countDocuments(in("genres", genres));
    }
}
