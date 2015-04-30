/*
 *
 *  Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package apim.restful.exportimport;


import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.xml.stream.XMLStreamException;

import apim.restful.exportimport.utils.ArchiveGenerator;
import com.google.gson.*;
import org.apache.axiom.om.OMElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.*;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.RegistryType;
import org.wso2.carbon.registry.api.Registry;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.registry.api.Resource;

import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.utils.CarbonUtils;

@Path("/")
public class APIService {
	APIProvider provider;
	APIIdentifier apiIdentifier;
	String baseAPIArchivePath;
	Registry registry;
	private static final Log log = LogFactory.getLog(APIService.class);

	@GET
	@Path("/export-api/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.MULTIPART_FORM_DATA)
	public Response getAPI(@PathParam("id") String id) throws APIExportException{

		//initializing provider
		setProvider();

		log.info("Retrieving API for API-Id : " + id);
		String name = id.substring(0, id.indexOf("-"));
		String version = id.substring(id.indexOf("-") + 1, id.lastIndexOf("-"));
		String providerName = id.substring(id.lastIndexOf("-") + 1, id.length());
		apiIdentifier = new APIIdentifier(providerName, name, version);
		API apiToReturn = null;
		String swagger = "";
		String tempAPIName = name+"-"+version;
		//registry for the current user
		getRegistry();
		try {
			//directory creation
			baseAPIArchivePath = "/Users/thilinicooray/Desktop/" +
			                                                 tempAPIName;
			//where
			// should
			// archive save?
			createDirectory(baseAPIArchivePath);

			apiToReturn = provider.getAPI(apiIdentifier);

			//retrieving thumbnail
			if(getAPIIcon()){
				apiToReturn.setThumbnailUrl(null);
			}

			//retrieving documents
			List<Documentation> docList = provider.getAllDocumentation(apiIdentifier);
			getAPIDocumentation(docList);

			//retrieving wsdl - error when manually adding
			String wsdlUrl = apiToReturn.getWsdlUrl();
			if (wsdlUrl != null) {
				if (getWSDL()) {
					apiToReturn.setWsdlUrl(null);
				}
			}

			//retrieving sequences
			Map<String,String> sequences = new HashMap<String,String>();
			sequences.put("in",apiToReturn.getInSequence());
			sequences.put("out",apiToReturn.getOutSequence());
			sequences.put("fault",apiToReturn.getFaultSequence());
			getSequences(sequences);


			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			// convert java object to JSON format,
			// and return as JSON formatted string
			String json = gson.toJson(apiToReturn);

			createDirectory(baseAPIArchivePath+"/Meta-information");

			//write converted json data to a file named "file.json"
			FileWriter writer = new FileWriter
					(baseAPIArchivePath+"/Meta-information/"+name+"-"+version+
					".json");
			writer.write(json);
			writer.close();

			ArchiveGenerator.zipIt(baseAPIArchivePath+".zip",baseAPIArchivePath);

		} catch (APIManagementException e) {
			log.error("Error while getting API" + e.toString());
			throw new APIExportException("Error while getting API", e);
		} catch (IOException e) {
			log.error(e.getMessage());
			throw new APIExportException(
					"I/O error while writing API Meta information to file :" + baseAPIArchivePath +
					"/Meta-information/" + name + "-" + version +
					".json", e);
		}

		return Response.ok(swagger).build();    //?
	}

	/**
	 * Initialize the logged in provider
	 *
	 * @throws APIExportException If an error occurs while retrieving current context
	 */
	private void setProvider() throws APIExportException{
		try {
			if (provider == null) {
				String currentProviderName = CarbonContext.getCurrentContext().getUsername();
				provider = APIManagerFactory.getInstance().getAPIProvider(currentProviderName);
			}
		} catch (APIManagementException e) {
			log.error("Error while retrieving " +e.toString());
			throw new APIExportException("Error while retrieving current provider",e);
		}
	}

	/**
	 * Retrieve registry of the logged in tenant
	 */
	private void getRegistry(){
		registry = CarbonContext.getCurrentContext().getRegistry(RegistryType.SYSTEM_GOVERNANCE);
	}

	/**
	 * Retrieve thumbnail image for the exporting API and store it in the archive directory
	 *
	 * @return whether thumbnail retrieval succeeded
	 * @throws APIExportException  If an error occurs while retrieving image from the registry or
	 * storing in the archive directory
	 */
	private boolean getAPIIcon() throws APIExportException {
		String thumbnailUrl = APIConstants.API_IMAGE_LOCATION + RegistryConstants.PATH_SEPARATOR +
		                      apiIdentifier.getProviderName() + RegistryConstants.PATH_SEPARATOR +
		                      apiIdentifier.getApiName() + RegistryConstants.PATH_SEPARATOR +
		                      apiIdentifier.getVersion() + RegistryConstants.PATH_SEPARATOR +
		                      APIConstants.API_ICON_IMAGE;
		try {
			if (registry.resourceExists(thumbnailUrl)) {
				Resource icon = registry.get(thumbnailUrl);
				InputStream imageDataStream = null;

				imageDataStream = icon.getContentStream();

				String mimeType = icon.getMediaType();   //how to set mimetype of receiving image

				OutputStream os = null;

				createDirectory(baseAPIArchivePath + "/Image");

				os = new FileOutputStream(baseAPIArchivePath + "/Image/icon.jpg");

				byte[] b = new byte[2048];
				int length;

				while ((length = imageDataStream.read(b)) != -1) {
					os.write(b, 0, length);
				}

				imageDataStream.close();
				os.close();
				return true;
			}
		} catch (IOException e) {
			log.error("I/O error while writing API Thumbnail to file" + e.toString());
			throw new APIExportException(
					"I/O error while writing API Thumbnail to file :" + baseAPIArchivePath +
					"/Image/icon.jpg", e);
		} catch (RegistryException e) {
			log.error("Error while retrieving Thumbnail " + e.toString());
			throw new APIExportException("Error while retrieving Thumbnail", e);
		}
		return false;
	}

	/**
	 * Retrieve documentation for the exporting API and store it in the archive directory
	 * FILE, INLINE and URL documentations are handled
	 *
	 * @param docList  documentation list of the exporting API
	 * @throws APIExportException  If an error occurs while retrieving documents from the
	 * registry or storing in the archive directory
	 */
	private void getAPIDocumentation(List<Documentation> docList) throws APIExportException {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		createDirectory(baseAPIArchivePath + "/Docs");

		try {
			for (Documentation doc : docList) {
				String sourceType = doc.getSourceType().name();

				if (sourceType.equalsIgnoreCase(Documentation.DocumentSourceType.FILE.toString())) {
					String fileName =
							doc.getFilePath().substring(doc.getFilePath().lastIndexOf("/") + 1);
					String filePath = APIUtil.getDocumentationFilePath(apiIdentifier, fileName);

					//check whether resource exists in the registry
					Resource docFile = registry.get(filePath);
					String localFilePath = "/Docs/" + fileName;
					OutputStream os = new FileOutputStream(baseAPIArchivePath + localFilePath);
					InputStream fileInputStream = docFile.getContentStream();
					byte[] b = new byte[2048];
					int length;

					while ((length = fileInputStream.read(b)) != -1) {
						os.write(b, 0, length);
					}

					fileInputStream.close();
					os.close();
					doc.setFilePath(localFilePath);
				}
			}

			String json = gson.toJson(docList);
			FileWriter writer = new FileWriter(baseAPIArchivePath + "/Docs/docs.json");
			writer.write(json);
			writer.close();

		} catch (IOException e) {
			log.error("I/O error while writing API documentation to file" + e.toString());
			throw new APIExportException("I/O error while writing API documentation to file", e);
		} catch (RegistryException e) {
			log.error("Error while retrieving documentation " + e.toString());
			throw new APIExportException("Error while retrieving documentation", e);
		}
	}

	/**
	 * Retrieve WSDL for the exporting API and store it in the archive directory
	 *
	 * @return whether WSDL retrieval succeeded
	 * @throws APIExportException If an error occurs while retrieving WSDL from the registry or
	 * storing in the archive directory
	 */
	private boolean getWSDL() throws APIExportException {
		try {
			String wsdlPath =
					APIConstants.API_WSDL_RESOURCE_LOCATION + apiIdentifier.getProviderName() +
					"--" + apiIdentifier.getApiName() + apiIdentifier.getVersion() + ".wsdl";
			if (registry.resourceExists(wsdlPath)) {
				createDirectory(baseAPIArchivePath + "/WSDL");

				Resource wsdl = registry.get(wsdlPath);
				InputStream wsdlStream = null;
				try {
					wsdlStream = wsdl.getContentStream();
				} catch (RegistryException e) {
					e.printStackTrace();
				}

				OutputStream os;

				os = new FileOutputStream(
						baseAPIArchivePath + "/WSDL/" + apiIdentifier.getApiName() + "-" +
						apiIdentifier.getVersion() + ".wsdl");

				byte[] b = new byte[2048];
				int length;

				while ((length = wsdlStream.read(b)) != -1) {
					os.write(b, 0, length);
				}

				wsdlStream.close();
				os.close();
				return true;

			}
		} catch (IOException e) {
			log.error("I/O error while writing WSDL to file" + e.toString());
			throw new APIExportException("I/O error while writing WSDL to file", e);
		} catch (RegistryException e) {
			log.error("Error while retrieving WSDL " + e.toString());
			throw new APIExportException("Error while retrieving WSDL", e);
		}
		return false;
	}

	/**
	 * Retrieve available custom sequences for the exporting API
	 *
	 * @param sequences custom sequences of the exporting API
	 * @throws APIExportException If an error occurs while retrieving sequences from registry
	 */
	private void getSequences(Map<String, String> sequences) throws APIExportException {
		if (sequences.size() > 0) {
			int tenantId = CarbonContext.getCurrentContext().getTenantId();
			createDirectory(baseAPIArchivePath + "/Sequences");

			try {
				String sequenceName = null;
				String direction = null;
				OMElement sequenceConfig = null;
				for (Map.Entry<String, String> sequence : sequences.entrySet()) {
					sequenceName = sequence.getValue();
					direction = sequence.getKey();
					if (sequenceName != null) {
						if (direction.equalsIgnoreCase("in")) {
							sequenceConfig =
									APIUtil.getCustomSequence(sequenceName, tenantId, "in");
							exportSequence(sequenceConfig, sequenceName, "in");
						} else if (direction.equalsIgnoreCase("out")) {
							sequenceConfig =
									APIUtil.getCustomSequence(sequenceName, tenantId, "out");
							exportSequence(sequenceConfig, sequenceName, "out");
						} else {
							sequenceConfig =
									APIUtil.getCustomSequence(sequenceName, tenantId, "fault");
							exportSequence(sequenceConfig, sequenceName, "fault");
						}
					}
				}
			} catch (APIManagementException e) {
				log.error("Error while retrieving custom sequence" + e.toString());
				throw new APIExportException("Error while retrieving custom sequence", e);

			}
		}
	}

	/**
	 * Store custom sequences in the archive directory
	 *
	 * @param sequenceConfig   Sequence configuration
	 * @param sequenceName     Sequence name
	 * @param direction        Direction of the sequence "in", "out" or "fault"
	 * @throws APIExportException  If an error occurs while serializing XML stream or storing in
	 * archive directory
	 */
	private void exportSequence(OMElement sequenceConfig, String sequenceName, String direction)
			throws APIExportException {
		OutputStream outputStream = null;
		try {
			createDirectory(baseAPIArchivePath + "/Sequences/" + direction + "-sequence");
			outputStream = new FileOutputStream(
					baseAPIArchivePath + "/Sequences/" + direction + "-sequence/" + sequenceName +
					"" +
					".xml");
			sequenceConfig.serialize(outputStream);
		} catch (FileNotFoundException e) {
			log.error("Unable to find file" + e.toString());
			throw new APIExportException(
					"Unable to find file: " + baseAPIArchivePath + "/Sequences/" + direction +
					"-sequence/" + sequenceName + "" +
					".xml", e);
		} catch (XMLStreamException e) {
			log.error("Error while processing XML stream" + e.toString());
			throw new APIExportException("Error while processing XML stream", e);
		}
	}

	/**
	 * Create directory at the given path
	 *
	 * @param path Path of the directory
	 * @throws APIExportException  If directory creation failed
	 */
	private void createDirectory(String path) throws APIExportException {
		if(path!=null) {
			File file = new File(path);
			if (!file.exists()) {
				if (!file.mkdirs()) {
					log.error("Error while creating directory : " + path);
					throw new APIExportException("Directory creation failed " + path);
				}
			}
		}
	}

}
