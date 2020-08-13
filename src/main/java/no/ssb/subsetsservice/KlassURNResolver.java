package no.ssb.subsetsservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KlassURNResolver {

    private static final Logger LOG = LoggerFactory.getLogger(KlassURNResolver.class);
    public static String klassBaseURL = "https://data.ssb.no/api/klass/v1/classifications";

    public static String getURL(){
        return System.getenv().getOrDefault("API_KLASS", klassBaseURL);
    }

    public JsonNode resolveURN(String codeURN, String from, String to){
        LOG.info("Attempting to resolve the KLASS code URN "+codeURN);
        String[] urnSplitColon = codeURN.split(":");
        String classificationID = "";
        String code = "";
        for (int i = 0; i < urnSplitColon.length; i++) {
            String value = urnSplitColon[i];
            if (value.equals("code")){
                if (urnSplitColon.length > i+1)
                    code = urnSplitColon[i+1];
            } else if (value.equals("classifications")){
                if (urnSplitColon.length > i+1)
                    classificationID = urnSplitColon[i+1];
            }
        }
        from = from.split("T")[0];
        to = to.split("T")[0];
        String url = makeURL(classificationID, from, to, code);
        ResponseEntity<JsonNode> selectCodesRE = getFrom(url);
        JsonNode codes = selectCodesRE.getBody();
        return codes;
    }

    public ArrayNode resolveURNs(List<String> codeURNs, String from, String to) {
        LOG.info("Resolving all code URNs in a subset");
        Map<String, String> classificationCodesMap = new HashMap<>();
        for (String codeURN : codeURNs) {
            String[] urnSplitColon = codeURN.split(":");
            String classificationID = "";
            String code = "";
            for (int i = 0; i < urnSplitColon.length; i++) {
                String value = urnSplitColon[i];
                if (value.equals("code")){
                    if (urnSplitColon.length > i+1)
                        code = urnSplitColon[i+1];
                } else if (value.equals("classifications")){
                    if (urnSplitColon.length > i+1)
                        classificationID = urnSplitColon[i+1];
                }
            }
            classificationCodesMap.merge(classificationID, code, (c1, c2)-> c1+","+c2);
        }

        String fromDate = from.split("T")[0];
        String toDate = to.split("T")[0];

        if (fromDate.compareTo(toDate) >= 0 && !toDate.equals(""))
            throw new IllegalArgumentException("fromDate "+fromDate+" must be before toDate "+toDate+", but was the same as or after the toDate. ");

        List<ArrayNode> codesArrayNodeList = new ArrayList<>();
        for (Map.Entry<String, String> classificationCodesEntry : classificationCodesMap.entrySet()) {
            String classification = classificationCodesEntry.getKey();
            String codesString = classificationCodesEntry.getValue();
            String URL = makeURL(classification, fromDate, toDate, codesString);
            ArrayNode codesFromClassification = (ArrayNode)(getFrom(URL).getBody().get(Field.CODES));
            String[] codesArray = codesString.split(",");
            for (int i = 0; i < codesFromClassification.size(); i++) {
                ObjectNode editableCode = codesFromClassification.get(i).deepCopy();
                editableCode.put("classification", classification);
                editableCode.put(Field.URN, Utils.generateURN(classification, editableCode.get("code").asText()));
                ArrayNode links = new ObjectMapper().createArrayNode();
                String selfURL = makeURL(classification, fromDate, toDate, codesArray[i]);
                links.add(new ObjectMapper().createObjectNode().put("_self", selfURL));
                editableCode.set("links", links);
                codesFromClassification.set(i, editableCode);
            }
        }
        classificationCodesMap.forEach((classification, codes) -> codesArrayNodeList.add((ArrayNode)(getFrom(makeURL(classification, fromDate, toDate, codes)).getBody().get(Field.CODES))));
        ArrayNode allCodesArrayNode = new ObjectMapper().createArrayNode();
        for (ArrayNode codes : codesArrayNodeList) {
            codes.forEach(allCodesArrayNode::add);
        }
        return allCodesArrayNode;
    }

    private String makeURL(String classificationID, String from, String to, String codes){
        klassBaseURL = getURL();
        return String.format("%s/%s/codes.json?from=%s&to=%s&selectCodes=%s", klassBaseURL, classificationID, from, to, codes);
    }

    private ResponseEntity<JsonNode> getFrom(String url)
    {
        LOG.info("Attempting to GET "+url);
        try {
            return new RestTemplate().getForEntity(url, JsonNode.class);
        } catch (HttpClientErrorException | HttpServerErrorException e){
            return ErrorHandler.newHttpError("could not retrieve "+url+".", e.getStatusCode(), LOG);
        }
    }
}
