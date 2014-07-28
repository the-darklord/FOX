package org.aksw.fox.web.feedback;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.aksw.fox.utils.FoxCfg;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class FeedbackHttpHandler extends HttpHandler {

    private static Logger      LOG                = Logger.getLogger(FeedbackHttpHandler.class);

    public static List<String> PARAMETER          = new ArrayList<>();
    public static List<String> PARAMETER_OPTIONAL = new ArrayList<>();
    static {
        Collections.addAll(PARAMETER,
                "key", "text", "entity_uri", "surface_form", "offset", "feedback", "systems", "manual", "annotation"
                );

        Collections.addAll(PARAMETER_OPTIONAL,
                "url", "gender", "language"
                );
    }

    // TODO
    String                     errorMessage       = "";
    protected FeedbackStore    feedbackStore      = null;
    private UrlValidator       urlValidator       = new UrlValidator();

    /**
     * Handles HTTP POST requests to store feedback.
     */
    public FeedbackHttpHandler() {
        this(new FeedbackStore());
    }

    /**
     * Handles HTTP POST requests to store feedback.
     * 
     * @param feedbackStore
     */
    public FeedbackHttpHandler(FeedbackStore feedbackStore) {
        this.feedbackStore = feedbackStore;
    }

    @Override
    public void service(Request request, Response response) throws Exception {
        LOG.info("service ...");
        if (LOG.isDebugEnabled()) {
            LOG.debug("mapping list: " + getMappings());
            LOG.debug("context path: " + request.getContextPath());
        }
        if (getMappings().contains(request.getContextPath())) {
            if (request.getMethod().getMethodString().equalsIgnoreCase("POST")) {
                LOG.info("service post ...");
                boolean done = false;
                if (request.getContentType().contains("application/json")) {
                    // json request
                    LOG.info("application/json ...");
                    done = insertJson(request, response);

                } else if (request.getContentType().contains("application/xml")) {
                    // xml request
                    LOG.info("application/xml ...");
                    done = insertXML(request, response);
                } else if (request.getContentType().contains("application/x-www-form-urlencoded")) {
                    // form
                    LOG.info("application/x-www-form-urlencoded ...");
                    done = insertForm(request, response);
                } else {
                    LOG.info("HTTP_UNSUPPORTED_TYPE (415)");
                    response.sendError(HttpURLConnection.HTTP_UNSUPPORTED_TYPE);
                }
                if (done) {
                    setResponse(response, "ok", HttpURLConnection.HTTP_OK, "text/plain");
                } else {
                    LOG.info("HTTP_BAD_REQUEST (400)");
                    response.sendError(HttpURLConnection.HTTP_BAD_REQUEST);
                }

            } else {
                LOG.info("HTTP_BAD_METHOD (405)");
                response.sendError(HttpURLConnection.HTTP_BAD_METHOD);
            }
        } else {
            LOG.info("HTTP_NOT_FOUND (404)");
            response.sendError(HttpURLConnection.HTTP_NOT_FOUND);
        }
    }

    protected String getQuery(Request request) {
        int contentLength = request.getContentLength();
        byte[] data = new byte[contentLength];
        InputStream is = new BufferedInputStream(request.getInputStream());
        int offset = 0;
        try {
            while (offset < contentLength) {
                final int pointer = is.read(data, offset, contentLength - offset);
                if (pointer == -1)
                    break;
                offset += pointer;
            }
        } catch (Exception e) {
            LOG.error("\n", e);
        }

        String query = "";
        try {
            query = new String(data, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            LOG.error("\n", e);
            query = "";
        }
        return query;
    }

    public boolean checkKey(String key) {
        if (key.equals(FoxCfg.get("api_key")))
            return true;
        else
            return false;
    }

    public boolean insertJson(Request request, Response response) {
        boolean rtn = false;
        String query = getQuery(request);
        LOG.info("query:" + query);

        if (!query.isEmpty()) {
            JsonObject json = (JsonObject) new JsonParser().parse(query);

            TextEntry textEntry = new TextEntry();
            LOG.info(json);

            String key = "";
            if (json.get("key") != null) {
                key = json.get("key").getAsString();
            }
            if (checkKey(key)) {
                // required
                List<FeedbackEntry> feedbackEntries = new ArrayList<>();
                try {
                    textEntry.text = json.get("text").getAsString();

                    for (JsonElement entity : json.get("entities").getAsJsonArray()) {
                        JsonObject e = entity.getAsJsonObject();

                        FeedbackEntry fe = new FeedbackEntry();
                        fe.entity_uri = e.get("entity_uri").getAsString();
                        fe.surface_form = e.get("surface_form").getAsString();
                        fe.offset = e.get("offset").getAsInt();
                        fe.feedback = e.get("feedback").getAsString();
                        fe.systems = e.get("systems").getAsString();
                        fe.manual = e.get("manual").getAsString();
                        fe.annotation = e.get("annotation").getAsString();

                        // TODO: check all values

                        // check given offset
                        boolean found = true;
                        String tmp_surface_form = "";
                        try {
                            tmp_surface_form = textEntry.text.substring(fe.offset, fe.offset + fe.surface_form.length());
                        } catch (Exception ee) {
                            found = false;
                        }
                        if (!found || !tmp_surface_form.equals(fe.surface_form)) {
                            setErrorMessage("Can't find surface form in text with the given offset: " + fe.entity_uri);
                            break;
                        }

                        // check uri
                        if (isValidUrl(fe.entity_uri)) {
                            feedbackEntries.add(fe);
                        } else {
                            setErrorMessage("Isn't a valid url: " + fe.entity_uri);
                            break;
                        }
                    }
                    rtn = true;

                } catch (Exception e) {
                    LOG.error("\n", e);
                    errorMessage = "Couldn't read data, check required parameters.";
                }

                // optional
                textEntry.url = (json.get("url") != null) ? json.get("url").toString() : "";
                textEntry.gender = (json.get("gender") != null) ? json.get("gender").toString() : "";
                textEntry.language = (json.get("language") != null) ? json.get("language").toString() : "";

                // insert data
                if (rtn) {
                    rtn = insert(textEntry, feedbackEntries);

                }
            } else {
                setErrorMessage("Wrong feedback api key.");
            }
        } else {
            setErrorMessage("Empty query.");
        }
        return rtn;
    }

    public boolean insertXML(Request request, Response response) {
        errorMessage = "XML not supported yet.";
        return false;
    }

    public boolean insertForm(Request request, Response response) {
        boolean rtn = true;
        boolean apiKeyValid = false;
        TextEntry textEntry = null;
        FeedbackEntry fe = new FeedbackEntry();
        List<FeedbackEntry> feedbackEntries = new ArrayList<>();
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap != null && parameterMap.size() > 0) {
            textEntry = new TextEntry();

            for (Entry<String, String[]> entry : parameterMap.entrySet()) {
                if (rtn && entry.getValue() != null && entry.getValue().length > 0)
                    try {
                        String key = entry.getKey();
                        if (key != null && entry.getValue().length > 0) {
                            String v = entry.getValue()[0];
                            switch (key.toLowerCase()) {

                            case "key":
                                String apiKey = v;
                                if (apiKey != null && checkKey(apiKey)) {
                                    apiKeyValid = true;
                                }
                                break;

                            case "text":
                                textEntry.text = URLDecoder.decode(v, "UTF-8");
                                break;

                            case "entity_uri":
                                fe.entity_uri = URLDecoder.decode(v, "UTF-8");
                                break;

                            case "surface_form":
                                fe.surface_form = URLDecoder.decode(v, "UTF-8");
                                break;

                            case "offset":
                                fe.offset = Integer.valueOf(v);
                                break;

                            case "feedback":
                                fe.feedback = v;
                                break;

                            case "systems":
                                fe.systems = v;
                                break;

                            case "manual":
                                fe.manual = v;
                                break;
                            case "annotation":
                                fe.annotation = v;
                                break;
                            case "gender":
                                textEntry.gender = v;
                                break;

                            case "url":
                                textEntry.url = v;
                                break;

                            case "language":
                                textEntry.language = v;
                                break;
                            }
                        } else {
                            rtn = false;
                            errorMessage = "Parameter missing or no value given.";
                            break;
                        }
                    } catch (Exception e) {
                        rtn = false;
                        errorMessage = "Exception while reading parameters and values.";
                        LOG.error("\n", e);
                        break;
                    }
            }
        } else {
            errorMessage = "Parameters missing.";
            rtn = false;
        }

        // check given offset
        boolean found = true;
        String tmp_surface_form = "";
        try {
            tmp_surface_form = textEntry.text.substring(fe.offset, fe.offset + fe.surface_form.length());
        } catch (Exception ee) {
            found = false;
        }
        if (!found || !tmp_surface_form.equals(fe.surface_form)) {
            rtn = false;
            setErrorMessage("Can't find surface form in text with the given offset: " + fe.entity_uri);
        }

        // check uri
        if (!isValidUrl(fe.entity_uri)) {
            rtn = false;
            setErrorMessage("Isn't a valid url: " + fe.entity_uri);
        }

        // check api key
        if (rtn) {
            feedbackEntries.add(fe);
            if (!apiKeyValid) {
                errorMessage = "Wrong feedback api key.";
                rtn = false;
            } else if (textEntry != null && feedbackEntries.size() > 0) {
                rtn = insert(textEntry, feedbackEntries);
            } else {
                errorMessage = "Nothing found.";
                rtn = false;
            }
        }
        return rtn;
    }

    private boolean isValidUrl(String url) {
        if (!urlValidator.isValid(url)) {
            LOG.error("uri isn't valid: " + url);
            return false;
        }
        return true;
    }

    /**
     * Inserts some text into the store.
     * 
     * @param in
     */
    protected boolean insert(TextEntry textEntry, List<FeedbackEntry> feedbackEntries) {
        boolean rtn = feedbackStore.insert(textEntry, feedbackEntries);
        if (!rtn)
            errorMessage = feedbackStore.errorMessage;
        return rtn;
    }

    public List<String> getMappings() {
        List<String> l = new ArrayList<>();
        l.add("/api/ner/feedback");
        return l;
    }

    /**
     * Writes data to response.
     * 
     * @param response
     * @param text
     * @param status
     * @param contentType
     */
    protected void setResponse(Response response, String data, int status, String contentType) {

        response.setContentType(contentType);
        response.setCharacterEncoding("utf-8");
        response.setStatus(status);

        byte[] bytes = data.getBytes();
        try {
            response.setContentLength(bytes.length);
            response.getWriter().write(data);
        } catch (IOException e) {
            LOG.error("\n", e);
        }
        response.finish();
    }

    private void setErrorMessage(String e) {
        LOG.error(e);
        errorMessage = e;
    }
}