
import com.google.gson.Gson;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    // Exceptions
    protected static class InvalidRequestsLimitException extends Exception {
        public InvalidRequestsLimitException(final int received, final int expected) {
            super(
                    String.format("Wrong requests count received: <%d>\nExpected: <%d>",
                            received, expected)
            );
        }
    }

    // Data class
    protected static class Document { // Replace "YourClassName" with a suitable class name

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

        public Description getDescription() {
            return description;
        }

        public void setDescription(Description description) {
            this.description = description;
        }

        public String getDoc_id() {
            return doc_id;
        }

        public void setDoc_id(String doc_id) {
            this.doc_id = doc_id;
        }

        public String getDoc_status() {
            return doc_status;
        }

        public void setDoc_status(String doc_status) {
            this.doc_status = doc_status;
        }

        public String getDoc_type() {
            return doc_type;
        }

        public void setDoc_type(String doc_type) {
            this.doc_type = doc_type;
        }

        public boolean isImportRequest() {
            return importRequest;
        }

        public void setImportRequest(boolean importRequest) {
            this.importRequest = importRequest;
        }

        public String getOwner_inn() {
            return owner_inn;
        }

        public void setOwner_inn(String owner_inn) {
            this.owner_inn = owner_inn;
        }

        public String getParticipant_inn() {
            return participant_inn;
        }

        public void setParticipant_inn(String participant_inn) {
            this.participant_inn = participant_inn;
        }

        public String getProducer_inn() {
            return producer_inn;
        }

        public void setProducer_inn(String producer_inn) {
            this.producer_inn = producer_inn;
        }

        public String getProduction_date() {
            return production_date;
        }

        public void setProduction_date(String production_date) {
            this.production_date = production_date;
        }

        public String getProduction_type() {
            return production_type;
        }

        public void setProduction_type(String production_type) {
            this.production_type = production_type;
        }

        public List<Product> getProducts() {
            return products;
        }

        public void setProducts(List<Product> products) {
            this.products = products;
        }

        public String getReg_date() {
            return reg_date;
        }

        public void setReg_date(String reg_date) {
            this.reg_date = reg_date;
        }

        public String getReg_number() {
            return reg_number;
        }

        public void setReg_number(String reg_number) {
            this.reg_number = reg_number;
        }

        // Inner classes for nested structures

        public static class Description {
            private String participantInn;

            public String getParticipantInn() {
                return participantInn;
            }

            public void setParticipantInn(String participantInn) {
                this.participantInn = participantInn;
            }
        }

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

            // Getters and setters for product fields
        }
    }

    // Serializer interface
    public interface ISerializer<T> {
        String serializeObject(T obj);

        T deserializeObject(String obj);
    }

    // Http requests sender interface
    public interface IHttpSender{
        String doGet(String url) throws IOException;

        String doPost(String url, String jsonParams) throws IOException;
    }

    // Http requests sender default impl
    protected static class DefaultHttpSender implements IHttpSender{

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
    protected static class DefaultJsonDocumentSerializer implements ISerializer<Document> {
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

    // Fields
    private final int MIN_REQUESTS_LIMIT = 0;
    private int requestsLimit;
    private TimeUnit requestsCounterRefreshTimeUnit;
    private volatile int requestsCounter;
    private volatile Long lastRequestTimeNanos;
    private ISerializer<Document> jsonSerializer;
    private IHttpSender httpSender;
    private ExecutorService requestsQueueSingleThreadExecutor;

    public CrptApi(final int requestsLimit, final TimeUnit requestsCounterRefreshTimeUnit) {
        this.requestsLimit = requestsLimit;
        this.requestsCounterRefreshTimeUnit = requestsCounterRefreshTimeUnit;
        this.requestsQueueSingleThreadExecutor = Executors.newSingleThreadExecutor();
        this.jsonSerializer = new DefaultJsonDocumentSerializer();
        this.httpSender = new DefaultHttpSender();
    }

    public String create(Document document){
    }

    protected boolean checkLastRequestTimeAndSet(){
        final long currentTime = System.nanoTime();
        if (lastRequestTimeNanos != null ||
                currentTime - lastRequestTimeNanos > requestsCounterRefreshTimeUnit.toNanos(1)){
            lastRequestTimeNanos = currentTime;
            return false;
        }
        lastRequestTimeNanos = currentTime;
        return true;
    }

    public void setRequestsCounterRefreshTimeUnit(TimeUnit requestsCounterRefreshTimeUnit) {
        this.requestsCounterRefreshTimeUnit = requestsCounterRefreshTimeUnit;
    }

    private void setRequestsLimit(final int requestsLimit) throws InvalidRequestsLimitException {
        if (requestsLimit < MIN_REQUESTS_LIMIT) {
            throw new InvalidRequestsLimitException(requestsLimit, MIN_REQUESTS_LIMIT);
        }

        this.requestsLimit = requestsLimit;
    }

    public void setJsonSerializer(ISerializer<Document> jsonSerializer) {
        this.jsonSerializer = jsonSerializer;
    }

}
