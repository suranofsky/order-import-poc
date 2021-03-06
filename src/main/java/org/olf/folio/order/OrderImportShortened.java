package org.olf.folio.order;


import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.servlet.ServletContext;
import org.json.JSONArray;
import org.json.JSONObject;
import org.marc4j.MarcJsonWriter;
import org.marc4j.MarcPermissiveStreamReader;
import org.marc4j.MarcReader;
import org.marc4j.MarcStreamReader;
import org.marc4j.MarcStreamWriter;
import org.marc4j.MarcTranslatedReader;
import org.marc4j.MarcWriter;
import org.marc4j.converter.impl.AnselToUnicode;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

public class OrderImportShortened {
	
	private static final Logger logger = Logger.getLogger(OrderImportShortened.class);
	private ServletContext myContext;
	private HashMap<String,String> lookupTable;
	private String tenant;
	
	

	
	public  JSONArray  upload(String fileName) throws IOException, InterruptedException, Exception {

		logger.info("...starting...");
		JSONArray responseMessages = new JSONArray();
		//COLLECT VALUES FROM THE CONFIGURATION FILE
		String baseOkapEndpoint = (String) getMyContext().getAttribute("baseOkapEndpoint");
		String apiUsername = (String) getMyContext().getAttribute("okapi_username");
		String apiPassword = (String) getMyContext().getAttribute("okapi_password");
		tenant = (String) getMyContext().getAttribute("tenant");
		String permLocationName = (String) getMyContext().getAttribute("permLocation");
		String permELocationName = (String) getMyContext().getAttribute("permELocation");
		String noteTypeName = (String) getMyContext().getAttribute("noteType");
		String materialTypeName = (String) getMyContext().getAttribute("materialType");
		
		//GET THE FOLIO TOKEN
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("username", apiUsername);
		jsonObject.put("password", apiPassword);
		jsonObject.put("tenant",tenant);
		String token = callApiAuth( baseOkapEndpoint + "authn/login",  jsonObject);

		//TODO: REMOVE
		logger.info("TOKEN: " + token); 
			
		
		//GET THE UPLOADED FILE
		String filePath = (String) myContext.getAttribute("uploadFilePath");
		InputStream in = null;		
		//MAKE SURE A FILE WAS UPLOADED
		InputStream is = null;
		if (fileName != null) {
			in = new FileInputStream(filePath + fileName);			
		}
		else {
			JSONObject responseMessage = new JSONObject();
			responseMessage.put("error", "no input file provided");
			responseMessage.put("PONumber", "~error~");
			responseMessages.put(responseMessage);
			return responseMessages;
		}
		
		//READ THE MARC RECORD FROM THE FILE AND VALIDATE IT
		//VALIDATES THE FUND CODE, TAG (OBJECT CODE
		MarcReader reader = new MarcStreamReader(in);
	    	Record record = null;
	    
	   	 JSONArray validateRequiredResult = validateRequiredValues(reader, token, baseOkapEndpoint);
	   	 if (!validateRequiredResult.isEmpty()) return validateRequiredResult;
	    
		//LOOKUP REFERENCE TABLES 
		//TODO
		//IMPROVE THIS - 'text' is repeated (it is a 'name' in more than one reference table)
		List<String> referenceTables = new ArrayList<String>(); 
		referenceTables.add(baseOkapEndpoint +"identifier-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "contributor-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "classification-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "contributor-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "contributor-name-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "locations?limit=10000");
		referenceTables.add(baseOkapEndpoint + "loan-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "note-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "material-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "instance-types?limit=1000");
		referenceTables.add(baseOkapEndpoint + "holdings-types?limit=1000");
		 
		 //SAVE REFERENCE TABLE VALUES (JUST LOOKUP THEM UP ONCE)
		 if (myContext.getAttribute(Constants.LOOKUP_TABLE) == null) {
				this.lookupTable = lookupReferenceValues(referenceTables,token);
				myContext.setAttribute(Constants.LOOKUP_TABLE, lookupTable);
			}
		 else {
				this.lookupTable = (HashMap<String, String>) myContext.getAttribute(Constants.LOOKUP_TABLE);
		 }
		
		//READ THE MARC RECORD FROM THE FILE
		in = new FileInputStream(filePath + fileName);
		reader = new MarcStreamReader(in);
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		MarcWriter w = new MarcStreamWriter(byteArrayOutputStream,"UTF-8");
		
		AnselToUnicode conv = new AnselToUnicode();
		w.setConverter(conv);
		
	    record = null;
		
		while (reader.hasNext()) {
			try {
				//INITIALIZE ELECTRONIC TO FALSE
				boolean electronic = false;
				
				record = reader.next();
				//GET THE 980s FROM THE MARC RECORD
				DataField twoFourFive = (DataField) record.getVariableField("245");
			    String title = twoFourFive.getSubfieldsAsString("a");
			    DataField nineEighty = (DataField) record.getVariableField("980");
			    String objectCode = nineEighty.getSubfieldsAsString("o");
			    String fundCode = nineEighty.getSubfieldsAsString("b");
			    String vendorCode =  nineEighty.getSubfieldsAsString("v");
			    String notes =  nineEighty.getSubfieldsAsString("n");
			    String quantity =  nineEighty.getSubfieldsAsString("q");
			    String price = nineEighty.getSubfieldsAsString("m");
			    String electronicIndicator = nineEighty.getSubfieldsAsString("z");
			    String vendorItemId = nineEighty.getSubfieldsAsString("c");
			    Integer quanityNo = 0; //INIT
			    if (quantity != null)  quanityNo = Integer.valueOf(quantity);
			    if (electronicIndicator != null && electronicIndicator.equalsIgnoreCase("ELECTRONIC")) electronic = true;
			    
			    
			    // GENERATE UUIDS FOR OBJECTS
			    UUID snapshotId = UUID.randomUUID();
			    UUID recordTableId = UUID.randomUUID();
			    UUID orderUUID = UUID.randomUUID();
			    UUID orderLineUUID = UUID.randomUUID();
			    
			    //NOW WE CAN START CREATING THE PO!
			    //PULL TOGETHER THE ENTIRE TITLE
			    JSONObject responseMessage = new JSONObject();
			    String titleTwo = twoFourFive.getSubfieldsAsString("b");
			    String titleThree = twoFourFive.getSubfieldsAsString("c");
			    if (titleTwo != null) title += " " + titleTwo;
			    if (titleThree != null) title += " " + titleThree;
			    //PUT THE TITLE IN THE RESPONSE MESSAGE
			    responseMessage.put("title", title);

				//LOOK UP VENDOR 
				String organizationEndpoint = baseOkapEndpoint + "organizations-storage/organizations?limit=30&offset=0&query=((code='" + vendorCode + "'))";
				String orgLookupResponse = callApiGet(organizationEndpoint,  token);
				JSONObject orgObject = new JSONObject(orgLookupResponse);
				String vendorId = (String) orgObject.getJSONArray("organizations").getJSONObject(0).get("id");
				//LOOK UP THE FUND
				String fundEndpoint = baseOkapEndpoint + "finance/funds?limit=30&offset=0&query=((code='" + fundCode + "'))";
				String fundResponse = callApiGet(fundEndpoint, token);
				JSONObject fundsObject = new JSONObject(fundResponse);
				String fundId = (String) fundsObject.getJSONArray("funds").getJSONObject(0).get("id");
				
				//GET THE NEXT PO NUMBER
				String poNumber = callApiGet(baseOkapEndpoint + "orders/po-number", token);
				JSONObject poNumberObj = new JSONObject(poNumber);
				logger.info("NEXT PO NUMBER: " + poNumberObj.get("poNumber"));
				
				// CREATING THE PURCHASE ORDER
				JSONObject order = new JSONObject();
				order.put("poNumber", poNumberObj.get("poNumber"));
				order.put("vendor", vendorId);
				order.put("orderType", "One-Time");
				order.put("reEncumber", false);
				order.put("id", orderUUID.toString());
				order.put("approved", true);
				order.put("workflowStatus","Open");
				
				// POST ORDER LINE
				//FOLIO WILL CREATE THE INSTANCE, HOLDINGS, ITEM (IF PHYSICAL ITEM)
				JSONObject orderLine = new JSONObject();
				JSONObject cost = new JSONObject();
				JSONObject location = new JSONObject();
				JSONArray locations = new JSONArray();
				JSONArray poLines = new JSONArray();
				if (electronic) {
					orderLine.put("orderFormat", "Electronic Resource");
					JSONObject eResource = new JSONObject();
					eResource.put("activated", false);
					eResource.put("createInventory", "Instance, Holding");
					eResource.put("trial", false);
					eResource.put("accessProvider", vendorId);
					orderLine.put("eresource",eResource);
					orderLine.put("orderFormat", "Electronic Resource");
					cost.put("quantityElectronic", 1);
					cost.put("listUnitPriceElectronic", price);
					location.put("quantityElectronic",quanityNo);
					location.put("locationId",lookupTable.get(permELocationName + "-location"));
					locations.put(location);
				}	
				else {
					JSONObject physical = new JSONObject();
					physical.put("createInventory", "Instance, Holding, Item");
					physical.put("materialType", lookupTable.get(materialTypeName));
					orderLine.put("physical", physical);
					orderLine.put("orderFormat", "Physical Resource");
					cost.put("listUnitPrice", price);
					cost.put("quantityPhysical", 1);
					location.put("quantityPhysical",quanityNo);
					location.put("locationId",lookupTable.get(permLocationName + "-location"));
					locations.put(location);
				}
				
				//VENDOR REFERENCE NUMBER IF INCLUDED IN THE MARC RECORD:
				if (vendorItemId != null) {
					JSONObject vendorDetail = new JSONObject();
					vendorDetail.put("instructions", "");
					vendorDetail.put("refNumber", vendorItemId);
					vendorDetail.put("refNumberType", "Internal vendor number");
					vendorDetail.put("vendorAccount", "");
					orderLine.put("vendorDetail", vendorDetail);
				}
				
				//TAG FOR THE PO LINE
				if (objectCode != null) {
					JSONArray tagList = new JSONArray();
					tagList.put(objectCode);
					JSONObject tags = new JSONObject();
					tags.put("tagList", tagList);
					orderLine.put("tags", tags);
				}
				
				orderLine.put("id", orderLineUUID);
				orderLine.put("source", "User");
				cost.put("currency", "USD");
				orderLine.put("cost", cost);
				orderLine.put("locations", locations);
				orderLine.put("titleOrPackage",title);
				orderLine.put("acquisitionMethod", "Purchase");
				JSONArray funds = new JSONArray();
				JSONObject fundDist = new JSONObject();
				fundDist.put("distributionType", "percentage");
				fundDist.put("value", 100);
				fundDist.put("fundId", fundId);
				funds.put(fundDist);
				orderLine.put("fundDistribution", funds);
				orderLine.put("purchaseOrderId", orderUUID.toString());
				poLines.put(orderLine);
				order.put("compositePoLines", poLines);
				
				//POST THE ORDER AND LINE:
				String orderResponse = callApiPostWithUtf8(baseOkapEndpoint + "orders/composite-orders",order,token); 
				JSONObject approvedOrder = new JSONObject(orderResponse);
				logger.info(orderResponse);
				
				//INSERT THE NOTE IF THERE IS A NOTE IN THE MARC RECORD
				if (notes != null && !notes.equalsIgnoreCase("")) {
					logger.info("NOTE TYPE NAME: " + noteTypeName);
					logger.info(lookupTable);
					JSONObject noteAsJson = new JSONObject();
					JSONArray links = new JSONArray();
					JSONObject link = new JSONObject();
					link.put("type","poLine");
					link.put("id", orderLineUUID);
					links.put(link);
					noteAsJson.put("links", links);
					noteAsJson.put("typeId", lookupTable.get(noteTypeName));
					noteAsJson.put("domain", "orders");
					noteAsJson.put("content", notes);
					noteAsJson.put("title", notes);
					String noteResponse = callApiPostWithUtf8(baseOkapEndpoint + "/notes",noteAsJson,token); 
					logger.info(noteResponse);
				}
								
				//GET THE UPDATED PURCHASE ORDER FROM THE API AND PULL OUT THE ID FOR THE INSTANCE FOLIO CREATED:
				String updatedPurchaseOrder = callApiGet(baseOkapEndpoint + "orders/composite-orders/" +orderUUID.toString() ,token); 
				JSONObject updatedPurchaseOrderJson = new JSONObject(updatedPurchaseOrder);
				String instanceId = updatedPurchaseOrderJson.getJSONArray("compositePoLines").getJSONObject(0).getString("instanceId");
				
				//GET THE INSTANCE RECORD FOLIO CREATED, SO WE CAN ADD BIB INFO TO IT:
				String instanceResponse = callApiGet(baseOkapEndpoint + "inventory/instances/" + instanceId, token);
				JSONObject instanceAsJson = new JSONObject(instanceResponse);
				String hrid = instanceAsJson.getString("hrid");
				
				//PREPARING TO ADD THE MARC RECORD TO SOURCE RECORD STORAGE:
				//CONSTRUCTING THE 999 OF THE MARC RECORD for FOLIO: 
				DataField field = MarcFactory.newInstance().newDataField();
				field.setTag("999");
				field.setIndicator1('f');
				field.setIndicator2('f');
				Subfield one = MarcFactory.newInstance().newSubfield('i', instanceId);
				Subfield two = MarcFactory.newInstance().newSubfield('s',recordTableId.toString());
				field.addSubfield(one);
				field.addSubfield(two);
				record.addVariableField(field);
			    if (record.getControlNumberField() != null) {
			    	record.getControlNumberField().setData(hrid);
			    }
			    else {
			    	ControlField cf = MarcFactory.newInstance().newControlField("001");
			    	cf.setData(hrid);
			    	record.addVariableField(cf);
			    }
				//TRANSFORM THE RECORD INTO JSON
				logger.info("MARC RECORD: " + record.toString());
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				MarcJsonWriter jsonWriter =  new MarcJsonWriter(baos);
				jsonWriter.setUnicodeNormalization(true);
				jsonWriter.write(record);
				jsonWriter.close();
				String jsonString = baos.toString();
				JSONObject mRecord = new JSONObject(jsonString);
				JSONObject content = new JSONObject();
				content.put("content",mRecord);
				logger.info("MARC TO JSON: " + mRecord);

				//GET THE RAW MARC READY TO POST TO THE API
				ByteArrayOutputStream rawBaos = new ByteArrayOutputStream();
				MarcWriter writer = new MarcStreamWriter(rawBaos);
				writer.write(record);
				JSONObject jsonWithRaw = new JSONObject();
				jsonWithRaw.put("id", instanceId);
				jsonWithRaw.put("content",byteArrayOutputStream);
				
				//CREATING JOB EXECUTION?
				//TODO: I'M NOT ENTIRELY SURE IF THIS IS NECESSARY?
				//WHAT THE CONSEQUENCES OF THIS ARE?
				//TO POST TO SOURCE RECORD STORAGE, A SNAPSHOT ID
				//SEEMS TO BE REQUIRECD
				JSONObject jobExecution = new JSONObject();
				jobExecution.put("jobExecutionId", snapshotId.toString());
				jobExecution.put("status", "PARSING_IN_PROGRESS");
				String snapShotResponse = callApiPostWithUtf8(baseOkapEndpoint + "source-storage/snapshots",  jobExecution,token);
				
				//OBJECT FOR SOURCE RECORD STORAGE API CALL:
				JSONObject sourceRecordStorageObject = new JSONObject();
				sourceRecordStorageObject.put("recordType", "MARC");
				sourceRecordStorageObject.put("snapshotId",snapshotId.toString());
				sourceRecordStorageObject.put("matchedId", instanceId.toString());
				//LINK THE INSTANCE TO SOURCE RECORD STORAGE
				JSONObject externalId = new JSONObject();
				externalId.put("instanceId",instanceId);
				sourceRecordStorageObject.put("externalIdsHolder", externalId);
				//RAW RECORD
				JSONObject rawRecordObject = new JSONObject();
				rawRecordObject.put("id",instanceId);
				rawRecordObject.put("content",jsonWithRaw.toString());
				//PARSED RECORD
				JSONObject parsedRecord = new JSONObject();
				parsedRecord.put("id", instanceId);
				parsedRecord.put("content", mRecord);
				sourceRecordStorageObject.put("rawRecord", rawRecordObject);
				sourceRecordStorageObject.put("parsedRecord", parsedRecord);
				sourceRecordStorageObject.put("id", instanceId);
				//CALL SOURCE RECORD STORAGE POST
				String storageResponse = callApiPostWithUtf8(baseOkapEndpoint + "source-storage/records", sourceRecordStorageObject,token);
				
				
				//ADD IDENTIFIERS AND CONTRIBUTORS TO THE INSTANCE
				//*AND* CHANGE THE SOURCE TO 'MARC'
				//SO THE OPTION TO VIEW THE MARC RECORD SHOWS UP 
				//IN INVENTORY!
				JSONArray identifiers = buildIdentifiers(record,lookupTable);
				JSONArray contributors = buildContributors(record, lookupTable);
				instanceAsJson.put("title", title);
				instanceAsJson.put("source", "MARC");
				instanceAsJson.put("instanceTypeId", lookupTable.get("text"));
				instanceAsJson.put("identifiers", identifiers);
				instanceAsJson.put("contributors", contributors);
				instanceAsJson.put("discoverySuppress", false);
				
				
				//GET THE HOLDINGS RECORD FOLIO CREATED, SO WE CAN ADD URLs FROM THE 856 IN THE MARC RECORD
				String holdingResponse = callApiGet(baseOkapEndpoint + "holdings-storage/holdings?query=(instanceId==" + instanceId + ")", token);
				JSONObject holdingsAsJson = new JSONObject(holdingResponse);
				JSONObject holdingRecord = holdingsAsJson.getJSONArray("holdingsRecords").getJSONObject(0);
				
				JSONArray eResources = new JSONArray();
				String linkText = (String) getMyContext().getAttribute("textForElectronicResources");
				List urls =  record.getVariableFields("856");
				Iterator<DataField> iterator = urls.iterator();
				while (iterator.hasNext()) {
					DataField dataField = (DataField) iterator.next();
					if (dataField != null && dataField.getSubfield('u') != null) {
						String url = dataField.getSubfield('u').getData();
						if (dataField.getSubfield('z') != null) {
							linkText = dataField.getSubfield('z').getData();
						}
						JSONObject eResource = new JSONObject();
						eResource.put("uri", dataField.getSubfield('u').getData());
						//TODO - DO WE WANT TO CHANGE THE LINK TEXT?
						eResource.put("linkText", linkText);
						//I 'THINK' THESE RELATIONSHIP TYPES ARE HARDCODED INTO FOLIO
						//CANT BE LOOKED UP WITH AN API?
						//https://github.com/folio-org/mod-inventory-storage/blob/master/reference-data/electronic-access-relationships/resource.json
						eResource.put("relationshipId", "f5d0068e-6272-458e-8a81-b85e7b9a14aa");
						eResources.put(eResource);
					}
				}

				
				//UPDATE THE INSTANCE RECORD
				instanceAsJson.put("electronicAccess", eResources);
				instanceAsJson.put("natureOfContentTermIds", new JSONArray());
				instanceAsJson.put("precedingTitles", new JSONArray());
				instanceAsJson.put("succeedingTitles", new JSONArray());
				String instanceUpdateResponse = callApiPut(baseOkapEndpoint + "inventory/instances/" + instanceId,  instanceAsJson,token);
				
				//UPDATE THE HOLDINGS RECORD
				holdingRecord.put("electronicAccess", eResources);
				//IF THIS WAS AN ELECTRONIC RECORD, MARK THE HOLDING AS EHOLDING
				if (electronic) {
					holdingRecord.put("holdingsTypeId",this.lookupTable.get("Electronic"));
				}
				String createHoldingsResponse = callApiPut(baseOkapEndpoint + "holdings-storage/holdings/" + holdingRecord.getString("id"), holdingRecord,token);
				
				//SAVE THE PO NUMBER FOR THE RESPONSE
				responseMessage.put("PONumber", poNumberObj.get("poNumber"));
				responseMessage.put("theOne", hrid);
				
				responseMessages.put(responseMessage);
			}
			catch(Exception e) {
				logger.fatal(e.toString());
				JSONObject responseMessage = new JSONObject();
				responseMessage.put("error",e.toString());
				responseMessage.put("PONumber", "~error~");
				responseMessages.put(responseMessage);
				return responseMessages;
			}
		}
	    
		
		return responseMessages;

	}
	
	
	public  HashMap<String,String> lookupReferenceValues(List<String> lookupTables,String token) throws IOException, InterruptedException, Exception  {
		Map<String, String> lookUpTable = new HashMap<String,String>();

		Iterator<String> lookupTablesIterator = lookupTables.iterator();
		while (lookupTablesIterator.hasNext()) {
			String endpoint = lookupTablesIterator.next();
			String response = callApiGet(endpoint,token);
			JSONObject jsonObject = new JSONObject(response);
			//TODO - IMPROVE THIS
			//(0) IS THE NUMBER OF ITEMS FOUND
			//(1) IS THE ARRAY OF ITEMS
			//NOT A GOOD APPROACH
			String elementName = (String) jsonObject.names().get(1);	
			JSONArray elements = jsonObject.getJSONArray(elementName);
			Iterator elementsIterator = elements.iterator();
			while (elementsIterator.hasNext()) {
				JSONObject element = (JSONObject) elementsIterator.next();
				String id = element.getString("id");
				String name = element.getString("name");
				if (endpoint.contains("locations")) name = name + "-location";
				//SAVING ALL OF THE 'NAMES' SO THE UUIDs CAN BE LOOKED UP
				lookUpTable.put(name,id);		
			}
		}

		return (HashMap<String, String>) lookUpTable;
	}
	
	


	
	public JSONArray validateRequiredValues(MarcReader reader,String token, String baseOkapEndpoint ) {
		
	    Record record = null;
	    JSONArray responseMessages = new JSONArray();
		while(reader.hasNext()) {
				try {
			    	record = reader.next();    					    
			    	//GET THE 980s FROM THE MARC RECORD
				    DataField nineEighty = (DataField) record.getVariableField("980");
				    
				    DataField twoFourFive = (DataField) record.getVariableField("245");
				    String title = twoFourFive.getSubfieldsAsString("a");
				    //REMOVED - NOT NEEDED String theOne = ((ControlField) record.getVariableField("001")).getData();
				    
					if (nineEighty == null) {
						JSONObject responseMessage = new JSONObject();
						responseMessage.put("error", "Record is missing the 980 field");
						responseMessage.put("PONumber", "~error~");
						responseMessage.put("title", title);
						//responseMessage.put("theOne", theOne);
						responseMessages.put(responseMessage);
						continue;
					}
			    	
					
					String objectCode = nineEighty.getSubfieldsAsString("o");
				    String fundCode = nineEighty.getSubfieldsAsString("b");
				    String vendorCode =  nineEighty.getSubfieldsAsString("v");
				    String notes =  nineEighty.getSubfieldsAsString("n");
				    String quantity =  nineEighty.getSubfieldsAsString("q");
				    String price = nineEighty.getSubfieldsAsString("m");
				    String electronicIndicator = nineEighty.getSubfieldsAsString("z");
				    String vendorItemId = nineEighty.getSubfieldsAsString("c");
				    Integer quanityNo = 0;
				    if (quantity != null)  quanityNo = Integer.valueOf(quantity);

				    
				    Map<String, String> requiredFields = new HashMap<String, String>();
				    requiredFields.put("Object code",objectCode);
				    requiredFields.put("Fund code",fundCode);
				    requiredFields.put("Vendor Code",vendorCode);
				    requiredFields.put("Price" , price);
				    
				    // MAKE SURE EACH OF THE REQUIRED SUBFIELDS HAS DATA
			        for (Map.Entry<String,String> entry : requiredFields.entrySet())  {
			        	if (entry.getValue()==null) {
			        		JSONObject responseMessage = new JSONObject();
			        		responseMessage.put("title", title);
			        		//responseMessage.put("theOne", theOne);
						    responseMessage.put("error", entry.getKey() + " Missing");
							responseMessage.put("PONumber", "~error~");
							responseMessages.put(responseMessage);
			        	}
			        }
			        
			        if (!responseMessages.isEmpty()) return responseMessages;
			        

				    
				    //VALIDATE THE ORGANIZATION, OBJECT CODE AND FUND
				    //STOP THE PROCESS IF AN ERRORS WERE FOUND
				    JSONObject orgValidationResult = validateOrganization(vendorCode, title, token, baseOkapEndpoint);
				    if (orgValidationResult != null) responseMessages.put(orgValidationResult);
				    JSONObject objectValidationResult = validateObjectCode(objectCode, title, token, baseOkapEndpoint);
				    if (objectValidationResult != null) responseMessages.put(objectValidationResult);
				    JSONObject fundValidationResult = validateFund(fundCode, title, token, baseOkapEndpoint, price);
				    if (fundValidationResult != null) responseMessages.put(fundValidationResult);
				    return responseMessages;
				    
				}

	    
		    catch(Exception e) {
		    	logger.fatal(e.getMessage());
		    	JSONObject responseMessage = new JSONObject();
		    	responseMessage.put("error", e.getMessage());
		    	responseMessage.put("PONumber", "~error~");
		    	responseMessages.put(responseMessage);
		    }
		}
		return responseMessages;
		
	}
	
	
	//TODO - FIX THESE METHODS THAT GATHER DETAILS FROM THE MARC RECORD.
	//THEY WERE HURRILY CODED
	//JUST WANTED TO GET SOME DATA IN THE INSTANCE
	//FROM THE MARC RECORD FOR THIS POC
	public JSONArray buildContributors(Record record,HashMap<String,String> lookupTable) {
		JSONArray contributors = new JSONArray();
		List fields = record.getDataFields();
		Iterator fieldsIterator = fields.iterator();
		while (fieldsIterator.hasNext()) {
			DataField field = (DataField) fieldsIterator.next();
			if (field.getTag().equalsIgnoreCase("100") || field.getTag().equalsIgnoreCase("700")) {
				contributors.put(makeContributor(field,lookupTable,"Personal name", new String[]{"a","b","c","d","f","g","j","k","l","n","p","t","u"}));
			}
		}
		return contributors;
	}
	
	public JSONObject makeContributor( DataField field, HashMap<String,String> lookupTable, String name_type_id, String[] subfieldArray) {
		List<String> list = Arrays.asList(subfieldArray);
		JSONObject contributor = new JSONObject();
		contributor.put("name", "");
		contributor.put("contributorNameTypeId", lookupTable.get(name_type_id));
		List subfields =  field.getSubfields();
		Iterator subfieldIterator = subfields.iterator();
		String contributorName = "";
		while (subfieldIterator.hasNext()) {
			Subfield subfield = (Subfield) subfieldIterator.next();
			String subfieldAsString = String.valueOf(subfield.getCode());  
			if (subfield.getCode() == '4') {
				if (lookupTable.get(subfield.getData()) != null) {
					contributor.put("contributorTypeId", lookupTable.get(subfield.getData()));
				}
				else {
					contributor.put("contributorTypeId", lookupTable.get("bkp"));
				}
			}
			else if (subfield.getCode() == 'e') {
				contributor.put("contributorTypeText", subfield.getData());
			}
			else if (list.contains(subfieldAsString)) {
				if (!contributorName.isEmpty()) {
					contributorName += ", " + subfield.getData();
				}
				else {
					contributorName +=  subfield.getData();
				}
			}
			
		}
		contributor.put("name", contributorName);
		return contributor;
	}
	
	
   public JSONArray buildIdentifiers(Record record,HashMap<String,String> lookupTable) {
		JSONArray identifiers = new JSONArray();
		
		List fields = record.getDataFields();
		Iterator fieldsIterator = fields.iterator();
		while (fieldsIterator.hasNext()) {
			DataField field = (DataField) fieldsIterator.next();
			System.out.println(field.getTag());
			List subfields =  field.getSubfields();
			Iterator subfieldIterator = subfields.iterator();
			while (subfieldIterator.hasNext()) {
				Subfield subfield = (Subfield) subfieldIterator.next();
				if (field.getTag().equalsIgnoreCase("020")) {
					if (subfield.getCode() == 'a') {
						JSONObject identifier = new JSONObject();
						String fullValue = subfield.getData();
						if (field.getSubfield('c') != null) fullValue += " "  + field.getSubfieldsAsString("c");
						if (field.getSubfield('q') != null) fullValue += " " + field.getSubfieldsAsString("q");
						identifier.put("value",fullValue);
						
						identifier.put("identifierTypeId", lookupTable.get("ISBN"));
						identifiers.put(identifier);
					}
					if (subfield.getCode() == 'z') {
						JSONObject identifier = new JSONObject();
						String fullValue = subfield.getData();
						if (field.getSubfield('c') != null) fullValue += " " + field.getSubfieldsAsString("c");
						if (field.getSubfield('q') != null) fullValue += " " + field.getSubfieldsAsString("q");
						identifier.put("value", fullValue);
						identifier.put("identifierTypeId", lookupTable.get("Invalid ISBN"));
						identifiers.put(identifier);
					}
				}
				if (field.getTag().equalsIgnoreCase("022")) {
					if (subfield.getCode() == 'a') {
						JSONObject identifier = new JSONObject();
						String fullValue = subfield.getData();
						if (field.getSubfield('c') != null) fullValue += " " + field.getSubfieldsAsString("c");
						if (field.getSubfield('q') != null) fullValue += " " + field.getSubfieldsAsString("q");
						identifier.put("value",fullValue);
						
						identifier.put("identifierTypeId", lookupTable.get("ISSN"));
						identifiers.put(identifier);
					}
					else if (subfield.getCode() == 'l') {
						JSONObject identifier = new JSONObject();
						String fullValue = subfield.getData();
						if (field.getSubfield('c') != null) fullValue += " " + field.getSubfieldsAsString("c");
						if (field.getSubfield('q') != null) fullValue += " " + field.getSubfieldsAsString("q");
						identifier.put("value", fullValue);
						identifier.put("identifierTypeId", lookupTable.get("Linking ISSN"));
						identifiers.put(identifier);
					}
					else {
						JSONObject identifier = new JSONObject();
						String fullValue = "";
						if (field.getSubfield('z') != null) fullValue += field.getSubfieldsAsString("z");
						if (field.getSubfield('y') != null) fullValue += " " +  field.getSubfieldsAsString("y");
						if (field.getSubfield('m') != null) fullValue += " " + field.getSubfieldsAsString("m");
						if (fullValue != "") {
							identifier.put("value", fullValue);
							identifier.put("identifierTypeId", lookupTable.get("Invalid ISSN"));
							identifiers.put(identifier);
						}
					}
				}
				
				
			}
			
		}
		return identifiers;
		
		
	}
	
	
	
	public String callApiGet(String url, String token) throws Exception, IOException, InterruptedException {
				CloseableHttpClient client = HttpClients.custom().build();
				HttpUriRequest request = RequestBuilder.get().setUri(url)
				.setHeader("x-okapi-tenant", tenant)
				.setHeader("x-okapi-token", token)
				.setHeader("Accept", "application/json")
				.setHeader("content-type","application/json")
				.build();


				HttpResponse response = client.execute(request);
				HttpEntity entity = response.getEntity();
				String responseString = EntityUtils.toString(entity, "UTF-8");
				int responseCode = response.getStatusLine().getStatusCode();

				logger.info("GET:");
				logger.info(url);
				logger.info(responseCode);
				logger.info(responseString);

				if (responseCode > 399) {
					throw new Exception(responseString);
				}

				return responseString;

	}

	//POST TO PO SEEMS TO WANT UTF8 (FOR SPECIAL CHARS)
	//IF UTF8 IS USED TO POST TO SOURCE RECORD STORAGE
	//SPECIAL CHARS DON'T LOOK CORRECT 
	//TODO - combine the two post methods
	public String callApiPostWithUtf8(String url, JSONObject body, String token)
			throws Exception, IOException, InterruptedException {
		CloseableHttpClient client = HttpClients.custom().build();
		HttpUriRequest request = RequestBuilder.post()
				.setUri(url)
				.setHeader("x-okapi-tenant", tenant)
				.setHeader("x-okapi-token", token)
				.setEntity(new StringEntity(body.toString(),"UTF-8"))
				.setHeader("Accept", "application/json")
				.setHeader("content-type","application/json")
				.build();

		HttpResponse response = client.execute(request);
		HttpEntity entity = response.getEntity();
		String responseString = EntityUtils.toString(entity, "UTF-8");
		int responseCode = response.getStatusLine().getStatusCode();

		logger.info("POST:");
		logger.info(body.toString());
		logger.info(url);
		logger.info(responseCode);
		logger.info(responseString);

		if (responseCode > 399) {
			throw new Exception(responseString);
		}

		return responseString;

	}

	

	
	
	
	
	public String callApiPut(String url, JSONObject body, String token)
			throws Exception, IOException, InterruptedException {
		CloseableHttpClient client = HttpClients.custom().build();
		HttpUriRequest request = RequestBuilder.put()
				.setUri(url)
				.setCharset(Charset.defaultCharset())
				.setEntity(new StringEntity(body.toString(),"UTF8"))
				.setHeader("x-okapi-tenant", tenant)
				.setHeader("x-okapi-token", token)
				.setHeader("Accept", "application/json")
				.setHeader("Content-type","application/json")
				.build();
		
		//TODO
		//UGLY WORK-AROUND
		//THE ORDERS-STORAGE ENDOINT WANTS 'TEXT/PLAIN'
		//THE OTHER API CALL THAT USES PUT,
		//WANTS 'APPLICATION/JSON'
		if (url.contains("orders-storage") || url.contains("holdings-storage")) {
			request.setHeader("Accept","text/plain");
		}
		HttpResponse response = client.execute(request);
		int responseCode = response.getStatusLine().getStatusCode();

		logger.info("PUT:");
		logger.info(body.toString());
		logger.info(url);
		logger.info(responseCode);
		//logger.info(responseString);

		if (responseCode > 399) {
			throw new Exception("Response: " + responseCode);
		}

		return "ok";

	}
	
	public  String callApiAuth(String url,  JSONObject  body)
			throws Exception, IOException, InterruptedException {
		    CloseableHttpClient client = HttpClients.custom().build();
		    HttpUriRequest request = RequestBuilder.post()
		    		.setUri(url)
		    		.setEntity(new StringEntity(body.toString()))
					.setHeader("x-okapi-tenant",tenant)
					.setHeader("Accept", "application/json").setVersion(HttpVersion.HTTP_1_1)
					.setHeader("content-type","application/json")
					.build();

		    CloseableHttpResponse response = client.execute(request);
			HttpEntity entity = response.getEntity();
			String responseString = EntityUtils.toString(entity);
			int responseCode = response.getStatusLine().getStatusCode();

			logger.info("POST:");
			logger.info(body.toString());
			logger.info(url);
			logger.info(responseCode);
			logger.info(responseString);

			if (responseCode > 399) {
				throw new Exception(responseString);
			}

			
			String token = response.getFirstHeader("x-okapi-token").getValue();
			return token;

	}


	public ServletContext getMyContext() {
		return myContext;
	}


	public void setMyContext(ServletContext myContext) {
		this.myContext = myContext;
	}
	
	static String readFile(String path, Charset encoding)  throws IOException  {
		  byte[] encoded = Files.readAllBytes(Paths.get(path));
		  return new String(encoded, encoding);
	}
	

	//TODO 
	//THESE VALIDATION METHODS COULD
	//USE IMPROVEMENT
	public JSONObject validateFund(String fundCode, String title, String token, String baseOkapiEndpoint, String price ) throws IOException, InterruptedException, Exception {
		
		//GET CURRENT FISCAL YEAR
		String fiscalYearCode =  (String) getMyContext().getAttribute("fiscalYearCode");
		String fundEndpoint = baseOkapiEndpoint + "finance/funds?limit=30&offset=0&query=((code='" + fundCode + "'))";
		
		JSONObject responseMessage = new JSONObject();
		
		String fundResponse = callApiGet(fundEndpoint, token);
		JSONObject fundsObject = new JSONObject(fundResponse);
		//----------->VALIDATION #1: MAKE SURE THE FUND CODE EXISTS
		if (fundsObject.getJSONArray("funds").length() < 1) {
			responseMessage.put("error", "Fund code in file (" + fundCode + ") does not exist in FOLIO");
			responseMessage.put("PONumber", "~error~");
			return responseMessage;
		}
		String fundId = (String) fundsObject.getJSONArray("funds").getJSONObject(0).get("id");
		logger.info("FUNDS: " + fundsObject.get("funds"));
		
		//----------->VALIDATION #2: MAKE SURE THE FUND CODE FOR THE CURRENT FISCAL HAS ENOUGH MONEY
		String fundBalanceQuery = baseOkapiEndpoint + "finance/budgets?query=(name=="  + fundCode + "-" + fiscalYearCode + ")";
		String fundBalanceResponse = callApiGet(fundBalanceQuery, token);
		JSONObject fundBalanceObject = new JSONObject(fundBalanceResponse);
		if (fundBalanceObject.getJSONArray("budgets").length() < 1) {
			responseMessage.put("error", "Fund code in file (" + fundCode + ") does not have a budget");
			responseMessage.put("title", title);
			responseMessage.put("PONumber", "~error~");
			return responseMessage;
		}
		//REMOVED ON 8-28 - NOW THAT THE BUDGETS CAN BE OVERSPENT
		/*Iterator budgetsIterator = fundBalanceObject.getJSONArray("budgets").iterator();
		boolean foundAGoodBudget = false;
		while (budgetsIterator.hasNext()) {
			JSONObject budget = (JSONObject) budgetsIterator.next();
			if (budget.getString("budgetStatus") == null || !budget.getString("budgetStatus").equalsIgnoreCase("active")) continue;
			BigDecimal availableMoney = budget.getBigDecimal("available");
			BigDecimal wantedToSpend = new BigDecimal(price);
			if (availableMoney.compareTo(wantedToSpend) > 0)  foundAGoodBudget = true;
		}
		
		if (!foundAGoodBudget) {
			responseMessage.put("error", "Fund code in file (" + fundCode + ") does not an active budget with enough money to open a purchase order which will encumber funds.  Using fund code: " + fundCode);
			responseMessage.put("title", title);
			responseMessage.put("theOne", id);
			responseMessage.put("PONumber", "~error~");
			return responseMessage;
		}*/
		//END REMOVED 8-28 BECAUSE BUDGETS CAN BE OVERSPENT
		return null;
	}
	
	public JSONObject validateObjectCode(String objectCode, String title, String token, String baseOkapiEndpoint ) throws IOException, InterruptedException, Exception {
		//---------->VALIDATION: MAKE SURE THE TAG (AKA OBJECT CODE) EXISTS
		JSONObject responseMessage = new JSONObject();
		String tagEndpoint = baseOkapiEndpoint + "tags?query=(label==" + objectCode + ")";
		String tagResponse = callApiGet(tagEndpoint,  token);
		JSONObject tagObject = new JSONObject(tagResponse);
		if (tagObject.getJSONArray("tags").length() < 1) {
			responseMessage.put("error", "Object code in the record (" + objectCode + ") does not exist in FOLIO");
			responseMessage.put("title", title);
			responseMessage.put("PONumber", "~error~");
			return responseMessage;
		}
		return null;
	}
	
	public JSONObject validateOrganization(String orgCode, String title,  String token, String baseOkapiEndpoint ) throws IOException, InterruptedException, Exception {
		JSONObject responseMessage = new JSONObject();
	    //LOOK UP THE ORGANIZATION
	    String organizationEndpoint = baseOkapiEndpoint + "organizations-storage/organizations?limit=30&offset=0&query=((code='" + orgCode + "'))";
	    String orgLookupResponse = callApiGet(organizationEndpoint,  token);
		JSONObject orgObject = new JSONObject(orgLookupResponse);
		//---------->VALIDATION: MAKE SURE THE ORGANIZATION CODE EXISTS
		if (orgObject.getJSONArray("organizations").length() < 1) {
			responseMessage.put("error", "Organization code in file (" + orgCode + ") does not exist in FOLIO");
			responseMessage.put("title", title);
			responseMessage.put("PONumber", "~error~");
			return responseMessage;
		}
		return null;
	}
	

}
