package org.apache.kafka.connect.mongodb;

import com.mongodb.CursorType;
import com.mongodb.MongoClient;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import org.apache.kafka.connect.errors.ConnectException;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.BSONTimestamp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author Andrea Patelli
 */
public class DatabaseReader implements Runnable {
    private String host;
    private Integer port;
    private String db;
    private String start;

    private ConcurrentLinkedQueue<Document> messages;

    private MongoCollection<Document> oplog;
    private Bson query;

    public DatabaseReader(String host, Integer port, String db, String start, ConcurrentLinkedQueue<Document> messages) {
        this.host = host;
        this.port = port;
        this.db = db;
        this.start = start;
        this.messages = messages;
        try {
            init();
        } catch(ConnectException e) {
            throw e;
        }
    }

    public void run() {
        Document fields = new Document();
        fields.put("ts", 1);
        fields.put("op", 1);
        fields.put("ns", 1);
        fields.put("o", 1);

        FindIterable<Document> documents = oplog
                .find(query)
                .sort(new Document("$natural", 1))
                .projection(Projections.include("ts", "op", "ns", "o"))
                .cursorType(CursorType.TailableAwait);

        for(Document document : documents) {
            messages.add(document);
        }
    }

    private void init() {
        oplog = readCollection();
        query = createQuery();
    }

    private MongoCollection readCollection() {
        MongoClient mongoClient = new MongoClient(host, port);
        MongoDatabase db = mongoClient.getDatabase("local");
        return db.getCollection("oplog.rs");
    }

    private Bson createQuery() {
        Long timestamp = Long.parseLong(start);
        Integer order = new Long(timestamp % 10).intValue();
        timestamp = timestamp / 10;

        Integer finalTimestamp = timestamp.intValue();
        Integer finalOrder = order;

        Bson query = Filters.and(
                Filters.exists("fromMigrate", false),
                Filters.gt("ts", new BSONTimestamp(finalTimestamp, finalOrder)),
                Filters.or(
                        Filters.eq("op", "i"),
                        Filters.eq("op", "u"),
                        Filters.eq("op", "d")
                ),
                Filters.eq("ns", db)
        );

        return query;
    }
}