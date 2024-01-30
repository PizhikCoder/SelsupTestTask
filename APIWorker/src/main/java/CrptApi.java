
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Setter;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import javax.script.ScriptEngine;
import java.io.IOException;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


// Exceptions
class InvalidRequestsLimitException extends Exception {
    public InvalidRequestsLimitException(final int received, final int expected) {
        super(
                String.format("Wrong requests count received: <%d>\nExpected: <%d>",
                        received, expected)
        );
    }
}

class TooMuchRequestsException extends Exception {
    TooMuchRequestsException() {
        super("The limit on the number of requests has been exceeded!");
    }
}


// Data class
@Data
@AllArgsConstructor
class Document { // Replace "YourClassName" with a suitable class name

    private Description description;
    private String doc_id;
    private String doc_status;
    private String doc_type;
    private boolean importRequest;
    private String owner_inn;
    private String participant_inn;
    private String producer_inn;
    private String production_date;
    private String production_type;
    private List<Product> products;
    private String reg_date;
    private String reg_number;

    // Nested classes
    @Data
    @AllArgsConstructor
    public static class Description {
        private String participantInn;

    }

    @Data
    @AllArgsConstructor
    public static class Product {
        private String certificate_document;
        private String certificate_document_date;
        private String certificate_document_number;
        private String owner_inn;
        private String producer_inn;
        private String production_date;
        private String tnved_code;
        private String uit_code;
        private String uitu_code;
    }
}


// Serializer interface
interface ISerializer<T> {
    String serializeObject(T obj);

    T deserializeObject(String obj);
}


// Http requests sender interface
interface IHttpSender {
    String doGet(String url) throws IOException;

    String doPost(String url, String jsonParams) throws IOException;
}


// Http requests sender default impl
class DefaultHttpSender implements IHttpSender {

    @Override
    public String doGet(String url) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet httpGet = new HttpGet(url);
            // Выполнение запроса и получение ответа
            return executeRequest(httpClient, httpGet);
        }
    }

    @Override
    public String doPost(String url, String jsonParams) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);

            // Установка заголовков (если необходимо)
            httpPost.setHeader("Content-Type", "application/json");

            // Установка тела запроса
            StringEntity requestEntity = new StringEntity(jsonParams);
            httpPost.setEntity(requestEntity);

            // Выполнение запроса и получение ответа
            return executeRequest(httpClient, httpPost);
        }
    }

    private String executeRequest(CloseableHttpClient httpClient, HttpRequestBase requestBase) throws IOException {
        // Выполнение запроса и получение ответа
        try (CloseableHttpResponse response = httpClient.execute(requestBase)) {
            // Проверка статуса ответа
            if (response.getStatusLine().getStatusCode() == 200) {
                // Извлечение содержимого ответа
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    return EntityUtils.toString(entity);
                }
            } else {
                throw new RuntimeException("HTTP Request Failed with Status code: " +
                        response.getStatusLine().getStatusCode());
            }
        }
        return "";
    }
}


// Serializer default impl
class DefaultJsonDocumentSerializer implements ISerializer<Document> {
    private final Gson gson = new Gson();

    @Override
    public String serializeObject(Document obj) {
        return gson.toJson(obj);
    }

    @Override
    public Document deserializeObject(String obj) {
        return gson.fromJson(obj, Document.class);
    }
}


public class CrptApi {
    private final int MIN_REQUESTS_LIMIT = 0;
    private final long TIMER_DELAY = 0;
    private final long SINGLE_THREAD_SLEEP_INTERVAL = 1000;
    private final String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";

    private volatile int requestsLimit;
    private volatile TimeUnit requestsCounterRefreshTimeUnit;
    private volatile int requestsCounter;
    private final Lock requestsCounterLock;
    @Setter
    private ISerializer<Document> jsonSerializer;
    @Setter
    private IHttpSender httpSender;
    private final ExecutorService requestsQueueSingleThreadExecutor;
    private final Timer requestsCounterRefreshTimer;

    {
        this.jsonSerializer = new DefaultJsonDocumentSerializer();
        this.httpSender = new DefaultHttpSender();
        requestsCounterLock = new ReentrantLock();
        this.requestsCounterRefreshTimer = new Timer();
        requestsCounterRefreshTimer.schedule(new RefreshTimerTask(),
                TIMER_DELAY,
                requestsCounterRefreshTimeUnit.toMillis(1));
    }

    public CrptApi(final int requestsLimit, final TimeUnit requestsCounterRefreshTimeUnit) {
        this.requestsLimit = requestsLimit;
        this.requestsCounterRefreshTimeUnit = requestsCounterRefreshTimeUnit;
        this.requestsQueueSingleThreadExecutor = Executors.newSingleThreadExecutor();
    }

    public Future<String> create(Document document) {
        try {
            return CompletableFuture.completedFuture(sendRequest(document));
        } catch (TooMuchRequestsException e) {
            return requestsQueueSingleThreadExecutor.submit(new SingleThreadTask(document));
        }
    }

    protected String sendRequest(Document document) throws TooMuchRequestsException {
        try {
            requestsCounterLock.lock();
            if (!checkCounter()) throw new TooMuchRequestsException();
            String requestRes = httpSender.doPost(URL, jsonSerializer.serializeObject(document));
            requestsCounter--;
            return requestRes;
        } catch (IOException e) {
            System.out.println("Can not send your request!");
            return "";
        } finally {
            requestsCounterLock.unlock();
        }
    }

    private boolean checkCounter() {
        return requestsCounter > 0;
    }

    public void setRequestsLimit(final int requestsLimit) throws InvalidRequestsLimitException {
        if (requestsLimit < MIN_REQUESTS_LIMIT) {
            throw new InvalidRequestsLimitException(requestsLimit, MIN_REQUESTS_LIMIT);
        }

        requestsCounterLock.lock();
        this.requestsLimit = requestsLimit;
        requestsCounterLock.unlock();
    }

    public void setRequestsCounterRefreshTimeUnit(TimeUnit requestsCounterRefreshTimeUnit) {
        this.requestsCounterRefreshTimeUnit = requestsCounterRefreshTimeUnit;
        requestsCounterRefreshTimer.cancel();
        requestsCounterRefreshTimer.schedule(new RefreshTimerTask(),
                TIMER_DELAY,
                requestsCounterRefreshTimeUnit.toMillis(1));
    }

    // Nested classes
    protected class RefreshTimerTask extends TimerTask {
        @Override
        public void run() {
            requestsCounterLock.lock();
            requestsCounter = requestsLimit;
            requestsCounterLock.unlock();
        }
    }

    @AllArgsConstructor
    protected class SingleThreadTask implements Callable<String> {
        private Document documentToSend;

        @Override
        public String call() {
            while (true) {
                try {
                    Thread.sleep(SINGLE_THREAD_SLEEP_INTERVAL);
                    return sendRequest(documentToSend);
                } catch (TooMuchRequestsException ignore) {
                } catch (InterruptedException ex) {
                    System.out.println("Single thread executor interrupted!");
                    return "";
                }
            }
        }
    }

}

class Main {
    public static void main(String[] args) {
        CrptApi crptApi = new CrptApi(10, TimeUnit.MINUTES);
        Document document = null; // Something document
        crptApi.create(document);
    }
}
