package org.edu_sharing.service.mediacenter;

import java.io.InputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.namespace.QName;
import org.apache.log4j.Logger;
import org.edu_sharing.alfrescocontext.gate.AlfAppContextGate;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.edu_sharing.repository.server.tools.ApplicationInfoList;
import org.edu_sharing.service.authority.AuthorityServiceFactory;
import org.edu_sharing.service.util.CSVTool;
import org.springframework.context.ApplicationContext;

public class MediacenterServiceImpl implements MediacenterService{
	
	Logger logger = Logger.getLogger(MediacenterServiceImpl.class);
	
	ApplicationContext  applicationContext = AlfAppContextGate.getApplicationContext();
	ServiceRegistry serviceregistry = (ServiceRegistry)applicationContext.getBean(ServiceRegistry.SERVICE_REGISTRY);
	AuthorityService authorityService = serviceregistry.getAuthorityService();
	NodeService nodeService = serviceregistry.getNodeService();
	org.edu_sharing.alfresco.service.AuthorityService eduAuthorityService = (org.edu_sharing.alfresco.service.AuthorityService)applicationContext.getBean("eduAuthorityService");
	
	@Override
	public int importMediacenters(InputStream csv) {
		RunAsWork<Integer> runAs = new RunAsWork<Integer>() {
			@Override
			public Integer doWork() throws Exception {
				List<List<String>> records = new CSVTool().getRecords(csv, CSVTool.ENC_ISO);

				int counter = 0;
				for (List<String> record : records) {

					String mzId = record.get(0);
					String mz = record.get(1);
					String plz = record.get(2);
					String ort = record.get(3);

					try {
						
						String authorityName = CCConstants.MEDIA_CENTER_GROUP_TYPE + "_" + mzId;
						logger.info("creating:" + authorityName);
						
						if(authorityService.authorityExists("GROUP_" + authorityName)) {
							logger.info("authority already exists:" + authorityName);
							continue;
						}
						
						String alfAuthorityName = authorityService.createAuthority(AuthorityType.GROUP, authorityName);
						authorityService.setAuthorityDisplayName(alfAuthorityName, mz);
						
						//admin group
						//authorityService.createGroupWithType(AuthorityService.ADMINISTRATORS_GROUP, profile.getDisplayName() + AuthorityService.ADMINISTRATORS_GROUP_DISPLAY_POSTFIX, group, AuthorityService.MEDIACENTER_ADMINISTRATORS_GROUP_TYPE);
						AuthorityServiceFactory.getLocalService().createGroupWithType(
								org.edu_sharing.alfresco.service.AuthorityService.ADMINISTRATORS_GROUP, 
								mz + org.edu_sharing.alfresco.service.AuthorityService.ADMINISTRATORS_GROUP_DISPLAY_POSTFIX, 
								authorityName, 
								org.edu_sharing.alfresco.service.AuthorityService.MEDIACENTER_ADMINISTRATORS_GROUP_TYPE);
						
						NodeRef authorityNodeRef = authorityService.getAuthorityNodeRef(alfAuthorityName);
						
						Map<QName, Serializable> groupExtProps = new HashMap<QName, Serializable>();
						groupExtProps.put(QName.createQName(CCConstants.CCM_PROP_GROUPEXTENSION_GROUPTYPE), CCConstants.MEDIA_CENTER_GROUP_TYPE);
						nodeService.addAspect(authorityNodeRef, QName.createQName(CCConstants.CCM_ASPECT_GROUPEXTENSION), groupExtProps);
						
						Map<QName, Serializable> groupAddressProps = new HashMap<QName, Serializable>();
						groupAddressProps.put(QName.createQName(CCConstants.CCM_PROP_ADDRESS_POSTALCODE), plz);
						groupAddressProps.put(QName.createQName(CCConstants.CCM_PROP_ADDRESS_CITY), ort);
						nodeService.addAspect(authorityNodeRef, QName.createQName(CCConstants.CCM_ASPECT_ADDRESS), groupAddressProps);
						
						Map<QName, Serializable> groupMZProps = new HashMap<QName, Serializable>();
						groupMZProps.put(QName.createQName(CCConstants.CCM_PROP_MEDIACENTER_ID), mzId);
						groupMZProps.put(QName.createQName(CCConstants.CCM_PROP_ADDRESS_CITY), ort);
						nodeService.addAspect(authorityNodeRef, QName.createQName(CCConstants.CCM_ASPECT_MEDIACENTER), groupMZProps);
						
						counter++;
					} catch (Exception e) {
						logger.error("error in record: " + ((record == null || record.size() < 1)?null:record.get(0)), e);
						throw e;
					}
				}
				return counter;
			}
		};

		return AuthenticationUtil.runAs(runAs, ApplicationInfoList.getHomeRepository().getUsername());
	}

}
