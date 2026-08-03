/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *
 */

package org.wso2.ei.dashboard.micro.integrator.delegates;

import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.wso2.ei.dashboard.core.commons.Constants;
import org.wso2.ei.dashboard.core.commons.utils.HttpUtils;
import org.wso2.ei.dashboard.core.commons.utils.ManagementApiUtils;
import org.wso2.ei.dashboard.core.data.manager.DataManager;
import org.wso2.ei.dashboard.core.data.manager.DataManagerSingleton;
import org.wso2.ei.dashboard.core.exception.ManagementApiException;
import org.wso2.ei.dashboard.core.rest.delegates.ArtifactDelegate;
import org.wso2.ei.dashboard.core.rest.model.Ack;
import org.wso2.ei.dashboard.core.rest.model.ArtifactUpdateRequest;
import org.wso2.ei.dashboard.core.rest.model.ArtifactsResourceResponse;
import org.wso2.ei.dashboard.core.rest.model.DataServiceFaultDetails;
import org.wso2.ei.dashboard.micro.integrator.commons.DelegatesUtil;
import org.wso2.ei.dashboard.micro.integrator.commons.Utils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Delegate class to handle requests from data-services page.
 */
public class DataServicesDelegate implements ArtifactDelegate {
    private static final Log logger = LogFactory.getLog(DataServicesDelegate.class);
    private final DataManager dataManager = DataManagerSingleton.getDataManager();

    @Override
    public ArtifactsResourceResponse getPaginatedArtifactsResponse(String groupId, List<String> nodeList,
        String searchKey, String lowerLimit, String upperLimit, String order, String orderBy, String isUpdate)
        throws ManagementApiException {
        DelegatesUtil.logDebugLogs(Constants.DATA_SERVICES, groupId, lowerLimit, upperLimit, order, orderBy, isUpdate);
        return DelegatesUtil.getPaginatedArtifactResponse(groupId, nodeList, Constants.DATA_SERVICES,
            searchKey, lowerLimit, upperLimit, order, orderBy, isUpdate);
    }

    public DataServiceFaultDetails getDataServiceFaultDetails(String groupId, String nodeId, String serviceName)
            throws ManagementApiException {
        logger.debug("Fetching fault details for data service from management console");
        String mgtApiUrl = ManagementApiUtils.getMgtApiUrl(groupId, nodeId);
        String accessToken = dataManager.getAccessToken(groupId, nodeId);
        try {
            String encodedServiceName = URLEncoder.encode(serviceName, StandardCharsets.UTF_8.name()).replace("+", "%20");
            String url = mgtApiUrl.concat("data-services/").concat(encodedServiceName).concat("/fault");
            try (CloseableHttpResponse httpResponse = Utils.doGet(groupId, nodeId, accessToken, url)) {
                int statusCode = httpResponse.getStatusLine().getStatusCode();
                if (statusCode != 200) {
                    throw new ManagementApiException(
                            "Error while retrieving fault details for data service: upstream returned " + statusCode,
                            statusCode);
                }
                JsonObject jsonResponse = HttpUtils.getJsonResponse(httpResponse);
                DataServiceFaultDetails faultDetails = new DataServiceFaultDetails();
                faultDetails.setServiceName(jsonResponse.has("serviceName") ? jsonResponse.get("serviceName").getAsString() : null);
                faultDetails.setErrorMessage(jsonResponse.has("errorMessage") ? jsonResponse.get("errorMessage").getAsString() : null);
                faultDetails.setFaultStackTrace(jsonResponse.has("faultStackTrace") ? jsonResponse.get("faultStackTrace").getAsString() : null);
                return faultDetails;
            }
        } catch (IOException e) {
            throw new ManagementApiException("Error while retrieving fault details for data service", 500);
        }
    }

    @Override
    public Ack updateArtifact(String groupId, ArtifactUpdateRequest request) {

        return null;
    }
}
