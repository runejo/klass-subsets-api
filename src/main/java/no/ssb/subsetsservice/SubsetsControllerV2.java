package no.ssb.subsetsservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.http.HttpStatus.*;

@CrossOrigin
@RestController
public class SubsetsControllerV2 {

    private MetricsService metricsService;

    private static SubsetsControllerV2 instance;
    private static final Logger LOG = LoggerFactory.getLogger(SubsetsControllerV2.class);

    @Autowired
    public SubsetsControllerV2(MetricsService metricsService){
        this.metricsService = metricsService;
        instance = this;
    }

    public SubsetsControllerV2(){
        instance = this;
    }

    public static SubsetsControllerV2 getInstance(){
        return instance;
    }

    @GetMapping("/v2/subsets")
    public ResponseEntity<JsonNode> getSubsetSeries(
            @RequestParam(defaultValue = "true") boolean includeDrafts,
            @RequestParam(defaultValue = "true") boolean includeFuture,
            @RequestParam(defaultValue = "true") boolean includeExpired) {
        metricsService.incrementGETCounter();

        LOG.info("GET all subsets includeDrafts="+includeDrafts+" includeFuture="+includeFuture+" includeExpired="+includeExpired);
        LDSFacade ldsFacade = new LDSFacade();
        ResponseEntity<JsonNode> subsetSeriesRE = ldsFacade.getAllSubsetSeries();
        ArrayNode subsetSeriesArray = subsetSeriesRE.getBody().deepCopy();
        String nowDate = Utils.getNowDate();
        for (int i = 0; i < subsetSeriesArray.size(); i++) {
            subsetSeriesArray.set(i, addLinksToSeries(subsetSeriesArray.get(i))); //FIXME: performance wise it would be best not to do this
        }
        if (!includeDrafts || !includeFuture || !includeExpired){
            for (int i = subsetSeriesArray.size() - 1; i >= 0; i--) {
                JsonNode subsetSeries = subsetSeriesArray.get(i);
                if (!includeDrafts && subsetSeries.get(Field.ADMINISTRATIVE_STATUS).equals(Field.DRAFT))
                    subsetSeriesArray.remove(i);
                else if (!includeFuture && subsetSeries.get(Field.VALID_FROM).asText().compareTo(nowDate) > 0)
                    subsetSeriesArray.remove(i);
                else if (!includeExpired
                        && subsetSeries.has(Field.VALID_UNTIL)
                        && !subsetSeries.get(Field.VALID_UNTIL).asText().isBlank()
                        && subsetSeries.get(Field.VALID_UNTIL).asText().compareTo(nowDate) < 0)
                    subsetSeriesArray.remove(i);
            }
        }
        return new ResponseEntity<>(subsetSeriesArray, OK);
    }

    /**
     * Create a new ClassificationSubsetSeries resource, with no versions yet
     * @param subsetSeriesJson
     * @return
     */
    @PostMapping("/v2/subsets")
    public ResponseEntity<JsonNode> postSubsetSeries(@RequestBody JsonNode subsetSeriesJson) {
        metricsService.incrementPOSTCounter();
        LOG.info("POST subset series received. Checking body . . .");

        if (subsetSeriesJson.isNull() || subsetSeriesJson.isEmpty())
            return ErrorHandler.newHttpError("POST subset series body was empty. Must be a subset series object.", BAD_REQUEST, LOG);
        if (subsetSeriesJson.isArray())
            return ErrorHandler.newHttpError("POST subset series body was array. Most be single subset series object.", BAD_REQUEST, LOG);

        ObjectNode editableSubsetSeries = subsetSeriesJson.deepCopy();
        subsetSeriesJson = null;

        if (!editableSubsetSeries.has(Field.ID))
            return ErrorHandler.newHttpError("POST subset series: Subset series must contain the field 'id'", BAD_REQUEST, LOG);

        String id = editableSubsetSeries.get(Field.ID).textValue();
        LOG.info("POST subset series with id "+id);

        if (!Utils.isClean(id))
            return ErrorHandler.illegalID(LOG);

        boolean subsetExists = new LDSFacade().existsSubsetSeriesWithID(id);
        if (subsetExists)
            return ErrorHandler.newHttpError(
                    "POST: Can not create subset. ID already in use",
                    BAD_REQUEST,
                    LOG);
        LOG.info("Subset with id "+id+" does not exist from before");

        editableSubsetSeries.put(Field.LAST_MODIFIED, Utils.getNowISO());
        editableSubsetSeries.put(Field.CREATED_DATE, Utils.getNowDate());
        editableSubsetSeries.put(Field.CLASSIFICATION_TYPE, Field.SUBSET);
        editableSubsetSeries.set(Field.VERSIONS, new ObjectMapper().createArrayNode());

        ResponseEntity<JsonNode> seriesSchemaValidationRE = validateSeries(editableSubsetSeries);
        if (!seriesSchemaValidationRE.getStatusCode().is2xxSuccessful())
            return seriesSchemaValidationRE;

        LOG.debug("POSTING subset series with id "+id+" to LDS");
        ResponseEntity<JsonNode> responseEntity = new LDSFacade().createSubsetSeries(editableSubsetSeries, id);
        if (responseEntity.getStatusCode().equals(CREATED)){
            LOG.info("Series with id "+id+" was successfully created in LDS");
            responseEntity = new ResponseEntity<>(editableSubsetSeries, CREATED);
        } else {
            LOG.error("Subset series with id " + id + " was NOT CREATED in LDS! Returning LDS responseEntity . . .");
        }
        return responseEntity;
    }

    @GetMapping("/v2/subsets/{id}")
    public ResponseEntity<JsonNode> getSubsetSeriesByID(@PathVariable("id") String id, @RequestParam(defaultValue = "false") boolean includeFullVersions) {
        metricsService.incrementGETCounter();
        LOG.info("GET subset series with id "+id);

        if (!Utils.isClean(id))
            return ErrorHandler.illegalID(LOG);

        ResponseEntity<JsonNode> subsetSeriesByIDRE = new LDSFacade().getSubsetSeries(id);
        HttpStatus status = subsetSeriesByIDRE.getStatusCode();
        LOG.debug("Call to LDSFacade to get a subset series with id "+id+" returned "+status.toString());
        if (status.equals(OK)) {
            ObjectNode series = addLinksToSeries(subsetSeriesByIDRE.getBody());
            if (includeFullVersions){
                LOG.debug("Including full versions");
                ResponseEntity<JsonNode> versionsRE = getVersions(id, true, true);
                if (versionsRE.getStatusCode().is2xxSuccessful())
                    series.set(Field.VERSIONS, versionsRE.getBody());
                else
                    return resolveNonOKLDSResponse("GET request to LDS for all versions of subset with id "+id+" ", versionsRE);
            }
            return new ResponseEntity<>(series, OK);
        } else
            return subsetSeriesByIDRE;
    }

    /**
     * Use this to make edits to the ClassificationSubsetSeries, except the versions array.
     * This is the information that is in common for all the versions.
     * Changes to the versions array will not be accepted.
     * To add a new version to the versions array you must instead use POST /v2/subsets/{id}/versions
     * To edit an existing version you must use PUT /v2/subsets/{id}/versions/{version_id}
     * @param seriesId
     * @param newEditionOfSeries
     * @return
     */
    @PutMapping(value = "/v2/subsets/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> putSubsetSeries(@PathVariable("id") String seriesId, @RequestBody JsonNode newEditionOfSeries) {
        metricsService.incrementPUTCounter();
        LOG.info("PUT subset series with id "+seriesId);

        if (!Utils.isClean(seriesId))
            return ErrorHandler.illegalID(LOG);
        
        if (newEditionOfSeries.isNull() || newEditionOfSeries.isEmpty() || newEditionOfSeries.isArray())
            return ErrorHandler.newHttpError("PUT body must be a non-empty object representing a single subset series", BAD_REQUEST, LOG);

        ResponseEntity<JsonNode> getSeriesRE = new LDSFacade().getSubsetSeries(seriesId);

        if (getSeriesRE.getStatusCode().equals(NOT_FOUND))
            return ErrorHandler.newHttpError(
                    "Can not PUT (edit) a subset that does not exist from before. POST the subset instead if you wish to create it",
                    BAD_REQUEST,
                    LOG);

        if (!getSeriesRE.getStatusCode().equals(OK)) {
            return resolveNonOKLDSResponse("GET from LDS subsetSeries w id '"+seriesId+"'", getSeriesRE);
        }

        ObjectNode currentLatestEditionOfSeries = getSeriesRE.getBody().deepCopy();
        currentLatestEditionOfSeries.remove(Field._LINKS);
        ObjectNode editableNewEditionOfSeries = newEditionOfSeries.deepCopy();
        newEditionOfSeries = null;
        editableNewEditionOfSeries.remove(Field._LINKS);

        ArrayNode oldVersionsArray = currentLatestEditionOfSeries.has(Field.VERSIONS) ? currentLatestEditionOfSeries.get(Field.VERSIONS).deepCopy() : new ObjectMapper().createArrayNode();
        editableNewEditionOfSeries.set(Field.VERSIONS, oldVersionsArray);
        editableNewEditionOfSeries.set(Field.CREATED_DATE, currentLatestEditionOfSeries.get(Field.CREATED_DATE));

        assert editableNewEditionOfSeries.has(Field.ID) : "Subset series did not have the field '"+Field.ID+"'.";

        editableNewEditionOfSeries.put(Field.LAST_MODIFIED, Utils.getNowISO());

        ResponseEntity<JsonNode> seriesSchemaValidationRE = validateSeries(editableNewEditionOfSeries);
        if (!seriesSchemaValidationRE.getStatusCode().is2xxSuccessful())
            return seriesSchemaValidationRE;

        String oldID = currentLatestEditionOfSeries.get(Field.ID).asText();
        String newID = editableNewEditionOfSeries.get(Field.ID).asText();

        boolean sameID = oldID.equals(newID);
        boolean sameIDAsRequest = newID.equals(seriesId);
        boolean consistentID = sameID && sameIDAsRequest;
        if (!consistentID){
            StringBuilder errorStringBuilder = new StringBuilder();
            if (!sameID)
                errorStringBuilder.append("- ID of submitted subset series (").append(newID).append(") was not the same as id of stored subset (").append(oldID).append("). ");
            if(!sameIDAsRequest)
                errorStringBuilder.append("- ID of submitted subset series (").append(newID).append(") was not the same as id in request param (").append(seriesId).append("). ");
            return ErrorHandler.newHttpError(
                    errorStringBuilder.toString(),
                    BAD_REQUEST,
                    LOG);
        }

        ResponseEntity<JsonNode> responseEntity = new LDSFacade().editSeries(editableNewEditionOfSeries, seriesId);
        if (responseEntity.getStatusCode().is2xxSuccessful()){
            responseEntity = new ResponseEntity<>(editableNewEditionOfSeries, OK);
        }
        return responseEntity;
    }

    /**
     * Use this to edit an existing version within a series.
     * Only edit versions that already have been created with post.
     * @param seriesId
     * @param versionUID
     * @param putVersion
     * @return
     */
    @PutMapping(value = "/v2/subsets/{seriesId}/versions/{versionUID}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> putSubsetVersion(@PathVariable("seriesId") String seriesId, @PathVariable("versionUID") String versionUID, @RequestBody JsonNode putVersion) {
        LOG.info("PUT subset version of series "+seriesId+" with version id "+versionUID);
        if (!Utils.isClean(seriesId))
            return ErrorHandler.illegalID(LOG);
        if (!Utils.isClean(versionUID))
            return ErrorHandler.newHttpError("Illegal characters in versionUID", BAD_REQUEST, LOG);

        if (putVersion.isNull() || putVersion.isEmpty() || putVersion.isArray())
            return ErrorHandler.newHttpError("PUT body must be a non-empty object representing a single subset version", BAD_REQUEST, LOG);

        ResponseEntity<JsonNode> getPreviousEditionOfVersion = getVersion(seriesId, versionUID);
        HttpStatus status = getPreviousEditionOfVersion.getStatusCode();
        if (status.equals(NOT_FOUND)){
            return ErrorHandler.newHttpError("Can not edit a subset version that does not exist.", BAD_REQUEST, LOG);
        }
        if (!status.equals(OK))
            return getPreviousEditionOfVersion;
        JsonNode previousEditionOfVersion = getPreviousEditionOfVersion.getBody();
        ObjectNode editablePutVersion = putVersion.deepCopy();

        editablePutVersion.set(Field.VERSION, previousEditionOfVersion.get(Field.VERSION));
        editablePutVersion.set(Field.SERIES_ID, previousEditionOfVersion.get(Field.SERIES_ID));
        editablePutVersion.put(Field.LAST_MODIFIED, Utils.getNowISO());
        editablePutVersion.set(Field.CREATED_DATE, previousEditionOfVersion.get(Field.CREATED_DATE));
        editablePutVersion = Utils.addCodeVersionsToAllCodesInVersion(editablePutVersion, LOG);

        ResponseEntity<JsonNode> versionSchemaValidationRE = validateVersion(editablePutVersion);
        if (!versionSchemaValidationRE.getStatusCode().is2xxSuccessful())
            return versionSchemaValidationRE;

        boolean wasDraftFromBefore = previousEditionOfVersion.get(Field.ADMINISTRATIVE_STATUS).asText().equals(Field.DRAFT);
        boolean isStatusOpen = editablePutVersion.get(Field.ADMINISTRATIVE_STATUS).asText().equals(Field.OPEN);

        if (isStatusOpen){
            if (editablePutVersion.get(Field.CODES).isEmpty())
                return ErrorHandler.newHttpError(
                        "Can not publish a subset with an empty code list",
                        HttpStatus.BAD_REQUEST,
                        LOG);
        }

        // One set of rules for if the old version is DRAFT:
        if (wasDraftFromBefore){
            if (isStatusOpen ||
                    (!previousEditionOfVersion.get(Field.VALID_FROM).equals(editablePutVersion.get(Field.VALID_FROM))) ||
                    (previousEditionOfVersion.has(Field.VALID_UNTIL) && (!editablePutVersion.has(Field.VALID_UNTIL)) || !editablePutVersion.get(Field.VALID_UNTIL).equals(previousEditionOfVersion.get(Field.VALID_UNTIL))) ||
                    (editablePutVersion.has(Field.VALID_UNTIL) && !previousEditionOfVersion.has(Field.VALID_UNTIL)) ){
                // check validity period overlap with other OPEN versions
                ResponseEntity<JsonNode> isOverlappingValidityRE = isOverlappingValidity(editablePutVersion);
                if (!isOverlappingValidityRE.getStatusCode().is2xxSuccessful()){
                    return isOverlappingValidityRE;
                }
                if (isStatusOpen) { // If this version is being published, check if we need to end the validity period of the previous currently valid version.
                    ResponseEntity<JsonNode> updateLatestPublishedValidUntilRE = updateLatestPublishedValidUntil(isOverlappingValidityRE, editablePutVersion, seriesId);
                    //TODO: Handle possible errors in the updateLatestPublishedValidUntilRE
                }
            }
            // If the new version is OPEN and the old one was DRAFT:
            // OR If validFrom or validUntil is changed

        } else { // Another stricter set of rules for if the old version is OPEN
            String oldCodeList = previousEditionOfVersion.get(Field.CODES).asText();
            String newCodeList = editablePutVersion.get(Field.CODES).asText();
            if (!oldCodeList.equals(newCodeList)){
                return ErrorHandler.newHttpError("Changes in code list not allowed to published subsets", BAD_REQUEST, LOG);
            }
            String oldValidFrom = previousEditionOfVersion.get(Field.VALID_FROM).asText();
            String newValidFrom = editablePutVersion.get(Field.VALID_FROM).asText();
            if (!oldValidFrom.equals(newValidFrom)){
                return ErrorHandler.newHttpError("Changes in validFrom not allowed to published subset", BAD_REQUEST, LOG);
            }
            String[] changeableFields = new String[]{Field.LAST_MODIFIED, Field.LAST_UPDATED_BY, Field.VALID_UNTIL, Field._LINKS, Field.STATISTICAL_UNITS};
            ResponseEntity<JsonNode> compareFieldsRE = compareFields(previousEditionOfVersion, editablePutVersion, changeableFields);
            if (!compareFieldsRE.getStatusCode().is2xxSuccessful())
                return compareFieldsRE;

        }
        ResponseEntity<JsonNode> editVersionRE = new LDSFacade().editVersion(editablePutVersion);
        if (editVersionRE.getStatusCode().is2xxSuccessful())
            return new ResponseEntity<>(editablePutVersion, OK);
        return editVersionRE;
    }

    @PostMapping(value = "/v2/subsets/{seriesId}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> postSubsetVersion(@PathVariable("seriesId") String seriesId, @RequestBody JsonNode version) {
        LOG.info("POST request to create a version of series "+seriesId);
        if (!Utils.isClean(seriesId))
            return ErrorHandler.illegalID(LOG);
        LOG.info("series id "+seriesId+" was legal");


        if (version.isNull() || version.isEmpty())
            return ErrorHandler.newHttpError("POST body was empty. Should contain a single subset version.", BAD_REQUEST, LOG);
        if (version.isArray())
            return ErrorHandler.newHttpError("POST body was an array. Should be an object representing a single subset version.", BAD_REQUEST, LOG);

        ResponseEntity<JsonNode> getSeriesByIDRE = getSubsetSeriesByID(seriesId, false);
        if (!getSeriesByIDRE.getStatusCode().equals(OK)) {
            LOG.error("Attempt to get subset series by id '"+seriesId+"' returned a non-OK status code.");
            return getSeriesByIDRE;
        }

        ObjectNode editableVersion = version.deepCopy();
        version = null;

        String validFrom = editableVersion.get(Field.VALID_FROM).asText();
        String validUntil = editableVersion.has(Field.VALID_UNTIL) && !editableVersion.get(Field.VALID_UNTIL).isNull() ? editableVersion.get(Field.VALID_UNTIL).asText() : null;
        boolean hasValidUntil = validUntil != null;
        if (hasValidUntil && validFrom.compareTo(validUntil) >= 0)
            return ErrorHandler.newHttpError(
                    "validFrom can not be the same date as- or before validUntil, when validUntil is defined",
                    BAD_REQUEST,
                    LOG);

        JsonNode series = getSeriesByIDRE.getBody();
        int versionsSize = series.has(Field.VERSIONS) ? series.get(Field.VERSIONS).size() : 0;
        LOG.debug("Amount of found versions in series "+seriesId+" before potentially adding new version: "+versionsSize);
        String versionNr = UUID.randomUUID().toString();
        editableVersion.put(Field.VERSION, versionNr);
        editableVersion.put(Field.SERIES_ID, seriesId);
        editableVersion.put(Field.LAST_MODIFIED, Utils.getNowISO());
        editableVersion.put(Field.CREATED_DATE, Utils.getNowDate());
        editableVersion = Utils.addCodeVersionsToAllCodesInVersion(editableVersion, LOG);

        boolean isStatusOpen = editableVersion.get(Field.ADMINISTRATIVE_STATUS).asText().equals(Field.OPEN);

        if (isStatusOpen)
            if (!editableVersion.has(Field.CODES) || editableVersion.get(Field.CODES).size() == 0)
                return ErrorHandler.newHttpError("Published subset version must have a non-empty code list", BAD_REQUEST, LOG);

        ArrayNode codes = (ArrayNode)editableVersion.get(Field.CODES);
        Map<String, Boolean> classificationMap = new HashMap<>();
        codes.forEach(c -> classificationMap.put(c.get(Field.CLASSIFICATION_ID).asText(), true));
        LOG.debug("printing "+classificationMap.keySet().size()+" classifications:");
        classificationMap.forEach((k,v)-> LOG.debug("classification "+k));
        LOG.debug("done printing classifications");
        AtomicReference<String> errorMessage = new AtomicReference<>("");
        LOG.debug("Getting statistical units for each individual classification used");
        Map<String, Boolean> statisticalUnitMap = new HashMap<>();
        classificationMap.keySet().forEach( k -> {
            ResponseEntity<JsonNode> getClassificationRE = KlassURNResolver.getFrom(KlassURNResolver.makeKLASSClassificationURL(k));
            if (getClassificationRE.getStatusCode().is2xxSuccessful()){
                JsonNode classification = getClassificationRE.getBody();
                if (!classification.has(Field.STATISTICAL_UNITS))
                    LOG.error("Classification "+k+" did not contain a "+Field.STATISTICAL_UNITS+" field!");
                else {
                    ArrayNode classificationStatisticalUnits = (ArrayNode)classification.get(Field.STATISTICAL_UNITS);
                    classificationStatisticalUnits.forEach(su -> statisticalUnitMap.put(su.asText(), true));
                }
            } else {
                errorMessage.accumulateAndGet("GET Classification " + k + " did not return 2xx successful. Instead returned " + getClassificationRE.getStatusCode()+". ", (x,y)-> x+y);
            }
        });
        if (!errorMessage.get().isEmpty())
            return ErrorHandler.newHttpError(errorMessage.get(), INTERNAL_SERVER_ERROR, LOG);
        else
            LOG.debug("There were no errors while finding out which "+statisticalUnitMap.size()+" statistical units are used by the "+classificationMap.size()+" classifications used in the new version of series "+seriesId);

        ArrayNode versionStatisticalUnitsArrayNode = new ObjectMapper().createArrayNode();
        statisticalUnitMap.forEach((k,v) -> versionStatisticalUnitsArrayNode.add(k));
        editableVersion.set(Field.STATISTICAL_UNITS, versionStatisticalUnitsArrayNode);
        System.out.println("statistical units array node of the new version of subset series "+seriesId+": "+versionStatisticalUnitsArrayNode.toString());

        // If status open, also edit the statisticalUnits of the series.
        if (series.has(Field.STATISTICAL_UNITS)){
            ArrayNode seriesStatisticalUnits = (ArrayNode) series.get(Field.STATISTICAL_UNITS);
            seriesStatisticalUnits.forEach(su -> statisticalUnitMap.put(su.asText(), true));
        }

        ArrayNode newSeriesStatisticalUnitsArrayNode = new ObjectMapper().createArrayNode();
        statisticalUnitMap.forEach((k,v) -> newSeriesStatisticalUnitsArrayNode.add(k));
        System.out.println("statistical units array node of the subset series "+seriesId+" after the new version is added: "+newSeriesStatisticalUnitsArrayNode.toString());
        ObjectNode editableSeries = series.deepCopy();
        series = null;
        editableSeries.set(Field.STATISTICAL_UNITS, newSeriesStatisticalUnitsArrayNode);
        ResponseEntity<JsonNode> putSeriesRE = putSubsetSeries(seriesId, editableSeries);
        if (!putSeriesRE.getStatusCode().is2xxSuccessful()){
            LOG.error("PUT series "+seriesId+" to update the statistical units array did not succeed. Status code "+putSeriesRE.getStatusCode());
        } else {
            LOG.debug("The series "+seriesId+" was successfully updated with a new statistical units array.");
        }

        ResponseEntity<JsonNode> versionSchemaValidationRE = validateVersion(editableVersion);
        if (!versionSchemaValidationRE.getStatusCode().is2xxSuccessful())
            return versionSchemaValidationRE;

        if (versionsSize == 0) {
            LOG.debug("Since there are no versions from before, we post the new version to LDS without checking validity overlap");
            LOG.debug("Attempting to POST version nr "+versionNr+" of subset series "+seriesId+" to LDS");
            ResponseEntity<JsonNode> ldsPostRE = new LDSFacade().postVersionInSeries(seriesId, versionNr, editableVersion);
            if (!ldsPostRE.getStatusCode().is2xxSuccessful())
                return ldsPostRE;
            LOG.debug("Successfully POSTed version nr "+versionNr+" of subset series "+seriesId+" to LDS");
            return new ResponseEntity<>(editableVersion, CREATED);
        }

        ResponseEntity<JsonNode> isOverlappingValidityRE = isOverlappingValidity(editableVersion);
        if (!isOverlappingValidityRE.getStatusCode().is2xxSuccessful())
            return isOverlappingValidityRE;

        ResponseEntity<JsonNode> updateLatestPublishedValidUntilRE = updateLatestPublishedValidUntil(isOverlappingValidityRE, editableVersion, seriesId);

        LOG.debug("Attempting to POST version nr "+versionNr+" of subset series "+seriesId+" to LDS");
        ResponseEntity<JsonNode> ldsPostRE = new LDSFacade().postVersionInSeries(seriesId, versionNr, editableVersion);

        if (ldsPostRE.getStatusCode().equals(CREATED)) {
            LOG.debug("Successfully POSTed version nr "+versionNr+" of subset series "+seriesId+" to LDS");
            return new ResponseEntity<>(editableVersion, CREATED);
        } else
            return ldsPostRE;
    }

    private ResponseEntity<JsonNode> updateLatestPublishedValidUntil(ResponseEntity<JsonNode> isOverlappingValidityRE, JsonNode newVersion, String seriesId){
        JsonNode isOverlapREBody = isOverlappingValidityRE.getBody();
        if (isOverlapREBody.get("existOtherPublishedVersions").asBoolean() && isOverlapREBody.get("isNewLatestVersion").asBoolean()) {
            LOG.debug("according to the overlap check there is a previously published version, and the new version is the new latest version");
            ObjectNode latestPublishedVersion = isOverlapREBody.get("latestPublishedVersion").deepCopy();
            if (latestPublishedVersion == null)
                return ErrorHandler.newHttpError("There is supposedly other published versions, and the posted version is the new latest version, but the old latest version returned from the overlap check was null", INTERNAL_SERVER_ERROR, LOG);
            if ((!latestPublishedVersion.has(Field.VALID_UNTIL) || latestPublishedVersion.get(Field.VALID_UNTIL).isNull())) {
                LOG.debug("The latestPublishedVersion did not have a validUntil date. Attempting to set it to one day before the validFrom of the new latest version");
                LocalDate validFromLocalDate = LocalDate.parse(newVersion.get(Field.VALID_FROM).asText());
                LocalDate newValidUntilOfLastVersion = validFromLocalDate.minusDays(1);
                latestPublishedVersion.put(Field.VALID_UNTIL, newValidUntilOfLastVersion.toString());
                latestPublishedVersion.remove(Field._LINKS);
                LOG.debug("Attempting to PUT the previous latest version with a new validUntil that is one day before the validFrom of the new latest version");
                ResponseEntity<JsonNode> putVersionRE = putSubsetVersion(seriesId, latestPublishedVersion.get(Field.VERSION).asText(), latestPublishedVersion);
                if (!putVersionRE.getStatusCode().is2xxSuccessful()){
                    return ErrorHandler.newHttpError("Failed to update the validUntil of the previous last published version. PUT caused error code "+putVersionRE.getStatusCode()+" and had body "+(putVersionRE.hasBody() && putVersionRE.getBody() != null ? putVersionRE.getBody().toPrettyString().replaceAll("\n", "").replaceAll("\r", "").replaceAll("\t", "") : ""), INTERNAL_SERVER_ERROR, LOG);
                }
            } else {
                LOG.debug("The latestPublishedVersion already had a validUntil date");
            }
        } else {
            LOG.debug("Either there are no other published versions, or the published version is not the new latest version");
        }
        return new ResponseEntity<>(OK);
    }

    @GetMapping("/v2/subsets/{id}/versions")
    public ResponseEntity<JsonNode> getVersions(
            @PathVariable("id") String id,
            @RequestParam(defaultValue = "true") boolean includeFuture,
            @RequestParam(defaultValue = "true") boolean includeDrafts) {
        metricsService.incrementGETCounter();
        LOG.info("GET all versions of subset with id: "+id+" includeFuture: "+includeFuture+" includeDrafts: "+includeDrafts);

        if (!Utils.isClean(id))
            return ErrorHandler.illegalID(LOG);

        ResponseEntity<JsonNode> getSeriesByIDRE = getSubsetSeriesByID(id, false);
        if (!getSeriesByIDRE.getStatusCode().equals(OK))
            return getSeriesByIDRE;

        JsonNode body = getSeriesByIDRE.getBody();
        if (!body.has(Field.VERSIONS))
            return ErrorHandler.newHttpError("The subset series exists, but has no versions", NOT_FOUND, LOG);

        ArrayNode versions = body.get(Field.VERSIONS).deepCopy();

        for (int i = 0; i < versions.size(); i++) {
            String[] splitVersionString = versions.get(i).asText().split("/");
            String versionUID = splitVersionString[splitVersionString.length-1]; // should be "version_id"
            ResponseEntity<JsonNode> versionRE = new LDSFacade().getVersionByID(versionUID);
            if (!versionRE.getStatusCode().is2xxSuccessful()) {
                return ErrorHandler.newHttpError(
                        "A version pointed to in the 'versions' array of the series, with the UID "+versionUID+", could not be retrieved from LDS. The call returned status code "+versionRE.getStatusCode().toString()+". This might be because the version was just POSTED and the operation to store this version in LDS has not completed yet. Try again shortly.",
                        INTERNAL_SERVER_ERROR,
                        LOG);
            }
            JsonNode versionBody = versionRE.getBody();
            versionBody = Utils.addLinksToSubsetVersion(versionBody);
            versions.set(i, versionBody);
        }

        String nowDate = Utils.getNowDate();
        for (int v = versions.size() - 1; v >= 0; v--) {
            JsonNode version = versions.get(v);
            if (!includeDrafts && version.get(Field.ADMINISTRATIVE_STATUS).asText().equals(Field.DRAFT))
                versions.remove(v);
            if (!includeFuture && version.get(Field.VALID_FROM).asText().compareTo(nowDate) > 0)
                versions.remove(v);
        }

        return new ResponseEntity<>(versions, OK);
    }

    /**
     * Get a subset corresponding to a given version id.
     * @param seriesID
     * @param versionID
     * @return
     */
    @GetMapping("/v2/subsets/{id}/versions/{versionID}")
    public ResponseEntity<JsonNode> getVersion(
            @PathVariable("id") String seriesID,
            @PathVariable("versionID") String versionID) {
        metricsService.incrementGETCounter();
        LOG.info("GET version "+versionID+" of subset with id "+seriesID);

        if (!Utils.isClean(seriesID))
            return ErrorHandler.illegalID(LOG);
        if (!Utils.isClean(versionID))
            return ErrorHandler.newHttpError("Illegal version ID", BAD_REQUEST, LOG);

        String[] splitUnderscore = versionID.split("_");
        if (splitUnderscore.length < 2) {
            String versionNr = splitUnderscore[0];
            versionID = String.format("%s_%s", seriesID, versionNr);
        }
        ResponseEntity<JsonNode> versionRE = new LDSFacade().getVersionByID(versionID);
        HttpStatus status = versionRE.getStatusCode();
        if (status.equals(NOT_FOUND))
            return versionRE;
        if (!status.is2xxSuccessful())
            return resolveNonOKLDSResponse("GET version of series '"+seriesID+"' from LDS by versionId '"+versionID+"' ", versionRE);
        JsonNode versionJsonNode = versionRE.getBody();
        versionJsonNode = Utils.addLinksToSubsetVersion(versionJsonNode);
        return new ResponseEntity<>(versionJsonNode, OK);
    }

    /**
     * Get a subset corresponding to a given version id.
     * @param versionUID
     * @return
     */
    @GetMapping("/v2/ClassificationSubsetVersion/{id}")
    public ResponseEntity<JsonNode> getVersion(@PathVariable("id") String versionUID) {
        metricsService.incrementGETCounter();
        LOG.info("GET version w id '"+versionUID+"'.");

        if (!Utils.isClean(versionUID))
            return ErrorHandler.newHttpError("Illegal characters in versionUID", BAD_REQUEST, LOG);

        ResponseEntity<JsonNode> versionRE = new LDSFacade().getVersionByID(versionUID);
        HttpStatus status = versionRE.getStatusCode();
        if (status.equals(OK) || status.equals(NOT_FOUND))
            return versionRE;
        else
            return resolveNonOKLDSResponse("GET version by id '"+versionUID+"' ", versionRE);
    }

    /**
     * If no parameters are given, returns valid codes in last version.
     * If from and to parameters are given (DATES),
     * returns a list of codes that are present in all the versions in the interval.
     * In other words, returns the intersection of all the versions.
     * @param id
     * @param from
     * @param to
     * @return
     */
    @GetMapping("/v2/subsets/{id}/codes")
    public ResponseEntity<JsonNode> getSubsetCodes(@PathVariable("id") String id,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(defaultValue = "false") boolean includeDrafts,
                                                   @RequestParam(defaultValue = "false") boolean includeFuture) {
        LOG.info("GET codes of subset with id "+id);
        metricsService.incrementGETCounter();

        if (!Utils.isClean(id))
            return ErrorHandler.illegalID(LOG);

        boolean isToDate = to != null;
        boolean isFromDate = from != null;
        if ((isFromDate && !Utils.isYearMonthDay(from)) || (isToDate && !Utils.isYearMonthDay(to)))
            return ErrorHandler.newHttpError("'from' and 'to' must be on format 'YYYY-MM-DD'", BAD_REQUEST, LOG);

        if (!isFromDate && !isToDate) {
            LOG.debug("getting all codes of the latest/current version of subset "+id);
            ResponseEntity<JsonNode> versionsByIDRE = getVersions(id, includeDrafts, includeFuture);
            JsonNode responseBodyJSON = versionsByIDRE.getBody();
            if (!versionsByIDRE.getStatusCode().equals(OK))
                return resolveNonOKLDSResponse("get versions of series with id "+id+" ", versionsByIDRE);
            else {
                if (responseBodyJSON == null) {
                    return ErrorHandler.newHttpError("response body of getSubset with id " + id + " was null, so could not get codes.", INTERNAL_SERVER_ERROR, LOG);
                }
                String date = Utils.getNowDate();
                ResponseEntity<JsonNode> codesAtRE = getSubsetCodesAt(id, date, includeFuture, includeDrafts);
                if (!codesAtRE.getStatusCode().equals(OK))
                    return resolveNonOKLDSResponse("GET codesAt "+date+" in series with id "+id+" ", codesAtRE);
                ArrayNode codes = (ArrayNode) codesAtRE.getBody();
                return new ResponseEntity<>(codes, OK);
            }
        }

        // If a date interval is specified using 'from' and 'to' query parameters
        ResponseEntity<JsonNode> versionsResponseEntity = getVersions(id, includeFuture, includeDrafts);
        if (!versionsResponseEntity.getStatusCode().equals(OK))
            return versionsResponseEntity;
        JsonNode versionsResponseBodyJson = versionsResponseEntity.getBody();
        LOG.debug(String.format("Getting valid codes of subset %s from date %s to date %s", id, from, to));
        ObjectMapper mapper = new ObjectMapper();
        Map<String, ArrayNode> codeMap = new HashMap<>();

        if (versionsResponseBodyJson == null)
            return ErrorHandler.newHttpError("Response body was null", INTERNAL_SERVER_ERROR, LOG);
        if (!versionsResponseBodyJson.isArray())
            return ErrorHandler.newHttpError("Response body was null", INTERNAL_SERVER_ERROR, LOG);

        ArrayNode versionsValidInDateRange = (ArrayNode) versionsResponseBodyJson;
        int nrOfVersions = versionsValidInDateRange.size();
        LOG.debug("Nr of versions: " + nrOfVersions);

        for (int i = versionsValidInDateRange.size() - 1; i >= 0; i--) {
            JsonNode version = versionsValidInDateRange.get(i);
            if (!includeDrafts && !version.get(Field.ADMINISTRATIVE_STATUS).asText().equals(Field.OPEN)) {
                versionsValidInDateRange.remove(i);
            }
            if (isToDate && version.get(Field.VALID_FROM).asText().compareTo(to) > 0) {
                versionsValidInDateRange.remove(i);
            }
            if (isFromDate && version.has(Field.VALID_UNTIL) && version.get(Field.VALID_UNTIL).asText().compareTo(from) < 0) {
                versionsValidInDateRange.remove(i);
            }
        }

        // Check if first version includes fromDate, and last version includes toDate. If not, then return an empty list.

        for (JsonNode subsetVersion : versionsValidInDateRange) {
            LOG.debug("Version " + subsetVersion.get(Field.VERSION) + " is valid in the interval, so codes will be added to map");
            // . . . using each code in this version as key, increment corresponding integer value in map
            JsonNode codes = subsetVersion.get(Field.CODES);
            ArrayNode codesArrayNode = (ArrayNode) codes;
            LOG.debug("There are " + codesArrayNode.size() + " codes in this version");
            for (JsonNode jsonNode : codesArrayNode) {
                String classificationId = jsonNode.get(Field.CLASSIFICATION_ID).asText();
                String code = jsonNode.get(Field.CODE).asText();
                String name = jsonNode.get(Field.NAME).asText();
                String level = jsonNode.get(Field.LEVEL).asText();
                String codeURN = classificationId+"_"+code+"_"+name+"_"+level;
                ArrayNode versionsArray = jsonNode.get(Field.VERSIONS).deepCopy();
                if (codeMap.containsKey(codeURN)) {
                    versionsArray.addAll(codeMap.get(codeURN));
                }
                codeMap.put(codeURN, versionsArray);
            }
        }

        ArrayNode codesInRangeArrayNode = new ObjectMapper().createArrayNode();

        for (String s : codeMap.keySet()) {
            ArrayNode codeVersionsList = codeMap.get(s);
            ObjectNode editableCode = new ObjectMapper().createObjectNode();
            String[] splitUnderscore = s.split("_");
            String classificationId = splitUnderscore[0];
            String code = splitUnderscore[1];
            String name = splitUnderscore[2];
            String level = splitUnderscore[3];
            editableCode.put(Field.CLASSIFICATION_ID, classificationId);
            editableCode.put(Field.CODE, code);
            editableCode.put(Field.NAME, name);
            editableCode.put(Field.LEVEL, level);
            editableCode.set(Field.VERSIONS, codeVersionsList);
            codesInRangeArrayNode.add(editableCode);
        }
        //TODO: return all (variations of) codes that are valid at all the the interval [from, to]
        return new ResponseEntity<>(codesInRangeArrayNode, OK);
    }

    /**
     * Returns all codes of the subset version that is valid on the given date.
     * Assumes only one subset is valid on any given date, with no overlap of start/end date.
     * @param id
     * @param date
     * @return
     */
    @GetMapping("/v2/subsets/{id}/codesAt")
    public ResponseEntity<JsonNode> getSubsetCodesAt(@PathVariable("id") String id,
                                                     @RequestParam String date,
                                                     @RequestParam(defaultValue = "false") boolean includeFuture,
                                                     @RequestParam(defaultValue = "false") boolean includeDrafts) {
        metricsService.incrementGETCounter();
        LOG.info("GET codesAt (valid at date) "+date+" for subset with id "+id);

        if (date == null || !Utils.isClean(id) || (!Utils.isYearMonthDay(date))) {
            StringBuilder stringBuilder = new StringBuilder();
            if (date == null) {
                stringBuilder.append("date == null. ");
            } else if (!Utils.isYearMonthDay(date)) {
                stringBuilder.append("date ").append(date).append(" was wrong format");
            }
            if (!Utils.isClean(id)) {
                stringBuilder.append("id ").append(id).append(" was not clean");
            }
            return ErrorHandler.newHttpError(
                    stringBuilder.toString(),
                    BAD_REQUEST,
                    LOG);
        }

        ResponseEntity<JsonNode> versionsRE = getVersions(id, includeFuture, includeDrafts);
        if (versionsRE.getStatusCode().equals(NOT_FOUND))
            return versionsRE;
        else if (!versionsRE.getStatusCode().equals(OK))
            return resolveNonOKLDSResponse("GET versions of subset with id " + id + " ", versionsRE);

        JsonNode versionsResponseBodyJSON = versionsRE.getBody();
        if (versionsResponseBodyJSON == null)
            return ErrorHandler.newHttpError("versions response body was null", INTERNAL_SERVER_ERROR, LOG);
        if (!versionsResponseBodyJSON.isArray())
            return ErrorHandler.newHttpError("versions response body was not array", INTERNAL_SERVER_ERROR, LOG);
        LOG.debug("codesAt: We found "+versionsResponseBodyJSON.size()+" subset versions. Now we are going to find if one of them is valid at the date given.");
        ArrayNode versionsArrayNode = (ArrayNode) versionsResponseBodyJSON;
        for (JsonNode versionJsonNode : versionsArrayNode) {
            String entryValidFrom = versionJsonNode.get(Field.VALID_FROM).textValue();
            String entryValidUntil = versionJsonNode.has(Field.VALID_UNTIL) ? versionJsonNode.get(Field.VALID_UNTIL).textValue() : null;
            String versionId = versionJsonNode.get(Field.VERSION).asText();
            LOG.debug("version "+versionId+" has validFrom "+entryValidFrom+" and validUntil "+(entryValidFrom != null ? entryValidUntil : "does not exist"));
            if (entryValidFrom.compareTo(date) <= 0 && (entryValidUntil == null || entryValidUntil.compareTo(date) >= 0)) {
                LOG.debug("The date "+date+" was within the range!");
                JsonNode codes = versionJsonNode.get(Field.CODES);
                return new ResponseEntity<>(codes, OK);
            } else {
                LOG.debug("The date "+date+" was not within the range.");
            }
        }
        return new ResponseEntity<>(new ObjectMapper().createArrayNode(), OK); //TODO Maybe this should be "not found"?
    }

    @GetMapping("/v2/subsets/schema")
    public ResponseEntity<JsonNode> getSchema(){
        metricsService.incrementGETCounter();
        LOG.info("GET schema definition for subsets series");
        return new LDSFacade().getSubsetSeriesSchema();
    }

    @DeleteMapping("/v2/subsets")
    void deleteAllSeries(){
        new LDSFacade().deleteAllSubsetSeries();
    }
    @DeleteMapping("/v2/subsets/{id}")
    void deleteSeriesById(@PathVariable("id") String id){
        new LDSFacade().deleteSubsetSeries(id);
    }

    @DeleteMapping("/v2/subsets/{id}/versions/{versionId}")
    void deleteVersionById(@PathVariable("id") String id, @PathVariable("versionId") String versionId){
        LOG.info("Deleting version "+versionId+" from series "+id);
        String[] versionIdSplitUnderscore = versionId.split("_");
        String versionUID = "";
        if (versionIdSplitUnderscore.length > 1)
            versionUID = versionId;
        else
            versionUID = id+"_"+versionId;
        LOG.debug("Version UID used in delete requests to LDS: "+versionUID);
        new LDSFacade().deleteSubsetVersionFromSeriesAndFromLDS(id, versionUID);
    }

    ResponseEntity<JsonNode> validateVersion(JsonNode version){
        String versionNr = version.has(Field.VERSION) ? version.get(Field.VERSION).asText() : "with no version nr";
        String seriesID = version.has(Field.SERIES_ID) ? version.get(Field.SERIES_ID).asText() : "";
        String versionUID = seriesID+"_"+versionNr;
        LOG.debug("Validating version "+versionUID+" ");
        ResponseEntity<JsonNode> versionSchemaRE = new LDSFacade().getSubsetVersionsDefinition();
        if (!versionSchemaRE.getStatusCode().is2xxSuccessful())
            return resolveNonOKLDSResponse("Request for subset versions definition ", versionSchemaRE);
        JsonNode versionsDefinition = versionSchemaRE.getBody();
        ResponseEntity<JsonNode> schemaCheckRE = Utils.checkAgainstSchema(versionsDefinition, version, LOG);
        if (!schemaCheckRE.getStatusCode().is2xxSuccessful())
            return schemaCheckRE;
        return new ResponseEntity<>(OK);
    }

    ResponseEntity<JsonNode> validateSeries(JsonNode series){
        String id = series.has(Field.ID) ? series.get(Field.ID).asText() : " with no field 'id'";
        LOG.debug("Validating series "+id+" . . .");
        ResponseEntity<JsonNode> seriesDefinitionRE = new LDSFacade().getSubsetSeriesDefinition();
        if (!seriesDefinitionRE.getStatusCode().is2xxSuccessful())
            return resolveNonOKLDSResponse("Request for subset series definition ", seriesDefinitionRE);
        JsonNode seriesDefinition = seriesDefinitionRE.getBody();
        assert seriesDefinition != null : "series definition body was null";
        ResponseEntity<JsonNode> schemaCheckRE = Utils.checkAgainstSchema(seriesDefinition, series, LOG);
        if (!schemaCheckRE.getStatusCode().is2xxSuccessful())
            return schemaCheckRE;
        return new ResponseEntity<>(OK);
    }

    private ResponseEntity<JsonNode> compareFields(JsonNode oldVersion, JsonNode newVersion, String[] changeableFieldsArray){
        List<String> changeableFields = Arrays.asList(changeableFieldsArray.clone());
        Iterator<Map.Entry<String, JsonNode>> oldFields = oldVersion.fields();
        while (oldFields.hasNext()){
            Map.Entry<String, JsonNode> stringJsonNodeEntry = oldFields.next();
            String fieldName = stringJsonNodeEntry.getKey();
            if (!newVersion.has(fieldName) && !changeableFields.contains(fieldName))
                return ErrorHandler.newHttpError("Field '"+fieldName+"' was missing, and is not a changeable field.", BAD_REQUEST, LOG);
            JsonNode fieldValue = stringJsonNodeEntry.getValue();
            if (newVersion.has(fieldName) && !changeableFields.contains(fieldName) && !fieldValue.asText().equals(newVersion.get(fieldName).asText()))
                return ErrorHandler.newHttpError("Field '"+fieldName+"' was changed, and is not a changeable field.", BAD_REQUEST, LOG);
        }
        return new ResponseEntity<>(OK);
    }

    private static ObjectNode addLinksToSeries(JsonNode subsetSeries){
        // Replace "/ClassificationSubsetVersion/id" with "subsets/id/versions/nr"
        ObjectNode editableSeries = subsetSeries.deepCopy();
        ArrayNode versions = editableSeries.get(Field.VERSIONS).deepCopy();
        ArrayNode newVersions = new ObjectMapper().createArrayNode();
        String seriesUID = editableSeries.get(Field.ID).asText();
        for (int i = 0; i < versions.size(); i++) {
            JsonNode version = versions.get(i);
            String versionPath = version.asText(); // should be "/ClassificationSubsetVersion/{version_id}", since this is how LDS links a resource of a different type
            String[] splitBySlash = versionPath.split("/");
            assert splitBySlash[0].isBlank() : "Index 0 in the array that splits the versionPath by '/' is not blank";
            assert splitBySlash[1].equals("ClassificationSubsetVersion") : "Index 1 in the array that splits the versionPath by '/' is not 'ClassificationSubsetVersion'"; //TODO: these checks could be removed later when i know it works
            String versionUID = splitBySlash[2];
            newVersions.add(Utils.getVersionLink(seriesUID, versionUID));
        }
        editableSeries.set(Field.VERSIONS, newVersions);
        editableSeries.set(Field._LINKS, Utils.getLinkSelfObject(Utils.getSeriesLink(seriesUID)));
        return editableSeries;
    }

    /**
     *
     * @param description of what the call to LDS was
     * @param ldsRE response entity gotten from the LDS instance
     * @return
     */
    private static ResponseEntity<JsonNode> resolveNonOKLDSResponse(String description, ResponseEntity<JsonNode> ldsRE){
        String body = "NO BODY";
        if (ldsRE.hasBody())
            body = ldsRE.getBody().asText();
        body = body.replaceAll("\n", "\\n_");
        return ErrorHandler.newHttpError(
                description+" returned status code "+ldsRE.getStatusCode().toString()+". Body: "+body,
                INTERNAL_SERVER_ERROR,
                LOG);
    }

    private ResponseEntity<JsonNode> isOverlappingValidity(JsonNode editableVersion) {
        String validFrom = editableVersion.get(Field.VALID_FROM).asText();
        String validUntil = editableVersion.has(Field.VALID_UNTIL) ? editableVersion.get(Field.VALID_UNTIL).asText() : null;
        String seriesID = editableVersion.get(Field.SERIES_ID).asText();
        ResponseEntity<JsonNode> getPublishedVersionsRE = getVersions(seriesID, true, false);
        if (getPublishedVersionsRE.getStatusCode().equals(NOT_FOUND)) {
            ObjectNode body = new ObjectMapper().createObjectNode();
            body.put("message", "Subset getVersions returned 404 NOT FOUND, which means there is no overlap");
            body.put("status", OK.value());
            body.put("existOtherPublishedVersions", false);
            body.put("isNewLatestVersion", true);
            body.put("isNewFirstVersion", true);
            return new ResponseEntity<>(body, OK); // No overlap if no versions found
        }
        if (!getPublishedVersionsRE.getStatusCode().is2xxSuccessful())
            return getPublishedVersionsRE; // FIXME

        boolean isNewLatestVersion = true;
        boolean isNewFirstVersion = true;
        ArrayNode publishedSubsetVersionsArrayNode = getPublishedVersionsRE.getBody().deepCopy();

        boolean existOtherPublishedVersions = false;
        JsonNode latestPublishedVersion = null;
        if (!publishedSubsetVersionsArrayNode.isEmpty()){
            existOtherPublishedVersions = true;
            String firstValidFrom = null;
            String lastValidFrom = null;
            for (JsonNode versionJsonNode : publishedSubsetVersionsArrayNode) {
                if (versionJsonNode.get(Field.ADMINISTRATIVE_STATUS).asText().equals(Field.OPEN)) { // We only care about checking against published subset versions
                    LOG.debug("Checking version "+versionJsonNode.get(Field.SERIES_ID).asText()+"_"+versionJsonNode.get(Field.VERSION).asText()+" for overlap with the new version, since it is published.");
                    String versionValidFrom = versionJsonNode.get(Field.VALID_FROM).asText();
                    if (firstValidFrom == null || versionValidFrom.compareTo(firstValidFrom) < 0)
                        firstValidFrom = versionValidFrom;
                    if (lastValidFrom == null || versionValidFrom.compareTo(lastValidFrom) > 0) {
                        lastValidFrom = versionValidFrom;
                        latestPublishedVersion = versionJsonNode;
                    }

                    if (validFrom.compareTo(versionValidFrom) == 0)
                        return ErrorHandler.newHttpError(
                                "validFrom of a subsetVersion can not be the same as an existing published subset version's validFrom. Colliding version nr: "+versionJsonNode.get(Field.VERSION).asText(),
                                BAD_REQUEST,
                                LOG);

                    String versionValidUntil = versionJsonNode.has(Field.VALID_UNTIL) ? versionJsonNode.get(Field.VALID_UNTIL).asText() : null;

                    if (validUntil != null) {
                        // Check if validUntil of the new version is within the validity range of this old version
                        if ((validUntil.compareTo(versionValidFrom) >= 0 && (versionValidUntil == null || validUntil.compareTo(versionValidUntil) <= 0)) ||
                                (validFrom.compareTo(versionValidFrom) >= 0 && (versionValidUntil == null || validFrom.compareTo(versionValidUntil) <= 0))){
                            return ErrorHandler.newHttpError(
                                    "The new version's validUntil is within the validity range of an existing subset version. Colliding version nr: "+versionJsonNode.get(Field.VERSION).asText(),
                                    BAD_REQUEST,
                                    LOG);
                        }
                    } else { // ValidUntil is null, which is ONLY allowed to be the case if the new version is supposed to be the new latest version
                        if (versionValidFrom.compareTo(validFrom) >= 0)
                            return ErrorHandler.newHttpError(
                                    "If a validUntil is not set for a posted version, it must be the new latest subset series version ",
                                    BAD_REQUEST,
                                    LOG);
                        if (versionValidUntil != null && versionValidUntil.compareTo(validFrom) >= 0)
                            return ErrorHandler.newHttpError(
                                    "The new latest version's validFrom must be after the previous versions validUntil, if the previous version has an explicit validUntil",
                                    BAD_REQUEST,
                                    LOG);
                    }

                    //TODO: I think this whole block is now superfluous and already covered by the one above. But this one does not cover all the bases of the one above.
                    if (versionValidUntil != null && validUntil != null) {
                        if (validUntil.compareTo(versionValidUntil) <= 0 && validUntil.compareTo(versionValidFrom) >= 0)
                            return ErrorHandler.newHttpError(
                                    "The new version's validUntil is within the validity range of an existing subset version. Colliding version nr: "+versionJsonNode.get(Field.VERSION).asText(),
                                    BAD_REQUEST,
                                    LOG);
                        if (validFrom.compareTo(versionValidFrom) >= 0 && validFrom.compareTo(versionValidUntil) <= 0)
                            return ErrorHandler.newHttpError(
                                    "The new version's validFrom is within the validity range of an existing subset version. Colliding version nr: "+versionJsonNode.get(Field.VERSION).asText(),
                                    BAD_REQUEST,
                                    LOG);
                        if (validUntil.compareTo(versionValidUntil) == 0)
                            return ErrorHandler.newHttpError(
                                    "validUntil can not be the same as existing subset's validUntil, when they are explicit. Colliding version nr: "+versionJsonNode.get(Field.VERSION).asText(),
                                    BAD_REQUEST,
                                    LOG);
                    }
                }
            }
            LOG.debug("Done iterating over all existing versions of the subset to check validity period overlaps");

            if (firstValidFrom != null && validFrom.compareTo(firstValidFrom) >= 0 && lastValidFrom != null && validFrom.compareTo(lastValidFrom) <= 0)
                return ErrorHandler.newHttpError("The validity period of a new subset must be before or after all existing versions", BAD_REQUEST, LOG);

            isNewLatestVersion = lastValidFrom == null || validFrom.compareTo(lastValidFrom) > 0;
            isNewFirstVersion = firstValidFrom == null || validFrom.compareTo(firstValidFrom) < 0;


            LOG.debug("Done processing and checking new version in relation to old versions");
        }
        ObjectNode body = new ObjectMapper().createObjectNode();
        body.put("message", "Subset version had no overlap with published versions");
        body.put("status", OK.value());
        body.put("existOtherPublishedVersions", existOtherPublishedVersions);
        body.put("isNewLatestVersion", isNewLatestVersion);
        body.put("isNewFirstVersion", isNewFirstVersion);
        body.set("latestPublishedVersion", latestPublishedVersion);
        return new ResponseEntity<>(body, OK);
    }

}
